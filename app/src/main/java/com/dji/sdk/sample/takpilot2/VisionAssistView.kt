package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import com.taklite.util.AppLog

/**
 * The obstacle-sensing cameras, rendered — DJI Pilot 2's small grey corner view.
 *
 * Deliberately a near-copy of [FpvTextureView] rather than a shared base class: this is a PROBE
 * and it must not be able to break the main video feed, which is the one thing on the flight
 * screen a pilot cannot fly without. If the feature proves out, the two
 * merge; if it does not, this file is deleted and the FPV view was never touched.
 *
 * The only real difference is the component index: the same `putCameraStreamSurface` call the
 * main feed uses, pointed at [ComponentIndexType.VISION_ASSIST].
 *
 * ⚠ IT CANNOT TURN ITSELF ON. Rendering a surface is not enough — the AIRCRAFT has to be told to
 * produce the stream, which is [VisionAssist.setEnabled]. A blank view here with vision assist
 * off is expected, not a fault.
 */
class VisionAssistView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : TextureView(context, attrs, defStyle), TextureView.SurfaceTextureListener {

    private companion object {
        const val TAG = "TP2VisionView"
        val SOURCE = ComponentIndexType.VISION_ASSIST
    }

    private var surface: Surface? = null
    @Volatile private var sawFrame = false

    /** Frames seen, so the probe can report whether the stream is actually arriving. */
    @Volatile
    var frames = 0L
        private set

    /** Told on the first frame, so the screen can say the stream arrived. */
    var onFirstFrame: ((Int, Int) -> Unit)? = null

    private val streamManager: ICameraStreamManager
        get() = MediaDataCenter.getInstance().cameraStreamManager

    private val receiveListener = ICameraStreamManager.ReceiveStreamListener { _, _, _, info ->
        frames++
        if (!sawFrame) {
            sawFrame = true
            AppLog.i(TAG, "VISION ASSIST first frame: ${info.width}x${info.height} " +
                "${info.mimeType} — the aircraft IS serving this stream")
            runCatching { onFirstFrame?.invoke(info.width, info.height) }
        }
    }

    init { surfaceTextureListener = this }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(st)
        surface = s
        runCatching {
            streamManager.putCameraStreamSurface(
                SOURCE, s, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE)
            streamManager.addReceiveStreamListener(SOURCE, receiveListener)
            AppLog.i(TAG, "registered a ${width}x$height surface for $SOURCE")
        }.onFailure { AppLog.w(TAG, "vision assist registration failed: ${it.message}") }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        val s = surface ?: return
        runCatching {
            streamManager.removeCameraStreamSurface(s)
            streamManager.putCameraStreamSurface(
                SOURCE, s, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE)
        }.onFailure { AppLog.w(TAG, "vision assist re-registration failed: ${it.message}") }
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        runCatching {
            surface?.let { streamManager.removeCameraStreamSurface(it) }
            streamManager.removeReceiveStreamListener(receiveListener)
        }
        surface?.release()
        surface = null
        sawFrame = false
        AppLog.i(TAG, "vision assist surface released after $frames frame(s)")
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
}
