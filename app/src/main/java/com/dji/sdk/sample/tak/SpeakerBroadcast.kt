package com.dji.sdk.sample.tak

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.megaphone.FileInfo
import dji.v5.manager.aircraft.megaphone.MegaphoneManager
import dji.v5.manager.aircraft.megaphone.MegaphoneStatus
import dji.v5.manager.aircraft.megaphone.PlayMode
import dji.v5.manager.aircraft.megaphone.UploadType
import dji.v5.manager.aircraft.megaphone.WorkMode
import com.taklite.util.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SENDS ONE STANDARDISED MESSAGE TO THE AIRCRAFT AND PLAYS IT.
 *
 * The speaker holds one sound, so every play is an upload. [SpeakerMessages] explains why there
 * is no library on the aircraft to pick from.
 *
 * ⚠ **`onSuccess` FIRES ONCE PER 8 KB CHUNK, AND A NORMAL FIRST-WINS LATCH WOULD TRUNCATE EVERY
 * MESSAGE.** This is the trap in this whole feature and it is read from the 5.18 bytecode, not
 * guessed: `MegaphoneManager$FileReadRunnable` is constructed with THE SAME
 * `CompletionCallbackWithProgress` the caller passed, and its per-chunk `addUploadBuffer`
 * acknowledgement calls `CallbackUtils.onSuccess` on it. So does `finishUpload`. So does the
 * upload listener's `SUCCESS_UPLOAD`. A `compareAndSet(false, true)` guard of the usual
 * answer-once kind would therefore fire on the acknowledgement of the FIRST 8 KB — and a 20 KB
 * "EVACUATE" would broadcast about 40% of itself, over a public area, and report success.
 *
 * ⚠ **AND THE PROGRESS PERCENTAGE NEVER REACHES 100.** The first rule written here was
 * "progress 100 AND THEN an onSuccess", and it failed when measured: an 11360B message
 * uploaded cleanly in 644ms and the highest progress MSDK ever reported was NINETY-NINE, because
 * it computes `100 * alreadySentBytes / totalBytes` in integer arithmetic. The genuine completion
 * arrived at 99%, was discarded as a chunk ack, and a message the aircraft was holding got
 * reported to the pilot as refused.
 *
 * So NEITHER callback decides completion now. The upload is finished when the progress STOPS
 * ADVANCING ([PROGRESS_SETTLE_MS]), and then the AIRCRAFT is asked whether it is ready —
 * `MegaphoneStatus.IN_TRANSMISSION` means it is still receiving, and `startPlay` independently
 * refuses with `MEGAPHONE_HAS_STARTED` unless the speaker is IDLE. That refusal is the real guard
 * against broadcasting a fragment, and it is the aircraft's answer instead of our inference.
 * Both of the callback-shape rules that came before this were wrong; the
 * aircraft's own state was right the first time.
 *
 * ⚠ TTS IS NOT AN OPTION ON THIS AIRFRAME and nothing here should reach for it.
 * `MegaphoneManager.startUpload()` refuses `UploadType.TTS_DATA` with `FEATURE_NOT_SUPPORTED`
 * locally, before any radio traffic, when the product is the M4D series — which this is. The
 * payload's own `tts=true` does not override the client-side gate.
 *
 * ⚠ THIS OBJECT REGISTERS NO SDK LISTENER (listener slots belong to the bridge classes). It calls `MegaphoneManager` verbs and
 * reads state through [AircraftAccessories].
 */
object SpeakerBroadcast {

    private const val TAG = "SpeakerBroadcast"

    /**
     * Quiet time on the upload callbacks that means the transfer has stopped.
     *
     * Measured: an 11.4 KB message pushed in 644ms with callbacks arriving continuously, so half
     * a second of silence is a long gap. Do not shorten it without re-measuring on a bigger file
     * — cutting a live transfer short is the failure this replaced.
     */
    private const val PROGRESS_SETTLE_MS = 500L

    /** How often to ask the aircraft whether it has finished receiving. */
    private const val STATUS_POLL_MS = 300L

    /** How long to wait for the speaker to leave IN_TRANSMISSION before giving up. */
    private const val READY_TIMEOUT_MS = 8_000L

    /**
     * The backstop for a transfer that never starts or dies silently.
     *
     * MEASURED: 11360B in 644ms, about 17.6 KB/s, so the 30s maximum message (60 KB)
     * should take roughly 3.4s. 15s is four times that. ⚠ It is a BACKSTOP, not a deadline — the
     * settle above ends a healthy upload long before this, and the first version of this code
     * used a watchdog as the completion path and reported a delivered message as refused.
     */
    private const val UPLOAD_TIMEOUT_MS = 15_000L

    /**
     * ⚠ REPEAT IS BUILT HERE, NOT WITH `PlayMode.LOOP`, AND THE DIFFERENCE IS THE SAFETY CASE.
     * LOOP never stops: it would leave a standardised message repeating over a public area with
     * nobody holding anything and nothing to end it. A bounded run — three plays, thirty seconds
     * apart, then silence — ends by itself whether or not anyone is watching, and can be stopped
     * at any point in between. Every message is still sent to the payload as SINGLE.
     */
    const val REPEAT_TIMES = 3

    /** The quiet between repeats, measured from the end of one play to the start of the next. */
    const val REPEAT_GAP_MS = 30_000L

    enum class Phase { IDLE, PREPARING, UPLOADING, STARTING, PLAYING, WAITING, FAILED }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /** The slot being sent or played, or null when idle. */
    @Volatile
    var activeSlot: Int? = null
        private set

    /** 0..100 while [Phase.UPLOADING]. */
    @Volatile
    var progressPct: Int = 0
        private set

    /** Why the last attempt failed, for the notice. Null unless [Phase.FAILED]. */
    @Volatile
    var failure: String? = null
        private set

    @Volatile
    var onChanged: (() -> Unit)? = null

    private val ui = Handler(Looper.getMainLooper())
    private var done = AtomicBoolean(true)
    private var uploadStartedAtMs = 0L

    /**
     * True while a broadcast is in progress, INCLUDING the silence between repeats.
     *
     * ⚠ THE GAP COUNTS. A run that is waiting to speak again is still a broadcast the pilot
     * started and has not ended — treating the quiet as idle would let a second message start
     * on top of a sequence that is still going.
     */
    val live: Boolean
        get() = phase == Phase.PREPARING || phase == Phase.UPLOADING ||
            phase == Phase.STARTING || phase == Phase.PLAYING || phase == Phase.WAITING

    /** How many plays are still to come in this run, including one in progress. */
    @Volatile
    var playsLeft: Int = 0
        private set

    /** Which play of the run is happening, 1-based, for the status line. */
    val playNumber: Int
        get() = (totalPlays - playsLeft + 1).coerceIn(1, totalPlays.coerceAtLeast(1))

    @Volatile
    var totalPlays: Int = 1
        private set

    private var repeatSlot: Int? = null
    private var repeatContext: Context? = null

    /** When the next repeat is due, on the elapsed-time clock. */
    @Volatile
    private var nextPlayAtMs = 0L

    /**
     * Whole seconds until the aircraft speaks again, 0 when nothing is pending.
     *
     * A silent gap with no counter reads as a broadcast that has stalled, and the pilot's only
     * way to find out otherwise is to wait and see.
     */
    val secondsToNextPlay: Int
        get() = if (phase != Phase.WAITING) 0
        else ((nextPlayAtMs - SystemClock.elapsedRealtime() + 999) / 1000)
            .coerceAtLeast(0L).toInt()

    /** Repaints the countdown once a second while the run is between plays. */
    private val gapTick = object : Runnable {
        override fun run() {
            if (phase != Phase.WAITING) return
            onChanged?.invoke()
            ui.postDelayed(this, 1000L)
        }
    }

    private fun set(next: Phase) {
        phase = next
        ui.post { onChanged?.invoke() }
    }

    private fun fail(why: String, onResult: (Boolean, String?) -> Unit) {
        AppLog.w(TAG, "broadcast failed: $why")
        failure = why
        set(Phase.FAILED)
        ui.post { onResult(false, why) }
    }

    /**
     * Uploads the message in [slot] and plays it.
     *
     * @param onResult false with a reason the pilot can read. Every refusal path ends here; none
     * of them may end in silence.
     */
    fun play(
        context: Context,
        slot: Int,
        repeat: Boolean = false,
        onResult: (Boolean, String?) -> Unit,
    ) {
        if (live) { onResult(false, "A message is already going out"); return }
        if (SpeakerTalk.talking) { onResult(false, "The microphone is open"); return }
        val message = SpeakerMessages.saved(context, slot)
        if (message == null) { onResult(false, "No message in that slot"); return }
        totalPlays = if (repeat) REPEAT_TIMES else 1
        playsLeft = totalPlays
        repeatSlot = slot
        // The application context, so a run cannot hold a dead Activity alive across its gaps.
        repeatContext = context.applicationContext
        if (repeat) {
            AppLog.i(TAG, "repeat run: $REPEAT_TIMES plays, ${REPEAT_GAP_MS / 1000}s apart")
        }
        sendOnce(context, slot, onResult)
    }

    /** One pass of the sequence. A repeat re-enters here. */
    private fun sendOnce(context: Context, slot: Int, onResult: (Boolean, String?) -> Unit) {
        val message = SpeakerMessages.saved(context, slot)
        if (message == null) { onResult(false, "No message in that slot"); return }

        activeSlot = slot
        progressPct = 0
        failure = null
        done = AtomicBoolean(false)
        set(Phase.PREPARING)
        AppLog.i(TAG, "message '${message.label}' (slot $slot, ${message.bytes}B, " +
            "${message.durationMs}ms): preparing, play $playNumber of $totalPlays")

        // ⚠ STEP 1 AND ITS RESULT IS IGNORED ON PURPOSE. `isFileChannelOpen` is an SDK-LOCAL
        // flag that is only cleared on a clean finish, a failure state, or this call. An
        // interrupted push therefore leaves it stuck true, and every later push fails with
        // TRANSMISSION_HAS_STARTED for the life of the connection. Cancelling something that
        // was never started is free; not cancelling is a dead speaker.
        MegaphoneManager.getInstance().cancelPushingFileToMegaphone(
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = stopThenSend(message, onResult)
                override fun onFailure(error: IDJIError) = stopThenSend(message, onResult)
            })
    }

    private fun stopThenSend(
        message: SpeakerMessages.Message,
        onResult: (Boolean, String?) -> Unit,
    ) {
        // Step 2, also ignored: clears MEGAPHONE_HAS_STARTED, and doubles as the stop when the
        // pilot switches from one message to another.
        MegaphoneManager.getInstance().stopPlay(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = setModesThenSend(message, onResult)
            override fun onFailure(error: IDJIError) = setModesThenSend(message, onResult)
        })
    }

    private fun setModesThenSend(
        message: SpeakerMessages.Message,
        onResult: (Boolean, String?) -> Unit,
    ) {
        // ⚠ STEP 3 IS LOAD-BEARING AFTER ANY PUSH-TO-TALK. SpeakerTalk deliberately leaves the
        // payload in REAL_TIME, so without this a message pressed after a talk goes to a payload
        // still expecting a live stream.
        MegaphoneManager.getInstance().setWorkMode(WorkMode.VOICE,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = setPlayModeThenSend(message, onResult)
                override fun onFailure(error: IDJIError) {
                    // Not fatal alone — the payload may already be in VOICE. The push decides.
                    AppLog.v(TAG, "work mode refused (${error.description()}) — continuing")
                    setPlayModeThenSend(message, onResult)
                }
            })
    }

    private fun setPlayModeThenSend(
        message: SpeakerMessages.Message,
        onResult: (Boolean, String?) -> Unit,
    ) {
        // ⚠ SINGLE IS FORCED ON EVERY MESSAGE, NEVER LOOP. A standardised message repeating
        // unattended over a public area is the loudspeaker form of the hot microphone that
        // SpeakerTalk's hold-only rule exists to prevent — and it is worse in one way, because
        // nobody is holding anything, so nothing ends it.
        MegaphoneManager.getInstance().setPlayMode(PlayMode.SINGLE,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = send(message, onResult)
                override fun onFailure(error: IDJIError) {
                    AppLog.v(TAG, "play mode refused (${error.description()}) — continuing")
                    send(message, onResult)
                }
            })
    }

    private fun send(message: SpeakerMessages.Message, onResult: (Boolean, String?) -> Unit) {
        progressPct = 0
        uploadStartedAtMs = SystemClock.elapsedRealtime()
        set(Phase.UPLOADING)
        AppLog.i(TAG, "uploading '${message.label}' (${message.bytes}B)")

        // ⚠ THE PERCENTAGE NEVER REACHES 100, AND WAITING FOR IT REPORTED A DELIVERED MESSAGE
        // AS REFUSED. Measured: an 11360B message uploaded in 644ms, the aircraft acknowledged it,
        // and the last progress MSDK reported was NINETY-NINE. It computes
        // `100 * alreadySentBytes / totalBytes` in integer arithmetic and the final ack does not
        // close the gap, so `>= 100` is a condition that never becomes true. The real completion
        // callback arrived at 99%, this code threw it away, and a message the aircraft already
        // held was reported as refused.
        //
        // So completion is not inferred from the callbacks at all now. The upload is FINISHED
        // when the progress stops advancing, and then THE AIRCRAFT decides whether it is ready:
        // MegaphoneStatus reports IN_TRANSMISSION while it is still receiving, and startPlay
        // refuses with MEGAPHONE_HAS_STARTED when it is not IDLE. That refusal is a real guard
        // against broadcasting a half-received message, and it is the aircraft's answer rather
        // than our inference.
        val settle = Runnable {
            if (done.compareAndSet(false, true)) {
                val ms = SystemClock.elapsedRealtime() - uploadStartedAtMs
                // The transfer time is the elapsed time MINUS the settle we just waited out.
                // Reporting the total as a rate understated it by a third on the first run.
                val transferMs = (ms - PROGRESS_SETTLE_MS).coerceAtLeast(1)
                AppLog.i(TAG, "upload settled at $progressPct%: ${message.bytes}B in " +
                    "${transferMs}ms (${message.bytes * 1000 / transferMs} B/s), ${ms}ms " +
                    "including the settle — asking the aircraft when it is ready")
                waitUntilReady(message, onResult, SystemClock.elapsedRealtime() + READY_TIMEOUT_MS)
            }
        }

        fun bump() {
            ui.removeCallbacks(settle)
            ui.postDelayed(settle, PROGRESS_SETTLE_MS)
        }

        MegaphoneManager.getInstance().startPushingFileToMegaphone(
            FileInfo(UploadType.VOICE_FILE, message.file, null),
            object : CommonCallbacks.CompletionCallbackWithProgress<Int> {
                override fun onProgressUpdate(progress: Int?) {
                    progressPct = (progress ?: 0).coerceIn(0, 100)
                    ui.post { onChanged?.invoke() }
                    bump()
                }

                override fun onSuccess() {
                    // Still fires per 8KB chunk, so it is not completion on its own — it only
                    // says the transfer is alive. It restarts the settle timer.
                    AppLog.v(TAG, "upload onSuccess at $progressPct%")
                    bump()
                }

                override fun onFailure(error: IDJIError) {
                    if (!done.compareAndSet(false, true)) return
                    ui.removeCallbacks(settle)
                    fail(describe(error), onResult)
                }
            })

        bump()
        ui.postDelayed({
            if (done.compareAndSet(false, true)) {
                AppLog.w(TAG, "upload timed out at $progressPct%")
                abort()
                fail("The aircraft did not take the message", onResult)
            }
        }, UPLOAD_TIMEOUT_MS)
    }

    /**
     * Waits for the speaker to stop receiving, then plays.
     *
     * ⚠ THE AIRCRAFT IS THE AUTHORITY ON WHETHER IT HAS THE FILE. IN_TRANSMISSION means it is
     * still taking bytes, and playing then would broadcast a fragment. This asks rather than
     * assumes.
     */
    private fun waitUntilReady(
        message: SpeakerMessages.Message,
        onResult: (Boolean, String?) -> Unit,
        deadline: Long,
    ) {
        MegaphoneManager.getInstance().getStatus(
            object : CommonCallbacks.CompletionCallbackWithParam<MegaphoneStatus> {
                private fun retry() {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        AppLog.w(TAG, "the speaker never finished receiving")
                        fail("The speaker did not finish taking the message", onResult)
                        return
                    }
                    ui.postDelayed({ waitUntilReady(message, onResult, deadline) }, STATUS_POLL_MS)
                }

                override fun onSuccess(status: MegaphoneStatus?) {
                    AppLog.v(TAG, "readiness: status=$status")
                    when (status) {
                        MegaphoneStatus.IN_TRANSMISSION,
                        MegaphoneStatus.TTS_IN_CONVERSION -> retry()
                        MegaphoneStatus.IN_EXCEPTION ->
                            fail("The speaker reports a fault", onResult)
                        else -> startPlay(message, onResult)
                    }
                }

                override fun onFailure(error: IDJIError) = retry()
            })
    }

    private fun startPlay(
        message: SpeakerMessages.Message,
        onResult: (Boolean, String?) -> Unit,
        retry: Boolean = true,
    ) {
        set(Phase.STARTING)
        val fired = AtomicBoolean(false)
        MegaphoneManager.getInstance().startPlay(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                if (!fired.compareAndSet(false, true)) return
                AppLog.i(TAG, "playing '${message.label}'")
                set(Phase.PLAYING)
                startPlayWatch(message)
                ui.post { onResult(true, null) }
            }

            override fun onFailure(error: IDJIError) {
                if (!fired.compareAndSet(false, true)) return
                val why = describe(error)
                // The speaker was still busy. One stop, one retry — not a loop.
                if (retry && why.contains("MEGAPHONE_HAS_STARTED", ignoreCase = true)) {
                    AppLog.i(TAG, "the speaker was still busy — stopping and retrying once")
                    MegaphoneManager.getInstance().stopPlay(
                        object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() = startPlay(message, onResult, retry = false)
                            override fun onFailure(e: IDJIError) =
                                startPlay(message, onResult, retry = false)
                        })
                    return
                }
                fail(why, onResult)
            }
        })
    }

    /** Stops whatever is going out, at any phase. */
    fun stop(onResult: (Boolean) -> Unit = {}) {
        val wasUploading = phase == Phase.UPLOADING || phase == Phase.PREPARING
        AppLog.i(TAG, "stopping (phase=$phase)")
        done.set(true)
        if (wasUploading) abort()
        MegaphoneManager.getInstance().stopPlay(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = finishStop(onResult)
            override fun onFailure(error: IDJIError) = finishStop(onResult)
        })
    }

    private fun finishStop(onResult: (Boolean) -> Unit) {
        ui.removeCallbacks(playWatch)
        // ⚠ THE PENDING REPEAT DIES WITH THE STOP. Without this, stopping a run would silence
        // the message that is playing and then let the aircraft speak again thirty seconds later
        // — the exact opposite of what the pilot just asked for.
        ui.removeCallbacks(repeatRun)
        ui.removeCallbacks(gapTick)
        playsLeft = 0
        repeatSlot = null
        repeatContext = null
        nextPlayAtMs = 0
        activeSlot = null
        progressPct = 0
        set(Phase.IDLE)
        ui.post { onResult(true) }
    }

    /** Cancels an in-flight push so the channel cannot stay stuck open. */
    private fun abort() {
        MegaphoneManager.getInstance().cancelPushingFileToMegaphone(
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {}
                override fun onFailure(error: IDJIError) {}
            })
    }

    /**
     * Drops all state and makes sure nothing is left going out.
     *
     * Called on disconnect and when the flight screen dies — the screen that carries the ON AIR
     * indicator is gone, so the broadcast must not outlive it.
     */
    fun reset() {
        ui.removeCallbacks(playWatch)
        ui.removeCallbacks(repeatRun)
        ui.removeCallbacks(gapTick)
        playsLeft = 0
        repeatSlot = null
        repeatContext = null
        nextPlayAtMs = 0
        onChanged = null
        if (live) stop()
        done.set(true)
        activeSlot = null
        progressPct = 0
        failure = null
        phase = Phase.IDLE
        AppLog.i(TAG, "broadcast state reset")
    }

    /** Never the word "null" — this aircraft returns null descriptions. */
    private fun describe(error: IDJIError?): String {
        val d = error?.description()
        if (!d.isNullOrBlank()) return d
        val c = error?.errorCode()
        return if (!c.isNullOrBlank()) c else "the aircraft refused it"
    }

    /**
     * Watches a playing message until the aircraft says it has stopped.
     *
     * ⚠ WITHOUT THIS THE PHASE STICKS AT [Phase.PLAYING] FOR EVER and every later press is
     * refused with "A message is already going out" — which is exactly what happened when
     * measured, minutes after a message had finished playing. A `noticeStatus` hook
     * existed for this and nothing ever called it; an observer nobody subscribes to is not a
     * mechanism, it is a comment.
     *
     * Polling, not a listener: SDK listener slots are kept for the bridge classes,
     * and the timer rule forbids timed WRITES, not reads.
     *
     * ⚠ THE BACKSTOP IS NOT OPTIONAL. The message's own duration is known, so if the status
     * read fails or never changes, the phase is released anyway once the message must have
     * ended. A feature that can wedge itself into "busy for ever" is worse than one that
     * occasionally releases early, because the pilot's only recovery is restarting the app.
     */
    private const val PLAY_POLL_MS = 500L

    private var playWatchDeadline = 0L

    private val playWatch = Runnable { pollPlaying() }

    private fun pollPlaying() {
        if (phase != Phase.PLAYING) return
        if (SystemClock.elapsedRealtime() >= playWatchDeadline) {
            AppLog.i(TAG, "the message must have finished — releasing on the backstop")
            onPlayFinished()
            return
        }
        MegaphoneManager.getInstance().getStatus(
            object : CommonCallbacks.CompletionCallbackWithParam<MegaphoneStatus> {
                override fun onSuccess(status: MegaphoneStatus?) {
                    if (phase != Phase.PLAYING) return
                    if (status != null && status != MegaphoneStatus.PLAYING) {
                        AppLog.i(TAG, "the message finished (status=$status)")
                        onPlayFinished()
                    } else {
                        ui.postDelayed(playWatch, PLAY_POLL_MS)
                    }
                }

                override fun onFailure(error: IDJIError) {
                    ui.postDelayed(playWatch, PLAY_POLL_MS)
                }
            })
    }

    /**
     * One play has ended: either wait and say it again, or stop.
     *
     * ⚠ EACH REPEAT RE-SENDS THE WHOLE MESSAGE rather than replaying what is on the speaker. The
     * slot holds one sound and anything can have overwritten it during the thirty-second gap — a
     * push-to-talk, another message, DJI Pilot 2. Re-sending costs about 1.4s against a 30s gap
     * and removes the entire question of what is actually resident.
     */
    private fun onPlayFinished() {
        playsLeft = (playsLeft - 1).coerceAtLeast(0)
        val slot = repeatSlot
        val context = repeatContext
        if (playsLeft <= 0 || slot == null || context == null) {
            AppLog.i(TAG, "broadcast run finished")
            activeSlot = null
            playsLeft = 0
            set(Phase.IDLE)
            return
        }
        AppLog.i(TAG, "$playsLeft play(s) left — next in ${REPEAT_GAP_MS / 1000}s")
        nextPlayAtMs = SystemClock.elapsedRealtime() + REPEAT_GAP_MS
        set(Phase.WAITING)
        ui.postDelayed(repeatRun, REPEAT_GAP_MS)
        ui.removeCallbacks(gapTick)
        ui.postDelayed(gapTick, 1000L)
    }

    private val repeatRun = Runnable {
        val slot = repeatSlot
        val context = repeatContext
        ui.removeCallbacks(gapTick)
        if (phase != Phase.WAITING || slot == null || context == null) return@Runnable
        if (SpeakerTalk.talking) {
            // The pilot is speaking. A live human outranks a recording — drop the rest of the run
            // rather than talking over them.
            AppLog.i(TAG, "repeat abandoned: the microphone is open")
            activeSlot = null
            playsLeft = 0
            set(Phase.IDLE)
            return@Runnable
        }
        sendOnce(context, slot) { ok, why ->
            if (!ok) AppLog.w(TAG, "repeat failed: $why")
        }
    }

    private fun startPlayWatch(message: SpeakerMessages.Message) {
        ui.removeCallbacks(playWatch)
        // The message's own length plus slack for the aircraft to start and report.
        playWatchDeadline = SystemClock.elapsedRealtime() + message.durationMs + 5_000L
        ui.postDelayed(playWatch, PLAY_POLL_MS)
    }
}
