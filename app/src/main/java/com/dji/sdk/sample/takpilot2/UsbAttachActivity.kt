package com.dji.sdk.sample.takpilot2

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.taklite.util.AppLog

/**
 * Invisible landing pad for `USB_ACCESSORY_ATTACHED`. It shows nothing and finishes at once.
 *
 * This filter used to sit on [TAKPilot2GoHomeActivity], which is `singleTask`. Delivering
 * the intent therefore brought Home forward and DESTROYED every activity above it — so a USB
 * event while the pilot was flying tore down the flight screen, and with it the drone's CoT
 * feed to TAK (`TakBridgeHolder.stop`), the team's video (`VideoStreamerHolder.stop`) and the
 * keep-awake flag, leaving the pilot to notice and tap back in. On an exported activity that
 * path is reachable by any app on the device, not only by real hardware.
 *
 * Home keeps `launchMode="singleTask"` — that is a design requirement, and
 * it exists so a forgotten intent flag cannot stack a second Home. The fix is to stop
 * pointing USB attach at Home, not to weaken the launch mode. `singleTop` would be WORSE: Home
 * is not on top during a flight, so the intent would build a second Home above the flight
 * screen — same teardown, plus the stacked Home the specification forbids.
 *
 * Structure follows DJI's own v5 sample (`UsbAttachActivity`, translucent, filter + accessory
 * meta-data moved off the launcher). Its BODY is deliberately not copied: the sample does
 * `NEW_TASK or CLEAR_TASK` back to main, which is precisely the teardown being fixed here.
 *
 * Nothing in this app reads the accessory payload — MSDK v5 handles attach internally once it
 * is initialised. This activity exists only so the framework has somewhere to deliver the
 * intent that is not the pilot's task stack.
 */
class UsbAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val running = TAKPilot2GoHomeActivity.visitedThisProcess
        AppLog.i(TAG, "USB accessory attached (app already running: $running)")
        if (!running) {
            // Cold start from an accessory attach: this is the case the auto-launch exists for,
            // so open Home normally. No CLEAR_TASK — there is nothing to clear, and using it
            // would re-create the very behaviour this class removes.
            AppLog.i(TAG, "cold start from accessory attach — opening Home")
            runCatching {
                startActivity(Intent(this, TAKPilot2GoHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { AppLog.w(TAG, "could not open Home: ${it.message}") }
        }
        // Already running: do nothing at all. Whatever the pilot is on — most importantly the
        // flight screen — stays exactly where it is.
        finish()
    }

    private companion object {
        const val TAG = "UsbAttach"
    }
}
