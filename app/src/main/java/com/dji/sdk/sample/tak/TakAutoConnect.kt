package com.dji.sdk.sample.tak

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import java.io.File
import java.util.UUID

/**
 * Silent TAK reconnect from saved enrollment — no UI, no password re-entry. Used three ways:
 *  - [attemptOnAppLaunch]: fired once per PROCESS from the home screen's onCreate, so a
 *    configured server is connected, with channels pulled, before the pilot even opens
 *    Pre-Flight Setup.
 *  - [retryIfDown]: fired whenever the home screen or Pre-Flight is SHOWN. This is the one
 *    that actually covers a pilot — read its note for why once-per-process is not enough.
 *  - [toggle]: the flight-screen TAK icon's on/off tap (connect if saved creds exist and we're
 *    not connected; disconnect otherwise).
 *
 * Shares the same SharedPreferences keys as [TakConnectActivity].
 */
object TakAutoConnect {
    private const val TAG = "TakAutoConnect"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_HOST = "host"
    private const val KEY_COT_PORT = "cot_port"
    private const val KEY_USERNAME = "username"
    private const val KEY_CALLSIGN = "callsign"
    private const val KEY_UID = "uid"
    private const val KEY_TRUSTSTORE = "truststore_path"
    private const val KEY_CLIENTCERT = "clientcert_path"
    private const val KEY_CAMERA_POINT = "camera_point"
    private const val KEY_LOGGED_OUT = "logged_out"

    // ---- Video channel (cert B) — the Elevated account. This file keeps its OWN copy of
    // these keys rather than sharing TakConnectActivity's, which is the existing convention
    // here: KEY_HOST and the rest above are already duplicated from that Activity. ----
    /** Internal, not private: [AppModeTransition] writes it false on the way into BASIC. */
    internal const val KEY_CHB_ENABLED = "chb_enabled"
    private const val KEY_CHB_TRUSTSTORE = "chb_truststore_path"
    private const val KEY_CHB_CLIENTCERT = "chb_clientcert_path"
    private const val KEY_CHB_LOGGED_OUT = "chb_logged_out"

    @Volatile private var attemptedThisProcess = false

    fun attemptOnAppLaunch(context: Context) {
        if (attemptedThisProcess) return
        attemptedThisProcess = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (TakManager.getInstance().isConnected) return
        if (prefs.getBoolean(KEY_LOGGED_OUT, false)) {
            AppLog.i(TAG, "user logged out — skipping auto-connect")
            return
        }
        if (!hasSavedCerts(prefs)) {
            AppLog.i(TAG, "no saved enrollment — skipping auto-connect")
            return
        }
        AppLog.i(TAG, "auto-connecting to saved TAK server on app launch")
        reconnect(context.applicationContext)
    }

    /**
     * Reconnect if TAK is down and we have what we need — WITHOUT the once-per-process latch.
     *
     * ⚠ **THIS EXISTS BECAUSE MOVING THE TAK CONFIGURATION OFF PRE-FLIGHT BROKE THE RETRY.**
     * [attemptOnAppLaunch] fires once, from the home screen's `onCreate`, and
     * the home screen is `singleTask` — so coming back to it does not run again, and
     * `attemptedThisProcess` would block it anyway. The retry that actually covered the pilot
     * was the one in Pre-Flight's own `onCreate`, and that went to [TakServerActivity] with
     * the rest of the TAK configuration. The result was an app that would not reconnect unless
     * the pilot opened a screen they have no other reason to open, or tapped the flight
     * screen's TAK icon.
     *
     * ⚠ A LIVE PROCESS IS THE CASE THAT MATTERS. The foreground service keeps this process
     * alive across a swipe-away, so "launching the app again" is often not a new process at
     * all: `attemptedThisProcess` is still true and nothing retries. Tie the retry to a SCREEN
     * BEING SHOWN, not to a process starting.
     *
     * Safe to call as often as you like: [reconnect] refuses when an attempt is already in
     * flight, so this cannot spawn parallel connects.
     */
    fun retryIfDown(context: Context) {
        if (TakManager.getInstance().isConnected) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LOGGED_OUT, false)) return
        if (!hasSavedCerts(prefs)) return
        AppLog.i(TAG, "TAK is down and an enrollment is saved — retrying the connection")
        reconnect(context.applicationContext)
    }

    /** Flight-screen TAK icon tap: connect if we can, disconnect if we're up. */
    fun toggle(context: Context, onResult: (ok: Boolean, msg: String) -> Unit) {
        if (TakManager.getInstance().isConnected) {
            AppLog.i(TAG, "TAK icon tap — disconnecting")
            runCatching { TakManager.getInstance().disconnect() }
            // A removal, not a drop: with TAK off on purpose there is no split to fail closed
            // for, and the re-tap below reconnects the Elevated account if it is enabled.
            // Through ElevatedChannel: a direct clear here could stop a client a reconnect had
            // just started, which the shared core cannot survive. See ElevatedChannel.
            ElevatedChannel.clear()
            runCatching { TakForegroundService.stop(context.applicationContext) }
            onResult(true, "TAK disconnected")
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!hasSavedCerts(prefs)) {
            onResult(false, "No saved TAK enrollment — set up the server in Pre-Flight Setup first")
            return
        }
        AppLog.i(TAG, "TAK icon tap — reconnecting")
        // Report what actually happened. This used to claim "Reconnecting to TAK…" even
        // when reconnect() had refused the request, so a pilot tapping a second time was told
        // it was working while nothing new had started.
        if (reconnect(context.applicationContext)) {
            onResult(true, "Reconnecting to TAK…")
        } else {
            onResult(false, "Already reconnecting to TAK…")
        }
    }

    fun hasSavedCerts(prefs: android.content.SharedPreferences): Boolean {
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        return ts.isNotEmpty() && cc.isNotEmpty() && File(ts).exists() && File(cc).exists()
    }

    /** The same check for cert B — the Elevated account. Mirrors [hasSavedCerts]; cert B has
     *  no host of its own, it reuses cert A's (one controller, two certificates). */
    fun hasSavedVideoCerts(prefs: android.content.SharedPreferences): Boolean {
        val ts = prefs.getString(KEY_CHB_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CHB_CLIENTCERT, "") ?: ""
        return ts.isNotEmpty() && cc.isNotEmpty() && File(ts).exists() && File(cc).exists()
    }

    /**
     * True while a connect attempt is on its worker thread. Without this a second TAK-icon
     * tap spawned a PARALLEL connect, and TakManager.connect() is not reentrant — it disconnects
     * and reassigns its client field with no lock, so two racing calls can orphan a live
     * TakClient socket thread that keeps publishing PLI for the same uid with nothing left
     * holding a reference to stop it.
     */
    @Volatile internal var connecting = false

    /**
     * Connect using saved certs + saved server settings.
     *
     * @return true if an attempt was actually started. False means nothing is in flight —
     * either the enrollment is unusable or an attempt is already running — which the caller
     * needs in order to tell the pilot something true.
     */
    fun reconnect(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, "") ?: ""
        val username = prefs.getString(KEY_USERNAME, "") ?: ""
        val cotPort = prefs.getInt(KEY_COT_PORT, 8089)
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        val callsign = prefs.getString(KEY_CALLSIGN, "sUAS") ?: "sUAS"
        var uid = prefs.getString(KEY_UID, "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_UID, uid).apply()
        }
        if (host.isEmpty() || ts.isEmpty() || cc.isEmpty()) {
            AppLog.w(TAG, "reconnect requested but saved enrollment is incomplete")
            return false
        }
        if (connecting) {
            AppLog.i(TAG, "reconnect already in flight — ignoring this request")
            return false
        }
        connecting = true
        val droneUid = "$uid-DRONE"
        Thread {
            // The body used to be bare. An exception on a plain Thread reaches Android's
            // default uncaught handler, which KILLS THE PROCESS — so a bad cert file or an
            // unreachable host could take the whole flight screen down mid-flight, from a
            // background thread, for a failure that only ever needed a log line.
            try {
                TakManager.getInstance().connect(
                    uid, callsign, "Cyan", "Team Member",
                    host, cotPort, ts, "atakatak", cc, "atakatak",
                )
                // Cert B — the Elevated account. See dialVideoChannel.
                dialVideoChannel(prefs, host, cotPort)
                TakBridgeHolder.start(droneUid, callsign)
                TakBridgeHolder.setCameraPointEnabled(prefs.getBoolean(KEY_CAMERA_POINT, false))
                TakForegroundService.start(context, callsign)
                // This line used to read "connected to …". connect() is fire-and-forget —
                // it only starts TakClient's socket thread — so the log asserted a connection
                // that may never have happened, which is misleading in exactly the log someone
                // reads to find out why TAK is not working. The flight screen's TAK dot polls
                // the real state.
                AppLog.i(TAG, "connecting to $host:$cotPort as $callsign (socket result follows)")
                // The channel auto-pull is gone with channel selection: the feature
                // silently destroyed markers. See TakManager.
            } catch (t: Throwable) {
                AppLog.e(TAG, "TAK reconnect failed: ${t.message}", t)
            } finally {
                connecting = false
            }
        }.start()
        return true
    }

    /**
     * Cert B — the Elevated account. Queued on [ElevatedChannel], which reads the mode at the
     * moment of the dial, skips a channel that is already configured, and re-reads the mode
     * after dialling. Every gate and every race note lives there, so this path and the
     * return-to-Enhanced path cannot disagree.
     *
     * ## ⚠ THE MODE GATE, AND IT IS THE ONE THAT MATTERS
     *
     * [reconnect] is the single chokepoint for boot, the home screen's resume, Pre-Flight's
     * resume AND the in-flight TAK badge. Ungated, a BASIC pilot who taps the badge to clear a
     * link wobble — a normal action on an airframe that drops its RF link for seconds at a time
     * — re-arms the split WITH THE STREAM UP, and the Standard channels silently lose the feed
     * from a tap meant to restore the connection. [AppModeTransition] also writes
     * chb_enabled=false; keep both.
     */
    internal fun dialVideoChannel(prefs: android.content.SharedPreferences, host: String, cotPort: Int) {
        ElevatedChannel.dial(
            prefs, host, cotPort,
            prefs.getString(KEY_CHB_TRUSTSTORE, "") ?: "",
            prefs.getString(KEY_CHB_CLIENTCERT, "") ?: "",
            force = false,
        ) { hasSavedVideoCerts(prefs) }
    }

    /**
     * Brings cert B back after a change to ENHANCED, without bouncing cert A.
     *
     * ⚠ IT ALWAYS QUEUES THE DIAL — it never returns early because a reconnect is in flight.
     * The first version did, and a reconnect that had already passed its own cert-B step while
     * the mode was still BASIC then finished with no cert B: the split never came back while
     * Pre-Flight section 8 (Application Mode) said it had. The dial is idempotent, so a reconnect
     * that also reaches it adds nothing. With cert A down and nothing in flight, a full
     * [reconnect] brings cert A up as well.
     */
    fun dialVideoChannelIfAllowed(context: Context) =
        redialAfterEnhanced(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            isConnected = { TakManager.getInstance().isConnected },
        ) { reconnect(context) }

    /** [dialVideoChannelIfAllowed] with its two live inputs passed in, for the unit tests. */
    internal fun redialAfterEnhanced(
        prefs: android.content.SharedPreferences,
        isConnected: () -> Boolean,
        reconnect: () -> Unit,
    ) {
        val host = prefs.getString(KEY_HOST, "") ?: ""
        if (host.isEmpty()) return
        dialVideoChannel(prefs, host, prefs.getInt(KEY_COT_PORT, 8089))
        if (!isConnected() && !connecting) reconnect()
    }
}
