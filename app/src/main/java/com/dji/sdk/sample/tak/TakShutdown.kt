package com.dji.sdk.sample.tak

import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog

/**
 * Closes EVERYTHING the application holds open to the outside — the one routine for every way
 * the pilot ends it: STOP/QUIT, a swipe from recents, and the controller powering off.
 *
 * ## ⚠ BOTH TAK CONNECTIONS, NOT ONE
 *
 * The swipe-away path once called `TakManager.disconnect()` alone. That closes cert A (Standard) and
 * NOTHING ELSE: the Elevated connection (cert B) is a separate client with its own lifecycle,
 * and the process survives a swipe (the foreground service is gone, but Android keeps the
 * process). Measured: after a swipe the Standard row left the TAK server's Connected Users,
 * the Elevated row stayed, the controller still held an ESTABLISHED socket to the CoT port, and
 * the drone bridge kept ticking every 2 s — so with the aircraft on, it could have gone on
 * reporting to the Elevated channel after the pilot closed the app. Elevated connections from
 * earlier runs were still listed on the server days later.
 *
 * The order is STOP/QUIT's, which already did most of this: the stream, the flight record, the
 * bridge (so nothing more is sent), then cert A, then cert B through [ElevatedChannel] — never
 * the core directly (see that object's note on start-then-stop).
 */
internal object TakShutdown {

    private const val TAG = "TakShutdown"

    /** Generous: a TLS close is one write. The power-off broadcast allows about 10 s in all. */
    private const val ELEVATED_CLEAR_WAIT_MS = 1_500L

    fun closeEverything(why: String) {
        AppLog.i(TAG, "closing every connection ($why)")
        runCatching { VideoStreamerHolder.stop() }
        // Before the bridge goes: this posts the GPX write to the logger's worker.
        runCatching { FlightPathLogger.endSession(why) }
        runCatching { TakBridgeHolder.stop() }
        runCatching { TakManager.getInstance().disconnect() }
        val cleared = runCatching { ElevatedChannel.clearAndWait(ELEVATED_CLEAR_WAIT_MS) }.getOrDefault(false)
        AppLog.i(TAG, if (cleared) "Elevated connection closed" else
            "Elevated connection: clear did not finish within ${ELEVATED_CLEAR_WAIT_MS}ms")
    }
}
