package com.dji.sdk.sample.tak

import com.taklite.util.AppLog

/**
 * What the flight screen's banner shows, and when.
 *
 * ## Why this is an arbitration layer, not a second warning source
 *
 * This aircraft already has a real one. `DJIDiagnostics` is a first-class SDK channel that
 * reports compass, IMU, battery and no-fly-zone faults in DJI's own localised words, and
 * [DjiSdkBridge] already subscribes to it and de-duplicates it. An aircraft **without such a
 * channel** would have to hand-derive all of that from status booleans. Rebuilding
 * that reconstruction here and letting it compete with the real thing would be a regression, and
 * would show the same fault twice in two different wordings.
 *
 * So the aircraft's own faults arrive through [onDiagnostics] and are shown VERBATIM at the top
 * of the priority order. This object never re-words them and never decides one is not worth
 * seeing. What it adds is the tier that `DJIDiagnostics` has no equivalent for — the aircraft
 * flying itself home, its own battery thresholds, the configured altitude and distance limits, a
 * missing home point, wind — plus the display discipline the raw banner lacked.
 *
 * ## What the display discipline is for
 *
 * Before this, `renderDiagnostics` did `items.joinToString("\n")`: every active fault, unbounded,
 * with no hold. A fault that flaps at the callback rate strobes, and a long list grows down over
 * the video. Here one warning owns the banner at a time — the worst — held for [HOLD_MS] so a
 * flicker cannot make the text jump, with a `+N` count for the rest. A worse warning preempts
 * immediately; nothing is ever hidden, only queued.
 *
 * ## No-fly zone stays on the banner (design decision)
 *
 * A fleet that routinely flies under an airspace exception might make a NO-FLY ZONE warning
 * log-only, because a red banner routinely correct to ignore teaches pilots to ignore red. **That
 * is not done here.** There is a field lesson pointing the other way:
 * "Cannot takeoff in a no-fly zone" was invisible through two flights, and `renderDiagnostics`
 * was written specifically not to filter by severity because of it. DJI's no-fly-zone diagnostic
 * therefore flows through [onDiagnostics] like any other aircraft fault and is displayed.
 *
 * ## Fed, never subscribed
 *
 * Same contract as [FlightPathLogger]. [onState] is called from the `FlightControllerState`
 * callback [DroneTakBridge] already owns; the SDK slot holds one client. Nothing here touches
 * the SDK.
 */
object FlightWarnings {

    /**
     * Priority order IS declaration order: worse first. Red = act now, amber = know it.
     *
     * Deliberately absent, because `DJIDiagnostics` already reports them and arrives as
     * [AIRCRAFT_FAULT]: compass interference, IMU faults, controller temperature, no-fly zones.
     * Also absent: a "returning home — signal lost" variant, because the telemetry
     * [Frame] carries no RC-link indicator to distinguish it from any other
     * return; a generic [RETURNING_HOME] covers that case honestly instead.
     */
    enum class Warning(val red: Boolean, val label: String) {
        /** The aircraft's own words, from DJIDiagnostics. Highest priority because it is the
         *  only source here that reports hardware faults, and because it is authoritative. */
        AIRCRAFT_FAULT(true, ""),
        BATTERY_CRITICAL(true, "BATTERY CRITICAL"),
        /** The aircraft has decided to put itself down where it is. Red: the pilot's remaining
         *  choice is where it lands, and only for a few more seconds. */
        BATTERY_LANDING(true, "LANDING NOW — BATTERY"),
        GPS_LOST(true, "GPS LOST — AIRCRAFT DRIFTS"),
        RTH_BATTERY(false, "RETURNING HOME — LOW BATTERY"),
        RETURNING_HOME(false, "RETURNING HOME"),
        WIND(false, "WIND TOO HIGH"),
        BATTERY_LOW(false, "BATTERY LOW"),
        AT_MAX_ALTITUDE(false, "AT ALTITUDE LIMIT"),
        AT_MAX_RANGE(false, "AT DISTANCE LIMIT"),
        NO_HOME_POINT(false, "NO HOME POINT"),
        /** App-side, not from the aircraft: the file log is on. Merged into the active set at
         *  display time from [debugLogOn], so it shows with no aircraft too. Last, because
         *  it is the one warning that is never about flight safety. */
        DEBUG_LOG(false, "DEBUG LOG ON"),
    }

    /** What the banner should show right now, or null for hidden. */
    /**
     * What the banner shows.
     *
     * [text] is the collapsed line — the worst warning plus a "+N" for the rest. [all] is every
     * active warning, worst first, with the aircraft's faults listed one by one instead of
     * counted. The flight screen shows [all] while the pilot holds the banner open by tapping
     * it, so the count is never the only way to reach the other faults.
     */
    data class Display(val text: String, val red: Boolean, val all: List<String> = emptyList())

    /** Minimum time a warning owns the banner once shown — long enough to read, short enough
     *  that a stack still cycles usefully. A WORSE warning preempts regardless. */
    private const val HOLD_MS = 4000L

    /**
     * How close to a configured limit counts as "at" it. The aircraft brakes as it approaches
     * rather than stopping dead, so a strict equality test would fire late or never. 5% of the
     * limit, floored at 5 m so a small ceiling does not make the band vanish.
     */
    private const val LIMIT_MARGIN_FRACTION = 0.05
    private const val LIMIT_MARGIN_MIN_M = 5.0

    /** Set from the flight screen's HUD tick from `AppLog.enabled`. Read in [displayAt], not
     *  from the diagnostics feed: that feed speaks only when the aircraft does, and this must
     *  show with nothing linked (design decision). */
    @Volatile var debugLogOn: Boolean = false

    private val lock = Any()
    private var active: Set<Warning> = emptySet()
    private var faultText: String = ""
    /**
     * The last NON-EMPTY fault text.
     *
     * Needed because [AIRCRAFT_FAULT] can keep the banner through its hold after the fault has
     * already cleared, and reading the live (now empty) text at that moment replaced the
     * aircraft's own words with a placeholder — the banner said "Compass error" and then
     * silently became "aircraft fault" while the pilot was reading it. The hold exists so text
     * does not move under the reader, so the text it holds has to be the text it showed.
     */
    private var lastFaultText: String = ""
    /**
     * The same faults as [faultText], still separate, WORST FIRST as the bridge ordered them.
     *
     * The banner shows one warning and counts the rest. Joining every
     * fault into one string defeated that: [AIRCRAFT_FAULT] is a single Warning, so its whole
     * joined text went on the banner and it grew without limit — five lines over the video on
     * the RC Plus 2, where the design intends one. Keeping the list lets the
     * banner print the worst fault and add the others to the "+N".
     */
    private var faultList: List<String> = emptyList()
    private var lastFaultList: List<String> = emptyList()
    private var shown: Warning? = null
    private var shownAtMs = 0L

    /** Configured limits in metres, or null when the pilot left the field blank. Set by the
     *  bridge at start from [FlightLimitsController], so this agrees with what was pushed. */
    @Volatile private var maxAltM: Double? = null
    @Volatile private var maxRadiusM: Double? = null

    fun setLimits(maxAltitudeM: Double?, maxRadiusMeters: Double?) {
        maxAltM = maxAltitudeM
        maxRadiusM = maxRadiusMeters
    }

    /**
     * The aircraft's own fault list, already de-duplicated and made readable by [DjiSdkBridge].
     * Shown verbatim — never re-worded, never filtered.
     */
    /**
     * Aircraft lines the pilot has closed and that stay closed for the flight.
     *
     * The ✕ hides ONE SET of faults and the banner returns the moment the live set differs —
     * that rule is what makes a close safe, and it stays the rule for anything the aircraft
     * ranks WARNING or above. It had a cost at night (seen on a night flight): the vision
     * system's low-ambient-light advisory clears and returns at the callback rate, and every
     * return was a "different set", so the banner came back over the video all night. A line
     * the aircraft itself ranks BELOW warning (NOTICE, CAUTION — advice, not alarm) may be
     * muted here once the pilot has read and closed it; the flight screen decides which lines
     * qualify from `DjiSdkBridge.diagnosticRanks`. Muted lines are filtered at the source in
     * [onDiagnostics], so they count in no "+N" and sit in no open list. Cleared by [reset],
     * i.e. per bridge start, never persisted. Logged on both ends.
     */
    @Volatile private var muted: Set<String> = emptySet()

    /** Lines muted this flight — for the test and the log. */
    val mutedLines: Set<String> get() = muted

    fun mute(lines: Collection<String>) {
        if (lines.isEmpty()) return
        synchronized(lock) {
            muted = muted + lines
            AppLog.i(TAG, "warning lines muted for the session by the pilot: " +
                lines.joinToString(" · "))
            // Re-apply to what is standing: the lines go now, not at the next report.
            applyDiagnostics(lastRawDiagnostics)
        }
    }

    /** The aircraft's list as it arrived, before the mute filter — so a mute can re-apply. */
    private var lastRawDiagnostics: List<String> = emptyList()

    fun onDiagnostics(items: List<String>) {
        synchronized(lock) {
            lastRawDiagnostics = items
            applyDiagnostics(items)
        }
    }

    /** Must hold [lock]. */
    private fun applyDiagnostics(raw: List<String>) {
        run {
            val items = if (muted.isEmpty()) raw else raw.filterNot { it in muted }
            val text = items.joinToString(" · ")
            if (text == faultText) return
            faultText = text
            faultList = items
            if (text.isNotEmpty()) { lastFaultText = text; lastFaultList = items }
            val next = if (text.isEmpty()) active - Warning.AIRCRAFT_FAULT
                       else active + Warning.AIRCRAFT_FAULT
            logTransitions(next)
            active = next
        }
    }

    /**
     * One telemetry frame, assembled by the bridge from its cached v5 key listens.
     *
     * MSDK v5 has no single flight-controller state object — each field is its own key — so
     * the bridge builds this struct from the same caches the PLI reads. The battery-threshold
     * judgement lives here too: v5 exposes only the configured thresholds, not a ready-made
     * "below threshold" flag, so the comparison happens in [compute] from pct + thresholds.
     */
    data class Frame(
        val flightMode: dji.sdk.keyvalue.value.flightcontroller.FlightMode?,
        val gpsLevel: dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel?,
        val wind: dji.sdk.keyvalue.value.flightcontroller.WindWarning?,
        val goingHome: Boolean,
        val batteryPct: Int,
        /** Configured warning thresholds, percent, or null before the aircraft reports them. */
        val lowBatteryThresholdPct: Int?,
        val seriousBatteryThresholdPct: Int?,
        val homeSet: Boolean,
    )

    /**
     * One state frame from the bridge's tick.
     *
     * @param airborne the same airborne test the PLI publishes, computed in the same tick —
     *   passed in rather than re-derived so the two records cannot disagree.
     * @param relAltM height above the takeoff point, metres.
     * @param homeDistanceM distance from the home point, metres, or NaN when unknown.
     */
    fun onState(
        state: Frame,
        airborne: Boolean,
        relAltM: Double,
        homeDistanceM: Double,
    ) {
        val computed = compute(state, airborne, relAltM, homeDistanceM)
        synchronized(lock) {
            // AIRCRAFT_FAULT is owned by onDiagnostics; preserve whatever it last said.
            val next = if (Warning.AIRCRAFT_FAULT in active) computed + Warning.AIRCRAFT_FAULT
                       else computed
            if (next == active) return
            logTransitions(next)
            active = next
        }
    }

    /** Caller holds [lock]. Appears at W, clears at I — the record that lets a post-flight read
     *  say when a condition started and when it went away. */
    private fun logTransitions(next: Set<Warning>) {
        (next - active).forEach { AppLog.w(TAG, "warning ACTIVE: ${it.name} (${labelOf(it)})") }
        (active - next).forEach { AppLog.i(TAG, "warning cleared: ${it.name}") }
    }

    /** The display policy. Keep it boring: every rule is one add() with its condition. */
    private fun compute(
        state: Frame,
        airborne: Boolean,
        relAltM: Double,
        homeDistanceM: Double,
    ): Set<Warning> {
        val out = java.util.EnumSet.noneOf(Warning::class.java)

        // Battery judgement, reconstructed from pct + the aircraft's own configured
        // thresholds (v5 exposes the thresholds, not the comparison). A null
        // threshold means "not reported yet" and never raises a warning by itself.
        val pct = state.batteryPct
        val low = state.lowBatteryThresholdPct
        val serious = state.seriousBatteryThresholdPct
        val belowLow = low != null && pct in 1..low
        val belowSerious = serious != null && pct in 1..serious

        // -------- red --------
        if (belowSerious) out.add(Warning.BATTERY_CRITICAL)
        // v5 has no LAND_IMMEDIATELY behavior report; a forced landing shows as
        // AUTO_LANDING mode while critically low.
        if (belowSerious &&
            state.flightMode == dji.sdk.keyvalue.value.flightcontroller.FlightMode.AUTO_LANDING) {
            out.add(Warning.BATTERY_LANDING)
        }
        // Gated on airborne: an aircraft acquiring its first fix on the ground is normal, and a
        // red banner at every power-up would teach pilots to ignore red. Airborne
        // without a GPS hold is the state that genuinely drifts.
        if (airborne && (isAttitudeMode(state.flightMode) || isPoorGps(state.gpsLevel))) {
            out.add(Warning.GPS_LOST)
        }

        // -------- amber --------
        // An aircraft flying itself with no banner reads as a runaway, so the pilot is told —
        // v5 does not report the RTH trigger reason, so the battery case is inferred from the
        // battery state at the time; everything else is the generic label.
        if (state.goingHome ||
            state.flightMode == dji.sdk.keyvalue.value.flightcontroller.FlightMode.GO_HOME) {
            if (belowLow || belowSerious) {
                out.add(Warning.RTH_BATTERY)
            } else {
                out.add(Warning.RETURNING_HOME)
            }
        }
        if (state.wind == dji.sdk.keyvalue.value.flightcontroller.WindWarning.LEVEL_1 ||
            state.wind == dji.sdk.keyvalue.value.flightcontroller.WindWarning.LEVEL_2) {
            out.add(Warning.WIND)
        }
        if (belowLow && Warning.BATTERY_CRITICAL !in out) out.add(Warning.BATTERY_LOW)
        if (airborne && atLimit(relAltM, maxAltM)) out.add(Warning.AT_MAX_ALTITUDE)
        if (airborne && atLimit(homeDistanceM, maxRadiusM)) out.add(Warning.AT_MAX_RANGE)
        // Airborne-gated for the same reason as GPS: no home point before takeoff is "not ready
        // yet", and the aircraft refuses a one-touch takeoff on its own.
        if (airborne && !state.homeSet) out.add(Warning.NO_HOME_POINT)

        return out
    }

    internal fun atLimit(value: Double, limit: Double?): Boolean {
        if (limit == null || !value.isFinite() || limit <= 0.0) return false
        val margin = maxOf(limit * LIMIT_MARGIN_FRACTION, LIMIT_MARGIN_MIN_M)
        return value >= limit - margin
    }

    /** Attitude mode: no GPS hold, so the aircraft drifts with the air mass. v5 reports
     *  every attitude variant as the one ATTI value. */
    internal fun isAttitudeMode(
        mode: dji.sdk.keyvalue.value.flightcontroller.FlightMode?,
    ): Boolean = mode == dji.sdk.keyvalue.value.flightcontroller.FlightMode.ATTI

    /** LEVEL_0/1 are "too few satellites to hold position". UNKNOWN is not treated as
     *  bad — an absent reading must not raise a red banner on its own. */
    internal fun isPoorGps(
        level: dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel?,
    ): Boolean = when (level) {
        dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel.LEVEL_0,
        dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel.LEVEL_1 -> true
        else -> false
    }

    /**
     * The fault list the banner is entitled to right now: the live one while the fault stands,
     * the last real one while it rides out its hold. Same rule as [lastFaultText] — the text
     * must not change under a reader mid-hold.
     */
    private fun heldFaults(): List<String> = faultList.ifEmpty { lastFaultList }

    private fun labelOf(w: Warning): String =
        if (w == Warning.AIRCRAFT_FAULT) {
            // The WORST fault only — the bridge sorted the list worst-first. The rest are
            // counted in the "+N" by displayAt, thus the banner stays one warning long however
            // many faults the aircraft is reporting.
            // "aircraft fault" is a last resort that should never be reached in practice — an
            // AIRCRAFT_FAULT only becomes active off a non-empty list.
            heldFaults().firstOrNull() ?: "aircraft fault"
        } else w.label

    /**
     * The lines of a banner the pilot's ✕ may hide. `DEBUG LOG ON` is not one of them
     * (design decision): the whole point of that warning is to be looked at until the log
     * is turned off, so a ✕ on a banner that carries it hides the aircraft's warnings and
     * leaves that one line standing. Pure, so the rule is pinned by [FlightWarningsTest].
     */
    fun dismissable(all: List<String>): List<String> = all.filter { it != Warning.DEBUG_LOG.label }

    /** Polled from the flight screen's HUD tick. */
    fun display(): Display? = displayAt(System.currentTimeMillis())

    /** [display] with an injectable clock, so tests can step time. Same logic, one body. */
    internal fun displayAt(now: Long): Display? {
        synchronized(lock) {
            // The app-side warning joins the aircraft's here — see [debugLogOn].
            val active = if (debugLogOn) this.active + Warning.DEBUG_LOG else this.active
            val worst = active.minOrNull()
            val cur = shown
            val held = cur != null && now - shownAtMs < HOLD_MS
            // A worse warning takes the banner immediately; otherwise the current one keeps it
            // for the hold, so a flicker at the callback rate cannot make the text strobe.
            val next = when {
                worst == null -> if (held) cur else null
                cur == null || !held || worst < cur -> worst
                else -> cur
            }
            if (next != cur) { shown = next; shownAtMs = now }
            val show = shown ?: return null
            // "+N" counts what is stacked behind this one, from the LIVE set — the shown warning
            // may itself have cleared already and just be riding out its hold.
            //
            // The aircraft's own faults count INDIVIDUALLY here, not as the one AIRCRAFT_FAULT
            // they arrive as. Three faults and a low battery read "+3", not "+1": the pilot is
            // told how many things are wrong, and the banner is still one warning long.
            val others = active.count { it != show } +
                if (show == Warning.AIRCRAFT_FAULT) (heldFaults().size - 1).coerceAtLeast(0) else 0
            val label = labelOf(show)
            val text = if (others > 0) "$label  +$others" else label
            // Every warning, worst first, with the aircraft's faults spelled out rather than
            // counted. Built from the LIVE set for the same reason the count is; when the shown
            // warning is only riding out its hold the set is empty, so fall back to the one
            // line the banner is holding — an expanded banner must never go blank.
            val all = if (active.isEmpty()) listOf(label) else
                active.sorted().flatMap { w ->
                    if (w == Warning.AIRCRAFT_FAULT) heldFaults() else listOf(w.label)
                }
            return Display(text, show.red, all)
        }
    }

    /**
     * New flight screen or aircraft cycle — drop the hold state so a stale banner from the last
     * flight cannot greet the pilot.
     *
     * ⚠ THE ACTIVE SET DOES NOT ALL REBUILD BY ITSELF. This doc once said it rebuilds within one
     * frame; that is true only of the warnings [onState] computes from telemetry each frame.
     * [AIRCRAFT_FAULT] arrives by a CHANGE-ONLY event, thus a fault that is already standing
     * when this runs is discarded and never returns — the aircraft has no reason to report it
     * again. Measured: that hid four live faults, two of them CAUTION, for as long as the
     * aircraft stayed connected.
     *
     * A caller that resets MUST therefore re-seed the fault straight afterwards, from the
     * bridge's cached list. [DroneTakBridge.start] is the one caller and it does this.
     *
     * LOGGED. Without the line, a flight-screen re-entry produces a second
     * "warning ACTIVE" with no "cleared" between, and a post-flight read cannot tell a condition
     * that persisted from one that went away and came back.
     */
    fun reset() {
        synchronized(lock) {
            if (active.isNotEmpty()) {
                AppLog.i(TAG, "warnings reset (${active.size} active discarded: " +
                    active.joinToString(", ") { it.name } + ")")
            }
            active = emptySet()
            faultText = ""
            lastFaultText = ""
            // The lists clear with the text they came from. Leaving them would carry the last
            // flight's faults into the new one through heldFaults(), which is the exact thing
            // this function exists to prevent.
            faultList = emptyList()
            lastFaultList = emptyList()
            shown = null
            shownAtMs = 0L
            lastRawDiagnostics = emptyList()
            if (muted.isNotEmpty()) AppLog.i(TAG, "session mutes cleared: ${muted.size} line(s)")
            muted = emptySet()
        }
    }

    private const val TAG = "FlightWarnings"
}
