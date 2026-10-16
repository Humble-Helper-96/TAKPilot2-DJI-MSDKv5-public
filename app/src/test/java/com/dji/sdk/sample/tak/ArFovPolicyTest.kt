package com.dji.sdk.sample.tak

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The field the AR overlay projects with, per lens. There is no PIP case: this airframe has
 * no composite picture. What it pins is that the thermal picture is projected with a THERMAL
 * field whether or not the camera has reported one, and that the vertical always derives
 * from the horizontal that is actually in use.
 */
class ArFovPolicyTest {

    private val calibrated = 73.0
    private val ir = 35.5
    private val video = 16.0 / 9.0      // 1920x1080 visible
    private val thermal = 640.0 / 512.0 // the bare sensor, 5:4

    private fun arFov(lens: CameraLens, live: Double?, aspect: Double): Pair<Double, Double> {
        val h = publishedHFov(lens, live, calibrated, ir)
        return h to vFovForAspect(h, aspect)
    }

    @Test
    fun `thermal pairs the live horizontal with the vertical of THAT lens`() {
        val (h, v) = arFov(CameraLens.IR, 35.3, thermal)
        assertEquals(35.3, h, 0.01)
        assertEquals(28.6, v, 0.1)
    }

    @Test
    fun `thermal before the camera has spoken is still a thermal field, not the visible one`() {
        // Without this rule the overlay projected with 73 x 41 across a 35-degree picture.
        val (h, v) = arFov(CameraLens.IR, null, thermal)
        assertEquals(35.5, h, 0.01)
        assertEquals(28.8, v, 0.1)
    }

    @Test
    fun `visible pairs the live horizontal with its own vertical`() {
        val (h, v) = arFov(CameraLens.EO, 65.8, video)
        assertEquals(65.8, h, 0.01)
        assertEquals(39.9, v, 0.1)
    }
}
