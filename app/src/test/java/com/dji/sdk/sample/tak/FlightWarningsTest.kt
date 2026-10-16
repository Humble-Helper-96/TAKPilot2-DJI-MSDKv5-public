package com.dji.sdk.sample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the banner policy: what is shown, in what order, and for how long.
 *
 * These are the rules a pilot reads during a fault, so they are pinned rather than left to be
 * re-derived. The display half is driven through [FlightWarnings.displayAt] with an explicit
 * clock so the hold can be stepped without sleeping.
 *
 * The state half of the policy ([FlightWarnings.onState]) is not exercised here: it takes a
 * `FlightControllerState`, whose fields are only settable through the SDK's own setters, and
 * constructing one under `returnDefaultValues` yields an object whose getters all return null.
 * The pure predicates it delegates to ARE covered, which is where the airframe-specific mapping
 * decisions actually live.
 */
class FlightWarningsTest {

    @Before
    fun clean() {
        FlightWarnings.reset()
        FlightWarnings.debugLogOn = false
    }

    // ---- Display policy ----

    @Test
    fun nothingActiveShowsNothing() {
        assertNull(FlightWarnings.displayAt(1_000L))
    }

    @Test
    fun anAircraftFaultIsShownVerbatim() {
        FlightWarnings.onDiagnostics(listOf("Compass error"))
        val d = FlightWarnings.displayAt(1_000L)!!
        assertEquals("Compass error", d.text)
        assertTrue(d.red)
    }

    /**
     * The no-fly-zone decision, pinned. This diagnostic is NOT suppressed to the log, because
     * suppressed it went invisible through two flights. If a log-only rule is ever introduced
     * for it, this test is what stops that happening silently.
     */
    @Test
    fun aNoFlyZoneDiagnosticReachesTheBanner() {
        FlightWarnings.onDiagnostics(listOf("Cannot takeoff in a no-fly zone"))
        val d = FlightWarnings.displayAt(1_000L)!!
        assertTrue("no-fly zone must not be filtered out", "no-fly zone" in d.text)
        assertTrue(d.red)
    }

    @Test
    fun severalFaultsShowTheWorstAndCountTheRest() {
        // This test used to require every fault on the banner at once,
        // which is what made the banner grow without limit — five lines over the live video on
        // an RC Plus 2, where the intent is one warning plus a "+N".
        //
        // Nothing is dropped: the other faults are carried by the count. The bridge hands this
        // list over worst-first, thus the first entry is the one the pilot most needs.
        FlightWarnings.onDiagnostics(listOf("Compass error", "IMU error"))
        val d = FlightWarnings.displayAt(1_000L)!!
        assertTrue("the worst fault belongs on the banner", "Compass error" in d.text)
        assertTrue("the second fault must be COUNTED, never dropped", "+1" in d.text)
    }

    @Test
    fun aSecondFaultChangesTheCountWithoutHidingTheFirst() {
        FlightWarnings.onDiagnostics(listOf("Compass error"))
        assertEquals("Compass error", FlightWarnings.displayAt(0L)!!.text)
        FlightWarnings.onDiagnostics(listOf("Compass error", "IMU error", "Battery fault"))
        val d = FlightWarnings.displayAt(1_000L)!!
        assertTrue("Compass error" in d.text)
        assertTrue("both of the others must be counted", "+2" in d.text)
    }

    @Test
    fun resetDropsTheFaultListAsWellAsTheText() {
        // The held list is what labelOf reads. If reset cleared only the text, the last
        // session's faults would come back through it.
        FlightWarnings.onDiagnostics(listOf("Compass error", "IMU error"))
        assertNotNull(FlightWarnings.displayAt(0L))
        FlightWarnings.reset()
        assertNull("a reset session must start with no banner", FlightWarnings.displayAt(0L))
    }

    @Test
    fun aClearedFaultHidesTheBannerOnceTheHoldExpires() {
        FlightWarnings.onDiagnostics(listOf("Compass error"))
        assertEquals("Compass error", FlightWarnings.displayAt(0L)!!.text)
        FlightWarnings.onDiagnostics(emptyList())
        // Still held: a fault that appears and clears within the hold must stay readable.
        assertEquals("Compass error", FlightWarnings.displayAt(1_000L)!!.text)
        // Past the hold, nothing is active, so the banner goes.
        assertNull(FlightWarnings.displayAt(9_000L))
    }

    @Test
    fun theHoldKeepsAWarningReadableWhileItFlickers() {
        FlightWarnings.onDiagnostics(listOf("Compass error"))
        assertEquals("Compass error", FlightWarnings.displayAt(0L)!!.text)
        // Flicker: gone and back inside the hold window. The text must not blank in between.
        FlightWarnings.onDiagnostics(emptyList())
        assertEquals("Compass error", FlightWarnings.displayAt(500L)!!.text)
        FlightWarnings.onDiagnostics(listOf("Compass error"))
        assertEquals("Compass error", FlightWarnings.displayAt(1_000L)!!.text)
    }

    @Test
    fun resetClearsEverything() {
        FlightWarnings.onDiagnostics(listOf("Compass error"))
        FlightWarnings.reset()
        assertNull(FlightWarnings.displayAt(1_000L))
    }

    // ---- The debug log line ----

    @Test
    fun theDebugLogShowsWithNoAircraftAndBehindEveryAircraftWarning() {
        // No aircraft at all: the app-side warning still shows.
        FlightWarnings.debugLogOn = true
        val alone = FlightWarnings.displayAt(1_000L)!!
        assertEquals("DEBUG LOG ON", alone.text)
        assertFalse(alone.red)
        // With an aircraft fault it is the one behind, listed last.
        FlightWarnings.onDiagnostics(listOf("Compass error"))
        val both = FlightWarnings.displayAt(1_001L)!!
        assertEquals("Compass error  +1", both.text)
        assertTrue(both.red)
        assertEquals(listOf("Compass error", "DEBUG LOG ON"), both.all)
        // Off: gone at once.
        FlightWarnings.debugLogOn = false
        assertEquals(listOf("Compass error"), FlightWarnings.displayAt(1_002L)!!.all)
    }

    @Test
    fun theDebugLogLineIsTheOneLineThePilotCannotClose() {
        assertEquals(listOf("Compass error"),
            FlightWarnings.dismissable(listOf("Compass error", "DEBUG LOG ON")))
        assertEquals(emptyList<String>(), FlightWarnings.dismissable(listOf("DEBUG LOG ON")))
    }

    // ---- Session mutes (night operations) ----

    @Test
    fun aMutedLineLeavesAtOnceAndDoesNotComeBackWhenItReturns() {
        FlightWarnings.onDiagnostics(listOf("Compass error", "Low ambient light"))
        assertEquals(listOf("Compass error", "Low ambient light"), FlightWarnings.displayAt(0L)!!.all)
        // The pilot closes it. It goes now, not at the next report, and the count follows.
        FlightWarnings.mute(listOf("Low ambient light"))
        assertEquals(listOf("Compass error"), FlightWarnings.displayAt(1L)!!.all)
        assertEquals("Compass error", FlightWarnings.displayAt(2L)!!.text)
        // It clears and returns, the way the vision system reports at night. Still gone.
        FlightWarnings.onDiagnostics(listOf("Compass error"))
        FlightWarnings.onDiagnostics(listOf("Compass error", "Low ambient light"))
        assertEquals(listOf("Compass error"), FlightWarnings.displayAt(3L)!!.all)
        // A line that was NOT muted still arrives.
        FlightWarnings.onDiagnostics(listOf("Compass error", "Low ambient light", "IMU error"))
        assertEquals(listOf("Compass error", "IMU error"), FlightWarnings.displayAt(4L)!!.all)
        // Alone, a muted line is no banner at all once its hold is over.
        FlightWarnings.onDiagnostics(listOf("Low ambient light"))
        assertNull(FlightWarnings.displayAt(60_000L))
    }

    @Test
    fun aMuteIsForTheSessionOnly() {
        FlightWarnings.mute(listOf("Low ambient light"))
        assertEquals(setOf("Low ambient light"), FlightWarnings.mutedLines)
        FlightWarnings.reset()
        assertTrue(FlightWarnings.mutedLines.isEmpty())
        FlightWarnings.onDiagnostics(listOf("Low ambient light"))
        assertEquals("Low ambient light", FlightWarnings.displayAt(0L)!!.text)
    }

    // ---- Limit band ----

    @Test
    fun atLimitFiresInsideTheMarginAndBeyondIt() {
        // 120 m limit -> 5% margin = 6 m, so 114 m and up is "at" it.
        assertTrue(FlightWarnings.atLimit(114.0, 120.0))
        assertTrue(FlightWarnings.atLimit(120.0, 120.0))
        assertTrue(FlightWarnings.atLimit(200.0, 120.0))
        assertFalse(FlightWarnings.atLimit(113.0, 120.0))
    }

    @Test
    fun atLimitUsesAFloorSoASmallCeilingStillHasABand() {
        // 20 m limit: 5% is 1 m, which is under the 5 m floor, so the band is 5 m.
        assertTrue(FlightWarnings.atLimit(15.0, 20.0))
        assertFalse(FlightWarnings.atLimit(14.0, 20.0))
    }

    @Test
    fun anUnsetLimitOrAnUnknownValueNeverWarns() {
        assertFalse(FlightWarnings.atLimit(500.0, null))
        assertFalse(FlightWarnings.atLimit(Double.NaN, 120.0))
        assertFalse(FlightWarnings.atLimit(500.0, 0.0))
    }

    // ---- NOT COVERED HERE, deliberately ----
    //
    // isAttitudeMode() and isPoorGps() encode which DJI enum values mean "unsafe", and they are
    // exactly what one would want pinned. They cannot be: those enums live only in
    // dji-sdk-provided, whose bytecode is a stub (constructors return before calling super), so
    // touching a constant in a JVM test throws VerifyError. See the note in app/build.gradle.
    // Both were checked against the SDK surface with javap when written — LEVEL_0/1/NONE and the
    // five ATTI_* variants are the real constant sets — and both must be confirmed in flight.

    // ---- Priority ----

    @Test
    fun theAircraftsOwnFaultOutranksEverythingElse() {
        val order = FlightWarnings.Warning.values().toList()
        assertEquals(FlightWarnings.Warning.AIRCRAFT_FAULT, order.first())
        // Every red sits above every amber, so an amber can never mask a red.
        val lastRed = order.indexOfLast { it.red }
        val firstAmber = order.indexOfFirst { !it.red }
        assertTrue("reds must all precede ambers", lastRed < firstAmber)
        // The app-side line is the least of all: never ahead of anything the aircraft says.
        assertEquals(FlightWarnings.Warning.DEBUG_LOG, order.last())
    }
}
