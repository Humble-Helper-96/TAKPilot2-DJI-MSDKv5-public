package com.dji.sdk.sample.tak

/**
 * Zoom display helpers — all that remains of the ladder.
 *
 * ⚠ THIS FILE ONCE HELD A TEN-RUNG TAP LADDER WITH HYBRID DISPLAY CROPS, and before that a
 * five-gear one. Both are gone (design decision): the
 * right dial reaches the camera's zoom through DJI's firmware on this aircraft, smoothly and
 * continuously, so every app-side stepping scheme was a fight with the hardware. The final
 * design needs no ladder at all — the dial zooms 1x-28x, the pill snaps to 1x, the display
 * follows the camera's own ratio. See `onZoomTapped` and `CameraZoomFollow` for the story.
 *
 * NO ANDROID OR SDK IMPORTS, ON PURPOSE — [ZoomLadderTest] pins what little is left.
 */
object ZoomLadder {

    /** The bottom of the range: 1x, the wide framing. The snap-to-1X pill's target. */
    const val MIN = 1.0

    /**
     * The pill's text for ANY ratio: "1X", "28X", "4.6X".
     *
     * ⚠ THIS USED TO BE `"${'$'}{ratio.toInt()}X"` WITH A COMMENT SAYING FRACTIONAL RATIOS WERE
     * THE CALLER'S JOB, and one caller did not do that job. `toInt()` truncates toward zero, so
     * a camera reporting **6.9958** — which is its gear 7 — was drawn as **"6X"** on the flight
     * screen after any restart with the camera left zoomed (measured).
     *
     * A function that silently truncates when it is misused is a trap, and the contract that
     * was supposed to prevent it lived only in a comment. It handles the whole range now, so
     * there is nothing left to get wrong: whole numbers print clean, anything else gets one
     * decimal. Values very close to a whole number round to it, because a camera that reports
     * 6.9958 for its own gear 7 should not make the pill say 7.0X.
     */
    fun label(ratio: Double): String {
        val nearest = Math.round(ratio).toDouble()
        return if (Math.abs(ratio - nearest) < 0.05) "${nearest.toInt()}X"
        else "%.1fX".format(java.util.Locale.US, ratio)
    }
}
