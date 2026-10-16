package com.dji.sdk.sample.tak

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dji.v5.common.recorder.AudioRecordHandler
import dji.v5.common.recorder.EncodedDataCallback
import dji.v5.common.recorder.OpusEncoder
import dji.v5.common.recorder.SourceDataCallback
import com.taklite.util.AppLog
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * RECORDS A STANDARDISED MESSAGE on the controller, into the exact bytes the AS1 accepts.
 *
 * ⚠ THIS CLASS NEVER TOUCHES THE AIRCRAFT. Not one `MegaphoneManager` call, deliberately: a
 * message is recorded on the ground, with no aircraft powered. Broadcasting is
 * [SpeakerBroadcast]'s job and it is a separate decision. Keeping the two apart is also what
 * makes the recorder testable without a Matrice.
 *
 * It is [SpeakerTalk] with ONE substitution: the encoded frames go to a file instead of to
 * `sendRealTimeDataToMegaphone`. That is not a coincidence — DJI's own `MegaphoneVM` sample tees
 * the same encoder output to a file and then uploads that file, so this pipeline IS the upload
 * format. There is no conversion step and nothing to get wrong about containers or sample rates:
 * `AudioRecordHandler` forces 16 kHz mono and `OpusEncoder` is configured from it.
 *
 * ⚠ THE MICROPHONE IS A PROCESS SINGLETON, and [SpeakerTalk] holds the other half of that
 * problem. The two REFUSE each other rather than tidying up after each other: this refuses to
 * start while `SpeakerTalk.talking`, and `SpeakerTalk` refuses to start while [recording]. A
 * "release whatever is there" call in either direction is how one feature silently steals the
 * microphone from the other mid-sentence.
 *
 * ⚠ PRESS TO START, PRESS TO STOP — NOT A HOLD, and that is not a contradiction of
 * [SpeakerTalk]'s hold-only doctrine. The doctrine exists because a toggle can leave the
 * AIRCRAFT emitting with nobody aware. Nothing here reaches the aircraft, and asking a pilot
 * to hold a button steady for a twenty-five second message is a worse control, not a safer one.
 */
object SpeakerRecorder {

    private const val TAG = "SpeakerRecorder"

    @Volatile
    private var recorder: AudioRecordHandler? = null

    @Volatile
    private var encoder: OpusEncoder? = null

    /** True from the press that starts a take until the file is closed. */
    @Volatile
    var recording = false
        private set

    /** Which slot the take is for, or null when idle. */
    @Volatile
    var slot: Int? = null
        private set

    /** Told whenever [recording] or the elapsed time moves, so a screen can repaint. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private var thread: HandlerThread? = null
    private var io: Handler? = null

    private var sink: BufferedOutputStream? = null
    private var takeFile: File? = null
    private var startedAtMs = 0L
    private var micFrames = 0
    private var encodedBytes = 0L

    private val ui = Handler(android.os.Looper.getMainLooper())

    /** Milliseconds since the take began, 0 when idle. */
    val elapsedMs: Long
        get() = if (!recording) 0L else SystemClock.elapsedRealtime() - startedAtMs

    /** Stops the take automatically at [SpeakerMessages.MAX_DURATION_MS]. */
    private val capTick = object : Runnable {
        override fun run() {
            if (!recording) return
            onChanged?.invoke()
            if (elapsedMs >= SpeakerMessages.MAX_DURATION_MS) {
                AppLog.i(TAG, "the take reached the ${SpeakerMessages.MAX_DURATION_MS}ms cap")
                stop { _, _, _ -> }
                return
            }
            ui.postDelayed(this, 200)
        }
    }

    /**
     * Starts a take for [slot].
     *
     * @param onStarted false with a reason when the take did not begin. A recorder that silently
     * does nothing is the worst outcome here, because the pilot only finds out when the
     * message they thought they made turns out to be silence.
     */
    fun start(context: Context, slot: Int, onStarted: (Boolean, String?) -> Unit) {
        if (recording) { onStarted(false, "Already recording"); return }
        if (SpeakerTalk.talking) {
            // See the class note: refuse, never take the microphone off the other feature.
            onStarted(false, "The microphone is in use by push-to-talk"); return
        }
        if (!SpeakerMessages.hasRoom(context)) {
            onStarted(false, "Not enough space on the controller"); return
        }
        forceRelease()
        this.slot = slot
        micFrames = 0
        encodedBytes = 0
        startIoThread()
        val temp = File(SpeakerMessages.dir(context), "take_s$slot.part")
        try {
            temp.delete()
            sink = BufferedOutputStream(FileOutputStream(temp))
            takeFile = temp
            buildPipeline()
            recording = true
            startedAtMs = SystemClock.elapsedRealtime()
            recorder?.start()
            encoder?.start()
            AppLog.i(TAG, "recording slot $slot -> ${temp.name}")
            ui.postDelayed(capTick, 200)
            onChanged?.invoke()
            onStarted(true, null)
        } catch (t: Throwable) {
            AppLog.w(TAG, "start failed: ${t.message}")
            recording = false
            cleanUp()
            onStarted(false, t.message ?: "The microphone did not open")
        }
    }

    private fun buildPipeline() {
        val rec = AudioRecordHandler.getInstance()
        rec.init()
        val enc = OpusEncoder()
        enc.config(rec.audioConfig)
        // ⚠ AGC IS ON FOR RECORDINGS AND OFF FOR PUSH-TO-TALK, DELIBERATELY.
        //
        // Measured: a recorded message sounded quieter than the pilot's live voice at
        // the same setting, and the payload was ruled out as the cause — a volume probe showed
        // set=54/real=54 in BOTH work modes, so the playback path applies identical gain. The
        // difference is the SIGNAL LEVEL of the recording itself: a pilot recording a message
        // holds the controller normally, and a pilot on push-to-talk holds it to their mouth.
        //
        // A standardised message must not be quieter because of how somebody held a controller
        // one afternoon, so the recorder normalises. Push-to-talk stays raw: it is live speech,
        // the pilot can hear their own level, and AGC would lift rotor and wind noise between
        // words on an open channel.
        //
        // ⚠ AGC LIFTS BACKGROUND NOISE TOO. Record somewhere quiet; a message made beside a
        // running aircraft will carry that with it, louder than before.
        enc.enableAgc(true)
        AppLog.i(TAG, "encoder: rate=${rec.audioConfig?.audioSampleRate} " +
            "ch=${rec.audioConfig?.audioChannelCount} buf=${rec.audioConfig?.bufferSize} " +
            "agc=${enc.isEnableAgc}")
        enc.setEncodedDataCallback(EncodedDataCallback { data, size ->
            // ⚠ THE GUARD IS INSIDE THE CALLBACK. Frames already queued keep arriving for a
            // moment after the stop, and writing them after the file is closed throws on a
            // background thread.
            if (!recording) return@EncodedDataCallback
            io?.post {
                try {
                    sink?.write(data, 0, size)
                    encodedBytes += size
                } catch (t: Throwable) {
                    AppLog.w(TAG, "write failed: ${t.message}")
                }
            }
        })
        rec.setDataCallBack(SourceDataCallback { data, _ ->
            micFrames++
            enc.putData(data)
        })
        recorder = rec
        encoder = enc
    }

    /**
     * Ends the take and closes the file.
     *
     * ⚠ THE ENCODER IS RELEASED BEFORE THE FILE IS CLOSED, and the close is POSTED to the same
     * IO thread the writes use. `OpusEncoder.release()` flushes its last frames through the
     * callback, so closing first would truncate the tail of every message — and a message that
     * loses its last word is exactly the failure this whole feature exists to avoid.
     */
    fun stop(onDone: (durationMs: Long, bytes: Long, ok: Boolean) -> Unit) {
        if (!recording) { onDone(0, 0, false); return }
        val wallMs = elapsedMs
        recording = false
        ui.removeCallbacks(capTick)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { encoder?.release() }      // flushes remaining frames into the sink
        recorder = null
        encoder = null

        val out = sink
        val file = takeFile
        sink = null
        val finish = Runnable {
            runCatching { out?.flush() }
            runCatching { out?.close() }
            val bytes = file?.length() ?: 0L
            val byBytes = SpeakerMessages.durationFromBytes(bytes)
            val whole = bytes % SpeakerMessages.PACKET_BYTES == 0L
            AppLog.i(TAG, "take done: ${bytes}B, ${wallMs}ms by clock, ${byBytes}ms by bytes, " +
                "mic frames=$micFrames, whole packets=$whole")
            // A large disagreement means the encoder dropped frames — say so loudly rather than
            // storing a message that is quietly shorter than the pilot just spoke.
            if (bytes > 0 && kotlin.math.abs(byBytes - wallMs) > 1500) {
                AppLog.w(TAG, "⚠ the take is ${wallMs - byBytes}ms shorter than the clock — " +
                    "the encoder dropped frames")
            }
            stopIoThread()
            ui.post {
                onChanged?.invoke()
                onDone(byBytes.coerceAtLeast(0), bytes, bytes > 0)
            }
        }
        val h = io
        if (h != null) h.post(finish) else finish.run()
        slot = null
    }

    /** Throws the take away. */
    fun cancel() {
        if (!recording) { cleanUp(); return }
        val file = takeFile
        stop { _, _, _ -> file?.delete() }
    }

    /** The file the last finished take wrote, for [SpeakerMessages.store]. */
    fun lastTake(): File? = takeFile

    private fun forceRelease() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { encoder?.release() }
        // The singleton too, in case our references were already dropped.
        runCatching { AudioRecordHandler.getInstance().stop() }
        runCatching { AudioRecordHandler.getInstance().release() }
        recorder = null
        encoder = null
    }

    private fun cleanUp() {
        runCatching { sink?.close() }
        sink = null
        forceRelease()
        stopIoThread()
        slot = null
        onChanged?.invoke()
    }

    private fun startIoThread() {
        if (thread != null) return
        thread = HandlerThread("speaker-recorder").also {
            it.start()
            io = Handler(it.looper)
        }
    }

    private fun stopIoThread() {
        runCatching { thread?.quitSafely() }
        thread = null
        io = null
    }
}
