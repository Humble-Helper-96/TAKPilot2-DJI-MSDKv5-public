package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import dji.v5.manager.aircraft.perception.data.ObstacleData
import dji.v5.manager.aircraft.perception.data.PerceptionDirection
import dji.v5.manager.aircraft.perception.listener.ObstacleDataListener

/**
 * Obstacle-avoidance state, cached process-wide.
 *
 * MSDK v5 notes:
 * - There are no per-face callbacks. PerceptionManager pushes ONE
 *   [ObstacleData] with a horizontal distance ring (List<Int>, one entry per
 *   `horizontalAngleInterval` degrees) plus single up/down distances.
 * - **Units are MILLIMETRES.** The uxsdk RadarWidgetModel divides these by 1000 to get
 *   metres; this file does the same in exactly one place ([mmToMeters]).
 * - The horizontal ring is folded into four [Face] quadrants so the edge view has one
 *   value per edge. Ring index 0 is assumed to be the aircraft NOSE, increasing clockwise —
 *   ⚠ VERIFY ON THE AIRCRAFT with a wall on a known side before trusting left/right.
 * - There is more than one avoidance switch in v5. [ObstacleAvoidanceType]
 *   (BRAKE/BYPASS/CLOSE) is the coarse one, and there are others.
 *
 *   ⚠ **THIS FILE USED TO SAY "RTH avoidance and landing protection are aircraft-firmware
 *   behavior in v5 with no public switch" AND THAT WAS FALSE.** It was written from the one
 *   API somebody found, not from the SDK surface, and it cost a flight: the Pre-Flight
 *   landing box was wired to nothing, the status line said "not read yet" for ever, and the
 *   aircraft descended with whatever the last app left behind. Disassembled from
 *   `dji-sdk-v5-aircraft-provided-5.18.0.jar`, `IVisualManager` and
 *   `IPerceptionCommon` carry, each with a getter:
 *
 *     setObstacleAvoidanceEnabled(bool, PerceptionDirection)   // UPWARD/DOWNWARD/HORIZONTAL
 *     setVisionPositioningEnabled(bool)
 *     setPrecisionLandingEnabled(bool)
 *     setOverallObstacleAvoidanceEnabled(bool)
 *     setObstacleAvoidanceBrakingDistance(double, PerceptionDirection)
 *     setObstacleAvoidanceWarningDistance(double, PerceptionDirection)
 *
 *   DJI Pilot 2's own takeoff record on this airframe reports exactly these as
 *   `downward_obstacle_avoidance=1`, `OA_distance=3.0`, `OA_warning_distance=10.69`.
 *
 *   ⚠ RTH avoidance genuinely has no public v5 switch — that half of the old claim stands.
 *   Its field stays null and its Pre-Flight control says so.
 *
 * - **A CUSHIONED LANDING IS THREE SETTINGS, NOT ONE.** Downward avoidance sees the ground,
 *   vision positioning is what holds the aircraft steady over it, and precision landing is
 *   the auto-land behaviour. Any one of them off takes the cushion away, which is why they
 *   are read and enforced together rather than behind a single boolean.
 *
 * Absence must not read as safety: a ring that never reports means "this aircraft cannot
 * see that way", NOT "that way is clear" — [sensingAircraft] stays false until real data
 * arrives, and the display stays hidden.
 */
object DjiObstacleState {
    /** Avoidance settings, enforcement and warnings. ALWAYS logged. */
    private const val TAG = "TP2Obstacle"

    /** The repeating per-sample distance readout. Its own tag so the Debug screen can hide
     *  the volume WITHOUT hiding the settings lines above. */
    private const val RANGE_TAG = "TP2ObstacleRange"

    /** Horizontal quadrants relative to the airframe. */
    enum class Face { NOSE, RIGHT, TAIL, LEFT, UP, DOWN }

    /** Per-face nearest obstacle in METRES. Absent = that face has not reported, which is
     *  not the same as clear. */
    @Volatile
    var faces: Map<Face, Float> = emptyMap()
        private set

    /** True once any real obstacle data has arrived — i.e. this airframe has sensors and
     *  they are alive. */
    @Volatile
    var sensingAircraft = false
        private set

    /**
     * Notified whenever anything here changes (on DJI's callback thread — marshal it
     * yourself).
     *
     * ⚠ **A LIST, NOT A SLOT: THE LISTEN HAS ONE OWNER AND CONSUMERS ARE FED FROM IT.** This was once a single
     * `var onChanged: (() -> Unit)?`. The flight screen used it to drive the
     * obstacle edge view; the moment Pre-Flight started reading avoidance state it took the
     * same slot, and a second registration replaces the first WITH NO WARNING — a pilot could
     * have reached the flight screen with the obstacle display silently dead. The rule exists
     * because that exact shape has bitten before, and it applies to the app's own
     * fan-out as much as to an SDK slot.
     */
    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addChangeListener(l: () -> Unit) { if (!changeListeners.contains(l)) changeListeners.add(l) }
    fun removeChangeListener(l: () -> Unit) { changeListeners.remove(l) }

    private fun notifyChanged() {
        for (l in changeListeners) runCatching { l() }
    }

    // ---- Live avoidance state, as the AIRCRAFT reports it ----
    // Null means "not read yet", NOT "off". The difference matters in front of a pilot.
    /** ⚠ GENUINELY no public v5 switch — stays null, and the Pre-Flight control says so
     *  rather than offering a tick that does nothing. */
    @Volatile var rthAvoidance: Boolean? = null; private set

    // ---- The three that make a cushioned landing. See the class note. ----
    /** Downward obstacle avoidance — whether the aircraft reacts to the ground coming up. */
    @Volatile var downwardAvoidance: Boolean? = null; private set
    /** Vision positioning — what holds the aircraft steady over the ground on descent. */
    @Volatile var visionPositioning: Boolean? = null; private set
    /** Precision landing — the auto-land behaviour. */
    @Volatile var precisionLanding: Boolean? = null; private set
    /** The master switch, above [ObstacleAvoidanceType]. */
    @Volatile var overallAvoidance: Boolean? = null; private set
    /** Downward braking distance in metres, as the aircraft holds it. DJI Pilot 2 uses 3.0. */
    @Volatile var downwardBrakingM: Double? = null; private set

    /**
     * The composite a pilot actually cares about: will this aircraft cushion a descent?
     * True only when every input is known AND on. Null while anything is still unread —
     * "not read yet" and "off" must never render the same.
     */
    val cushionedLanding: Boolean?
        get() {
            val parts = listOf(downwardAvoidance, visionPositioning, overallAvoidance)
            return when {
                parts.any { it == null } -> null
                else -> parts.all { it == true }
            }
        }

    private val obstacleListener = ObstacleDataListener { data -> onObstacleData(data) }
    @Volatile private var listenerArmed = false

    /** Wired from [DjiSdkBridge] on every (re)connect. */
    fun onProductConnected(@Suppress("UNUSED_PARAMETER") context: Context) {
        armListener()

        // ⚠ READ AGAIN, BOUNDED. Measured: this fires at `onProductConnect: 0`,
        // about 400 ms BEFORE SDK registration completes, and four of the five getters answer
        // "unavailable" because the aircraft is not really there yet. A single read at connect
        // therefore reports "not available" for a whole connection on a healthy aircraft — the
        // same false unknown this honest-status work exists to remove. Re-asks only while
        // something is still unknown, a few times, then stops. READS ONLY: a timer
        // must never WRITE to the aircraft, and nothing here writes.
        rereadAttempt = 0
        scheduleReread()
    }

    /** Subscribes to the obstacle feed, once. Separated from [onProductConnected] so a
     *  resume can re-arm it without pretending the product just connected. */
    private fun armListener() {
        runCatching {
            if (!listenerArmed) {
                listenerArmed = true
                PerceptionManager.getInstance().addObstacleDataListener(obstacleListener)
            }
            AppLog.i(TAG, "obstacle-data listener armed")
        }.onFailure { AppLog.w(TAG, "obstacle-data listener failed: ${it.message}") }
    }

    /**
     * Re-read when a SCREEN IS SHOWN, not only when the product connects.
     *
     * ⚠ **THE CONNECT-TIME RETRY IS BOUNDED AND GIVES UP, AND IT LEFT NO WAY BACK.**
     * [scheduleReread] tries four times across about 29 seconds and then stops, which is
     * correct on its own — an airframe without a sensor must not be polled for ever. But a
     * connection where the values did not arrive inside that window was then stuck: every screen
     * showed "not available", the home card showed OBSTACLE AVOIDANCE "—" in amber, and its
     * "READING AIRCRAFT…" hold could never clear, for as long as the aircraft stayed
     * connected. Found on the controller with avoidance actually **ON** and every
     * screen saying otherwise — the exact false reading this whole honest-status design
     * exists to prevent.
     *
     * ⚠ **THIS IS THE SAME FIX, AND THE SAME REASONING, AS `TakAutoConnect.retryIfDown`:**
     * tie the retry to a SCREEN BEING SHOWN, not to a one-off event. A pilot looking at the
     * card is exactly the moment a fresh answer is worth asking for, and it costs nothing
     * when everything is already known.
     *
     * ⚠ **READS ONLY.** A TIMED WRITE to the aircraft is forbidden; nothing on this path writes.
     */
    fun refreshIfIncomplete() {
        if (!DjiSdkBridge.isProductConnected) return
        if (allKnown()) return
        // A cycle already running would have its handler stacked by a second one, and two
        // ladders interleaving would burn the budget in a fraction of the intended time.
        if (rereadInFlight) return
        AppLog.i(TAG, "avoidance state incomplete on resume — re-reading")
        armListener()
        rereadAttempt = 0
        scheduleReread()
    }

    fun onProductDisconnected() {
        faces = emptyMap()
        sensingAircraft = false
        rthAvoidance = null
        downwardAvoidance = null; visionPositioning = null; precisionLanding = null
        overallAvoidance = null; downwardBrakingM = null
        // The ladder belongs to the connection that is going away. Leaving the flag set would
        // make the next refreshIfIncomplete() decline to run, believing a cycle was trying.
        rereadHandler.removeCallbacksAndMessages(null)
        rereadInFlight = false
        lastLoggedNear = -1f
        notifyChanged()
    }

    private fun mmToMeters(mm: Int?): Float? =
        mm?.takeIf { it > 0 }?.let { it / 1000f }

    /**
     * One push of the full obstacle picture. The horizontal ring is folded to the nearest
     * reading per quadrant; a quadrant with no valid reading DROPS from the map (an absent
     * face must never keep a stale distance alive).
     */
    private fun onObstacleData(data: ObstacleData?) {
        data ?: return
        val ring: List<Int> = data.horizontalObstacleDistance ?: emptyList()
        val next = HashMap<Face, Float>(4)
        if (ring.isNotEmpty()) {
            val n = ring.size
            fun quadrantMin(centerFrac: Double): Float? {
                // Quadrant = center ± 1/8 of the ring, wrapping.
                val half = n / 8
                val center = (centerFrac * n).toInt()
                var min: Int? = null
                for (off in -half..half) {
                    val v = ring[((center + off) % n + n) % n]
                    if (v > 0 && (min == null || v < min!!)) min = v
                }
                return mmToMeters(min)
            }
            // Index 0 = NOSE, clockwise — verify on the aircraft before trusting left/right.
            quadrantMin(0.00)?.let { next[Face.NOSE] = it }
            quadrantMin(0.25)?.let { next[Face.RIGHT] = it }
            quadrantMin(0.50)?.let { next[Face.TAIL] = it }
            quadrantMin(0.75)?.let { next[Face.LEFT] = it }
        }
        // Up and down, the same millimetre contract as the ring (design decision: show them,
        // near only — the view's threshold decides). DOWN is the GROUND whenever the aircraft is
        // low, and the ground is not an obstacle while landing or sitting on it: it goes in
        // only when the aircraft says it is flying, and the view's near threshold keeps a
        // normal hover above it. A reading of 0 means "no reading", as in the ring.
        mmToMeters(data.upwardObstacleDistance)?.let { next[Face.UP] = it }
        if (TakBridgeHolder.hud()?.isFlying == true)
            mmToMeters(data.downwardObstacleDistance)?.let { next[Face.DOWN] = it }
        if (ring.isNotEmpty() || data.upwardObstacleDistance > 0) sensingAircraft = true
        faces = next
        next.entries.minByOrNull { it.value }?.let { logNearIfNotable(it.key, it.value) }
        notifyChanged()
    }

    /** Nearest obstacle on any face in metres, or null if nothing is reporting. */
    fun nearestMeters(): Float? = faces.values.minOrNull()

    // ---- Logging, biased toward what matters ----
    @Volatile private var lastLoggedNear = -1f
    @Volatile private var lastNearLogMs = 0L

    private fun logNearIfNotable(face: Face, m: Float) {
        if (m > LOG_NEAR_M) return
        val now = android.os.SystemClock.elapsedRealtime()
        // Log on meaningful movement or on a slow heartbeat, so a steady hover near a wall
        // does not fill the log while a genuine approach still gets sampled.
        if (now - lastNearLogMs < NEAR_MIN_GAP_MS && kotlin.math.abs(m - lastLoggedNear) < 0.5f) return
        lastNearLogMs = now
        lastLoggedNear = m
        AppLog.i(RANGE_TAG, "obstacle $face ${"%.1f".format(m)}m")
    }

    /** Below this a reading is worth a log line. */
    private const val LOG_NEAR_M = 15f
    private const val NEAR_MIN_GAP_MS = 500L

    /** Backing off, so a slow link still lands and a dead key costs four reads, not a loop. */
    private val REREAD_DELAYS_MS = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L)

    // ⚠ THE SAVED PRE-FLIGHT INTENT IS GONE WITH THE WRITES. There is no stored
    // "system/rth/landing" selection any more, because nothing could act on it. The
    // `takpilot2_avoid` preference file is simply left behind — reading a dead key to delete
    // it is not worth a migration, and nothing reads it.

    /**
     * Reads what the aircraft currently holds. **READ ONLY — this object writes NOTHING.**
     *
     * ⚠ THE WRITE PATH WAS DELETED, AND THAT IS THE FIX, NOT A RETREAT. It enforced a
     * Pre-Flight selection by writing [ObstacleAvoidanceType], and on this airframe every part
     * of that was broken:
     *
     *  - **The M4TD REJECTS the getter.** `getObstacleAvoidanceType` answers
     *    "unavailable: null" on this aircraft (measured), so the value it was
     *    guarding on could never be known. That field is gone with the write.
     *  - **Which made the write BLIND.** The guard was `if (<the unread value> == desired)`,
     *    and null never equals true — so the "only correct what is wrong" rule degraded into
     *    writing a flight-safety setting the app had just failed to read.
     *  - **And the chain died silently.** The flight-state gate resolves fail-safe only when a
     *    callback FIRES with a failure; a key that never answers at all leaves it hanging, with
     *    no write, no skip and no log line. Observed on the same aircraft. "An observer nobody
     *    subscribes to is not a mechanism."
     *
     * **THE REASON IT IS SAFE TO STOP IS NOT THAT THE WRITES WERE BROKEN — IT IS THAT THIS
     * AIRCRAFT HAS A PROPER CONFIGURATION SURFACE ALREADY.** These settings live in the
     * AIRCRAFT, not in an app; DJI Pilot 2 is on the same controller and sets them well, and
     * what it sets persists into this application.
     *
     * ⚠ **AN AIRCRAFT WITH NO SECOND APP TO SET THESE WOULD STILL NEED ENFORCEMENT.** The
     * doctrine "leaving whatever the other app last set is not neutral, it is UNKNOWN" is right
     * where nothing else configures the aircraft. Copying it here solved a problem this
     * airframe does not have. This is a deliberate difference with a reason, not a gap to close.
     *
     * What survives, and what actually matters, is that the pilot is shown the aircraft's REAL
     * state — never a tick that does nothing.
     */
    private fun readSwitches() {
        // Each read is independent: one unsupported key must not stop the others. A failure
        // leaves its field null, which renders as "not available", never as "off".
        readBool("overall obstacle avoidance",
            { cb -> PerceptionManager.getInstance().getOverallObstacleAvoidanceEnabled(cb) }) {
            overallAvoidance = it
        }
        readBool("downward obstacle avoidance",
            { cb -> PerceptionManager.getInstance()
                .getObstacleAvoidanceEnabled(PerceptionDirection.DOWNWARD, cb) }) {
            downwardAvoidance = it
        }
        readBool("vision positioning",
            { cb -> PerceptionManager.getInstance().getVisionPositioningEnabled(cb) }) {
            visionPositioning = it
        }
        readBool("precision landing",
            { cb -> PerceptionManager.getInstance().getPrecisionLandingEnabled(cb) }) {
            precisionLanding = it
        }
        runCatching {
            PerceptionManager.getInstance().getObstacleAvoidanceBrakingDistance(
                PerceptionDirection.DOWNWARD,
                object : CommonCallbacks.CompletionCallbackWithParam<Double> {
                    override fun onSuccess(v: Double?) {
                        downwardBrakingM = v
                        AppLog.i(TAG, "downward braking distance = $v m")
                        notifyChanged()
                    }
                    override fun onFailure(error: IDJIError) {
                        AppLog.i(TAG, "downward braking distance unavailable: ${error.description()}")
                    }
                })
        }.onFailure { AppLog.w(TAG, "downward braking distance read threw: ${it.message}") }

        // ⚠ RTH avoidance genuinely has no public v5 switch — unlike the rest of the old
        // claim, this half was true. Its field stays null and the screen says so.
        AppLog.i(TAG, "rthAvoidance: no public v5 getter — shown as not available")
    }

    @Volatile private var rereadAttempt = 0
    /** True while a re-read ladder is still scheduling itself. See [refreshIfIncomplete]. */
    @Volatile private var rereadInFlight = false
    private val rereadHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** True once every value the screens report is known, so the retry can stop early. */
    private fun allKnown(): Boolean =
        downwardAvoidance != null && visionPositioning != null &&
            precisionLanding != null && overallAvoidance != null

    private fun scheduleReread() {
        readSwitches()
        if (allKnown() || rereadAttempt >= REREAD_DELAYS_MS.size) {
            if (!allKnown()) {
                AppLog.i(TAG, "avoidance state still incomplete after $rereadAttempt re-reads " +
                    "— the unknown values stay \"not available\" until a screen asks again")
            }
            rereadInFlight = false
            return
        }
        val delay = REREAD_DELAYS_MS[rereadAttempt]
        rereadAttempt++
        rereadInFlight = true
        rereadHandler.postDelayed({ scheduleReread() }, delay)
    }

    /** One boolean read, with the same failure doctrine for all of them: log it, leave the
     *  field null, never guess. */
    private inline fun readBool(
        what: String,
        crossinline call: (CommonCallbacks.CompletionCallbackWithParam<Boolean>) -> Unit,
        crossinline assign: (Boolean?) -> Unit,
    ) {
        runCatching {
            call(object : CommonCallbacks.CompletionCallbackWithParam<Boolean> {
                override fun onSuccess(v: Boolean?) {
                    assign(v)
                    AppLog.i(TAG, "$what = $v")
                    notifyChanged()
                }
                override fun onFailure(error: IDJIError) {
                    // Common and harmless: an airframe without the feature rejects the getter.
                    AppLog.i(TAG, "$what unavailable: ${error.description()}")
                }
            })
        }.onFailure { AppLog.w(TAG, "$what read threw: ${it.message}") }
    }
}