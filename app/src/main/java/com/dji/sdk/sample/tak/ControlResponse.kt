package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

/**
 * How fast the controller drives the camera: one FIXED number per response mode, written to the
 * aircraft at connect and on an explicit change.
 *
 * The SDK call is `GimbalKey.KeyPitchControlMaxSpeed`, an Integer key that can get, set and
 * listen (the MSDK v5 form of `gimbal.setControllerMaxSpeed(Axis.PITCH, n)`).
 *
 * ⚠ WHY FIXED, AND NOT A MULTIPLIER. An earlier design read the gimbal's current speed and
 * multiplied it by 1.5. That compounded, because the value it read was the one it had written
 * last time, and the logs caught it climbing 22 → 33 → 49 → 73 → 100 until it pinned
 * at the ceiling. The camera handled differently on every flight and the pilot had no way to
 * know which speed they had. A fixed target is the whole point: the same selection gives the
 * same feel every time, whatever the last flight or the DJI app left behind.
 *
 * ⚠ THE VALUES ARE A STARTING POINT, NOT A MEASUREMENT. They began as a Mini 2's figures
 * (whose gimbal reports 1..100) and both modes have since felt right on a Matrice 4TD. The
 * read-back line in Pre-Flight reports what the aircraft actually took, and that is the number
 * the next adjustment starts from. Two named constants, so they are easy to move.
 *
 * ⚠ NOT CLAMPED. v5 exposes the value as a
 * key and does not publish a capability range alongside it, so there is nothing to clamp
 * against. The write is sent as-is and the READ-BACK is what proves what the aircraft took —
 * if an airframe reports a different scale, the read-back will show it and these constants
 * move. Trust the aircraft's answer, never the request.
 */
object ControlResponse {

    private const val TAG = "TP2Control"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_MODE = "control_response_precision"

    /** Gimbal pitch speed for each mode, 1..100. Both felt right on the Matrice 4TD. */
    private const val NORMAL_PITCH_SPEED = 35
    private const val PRECISION_PITCH_SPEED = 15

    enum class Mode(val label: String, val pitchSpeed: Int) {
        NORMAL("Normal", NORMAL_PITCH_SPEED),
        PRECISION("Precision", PRECISION_PITCH_SPEED),
    }

    /** What the AIRCRAFT reports holding, read back after a write. Null until one lands. */
    @Volatile
    var aircraftPitchSpeed: Int? = null
        private set

    fun saved(context: Context): Mode =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MODE, false)
        ) Mode.PRECISION else Mode.NORMAL

    fun save(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MODE, mode == Mode.PRECISION).apply()
        AppLog.i(TAG, "control response set to ${mode.label}")
    }

    /**
     * Pushes the saved mode to the gimbal, then reads back what it took.
     *
     * Safe to call with no aircraft — the key write fails and is logged. Called at connect and
     * whenever the pilot changes the setting. **Never on a timer:** this is a write to the
     * flight hardware, and the no-timed-writes rule applies to it as much as to the flight limits.
     */
    fun apply(context: Context, onDone: (() -> Unit)? = null) {
        val mode = saved(context)
        val key = KeyTools.createKey(GimbalKey.KeyPitchControlMaxSpeed, ComponentIndexType.LEFT_OR_MAIN)
        runCatching {
            KeyManager.getInstance().setValue(key, mode.pitchSpeed,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        AppLog.i(TAG, "gimbal pitch speed set to ${mode.pitchSpeed} (${mode.label})")
                        readBack(onDone)
                    }

                    override fun onFailure(error: IDJIError) {
                        // Not fatal: the read-back still runs, so the Pre-Flight line shows what
                        // the aircraft holds rather than going blank on a refused write.
                        AppLog.w(TAG, "gimbal pitch speed set to ${mode.pitchSpeed} failed: " +
                            error.description())
                        readBack(onDone)
                    }
                })
        }.onFailure {
            AppLog.w(TAG, "gimbal pitch-speed write threw: ${it.message}")
            onDone?.invoke()
        }
    }

    /**
     * Clears the value read back from the aircraft. Call on disconnect — the Pre-Flight line
     * must not go on reporting the PREVIOUS aircraft's gimbal speed as this one's answer.
     */
    fun resetOnDisconnect() {
        aircraftPitchSpeed = null
    }

    /** The answer, not the request: a success callback is not proof. */
    private fun readBack(onDone: (() -> Unit)?) {
        val key = KeyTools.createKey(GimbalKey.KeyPitchControlMaxSpeed, ComponentIndexType.LEFT_OR_MAIN)
        runCatching {
            KeyManager.getInstance().getValue(key,
                object : CommonCallbacks.CompletionCallbackWithParam<Int> {
                    override fun onSuccess(value: Int?) {
                        aircraftPitchSpeed = value
                        AppLog.i(TAG, "aircraft gimbal pitch speed is now: $value")
                        onDone?.invoke()
                    }

                    override fun onFailure(error: IDJIError) {
                        AppLog.w(TAG, "gimbal pitch-speed read-back failed: ${error.description()}")
                        onDone?.invoke()
                    }
                })
        }.onFailure {
            AppLog.w(TAG, "gimbal pitch-speed read-back threw: ${it.message}")
            onDone?.invoke()
        }
    }
}
