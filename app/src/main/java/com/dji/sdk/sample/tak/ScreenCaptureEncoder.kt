package com.dji.sdk.sample.tak

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.taklite.util.AppLog
import java.nio.ByteBuffer

/**
 * Screen-capture H.264 encoder for the outbound RTSP push. It replaced a decode -> scale ->
 * encode transcoder of the aircraft feed, which was deleted as dead code.
 *
 * MediaProjection mirrors the whole flight screen (FPV + HUD + map + toolbar, the whole screen
 * by design) into a [VirtualDisplay] sized to the profile's target resolution, straight into an
 * H.264 encoder's input Surface. Compared to the decode-transcoder this:
 *  - has NO second decoder (it captures FPV's already-decoded, clean pixels), and
 *  - does the scaling on the GPU (VirtualDisplay), not the CPU (the software downsample that
 *    was the bottleneck / artifacting source in the old transcoder),
 * so it's cheaper AND structurally can't hit the NAL-drop / keyframe-starvation artifacting
 * class: there is no source NAL stream to drop from, only clean composited pixels.
 *
 * The encoder still makes its own 2s IDR (self-healing for remote viewers) and honours
 * [requestSyncFrame] to arm the RTSP packetizer on connect — no aircraft round-trip.
 *
 * Emits the same callbacks the RTSP pusher already consumes: [onParamsReady] (encoder SPS/PPS)
 * and [onEncoded] (each encoded frame).
 */
class ScreenCaptureEncoder(
    context: Context,
    private val mediaProjection: MediaProjection,
    private val profile: StreamProfile,
    private val codec: VideoCodec,
    private val onEncoded: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    private val onParamsReady: (sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) -> Unit,
    // The drain thread dying (codec error) or the system stopping the projection used to
    // be silent — the encoder just quietly quit while `streaming` stayed true upstream. This
    // fires once, either way, so the owner can tear down and tell the pilot.
    private val onGone: (String) -> Unit = {},
) {
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var goneNotified = false
    private var encFrameCount = 0
    private var encBytesSinceLog = 0L

    private val screenW: Int
    private val screenH: Int
    private val densityDpi: Int

    init {
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
        }
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        densityDpi = metrics.densityDpi
    }

    /** Registered by the caller (the projection owner) so a stop from the system side tears
     *  this down too. */
    val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            AppLog.i(TAG, "media projection stopped by system")
            release()
            notifyGone("media projection stopped by system")
        }
    }

    /** Fires [onGone] at most once — the drain thread dying and the system stopping the
     *  projection can both reach here. Posted to main so the owner's teardown (which may
     *  join [drainThread]) never runs ON [drainThread] itself, which would deadlock. */
    private fun notifyGone(reason: String) {
        if (goneNotified) return
        goneNotified = true
        android.os.Handler(android.os.Looper.getMainLooper()).post { onGone(reason) }
    }

    fun start(): Boolean {
        // Preserve the screen's aspect ratio, cap the SHORTER-in-landscape (height) to the
        // profile. e.g. a 2400x1080 landscape screen at Standard(480) -> ~1066x480.
        var targetH = minOf(profile.maxHeight, screenH)
        var targetW = (screenW.toDouble() / screenH * targetH).toInt()
        targetW -= targetW % 2   // even dims for the encoder
        targetH -= targetH % 2

        // VBR first, Baseline+Level4, with a fallback ladder — see EncoderConfig. The CBR this
        // used to ask for is what starves keyframes and produces the pulse stream viewers see.
        val configured = EncoderConfig.configure(
            targetW, targetH, profile.bitrateBps, profile.fps, I_FRAME_INTERVAL_S, TAG,
            preferVbr = true, codec = codec)
            ?: return false

        return runCatching {
            val enc = configured.first
            val surface = enc.createInputSurface()
            enc.start()
            encoder = enc
            inputSurface = surface

            mediaProjection.registerCallback(projectionCallback, null)
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "TAKPilot2Stream",
                targetW, targetH, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null,
            )

            running = true
            drainThread = Thread({ drainLoop() }, "ScreenCaptureEncoder").apply { start() }
            // Reports the variant that actually configured, not what was asked for. This line
            // used to say "CBR" unconditionally, which would now be false — and a log that
            // states the rate-control mode from a hardcoded string is worse than one that omits
            // it, because it is the first thing read when the pulse is being investigated.
            AppLog.i(TAG, "screen capture [${profile.name}] ${codec.label}: " +
                    "${screenW}x$screenH -> ${targetW}x$targetH " +
                    "@ ${profile.fps}fps ${profile.bitrateBps / 1000}kbps, ${I_FRAME_INTERVAL_S}s IDR, " +
                    "encoder variant: ${configured.second}")
            true
        }.onFailure {
            AppLog.e(TAG, "screen capture start failed: ${it.message}", it)
            release()
        }.getOrDefault(false)
    }

    /** Ask the encoder for an IDR now — arms the RTSP packetizer on connect / heals viewers. */
    fun requestSyncFrame() {
        runCatching {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure { AppLog.w(TAG, "requestSyncFrame failed: ${it.message}") }
    }

    /**
     * Tearing the codec down used to race the drain thread. `join(500)` is a TIMEOUT, not
     * a guarantee — and [onEncoded] pushes each frame to the RTSP client, i.e. it does network
     * I/O, so overrunning 500 ms is ordinary rather than exotic. When the join timed out,
     * `encoder.stop()`/`release()` ran WHILE [drainLoop] was still inside
     * `dequeueOutputBuffer`/`releaseOutputBuffer` on the same MediaCodec. That is undefined
     * behaviour, and it was invisible twice over: `runCatching` swallowed the exception here,
     * and the loop's own catch stays quiet because `running` is already false.
     *
     * So the codec is never released out from under a live drain thread. If the thread has not
     * stopped by the deadline, teardown is HANDED TO IT — [releaseCodec] is one-shot, so
     * whichever side gets there first does it exactly once.
     */
    fun release() {
        running = false
        runCatching { mediaProjection.unregisterCallback(projectionCallback) }
        // Released first on purpose: it cuts off the codec's input, so the loop runs dry and
        // reaches its exit check instead of being handed more work while we wait for it.
        runCatching { virtualDisplay?.release() }; virtualDisplay = null

        val t = drainThread
        drainThread = null
        if (t != null && t !== Thread.currentThread()) {
            // Armed BEFORE the join, not after. If the thread finishes anywhere in the join
            // window, its finally block has to see the flag already set — arming it afterwards
            // leaves a gap where the loop reads `false` on its way out and this side then
            // returns believing the loop will do it, so the codec is released by NOBODY.
            releaseCodecOnDrainExit = true
            runCatching { t.join(DRAIN_JOIN_TIMEOUT_MS) }
            if (t.isAlive) {
                AppLog.w(TAG, "drain thread still running after ${DRAIN_JOIN_TIMEOUT_MS}ms — " +
                    "handing codec teardown to it rather than racing it")
                return
            }
        }
        // The thread is gone (or there never was one, or we ARE it): safe to do it here. If the
        // loop's finally already ran, releaseCodec is one-shot and this is a no-op.
        releaseCodec()
    }

    /** Set when [release] gave up waiting; [drainLoop] then owns the teardown on its way out. */
    @Volatile private var releaseCodecOnDrainExit = false
    private val codecLock = Any()
    private var codecReleased = false

    /** One-shot codec/surface teardown. Safe to call from either the caller of [release] or
     *  the drain thread; only the first call does the work. */
    private fun releaseCodec() {
        synchronized(codecLock) {
            if (codecReleased) return
            codecReleased = true
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }; encoder = null
            runCatching { inputSurface?.release() }; inputSurface = null
        }
    }

    private fun drainLoop() {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = enc.dequeueOutputBuffer(info, 100_000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    idx >= 0 -> {
                        val outBuf = enc.getOutputBuffer(idx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                handleCodecConfig(outBuf, info)
                            } else {
                                onEncoded(outBuf, info)
                                encFrameCount++
                                encBytesSinceLog += info.size
                                if (encFrameCount % 150 == 0) {
                                    AppLog.v(TAG, "[${profile.name}] $encFrameCount frames encoded, " +
                                            "${encBytesSinceLog / 1024}KB in last 150")
                                    encBytesSinceLog = 0
                                }
                            }
                        }
                        enc.releaseOutputBuffer(idx, false)
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) {
                AppLog.w(TAG, "drain loop error: ${t.message}")
                notifyGone("drain loop error: ${t.message}")
            }
        } finally {
            // If release() stopped waiting for this thread, it deliberately left the codec
            // alone rather than tearing it down underneath us. Nothing else is touching the
            // codec now that this loop is finished, so the teardown is ours to complete.
            if (releaseCodecOnDrainExit) {
                AppLog.i(TAG, "drain thread exiting — completing the deferred codec release")
                releaseCodec()
            }
        }
    }

    /** Pulls VPS/SPS/PPS out of the codec-config buffer — three NALs on H.265, two on H.264.
     *  The classification lives in [EncoderConfig.splitParams] so both encode paths share it. */
    private fun handleCodecConfig(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        buf.get(bytes)
        val params = EncoderConfig.splitParams(bytes, codec.isHevc, TAG) ?: return
        onParamsReady(ByteBuffer.wrap(params.sps), ByteBuffer.wrap(params.pps),
            params.vps?.let { ByteBuffer.wrap(it) })
    }

    companion object {
        private const val TAG = "ScreenCaptureEncoder"
        private const val I_FRAME_INTERVAL_S = 2

        /**
         * How long [release] waits for the drain thread before handing it the codec teardown.
         * Kept short because release() can run on the main thread (a give-up lands there), and
         * blocking the UI is worse than deferring the teardown by a few frames — handing the
         * teardown to the drain thread makes overrunning safe rather than merely unlikely.
         */
        private const val DRAIN_JOIN_TIMEOUT_MS = 500L
    }
}
