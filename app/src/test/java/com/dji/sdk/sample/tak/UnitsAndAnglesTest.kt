package com.dji.sdk.sample.tak

import com.dji.sdk.sample.takpilot2.Units
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Conversions and angle normalisation. Small, but these feed the HUD and the marker maths — a
 * wrong factor or a sign that does not wrap is a wrong picture for the whole team.
 */
class UnitsAndAnglesTest {

    @Test
    fun metersToFeetUsesTheRightFactor() {
        assertEquals(328.084, Units.metersToFeet(100.0), 0.001)
        assertEquals(0.0, Units.metersToFeet(0.0), 0.0)
    }

    @Test
    fun norm360WrapsBothDirections() {
        assertEquals(270.0, CameraSlantPoint.norm360(-90.0), 1e-9)
        assertEquals(90.0, CameraSlantPoint.norm360(450.0), 1e-9)
        assertEquals(0.0, CameraSlantPoint.norm360(360.0), 1e-9)
        assertEquals(359.5, CameraSlantPoint.norm360(-0.5), 1e-9)
        // Already in range: must pass through untouched, not shift by a rounding step.
        assertEquals(180.0, CameraSlantPoint.norm360(180.0), 1e-9)
    }

    /** Bearing and distance between two points, the pair the markers list and the HOME readout
     *  both use. Due north and due east from a known point, so the answers are checkable by
     *  inspection rather than by re-running the same formula. */
    @Test
    fun bearingAndDistanceAgreeWithTheCompass() {
        val lat = 45.2
        val lon = -100.9
        // One tenth of a degree of latitude north — about 11.1 km, bearing 000.
        val north = CameraSlantPoint.initialBearingDeg(lat, lon, lat + 0.1, lon)
        assertEquals(0.0, CameraSlantPoint.norm360(north), 0.5)
        assertEquals(11_119.0, CameraSlantPoint.distanceMeters(lat, lon, lat + 0.1, lon), 50.0)

        // East at this latitude: still 090, and shorter on the ground than the same degree
        // change in latitude — the cos(lat) term is the thing being checked.
        val east = CameraSlantPoint.initialBearingDeg(lat, lon, lat, lon + 0.1)
        assertEquals(90.0, CameraSlantPoint.norm360(east), 0.5)
        val eastM = CameraSlantPoint.distanceMeters(lat, lon, lat, lon + 0.1)
        assertEquals(7_835.0, eastM, 60.0)
    }

    /**
     * A look-point beyond the 10 km cap is CLAMPED to the cap, never replaced by the 300 m
     * "no solution" fallback. The old code substituted the fallback, so shallowing the gimbal
     * by one degree across the threshold snapped the point from ~10 km to 300 m in one tick.
     * Flat-ground path only (no elevation lookup), which is what exercises computeFlat.
     */
    @Test
    fun slantRangeClampsAtMaxInsteadOfCollapsingToTheFallback() {
        val lat = 45.14
        val lon = -100.93

        // 1000 m up at 2 deg below the horizon solves to ~28.6 km — well past the 10 km cap.
        val veryShallow = CameraSlantPoint.compute(lat, lon, 1_000.0, 0.0, -2.0)
        assertEquals(10_000.0, veryShallow.rangeMeters, 1.0)

        // Just inside the cap stays exact, so the clamp does not truncate ordinary geometry.
        val inRange = CameraSlantPoint.compute(lat, lon, 1_000.0, 0.0, -45.0)
        assertEquals(1_000.0, inRange.rangeMeters, 1.0)

        // Crossing the threshold must not jump: a hair either side stays near the cap rather
        // than collapsing to 300 m, which is the actual defect this pins.
        val justOver = CameraSlantPoint.compute(lat, lon, 1_000.0, 0.0, -5.71).rangeMeters
        val justUnder = CameraSlantPoint.compute(lat, lon, 1_000.0, 0.0, -5.72).rangeMeters
        assertEquals(justUnder, justOver, 50.0)

        // Geometry with no solution at all (camera level or above) still uses the fallback.
        val level = CameraSlantPoint.compute(lat, lon, 1_000.0, 0.0, 0.0)
        assertEquals(300.0, level.rangeMeters, 1e-9)
    }
}
