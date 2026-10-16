package com.dji.sdk.sample.takpilot2

import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dji.sdk.sample.R
import com.taklite.util.AppLog

/**
 * Pilot field guide — a read-in-the-field explanation of what every control does.
 *
 * **Audience is the pilot, not a developer.** No class names, no file paths, no SDK talk. Where
 * a limitation matters to a flight decision it IS stated plainly (what the FAA layer can't tell
 * you, when the altitude readout is approximate, what a local marker delete does and doesn't
 * do), because a guide that only lists happy paths is the kind that gets someone in trouble.
 *
 * **Every pilot-facing string in this file is written in ASD-STE100 Simplified Technical
 * English. Keep it that way when you edit.** STE is the aerospace controlled-language standard
 * (approved dictionary + 53 writing rules); it exists so that a reader who is tired, rushed, or
 * reading in a second language cannot mis-parse a safety-relevant instruction. The rules this
 * text is held to:
 *  - **One word, one meaning, one part of speech.** A short screen press is always "touch",
 *    never tap/press/hit. A long press is always "touch and hold". The airframe is always the
 *    "aircraft", never the drone. A marker is always a "marker", never a pin.
 *  - **Approved vocabulary.** "make sure" not ensure, "about" not approximately, "use" not
 *    utilize, "let" not allow, "get" not obtain, "but" not however, "because of" not due to.
 *  - **Active voice, simple tenses, no -ing forms.** "The app sends the marker", not "the
 *    marker is sent" or "sending the marker".
 *  - **Sentence length**: 20 words max for an instruction, 25 for description. Six sentences
 *    max per paragraph, one topic each.
 *  - **Conditions come first**: "If the signal is weak, select Low" — never the reverse.
 *  - **No idiom, metaphor, or humour.** They are the first thing to fail a tired reader and the
 *    first thing to fail a translator.
 *  - **Warnings open with the command**, then the reason.
 *
 * The one deliberate exception: **on-screen control labels are quoted verbatim** even when they
 * are not STE ("Drop Marker at Crosshair", "Enroll & Connect"). STE treats these as technical
 * names, and a guide that renames the button a pilot is hunting for is worse than useless.
 *
 * **The icon examples are live views, not pictures.** Each one is the real toolbar widget —
 * [BatteryGaugeView], [SignalBarsView], [LiveToggleView], [RecordToggleView], the TAK badge with
 * its status dot — constructed here and driven into the state being described. Screenshots or
 * hand-drawn copies would silently go stale the next time an icon changes; these can't, because
 * they ARE the icons. It also means a state shown here is genuinely reachable in the app.
 *
 * ## Length
 *
 * The rule: **keep every fact that changes a flight decision, delete the explanation of why.**
 * A pilot needs "land when the ring is yellow", not a paragraph on how the gauge is computed.
 * A guide long enough that a pilot does not read it is worse than a short one that omits
 * something. Say what the control is and what to do, and delete the explanation of the
 * consequence.
 *
 * ## ⚠ WHAT MAKES A GUIDE LOOK LONG IS BOXES, NOT WORDS
 *
 * Cutting words alone does not make a guide read shorter; the word count is the wrong
 * measurement. Every `warn` and `note`, and every string in an `entry`'s `caveats`, renders as
 * a boxed blockquote, and a guide with nineteen of them read as a wall of alarms and looked
 * twice its length, whatever it weighed. Sixteen of those boxes became ordinary prose with
 * **no sentence deleted**: every safety fact still reads, in the same words, in the paragraph
 * where it belongs.
 *
 * The boxes left are the calibration note, "the AR view is not accurate for a point", and the
 * facts the aircraft itself taught us: the spotlight disabling obstacle sensing, and a message
 * continuing when the panel is closed.
 *
 * **Keep it that way.** A new `warn` or `note` here is another box, and the reason to reach
 * for one is almost always that the sentence matters — which is true of most sentences in
 * this file. Write it as prose. A box that is used nineteen times has stopped being a box.
 *
 * There is deliberately no "What this build cannot do" section: the camera-angle limit rides
 * with the crosshair entry, where a pilot meets the same fact in context, and a guide that
 * lists what a build does not have grows without limit.
 *
 * ⚠ **ANY FURTHER SHORTENING IS CONTENT, NOT PROSE.** It would mean deleting documentation
 * for controls THIS app has: the warnings banner, the obstacle distances, the aircraft-data
 * block, the RTH/HOME lines, the resource bar, the exposure slider, the clock, the map display
 * and the video re-sync. That is a product decision, not an editing pass.
 *
 * What must not be trimmed, because each one changes what a pilot does: the battery-refusal
 * warning, the crosshair angle and error table, the marker refusal conditions, what a marker
 * delete does NOT do, the certificate rule on channels, the FAA "not an approval" warning, the
 * obstacle "no mark is not clear" warning, and the spotlight disabling the obstacle sensors.
 *
 * ## The controller buttons
 *
 * The L1/L2/L3 mapping is documented in the entry below. When a hardware mapping changes,
 * that entry is the one place to change it, and `TAKPilot2GoFlightActivity.onKeyDown` is the
 * ground truth to check it against.
 *
 * The aim calibration is covered on the AR entry, with the field procedure in section 4 —
 * expand it only if a pilot actually gets lost.
 *
 * The in-flight channel picker (touch and hold on the TAK badge) is documented in BOTH places
 * it can be reached from — section 2 with the rest of the channel rules, and on the TAK
 * connection entry in section 3, where a pilot looking at the badge will find it.
 *
 * ## NO AIRFRAME IS NAMED IN THIS FILE, and it must stay that way
 *
 * Text written for a different airframe names that airframe throughout. **An airframe this
 * SDK does not support must not be named here**: every such sentence would be false, and some
 * were dangerous rather than merely stale, which is why they were removed. When text is next
 * brought in from elsewhere, take the facts and drop the airframe. The same rule applies to
 * "phone": this build runs on a smart controller.
 */
/*
 * SIZES SCALED TO THIS PANEL: title 18, section 15, sub and entry titles 12, body and lede 12,
 * caveats and entry bodies 11, captions 11. The same three-quarters of the phone sizes as
 * every other screen, floored at 11sp. The handout (tools/generate_field_guide_md.py) carries
 * no sizes and is unaffected.
 */
class FieldGuideActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_field_guide)
        AppLog.v(TAG, "field guide opened")
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }
        content = findViewById(R.id.fieldGuideContent)

        title("TAKPilot2 Field Guide")
        lede("What each control does, on the screen and in Pre-Flight Setup. Read it before " +
            "you fly. This is the build for the smart controller.")
        // ⚠ NO TEMPORARY "THIS BUILD HAS NOT FLOWN" WARNING HERE. One stood in this spot once,
        // with a comment asking a human to delete it after the first flight, and it outlived
        // several released versions that had all flown.
        //
        // A FALSE SAFETY NOTICE COSTS MORE THAN A MISSING ONE: it is the first thing in the
        // guide, and a pilot who learns that the loudest warning is untrue has been taught to
        // discount the rest. If a temporary warning is ever added here again, tie it to
        // something that CANNOT be forgotten rather than to a comment asking a human to remember.

        sectionOne()
        sectionTwo()
        sectionThree()
        sectionAimOffsets()
        sectionFour()

        divider()
        body("If this guide does not agree with the aircraft, obey the aircraft. Then tell " +
            "the person who maintains the app.")
        spacer(24)

        scrollToAnchor(intent?.getStringExtra(EXTRA_SCROLL_TO))
    }

    // ---------------------------------------------------------------- Section 1

    private fun sectionOne() {
        section("1. What this app is for")
        body("TAKPilot2 flies your DJI aircraft and puts it on the shared TAK map of your " +
            "team.")

        bullet("Your team sees the position, heading and altitude of the aircraft, and the " +
            "point on the ground where the camera looks.")
        bullet("You put markers on what you see. Your team gets them in a few seconds.")
        bullet("The quick marker is one marker. Each new touch moves it.")
        bullet("The app can send live video to a server of your team.")

        note("Do the firmware updates, the calibrations and the aircraft registration in the " +
            "DJI app first. Not here.")

        body("A red TAK icon means your team cannot see the aircraft or your markers. The " +
            "aircraft still flies correctly.")
    }

    // ---------------------------------------------------------------- Section 2

    private fun sectionTwo() {
        section("2. Pre-Flight Setup")
        body("Set these on the ground prior to flight. Change them for a new area, a new " +
            "server or a new task.")

        sub("1. Aircraft Settings")
        body("Max altitude, max distance and RTH altitude, in feet. The app sends them at each " +
            "connection. An empty field keeps what the aircraft holds. At the max distance the " +
            "aircraft stops and holds. It does not come home by itself.")
        body("Set the RTH altitude below the Max altitude, and above the highest obstacle " +
            "between you and the aircraft.")

        body("Control response sets the speed of the camera controls. Stick mode sets what the " +
            "sticks do; Mode 2 is usual, and it is sent only on Apply.")

        body("If the signal is lost sets what the aircraft does alone. Select Return Home. It " +
            "applies also if the app stops in flight.")

        body("Obstacle avoidance is shown here, not set here. The line reports what the " +
            "AIRCRAFT holds. To change it, use DJI Pilot 2 on this controller — the settings " +
            "live in the aircraft and this app reads them.")
        body("\"Not available\" does not mean off. It means the aircraft did not answer, so " +
            "the app does not know. Treat it as unknown and check in DJI Pilot 2.")

        body("Battery Warning and Battery Critical are where the aircraft warns you and lands.")
        body("Some aircraft keep their own battery levels and refuse a change. If yours " +
            "refuses, the fields become read-only and show the levels the aircraft holds. " +
            "Plan for those.")

        body("Apply sends all of these now, then shows what the aircraft holds — not what " +
            "you typed.")

        sub("2. Video Streaming")
        body("Optional. Type the address of the video server of your team, the port, the " +
            "broadcast ID and the login. Select the quality: Standard, or Low if the " +
            "connection is weak. You can change the quality in flight.")

        body("Touch Configure Video Servers to set the address, the login and the codec. " +
            "Select the codec H.264: more clients can show it.")
        if (com.dji.sdk.sample.tak.AppMode.secondVideoServerAllowed(modePrefs())) {
            body("Elevated mode only. Active server selects between two servers, and each one " +
                "has its own address, login and codec.")
        }
        body("The video has two protocols: RTSP and SRT. RTSP gives the lowest delay on a " +
            "reliable network. SRT gives a low delay on a less reliable network, for example " +
            "a cellular network.")
        body("If your team cannot see the video and this screen shows no fault, select H.264. " +
            "A client that cannot show H.265 gives no error that you can see here.")
        body("These settings do not start the video. Use the LIVE button in flight.")

        sub("3. TAK Server Connection")
        body("Pre-Flight shows who you are connected as, which channels are active, and where " +
            "the live-video link goes. To change any of it, touch Configure TAK Server. That " +
            "opens a screen with all the settings on it.")
        body("On that screen: type the address of the TAK server, the two ports, your " +
            "username, your password and the callsign of the aircraft. Then touch Enroll & " +
            "Connect.")

        body("My Channels shows the channels of the server, which holds them, not the app. A " +
            "change applies immediately. \"Rx Only\" gives you data but takes none. You can " +
            "also change channels in flight: touch and hold the TAK icon.")
        body("The channels belong to your certificate, not to this controller. If two " +
            "controllers sign in as the same user, a change on one changes the other.")
        if (com.dji.sdk.sample.tak.AppMode.elevatedAccountAllowed(modePrefs())) {
        body("Elevated mode only. Elevated account (optional): the fleet's second TAK account. Only the channel it " +
            "belongs to gets the live-video link. Every other channel still gets the position " +
            "and the markers. After Log Out, enroll the Elevated account again, or the video " +
            "link goes to every channel.")
        body("You can tick and untick the Elevated account's channels, the same as your own. " +
            "Untick a channel to stop the aircraft reaching it. That is also how you stop data " +
            "arriving from it: the server stops sending it. The app cannot ignore data by " +
            "channel, because a message does not say which channel it came from.")
        warn("The Elevated account is shared by every controller. If you untick its video " +
            "channel, no controller sends video to that channel until somebody ticks it again.")
        }
        // Its OWN predicate, not elevatedAccountAllowed: gating this paragraph on the Elevated
        // predicate works only while every predicate happens to agree.
        if (com.dji.sdk.sample.tak.AppMode.emergencyBroadcastAllowed(modePrefs())) {
        body("Elevated mode only. Emergency Broadcast: touch and hold LIVE, then touch Start " +
            "Emergency Broadcast. " +
            "Every channel gets the live-video link for 15 minutes. The aircraft marker carries " +
            "the link, thus the aircraft must have a GPS position. A notice with a timer shows " +
            "at the top of the flight screen. Touch the notice to add 15 minutes. Touch and " +
            "hold it to stop. Each start, renew and stop goes in the flight record.")
        }

        sub("4. Elevation Data (DTED)")
        body("The terrain data for your area, one file for each region. It makes the markers " +
            "more accurate and the altitude true above the ground. Without it, the altitude " +
            "is the height above your takeoff point.")

        sub("5. FAA Airspace Ceilings (UASFM)")
        body("This downloads the FAA ceiling data for an area, and the flight screen then " +
            "shows the ceiling at the aircraft. Download it on wifi before you go.")
        body("Do not use this data as an approval to fly. It can be out of date. You must get " +
            "your own airspace approval.")

        sub("6. Map Display")
        body("The type of the small map on the flight screen: Street, Hybrid (satellite) or a " +
            "custom map of your team. Touch Save Map Display. It changes your map only, not " +
            "the map of your team.")

        // In BOTH modes: the control exists in both, and the "Elevated mode only" lines above
        // need something to refer to.
        sub("8. Application Mode")
        body("Elevated or Basic. Elevated has two TAK accounts, two video servers and " +
            "Emergency Broadcast. Basic has one of each and no Emergency Broadcast: every " +
            "active channel gets the aircraft and, when a video address is on, its video " +
            "link. The locks need no password.")
        body("Touch Change Mode. The screen says who will receive the aircraft after the " +
            "change. A change needs the password in both directions, and it cannot be made " +
            "while the aircraft flies or LIVE is on.")
    }

    /**
     * ⚠ THE GUIDE MIRRORS THE SCREENS, thus it must not describe controls the mode removes. A
     * guide that names a control the pilot cannot find is worse than a shorter guide — they go
     * looking for it in the air.
     *
     * ⚠ AND THE PRINTED HANDOUT CANNOT SEE THESE GATES. tools/generate_field_guide_md.py finds
     * builder calls by regex and ignores control flow, so every gated paragraph still prints.
     * That is why each Enhanced-only group OPENS with "Elevated mode only." (the on-screen name of ENHANCED) in its own text: the
     * handout stays the superset and stays true, and the in-app guide simply leaves the group
     * out. Never put a mode-dependent `if … else` INSIDE one call — the generator joins every
     * literal in an argument and would print both halves glued together.
     */
    private fun modePrefs(): android.content.SharedPreferences =
        getSharedPreferences(com.dji.sdk.sample.tak.TakConnectActivity.PREFS, MODE_PRIVATE)

    // ---------------------------------------------------------------- Section 3

    private fun sectionThree() {
        section("3. The Flight Screen")
        body("The live camera image fills the screen. The status icons are in a capsule at the " +
            "top left. The action buttons are in a column down the left side, under the " +
            "capsule, one above the other, all the same size. The readout is down the right " +
            "side and the small map is in the bottom right corner. Warnings show at the " +
            "bottom left.")

        sub("Status capsule (top left)")

        entry(
            listOf(icon(R.drawable.ic_menu) to "Menu"),
            "Menu",
            "Closes the flight screen and shows the home screen. The aircraft continues to " +
                "fly and stays connected to TAK.",
        )

        entry(
            listOf(
                takBadge(connected = true) to "Connected",
                takBadge(connected = false) to "Not connected",
            ),
            "TAK connection",
            "Green: your aircraft is on the TAK map of your team. Red: it is not. You can " +
                "fly, but your team cannot see the aircraft.\n\n" +
                "Touch: connect or disconnect. Touch and hold: the TAK Channels. If they are " +
                "locked, touch Unlock. In Elevated mode it asks for the password.",
        )

        entry(
            listOf(
                battery(85) to "85%",
                battery(24) to "24%",
                battery(9) to "9%",
            ),
            "Battery",
            "The charge in the battery of the aircraft. Green shows more than 30%. Yellow " +
                "shows 16% to 30%, and red shows 15% or less. Land the aircraft when the ring " +
                "is yellow. Do not wait for red.",
        )

        entry(
            listOf(
                signal(90) to "Strong",
                signal(60) to "Medium",
                signal(20) to "Weak",
            ),
            "Controller signal",
            "The strength of the signal between the controller and the aircraft. One yellow " +
                "bar shows a weak signal, and red with no bars shows that you can lose it. If " +
                "the bars decrease, fly the aircraft nearer or lift the controller.",
        )

        entry(
            listOf(
                gps(hasFix = true) to "Position",
                gps(hasFix = false) to "No position",
            ),
            "GPS satellites",
            "The quantity of satellites that the aircraft receives. Green shows that the " +
                "aircraft has its position. Wait for green before you take off: without a " +
                "position the aircraft cannot hold station, set a home point, or come home.",
        )

        entry(
            listOf(
                image(R.drawable.ic_rth_home_set) to "Home set",
                image(R.drawable.ic_rth) to "No home",
            ),
            "Return to Home",
            "Touch: sends the aircraft home. The app asks you to confirm.\n\n" +
                "Touch and hold (or touch during a return): the Return to Home menu. Cancel " +
                "Return shows only while the aircraft returns. It stops the return at once and " +
                "gives you the sticks — no confirmation. The app then tells you if the return " +
                "did not stop. Reset Home Point moves the home point to your position. Use it " +
                "if you moved away from the takeoff point. The app asks you to confirm.\n\n" +
                "The house is green when the home point is set.",
        )

        sub("Toolbar: right side (buttons)")

        entry(
            listOf(image(R.drawable.ic_drop_pin) to "Marker"),
            "Put a marker",
            "Touch: puts a marker at the centre of the image. Point the camera at the target " +
                "first, then select the type and type a name.\n\n" +
                "Touch and hold: the \"Markers\" list. Touch rows to select them, then " +
                "Re-send or Delete all of them at one time. Touch and hold one row to change " +
                "your own marker, or to send a marker of your team again.\n\n" +
                "DELETE REMOVES A MARKER FROM THIS AIRCRAFT ONLY. It stays on the screens of " +
                "your team for about 3 days.\n\n" +
                "The app does not put a marker if the crosshair ring is red, if the aircraft " +
                "has no GPS position, or if it is less than 25 ft above the ground. Point the " +
                "camera down more, or climb higher.",
        )

        entry(
            listOf(arPill(on = false) to "Off", arPill(on = true) to "On"),
            "AR: markers on the video",
            "Draws the markers on the live image near their positions. It is green when on, " +
                "and on when you open the flight screen. A marker outside the image shows as " +
                "an arrow at the edge: turn the camera that way.\n\n" +
                "Touch and hold: select what the app draws, set the air-traffic range, and " +
                "open Aim Offsets. Air traffic positions can be about ten seconds old.\n\n" +
                "Calibrate FOV changes how wide the app thinks the camera sees. Adjust it " +
                "against an object near the TOP or BOTTOM edge of the picture: there is one " +
                "control, and the up-down direction is where an error shows.\n\n" +
                "Aim Offsets moves every marker and the camera point together. If all the " +
                "markers are wrong in the same direction, calibrate there: aim at a known " +
                "object with the camera 25 degrees down, then adjust until the point sits on " +
                "the object.",
            listOf(
                "THE AR VIEW IS NOT ACCURATE FOR A POINT. It shows the general area of a " +
                    "marker. Do not use it to choose between objects that are close together, " +
                    "such as one house in a tight row of houses.",
            ),
            anchor = ANCHOR_AR,
        )

        // NO PHOTO PILL: the controller's shutter button takes the still. The button itself IS
        // documented, because the screen reacts to it — the camera-mode switch moves and a
        // notice confirms the still — and a pilot who does not know that the half-press is
        // focus presses twice.
        entry(
            emptyList(),
            "The shutter button (right shoulder)",
            "The button on the top right of the controller takes a still picture. Press it " +
                "half way to focus. Press it fully to take the picture. The screen shows " +
                "PHOTO SAVED when the picture is on the card.\n\n" +
                "The camera changes to the photo mode and stays there. The switch under the " +
                "EV slider shows which mode the camera is in.",
            caveats = listOf(
                "A STILL WHILE THE CAMERA RECORDS IS A FRAME OF THE VIDEO. The recording " +
                    "continues.",
            ),
        )

        entry(
            emptyList(),
            "The camera mode switch",
            "A small switch under the EV slider shows what the camera is set to save: the " +
                "movie camera on the left for video, the still camera on the right for " +
                "photos. The white knob is on the mode the AIRCRAFT reports.\n\n" +
                "Touch the other side to change the mode. This takes no picture and starts " +
                "no recording.\n\n" +
                "Both sides are amber until the camera has answered. Do not read amber as " +
                "a mode.",
            caveats = listOf(
                "THE SWITCH DOES NOT MOVE WHILE THE CAMERA RECORDS. Stop the recording first.",
            ),
        )

        entry(
            listOf(zoomPill("1X") to "Normal", zoomPill("2X") to "2X view"),
            "Zoom",
            "The RIGHT DIAL on top of the controller moves the zoom smoothly, from 1X to " +
                "28X. The button shows the true value, for example 4.6X, and lights up when " +
                "you are zoomed in.\n\n" +
                "Touch the button to go back to 1X immediately.\n\n" +
                "Your team sees the same view in the video.\n\n" +
                "The app knows the true view angle at every step, so the markers and the " +
                "camera point stay accurate when you magnify. Small movements of the aircraft " +
                "are magnified too — hold steady before you put a marker.",
        )

        entry(
            // No icon: this is a HIDDEN GESTURE, not a button. Re-sync is a long-press on the
            // image itself (see the flight activity's comment above fpvView.setOnLongClickListener)
            // — ic_resync was never a real on-screen icon anywhere in this app, and an earlier
            // body said "touch this button", describing a control that does not exist. Field
            // Guide rule: document the invisible, don't draw a control the screen never shows.
            emptyList(),
            "Video re-sync",
            "Corrects the video image. If blocks or marks occur in the image, touch and hold " +
                "anywhere on the video image and it becomes correct in a few seconds. It " +
                "changes your image only.",
        )

        entry(
            // irPill() builds the IR button's ACTUAL drawables and tints, the same idea as the AR
            // entry below, so the legend cannot show a state the toolbar never renders. (The IR
            // button is not a LiveToggleView: its states are not the LIVE pill's.)
            listOf(irPill(on = false) to "Off", irPill(on = true) to "On"),
            "IR: the thermal camera",
            "Touch to change between the usual camera and the thermal camera. The button is " +
                "green when the thermal camera is on.\n\n" +
                "When the thermal camera is on, a colour button shows in the column on the " +
                "right. Touch it to change the colours: white hot, black hot, ironbow.\n\n" +
                "The zoom button is not available when the thermal camera is on. The two " +
                "controls use the same camera.",
        )

        entry(
            // lightsPill() is renderLightsButton()'s own drawables. Order: on, off, then not
            // known.
            listOf(
                lightsPill(true) to "Lights on",
                lightsPill(false) to "Lights off",
                lightsPill(null) to "Not known",
            ),
            "Lights: the motor lights and the beacon",
            "Touch to turn the lights at the motors on or off. This includes the red and " +
                "green lights that show the status of the aircraft.\n\n" +
                "Touch and hold to turn the beacon on or off. A message tells you if the " +
                "beacon is on or off.\n\n" +
                "The button shows what the AIRCRAFT reports, not what you asked for. If the " +
                "aircraft refuses, a message tells you and the button does not change. Amber " +
                "means the aircraft has not answered yet: do not read it as off.",
        )

        entry(
            // The LIVE button's own states.
            listOf(
                live(LiveToggleView.State.OFF) to "Off",
                live(LiveToggleView.State.LIVE) to "Video on",
                live(LiveToggleView.State.RECONNECTING) to "Connects again",
            ),
            "LIVE: video to your team",
            "Starts and stops the live video to the video server of your team. Set the server " +
                "in Pre-Flight Setup first.\n\n" +
                "Touch and hold: the video quality. If the stream is on, the new quality " +
                "applies immediately.\n\n" +
                "A yellow button that flashes shows the connection stopped and the app is " +
                "trying again. Do not touch it: that stops the app trying.",
        )

        entry(
            listOf(
                rec(recording = false) to "Off",
                rec(recording = true) to "Records",
            ),
            "REC: record to the aircraft",
            "Records video to the card in the aircraft, independently of the live video. The " +
                "card keeps the full quality; the live video to your team is lower.\n\n" +
                "REC records from ANY camera mode. If the camera is in the photo mode, the app " +
                "changes it to video, waits for the camera to confirm, then starts. The " +
                "button is red while the camera records.\n\n" +
                "The record button on the top left of the controller does the same. In the " +
                "video mode the controller starts and stops the recording by itself. In the " +
                "photo mode the app does it, through the same steps as the REC button.",
        )

        sub("On the video image")

        entry(
            emptyList(),
            "The crosshair",
            "The centre of the image, and where a marker goes. The ring colour shows the " +
                "accuracy at the current camera angle.\n\n" +
                "WITH terrain data:\n" +
                "GREEN: 25° down or more. The error is about 10 ft.\n" +
                "YELLOW: 10° to 25° down. The error is about 50 ft.\n\n" +
                "WITHOUT terrain data:\n" +
                "GREEN: 30° down or more. The error is about 50 ft.\n" +
                "YELLOW: 15° to 30° down. The error is about 100 ft.\n\n" +
                "RED: less than the yellow angle. The app does not put a marker. Point the " +
                "camera down more, or fly nearer.\n\n" +
                "These values need a good GPS position. Out of your terrain data, the ring " +
                "changes to the no-terrain angles. A marker near the edge of the picture is " +
                "less accurate than one in the centre.",
        )

        entry(
            emptyList(),
            "Quick marker: touch the crosshair",
            "Touch the crosshair to put a marker immediately, with no questions. The marker " +
                "keeps one short name — one letter and three digits — that belongs to this " +
                "controller and does not change.\n\n" +
                "THERE IS ONLY ONE QUICK MARKER. Point the camera at a new location and touch " +
                "the crosshair again: the marker MOVES there, on the screens of all your " +
                "team. Use it to show your team what you look at now.",
        )

        entry(
            emptyList(),
            "Static marker: touch and hold the crosshair",
            "Touch the crosshair and hold it to put a static marker of the type Unknown. THIS " +
                "MARKER DOES NOT MOVE. A second touch and hold puts a SECOND marker. The name " +
                "is the callsign of the aircraft and a number, for example I101-P7.\n\n" +
                "Use it to keep a record of a position.",
        )

        entry(
            emptyList(),
            "Obstacle distances",
            "A mark at the nearest edge of the video, with the distance in feet. A curved " +
                "line is an obstacle at that side; FWD is in front, REAR is behind. It shows " +
                "at about 39 ft in yellow, then red at 13 ft or less.\n\n" +
                "AN EDGE WITH NO MARK DOES NOT MEAN THE DIRECTION IS CLEAR. It can also mean " +
                "the aircraft has no sensor there. With no obstacle sensors this display " +
                "always stays empty.",
        )

        entry(
            emptyList(),
            "Warnings (bottom left)",
            "A box at the bottom left shows a warning. RED means act now. AMBER means know it. " +
                "IF THE MOTORS DO NOT START, READ THIS BOX FIRST.\n\n" +
                "Most come from the aircraft, in its own words. The app adds its own for the " +
                "return home, the battery, the limits, a missing home point and high wind. " +
                "The box shows the most important one, with a count if there are more. A " +
                "small arrow pointing up means there are more above it.\n\n" +
                "Touch the box to read all of them, worst first from the top, and touch the " +
                "arrow under the list to close it. Touch the X to hide this set of warnings. " +
                "The box comes back by itself the moment the aircraft says something " +
                "different.\n\n" +
                "DEBUG LOG ON is an amber warning from the app, not from the aircraft. It " +
                "means the debug log is writing to the controller, which costs some " +
                "smoothness. The X does not hide it. Turn the log off on the Debug screen.",
            caveats = listOf(
                "AN ADVISORY YOU HIDE WITH THE X STAYS HIDDEN FOR THIS FLIGHT. The aircraft " +
                    "sends some lines as advice, not as alarm, for example low light for the " +
                    "obstacle cameras at night. If you hide one of those it does not come " +
                    "back when it repeats. A real warning always comes back.",
            ),
        )

        entry(
            emptyList(),
            "Antenna aim (bottom centre)",
            "An arc that shows where the aircraft is, relative to where the controller " +
                "points. The marker at the top of the arc means the antennas face the " +
                "aircraft; green means close enough. It shows only when the controller has a " +
                "compass direction and both positions are known.\n\n" +
                "Use it when you cannot see the aircraft: turn your body until the marker is " +
                "at the top. The antennas of the controller are directional, and a link is " +
                "strongest when they face the aircraft.",
        )

        entry(
            emptyList(),
            "The controller buttons: L1, L2, L3 and C1",
            "The buttons on the front left of the controller do the same as the controls " +
                "on the screen. Small labels at the left edge of the screen show what each " +
                "button does.\n\n" +
                "C1, on the back of the controller, changes between the usual camera and " +
                "the thermal camera, the same as L3 and the IR button. C2 and C3 do " +
                "nothing in this app.\n\n" +
                "L1 is the crosshair as a button. Press it to put the quick marker where " +
                "the crosshair points. Press and hold it to put a new static marker there. " +
                "Each hold makes a new marker.\n\n" +
                "L2 opens and closes the accessory panel. This is the ONLY way to the " +
                "panel. There is no button for it on the screen.\n\n" +
                "L3 changes between the usual camera and the thermal camera, the same as " +
                "the IR button.\n\n" +
                "R1, on the front right, is push-to-talk. Hold it to speak through the " +
                "aircraft.\n\n" +
                "R3, below it, changes the small view at the bottom right between the MAP and " +
                "the VISION view. The small view shows which one it is, and shows R3.",
        )

        entry(
            emptyList(),
            "The vision view (R3)",
            "The aircraft has cameras that look for obstacles. R3 shows what they see, in the " +
                "small view at the bottom right, in place of the map. Press R3 again to get " +
                "the map back.\n\n" +
                "The picture is grey and very wide. This is normal: these are not the camera " +
                "you take video with.\n\n" +
                "The view changes by itself to look the way the aircraft moves. Forward, back, " +
                "left, right and down.\n\n" +
                "USE IT TO SEE WHAT IS BESIDE AND BEHIND THE AIRCRAFT. The video camera looks " +
                "one way only, and this looks the way you are going.",
            listOf(
                "THE VIEW IS EMPTY UNTIL THE AIRCRAFT IS IN THE AIR. The obstacle cameras do " +
                    "not send a picture on the ground. An empty view before you take off is " +
                    "correct.",
            ),
        )

        entry(
            emptyList(),
            "The accessory panel (L2)",
            "Controls the light and the speaker on the aircraft. Press L2 to open it and L2 " +
                "again to close it. You can also touch the ✕ in the corner of the panel.\n\n" +
                "The panel shows only the parts that answer. If the light is not fitted, " +
                "there is no light section.\n\n" +
                "For the light there are three buttons: LOW, HIGH and STROBE. Touch one to put " +
                "the light in that state. Touch the same button again to turn the light off. " +
                "STROBE always flashes at full brightness.\n\n" +
                "ON THE GROUND THE AIRCRAFT LIMITS THE LIGHT to 40 percent. HIGH does not give " +
                "full brightness until the aircraft is in the air. The aircraft tells you this " +
                "in the warning box.\n\n" +
                "For the speaker there are three message buttons. Touch one to send that " +
                "message to the aircraft and play it. Touch the same button again to stop it. " +
                "The aircraft speaks about one second after you touch.\n\n" +
                "REPEAT x3 sends the next message three times, with 30 seconds between each " +
                "one. It then stops. The button on the screen counts down to the next one.\n\n" +
                "A slider sets the volume.\n\n" +
                "TO SPEAK THROUGH THE AIRCRAFT, hold HOLD TO TALK, or hold the R1 button on " +
                "the controller. The two do the same. Speak into the controller.\n\n" +
                "WAIT FOR GREEN BEFORE YOU SPEAK. The button shows OPENING in amber for about " +
                "one and a half seconds while the aircraft gets ready. It then shows ON AIR in " +
                "green. Your words before green do not go out.\n\n" +
                "WHEN YOU LET GO, the button shows FINISHING in amber for about two seconds. " +
                "The aircraft is still saying your last words. Do not be surprised by this.\n\n" +
                "You must HOLD the button. The aircraft stops when you let go. This is on " +
                "purpose: a button that stays on can leave the aircraft loud when you do not " +
                "know it.\n\n" +
                "RECORD THE THREE MESSAGES IN PRE-FLIGHT, in section 7. The aircraft has no " +
                "messages until you record them.\n\n" +
                "THE L2 LABEL AT THE LEFT OF THE SCREEN tells you what the aircraft is doing, " +
                "even when the panel is closed. Green ON AIR means the aircraft is making " +
                "sound now. Amber means it is not yet: OPENING, SENDING or FINISHING. Touch " +
                "the label to open the panel and stop the message.\n\n" +
                "A button is GREEN when that function is on. It is grey when the function is " +
                "off. It is AMBER when the aircraft has not said yet, or while a message goes " +
                "to the aircraft.",
            // ⚠ A COMMA, NOT A PLUS. `"..." +` followed by listOf(...) is something Kotlin
            // ACCEPTS — String.plus(Any?) appends the list's toString() — so it compiles, runs,
            // and renders the caveats as a bracketed list literal glued onto the body while the
            // entry silently loses its caveats parameter entirely. The mangled text would be the
            // obstacle-sensing warning.
            listOf(
                "THE LIGHT STOPS THE OBSTACLE SENSORS. When the light is on, the aircraft " +
                    "cannot see obstacles. The aircraft gives a caution when this happens. Fly " +
                    "with more care, and keep more distance from obstacles, while the light " +
                    "is on.",
                "A MESSAGE CONTINUES IF YOU CLOSE THE PANEL. This is on purpose, so a message " +
                    "is not cut in half. Watch for the green ON AIR label.",
            ),
        )

        sub("The readout: right side")

        entry(
            emptyList(),
            "Exposure slider (top right)",
            "Makes the image brighter or darker. Use it when the automatic exposure is not " +
                "correct, for example a dark object against snow.",
        )

        entry(
            emptyList(),
            "Clock",
            "Below the slider, the time of the controller. Use it to give a time to your team.",
        )

        entry(
            emptyList(),
            "Aircraft data",
            "Three lines: the callsign and speed, the height above the ground and above sea " +
                "level, then the latitude and longitude. Below them GIMBAL shows the camera " +
                "angle, in the colour of the crosshair ring.\n\n" +
                "AGL is the true height above the ground and needs terrain data. Without it " +
                "ALT shows instead: the height above your takeoff point. MSL is above sea " +
                "level, which airspace limits use.",
        )

        entry(
            emptyList(),
            "RTH and HOME lines",
            "RTH is the return height the AIRCRAFT holds now. Two dashes mean the app does " +
                "not know it, not that it is zero. HOME is the distance and direction to the " +
                "home point, in degrees true.\n\n" +
                "RTH shows the AIRCRAFT, not what you typed in Pre-Flight Setup. If the two " +
                "differ, the aircraft did not accept it. Send it again with Apply.",
        )

        entry(
            emptyList(),
            "FAA ceiling line",
            "The published ceiling at the position of the aircraft. It becomes red if you fly " +
                "above it. It shows AGL, thus compare it with the AGL line and not MSL.\n\n" +
                "Grey \"Class G\" - no facility map here; the usual 400 ft limit applies.\n" +
                "Yellow \"no data here\" - you flew out of the area you downloaded.",
        )

        entry(
            emptyList(),
            "The map",
            "North is at the top, the aircraft is in the centre, and the red line goes from " +
                "the home point to the aircraft. Touch a marker of your team to remove it from " +
                "your map only.\n\n" +
                "The button at the bottom left gives two zoom levels: WIDE is the full distance " +
                "you may fly, NEAR shows more detail. Touch the map two times to make it " +
                "large, and again to make it small.\n\n" +
                "Map data: OpenStreetMap contributors. Satellite images: Esri World Imagery.",
        )

        sub("The resource bar: bottom left")

        entry(
            emptyList(),
            "System resources",
            "Off unless you switch it on in Debug Log. It shows the memory of the controller " +
                "(SYS) and of the app (APP), the load (CPU) and the quantity of TAK contacts " +
                "(TAK).\n\n" +
                "Watch TAK: it must go up and down with the persons and aircraft near you. If " +
                "it only goes up across a flight, report it.",
        )
    }

    /**
     * How to calibrate Aim Offsets.
     *
     * ⚠ **THE AR ENTRY IN SECTION 3 ALREADY SAYS WHAT AIM OFFSETS DOES. THIS SECTION IS THE
     * PROCEDURE** — when to do it, what second device you need, how to find the point to
     * watch, and how to tell that a change has taken effect. That is the part a pilot needs in
     * the field.
     *
     * Nothing here is airframe-specific. It is a method for lining up the camera look point
     * against a known object on a second TAK client, using the SPI this application sends.
     *
     * ⚠ **NO CAMERA FACTS FROM ANOTHER AIRFRAME.** Claims such as "a lens cannot be changed
     * while the aircraft records" or "one shutter press saves both sensors" were established on
     * a different aircraft, from its own card and logs. This aircraft switches its video STREAM
     * SOURCE rather than a display mode, and those behaviours have not been measured here — see
     * onIrTapped. Do not add such sentences without measuring them on this aircraft.
     */
    private fun sectionAimOffsets() {
        section("4. How to correct the position of a marker")

        body("If the aim of the camera has a small error, the markers go to a position that " +
            "is not correct. \"Aim Offsets\" corrects this. The error belongs to the " +
            "aircraft, not to the app: a gimbal can move after a hard landing or a repair.")

        body("Do this one time for each aircraft. Do it again after a hard landing, after a " +
            "repair of the gimbal or the camera, or when you use a different aircraft.")

        body("You need a second device with iTAK, ATAK, TAK Aware or CloudTAK that can see " +
            "the markers of the aircraft.")

        sub("Before you start")
        bullet("Select a target that you see clearly in the video and can find on the map of " +
            "the second device. Center the reticle of the controller on that object. A mark " +
            "on a road or a corner of a building is good. The aircraft sends its camera look " +
            "point to TAK about two times each second. On the second TAK device, find that " +
            "point. Its name ends with \"-SPI\" and its icon looks like the reticle. Watch " +
            "the SPI while you change the offsets on the controller. Change the offsets until " +
            "the SPI on the map lines up with the object the reticle points at in the video.")
        bullet("The point moves a few seconds after each change. Change one control at a " +
            "time. Stop when the point is on the target.")
    }

    // ---------------------------------------------------------------- Section 5

    private fun sectionFour() {
        section("5. Flight path records")
        body("The app records each flight automatically, with no switch and no TAK server. " +
            "It starts when the aircraft leaves the ground and stops when it is down for 10 " +
            "seconds, thus a short touch does not divide the record.")

        body("Open Downloads/TAKPilotFlights on the controller. Each flight makes two files:")
        bullet(".gpx - the track. Import it into ATAK or Google Earth.")
        bullet(".csv - one row each second: time, position, altitude, speed, heading, " +
            "battery and satellites. Open it in a spreadsheet.")

        body("Without a GPS position the app records nothing for that time. It does not " +
            "write a false position.")
        body("The folder keeps about 50 MB, then deletes the oldest files. Copy a record " +
            "away to keep it.")
    }

    // ---------------------------------------------------------------- Section 5

    // There is deliberately no "What this build cannot do" section. The camera-angle limit
    // rides with the crosshair entry, where a pilot meets the same fact in context, and a
    // guide that lists what a build does not have grows without limit.

    /** Action-bar back arrow behaves the same as the system back gesture. */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ------------------------------------------------------- content builders

    private fun title(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 18f
        setTypeface(null, android.graphics.Typeface.BOLD)
    })

    private fun lede(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_secondary)); textSize = 12f
        setPadding(0, dp(6), 0, dp(4))
    })

    private fun section(text: String) {
        divider()
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        })
    }

    private fun sub(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_accent)); textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        letterSpacing = 0.03f
        setPadding(0, dp(12), 0, dp(4))
    })

    private fun body(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 12f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, 0, 0, dp(8))
    })

    private fun bullet(text: String) = content.addView(TextView(this).apply {
        this.text = "•  $text"
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 12f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(8), 0, 0, dp(6))
    })

    /** Neutral aside — worth knowing, not a hazard. */
    private fun note(text: String) =
        calloutView(text, R.color.tp_accent, R.color.tp_surface_guide_note)

    /** Something that can bite you in the air or on the ground. */
    private fun warn(text: String) =
        calloutView(text, R.color.tp_btn_danger_dialog, R.color.tp_surface_guide_warn)

    /**
     * Callout row: a coloured tint bar against a low-saturation background of the same hue.
     *
     * Takes colour RESOURCES, not hex strings — a literal in Kotlin is easy to reach for and
     * easy to miss, which is why this file is inside the token rule.
     */
    private fun calloutView(text: String, @ColorRes barColor: Int, @ColorRes bgColor: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(applicationContext, bgColor))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(4); bottomMargin = dp(10)
            }
        }
        row.addView(View(this).apply {
            setBackgroundColor(ContextCompat.getColor(applicationContext, barColor))
            layoutParams = LinearLayout.LayoutParams(dp(3), MATCH)
        })
        row.addView(TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_dim)); textSize = 11f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        })
        content.addView(row)
    }

    private fun divider() = content.addView(View(this).apply {
        setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_border))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            topMargin = dp(20); bottomMargin = dp(12)
        }
    })

    private fun spacer(heightDp: Int) = content.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
    })

    /**
     * One control: its icon in each state worth recognising, its name, what it does, and any
     * caveats. [icons] may be empty for parts of the screen that aren't a button.
     */
    /**
     * Jumps to the entry tagged [anchor], for a deep link from elsewhere in the app.
     *
     * Posted rather than called directly: the guide is built in onCreate and nothing has been
     * measured or laid out yet, so the card's y position is still 0 and an immediate scroll
     * would silently do nothing. Runs after the first layout pass instead.
     *
     * Fails OPEN — an absent or unrecognised anchor leaves the guide at the top, which is what
     * a reader wants if a link ever goes stale.
     */
    private fun scrollToAnchor(anchor: String?) {
        if (anchor.isNullOrEmpty()) return
        val scroll = findViewById<android.widget.ScrollView>(R.id.fieldGuideScroll) ?: return
        val target = content.findViewWithTag<View>(anchor)
        if (target == null) {
            AppLog.w(TAG, "field guide anchor '$anchor' not found — opening at the top")
            return
        }
        scroll.post {
            // Offset up a little so the reader lands with the entry's title in view, not with
            // the card's top edge flush against the action bar.
            scroll.scrollTo(0, (target.top - dp(12)).coerceAtLeast(0))
            AppLog.v(TAG, "field guide opened at '$anchor'")
        }
    }

    private fun entry(
        icons: List<Pair<View, String>>,
        name: String,
        what: String,
        caveats: List<String> = emptyList(),
        anchor: String? = null,
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
            // Deep-link target — see EXTRA_SCROLL_TO. A tag rather than a generated view id
            // because the whole guide is built in code and ids would have to be kept unique
            // by hand against a resource file that has no other reason to exist.
            if (anchor != null) tag = anchor
        }

        if (icons.isNotEmpty()) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = dp(10)
                }
            }
            for ((view, caption) in icons) {
                val captionView = TextView(this@FieldGuideActivity).apply {
                    text = caption
                    setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_muted)); textSize = 11f
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                    setPadding(0, dp(5), 0, 0)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                }
                // Size the chip from the MEASURED caption rather than leaving it to wrap_content.
                // Letting the layout work it out doesn't survive here: the icon is a fixed-width
                // child, so the cell settles on the icon's width and a longer caption
                // ("Not connected") gets silently cut to something that reads as a different
                // state ("Not conn"). Measuring the text and flooring the chip to it is the only
                // version that can't clip.
                val captionWidth = captionView.paint.measureText(caption).toInt()
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    // Toolbar-dark chip behind each example: these icons are drawn to sit on
                    // the flight toolbar, and judging them against a lighter card would be
                    // misleading about how they actually read in the air.
                    setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide_code))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    minimumWidth = captionWidth + dp(20)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                        rightMargin = dp(8)
                    }
                }
                cell.addView(view)
                cell.addView(captionView)
                strip.addView(cell)
            }
            card.addView(strip)
        }

        card.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE); textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(5))
        })
        card.addView(TextView(this).apply {
            text = what
            setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 11f
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        for (c in caveats) {
            card.addView(TextView(this).apply {
                text = "!  $c"
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_warn)); textSize = 12f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(8), 0, 0)
            })
        }
        content.addView(card)
    }

    // ------------------------------------------------- live icon examples

    private fun iconParams(sizeDp: Int = 34) = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))

    private fun icon(res: Int) = image(res)

    private fun image(res: Int): View = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = iconParams()
    }

    /** The TAK badge exactly as the toolbar builds it, dot tinted to the state described. */
    // Tokens from takpilot_colors.xml, not raw hex literals: colours come from the tokens, and
    // no new Color.parseColor call site is added.
    // Instance-level (not companion) because resolving a color token needs a Context.
    private val connectedGreen by lazy { ContextCompat.getColor(this, R.color.tp_state_go) }
    private val disconnectedRed by lazy { ContextCompat.getColor(this, R.color.tp_state_danger) }
    private val noFixGrey by lazy { ContextCompat.getColor(this, R.color.tp_text_secondary) }

    private fun takBadge(connected: Boolean): View {
        val frame = android.widget.FrameLayout(this).apply { layoutParams = iconParams() }
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_tak_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(34), dp(34))
        })
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.bg_status_dot)
            setColorFilter(if (connected) connectedGreen else disconnectedRed)
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
        })
        return frame
    }

    private fun battery(pct: Int): View =
        BatteryGaugeView(this).apply { layoutParams = iconParams(); setPercent(pct) }

    private fun signal(pct: Int): View =
        SignalBarsView(this).apply { layoutParams = iconParams(); setPercent(pct) }

    private fun gps(hasFix: Boolean): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_gps)
        setColorFilter(if (hasFix) connectedGreen else noFixGrey)
        layoutParams = iconParams()
    }

    private fun live(state: LiveToggleView.State): View =
        LiveToggleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(66), dp(34))
            setState(state)
        }

    private fun rec(recording: Boolean): View =
        RecordToggleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(66), dp(34))
            setRecording(recording)
        }

    private fun zoomPill(label: String): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_zoom_pill)
        setTextColor(Color.WHITE); textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    /** The AR pill in either state, built from the same drawables and tints the flight screen's
     *  refreshArButton() uses, so an example here can't show a state the toolbar never renders. */
    private fun arPill(on: Boolean): View = TextView(this).apply {
        text = "AR"
        gravity = Gravity.CENTER
        setBackgroundResource(if (on) R.drawable.bg_pill_active else R.drawable.bg_zoom_pill)
        setTextColor(if (on) connectedGreen else Color.WHITE)
        alpha = if (on) 1f else 0.45f
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    /** The IR pill in either state, built from the same drawables and tints the flight screen's
     *  renderIrButtons() uses (same shape as arPill, no alpha dimming — renderIrButtons()
     *  doesn't dim the off state, unlike the AR pill). */
    private fun irPill(on: Boolean): View = TextView(this).apply {
        text = "IR"
        gravity = Gravity.CENTER
        setBackgroundResource(if (on) R.drawable.bg_pill_active else R.drawable.bg_zoom_pill)
        setTextColor(if (on) connectedGreen else Color.WHITE)
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    /**
     * The exterior-lights pill in all THREE of its states, built from the same drawables and the
     * same colours renderLightsButton() uses.
     *
     * The button says its state with COLOUR like the pills beside it, so
     * an icon-only legend (a bare icon at half alpha for unknown) would disagree with the
     * screen the pilot is holding — and the amber state is the reason this is worth a pill
     * rather than a tint: it is
     * the only control on the flight screen that can say "the aircraft has not answered", and a
     * pilot who has never been shown it has no way to read it at night.
     *
     * ⚠ The lit and unknown states share the PLAIN bulb and take a tint; the dark state keeps
     * the slashed bulb UNTINTED. See renderLightsButton() for why those two facts must stay
     * together.
     */
    private fun lightsPill(on: Boolean?): View = ImageView(this).apply {
        setImageResource(if (on == false) R.drawable.ic_led_off else R.drawable.ic_led_on)
        setBackgroundResource(when (on) {
            true -> R.drawable.bg_pill_active
            false -> R.drawable.bg_zoom_pill
            null -> R.drawable.bg_pill_unknown
        })
        imageTintList = when (on) {
            true -> androidx.core.content.ContextCompat.getColorStateList(
                this@FieldGuideActivity, R.color.tp_state_go)
            null -> androidx.core.content.ContextCompat.getColorStateList(
                this@FieldGuideActivity, R.color.tp_state_unknown)
            false -> null
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {

        /**
         * Optional intent extra: open the guide scrolled to one entry rather than at the top.
         * Value is one of the ANCHOR_* constants, matched against the tag [entry] puts on its
         * card. An unknown or absent value opens at the top, which is the safe default — a
         * deep link that stops matching must not open a blank-looking screen.
         */
        const val EXTRA_SCROLL_TO = "scrollTo"

        /** The AR overlay entry, reached from the flight screen's AR menu. */
        const val ANCHOR_AR = "ar"

        /** Opens the guide at [anchor] (or the top if null). */
        @JvmStatic
        fun intent(context: android.content.Context, anchor: String? = null) =
            android.content.Intent(context, FieldGuideActivity::class.java).apply {
                if (anchor != null) putExtra(EXTRA_SCROLL_TO, anchor)
            }

        private const val TAG = "TP2Guide"
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
