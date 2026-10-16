package com.dji.sdk.sample.tak

/**
 * The lens whose picture is on the screen. MSDKv5 switches the video STREAM SOURCE (wide /
 * zoom / infrared) and composites nothing, so there are two cases and no picture-in-picture
 * blend case.
 */
enum class CameraLens { EO, IR }

/**
 * Which horizontal field of view the overlay projects with and the wire publishes, for the
 * lens that is live.
 *
 * The camera's live figure comes from its reported focal length ([CameraFov]) for the visible
 * lenses AND the thermal lens, and the overlay and the `<sensor>` cone already read one
 * accessor, so the screen and the wire cannot disagree. The gap this closes: before the thermal
 * focal read has answered — or when the aircraft refuses it — the fallback was the calibrated
 * VISIBLE base, projected across a thermal picture about half as wide. The thermal constant
 * is the fallback for that lens now, as the visible calibration is for the visible one.
 *
 * Pure, no SDK import, so the choice is pinned by a unit test rather than by a flight.
 *
 * @param liveHFov the horizontal field the camera itself reports, null before it has spoken.
 * @param calibratedHFov the application's calibratable visible-lens figure.
 * @param irHFov the thermal constant, the fallback for a camera that has not reported yet.
 */
internal fun publishedHFov(
    lens: CameraLens,
    liveHFov: Double?,
    calibratedHFov: Double,
    irHFov: Double,
): Double = when (lens) {
    // The camera reports the field for whatever lens is live, thermal included, so when it is
    // talking the lens is not consulted. The constants are the fallback for a camera that has
    // not reported yet.
    CameraLens.IR -> liveHFov ?: irHFov
    CameraLens.EO -> liveHFov ?: calibratedHFov
}

/**
 * The vertical field that pairs with [hDeg] under the live video aspect, in tangent space —
 * the only pairing that is self-consistent for a rectilinear lens:
 * `tan(hFov/2) / tan(vFov/2) == frameWidth / frameHeight`. Pure; [TakBridgeHolder.vFovFor]
 * calls it with the live aspect.
 */
internal fun vFovForAspect(hDeg: Double, aspect: Double): Double =
    2.0 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(hDeg / 2.0)) / aspect))

/**
 * The horizontal field under [aspect] for a lens whose DIAGONAL field is [dDeg]:
 * `tan(h/2) = tan(d/2) · w / sqrt(w² + h²)`. The one conversion [CameraFov]'s focal-length
 * route and the thermal fallback constant both go through, so they cannot disagree.
 */
internal fun hFovFromDiagonal(dDeg: Double, aspect: Double): Double {
    val f = aspect / Math.sqrt(aspect * aspect + 1.0)
    return 2.0 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(dDeg / 2.0)) * f))
}
