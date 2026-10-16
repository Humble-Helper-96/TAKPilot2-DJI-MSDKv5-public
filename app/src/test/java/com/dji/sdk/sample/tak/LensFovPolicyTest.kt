package com.dji.sdk.sample.tak

import org.junit.Assert.assertEquals
import org.junit.Test

class LensFovPolicyTest {

    private val eo = 73.0      // the calibrated visible figure (TakBridgeHolder.DEFAULT_HFOV)
    private val ir = 35.5      // the thermal fallback, from the measured 52.7 mm focal length
    private val liveEo = 65.8  // what the camera reports on a visible lens
    private val liveIr = 35.3  // what the camera reports on the thermal lens

    @Test
    fun `the camera's live figure wins on either lens`() {
        assertEquals(liveEo, publishedHFov(CameraLens.EO, liveEo, eo, ir), 0.0)
        assertEquals(liveIr, publishedHFov(CameraLens.IR, liveIr, eo, ir), 0.0)
    }

    @Test
    fun `the constants are the fallback before the camera has spoken`() {
        assertEquals(eo, publishedHFov(CameraLens.EO, null, eo, ir), 0.0)
        // THE GAP THIS CLOSES: thermal with no report used to fall back to the visible base.
        assertEquals(ir, publishedHFov(CameraLens.IR, null, eo, ir), 0.0)
    }

    @Test
    fun `the vertical follows the tangent identity`() {
        // tan(h/2)/tan(v/2) == aspect, by construction.
        val h = 50.0
        val v = vFovForAspect(h, 1.5)
        val ratio = Math.tan(Math.toRadians(h / 2)) / Math.tan(Math.toRadians(v / 2))
        assertEquals(1.5, ratio, 1e-9)
    }

    @Test
    fun `diagonal to horizontal is the same conversion CameraFov applies`() {
        // The M4TD thermal: 52.7 mm equivalent -> 44.6 deg diagonal (CameraFov's measured
        // figure, DJI publishes 45). Under the bare 640x512 sensor that is about 35.5 wide.
        val d = 2.0 * Math.toDegrees(Math.atan(43.27 / (2.0 * 52.7)))
        assertEquals(44.6, d, 0.1)
        assertEquals(35.5, hFovFromDiagonal(d, 640.0 / 512.0), 0.1)
        // A 16:9 visible frame at 84 deg diagonal (the published wide FOV) is about 76 wide.
        assertEquals(76.2, hFovFromDiagonal(84.0, 16.0 / 9.0), 0.2)
    }
}
