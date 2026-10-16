package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * THE THREE STANDARDISED MESSAGES the aircraft can broadcast, held by this application.
 *
 * ⚠ THE APPLICATION OWNS THE LIBRARY, THE SPEAKER OWNS ONE SLOT. The AS1 holds exactly one
 * sound. Its name is hard-coded inside MSDK as `megaphone_file`, and `startPlay()` re-selects
 * that name on every play, so there is nothing on the aircraft to choose between. Measured:
 * `SpeakerKey.KeyAudioFileList` is refused at all ten component indexes. A "list of
 * messages" therefore has to live HERE, and playing one means uploading it — see
 * [SpeakerBroadcast].
 *
 * ⚠ THE AUDIO FORMAT IS NOT NEGOTIABLE and it is why messages are RECORDED on the controller
 * rather than shipped with the application. The speaker takes RAW OPUS PACKETS — 16 kHz, mono,
 * 16 kbps CBR, 40 ms frames, concatenated with no Ogg container, no `OpusHead`, no length
 * prefixes. An `.opus` or `.ogg` file from ffmpeg is Ogg-encapsulated 48 kHz VBR and will not
 * play. The only two producers of the right bytes are DJI's own `OpusEncoder` (which
 * [SpeakerRecorder] drives, and which DJI's `MegaphoneVM` sample tees straight to a file exactly
 * this way) and `PCMTools`, whose output DJI's own documentation deprecates at MSDK 5.14.0 and
 * whose `convertToOpusFileSync` mis-frames the tail of any PCM that is not a multiple of 2560
 * bytes. So: the recorder is the source of truth.
 *
 * At 16 kbps the arithmetic is simple and worth keeping in one place: **2 KB per second of
 * audio**, 80 bytes per 40 ms packet. A ten-second message is about 20 KB.
 */
object SpeakerMessages {

    private const val TAG = "SpeakerMessages"

    /**
     * This feature owns its preferences file, following [ArSettings]'s precedent rather than
     * joining the app-wide `takpilot2_tak`. Messages are content, not aircraft configuration.
     */
    private const val PREFS = "takpilot2_speaker"

    private const val DIR_NAME = "speaker"

    /** Slot numbers. Three, because three pills is what the 280dp panel holds. */
    val SLOTS = 1..3

    /**
     * ⚠ DERIVED FROM THE PILL, NOT CHOSEN. The panel is 280dp wide with 14dp padding each side,
     * so three pills and two 8dp gaps leave about 78dp each, which is 8 uppercase characters at
     * 12sp bold in this device's bucket. "EVACUATE" is exactly 8. Raising this means smaller
     * text or a wider panel, and the panel must not pass a third of the screen.
     */
    const val MAX_LABEL_CHARS = 8

    /**
     * ⚠ A CAP ON HOW LONG THE PILOT WAITS, not on how much can be stored. Every play re-uploads
     * the whole message, so its length IS the delay before the aircraft speaks. 30 s is 60 KB.
     */
    const val MAX_DURATION_MS = 30_000L

    /** Below this a "recording" is a slip of the finger, not a message. */
    const val MIN_DURATION_MS = 700L

    /** 16 kbps CBR. The one place this arithmetic is written down. */
    const val BYTES_PER_SECOND = 2000

    /** 40 ms of 16 kHz mono at 16 kbps. A whole file is a multiple of this. */
    const val PACKET_BYTES = 80

    /** Same reserve and the same reason as [DtedStore]: filling internal storage in flight. */
    private const val FREE_SPACE_RESERVE_BYTES = 500L * 1024 * 1024

    /** One recorded message. [file] is guaranteed to exist and be non-empty at construction. */
    data class Message(
        val slot: Int,
        val label: String,
        val durationMs: Long,
        val bytes: Long,
        val recordedAtMs: Long,
        val file: File,
    )

    /** `msg_s1_label`, `msg_s2_duration_ms`, … — the shape `TakConnectActivity.vKey` uses. */
    private fun mKey(slot: Int, base: String) = "msg_s${slot}_$base"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Created lazily, like [DtedStore.dir]. */
    fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        // An earlier test harness recorded into slot 0, outside SLOTS, so it could never
        // overwrite a real message. The harness is gone; this clears anything it left behind.
        File(d, "message_s0.opus").delete()
        File(d, "take_s0.part").delete()
        return d
    }

    /**
     * ⚠ FIXED FILENAMES, ONE PER SLOT. Nothing user-supplied ever reaches a path, so the
     * path-traversal question that DTED imports raise cannot arise here at all — it is
     * solved by construction rather than by sanitising a name.
     */
    fun fileFor(context: Context, slot: Int): File = File(dir(context), "message_s$slot.opus")

    /**
     * The last volume the pilot chose, remembered across screens and app restarts.
     *
     * ⚠ THIS IS FOR DISPLAY, AND IT IS NOT WRITTEN TO THE AIRCRAFT ON ENTRY. The speaker keeps
     * its own volume perfectly well — a flight log shows it reporting 100 across
     * every screen cycle — so re-applying it would be an unasked-for write to a payload for no
     * gain. What this fixes is the SLIDER showing 0 in the moment before the aircraft answers,
     * which is what made a working setting look like a lost one.
     */
    fun savedVolume(context: Context): Int =
        prefs(context).getInt("speaker_volume", 100).coerceIn(0, 100)

    fun saveVolume(context: Context, volume: Int) {
        prefs(context).edit().putInt("speaker_volume", volume.coerceIn(0, 100)).apply()
    }

    /** The default label for an unnamed slot. */
    fun defaultLabel(slot: Int) = "MSG $slot"

    /**
     * What is in a slot, or null when it is empty.
     *
     * ⚠ THE FILE IS RE-CHECKED ON EVERY CALL, never trusted from the preferences alone. A
     * preference row is a claim; the file is the message. If the two disagree the file wins,
     * because a pill offering a message whose audio is gone is a button that fails at the moment
     * it matters.
     */
    fun saved(context: Context, slot: Int): Message? {
        val file = fileFor(context, slot)
        if (!file.exists() || file.length() <= 0L) return null
        val p = prefs(context)
        return Message(
            slot = slot,
            label = p.getString(mKey(slot, "label"), null)?.takeIf { it.isNotBlank() }
                ?: defaultLabel(slot),
            durationMs = p.getLong(mKey(slot, "duration_ms"), 0L),
            bytes = file.length(),
            recordedAtMs = p.getLong(mKey(slot, "recorded_at"), 0L),
            file = file,
        )
    }

    /** Every recorded slot, in slot order. Empty slots are absent, not placeholders. */
    fun all(context: Context): List<Message> = SLOTS.mapNotNull { saved(context, it) }

    /** The label a slot shows, recorded or not — the Pre-Flight screen needs both cases. */
    fun label(context: Context, slot: Int): String =
        prefs(context).getString(mKey(slot, "label"), null)?.takeIf { it.isNotBlank() }
            ?: defaultLabel(slot)

    /** Trimmed, upper-cased and capped. A label is a pill face, not free text. */
    fun saveLabel(context: Context, slot: Int, label: String) {
        val clean = label.trim().replace(Regex("\\s+"), " ")
            .take(MAX_LABEL_CHARS).uppercase()
        prefs(context).edit().putString(mKey(slot, "label"), clean).apply()
        AppLog.i(TAG, "slot $slot label = '$clean'")
    }

    /**
     * Moves a finished recording into [slot].
     *
     * ⚠ THE PREFERENCES ARE WRITTEN LAST, AFTER the file is in place. The other order leaves a
     * window where a crash produces a preference row pointing at a file that does not exist —
     * which [saved] would survive, but only because it re-checks. Belt and braces.
     */
    fun store(context: Context, slot: Int, source: File, durationMs: Long): Boolean {
        if (!source.exists() || source.length() <= 0L) {
            AppLog.w(TAG, "slot $slot: nothing to store, the take is empty")
            return false
        }
        if (durationMs < MIN_DURATION_MS) {
            AppLog.w(TAG, "slot $slot: the take is only ${durationMs}ms — discarded")
            return false
        }
        val target = fileFor(context, slot)
        val part = File(target.parentFile, target.name + ".part")
        return try {
            source.copyTo(part, overwrite = true)
            // Atomic swap, the same discipline DtedStore uses: a half-written message is never
            // visible under the real name.
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                AppLog.w(TAG, "slot $slot: rename failed")
                return false
            }
            prefs(context).edit()
                .putLong(mKey(slot, "duration_ms"), durationMs)
                .putLong(mKey(slot, "recorded_at"), System.currentTimeMillis())
                .apply()
            AppLog.i(TAG, "slot $slot stored: ${target.length()}B ${durationMs}ms " +
                "(${target.length() % PACKET_BYTES} bytes past a whole packet)")
            true
        } catch (t: Throwable) {
            AppLog.w(TAG, "slot $slot store failed: ${t.message}")
            part.delete()
            false
        }
    }

    /** Removes the audio and the metadata. The label is kept — it is the pilot's wording. */
    fun delete(context: Context, slot: Int) {
        fileFor(context, slot).delete()
        prefs(context).edit()
            .remove(mKey(slot, "duration_ms"))
            .remove(mKey(slot, "recorded_at"))
            .apply()
        AppLog.i(TAG, "slot $slot deleted")
    }

    /** True when there is room to record another [MAX_DURATION_MS] message. */
    fun hasRoom(context: Context): Boolean {
        val free = dir(context).usableSpace
        val need = MAX_DURATION_MS / 1000 * BYTES_PER_SECOND
        return free - need > FREE_SPACE_RESERVE_BYTES
    }

    /**
     * A cheap shape test on a file that claims to be a message.
     *
     * ⚠ NOT A VALIDATOR, AND NOT YET A GATE. At 16 kbps CBR with 40 ms frames every packet is
     * [PACKET_BYTES], so a whole file divides exactly — and an Ogg-wrapped file from ffmpeg
     * almost never will, which is what makes this worth logging. The packet size is INFERRED
     * from the encoder's `createEncoder(16000, 1, 16000)` and CBR arithmetic, not measured, so
     * for now this only WARNS. Promote it to a rejection once a real recording is confirmed to
     * divide by 80.
     */
    fun looksLikeRawOpus(file: File): Boolean = file.length() > 0 && file.length() % PACKET_BYTES == 0L

    /** Duration implied by the byte count, for cross-checking the wall clock. */
    fun durationFromBytes(bytes: Long): Long = bytes / PACKET_BYTES * 40L

    // ============================ EXPORT AND IMPORT ============================
    //
    // ⚠ A FLEET NEEDS THE SAME WORDS ON EVERY CONTROLLER. Recording is per-controller, so
    // without this each aircraft in a fleet says something slightly different — which defeats
    // the point of a STANDARDISED message. One controller records; the rest import.
    //
    // ⚠ THE BUNDLE IS OUR OWN FORMAT AND IT ACCEPTS NOTHING ELSE. The speaker takes raw Opus
    // packets and nothing on a controller can tell that from an Ogg file by looking at the name.
    // Importing arbitrary audio would mean a validator nobody can trust and a message that fails
    // silently the first time it matters. A `.tp2msg` holds audio this application produced, and
    // the manifest says so.

    private const val BUNDLE_SCHEMA = 1
    private const val BUNDLE_AUDIO = "message.opus"
    private const val BUNDLE_MANIFEST = "message.json"

    /** The file name offered when exporting, e.g. `EVACUATE.tp2msg`. */
    fun bundleName(context: Context, slot: Int): String {
        val safe = label(context, slot).replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
        return (if (safe.isEmpty()) "message$slot" else safe) + ".tp2msg"
    }

    /**
     * Writes slot [slot] to [out] as a bundle. Returns null on success, or a reason.
     *
     * The manifest carries the label and the audio's shape, so an import can refuse a bundle
     * from a future version rather than feeding the speaker something it will not play.
     */
    fun exportTo(context: Context, slot: Int, out: OutputStream): String? {
        val message = saved(context, slot) ?: return "There is no message in that slot"
        return try {
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(BUNDLE_MANIFEST))
                zip.write(
                    ("{\"schema\":$BUNDLE_SCHEMA," +
                        "\"label\":\"${message.label.replace("\"", "")}\"," +
                        "\"durationMs\":${message.durationMs}," +
                        "\"bytes\":${message.bytes}," +
                        "\"sampleRate\":16000,\"bitrate\":16000,\"frameMs\":40}")
                        .toByteArray()
                )
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(BUNDLE_AUDIO))
                message.file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            AppLog.i(TAG, "slot $slot exported: '${message.label}' ${message.bytes}B")
            null
        } catch (t: Throwable) {
            AppLog.w(TAG, "slot $slot export failed: ${t.message}")
            "Could not write the file: ${t.message}"
        }
    }

    /**
     * Reads a bundle from [input] into [slot]. Returns null on success, or a reason.
     *
     * ⚠ THE AUDIO IS CHECKED FOR SHAPE BEFORE IT IS KEPT. At 16 kbps CBR with 40 ms frames a
     * whole file divides by [PACKET_BYTES]; an Ogg-wrapped file from ffmpeg will not. This was a
     * warning until a real recording confirmed the packet size (244 packets in 19520B,
     * exactly), and it is a refusal now — a message that fails at the moment
     * it matters is worse than one that never imported.
     */
    fun importFrom(context: Context, slot: Int, input: InputStream): String? {
        var audio: ByteArray? = null
        var manifest: String? = null
        try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        BUNDLE_AUDIO -> audio = zip.readBytes()
                        BUNDLE_MANIFEST -> manifest = String(zip.readBytes())
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "import failed to read: ${t.message}")
            return "That file is not a TAKPilot2 message"
        }

        // ⚠ EVERY REFUSAL LOGS. Three of these paths used to return a reason without a log line,
        // so a rejected import left NO trace at all — a pilot reported "no error displayed"
        // and the log could not even confirm the attempt had happened.
        val bytes = audio
        if (bytes == null) {
            AppLog.w(TAG, "import refused: no $BUNDLE_AUDIO inside — not a TAKPilot2 message")
            return "That file is not a TAKPilot2 message"
        }
        val text = manifest
        if (text == null) {
            AppLog.w(TAG, "import refused: no $BUNDLE_MANIFEST inside")
            return "That file is not a TAKPilot2 message"
        }
        val schema = Regex("\"schema\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (schema == null || schema > BUNDLE_SCHEMA) {
            AppLog.w(TAG, "import refused: schema $schema, this app understands $BUNDLE_SCHEMA")
            return "That message was made by a newer version of this app"
        }
        if (bytes.isEmpty() || bytes.size % PACKET_BYTES != 0) {
            AppLog.w(TAG, "import refused: ${bytes.size}B is not whole packets")
            return "That audio is not in the form the speaker accepts"
        }
        if (!hasRoom(context)) {
            AppLog.w(TAG, "import refused: not enough space")
            return "Not enough space on the controller"
        }

        val target = fileFor(context, slot)
        val part = File(target.parentFile, target.name + ".part")
        return try {
            part.writeBytes(bytes)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) { part.delete(); return "Could not save the message" }
            val label = Regex("\"label\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
            if (!label.isNullOrBlank()) saveLabel(context, slot, label)
            prefs(context).edit()
                .putLong(mKey(slot, "duration_ms"), durationFromBytes(bytes.size.toLong()))
                .putLong(mKey(slot, "recorded_at"), System.currentTimeMillis())
                .apply()
            AppLog.i(TAG, "slot $slot imported: '${label}' ${bytes.size}B")
            null
        } catch (t: Throwable) {
            part.delete()
            AppLog.w(TAG, "slot $slot import failed: ${t.message}")
            "Could not save the message: ${t.message}"
        }
    }
}
