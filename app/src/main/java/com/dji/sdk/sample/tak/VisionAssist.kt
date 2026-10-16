package com.dji.sdk.sample.tak

import dji.sdk.keyvalue.value.flightassistant.VisionAssistDirection
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import com.taklite.util.AppLog

/**
 * THE AIRCRAFT'S OBSTACLE-SENSING CAMERAS as a picture — DJI Pilot 2's small grey wide-angle
 * view in the corner, which follows whichever way the aircraft is travelling.
 *
 * ⚠ THIS IS NOT A PAYLOAD CAMERA. It is the vision system that does obstacle avoidance, which is
 * why the picture is monochrome and very wide. It arrives on its own component index,
 * [ComponentIndexType.VISION_ASSIST], through the SAME `putCameraStreamSurface` call the main FPV
 * view already uses — so the rendering is solved; the questions are whether the aircraft serves
 * the stream at all and what it costs the main video feed.
 *
 * ⚠ "FOLLOWS THE DIRECTION OF TRAVEL" IS DJI'S OWN MODE, not something to build from a velocity
 * vector: [VisionAssistDirection.AUTO]. The others are FRONT, BACK, LEFT, RIGHT, UP, DOWN.
 *
 * ⚠ THIS OBJECT OWNS THE VISION-ASSIST LISTENER (one owner per listener slot). Consumers are fed from
 * [onChanged]; nothing else may subscribe.
 *
 * ⚠ THE TWO STREAMS SHARE ONE DOWNLINK. `ICameraStreamManager` exposes `setStreamPriority` and
 * `setStreamEncoderBitrate` per component, which only makes sense if they compete — and a
 * flight has already shown a message upload slowing the link. **The cost to the main FPV
 * feed is the real question about this feature, not the plumbing.** Nothing here changes priority
 * or bitrate yet; measure first.
 */
object VisionAssist {

    private const val TAG = "VisionAssist"

    private val streamManager: ICameraStreamManager
        get() = MediaDataCenter.getInstance().cameraStreamManager

    /** What the aircraft says: is the vision-assist view switched on. Null until it answers. */
    @Volatile
    var enabled: Boolean? = null
        private set

    /** The direction the aircraft is showing. Null until it answers. */
    @Volatile
    var direction: VisionAssistDirection? = null
        private set

    /**
     * The directions THIS aircraft offers, as reported by the aircraft.
     *
     * ⚠ THIS IS THE FEASIBILITY ANSWER. The M4D capability file says nothing about vision assist
     * either way, and this airframe has already advertised one thing (the speaker's TTS) that
     * MSDK then refused outright. An empty list here means the feature is not available however
     * good the API looks.
     */
    @Volatile
    var available: List<VisionAssistDirection> = emptyList()
        private set

    @Volatile
    var onChanged: (() -> Unit)? = null

    private var listener: ICameraStreamManager.VisionAssistStatusListener? = null

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private fun notifyChanged() {
        val cb = onChanged ?: return
        ui.post { cb() }
    }

    /** Attaches the status listener, once. */
    fun start() {
        if (listener != null) return
        val l = object : ICameraStreamManager.VisionAssistStatusListener {
            override fun onVisionAssistEnabled(on: Boolean) {
                AppLog.i(TAG, "the aircraft reports vision assist enabled=$on")
                enabled = on
                notifyChanged()
            }

            override fun onVisionAssistViewDirectionRangeUpdated(
                range: List<VisionAssistDirection>,
            ) {
                available = range.toList()
                AppLog.i(TAG, "the aircraft offers these directions: $available")
                notifyChanged()
            }

            override fun onVisionAssistViewDirectionUpdated(d: VisionAssistDirection) {
                AppLog.i(TAG, "vision assist direction is now $d")
                direction = d
                notifyChanged()
            }
        }
        runCatching { streamManager.addVisionAssistStatusListener(l) }
            .onSuccess { listener = l; AppLog.i(TAG, "vision assist listener attached") }
            .onFailure { AppLog.w(TAG, "could not attach the listener: ${it.message}") }
    }

    /**
     * Switches the aircraft's vision-assist view on or off.
     *
     * ⚠ THIS IS A WRITE TO THE AIRCRAFT and it happens on an explicit pilot action only, never on
     * a timer. The result is the aircraft's, reported through the listener above
     * — [enabled] is never set from the request.
     */
    fun setEnabled(on: Boolean, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        AppLog.i(TAG, "asking the aircraft to turn vision assist ${if (on) "ON" else "OFF"}")
        runCatching {
            streamManager.enableVisionAssist(on, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "vision assist ${if (on) "on" else "off"}: accepted")
                    onResult(true, null)
                }

                override fun onFailure(error: IDJIError) {
                    val why = error.description()?.takeIf { it.isNotBlank() }
                        ?: error.errorCode() ?: "refused"
                    AppLog.w(TAG, "vision assist refused: $why")
                    onResult(false, why)
                }
            })
        }.onFailure {
            AppLog.w(TAG, "enableVisionAssist threw: ${it.message}")
            onResult(false, it.message)
        }
    }

    /** Points the view. [VisionAssistDirection.AUTO] follows the direction of travel. */
    fun setDirection(d: VisionAssistDirection, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        AppLog.i(TAG, "asking for vision assist direction $d")
        runCatching {
            streamManager.setVisionAssistViewDirection(d,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() { onResult(true, null) }
                    override fun onFailure(error: IDJIError) {
                        val why = error.description()?.takeIf { it.isNotBlank() }
                            ?: error.errorCode() ?: "refused"
                        AppLog.w(TAG, "direction $d refused: $why")
                        onResult(false, why)
                    }
                })
        }.onFailure { onResult(false, it.message) }
    }

    /** Detaches and drops state. */
    fun stop() {
        listener?.let { l -> runCatching { streamManager.removeVisionAssistStatusListener(l) } }
        listener = null
        onChanged = null
        enabled = null
        direction = null
        available = emptyList()
        AppLog.i(TAG, "vision assist listener detached")
    }
}
