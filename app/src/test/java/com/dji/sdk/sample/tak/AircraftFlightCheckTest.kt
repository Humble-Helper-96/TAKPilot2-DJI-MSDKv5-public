package com.dji.sdk.sample.tak

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Pins [AircraftFlightCheck]'s decision table and its answer-once rule. This object decides
 * whether an irreversible action may run — a mode change that closes the Elevated socket, or a
 * card format — so every row is written out.
 *
 * The SDK and the timer are fakes: a read is answered when the test says so, and a timeout fires
 * when the test fires it.
 */
class AircraftFlightCheckTest {

    private class FakeSdk : AircraftFlightCheck.Sdk {
        var linked = true
        val flyingReads = mutableListOf<(Boolean?) -> Unit>()
        val connectionReads = mutableListOf<(Boolean?) -> Unit>()
        override fun isLinked() = linked
        override fun readIsFlying(then: (Boolean?) -> Unit) { flyingReads += then }
        override fun readConnection(then: (Boolean?) -> Unit) { connectionReads += then }
    }

    private val sdk = FakeSdk()
    private val timers = mutableListOf<() -> Unit>()
    private val answers = mutableListOf<Boolean?>()

    private val origSdk = AircraftFlightCheck.sdk
    private val origSchedule = AircraftFlightCheck.schedule

    @Before fun install() {
        AircraftFlightCheck.sdk = sdk
        AircraftFlightCheck.schedule = { _, r -> timers += r }
        AircraftFlightCheck.lastKnownFlying = null
    }

    @After fun restore() {
        AircraftFlightCheck.sdk = origSdk
        AircraftFlightCheck.schedule = origSchedule
        AircraftFlightCheck.lastKnownFlying = null
    }

    private fun ask() = AircraftFlightCheck.ask { answers += it }

    // ---- Not linked ----

    /** ⚠ THE CURRENT RULE, AND IT IS OPEN: unknown since boot reads as grounded.
     *  If flight testing decides otherwise, this test is the one that changes. */
    @Test
    fun `unlinked with nothing known since boot proceeds`() {
        sdk.linked = false
        ask()
        assertEquals(listOf<Boolean?>(false), answers)
    }

    @Test
    fun `unlinked after the aircraft last said flying refuses`() {
        sdk.linked = false
        AircraftFlightCheck.noteFlying(true)
        ask()
        assertEquals(listOf<Boolean?>(null), answers)
    }

    @Test
    fun `unlinked after the aircraft last said grounded proceeds`() {
        sdk.linked = false
        AircraftFlightCheck.noteFlying(false)
        ask()
        assertEquals(listOf<Boolean?>(false), answers)
    }

    // ---- Linked: the flying key answers ----

    @Test
    fun `the aircraft says flying - refuse, and remember it`() {
        ask()
        sdk.flyingReads.single()(true)
        assertEquals(listOf<Boolean?>(true), answers)
        assertEquals(true, AircraftFlightCheck.lastKnownFlying)
    }

    @Test
    fun `the aircraft says grounded - proceed`() {
        ask()
        sdk.flyingReads.single()(false)
        assertEquals(listOf<Boolean?>(false), answers)
    }

    // ---- Linked: the flying key fails, the connection key decides ----

    @Test
    fun `flying key fails and the flight controller is not connected - the unlinked rule`() {
        ask()
        sdk.flyingReads.single()(null)
        sdk.connectionReads.single()(false)
        assertEquals(listOf<Boolean?>(false), answers)
    }

    @Test
    fun `flying key fails, not connected, but last said flying - refuse`() {
        AircraftFlightCheck.noteFlying(true)
        ask()
        sdk.flyingReads.single()(null)
        sdk.connectionReads.single()(false)
        assertEquals(listOf<Boolean?>(null), answers)
    }

    /** ⚠ Only a DEFINITE "not connected" is the unlinked case. Connected, or no answer, is unknown. */
    @Test
    fun `flying key fails and the connection key says connected or nothing - refuse`() {
        ask()
        sdk.flyingReads.single()(null)
        sdk.connectionReads.single()(true)
        ask()
        sdk.flyingReads[1](null)
        sdk.connectionReads[1](null)
        assertEquals(listOf<Boolean?>(null, null), answers)
    }

    // ---- Budgets ----

    /** A flying key that never answers must still let the connection key ask. */
    @Test
    fun `a silent flying key falls back to the connection key with a budget of its own`() {
        ask()
        timers.removeAt(0)()                     // the flying key's budget runs out
        assertEquals(emptyList<Boolean?>(), answers)
        sdk.connectionReads.single()(false)
        assertEquals(listOf<Boolean?>(false), answers)
    }

    @Test
    fun `both keys silent - refuse`() {
        ask()
        timers.removeAt(0)()
        timers.removeAt(0)()
        assertEquals(listOf<Boolean?>(null), answers)
    }

    @Test
    fun `the fallback starts once, whether the failure or the timeout comes first`() {
        ask()
        sdk.flyingReads.single()(null)
        timers.removeAt(0)()
        assertEquals(1, sdk.connectionReads.size)
    }

    /** A real report during the fallback is still the aircraft's answer. */
    @Test
    fun `a late flying report during the fallback is taken`() {
        ask()
        timers.removeAt(0)()
        sdk.flyingReads.single()(true)
        assertEquals(listOf<Boolean?>(true), answers)
    }

    // ---- Answer once ----

    /** ⚠ A late "grounded" must never act after a refusal was given (an answer is given once). */
    @Test
    fun `a reply after the answer is ignored`() {
        ask()
        timers.removeAt(0)()
        timers.removeAt(0)()                     // refused: null
        sdk.flyingReads.single()(false)
        sdk.connectionReads.single()(false)
        assertEquals(listOf<Boolean?>(null), answers)
    }

    @Test
    fun `a callback that fires twice answers once`() {
        ask()
        sdk.flyingReads.single()(false)
        sdk.flyingReads.single()(true)
        assertEquals(listOf<Boolean?>(false), answers)
        assertNull(timers.getOrNull(1))         // no fallback was started
    }
}
