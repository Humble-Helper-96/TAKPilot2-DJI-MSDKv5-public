package com.dji.sdk.sample.tak

import org.junit.Assert.assertEquals
import org.junit.Test

/** What little remains after the ladder's retirement — see ZoomLadder's class doc. */
class ZoomLadderTest {

    @Test
    fun theFloorIsTheWideFraming() {
        assertEquals(1.0, ZoomLadder.MIN, 0.0)
    }

    @Test
    fun wholeRatiosHaveNoDecimals() {
        assertEquals("1X", ZoomLadder.label(1.0))
        assertEquals("28X", ZoomLadder.label(28.0))
    }

    /**
     * ⚠ THE REGRESSION THIS FILE EXISTS FOR NOW. label() truncated with toInt(), so the camera
     * reporting 6.9958 for its own gear 7 put "6X" on the flight screen.
     */
    @Test
    fun aRatioJustUnderAGearReadsAsThatGear() {
        assertEquals("7X", ZoomLadder.label(6.995833333333334))
        assertEquals("1X", ZoomLadder.label(1.0083333333333333))
        assertEquals("3X", ZoomLadder.label(2.9791666666666665))
    }

    @Test
    fun ratiosBetweenGearsKeepOneDecimal() {
        assertEquals("4.6X", ZoomLadder.label(4.6))
        assertEquals("1.5X", ZoomLadder.label(1.525))
        assertEquals("17.5X", ZoomLadder.label(17.491666666666667))
    }
}
