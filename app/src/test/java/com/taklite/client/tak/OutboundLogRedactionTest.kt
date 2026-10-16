package com.taklite.client.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the redaction applied to every CoT this application logs on its way out.
 *
 * THIS IS A SECURITY TEST, not a formatting one. The pilot PLI carries the video url and that
 * url carries the media-server password. This application does not write a credential to the
 * log or to logcat, and the outbound CoT log would have undone that on its own. If these tests
 * fail, the log is leaking a password.
 */
class OutboundLogRedactionTest {

    @Test
    fun credentialsInAVideoUrlAreMasked() {
        val out = TakManager.redactCredentials(
            "rtsp://tak:hunter2@stream.example.com:8554/UAS-BRAVO-Low?tcp")
        assertEquals("rtsp://<user>:<pass>@stream.example.com:8554/UAS-BRAVO-Low?tcp", out)
        assertFalse("hunter2" in out)
        assertFalse("tak:hunter2" in out)
    }

    /** The real shape: a whole PLI with the url inside an XML attribute. */
    @Test
    fun credentialsAreMaskedInsideAFullCotEvent() {
        val xml = """<event version="2.0" type="a-f-G-U-C" uid="PILOT-1"><detail>""" +
            """<__video uid="v1" sensor="UAS-BRAVO-Pilot" """ +
            """url="rtsp://tak:hunter2@stream.example.com:8554/UAS-BRAVO-Low?tcp">""" +
            """<ConnectionEntry address="stream.example.com" port="8554"/></__video>""" +
            """</detail></event>"""
        val out = TakManager.redactCredentials(xml)
        assertFalse("the password must not survive", "hunter2" in out)
        assertTrue("the rest of the event must be intact", "uid=\"PILOT-1\"" in out)
        assertTrue("the host must stay readable", "stream.example.com" in out)
        assertTrue("<ConnectionEntry" in out)
    }

    /** A url with no credentials must come through untouched — over-redacting would make the
     *  log useless for the thing it was added to diagnose. */
    @Test
    fun aUrlWithoutCredentialsIsUnchanged() {
        val plain = "rtsp://stream.example.com:8554/UAS-BRAVO-Low?tcp"
        assertEquals(plain, TakManager.redactCredentials(plain))
        val marker = """<event type="a-u-G" uid="marker-cc742cef"><point lat="45.3" lon="-100.5"/></event>"""
        assertEquals(marker, TakManager.redactCredentials(marker))
    }

    @Test
    fun nullIsSafe() {
        assertEquals(null, TakManager.redactCredentials(null))
    }
}
