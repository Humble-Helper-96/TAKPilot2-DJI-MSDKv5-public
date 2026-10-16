package com.dji.sdk.sample.tak

import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import com.taklite.util.AppLog

/**
 * The camera's OWN field of view, from its reported focal length — the calibration knob's
 * replacement, and the first correct FOV above 1x.
 *
 * MEASURED on the aircraft, stepping the zoom ladder rung by rung:
 *
 *   KeyCameraHybridZoomFocalLength  700 / 1680 / 3360 / 6720 at rungs 3/7/14/28
 *   KeyCameraOpticalZoomFocalLength 240 while the WIDE camera is live
 *   KeyThermalFocalLength 52, KeyCameraIRFocalLength 527, constant
 *
 * The unit is the 35mm-EQUIVALENT focal length (x10 on hybrid/optical/ir; x1 on thermal), and
 * the hybrid value is LIVE — it includes the current zoom. Note 700 at "3X": the camera's real
 * gear is 2.917x, which dividing a base FOV by the nominal rung could never know. The
 * equivalence convention is full-frame DIAGONAL (43.27mm), confirmed against DJI's own spec:
 * 24mm equiv -> 84 deg diagonal, the published wide FOV; 52.7mm -> ~45 deg, the published
 * thermal FOV.
 *
 * So:  dfov = 2*atan(43.27 / (2*f35))   and the horizontal follows from the live picture
 * shape ([TakBridgeHolder.setVideoAspect]), the same identity the one-knob calibration uses.
 *
 * `KeyRetrieveLensFOV` — the key that SHOULD answer this directly — is refused for every lens
 * on this airframe (measured, 14 refusals per lens). The focal-length route is the one the
 * aircraft actually serves.
 *
 * ⚠ ASYNC READS ONLY: the one-argument getValue reads a cache that is empty
 * on a cold start. And the answer is fed through a sanity gate — a camera that reports
 * something absurd must not send every marker to infinity.
 */
object CameraFov {

    private const val TAG = "CameraFov"
    private val MAIN_CAM = ComponentIndexType.LEFT_OR_MAIN

    /** Full-frame diagonal, mm — the reference the 35mm equivalence is defined against. */
    private const val FF_DIAGONAL_MM = 43.27

    /** The wide camera's 35mm-equivalent focal length. MEASURED (optical=240 while wide is
     *  live), and it matches DJI's published 24mm; used directly because the wide lens is
     *  fixed and the hybrid key follows the ZOOM lens, which idles wherever it was left. */
    private const val WIDE_F35_MM = 24.0

    /**
     * Refreshes [TakBridgeHolder]'s camera-reported FOV for the live source.
     *
     * @param irLive  true when the INFRARED camera is the live stream source.
     * @param zoomRatio the ladder's read-back — only consulted to decide wide vs zoom lens.
     */
    @Volatile
    private var lastRatio: Double = 0.0

    fun refresh(irLive: Boolean, zoomRatio: Double) {
        lastRatio = zoomRatio
        // The lens is told here, where every source change already arrives, so the FOV
        // fallback follows it at once — before the read below has answered, and even if the
        // aircraft refuses it. See TakBridgeHolder.activeLens.
        TakBridgeHolder.activeLens = if (irLive) CameraLens.IR else CameraLens.EO
        when {
            irLive -> {
                KeyManager.getInstance().getValue(
                    KeyTools.createCameraKey(
                        CameraKey.KeyCameraIRFocalLength, MAIN_CAM,
                        CameraLensType.CAMERA_LENS_THERMAL),
                    object : CommonCallbacks.CompletionCallbackWithParam<Int> {
                        override fun onSuccess(value: Int?) {
                            adopt(value?.let { it / 10.0 }, "thermal")
                        }

                        override fun onFailure(error: IDJIError) {
                            AppLog.w(TAG, "thermal focal read refused: ${error.description()}")
                        }
                    })
            }

            zoomRatio > 1.0 -> {
                KeyManager.getInstance().getValue(
                    KeyTools.createCameraKey(
                        CameraKey.KeyCameraHybridZoomFocalLength, MAIN_CAM,
                        CameraLensType.CAMERA_LENS_ZOOM),
                    object : CommonCallbacks.CompletionCallbackWithParam<Int> {
                        override fun onSuccess(value: Int?) {
                            adopt(value?.let { it / 10.0 }, "zoom")
                        }

                        override fun onFailure(error: IDJIError) {
                            AppLog.w(TAG, "hybrid focal read refused: ${error.description()}")
                        }
                    })
            }

            else -> adopt(WIDE_F35_MM, "wide")
        }
    }

    /**
     * The camera's live 35mm-equivalent focal length, or null before it has answered.
     *
     * ⚠ THIS IS THE ZOOM PILL'S SOURCE OF TRUTH, NOT `KeyCameraZoomRatios`. The ratio key's
     * SCALE CHANGED WITHIN A SINGLE DAY: for a whole morning `f35 = ratio x 24` held exactly
     * across twenty samples, and by the afternoon the same camera at its widest 24.0mm was
     * reporting a ratio of 1.51 — a divisor of about 15.9. What shifts it is not known. The pill
     * read 1.5X while the picture was as wide as the camera goes, twice on two different days.
     *
     * The focal length has never once disagreed with the picture, so the pill is derived from it
     * and the ratio key is no longer displayed at all.
     */
    @Volatile
    var lastF35Mm: Double? = null
        private set

    /** Told when the focal length moves, so a screen can repaint the zoom readout. */
    @Volatile
    var onFovChanged: (() -> Unit)? = null

    /**
     * The zoom to SHOW: how much narrower the view is than the wide framing.
     *
     * Null until the camera answers. Thermal is excluded by the caller — its focal length is
     * fixed and a "ratio" against the visible wide lens would be meaningless.
     */
    fun displayRatio(): Double? = lastF35Mm?.let { (it / WIDE_F35_MM).coerceAtLeast(1.0) }

    private fun adopt(f35mm: Double?, lens: String) {
        if (f35mm == null || !f35mm.isFinite() || f35mm < 4.0 || f35mm > 2000.0) {
            AppLog.w(TAG, "$lens focal length implausible ($f35mm mm) — keeping the previous FOV")
            return
        }
        val dfov = 2.0 * Math.toDegrees(Math.atan(FF_DIAGONAL_MM / (2.0 * f35mm)))
        TakBridgeHolder.setCameraFov(dfov)
        if (lens != "thermal") {
            lastF35Mm = f35mm
            onFovChanged?.invoke()
        }
        // The ratio is logged beside the focal length so the two feeds can always be compared
        // after the fact — their disagreement is what a wrong zoom pill looks like in a log.
        AppLog.i(TAG, "camera FOV adopted: $lens f35=${"%.1f".format(f35mm)}mm -> " +
            "dfov=${"%.1f".format(dfov)} deg (h=${"%.1f".format(TakBridgeHolder.currentHFov())})" +
            " [ratio ${"%.2f".format(lastRatio)}]")
    }
}
