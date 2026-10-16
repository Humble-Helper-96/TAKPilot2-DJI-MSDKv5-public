package com.taklite.client.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the CoT XML that goes to the TAK server.
 *
 * Not schema validation — these hold the fields ATAK and CloudTAK actually read, so a refactor
 * cannot silently drop one. Everything asserted here is visible on somebody else's screen.
 */
class CotBuilderTest {

    @Test
    fun pilotPliCarriesIdentityPositionAndTakv() {
        val xml = CotBuilder.buildPLI(
            "PILOT-1", "UAS-BRAVO-Pilot", "Cyan", "Team Member",
            45.1, -100.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot2", "SmartController", "Android", "1.5.9")
        assertTrue("uid=\"PILOT-1\"" in xml)
        assertTrue("callsign=\"UAS-BRAVO-Pilot\"" in xml)
        assertTrue("lat=\"45.1\"" in xml)
        assertTrue("lon=\"-100.9\"" in xml)
        assertTrue("a-f-G-U-C" in xml)          // PLI type
        assertTrue("<takv" in xml)
        assertTrue("<status battery=\"77\" />" in xml)
    }

    /**
     * The BUILDER still advertises a video url on a pilot marker when it is given one. This
     * pins the builder, not the application's policy.
     *
     * ⚠ **NO APPLICATION PASSES ONE ANY MORE (design decision).** The aircraft marker
     * alone advertises the stream: TAK Aware draws a team member carrying a video entry as a
     * MIL-STD-2525 square instead of the team-member dot, and the dot was chosen. See
     * `DroneTakBridge.sendPilotPli`. The parameter stays on the builder because the shared core
     * is vendor- and policy-neutral; the call site is where the decision lives. A note here
     * must describe the CURRENT policy: one that explains why the old behaviour was right is
     * how the old behaviour comes back.
     */
    @Test
    fun aVideoUrlIsAdvertisedOnThePilotMarkerWhenGiven() {
        val withVideo = CotBuilder.buildPLI(
            "PILOT-1", "UAS-BRAVO-Pilot", "Cyan", "Team Member",
            45.1, -100.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot2", "SmartController", "Android", "1.5.9",
            "rtsp://server:8554/evo2")
        assertTrue("<__video" in withVideo)
        assertTrue("rtsp://server:8554/evo2" in withVideo)

        // The no-video overload must not emit an empty element for a stream that is not running.
        val without = CotBuilder.buildPLI(
            "PILOT-1", "UAS-BRAVO-Pilot", "Cyan", "Team Member",
            45.1, -100.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot2", "SmartController", "Android", "1.5.9")
        assertFalse("<__video" in without)
    }

    @Test
    fun dronePliCarriesTrackBatteryAndSensor() {
        val xml = droneXml()
        assertTrue("uid=\"UID-DRONE\"" in xml)
        assertTrue("lat=\"45.2\"" in xml)
        assertTrue("lon=\"-100.8\"" in xml)
        // Exact, not a family of maybes: this is the field a teammate reads off the aircraft.
        assertTrue("<status battery=\"66\" />" in xml)
        assertTrue("<sensor" in xml)
    }

    /**
     * AIRFRAME IDENTITY ON THE WIRE. These three strings tell every other client what aircraft
     * this is, and receiving clients may match on them to decide how to draw it.
     *
     * A build ported from one airframe to another carries the old strings with it unless
     * something pins them, and then the aircraft tells the whole channel it is a different
     * model. Pinned so they cannot drift, and so the mistake is caught here rather than in the
     * field.
     */
    @Test
    fun theDronePliIdentifiesThisAirframeAndNotAnother() {
        // The stale strings asserted against below are the two this constant has actually
        // carried by mistake — M30T, then MINI2 — on a Matrice 4TD.
        val xml = droneXml()
        assertTrue("model=\"M4TD\"" in xml)
        assertTrue("typeTag=\"_DJIV5_\"" in xml)
        assertTrue("type=\"DJIV5\"" in xml)
        assertFalse("a stale airframe string must never reappear", "MINI2" in xml)
        assertFalse("a stale airframe string must never reappear", "M30T" in xml)
        assertFalse("another airframe family's tag must never appear", "AUTEL" in xml)
    }

    @Test
    fun theDroneIsAnAirTrackNotAGroundContact() {
        // Air domain — position 3 of the type. This is what makes other clients draw it as an
        // aircraft, and what CotParser keys the retention rules off.
        assertTrue("a-f-A-" in droneXml())
    }

    /**
     * A flight once shipped the video url on the wire and NO client offered to play it: the
     * element was `<__video sensor url/>` with no uid and no ConnectionEntry.
     * CloudTAK's CoT library makes ConnectionEntry's uid and address mandatory, so there was
     * nothing to build a player from. These assertions are the regression.
     */
    @Test
    fun videoAdvertisementCarriesAConnectionEntry() {
        val url = "rtsp://tak:pw@stream.example.com:8554/Feed-B-Low?tcp"
        val xml = CotBuilder.buildPLI(
            "PILOT-1", "UAS-BRAVO-Pilot", "Cyan", "Team Member",
            45.1, -100.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot2", "SmartController", "Android", "1.5.9", url)

        assertTrue("<ConnectionEntry" in xml)
        assertTrue("address=\"stream.example.com\"" in xml)
        assertTrue("port=\"8554\"" in xml)
        assertTrue("path=\"/Feed-B-Low\"" in xml)
        assertTrue("protocol=\"rtsp\"" in xml)
        assertTrue("alias=\"UAS-BRAVO-Pilot\"" in xml)
        // Both uids present and equal — a client keys its video entry on this.
        val uid = CotBuilder.videoUidFor(url)
        assertTrue("<__video uid=\"$uid\"" in xml)
        assertTrue("<ConnectionEntry uid=\"$uid\"" in xml)
        // The full url, credentials and all, still rides the element for clients that read it.
        assertTrue("url=\"rtsp://tak:pw@stream.example.com:8554/Feed-B-Low?tcp\"" in xml)
    }

    /** One stream, one uid: the aircraft and the operator must not advertise it as two feeds. */
    @Test
    fun aircraftAndOperatorAdvertiseTheSameVideoUid() {
        val url = "rtsp://host:8554/Feed-A"
        val pilot = CotBuilder.buildPLI(
            "PILOT-1", "UAS-BRAVO-Pilot", "Cyan", "Team Member",
            45.1, -100.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot2", "SmartController", "Android", "1.5.9", url)
        val drone = CotBuilder.buildDronePLI(
            "UID-DRONE", "UAS-BRAVO",
            45.2, -100.8, 100.0, 250.0, 7.5, 66,
            url, "UID-DRONE-SPI",
            65.8, 39.9, 250.0, -10.0, 300.0, 0.0,
            0.0, -10.0, 250.0,
            true, 300,
            7100, 4686, 15.9,
            "PILOT-1")
        val uid = CotBuilder.videoUidFor(url)
        assertTrue("<__video uid=\"$uid\"" in pilot)
        assertTrue("<__video uid=\"$uid\"" in drone)
    }

    /**
     * ⚠ A per-session token in the path (`-<8 lowercase hex>-Low`) is NOT part of the uid. An
     * application that randomizes its path gets a new token at every launch; a uid that followed
     * it would give every client a new video alias per flight. The url and the ConnectionEntry
     * path keep the token — they are what a client plays.
     */
    @Test
    fun aPathTokenDoesNotChangeTheVideoUid() {
        val plain = "rtsp://tak:pw@stream.example.com:8554/Feed-B-Low?tcp"
        val tokened = "rtsp://tak:pw@stream.example.com:8554/Feed-B-7f3a9c2d-Low?tcp"
        val other = "rtsp://tak:pw@stream.example.com:8554/Feed-B-0badcafe-Low?tcp"
        val uid = CotBuilder.videoUidFor(plain)
        assertTrue(uid == CotBuilder.videoUidFor(tokened))
        assertTrue(uid == CotBuilder.videoUidFor(other))
        // The same holds for a path with no query, and a different feed is still different.
        assertTrue(CotBuilder.videoUidFor("rtsp://h/Feed-B-Low") ==
            CotBuilder.videoUidFor("rtsp://h/Feed-B-7f3a9c2d-Low"))
        assertFalse(uid == CotBuilder.videoUidFor("rtsp://tak:pw@stream.example.com:8554/Feed-C-Low?tcp"))

        val xml = CotBuilder.buildPLI(
            "PILOT-1", "UAS-BRAVO-Pilot", "Cyan", "Team Member",
            45.1, -100.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot2", "SmartController", "Android", "1.5.9", tokened)
        assertTrue("<__video uid=\"$uid\"" in xml)
        assertTrue("<ConnectionEntry uid=\"$uid\"" in xml)
        assertTrue("path=\"/Feed-B-7f3a9c2d-Low\"" in xml)
        assertTrue("url=\"$tokened\"" in xml)
    }

    /** No url, no element — an absent feed must not advertise an empty one. */
    @Test
    fun noVideoUrlMeansNoVideoElement() {
        assertFalse("__video" in droneXml())
    }

    private fun droneXml(): String = CotBuilder.buildDronePLI(
        "UID-DRONE", "UAS-BRAVO",
        45.2, -100.8, 100.0, 250.0, 7.5, 66,
        null, "UID-DRONE-SPI",
        65.8, 39.9, 250.0, -10.0, 300.0, 0.0,
        0.0, -10.0, 250.0,
        true, 300,
        7100, 4686, 15.9,
        "PILOT-1")

    // ---- Marker type fidelity ----

    /**
     * The four affiliations this application places must still map exactly as they did. This is
     * the regression guard for the refactor that put a type-preserving builder underneath
     * buildMarker: the affiliation path is now a delegation, and a wrong table here would
     * change the icon every teammate sees.
     */
    @Test
    fun affiliationsStillMapToTheirBareGroundTypes() {
        val cases = mapOf(
            "Friendly" to "a-f-G", "Hostile" to "a-h-G",
            "Neutral" to "a-n-G", "Unknown" to "a-u-G")
        for ((aff, type) in cases) {
            val xml = CotBuilder.buildMarker(
                "PILOT-1", "UAS-BRAVO", "marker-1", aff, 45.1, -100.9, 30.0, "M-01", "", null)
            assertTrue("$aff should send $type", "type=\"$type\"" in xml)
        }
        // An unrecognised affiliation falls back to friendly rather than emitting nothing.
        val odd = CotBuilder.buildMarker(
            "PILOT-1", "UAS-BRAVO", "marker-1", "Banana", 45.1, -100.9, 30.0, "M-01", "", null)
        assertTrue("type=\"a-f-G\"" in odd)
    }

    /**
     * A marker RE-BROADCAST from the team keeps its own CoT type.
     *
     * b-m-p-w-GOTO is the case that made this necessary: it is an ATAK marker point, it is
     * accepted into the shared store, and it has no affiliation to derive a type from. Sent
     * through the affiliation path it would have gone out as a friendly ground marker on every
     * screen in the team.
     */
    @Test
    fun aResentMarkerKeepsItsOwnCotType() {
        for (type in listOf("b-m-p-w-GOTO", "a-h-G", "b-m-p-s-m")) {
            val xml = CotBuilder.buildMarkerWithType(
                "PILOT-1", "UAS-BRAVO", "shared-uid-9", type,
                45.1, -100.9, 30.0, "Team marker", "", null, type)
            assertTrue("$type must survive", "type=\"$type\"" in xml)
            assertTrue("uid must be reused", "uid=\"shared-uid-9\"" in xml)
            assertFalse("must not fall back to friendly", "type=\"a-f-G\"" in xml)
        }
    }

    /** The uid is the marker's identity: a re-send must not mint a new one. */
    @Test
    fun cotTypeForAffiliationIsTheSameTableTheBuilderUses() {
        assertTrue(CotBuilder.cotTypeForAffiliation("hostile") == "a-h-G")
        assertTrue(CotBuilder.cotTypeForAffiliation("HOSTILE") == "a-h-G")
        assertTrue(CotBuilder.cotTypeForAffiliation(null) == "a-f-G")
    }
}
