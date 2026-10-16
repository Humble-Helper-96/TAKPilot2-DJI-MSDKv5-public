package com.dji.sdk.sample.takpilot2

import android.app.Application
import android.content.Context
import com.dji.sdk.sample.BuildConfig
import com.cySdkyc.clx.Helper
import com.dji.sdk.sample.tak.EmergencyBroadcastPolicy
import com.dji.sdk.sample.tak.FlightPathLogger
import com.dji.sdk.sample.tak.TakBridgeHolder
import com.dji.sdk.sample.tak.TakDropMarkers
import com.dji.sdk.sample.tak.TakMissionManager
import com.dji.sdk.sample.tak.UasfmIndex
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog

class TAKPilot2Application : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // DJI security component. Must install before the SDK starts.
        Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // AppLog holds appContext as a lateinit, and nothing called init() until now. The
        // Log.* half of AppLog worked without it, thus the gap stayed hidden; the file half
        // did not. The Debug screen reads the log file on a timer and crashed the process on
        // open. Initialise before anything logs.
        AppLog.init(this)
        settleAppMode()
        TakBridgeHolder.init(this)
        // These three singletons each store appContext only in init(), and without these calls
        // each failed silently: no flight record, every install shared the same default
        // quick-marker callsign and lost dropped pins on restart, and feed membership never
        // survived a restart.
        FlightPathLogger.init(this)
        FlightPathLogger.sweepOrphans()
        TakDropMarkers.init(this)
        TakMissionManager.init(this)
        // Same shape as the three above — preload() says "call once at app start" in its
        // own doc and nothing ever did. Without it the FAA cell table (tens of thousands of
        // rows for a statewide pull) is read on the first HUD tick instead, i.e. on the main
        // thread, stalling the flight screen for the length of the read. It warms on its own
        // worker and is safe when nothing has been downloaded.
        UasfmIndex.preload(this)
        // The stream path this process will publish under, once. This is also where the
        // per-process token is made when the random path is on — see StreamPath.
        com.dji.sdk.sample.tak.StreamPath.logSessionPath(this)
        // What the application does when an Emergency Broadcast starts, renews or ends — the
        // flight-record line. Here, not on a screen, so it runs whichever screen is open, or
        // none. See EmergencyBroadcastPolicy.
        EmergencyBroadcastPolicy.install(this)
        // ⚠ WITHOUT THIS EVERY CONTROLLER REPORTS ITSELF AS "TAK Lite 1.0". TakManager ships
        // deliberately GENERIC placeholders for the <takv> block — the core is shared between
        // applications, so hardcoding one app's name inside it would mislabel the others. Each
        // app is meant to replace them here, at startup; without this call a TAK server's
        // connected-users panel shows the placeholder and `Unknown Device (<callsign>)`.
        //
        // ⚠ THE VERSION COMES FROM BuildConfig, NOT A LITERAL, so it cannot drift from the
        // build it ships in. TAKPilot2GoHomeActivity's version line claims it "cannot disagree
        // with what the TAK server was told"; this call is what makes that true — otherwise
        // the server is told "1.0" whatever the build says.
        //
        // ⚠ This is the SIXTH initialiser of exactly this shape in this file — AppLog,
        // FlightPathLogger, TakDropMarkers, TakMissionManager and UasfmIndex are the others. A
        // singleton whose doc says "call once at app start" needs its call added HERE in the
        // same change, or it fails silently and is found months later.
        TakManager.getInstance().setClientIdentity(
            "TAKPilot2-DJIv5",
            android.os.Build.MODEL,
            "Android " + android.os.Build.VERSION.RELEASE,
            BuildConfig.VERSION_NAME)
    }

    /**
     * Decides ENHANCED or BASIC before ANYTHING reads it — and re-asserts BASIC's invariants.
     *
     * ⚠ **HERE, NOT IN PRE-FLIGHT.** Running the first-run decision from Pre-Flight's onCreate
     * is not enough: the boot TAK dial (Home's onCreate), the flight screen's channel unlock and
     * the Field Guide are all reachable BEFORE Pre-Flight is ever opened, and until the decision
     * runs each of them reads the default. That default is
     * ENHANCED, which is safe for the dial — but a fresh device destined for BASIC would still
     * ask for a password in the air until someone happened to open Pre-Flight. The Application
     * runs before every Activity and every restarted Service, so nothing can read an unsettled
     * mode.
     *
     * ⚠ **THE ORDER INSIDE IS LOAD-BEARING, AND IT LIVES IN [com.dji.sdk.sample.tak.AppMode.settle]**
     * — a function of the preferences alone, so a test holds the order instead of this comment:
     * the video slot migrations FIRST, then the first-run decision, then the plain video keys
     * re-derived from the slots in both modes.
     */
    private fun settleAppMode() {
        // ⚠ GUARDED — THIS RUNS ON EVERY PROCESS START. Android's SharedPreferences throws
        // ClassCastException when a key holds a different type than it is read as. A drifted
        // key could already crash Pre-Flight; unguarded here it would crash EVERY launch — Home
        // and the flight screen included — for good. On failure the mode stays unwritten and
        // reads ENHANCED, which is the behaviour from before modes existed.
        runCatching {
            com.dji.sdk.sample.tak.AppMode.settle(
                getSharedPreferences(com.dji.sdk.sample.tak.TakConnectActivity.PREFS, MODE_PRIVATE),
                hasElevated = com.dji.sdk.sample.tak.TakMissionManager.hasElevatedEnrollment(this),
            )
        }.onFailure { AppLog.e("TAKPilot2Application", "app mode not settled: ${it.message}", it) }
    }
}
