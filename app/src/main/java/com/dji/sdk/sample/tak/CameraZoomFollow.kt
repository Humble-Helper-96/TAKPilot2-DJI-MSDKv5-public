package com.dji.sdk.sample.tak

import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.manager.KeyManager
import com.taklite.util.AppLog

/**
 * Follows the camera's OWN zoom ratio, however it was commanded.
 *
 * ⚠ THE RIGHT DIAL IS WIRED TO THE CAMERA BY DJI'S FIRMWARE — the same path as the hardware
 * record button. Measured: with this app's own dial listener stepping the
 * ladder, the camera ALSO ramped continuously under the firmware's command, smoothly zoomed
 * itself to 1x with no app involvement, and left the zoom pill claiming 7X. This app cannot
 * disable that path (and must not — the controller stays OEM-intact by project rule), so an
 * app-side dial handler is a fight with the firmware that the firmware always wins.
 *
 * So the app FOLLOWS instead: this listener tracks KeyCameraZoomRatios and hands every change
 * to the flight screen, which updates the pill, clears any display crop, and re-reads the
 * camera-reported FOV. The pill can then never disagree with the picture, whoever moved it.
 *
 * Also measured: driven by the dial, the camera sits BETWEEN its gears (the clamping to
 * [1, 3, 7, 14, 28] applies to set-requests, not to native motion) and reports its true
 * hybrid focal length while it moves — so the FOV stays honest at any ratio the dial finds.
 *
 * This object owns the SDK listen (one owner per listener slot). The flight screen arms it in onResume and
 * disarms in onPause.
 */
object CameraZoomFollow {

    private const val TAG = "CameraZoomFollow"
    private val listenHolder = Any()

    // ONE Handler, not one per event. The dial can fire this listener many times a second
    // while it's being turned, and a fresh Handler(Looper) per event was an allocation (plus
    // its own Looper lookup) on every single tick of a continuous zoom sweep for no reason —
    // the main Looper does not change.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Called on the MAIN thread with the camera's current ratio whenever it changes. */
    fun arm(onZoom: (Double) -> Unit) {
        val key = KeyTools.createCameraKey(
            CameraKey.KeyCameraZoomRatios, ComponentIndexType.LEFT_OR_MAIN,
            CameraLensType.CAMERA_LENS_ZOOM)
        KeyManager.getInstance().listen(key, listenHolder,
            CommonCallbacks.KeyListener<Double> { _, value ->
                if (value != null && value > 0) {
                    mainHandler.post { onZoom(value) }
                }
            })
        AppLog.i(TAG, "following the camera's zoom ratio")
    }

    fun disarm() {
        KeyManager.getInstance().cancelListen(listenHolder)
        AppLog.i(TAG, "zoom follow disarmed")
    }
}
