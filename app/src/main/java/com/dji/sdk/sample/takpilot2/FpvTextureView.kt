package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import com.taklite.util.AppLog
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager

/**
 * Live FPV view — MSDK v5 edition.
 *
 * There is no custom MediaCodec pipeline here (Annex-B reassembly, slice parsing, decoder
 * health, an IDR-request lever): MSDK v5 decodes on the SDK side.
 * [ICameraStreamManager.putCameraStreamSurface] renders the live feed straight into this
 * view's SurfaceTexture, scaled CENTER_INSIDE.
 *
 * What this file owns:
 * - The surface lifecycle (register on available, re-register on size change, remove on
 *   destroy).
 * - [onFirstFrame], for the "NO VIDEO" cover — driven by the raw stream listener, which
 *   fires per encoded frame without touching the decode path.
 * - [onVideoRectChanged], the on-screen rectangle the video actually occupies after
 *   CENTER_INSIDE letterboxing — the AR overlay and crosshair align to it.
 * - [requestResync] — re-registers the surface, which is the whole reset behind Video
 *   Re-Sync.
 */
class FpvTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private companion object {
        const val TAG = "TP2Fpv"
        val CAMERA = ComponentIndexType.LEFT_OR_MAIN
    }

    /** Fired once when the first frame of a (re)started stream arrives. */
    var onFirstFrame: (() -> Unit)? = null

    /** Fired (on the UI thread) when the letterboxed video rectangle changes. */
    var onVideoRectChanged: ((RectF) -> Unit)? = null

    private var surface: Surface? = null
    @Volatile private var streamW = 0
    @Volatile private var streamH = 0
    @Volatile private var sawFrame = false

    private val streamManager: ICameraStreamManager
        get() = MediaDataCenter.getInstance().cameraStreamManager

    /**
     * Raw encoded-stream listener: cheap per-frame signal carrying the stream dimensions.
     * Used for first-frame detection and the letterbox rect; the pixels themselves go
     * through the surface DJI renders into.
     */
    private val receiveListener = ICameraStreamManager.ReceiveStreamListener { _, _, _, info ->
        if (!sawFrame) {
            sawFrame = true
            AppLog.i(TAG, "first frame: ${info.width}x${info.height} ${info.mimeType}")
            runCatching { onFirstFrame?.invoke() }
        }
        if (info.width != streamW || info.height != streamH) {
            streamW = info.width
            streamH = info.height
            post { recomputeVideoRect() }
        }
    }

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        AppLog.i(TAG, "surface available ${width}x$height — registering with camera stream manager")
        val s = Surface(st)
        surface = s
        runCatching {
            streamManager.putCameraStreamSurface(
                CAMERA, s, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE)
            streamManager.addReceiveStreamListener(CAMERA, receiveListener)
        }.onFailure { AppLog.w(TAG, "camera stream registration failed: ${it.message}") }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        // DJI scales to the registered dimensions, so a resized surface must re-register.
        AppLog.i(TAG, "surface resized ${width}x$height — re-registering")
        val s = surface ?: return
        runCatching {
            streamManager.removeCameraStreamSurface(s)
            streamManager.putCameraStreamSurface(
                CAMERA, s, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE)
        }.onFailure { AppLog.w(TAG, "camera stream re-registration failed: ${it.message}") }
        recomputeVideoRect()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        AppLog.i(TAG, "surface destroyed — removing from camera stream manager")
        runCatching {
            surface?.let { streamManager.removeCameraStreamSurface(it) }
            streamManager.removeReceiveStreamListener(receiveListener)
        }
        surface?.release()
        surface = null
        sawFrame = false
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /**
     * Video Re-Sync: drop and re-register the surface and stream listener. In v5 the
     * decoder is DJI's; a fresh registration is the whole reset.
     */
    fun requestResync() {
        AppLog.i(TAG, "resync requested")
        val s = surface ?: return
        sawFrame = false
        runCatching {
            streamManager.removeCameraStreamSurface(s)
            streamManager.removeReceiveStreamListener(receiveListener)
            streamManager.putCameraStreamSurface(
                CAMERA, s, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE)
            streamManager.addReceiveStreamListener(CAMERA, receiveListener)
        }.onFailure { AppLog.w(TAG, "resync failed: ${it.message}") }
    }

    /**
     * The rectangle the video occupies inside this view after CENTER_INSIDE scaling. The same
     * aspect-fit math feeds the overlay alignment AND the view transform below, since DJI does
     * the scaling itself.
     *
     * ⚠ A 16:9 stream in the RC Plus 2's 16:10 screen does not fit exactly. Whatever is done
     * about that must keep the aspect ratio and the field of view the AR projection depends
     * on: a transform the overlays are told about (see [recomputeVideoRect]), never a silent
     * crop that would change the FOV and put every projected marker in the wrong place.
     *
     * The same transform goes to the texture and to the rectangle the overlays receive. They
     * MUST move together: the crosshair is the aiming reference for a marker drop, so if the
     * picture moves and the rectangle does not, a marker dropped at the crosshair no longer
     * lands under it.
     */
    /**
     * The display crop for the hybrid zoom, 1.0 = none. Set from the flight screen after a
     * zoom read-back; the transform recomputes immediately.
     */
    @Volatile
    private var digitalCrop = 1f

    fun setDigitalCrop(crop: Double) {
        val c = crop.toFloat().coerceIn(1f, 4f)
        if (c == digitalCrop) return
        digitalCrop = c
        recomputeVideoRect()
    }

    private fun recomputeVideoRect() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f || streamW <= 0 || streamH <= 0) return
        val viewAspect = vw / vh
        val videoAspect = streamW.toFloat() / streamH.toFloat()
        // The FOV model derives its VERTICAL from the horizontal and this shape, so it has to
        // come from the same number that letterboxes the picture — otherwise the two disagree
        // about what the pilot is looking at. It changes with the lens: the thermal camera is
        // 640x512 (5:4), the visible one 16:9.
        com.dji.sdk.sample.tak.TakBridgeHolder.setVideoAspect(videoAspect.toDouble())
        // FILL-CROP FOR A WIDER-THAN-THE-SCREEN PICTURE. The 16:9 stream fitted to this 16:10
        // panel's width leaves 120px = 48dp of no picture, parked at the top under the band.
        // The picture instead fills the HEIGHT and overflows the width by about 107px a side,
        // which is cropped: a strip of picture lost at each edge, no bar. DJI still letterboxes
        // INTO the surface (CENTER_INSIDE above), so the fill is this view's own transform —
        // the texture is magnified about the VIEW CENTRE by `fill`, which is also the frame
        // centre, so the crosshair keeps aiming at the same pixel and a marker dropped there
        // still lands under it. The rect handed to the overlays is where the WHOLE frame is at
        // that scale: it deliberately overflows the view, so a target cropped off screen
        // projects outside the view and becomes an edge arrow (ArOverlayView.project).
        //
        // ⚠ A TALLER-THAN-THE-SCREEN PICTURE STAYS PILLARBOXED. The thermal stream is 5:4;
        // filling the height would already be the case, and filling the WIDTH would crop
        // 13 % off the top and bottom of a thermal frame. The bars sit at the sides there.
        val fitWider = videoAspect >= viewAspect
        val fit = if (fitWider) {
            val h = vw / videoAspect
            RectF(0f, (vh - h) / 2f, vw, (vh + h) / 2f)
        } else {
            val w = vh * videoAspect
            RectF((vw - w) / 2f, 0f, (vw + w) / 2f, vh)
        }
        val fill = if (fitWider) vh / fit.height() else 1f
        val rect = RectF(fit)
        if (fill > 1.001f) {
            val cx = vw / 2f
            val cy = vh / 2f
            rect.set(cx - fit.width() * fill / 2f, cy - fit.height() * fill / 2f,
                     cx + fit.width() * fill / 2f, cy + fit.height() * fill / 2f)
        }
        // No bottom-edge shift: nothing is letterboxed any more.
        val dy = 0f

        // setTransform touches the view, thus it goes to the UI thread — this method also
        // runs from the stream listener, which does not.
        //
        // THE DIGITAL CROP composes here: a centred scale about the
        // rect's middle, on top of the letterbox translate. The camera only has gears
        // 1/3/7/14/28, so the ladder's intermediate levels are this crop riding the nearest
        // gear below. Centred about the RECT, not the view: the crosshair sits at the rect's
        // centre, so a centred crop leaves the aiming point on the same pixel — a marker
        // dropped at the crosshair lands where it did uncropped, and the screen-capture
        // stream carries the same picture the pilot sees.
        post {
            setTransform(Matrix().apply {
                // The fill about the view centre, then the hybrid ladder's digital crop
                // about the rect's centre — the same point, so the two compose as one zoom.
                if (fill > 1.001f) setScale(fill, fill, vw / 2f, vh / 2f)
                val c = digitalCrop
                if (c > 1.001f) postScale(c, c, rect.centerX(), rect.centerY())
            })
        }

        AppLog.d(TAG, "video rect: $rect (stream ${streamW}x$streamH in view ${width}x$height," +
            " fill x%.3f, %s)".format(fill, if (fill > 1.001f) "cropped at the sides" else "pillarboxed"))
        runCatching { onVideoRectChanged?.invoke(rect) }
    }
}
