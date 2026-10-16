package com.dji.sdk.sample.tak

import android.os.Handler
import android.os.HandlerThread
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.recorder.AudioRecordHandler
import dji.v5.common.recorder.EncodedDataCallback
import dji.v5.common.recorder.OpusEncoder
import dji.v5.common.recorder.SourceDataCallback
import dji.v5.manager.aircraft.megaphone.MegaphoneManager
import dji.v5.manager.aircraft.megaphone.WorkMode
import com.taklite.util.AppLog

/**
 * PUSH-TO-TALK through the AS1 speaker: the controller's microphone, live, out of the aircraft.
 *
 * The pipeline is DJI's own, the same one their `MegaphoneVM` sample uses:
 * mic -> [AudioRecordHandler] -> [OpusEncoder] -> `sendRealTimeDataToMegaphone`.
 *
 * ⚠ **PUSH TO TALK, NEVER A TOGGLE, AND THAT IS A SAFETY DECISION** (design decision). A
 * toggle can leave a HOT MICROPHONE broadcasting from an aircraft over a public area with nobody
 * aware of it — the pilot's own conversation, on a loudspeaker, at whatever range the aircraft
 * happens to be. Every route in and out of this object is therefore a hold: the transmission
 * lives only as long as a finger or a button is down. [stop] is idempotent and safe to call from
 * anywhere, and it is called from the panel closing, the screen pausing and the screen dying, so
 * no path through the application can leave the microphone open.
 *
 * ⚠ THE MICROPHONE IS A SINGLETON. [AudioRecordHandler] is one instance for the process, so a
 * talk that did not release cleanly makes the NEXT one fail with the microphone unavailable.
 * [forceRelease] therefore tears down the recorder and the encoder before every start, whatever
 * state this object believes it is in.
 *
 * ⚠ The work mode is left in [WorkMode.REAL_TIME] after a talk. Putting it back would be a write
 * to the payload for no pilot-visible gain, and the next SOUND press sets what it needs anyway.
 */
object SpeakerTalk {

    private const val TAG = "SpeakerTalk"

    @Volatile
    private var recorder: AudioRecordHandler? = null

    @Volatile
    private var encoder: OpusEncoder? = null

    /** True from the moment the pilot presses until the moment the microphone is released. */
    @Volatile
    var talking = false
        private set

    /**
     * True once the AIRCRAFT is believed to be carrying the audio — not merely when the channel
     * was requested.
     *
     * ⚠ MEASURED IN FLIGHT: the pilot's voice does not come out of the aircraft for
     * TWO TO THREE SECONDS after the press, while this application reaches "LIVE" in about 80ms.
     * The microphone is not the delay — a 1.84s press captured and sent 42 frames, all of it —
     * the payload's real-time channel simply takes that long to start emitting. The pill used to
     * paint ON AIR the instant [talking] was set, which is BEFORE any of the asynchronous work,
     * so it claimed the aircraft was speaking through the whole of a short press that produced
     * no sound at all. The pilot spoke into a dead channel and the screen said it was working.
     */
    @Volatile
    var carrying = false
        private set

    /**
     * True from the pilot's RELEASE until the last of their speech has gone to the aircraft.
     *
     * ⚠ THE RELEASE USED TO CUT THE PILOT OFF MID-WORD. `stop()` set [talking] false first, and
     * both send guards test it — so the frames `OpusEncoder.release()` flushes were discarded,
     * and so was anything already queued on the IO thread. Measured: the payload takes
     * about 1.5s to start emitting, which means roughly that much of the pilot's voice is in
     * flight at any moment, and all of it was thrown away by this application the instant the
     * button came up.
     *
     * ⚠ THIS IS A DELIBERATE, BOUNDED EXCEPTION TO "STOP MEANS STOP". The aircraft keeps speaking
     * for up to [TAIL_DRAIN_MS] after the release — but ONLY words the pilot has already said,
     * never new microphone input: the recorder is stopped first, so nothing further can be
     * captured. The pill stays amber for the whole drain, because the screen must not say silent
     * while the aircraft is still talking.
     */
    @Volatile
    var draining = false
        private set

    /**
     * How long the already-spoken tail is allowed to finish.
     *
     * From the measured pipeline latency: the payload reported PLAYING 1533ms and 1535ms after
     * two separate presses, so about that much audio is in flight when the button is released.
     * Sending the end-of-file any sooner is what truncates it.
     */
    private const val TAIL_DRAIN_MS = 1600L

    /**
     * How long to keep saying "opening" when the aircraft never confirms.
     *
     * A backstop, not the mechanism: [pollCarrying] asks the payload and this only decides how
     * long to wait before believing it anyway. Set from the measured 2-3s with margin.
     */
    /**
     * ⚠ IN_TRANSMISSION IS NOT "EMITTING". The payload reports it about 260ms after the press,
     * reliably — but heard from the ground, the voice still does not come out of the aircraft for
     * two to three seconds, so believing that status put the pill green far too early and moved
     * the lie rather than removing it.
     *
     * So the poll now runs for the WHOLE talk and logs every status change, looking for a later
     * transition that means "the speaker is actually making sound". If one exists it becomes the
     * signal and this constant stops mattering; until then it is the honest fallback, set from
     * what was heard on the ground and marked as such.
     */
    private const val CARRY_ASSUME_MS = 2500L
    private const val CARRY_POLL_MS = 250L

    /** Told whenever [talking] changes, so the panel can paint the TALK control. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private var thread: HandlerThread? = null
    private var io: Handler? = null
    private val ui = Handler(android.os.Looper.getMainLooper())

    private var micFrames = 0
    private var sentFrames = 0

    /**
     * Opens the live channel and starts sending.
     *
     * @param onStarted true when the aircraft is actually taking audio. False means the pilot
     * must be told, because a push-to-talk that silently does nothing is worse than one that
     * refuses — the pilot keeps talking to a speaker that is not on.
     */
    fun start(onStarted: (Boolean) -> Unit) {
        if (talking) { onStarted(true); return }
        // ⚠ THE PAIRED HALF OF SpeakerRecorder'S GUARD. The microphone is a process singleton;
        // the two features REFUSE each other rather than releasing each other's hold, because a
        // "clean up whatever is there" call is how one silently steals the microphone from the
        // other mid-sentence.
        if (SpeakerRecorder.recording) {
            AppLog.w(TAG, "talk refused: a message is being recorded")
            onStarted(false); return
        }
        // A press during a drain abandons the tail: the pilot is speaking again, and the new
        // words outrank the end of the last sentence.
        if (draining) {
            AppLog.i(TAG, "talk: a new press abandoned the previous tail")
            ui.removeCallbacksAndMessages(null)
            draining = false
        }
        forceRelease()
        talking = true
        carrying = false          // opening, NOT on air — see [carrying]
        lastStatus = null
        notifyChanged()
        micFrames = 0
        sentFrames = 0
        startIoThread()
        AppLog.i(TAG, "talk: opening the live channel")
        MegaphoneManager.getInstance().setWorkMode(WorkMode.REAL_TIME,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = openTransmission(onStarted)
                override fun onFailure(error: IDJIError) {
                    // Not fatal on its own: the payload may already be in real-time mode. The
                    // transmission open below is the step that decides.
                    AppLog.w(TAG, "talk: work mode refused (${error.description()}) — " +
                        "opening anyway")
                    openTransmission(onStarted)
                }
            })
    }

    private fun openTransmission(onStarted: (Boolean) -> Unit) {
        MegaphoneManager.getInstance().startRealTimeTransmission(
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    // The pilot may have let go during the round trip. Do not open a microphone
                    // nobody is holding.
                    if (!talking) { AppLog.i(TAG, "talk: released before it opened"); stop(); return }
                    try {
                        buildPipeline()
                        recorder?.start()
                        encoder?.start()
                        AppLog.i(TAG, "talk: sending — waiting for the aircraft to carry it")
                        pollCarrying(android.os.SystemClock.elapsedRealtime())
                        onStarted(true)
                    } catch (t: Throwable) {
                        AppLog.w(TAG, "talk: the audio pipeline failed: ${t.message}")
                        stop()
                        onStarted(false)
                    }
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "talk: the aircraft refused the live channel: " +
                        "${error.description()}")
                    stop()
                    onStarted(false)
                }
            })
    }

    /**
     * Asks the payload whether it has started carrying the audio, and says so on the pill.
     *
     * ⚠ WE CANNOT HEAR THE AIRCRAFT, so this is the aircraft's own status rather than an
     * assumption — the same lesson as the message upload. The status values seen during a live
     * talk are logged, because nothing documents what a megaphone reports in REAL_TIME mode and
     * the answer decides whether this stays a poll or becomes something better.
     */
    private fun pollCarrying(startedAtMs: Long) {
        if (!talking) return
        val waited = android.os.SystemClock.elapsedRealtime() - startedAtMs
        MegaphoneManager.getInstance().getStatus(
            object : CommonCallbacks.CompletionCallbackWithParam<
                dji.v5.manager.aircraft.megaphone.MegaphoneStatus> {
                override fun onSuccess(
                    status: dji.v5.manager.aircraft.megaphone.MegaphoneStatus?,
                ) {
                    if (!talking) return
                    // Log only the CHANGES, so a whole talk is a short readable trace rather
                    // than forty identical lines.
                    if (status != lastStatus) {
                        AppLog.i(TAG, "talk: after ${waited}ms the speaker reports $status")
                        lastStatus = status
                    }
                    // ⚠ PLAYING, NOT IN_TRANSMISSION. If this payload ever reports PLAYING during
                    // a live talk then THAT is the moment it makes sound, and it is the signal
                    // this whole poll exists to find. IN_TRANSMISSION only means bytes arriving.
                    val emitting =
                        status == dji.v5.manager.aircraft.megaphone.MegaphoneStatus.PLAYING
                    when {
                        emitting -> markCarrying(waited, "the speaker reports PLAYING")
                        waited >= CARRY_ASSUME_MS ->
                            markCarrying(waited, "no PLAYING reported — assuming by ${CARRY_ASSUME_MS}ms")
                        else -> io?.postDelayed({ pollCarrying(startedAtMs) }, CARRY_POLL_MS)
                    }
                    // Keep watching even after the pill goes green, so the trace shows whether a
                    // later transition exists that we should have been using instead.
                    if (carrying && waited < 6000L) {
                        io?.postDelayed({ pollCarrying(startedAtMs) }, CARRY_POLL_MS)
                    }
                }

                override fun onFailure(error: IDJIError) {
                    if (!talking) return
                    if (waited >= CARRY_ASSUME_MS) markCarrying(waited, "status unavailable")
                    else io?.postDelayed({ pollCarrying(startedAtMs) }, CARRY_POLL_MS)
                }
            })
    }

    @Volatile
    private var lastStatus: dji.v5.manager.aircraft.megaphone.MegaphoneStatus? = null

    private fun markCarrying(waitedMs: Long, why: String) {
        if (carrying || !talking) return
        carrying = true
        AppLog.i(TAG, "talk: ON AIR after ${waitedMs}ms — $why")
        notifyChanged()
    }

    private fun buildPipeline() {
        val rec = AudioRecordHandler.getInstance()
        rec.init()
        val enc = OpusEncoder()
        enc.config(rec.audioConfig)
        AppLog.i(TAG, "talk: rate=${rec.audioConfig?.audioSampleRate} " +
            "ch=${rec.audioConfig?.audioChannelCount} buf=${rec.audioConfig?.bufferSize}")
        enc.setEncodedDataCallback(EncodedDataCallback { data, size ->
            // ⚠ THE GUARD ALLOWS THE DRAIN. It used to be `if (!talking) return`, which threw
            // away the encoder's flushed tail and cut the pilot off mid-word — see [draining].
            // Nothing NEW can arrive here after a release, because the recorder is stopped
            // first; what passes now is only speech the pilot already made.
            if (!talking && !draining) return@EncodedDataCallback
            sentFrames++
            io?.post {
                if (!talking && !draining) return@post
                MegaphoneManager.getInstance().sendRealTimeDataToMegaphone(data, size,
                    object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {}
                        override fun onFailure(error: IDJIError) {}
                    })
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
     * Closes the microphone and the live channel. Safe to call at any time, from any state.
     *
     * ⚠ THE ORDER MATTERS. The microphone is released FIRST, so nothing more can be encoded,
     * and only then is the end-of-file appended to tell the payload the talk is over. The other
     * order leaves a window where frames are still being produced after the payload has been
     * told there are none coming.
     */
    fun stop() {
        if (!talking && !draining && recorder == null && encoder == null) return
        val wasTalking = talking
        talking = false
        carrying = false
        AppLog.i(TAG, "talk: released (mic frames=$micFrames sent=$sentFrames) — draining the tail")

        if (!wasTalking) { finishDrain(false); return }

        // ⚠ ORDER. The microphone stops FIRST, so nothing new can be captured, and only then is
        // the encoder released — its flush is the tail we are trying to keep.
        draining = true
        notifyChanged()                       // amber: released, still finishing
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { encoder?.release() }    // flushes the last frames through the callback
        encoder = null

        // ⚠ THE END-OF-FILE IS DELAYED ON PURPOSE. Sent immediately it truncates whatever the
        // payload still holds — which is about 1.5s of the pilot's voice.
        ui.postDelayed({ finishDrain(true) }, TAIL_DRAIN_MS)
    }

    /** Closes the channel once the tail has gone. */
    private fun finishDrain(sendEof: Boolean) {
        if (!draining && !sendEof) {
            forceRelease(); stopIoThread(); notifyChanged(); return
        }
        draining = false
        AppLog.i(TAG, "talk: tail drained (sent=$sentFrames) — closing")
        forceRelease()
        if (sendEof) {
            MegaphoneManager.getInstance().appendEOFToRealTimeData(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() { AppLog.i(TAG, "talk: closed") }
                    override fun onFailure(error: IDJIError) {
                        AppLog.w(TAG, "talk: close refused: ${error.description()}")
                    }
                })
        }
        stopIoThread()
        notifyChanged()
    }

    /** Tears the microphone down whatever state this object believes it is in. */
    private fun forceRelease() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { encoder?.release() }
        // The singleton as well, in case our own references were already dropped.
        runCatching { AudioRecordHandler.getInstance().stop() }
        runCatching { AudioRecordHandler.getInstance().release() }
        recorder = null
        encoder = null
    }

    private fun startIoThread() {
        if (thread != null) return
        thread = HandlerThread("speaker-talk").also {
            it.start()
            io = Handler(it.looper)
        }
    }

    private fun stopIoThread() {
        runCatching { thread?.quitSafely() }
        thread = null
        io = null
    }

    private fun notifyChanged() {
        val cb = onChanged ?: return
        Handler(android.os.Looper.getMainLooper()).post { cb() }
    }
}
