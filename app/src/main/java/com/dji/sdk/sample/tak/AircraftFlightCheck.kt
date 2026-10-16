package com.dji.sdk.sample.tak

import com.taklite.util.AppLog
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

/**
 * "Is the aircraft flying?" — asked of the AIRCRAFT, at the moment it matters.
 *
 * ⚠ **THE OBVIOUS SOURCE FAILS OPEN, AND THAT IS WHY THIS EXISTS.** `TakBridgeHolder.hud()` is
 * null whenever the bridge is stopped, and the flight screen's `onStop` stops it. Pre-Flight
 * opens from Home, and the bridge restarts there only when TAK was DOWN — so with TAK up,
 * `hud()?.isFlying == true` reads "grounded" on an aircraft that is in the air. Found while
 * adding the Basic mode.
 *
 * So the refusals that must not act on a flying aircraft — the mode switch, which closes the
 * socket a supervisor tracks the aircraft on, and Format Card, which destroys footage — ask
 * fresh, with the TWO-argument `getValue` (the one-argument form reads MSDK's
 * local cache, which answers null for a key nothing has fetched).
 *
 * ## The three answers
 *
 *  - `false` — the aircraft says it is on the ground; or it is not linked and the last thing it
 *    said was not "flying". Proceed.
 *  - `true` — the aircraft says it is flying. Refuse.
 *  - `null` — unknown. ⚠ REFUSE: an unknown must never be collapsed into "grounded".
 *
 * ## ⚠ A LINK DROP IS NOT A LANDING
 *
 * The first version answered `false` whenever no aircraft was linked, reasoning that a link-lost
 * aircraft is not feeding a position to publish. But this airframe drops its RF link for seconds
 * at a time in ordinary flight. A pilot on Pre-Flight during one of those drops could change the
 * mode, close the Elevated socket — and the link would return seconds later to a flight with no
 * Elevated audience for the rest of it. So an unlinked aircraft counts as grounded only if the
 * last thing it reported was NOT flying. That value is fed by the bridge's existing KeyIsFlying
 * listener ([noteFlying]) — consumers are fed from the bridge, not subscribed to
 * the SDK — and by this object's own fresh reads. A `null` from a dropping link never reaches it.
 *
 * ## ⚠ IT ALWAYS ANSWERS, AND IT ANSWERS ONCE
 *
 * A key that never replies used to leave the caller waiting for ever: the mode button and Format
 * did nothing, with no message. Each read has [TIMEOUT_MS]. After that, the
 * flying key falls back to the connection key, and the connection key answers `null` — refuse.
 * A reply that arrives after the answer is ignored, so a late "grounded" can never trigger an
 * irreversible action long after the pilot confirmed it. Also: a completion
 * callback can fire twice; the decision is taken once.
 *
 * ⚠ **EACH READ HAS ITS OWN BUDGET.** The connection read
 * exists for the aircraft-OFF case, and in that case the flying key can take the SDK's own
 * timeout to fail, or never answer. With one shared budget the connection key was never asked,
 * and every change with the aircraft off was refused. So the worst case is 2 x [TIMEOUT_MS].
 *
 * ⚠ **OPEN, AND IT NEEDS MEASURING ON THE AIRCRAFT**: an unlinked aircraft
 * with NO last-known value (the app restarted mid-flight during a link drop) still reads as
 * grounded. What the SDK returns for the flying and connection keys with the aircraft OFF is not
 * measured yet, and the choice depends on it. [AircraftFlightCheckTest] pins the current table,
 * so the change, when it comes, is a visible one.
 *
 * The callback can arrive on an SDK thread or the main thread; callers post to the main thread
 * themselves and must check that their screen still exists.
 */
object AircraftFlightCheck {

    private const val TAG = "AircraftFlightCheck"

    internal const val TIMEOUT_MS = 3_000L

    /** The last value the aircraft reported. Never null once known: a link that is dropping
     *  reports null, and that must not overwrite a "flying". Internal for the tests only. */
    @Volatile internal var lastKnownFlying: Boolean? = null

    /** Fed from the bridge's KeyIsFlying listener. Non-null values only. */
    fun noteFlying(flying: Boolean) {
        lastKnownFlying = flying
    }

    /** What this object needs from the SDK. A seam, so [AircraftFlightCheckTest] can drive every
     *  row of the table without an aircraft. */
    internal interface Sdk {
        fun isLinked(): Boolean
        fun readIsFlying(then: (Boolean?) -> Unit)
        fun readConnection(then: (Boolean?) -> Unit)
    }

    private object LiveSdk : Sdk {
        override fun isLinked() = DjiSdkBridge.isProductConnected
        override fun readIsFlying(then: (Boolean?) -> Unit) = read(FlightControllerKey.KeyIsFlying, then)
        override fun readConnection(then: (Boolean?) -> Unit) = read(FlightControllerKey.KeyConnection, then)
    }

    // ---- Seams. Production values here; tests replace both. ----
    internal var sdk: Sdk = LiveSdk
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    internal var schedule: (Long, () -> Unit) -> Unit = { ms, r -> main.postDelayed(r, ms) }

    fun ask(callback: (Boolean?) -> Unit) {
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        fun once(v: Boolean?, why: String) {
            if (answered.compareAndSet(false, true)) {
                AppLog.i(TAG, "flying = $v ($why)")
                callback(v)
            }
        }

        if (!sdk.isLinked()) {
            once(unlinked(), "no aircraft linked")
            return
        }

        // ⚠ The flying key did not answer. That is also what an RC with the aircraft OFF can look
        // like, and refusing every change made with the aircraft off would block the ordinary way
        // to configure a controller — so ask whether the flight controller is even connected. A
        // definite "not connected" is the unlinked case; anything else is unknown, and unknown
        // refuses. It starts once only: on a failure or at the end of the flying key's budget.
        val fellBack = java.util.concurrent.atomic.AtomicBoolean(false)
        fun fallBack(why: String) {
            if (answered.get() || !fellBack.compareAndSet(false, true)) return
            AppLog.i(TAG, "$why — asking whether the flight controller is connected")
            schedule(TIMEOUT_MS) { once(null, "no answer within ${TIMEOUT_MS}ms — refusing") }
            sdk.readConnection { connected ->
                if (connected == false) once(unlinked(), "flight controller not connected")
                else once(null, "flying state unreadable")
            }
        }

        schedule(TIMEOUT_MS) { fallBack("the flying key did not answer within ${TIMEOUT_MS}ms") }
        sdk.readIsFlying { flying ->
            if (flying != null) {
                // A real report from the aircraft. If it arrives while the fallback is running it
                // is still the better answer, so it is taken.
                noteFlying(flying)
                once(flying, "aircraft reported")
            } else {
                fallBack("the flying key failed")
            }
        }
    }

    /** Not linked: grounded — unless the last thing the aircraft said was that it was flying. */
    private fun unlinked(): Boolean? = if (lastKnownFlying == true) null else false

    private fun read(
        key: dji.sdk.keyvalue.key.DJIKeyInfo<Boolean>,
        then: (Boolean?) -> Unit,
    ) {
        runCatching {
            KeyManager.getInstance().getValue(
                KeyTools.createKey(key),
                object : CommonCallbacks.CompletionCallbackWithParam<Boolean> {
                    override fun onSuccess(value: Boolean?) = then(value)
                    override fun onFailure(error: IDJIError) {
                        AppLog.w(TAG, "read failed: ${error.description()}")
                        then(null)
                    }
                },
            )
        }.onFailure {
            AppLog.w(TAG, "read threw: ${it.message}")
            then(null)
        }
    }
}
