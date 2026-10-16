package com.dji.sdk.sample.tak

import com.taklite.util.AppLog
import dji.sdk.keyvalue.key.DJIRemoteControllerKey
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager

/**
 * The RC Plus 2's custom buttons — C1, C2, C3 — which reach the application only as MSDK keys
 * and never as Android input (verified silent in `getevent` with a live capture bracketed by
 * presses of the L1 and R1 shoulder buttons, which do appear there).
 *
 * ONE OWNER: a listener slot holds one client and a second registration
 * silently replaces the first, so the keys are listened here and nowhere else, and the flight
 * screen is FED through [onC1]. Armed by the flight screen while it is up, disarmed when it
 * goes — the holder is this object, so a stale activity cannot keep the listen alive.
 *
 * Today only C1 has a consumer: the IR toggle, beside L3. The others are
 * listened and logged so the log shows they reach the application; a function waits for a
 * decision, not for plumbing. The keys are `DJIRemoteControllerKey`'s, not
 * `RemoteControllerKey`'s — checked against the 5.18 jar with javap.
 */
object RcButtons {
    private const val TAG = "RcButtons"

    /** Fed on the DOWN edge of C1, on the SDK's thread — marshal to the UI yourself. */
    @Volatile var onC1: (() -> Unit)? = null

    /**
     * Fed on the DOWN edge of the RIGHT SHOULDER's shutter key (`KeyShutterButtonDown`).
     *
     * ⚠ THIS, NOT THE ANDROID KEY. The shoulder also reaches the activity as
     * `KEYCODE_BUTTON_R1`, but the focus half-press and the full press arrive as the SAME
     * Android key (measured), so acting on that would take a still on every focus.
     * The MSDK key is how DJI Pilot 2 takes the still; the firmware does nothing on its own
     * with another app in front (measured: no still in PHOTO mode). Whether the
     * half-press fires this key too is what the log will show on the first press.
     */
    @Volatile var onShutter: (() -> Unit)? = null

    private val holder = Any()
    private var armed = false

    fun arm() {
        if (armed) return
        armed = true
        DJIRemoteControllerKey.KeyCustomButton1Down.create().listen(holder) { down ->
            if (down == true) {
                AppLog.i(TAG, "controller button C1 — IR toggle")
                onC1?.invoke()
            }
        }
        DJIRemoteControllerKey.KeyCustomButton2Down.create().listen(holder) { down ->
            if (down == true) AppLog.i(TAG, "controller button C2 — no function assigned")
        }
        DJIRemoteControllerKey.KeyCustomButton3Down.create().listen(holder) { down ->
            if (down == true) AppLog.i(TAG, "controller button C3 — no function assigned")
        }
        DJIRemoteControllerKey.KeyShutterButtonDown.create().listen(holder) { down ->
            if (down == true) {
                AppLog.i(TAG, "controller SHUTTER key down (right shoulder) — still")
                onShutter?.invoke()
            }
        }
        DJIRemoteControllerKey.KeyRCShutterButtonLongPress.create().listen(holder) { v ->
            if (v == true) AppLog.i(TAG, "controller SHUTTER long press — no function assigned")
        }
        // The left shoulder's own MSDK key, logged beside the Android path the activity acts
        // on, so the log can say whether the two always arrive together.
        DJIRemoteControllerKey.KeyRecordButtonDown.create().listen(holder) { down ->
            if (down == true) AppLog.i(TAG, "controller RECORD key down (left shoulder) — MSDK path")
        }
        AppLog.i(TAG, "C1-C3, shutter and record listens armed")
    }

    fun disarm() {
        if (!armed) return
        armed = false
        runCatching { KeyManager.getInstance().cancelListen(holder) }
        AppLog.i(TAG, "C1-C3 listens cancelled")
    }
}
