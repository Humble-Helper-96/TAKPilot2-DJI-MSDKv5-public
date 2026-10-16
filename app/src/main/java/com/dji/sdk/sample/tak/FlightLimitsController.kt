package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.flightcontroller.FailsafeAction
import dji.sdk.keyvalue.value.remotecontroller.ControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

/**
 * Pushes the pilot-configured flight-safety limits (Pre-Flight Setup screen, "Drone Settings"
 * section) to the aircraft on connect: max altitude, max distance (radius), RTH altitude, and
 * the signal-loss failsafe behavior.
 *
 * MSDK v5 key for each classic FlightController setter, all written through KeyManager:
 *   setMaxFlightHeight            -> KeyHeightLimit (m)
 *   setMaxFlightRadiusLimitation* -> KeyDistanceLimitEnabled + KeyDistanceLimit (m)
 *   setGoHomeHeightInMeters       -> KeyGoHomeHeight (m)
 *   setConnectionFailSafeBehavior -> KeyFailsafeAction (HOVER/LANDING/GOHOME)
 *   set(Serious)LowBatteryWarningThreshold -> the same-named v5 keys
 *   setAircraftMappingStyle       -> RemoteControllerKey.KeyControlMode (JP/USA/CH)
 *
 * Each is optional — an empty field on the setup screen means "don't override, leave the
 * aircraft's current/default setting alone." Fields are entered/persisted in feet; converted
 * to meters only here, at the point of calling the SDK. Out-of-range values are still sent —
 * the SDK's own rejection is the source of truth, logged here rather than duplicating range
 * validation client-side.
 *
 * **Signal-loss failsafe vs. max distance — two different mechanisms, don't conflate them.**
 * The failsafe fires when the aircraft loses the RC link: it's an aircraft-firmware setting,
 * so it still works if this app (or the whole controller) dies mid-flight. The max-radius
 * limit is a geofence — the aircraft refuses to fly past it and hovers at the boundary.
 *
 * Rule (written after a timed write burst crashed an aircraft):
 * flight-controller writes happen at connect or on an explicit Apply, never on a timer.
 *
 * The apply and read-back path is **verified on the Matrice 4TD**: a limit changed 150 → 250
 * applied and read back. The key set: KeyHeightLimit for max altitude, KeyDistanceLimit for
 * radius, KeyGoHomeHeight for RTH. If a key returns null, the key is wrong, not the aircraft —
 * check the MSDK v5 key list before changing the logic.
 */
object FlightLimitsController {
    private const val TAG = "TP2Limits"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_MAX_ALT_FT = "limit_max_altitude_ft"
    private const val KEY_MAX_RADIUS_FT = "limit_max_radius_ft"
    private const val KEY_RTH_ALT_FT = "limit_rth_altitude_ft"
    private const val KEY_FAILSAFE = "limit_failsafe_behavior"
    private const val KEY_LOW_BATT = "limit_low_battery_pct"
    private const val KEY_CRIT_BATT = "limit_critical_battery_pct"
    private const val KEY_STICK_MODE = "limit_stick_mode"

    private const val FT_PER_M = 3.28084

    /**
     * The aircraft's battery warning levels, as READ BACK from the aircraft — not the saved
     * preference. Null until a read-back lands. Here so the Pre-Flight screen can show what
     * the aircraft actually holds, which is the only honest thing to show for a setting the
     * aircraft owns.
     */
    @Volatile var aircraftWarningPct: Int? = null
        private set
    @Volatile var aircraftCriticalPct: Int? = null
        private set

    /**
     * The other four limits, also as READ BACK from the aircraft. Null until a read-back lands.
     *
     * These exist for the same reason the battery pair does: the Pre-Flight screen promises
     * "values below are what the aircraft reports", and an earlier version could only
     * report the battery pair, because nothing else was ever read back. An Apply that pushed
     * six settings and reported two of them looks complete and is not.
     */
    @Volatile var aircraftMaxAltM: Int? = null
        private set
    @Volatile var aircraftMaxRadiusM: Int? = null
        private set
    @Volatile var aircraftRthAltM: Int? = null
        private set
    @Volatile var aircraftFailsafe: FailsafeAction? = null
        private set

    /**
     * Set when the aircraft REFUSES a battery-threshold write. Some airframes own these levels
     * and reject both setters outright, and the vendor documentation is wrong about which ones.
     * The Pre-Flight screen uses this to stop the two fields pretending they can be edited.
     */
    @Volatile var batteryThresholdsRefused = false
        private set

    /** How long a read-back may take before the barrier is released without it. */
    private const val READ_BACK_TIMEOUT_MS = 4000L

    /**
     * RC stick mapping. Mode 2 (left stick throttle/yaw) is the near-universal default.
     * v5 names the modes by region convention: JP = Mode 1, USA = Mode 2, CH = Mode 3.
     *
     * ⚠ This is a REMOTE CONTROLLER setting, not a flight-controller one, and it changes what
     * the sticks do. It is pushed only on an explicit Apply, never silently at connect — see
     * [applyDefaults], which deliberately leaves it alone.
     */
    enum class StickMode(val id: String, val label: String, val sdk: ControlMode) {
        MODE_1("1", "Mode 1", ControlMode.JP),
        MODE_2("2", "Mode 2", ControlMode.USA),
        MODE_3("3", "Mode 3", ControlMode.CH),
        ;
        companion object {
            fun fromId(id: String?): StickMode = values().firstOrNull { it.id == id } ?: MODE_2
        }
    }

    fun savedLowBatteryPct(context: Context): String = pref(context, KEY_LOW_BATT, "30")
    fun savedCriticalBatteryPct(context: Context): String = pref(context, KEY_CRIT_BATT, "15")
    fun savedStickMode(context: Context): StickMode =
        StickMode.fromId(pref(context, KEY_STICK_MODE, StickMode.MODE_2.id))

    fun saveBattery(context: Context, lowPct: String, criticalPct: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOW_BATT, lowPct.trim())
            .putString(KEY_CRIT_BATT, criticalPct.trim())
            .apply()
    }

    fun saveStickMode(context: Context, mode: StickMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STICK_MODE, mode.id).apply()
    }

    /** What the aircraft does when it loses the RC link. Ids are what's persisted.
     *  There is deliberately no "leave the aircraft's setting alone" option — this is the
     *  setting you least want to be unsure about. */
    enum class Failsafe(val id: String, val label: String, val sdk: FailsafeAction) {
        GO_HOME("gohome", "Return to Home", FailsafeAction.GOHOME),
        HOVER("hover", "Hover in place", FailsafeAction.HOVER),
        LAND("land", "Land immediately", FailsafeAction.LANDING),
        ;
        companion object {
            fun fromId(id: String?): Failsafe = values().firstOrNull { it.id == id } ?: GO_HOME
        }
    }

    fun savedMaxAltitudeFt(context: Context): String = pref(context, KEY_MAX_ALT_FT, "200")
    fun savedMaxRadiusFt(context: Context): String = pref(context, KEY_MAX_RADIUS_FT, "5280")
    fun savedRthAltitudeFt(context: Context): String = pref(context, KEY_RTH_ALT_FT, "150")

    /** Defaults to Return to Home — the safe choice for the "flew out of radio range" case,
     *  made explicit and verifiable rather than assumed. */
    fun savedFailsafe(context: Context): Failsafe =
        Failsafe.fromId(pref(context, KEY_FAILSAFE, Failsafe.GO_HOME.id))

    private fun pref(context: Context, key: String, default: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, default) ?: default

    fun save(context: Context, maxAltFt: String, maxRadiusFt: String, rthAltFt: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MAX_ALT_FT, maxAltFt.trim())
            .putString(KEY_MAX_RADIUS_FT, maxRadiusFt.trim())
            .putString(KEY_RTH_ALT_FT, rthAltFt.trim())
            .apply()
    }

    fun saveFailsafe(context: Context, failsafe: Failsafe) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FAILSAFE, failsafe.id).apply()
    }

    // ------------------------------------------------------------------
    // Typed set/get helpers over KeyManager, with per-call result logging.
    // ------------------------------------------------------------------
    /**
     * @param onResult receives null on success and the error on refusal, BEFORE [next] runs.
     *   The caller needs the refusal, not just the log line: a summary that counts a refused
     *   write as applied reports the request rather than the aircraft, which is the same
     *   failure as trusting a success callback.
     */
    private fun <T> setKey(
        keyInfo: DJIKeyInfo<T>,
        value: T,
        label: String,
        onResult: (IDJIError?) -> Unit = {},
        next: () -> Unit = {},
    ) {
        // Answer once: DJI SDKs HAVE BEEN SEEN TO FIRE SOME COMPLETION CALLBACKS TWICE
        // (observed with Gimbal.setControllerMaxSpeed). `next` is the only thing advancing the
        // write chain, so a second fire does not merely repeat one step — it FORKS the chain,
        // and each fork can fork again, turning an ordered sequence of flight-controller writes
        // (height limit, distance limit, go-home height, failsafe, both battery thresholds,
        // stick mode) into an interleaved burst. That is the exact shape of write that has
        // crashed an aircraft, so the guard belongs here, in the
        // one place every chain step passes through, rather than at each call site.
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        KeyManager.getInstance().setValue(
            KeyTools.createKey(keyInfo), value,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    if (!answered.compareAndSet(false, true)) {
                        AppLog.w(TAG, "$label: duplicate completion (success) ignored"); return
                    }
                    AppLog.i(TAG, "$label: OK"); onResult(null); next()
                }
                override fun onFailure(error: IDJIError) {
                    if (!answered.compareAndSet(false, true)) {
                        AppLog.w(TAG, "$label: duplicate completion (failure) ignored"); return
                    }
                    AppLog.i(TAG, "$label: ${error.description()}"); onResult(error); next()
                }
            },
        )
    }

    private fun <T> getKey(keyInfo: DJIKeyInfo<T>, label: String, onValue: (T?) -> Unit = {}) {
        KeyManager.getInstance().getValue(
            KeyTools.createKey(keyInfo),
            object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(v: T?) { AppLog.i(TAG, "$label is now: $v"); onValue(v) }
                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "$label read failed: ${error.description()}")
                }
            },
        )
    }

    /** Apply whichever limits are configured (called once on connect). Skips any limit whose
     *  field is empty/unparseable — that limit is simply not touched. */
    fun applyDefaults(context: Context) {
        val maxAltM = ftToM(savedMaxAltitudeFt(context))
        val maxRadiusM = ftToM(savedMaxRadiusFt(context))
        val rthAltM = ftToM(savedRthAltitudeFt(context))
        AppLog.i(TAG, "applyDefaults: maxAltM=$maxAltM maxRadiusM=$maxRadiusM rthAltM=$rthAltM " +
            "failsafe=${savedFailsafe(context).id} (null = not configured, skipped)")

        maxAltM?.let { m -> setKey(FlightControllerKey.KeyHeightLimit, m, "KeyHeightLimit($m)") }
        maxRadiusM?.let { m ->
            setKey(FlightControllerKey.KeyDistanceLimitEnabled, true,
                "KeyDistanceLimitEnabled(true)") {
                setKey(FlightControllerKey.KeyDistanceLimit, m, "KeyDistanceLimit($m)")
            }
        }
        rthAltM?.let { m -> setKey(FlightControllerKey.KeyGoHomeHeight, m, "KeyGoHomeHeight($m)") }

        val sdkBehavior = savedFailsafe(context).sdk
        setKey(FlightControllerKey.KeyFailsafeAction, sdkBehavior,
            "KeyFailsafeAction($sdkBehavior)") {
            // Always read back, including on a reported failure: this is the one limit the
            // pilot can't casually verify in the air, so the log is the practical proof that
            // the aircraft actually took the setting.
            readBackFailsafe()
        }
    }

    /**
     * Pushes everything the pilot has set, then READS IT ALL BACK, reporting progress.
     *
     * Separate from [applyDefaults] on purpose. That one runs unattended at connect and pushes
     * only the flight-controller limits. This runs when the pilot presses Apply, and it is the
     * only path that touches the RC stick mapping — a setting that changes what the sticks do
     * must never move because an app reconnected.
     *
     * @param onProgress (done, total, message) on the main thread.
     * @param onDone     (ok, summary) once every step has reported.
     */
    fun applyToAircraft(
        context: Context,
        onProgress: (Int, Int, String) -> Unit,
        onDone: (Boolean, String) -> Unit,
    ) {
        if (!DjiSdkBridge.isProductConnected) {
            onDone(false, "No aircraft connected. Settings are saved and will be applied when it connects.")
            return
        }

        val lowPct = savedLowBatteryPct(context).trim().toIntOrNull()
        val critPct = savedCriticalBatteryPct(context).trim().toIntOrNull()
        val stick = savedStickMode(context)

        // Steps are counted up front so the bar is determinate — a pilot watching an
        // indeterminate spinner cannot tell "working" from "hung".
        data class Step(val name: String, val run: (() -> Unit) -> Unit)
        val steps = ArrayList<Step>()

        // What the aircraft REFUSED. The summary used to say "Applied 7 setting(s)" whether or
        // not the aircraft took them. Counting a refusal as applied is the same failure as
        // trusting a success callback: it reports the request, not the aircraft.
        val refused = java.util.Collections.synchronizedList(ArrayList<String>())
        fun record(step: String, e: IDJIError?) {
            if (e == null) return
            synchronized(refused) { if (step !in refused) refused.add(step) }
            if (step.endsWith("battery level")) batteryThresholdsRefused = true
        }

        ftToM(savedMaxAltitudeFt(context))?.let { m ->
            steps.add(Step("Max altitude") { next ->
                setKey(FlightControllerKey.KeyHeightLimit, m, "KeyHeightLimit($m)",
                    { e -> record("Max altitude", e) }, next)
            })
        }
        ftToM(savedMaxRadiusFt(context))?.let { m ->
            steps.add(Step("Max distance") { next ->
                setKey(FlightControllerKey.KeyDistanceLimitEnabled, true,
                    "KeyDistanceLimitEnabled(true)", { e -> record("Max distance", e) }) {
                    setKey(FlightControllerKey.KeyDistanceLimit, m, "KeyDistanceLimit($m)",
                        { e -> record("Max distance", e) }, next)
                }
            })
        }
        ftToM(savedRthAltitudeFt(context))?.let { m ->
            steps.add(Step("RTH altitude") { next ->
                setKey(FlightControllerKey.KeyGoHomeHeight, m, "KeyGoHomeHeight($m)",
                    { e -> record("RTH altitude", e) }, next)
            })
        }
        steps.add(Step("Signal-loss behaviour") { next ->
            val b = savedFailsafe(context).sdk
            setKey(FlightControllerKey.KeyFailsafeAction, b, "KeyFailsafeAction($b)",
                { e -> record("Signal-loss behaviour", e) }, next)
        })
        if (lowPct != null) {
            steps.add(Step("Low battery level") { next ->
                setKey(FlightControllerKey.KeyLowBatteryWarningThreshold, lowPct,
                    "KeyLowBatteryWarningThreshold($lowPct)",
                    { e -> record("Low battery level", e) }, next)
            })
        }
        if (critPct != null) {
            steps.add(Step("Critical battery level") { next ->
                setKey(FlightControllerKey.KeySeriousLowBatteryWarningThreshold, critPct,
                    "KeySeriousLowBatteryWarningThreshold($critPct)",
                    { e -> record("Critical battery level", e) }, next)
            })
        }
        steps.add(Step("Stick mode") { next ->
            setKey(RemoteControllerKey.KeyControlMode, stick.sdk,
                "KeyControlMode(${stick.sdk})", { e -> record("Stick mode", e) }, next)
        })
        // ⚠ `next` goes INTO readBackAll's completion, not after the call. It used to be
        // `readBackAll(); next()`, which fired the "values below are what the aircraft reports"
        // message before a single getter had answered — every other step here already threads
        // `next` through its SDK callback, and this one did not. The values then arrived after
        // the message and nothing re-rendered, so the line stayed empty.
        steps.add(Step("Reading back") { next -> readBackAll { next() } })

        val total = steps.size
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun runStep(i: Int) {
            if (i >= total) {
                val attempted = total - 1          // the read-back step is not a setting
                val declined = synchronized(refused) { refused.toList() }
                val summary = buildString {
                    append("Applied ${attempted - declined.size} of $attempted setting(s).")
                    if (declined.isNotEmpty()) {
                        append(" This aircraft refused: ${declined.joinToString(", ")}.")
                    }
                    append(" Values below are what the aircraft reports.")
                }
                main.post { onDone(declined.isEmpty(), summary) }
                return
            }
            main.post { onProgress(i, total, steps[i].name) }
            // Each step's callback drives the next, so the bar tracks real completions rather
            // than a timer guessing at them.
            runCatching { steps[i].run { runStep(i + 1) } }
                .onFailure {
                    AppLog.w(TAG, "apply step '${steps[i].name}' threw: ${it.message}")
                    runStep(i + 1)
                }
        }
        runStep(0)
    }

    /** Asks the aircraft what it actually holds now. The answer, not the request, is what the
     *  Pre-Flight screen shows. */
    private fun readBackAll(done: () -> Unit) {
        // Six getters in flight at once; `done` fires when the last one answers. A getter that
        // never calls back would otherwise leave the Apply button disabled for good, so a
        // watchdog releases the barrier once. `fired` makes both paths one-shot.
        val outstanding = java.util.concurrent.atomic.AtomicInteger(6)
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        // Named so it can be REMOVED once the barrier fires — an already-spent watchdog that
        // stays queued holds this callback (and its Activity) alive to the timeout for nothing.
        var watchdog: Runnable? = null
        fun complete() {
            if (!fired.compareAndSet(false, true)) return
            watchdog?.let { main.removeCallbacks(it) }
            main.post { done() }
        }
        fun oneDown() {
            if (outstanding.decrementAndGet() <= 0) complete()
        }
        watchdog = Runnable {
            if (!fired.get()) {
                AppLog.w(TAG, "read-back timed out with ${outstanding.get()} getter(s) unanswered")
                complete()
            }
        }
        main.postDelayed(watchdog!!, READ_BACK_TIMEOUT_MS)

        /** Every getter has the same shape: store it, log it, count it down. */
        fun <T> read(keyInfo: DJIKeyInfo<T>, name: String, store: (T?) -> Unit) = runCatching {
            KeyManager.getInstance().getValue(
                KeyTools.createKey(keyInfo),
                object : CommonCallbacks.CompletionCallbackWithParam<T> {
                    override fun onSuccess(v: T?) {
                        store(v)
                        AppLog.i(TAG, "aircraft $name is now: $v")
                        oneDown()
                    }
                    override fun onFailure(error: IDJIError) {
                        AppLog.w(TAG, "get $name failed: ${error.description()}")
                        oneDown()
                    }
                },
            )
        }.onFailure {
            AppLog.w(TAG, "get $name threw: ${it.message}")
            oneDown()
        }

        read(FlightControllerKey.KeyFailsafeAction, "signal-loss behavior") { aircraftFailsafe = it }
        read(FlightControllerKey.KeyLowBatteryWarningThreshold, "low-battery level") { aircraftWarningPct = it }
        read(FlightControllerKey.KeySeriousLowBatteryWarningThreshold, "critical-battery level") { aircraftCriticalPct = it }
        read(FlightControllerKey.KeyHeightLimit, "max altitude") { aircraftMaxAltM = it }
        read(FlightControllerKey.KeyDistanceLimit, "max radius") { aircraftMaxRadiusM = it }
        read(FlightControllerKey.KeyGoHomeHeight, "RTH height") { aircraftRthAltM = it }
    }

    /** Asks the aircraft what its signal-loss behavior actually is now, and logs it. */
    private fun readBackFailsafe() {
        // STORES the answer, it does not only log it. This runs at connect, so without the
        // store the Pre-Flight screen showed no signal-loss value until the pilot pressed
        // Apply — an aircraft that already held the right behaviour looked like one that
        // held none.
        getKey(FlightControllerKey.KeyFailsafeAction, "aircraft signal-loss behavior") {
            aircraftFailsafe = it
        }
    }

    /**
     * Clears every value read back from the aircraft. Call on disconnect — a swapped-in
     * aircraft's Pre-Flight screen must not go on showing the PREVIOUS aircraft's limits (or
     * its refused-battery-write lock) while the new one has not yet answered a single getter.
     */
    fun resetOnDisconnect() {
        aircraftWarningPct = null
        aircraftCriticalPct = null
        aircraftMaxAltM = null
        aircraftMaxRadiusM = null
        aircraftRthAltM = null
        aircraftFailsafe = null
        batteryThresholdsRefused = false
    }

    /**
     * Parses a feet string to a rounded meters int, or null if blank/unparseable.
     *
     * Internal rather than private so [FlightWarnings] can convert the SAME stored strings this
     * controller pushes to the aircraft. Both must read one source, or the at-limit banner ends
     * up describing a limit the aircraft is not enforcing.
     */
    internal fun ftToM(feetStr: String): Int? {
        val ft = feetStr.trim().toDoubleOrNull() ?: return null
        return Math.round(ft / FT_PER_M).toInt()
    }
}
