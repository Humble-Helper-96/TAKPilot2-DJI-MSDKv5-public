package com.taklite.client.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the `<takv>` client identity — what a TAK server's connected-users panel shows for
 * this app.
 *
 * ⚠ **THE BUG THIS EXISTS FOR WAS AN UNCALLED METHOD, NOT A WRONG VALUE.** Every constant was
 * correct; [TakManager.setClientIdentity] was simply never called, so every installation
 * reported itself as the shared core's placeholder `TAK Lite 1.0` with
 * `Unknown Device (<callsign>)`. Found on the server months after the placeholders were
 * written.
 *
 * A test over the constants cannot catch that — they were never wrong. The only thing that
 * can is a check that the CALL EXISTS, which is why this reads the Application source. It is
 * blunt on purpose: the alternative is Robolectric for one assertion, and this file has five
 * prior uncalled-initialiser incidents behind it (`AppLog`, `FlightPathLogger`,
 * `TakDropMarkers`, `TakMissionManager`, `UasfmIndex`) to justify a blunt guard.
 */
class ClientIdentityTest {

    /** Walk up from the test's working directory so this does not care where Gradle runs it. */
    private fun appSource(relative: String): File {
        var dir = File(".").absoluteFile
        repeat(5) {
            val f = File(dir, "app/src/main/java/$relative")
            if (f.isFile) return f
            dir = dir.parentFile ?: return@repeat
        }
        error("could not locate app/src/main/java/$relative from ${File(".").absolutePath}")
    }

    private val application by lazy {
        appSource("com/dji/sdk/sample/takpilot2/TAKPilot2Application.kt").readText()
    }

    /** THE regression. Without this call nothing else in this file matters. */
    @Test
    fun `the Application sets the client identity at startup`() {
        assertTrue(
            "TAKPilot2Application must call TakManager.setClientIdentity() — without it the " +
                "whole fleet reports as the shared core's placeholder. See its doc.",
            "setClientIdentity(" in application)
    }

    /** The version must come from the build, or it drifts the moment someone forgets. */
    @Test
    fun `the reported version comes from BuildConfig and is not a literal`() {
        val call = application.substringAfter("setClientIdentity(").substringBefore(")")
        assertTrue("the identity must report BuildConfig.VERSION_NAME, not a hardcoded string",
            "BuildConfig.VERSION_NAME" in call)
    }

    /** Naming another app's platform here would make two different apps indistinguishable in
     *  the server's list, which is the problem this whole mechanism exists to solve. */
    @Test
    fun `the platform names this app and not another`() {
        val call = application.substringAfter("setClientIdentity(").substringBefore(")")
        assertTrue("the platform must identify this app", "TAKPilot2-DJIv5" in call)
        assertFalse("that is another app's identity", "TAKPilot2-Other" in call)
    }

    /** ⚠ The placeholders are the shared core's, deliberately generic because TakManager is
     *  vendor-neutral shared code. Shipping one means the call was lost again. */
    @Test
    fun `no placeholder from the shared core is shipped as this app's identity`() {
        val call = application.substringAfter("setClientIdentity(").substringBefore(")")
        for (placeholder in listOf("\"TAK Lite\"", "\"Unknown Device\"", "\"1.0\"")) {
            assertFalse("$placeholder is TakManager's placeholder, not an identity",
                placeholder in call)
        }
    }

    /** The mechanism itself: what is set is what reaches the wire. Pins the plumbing between
     *  the Application's call and the `<takv>` attributes, independently of the call existing. */
    @Test
    fun `the identity given reaches the takv block verbatim`() {
        val xml = CotBuilder.buildPLI(
            "UID-1", "UAS-ALPHA-Pilot", "Cyan", "Team Member", true,
            45.3, -100.5, 100.0, 5.0, 0.0, 0.0, 80,
            "TAKPilot2-DJIv5", "DJI RC PLUS 2 (UAS-ALPHA)", "Android 13", "2.4.0", null)
        assertTrue("""platform="TAKPilot2-DJIv5"""" in xml)
        assertTrue("""version="2.4.0"""" in xml)
        assertTrue("""device="DJI RC PLUS 2 (UAS-ALPHA)"""" in xml)
        assertTrue("""os="Android 13"""" in xml)
        assertFalse("TAK Lite" in xml)
    }
}
