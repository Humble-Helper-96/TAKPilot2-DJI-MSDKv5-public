package com.dji.sdk.sample.tak

import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.battery.BatteryLedsInfo
import dji.sdk.keyvalue.value.flightcontroller.LEDsSettings
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import com.taklite.util.AppLog

/**
 * The aircraft's exterior lights: the MOTOR LEDs and the BEACON, controlled separately.
 *
 * ⚠ DJI'S FIELD NAMES DO NOT MATCH THE LIGHTS. This map was measured on a Matrice 4TD
 * by writing one member at a time and watching the airframe, after two guesses
 * from the names were wrong:
 *
 * | SDK member                          | The light it actually drives          |
 * |-------------------------------------|---------------------------------------|
 * | `navigationLEDsOn` + `aircraftLed`  | THE WHITE BEACON — not the nav lights |
 * | `frontLEDsOn`                       | front motor LEDs, steady red          |
 * | `rearLEDsOn`, `statusIndicatorLEDsOn` | rear motor LEDs, flashing red/green |
 *
 * `navigationLEDsOn` is the trap: it reads like the position lights and it is the beacon. A
 * control wired from that name alone would do the opposite of its label, and the pilot would
 * only find out in the dark. Trust the aircraft, never the documentation.
 *
 * `BeaconKey.KeyIsBeaconOpened` — the key that SHOULD be the beacon — is refused by this
 * airframe outright: the write is rejected and the read returns null. Measured, not assumed.
 *
 * ⚠ The two beacon members move together, so it is not yet known whether one alone is enough.
 * They are written as a pair until someone separates them on hardware.
 *
 * ⚠ [motorLedsOn] AND [beaconOn] ARE THE AIRCRAFT'S ANSWER, NEVER OUR REQUEST.
 * Every write is followed by a read-back and the buttons render from what comes back, so
 * a refused write cannot paint a control that claims the lights changed when they did not.
 *
 * ⚠ These are WRITES TO THE FLIGHT CONTROLLER, thus on an explicit button press only, never
 * on a timer.
 */
object AircraftLights {

    private const val TAG = "AircraftLights"

    /**
     * The motor LEDs — front steady red plus the rear flashing pair. True when any is on,
     * false when all are off, NULL when the aircraft has not answered. Null is its own state
     * on purpose: the button shows unknown rather than guessing off.
     *
     * ⚠ This group includes the STATUS INDICATOR, the rear flashing red/green that is how the
     * airframe reports its own health at a glance. Turning the motor LEDs off turns that
     * signal off with them — a deliberate decision, because a pilot going dark
     * needs the aircraft dark, and a status light that stayed lit would defeat the point.
     */
    @Volatile
    var motorLedsOn: Boolean? = null
        private set

    /** The white beacon. Same null-is-unknown rule as [motorLedsOn]. */
    @Volatile
    var beaconOn: Boolean? = null
        private set

    private val ledsKey: DJIKey<LEDsSettings>
        get() = KeyTools.createKey(FlightControllerKey.KeyLEDsSettings)

    private val batteryLedsKey: DJIKey<BatteryLedsInfo>
        get() = KeyTools.createKey(BatteryKey.KeyBatteryLEDsEnabled)

    /**
     * The last full value each key reported.
     *
     * Held because a write carries every member at once: without the others, changing the
     * motor LEDs would silently clear the beacon and vice versa. Preserving them is not an
     * optimisation — it is the difference between changing one light and changing all of them.
     */
    @Volatile
    private var lastLeds: LEDsSettings? = null

    @Volatile
    private var lastBatteryLed: Boolean? = null

    /**
     * Asks the aircraft what its lights are doing and updates both states.
     *
     * ⚠ THE ASYNCHRONOUS getValue, NOT THE ONE-ARGUMENT ONE. The synchronous
     * `getValue(key)` reads MSDK's LOCAL CACHE, and a key nothing has fetched or subscribed
     * yet is simply absent from it — so on a fresh app start it returns null for ever and the
     * pill sits grey. That is exactly what happened on a cold start, and what made it
     * confusing is that the sync read WORKED once a write had populated the cache: the state
     * only appeared after the pilot had already changed something. The two-argument form
     * queries the aircraft.
     *
     * Reading is free of the write rule — it forbids timed WRITES, not polling a state.
     */
    fun refresh(onDone: (() -> Unit)? = null) {
        // Answer once: the two getters answer on SDK threads and this SDK fires some
        // completions twice, so the join flags are atomic (plain `var`s had no cross-thread
        // visibility guarantee) and `fired` makes the tail one-shot.
        val ledsDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val battDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun finish() {
            if (!ledsDone.get() || !battDone.get()) return
            if (!fired.compareAndSet(false, true)) return
            val leds = lastLeds
            val motors = listOfNotNull(
                leds?.frontLEDsOn, leds?.rearLEDsOn, leds?.statusIndicatorLEDsOn,
            )
            motorLedsOn = if (motors.isEmpty()) null else motors.any { it }
            beaconOn = leds?.navigationLEDsOn
            AppLog.v(TAG, "lights read-back: motors=$motorLedsOn beacon=$beaconOn leds=$leds")
            // The SDK callback below can land on a non-main thread. onDone renders a view
            // (renderLightsButton) at one of its two call sites, and only one of them
            // remembers to wrap itself in runOnUiThread — post here so every caller,
            // present and future, gets a main-thread callback for free.
            main.post { onDone?.invoke() }
        }

        KeyManager.getInstance().getValue(ledsKey,
            object : CommonCallbacks.CompletionCallbackWithParam<LEDsSettings> {
                override fun onSuccess(value: LEDsSettings?) {
                    if (value != null) lastLeds = value
                    ledsDone.set(true); finish()
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.v(TAG, "LEDs read failed: ${error.description()}")
                    ledsDone.set(true); finish()
                }
            })

        KeyManager.getInstance().getValue(batteryLedsKey,
            object : CommonCallbacks.CompletionCallbackWithParam<BatteryLedsInfo> {
                override fun onSuccess(value: BatteryLedsInfo?) {
                    if (value != null) lastBatteryLed = value.batteryLed
                    battDone.set(true); finish()
                }

                override fun onFailure(error: IDJIError) {
                    battDone.set(true); finish()
                }
            })
    }

    /**
     * Turns the MOTOR LEDs on or off, preserving the beacon.
     *
     * @param onResult true only when the aircraft CONFIRMED the new state on read-back. False
     * means the pilot must be told the aircraft did not change the lights.
     */
    fun setMotorLeds(on: Boolean, onResult: (Boolean) -> Unit) {
        // READ FIRST. A write carries every member, so the half we are not changing has to be
        // a value the AIRCRAFT gave us — never a guess. See [requireState].
        val prev = requireState() ?: run { onResult(false); return }
        AppLog.i(TAG, "motor LEDs: asking for ${if (on) "ON" else "OFF"} " +
            "(beacon stays ${prev.navigationLEDsOn})")
        // Built BY NAME. The positional constructor is avoided on purpose: the declared field
        // order is (front, statusIndicator, rear, navigation), a constructor is not
        // contractually bound to that order, and getting it wrong would move a different
        // light — silently, and visible only on the airframe.
        val settings = LEDsSettings().apply {
            frontLEDsOn = on
            rearLEDsOn = on
            statusIndicatorLEDsOn = on
            navigationLEDsOn = prev.navigationLEDsOn          // the beacon, untouched
        }
        writeLeds(settings, "motors") { onResult(motorLedsOn == on) }
    }

    /**
     * Turns the BEACON on or off, preserving the motor LEDs.
     *
     * Writes both beacon members — `navigationLEDsOn` and the battery key's `aircraftLed` —
     * because they were only ever observed moving together.
     */
    fun setBeacon(on: Boolean, onResult: (Boolean) -> Unit) {
        val prev = requireState() ?: run { onResult(false); return }
        AppLog.i(TAG, "beacon: asking for ${if (on) "ON" else "OFF"}")
        val settings = LEDsSettings().apply {
            frontLEDsOn = prev.frontLEDsOn
            rearLEDsOn = prev.rearLEDsOn
            statusIndicatorLEDsOn = prev.statusIndicatorLEDsOn
            navigationLEDsOn = on
        }

        // Answer once: one-shot, for the same reason as refresh() above. A second fire
        // here does not repeat the WRITES (they are already issued by the time finish runs),
        // but it does run a second read-back and deliver a second onResult — and the caller
        // turns onResult into a Toast, so a racing pair could show the pilot two contradictory
        // answers about the beacon, which is worse than either answer alone.
        val ledsDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val battDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish() {
            if (!ledsDone.get() || !battDone.get()) return
            if (!fired.compareAndSet(false, true)) return
            // The read-back is the answer, not the callbacks. Either write can be refused
            // while the other succeeds, which would leave the two halves disagreeing.
            refresh { onResult(beaconOn == on) }
        }

        KeyManager.getInstance().setValue(ledsKey, settings,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "KeyLEDsSettings(beacon=$on): OK")
                    ledsDone.set(true); finish()
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "KeyLEDsSettings(beacon) refused: ${error.description()}")
                    ledsDone.set(true); finish()
                }
            })

        // batteryLed is left as the aircraft last reported it: it is the battery's own level
        // display, not part of the beacon, and was only bundled here because the two share a
        // key. aircraftLed is the beacon half.
        val batt = BatteryLedsInfo(on, lastBatteryLed ?: true)
        KeyManager.getInstance().setValue(batteryLedsKey, batt,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "KeyBatteryLEDsEnabled(aircraftLed=$on): OK")
                    battDone.set(true); finish()
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "KeyBatteryLEDsEnabled refused: ${error.description()}")
                    battDone.set(true); finish()
                }
            })
    }

    /**
     * The aircraft's CURRENT light state, re-read right now, or null if it will not say.
     *
     * ⚠ A NULL HERE MUST REFUSE THE WRITE. Every write carries all four members, so a missing
     * value used to fall back to the value being requested — which changed the very light the
     * caller was trying to preserve. Found on a fresh start: the pill came up grey
     * (state unknown, the aircraft had not answered yet) and the first tap turned the beacon
     * on as well as the motor LEDs. Refusing is the honest answer; guessing moves a light the
     * pilot did not ask for, in the dark, on an aircraft they may be trying to hide.
     */
    private fun requireState(): LEDsSettings? {
        // The cached value from the last successful async read. Not a fresh fetch: the read
        // is asynchronous, and the tick keeps asking until it lands, so by the time a pilot
        // can press anything this is populated — or the aircraft is not answering at all, in
        // which case refusing is the right outcome.
        val s = lastLeds
        if (s == null) AppLog.w(TAG, "lights: the aircraft has not reported its lights — " +
            "refusing the write rather than guessing the half being preserved")
        return s
    }

    /**
     * Clears every value read back from the aircraft. Call on disconnect — otherwise the lights
     * pill keeps showing the PREVIOUS aircraft's on/off state (and [requireState] keeps handing
     * out its stale [LEDsSettings] to preserve on the next write) until this one happens to
     * answer a refresh.
     */
    fun resetOnDisconnect() {
        motorLedsOn = null
        beaconOn = null
        lastLeds = null
        lastBatteryLed = null
    }

    /** One write path for the LEDs key, so the read-back rule is applied the same way twice. */
    private fun writeLeds(settings: LEDsSettings, what: String, verify: () -> Unit) {
        KeyManager.getInstance().setValue(ledsKey, settings,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "KeyLEDsSettings($what): OK")
                    refresh(verify)
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "KeyLEDsSettings($what) refused: ${error.description()}")
                    refresh(verify)
                }
            })
    }
}
