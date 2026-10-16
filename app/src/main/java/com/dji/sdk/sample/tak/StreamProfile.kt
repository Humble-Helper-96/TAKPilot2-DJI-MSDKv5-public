package com.dji.sdk.sample.tak

/**
 * The outbound video quality tier the pilot picks (LIVE pill, touch and hold).
 *
 * This used to be `StreamTranscoder.TranscodeProfile`, nested inside a 348-line class that was
 * never constructed. That class was a decode -> scale -> encode pipeline for the aircraft feed;
 * this application captures the composited screen instead ([ScreenCaptureEncoder]), so the
 * only living part of the file was this enum. The class is gone — and with it a latent defect,
 * where the encoder was built on the first INFO_OUTPUT_FORMAT_CHANGED and a mid-stream
 * resolution change (thermal 5:4 to visible 16:9) left stale dimensions and silently wrong
 * geometry. If a decode-transcode path is ever wanted back, that must be fixed first.
 *
 * Renamed while moving: nothing transcodes any more, so "Transcode" described a pipeline that no
 * longer exists. The CONSTANT names are unchanged because [prefValue] derives from them, and a
 * saved preference has to keep resolving.
 */
enum class StreamProfile(val maxHeight: Int, val fps: Int, val bitrateBps: Int) {
    LOW(480, 10, 375_000),        // 1066x480 — marginal/cellular links
    STANDARD(720, 15, 800_000),   // 1600x720 — default
    HIGH(1080, 15, 1_800_000);    // 2400x1080

    /** The pref-file spelling, derived so a rename cannot desynchronise the two. */
    val prefValue: String get() = name.lowercase()

    /** The menu label — "Low", "Standard", "High". */
    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        fun fromPref(name: String?): StreamProfile = when (name) {
            "low" -> LOW
            "high" -> HIGH
            // Also the landing place for the legacy "original" profile — see
            // VideoStreamerHolder.normalizeProfile.
            else -> STANDARD
        }
    }
}
