package com.dji.sdk.sample.takpilot2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pilot's custom tile URL reaches the style JSON, and both failure modes it had ended
 * as a blank map with no explanation.
 */
class MaplibreStyleTest {

    private val good = "https://tiles.example.com/{z}/{x}/{y}.png"

    @Test
    fun anOrdinaryUrlIsAccepted() {
        assertNull(MaplibreStyle.validateCustomUrl(good))
        assertNull(MaplibreStyle.validateCustomUrl("  $good  "))
    }

    @Test
    fun cleartextIsRejectedWithAReasonThePilotCanAct_on() {
        val problem = MaplibreStyle.validateCustomUrl("http://tiles.example.com/{z}/{x}/{y}.png")
        assertNotNull(problem)
        // The message has to name https, since "invalid URL" would leave the pilot guessing at
        // the one thing that is wrong. targetSdk 35 blocks cleartext, so this can never work.
        assertTrue(problem!!, problem.contains("https"))
    }

    @Test
    fun aUrlWithoutXyzPlaceholdersIsRejected() {
        assertNotNull(MaplibreStyle.validateCustomUrl("https://tiles.example.com/map.png"))
        assertNotNull(MaplibreStyle.validateCustomUrl("https://tiles.example.com/{z}/{x}.png"))
        assertNull(MaplibreStyle.validateCustomUrl("https://tiles.example.com/{Z}/{X}/{Y}.png"))
    }

    @Test
    fun emptyAndNonHttpsAreRejected() {
        assertNotNull(MaplibreStyle.validateCustomUrl(""))
        assertNotNull(MaplibreStyle.validateCustomUrl("   "))
        assertNotNull(MaplibreStyle.validateCustomUrl("ftp://tiles.example.com/{z}/{x}/{y}.png"))
    }

    /**
     * The defensive half. Validation runs at save time, but a URL saved by an older build is
     * still out there, so `custom()` must not be able to emit broken JSON whatever it is handed
     * — a malformed style makes MapLibre drop the whole thing and the map goes black.
     *
     * Asserted on the emitted text rather than by parsing: `org.json` is the stubbed android.jar
     * in this module's unit tests (returnDefaultValues), so JSONObject answers null here. Same
     * approach CotBuilderTest takes for XML.
     */
    @Test
    fun aQuoteInTheUrlCannotBreakOutOfTheStringValue() {
        val hostile = """https://x.example.com/{z}/{x}/{y}.png","evil":"injected"""
        val json = MaplibreStyle.custom(hostile)
        // The break-out sequence must not survive as literal JSON syntax...
        assertFalse("quote escaped out of the string value", json.contains(""".png","evil""""))
        // ...it must appear escaped instead.
        assertTrue("quote was not escaped", json.contains("""\"evil\""""))
        // And "evil" must never become a key: every quote around it is escaped, so the only
        // occurrences are inside the tile string.
        assertFalse(json.contains("\"evil\":"))
    }

    @Test
    fun backslashesAndControlCharactersAreEscaped() {
        // A literal backslash must be doubled, not passed through to break the next character.
        assertTrue(MaplibreStyle.custom("""https://x/a\b/{z}/{x}/{y}.png""")
            .contains("""a\\b"""))
        // Raw newlines/tabs are not legal inside a JSON string and must come out as escapes.
        val nl = MaplibreStyle.custom("https://x/{z}/{x}/{y}.png\n")
        assertTrue("raw newline left in the tile string", nl.contains("""png\n""""))
        val tab = MaplibreStyle.custom("https://x/\t{z}/{x}/{y}.png")
        assertTrue("raw tab left in the tile string", tab.contains("""x/\t{z}"""))
    }
}
