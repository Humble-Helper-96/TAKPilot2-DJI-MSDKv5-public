package com.dji.sdk.sample.takpilot2

import java.util.Locale

/**
 * Imperial display units for the flight UI. The aircraft and every internal calculation work in
 * metres — conversion happens only at the point of display, so nothing downstream (CoT, DTED,
 * slant-range math) is affected by what the pilot sees.
 *
 * The display standard is imperial throughout: the Pre-Flight Setup limits are already entered in
 * feet, the altitude readout is feet and speed is MPH, so distances being metres was the odd one
 * out rather than a deliberate choice.
 *
 * ⚠ `Locale.US` on every format call, deliberately. `String.format` with no locale uses the
 * device default, and a comma-decimal locale (most of Europe) or a non-Latin-digit one would
 * turn every readout on the HUD into something a fixed-format ATAK-style pilot display was
 * never built to show — this is a pilot-safety readout, not a locale-aware label.
 */
object Units {
    const val FEET_PER_METER = 3.28084
    const val MPH_PER_MS = 2.23694
    private const val FEET_PER_MILE = 5280.0

    fun metersToFeet(meters: Double): Double = meters * FEET_PER_METER

    /** Whole feet, e.g. `"1240 ft"`. For values that stay comfortably under a mile — the
     *  aircraft's own max-distance geofence defaults to 5280 ft, so the home-distance readout
     *  lives here rather than switching units mid-flight. */
    fun feet(meters: Double): String = "%.0f ft".format(Locale.US, metersToFeet(meters))

    /** Feet under a mile, miles above it (`"820 ft"` / `"2.4 mi"`). For distances with no
     *  natural bound — dropped markers can be anywhere, and five-digit feet stops being
     *  readable at a glance. */
    fun distance(meters: Double): String {
        val ft = metersToFeet(meters)
        return if (ft < FEET_PER_MILE) "%.0f ft".format(Locale.US, ft)
        else "%.1f mi".format(Locale.US, ft / FEET_PER_MILE)
    }

    fun mph(metersPerSecond: Double): String = "%.0f mph".format(Locale.US, metersPerSecond * MPH_PER_MS)
}
