package com.dji.sdk.sample.takpilot2

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.taklite.util.AppLog
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dji.sdk.sample.R
import com.dji.sdk.sample.tak.ArSettings
import com.dji.sdk.sample.tak.AircraftAccessories
import com.dji.sdk.sample.tak.AircraftLights
import com.dji.sdk.sample.tak.AppMode
import com.dji.sdk.sample.tak.SpeakerBroadcast
import com.dji.sdk.sample.tak.SpeakerMessages
import com.dji.sdk.sample.tak.SpeakerTalk
import com.dji.sdk.sample.tak.VisionAssist
import dji.sdk.keyvalue.value.flightassistant.VisionAssistDirection
import dji.v5.manager.aircraft.megaphone.MegaphoneStatus
import dji.v5.manager.aircraft.megaphone.PlayMode
import com.dji.sdk.sample.tak.CameraSlantPoint
import com.dji.sdk.sample.tak.FlightLimitsController
import com.dji.sdk.sample.tak.ZoomLadder
import com.dji.sdk.sample.tak.DjiObstacleState
import com.dji.sdk.sample.tak.DjiSdkBridge
import android.content.Intent
import com.dji.sdk.sample.tak.ExposureController
import com.dji.sdk.sample.tak.FlightWarnings
import com.dji.sdk.sample.tak.OperatorLocation
import com.dji.sdk.sample.tak.TakBridgeHolder
import com.dji.sdk.sample.tak.TakDropMarkers
import com.dji.sdk.sample.tak.VideoStreamerHolder
import com.mapbox.geojson.Feature
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.Property
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconAllowOverlap
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconIgnorePlacement
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconImage
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconRotate
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconRotationAlignment
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconSize
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineColor
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineWidth
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.visibility
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.taklite.client.tak.TakManager
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIActionKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CameraThermalPalette
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.camera.ZoomRatiosRange
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import java.util.UUID

/**
 * TAKPilot2 Go flight screen — live FPV video (full-bleed) + a live map overlay, phone-sized.
 * Owns the [TakBridgeHolder] telemetry-to-CoT bridge's lifecycle: starts it on
 * entry, stops it on exit. TakManager.sendDronePLI() no-ops internally when TAK isn't
 * connected, so this runs safely whether or not TAK Setup has been done yet.
 *
 * Also drives the on-screen telemetry HUD + own-aircraft map marker — a
 * 500ms poll of [TakBridgeHolder.hud].
 * Inbound TAK contacts/markers are drawn by [com.dji.sdk.sample.tak.TakMapMarkers], which owns
 * its own source/layer on this screen's style; dropped pins and the AR overlay have their
 * own handlers on this screen.
 */
class TAKPilot2GoFlightActivity : AppCompatActivity() {

    private lateinit var fpvView: FpvTextureView
    private lateinit var mapView: MapView
    private lateinit var mapContainer: android.widget.FrameLayout
    private lateinit var mapZoomButton: TextView
    private lateinit var resourceMonitorRow: View
    private lateinit var resourceMonitorCells: List<TextView>
    private lateinit var noVideoCover: View
    private lateinit var fpvOverlayText: TextView
    private lateinit var toolbarBattery: BatteryGaugeView
    private lateinit var toolbarGps: TextView
    private lateinit var toolbarGpsIcon: ImageView
    private lateinit var toolbarTakDot: ImageView
    private lateinit var toolbarSignal: SignalBarsView
    private lateinit var toolbarSignalText: TextView
    private lateinit var liveToggle: LiveToggleView
    private lateinit var recordToggle: RecordToggleView
    private lateinit var rthButton: ImageButton
    private lateinit var zoomButton: TextView
    private lateinit var irButton: TextView
    private lateinit var irPaletteButton: TextView
    private lateinit var lightsButton: ImageButton
    /** True while the INFRARED camera is the live source. Mirrors the aircraft's answer:
     *  set from the stream-source read-back, never from what was asked. */
    private var irOn = false
    /** False until the aircraft has told us which lens, zoom and palette it is actually on.
     *  See [syncCameraFromAircraft] — the UI must not claim a state it has not been told. */
    private var cameraStateSynced = false
    /** True while the ZOOM (tele) camera is the live stream source. Maintained at every
     *  source switch — the zoom-follow logic must know which camera the pilot is LOOKING at,
     *  and inferring it from the display ratio broke the moment ratios went fractional. */
    private var teleLive = false
    /** True while a source switch is in flight; follow events hold off until it lands. */
    private var sourceSwitchPending = false
    private var irPalette = 0
    private lateinit var fpvFaaCeiling: TextView
    private lateinit var fpvRthAltitude: TextView
    private lateinit var fpvHomeDistance: TextView
    private lateinit var fpvClock: TextView
    private lateinit var fpvGimbalPitch: TextView
    private lateinit var crosshairView: CrosshairView
    private lateinit var arOverlay: ArOverlayView
    private lateinit var arButton: TextView

    // FAA UASFM ceiling, cached per grid cell. The lookup itself is a hash hit, but re-deriving
    // and re-formatting it on every 500ms tick is pointless when the answer only changes when
    // the aircraft crosses a 1/120-degree cell boundary (~1/2 mile), so we recompute on the
    // crossing instead. MIN_VALUE means "nothing cached yet".
    private var lastFaaGridRow = Int.MIN_VALUE
    private var lastFaaGridCol = Int.MIN_VALUE
    /** Fires the "Video link down" notice on a CHANGE only — never on the first read, so
     *  connecting with the Elevated connection not yet up does not immediately show a notice
     *  for something the pilot did not just do. */
    private var lastVideoChannelDown: Boolean? = null
    private var cachedFaaCeilingFt: Int? = null
    private var cachedFaaWithinDownloadedArea = false
    private var currentCallsign: String = ""
    /** The zoom rung the camera is on, as [ZoomLadder] units. 1.0 is the wide camera. */
    private var zoomRatio = ZoomLadder.MIN

    private var map: MapboxMap? = null
    private var aircraftSource: GeoJsonSource? = null
    private var aircraftLayer: SymbolLayer? = null
    private var homeSource: GeoJsonSource? = null
    private var homeLayer: SymbolLayer? = null
    private var homeLineSource: GeoJsonSource? = null
    private var homeLineLayer: LineLayer? = null
    private lateinit var fpvNotice: TextView
    private lateinit var emergencyBroadcastBanner: TextView
    private lateinit var flightDiagnostics: TextView
    private lateinit var flightDiagnosticsRow: View
    private lateinit var flightDiagnosticsClose: TextView
    private lateinit var fpvAntennaArc: AntennaAimView

    /**
     * True while the pilot holds the warning banner open. A tap toggles it.
     *
     * Held in the ACTIVITY and not in FlightWarnings: it is a view state, and the banner is
     * repainted from the HUD tick, so the flag has to outlive each repaint. It clears whenever
     * the banner hides, thus a new set of faults always arrives collapsed.
     */
    private var warningExpanded = false

    /**
     * The set of faults the pilot has CLOSED, as the joined text of every line the banner was
     * showing at the moment of the close. Null when nothing is closed.
     *
     * ⚠ A CLOSE HIDES ONE SET OF FAULTS, NEVER THE BANNER ITSELF. The signature is compared on
     * every repaint, so the banner returns the instant the live set differs by one line — a new
     * fault, a worse fault, or a fault that cleared and came back. A pilot cannot close the
     * banner and then miss the next thing the aircraft says.
     *
     * This is the whole reason a close is safe here. Making something show less can turn a
     * display quirk into a safety fault, so the only state a close can reach is "this exact
     * set, already read".
     */
    private var warningDismissedSignature: String? = null

    /** The media-mode switch. Painted from the Hud's [DroneTakBridge.Hud.cameraMode]. */
    private lateinit var fpvMediaMode: MediaModeView
    /** The last mode painted, so a CHANGE is announced and the first answer is not. */
    private var lastPaintedCameraMode: dji.sdk.keyvalue.value.camera.CameraMode? = null
    private var cameraModeEverPainted = false

    // ---- THE ACCESSORY PANEL (L2): the AL1 light and the AS1 speaker. See AircraftAccessories.
    private lateinit var accessoryPanel: View
    private lateinit var accessoryNone: TextView
    private lateinit var accessoryLightBlock: View
    private lateinit var accessoryLightBrightnessRow: View
    private lateinit var accessoryLightLow: TextView
    private lateinit var accessoryLightHigh: TextView
    private lateinit var accessoryLightBlink: TextView
    private lateinit var accessorySpeakerBlock: View
    private lateinit var accessorySpeakerMessageRow: View
    private lateinit var accessorySpeakerNoMessages: TextView
    private lateinit var accessorySpeakerRepeat: TextView
    private lateinit var visionAssistView: VisionAssistView
    private lateinit var visionAssistLabel: TextView

    /**
     * The L2 hint chip, which doubles as the ON AIR indicator.
     *
     * ⚠ THIS IS THE ONLY THING ON SCREEN THAT SAYS THE AIRCRAFT IS SPEAKING once the panel is
     * closed — and a repeat run speaks, unattended, for over a minute. Closing the panel does not
     * stop a message on purpose (a bounded message should finish; cutting it off mid-sentence is
     * an operational failure), so the safety has to come from being visible instead. It is also
     * exactly where the pilot must press to reach the stop.
     */
    private lateinit var hintL2: TextView

    /**
     * True when the next message is sent three times, thirty seconds apart.
     *
     * ⚠ HELD IN THE ACTIVITY AND NOT PERSISTED. A run speaks unattended for over a minute, so it
     * is armed per mission rather than inherited from a previous one — a pilot who armed it last
     * week must not get a surprise three-message broadcast today.
     */
    private var speakerRepeatArmed = false
    private lateinit var accessorySpeakerMsgPills: List<TextView>
    private lateinit var accessorySpeakerVolumeLabel: TextView
    private lateinit var accessorySpeakerVolume: android.widget.SeekBar
    private lateinit var accessorySpeakerStatus: TextView
    private lateinit var accessorySpeakerTalk: TextView

    /**
     * True while a slider is being dragged, so [renderAccessoryPanel] leaves it alone.
     *
     * Without it a repaint mid-drag would snap the thumb back to the aircraft's last reported
     * value and fight the pilot's finger. The write goes on the RELEASE, never on every step:
     * a brightness slider dragged across its travel would otherwise be a keystroke burst of
     * writes to a payload, which is the shape of a fault that has crashed an aircraft.
     */
    private var accessorySliderDragging = false

    private var flightShootPhotoButton: ImageButton? = null

    /**
     * True from the shutter tap until the camera is back in VIDEO mode.
     *
     * ⚠ WITHOUT THIS, RAPID TAPS OVERLAP AND FAIL. Measured: three taps in 1.5s started
     * three mode-switch/expose/shoot/restore sequences at once. One photo was taken and the
     * other two returned a NULL error, so the pilot read "Photo failed: null" while a photo
     * had in fact been saved. Three restore loops then ran concurrently.
     *
     * The pilot was not misusing it. The mode switch takes about 1.5s and the button gave no
     * sign of working, so a second press is the natural thing to do — which is why this flag
     * comes with [setShutterBusy] and not on its own.
     */
    private var photoSequenceActive = false
    private lateinit var obstacles: ObstacleEdgeView
    // Edge-triggers the "Home Point Set" notice only on the false->true transition (not every
    // tick while it's already set), and only once per bridge start.
    private var lastHomeSet = false

    // Per-tick allocation: the mini-map camera, the home marker and the
    // home->aircraft line were rebuilt every 500ms HUD tick even when nothing had moved
    // (parked on the ground, or between fix updates). These remember what was last drawn so
    // the tick can skip the GeoJSON/CameraPosition allocation when it would be a no-op.
    private var lastMapLat: Double? = null
    private var lastMapLon: Double? = null
    private var lastMapZoom: Double? = null
    private var lastHomeLat: Double? = null
    private var lastHomeLon: Double? = null
    private var lastHomeLineAircraftLat: Double? = null
    private var lastHomeLineAircraftLon: Double? = null
    // Deliberately separate from lastHomeLat/lastHomeLon above (the marker's cache): the
    // marker block runs first each tick and syncs that pair to the current home position
    // BEFORE this line's guard would get a chance to compare against it, which would make a
    // real home-point move invisible to the line. Two independent caches, no shared state.
    private var lastHomeLineHomeLat: Double? = null
    private var lastHomeLineHomeLon: Double? = null
    private var lastOverlayText: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateHud()
            handler.postDelayed(this, HUD_INTERVAL_MS)
        }
    }
    /** A field, not a call-site lambda, so it can be removed again — see the note on
     *  DjiObstacleState's listener list. */
    private val obstacleListener: () -> Unit =
        { runOnUiThread { obstacles.update(DjiObstacleState.faces) } }

    private val hideNotice = Runnable { fpvNotice.visibility = View.GONE }

    /**
     * Told by [TakManager] whenever the Emergency Broadcast override starts, renews or ends.
     * This screen only PAINTS. The flight-record line belongs to
     * [com.dji.sdk.sample.tak.EmergencyBroadcastPolicy], installed at application start so it
     * runs whichever screen is open, or none.
     */
    private val emergencyBroadcastListener =
        TakManager.EmergencyBroadcastListener { active, expiresAtEpochMs, _ ->
            runOnUiThread { paintEmergencyBroadcast(active, expiresAtEpochMs) }
        }

    /** The store every [AppMode] predicate on this screen reads. One accessor, so no gate on
     *  this screen can end up reading a different preference file from the others. */
    private fun modePrefs(): android.content.SharedPreferences =
        getSharedPreferences(com.dji.sdk.sample.tak.TakConnectActivity.PREFS, MODE_PRIVATE)

    /** Shows or hides the running notice and keeps its countdown current. Called from the
     *  listener above on every change, and from [updateHud]'s tick while active so the mm:ss
     *  keeps moving. */
    private fun paintEmergencyBroadcast(active: Boolean, expiresAtEpochMs: Long) {
        if (!::emergencyBroadcastBanner.isInitialized) return
        // ⚠ THE GATE BELONGS HERE AND NOWHERE ELSE. This function sets the notice VISIBLE and
        // the HUD tick calls it twice a second, so hiding it at any other site is undone within
        // half a second. AFTER the lateinit guard above, never before it. BASIC cannot have a
        // broadcast running — AppModeTransition cancels one before it tears the split down —
        // and this is the backstop that makes that true on screen as well as on the wire.
        // Display only: a broadcast found running is HIDDEN, not cancelled — cancelling from a
        // paint function would mix policy into display and fire the listener that called it.
        if (!AppMode.emergencyBroadcastAllowed(modePrefs())) {
            emergencyBroadcastBanner.visibility = View.GONE
            return
        }
        if (!active) {
            emergencyBroadcastBanner.visibility = View.GONE
            return
        }
        // ⚠ **THE NOTICE MUST NOT CLAIM VIDEO THAT IS NOT GOING OUT.**
        // The override only decides which CONNECTIONS may carry the video url; it cannot
        // conjure a url. With nothing streaming, `videoUrl` is null, `TakManager.videoFor`
        // returns null on every path, and not one channel gets a link — while the notice said
        // "video on all channels" to a pilot who had just pressed the button in an incident.
        // That is a false affirmative at the worst possible moment, and it is why starting a
        // broadcast now starts the stream too (see wireEmergencyBroadcastRow).
        //
        // This line is the BACKSTOP for every case that coupling cannot cover: the pilot stops
        // LIVE while a broadcast runs, the media server refuses the connection, the link drops.
        // It is read on the HUD tick, so it follows the stream rather than the button press.
        val streaming = VideoStreamerHolder.isRunning
        emergencyBroadcastBanner.text = if (streaming) {
            "EMERGENCY BROADCAST — video on all channels — ${remainingText(expiresAtEpochMs)}"
        } else {
            "EMERGENCY BROADCAST — NO VIDEO STREAM — ${remainingText(expiresAtEpochMs)}"
        }
        emergencyBroadcastBanner.setTextColor(ContextCompat.getColor(applicationContext,
            if (streaming) R.color.tp_state_emergency else R.color.tp_state_caution))
        emergencyBroadcastBanner.visibility = View.VISIBLE
    }

    /** mm:ss until [expiresAtEpochMs], never negative. */
    private fun remainingText(expiresAtEpochMs: Long): String {
        val remainingSec = ((expiresAtEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
        return "%d:%02d".format(remainingSec / 60, remainingSec % 60)
    }

    /**
     * The Emergency Broadcast rows of the LIVE long-press menu. Shown only when an Elevated
     * account (video channel) is configured — with no split there is nothing to override. No
     * confirm step (design decision): the long-press and a button that says what it does
     * are the friction. While one runs, the flight-screen notice is the control (tap renews,
     * touch-and-hold stops), and the same two actions are offered here.
     */
    private fun wireEmergencyBroadcastRow(view: View, dialog: AlertDialog) {
        val tm = TakManager.getInstance()
        // ⚠ EXPLICIT, NOT INHERITED, AND FIRST. In BASIC the split is already gone, so the line
        // below would hide these rows anyway — but that is a SIDE EFFECT of another subsystem's
        // state. And the Start button starts the STREAM before the broadcast
        // (startStreamForEmergencyBroadcast raises the screen-capture consent dialog), so a
        // gate placed around the broadcast call alone would still pop that dialog and start a
        // stream. Gated here, nothing below can run.
        if (!AppMode.emergencyBroadcastAllowed(modePrefs())) return // the rows stay GONE
        if (!tm.isVideoChannelConfigured) return // the rows stay GONE
        val caption = view.findViewById<TextView>(R.id.videoEmergencyCaption)
        val start = view.findViewById<android.widget.Button>(R.id.videoEmergencyButton)
        val stop = view.findViewById<android.widget.Button>(R.id.videoEmergencyStopButton)
        val active = tm.isEmergencyBroadcastActive
        caption.text = if (active) {
            "Emergency Broadcast is running — ${remainingText(tm.emergencyBroadcastExpiresAtEpochMs())} left."
        } else {
            "Emergency Broadcast: every channel gets the aircraft's video link for 15 minutes. " +
                "It starts the video stream if it is not running. The aircraft marker carries " +
                "the link, so the aircraft must be reporting its position."
        }
        start.text = if (active) "Renew for 15 min" else "Start Emergency Broadcast (15 min)"
        caption.visibility = View.VISIBLE
        start.visibility = View.VISIBLE
        stop.visibility = if (active) View.VISIBLE else View.GONE
        start.setOnClickListener {
            AppLog.i(TAG, "LIVE menu: Emergency Broadcast ${if (active) "renew" else "start"}")
            // ⚠ A BROADCAST WITHOUT A STREAM ADVERTISES NOTHING. The
            // override decides which connections may carry the video url; with nothing
            // streaming there is no url to carry, so every channel got exactly what it had
            // before and the notice said otherwise. Starting one therefore starts the stream.
            // A renew does not — a running broadcast already has whatever stream it has, and
            // re-requesting a projection mid-incident would put a system dialog over the
            // flight screen for no gain.
            if (!active && !startStreamForEmergencyBroadcast()) {
                // Refused, and the pilot has been told why. The override does NOT start: a
                // 15-minute authorisation that can never advertise anything is the fault this
                // whole change exists to remove.
                dialog.dismiss()
                return@setOnClickListener
            }
            tm.startOrRenewEmergencyBroadcast()
            dialog.dismiss()
        }
        stop.setOnClickListener {
            AppLog.i(TAG, "LIVE menu: Emergency Broadcast stop")
            tm.cancelEmergencyBroadcast()
            dialog.dismiss()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Mapbox.getInstance(applicationContext)
        super.onCreate(savedInstanceState)

        // OOM-restart guard. If the app is killed for memory in flight, Android restores the task
        // and recreates THIS activity directly — into a cold process where the DJI SDK was never
        // registered and no product connection was started (that only happens in Home). Coming up
        // here would show a dead aircraft link and a frozen HUD that looks live, which is worse
        // than an obvious failure. The tell: we were restored (savedInstanceState != null) yet
        // this process never passed through Home. Bounce there, which re-registers the SDK and
        // lets the pilot re-enter the flight screen deliberately.
        if (savedInstanceState != null && !TAKPilot2GoHomeActivity.visitedThisProcess) {
            AppLog.w(TAG, "restored into a cold process (OOM restart) — routing to Home")
            startActivity(
                Intent(this, TAKPilot2GoHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
            return
        }

        setContentView(R.layout.activity_takpilot2go_flight)
        AppLog.v(TAG, "onCreate")

        // THE SCREEN STAYS ON FOR THE WHOLE FLIGHT. Without this flag
        // Android's display timeout can blank the live FPV mid-sortie — and the TAK video
        // stream is a capture of this screen, so the team's feed blanks with it. The flag is
        // window-scoped: it clears itself when this activity goes away, so Home and
        // Pre-Flight keep the normal timeout.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // No native ActionBar on this screen (see AppTheme.NoActionBar in the manifest) — the
        // custom toolbar below is the only top bar. That theme also makes the status bar
        // transparent/overlaid, so go immersive here too, or the phone's status bar icons
        // would sit on top of the toolbar instead of the reclaimed dead space.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        fpvView = findViewById(R.id.flightFpvView)
        noVideoCover = findViewById(R.id.flightNoVideoCover)
        // FpvTextureView owns its own decode lifecycle (via SurfaceTextureListener); we just
        // hide the "waiting for video" placeholder once the first frame arrives.
        fpvView.onFirstFrame = { runOnUiThread { noVideoCover.visibility = View.GONE } }
        val crosshair = findViewById<CrosshairView>(R.id.flightCrosshair)
        crosshairView = crosshair
        // The reticle itself is the control. Tap re-aims (or places) the ONE quick marker;
        // long-press drops a NEW stationary Unknown marker.
        crosshair.onReticleTap = { onQuickDropTapped() }
        crosshair.onReticleLongPress = { onUnknownMarkerAction() }
        arOverlay = findViewById(R.id.flightArOverlay)
        // Both consume the same video rectangle: the AR projection has to agree with the
        // crosshair about where the centre of the image is, or a marker dropped at the
        // crosshair won't render under it — which is the whole self-test for this feature.
        fpvView.onVideoRectChanged = { rect ->
            runOnUiThread {
                crosshair.setVideoRect(rect)
                arOverlay.setVideoRect(rect)
                obstacles.setVideoRect(rect)
            }
        }
        // Tell the AR overlay how much of the video our own chrome covers, so an off-frame edge
        // arrow can't be parked underneath the toolbar or the HUD column where it's invisible —
        // the exact case a pilot needs most (aircraft directly overhead). Measured from the real
        // views after layout rather than hardcoded dp, so a toolbar/HUD change can't silently
        // break it. Re-read on every layout pass: rotation, or the h440dp map-size override,
        // both change these.
        val toolbarView = findViewById<View>(R.id.flightToolbar)
        val hudColumn = findViewById<View>(R.id.flightHudColumn)
        val column = findViewById<View>(R.id.flightToolbarActions)
        toolbarView.viewTreeObserver.addOnGlobalLayoutListener {
            // The obstacle radar takes the same top inset the AR overlay does — its
            // forward chevrons and distance label must never sit under the toolbar.
            obstacles.setTopInset(toolbarView.height.toFloat())
            // And the LEFT inset for the actions column: its right edge,
            // so the left-face label and an edge arrow land beside the column, not under it.
            val columnRight = column.x + column.width
            obstacles.setLeftInset(columnRight)
            arOverlay.setChromeInsets(
                top = toolbarView.height.toFloat(),
                left = columnRight,
                right = hudColumn.width.toFloat(),
            )
        }

        fpvNotice = findViewById(R.id.fpvNotice)
        flightDiagnostics = findViewById(R.id.flightDiagnostics)
        flightDiagnosticsRow = findViewById(R.id.flightDiagnosticsRow)
        flightDiagnosticsClose = findViewById(R.id.flightDiagnosticsClose)
        fpvAntennaArc = findViewById(R.id.fpvAntennaArc)
        obstacles = findViewById(R.id.flightObstacles)
        obstacles.update(DjiObstacleState.faces)
        DjiObstacleState.addChangeListener(obstacleListener)
        // Render whatever is ALREADY standing before subscribing — the callback is change-only,
        // so entering the flight screen with a fault already active would otherwise show nothing
        // until the fault happened to change.
        FlightWarnings.onDiagnostics(DjiSdkBridge.diagnostics)
        // Tap the banner to open it and read every fault; tap again to close it. The banner is
        // drawn above the crosshair, thus it takes the touch and a tap on a warning can never
        // fall through and drop a marker.
        flightDiagnostics.setOnClickListener {
            warningExpanded = !warningExpanded
            renderWarning()
        }
        // The ✕ closes the set of faults now on the banner. It is a separate view and not a
        // second gesture on the text, because the text's tap already means "expand" and a
        // long-press to close would be a third hidden gesture on one control.
        flightDiagnosticsClose.setOnClickListener {
            val closing = FlightWarnings.display()?.all?.let { FlightWarnings.dismissable(it) }
            // Lines the aircraft ranks BELOW warning stay closed for the flight (at night the
            // low-light advisory otherwise returns over the video on every flap). Warnings and
            // worse keep the one-set rule and come back if they return.
            val ranks = DjiSdkBridge.diagnosticRanks
            val advisories = closing.orEmpty().filter { line ->
                ranks[line]?.let { it < DjiSdkBridge.RANK_WARNING } == true
            }
            if (advisories.isNotEmpty()) {
                AppLog.i(TAG, "closing advisory line(s) for the session (rank below warning): " +
                    advisories.joinToString(" · ") { "$it [rank ${ranks[it]}]" })
                FlightWarnings.mute(advisories)
            }
            warningDismissedSignature = closing?.joinToString("\n")
            warningExpanded = false
            AppLog.i(TAG, "warning banner closed by pilot: $warningDismissedSignature")
            renderWarning()
        }
        renderWarning()
        DjiSdkBridge.onDiagnostics = { items ->
            FlightWarnings.onDiagnostics(items)
            runOnUiThread { renderWarning() }
        }
        fpvOverlayText = findViewById(R.id.fpvOverlayText)
        fpvFaaCeiling = findViewById(R.id.fpvFaaCeiling)
        fpvRthAltitude = findViewById(R.id.fpvRthAltitude)
        fpvHomeDistance = findViewById(R.id.fpvHomeDistance)
        fpvClock = findViewById(R.id.fpvClock)
        fpvGimbalPitch = findViewById(R.id.fpvGimbalPitch)
        fpvMediaMode = findViewById(R.id.fpvMediaMode)
        fpvMediaMode.onModeRequested = { wanted -> onMediaModeRequested(wanted) }
        // C1 is a second route to the IR toggle beside L3. The listen lives in
        // RcButtons (one owner); this screen is fed, and only while it is up.
        com.dji.sdk.sample.tak.RcButtons.onC1 = { runOnUiThread { if (irButton.isEnabled) onIrTapped() } }
        // The right shoulder's shutter, through its MSDK key — see RcButtons.onShutter for why
        // the Android key cannot be used. Stays in stills afterwards (by design): the
        // media switch shows the mode and REC records from any mode.
        com.dji.sdk.sample.tak.RcButtons.onShutter = { runOnUiThread { onShootPhotoTapped(stayInStills = true) } }
        // Every still the camera finishes gets a notice, whoever took it (the firmware says
        // nothing on screen with this application in front).
        TakBridgeHolder.onStillTaken = { showNotice("Photo saved to aircraft SD card") }
        com.dji.sdk.sample.tak.RcButtons.arm()
        toolbarBattery = findViewById(R.id.toolbarBattery)
        toolbarGps = findViewById(R.id.toolbarGps)
        toolbarGpsIcon = findViewById(R.id.toolbarGpsIcon)
        toolbarTakDot = findViewById(R.id.toolbarTakDot)
        toolbarSignal = findViewById(R.id.toolbarSignal)
        toolbarSignalText = findViewById(R.id.toolbarSignalText)

        emergencyBroadcastBanner = findViewById(R.id.flightEmergencyBroadcastBanner)
        // The running notice IS the control while a broadcast runs: a tap
        // adds 15 minutes, a touch-and-hold stops it. STARTING one is in the LIVE long-press
        // menu — see wireEmergencyBroadcastRow — and nothing for it sits in the actions column
        // (six pills, two widths: that is a rule).
        emergencyBroadcastBanner.setOnClickListener {
            // ⚠ RENEW ONLY. startOrRenewEmergencyBroadcast STARTS one when none is running, and
            // the GONE repaint after a cancel or an expiry arrives one main-loop message late
            // (the core posts it). A tap in that window started a FRESH broadcast that skipped
            // startStreamForEmergencyBroadcast — the stream coupling — and so could advertise
            // nothing while the notice claimed otherwise. Starting belongs to the LIVE menu.
            // The mode is read too, so a tap can never start one in BASIC whatever the view
            // state.
            val tm = TakManager.getInstance()
            if (!AppMode.emergencyBroadcastAllowed(modePrefs()) || !tm.isEmergencyBroadcastActive) {
                AppLog.i(TAG, "tap: Emergency Broadcast notice — nothing running, not started")
                return@setOnClickListener
            }
            AppLog.i(TAG, "tap: Emergency Broadcast notice — renew")
            tm.startOrRenewEmergencyBroadcast()
        }
        emergencyBroadcastBanner.setOnLongClickListener {
            AppLog.i(TAG, "touch-and-hold: Emergency Broadcast notice — stop")
            TakManager.getInstance().cancelEmergencyBroadcast()
            true
        }
        TakManager.getInstance().addEmergencyBroadcastListener(emergencyBroadcastListener)
        paintEmergencyBroadcast(
            TakManager.getInstance().isEmergencyBroadcastActive,
            TakManager.getInstance().emergencyBroadcastExpiresAtEpochMs(),
        )

        resourceMonitorRow = findViewById(R.id.flightResourceMonitorRow)
        // Drawn above the warning banner, which shares the bottom-left: the row is a debug
        // readout switched on from the Debug screen, and it is off in flight.
        resourceMonitorRow.bringToFront()
        resourceMonitorCells = listOf(
            findViewById(R.id.flightResSys),
            findViewById(R.id.flightResApp),
            findViewById(R.id.flightResCpu),
            findViewById(R.id.flightResGpu),
            findViewById(R.id.flightResTak),
        )
        // Read once at open, not per tick: the Debug switch cannot change while this screen is
        // in front, and re-reading a pref 2x a second for a debug overlay is silly.
        resourceMonitorRow.visibility =
            if (AppLog.resourceMonitor) View.VISIBLE else View.GONE

        mapContainer = findViewById(R.id.flightMapContainer)
        // ROUND THE MAP ITSELF, not just its frame.
        //
        // bg_map_outline is the container's FOREGROUND: it draws the border over the map but
        // clips nothing, so rounding that drawable alone leaves square tiles filling the corners
        // the border has curved away from. The clip has to happen here.
        //
        // ⚠ THIS WORKS ONLY BECAUSE THE MAP IS IN TEXTURE MODE. A SurfaceView-backed map is
        // composited separately and a parent's outline clip does not touch it. See the note on
        // mapbox_renderTextureMode in the layout — that flag is load-bearing for these corners.
        //
        // ⚠ THE CLIP RADIUS IS NOT THE FRAME'S RADIUS. A shape's stroke is centred on a path
        // inset by half its width, so a frame declaring hud_pill_radius presents an OUTER edge
        // of (radius + stroke/2), which curves away from the corner faster than a bare-radius
        // clip does — clipping at the bare radius leaves a sliver of map outside the border on
        // every corner. Clipping at (radius + stroke) puts the map's cut edge on the MIDDLE of
        // the stroke, giving half the stroke of tolerance on each side, because two
        // independently anti-aliased edges do not have to agree to the pixel.
        //
        // Both dimens are per-device, thus this is computed rather than written down as a third
        // number that would drift. The outline is rebuilt from the view's CURRENT size on every
        // call, so the map's size toggle needs nothing here.
        val mapFrameStroke = resources.getDimension(R.dimen.hud_text_outline_width)
        val mapClipRadius = resources.getDimension(R.dimen.hud_pill_radius) + mapFrameStroke
        mapContainer.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, mapClipRadius)
            }
        }
        mapContainer.clipToOutline = true
        // Bound here, ahead of the map gestures below, because the corner's double-tap handler
        // is attached to both views. setupVisionAssist() re-finds it harmlessly.
        visionAssistView = findViewById(R.id.flightVisionAssist)
        mapView = findViewById(R.id.flightMapView)
        mapView.onCreate(savedInstanceState)

        // WIDE/NEAR. Persisted, unlike the double-tap expansion: this is a standing preference
        // about how much ground the pilot wants to see, not a momentary look.
        mapWide = getSharedPreferences("takpilot2_tak", MODE_PRIVATE).getBoolean(KEY_MAP_WIDE, false)
        mapZoomButton = findViewById(R.id.flightMapZoomButton)
        mapZoomButton.setOnClickListener {
            mapWide = !mapWide
            getSharedPreferences("takpilot2_tak", MODE_PRIVATE).edit()
                .putBoolean(KEY_MAP_WIDE, mapWide).apply()
            AppLog.v(TAG, "tap: mini-map zoom -> ${if (mapWide) "WIDE" else "NEAR"}")
            applyMapZoom()
        }
        // Label the button from the RESTORED state, not from the layout's placeholder. Without
        // this the button read "WIDE" on every launch whatever the map was actually doing —
        // which is precisely the failure the state-not-action labelling exists to avoid, and it
        // was invisible in the log because the zoom itself was correct.
        applyMapZoom()

        // DOUBLE TAP TO EXPAND — detected on the VIEW, not through the map's click listener.
        //
        // The obvious approach does not work and it is worth writing down why. MapLibre delivers
        // map clicks from a standard Android GestureDetector's onSingleTapConfirmed. On a double
        // tap that callback is never delivered at all — the detector raises onDoubleTap instead.
        // So counting two map clicks inside a timeout can never see a second click, and the
        // gesture appears completely dead. It did.
        //
        // Our own detector runs ahead of the MapView's onTouchEvent and does not consume the
        // event (the listener returns false), so MapLibre still receives everything and marker
        // taps are untouched. It also means a marker hide stays immediate rather than waiting
        // out a double-tap window.
        val mapGestures = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    toggleMapExpanded()
                    return true
                }
            },
        )
        @Suppress("ClickableViewAccessibility")   // pass-through listener; never consumes
        mapView.setOnTouchListener { _, ev ->
            mapGestures.onTouchEvent(ev)
            false
        }
        // ⚠ THE SAME GESTURE ON THE VISION VIEW. The two share this corner, and the double tap
        // used to live only on the MapView — so with the corner expanded and vision showing, a
        // double tap reached a view that was GONE and nothing happened. The corner stayed large
        // until the pilot swapped back to the map to shrink it. The
        // expansion belongs to the CORNER, not to the map that happens to be in it.
        //
        // This one DOES consume: there is no MapLibre underneath to pass through to, and a
        // touch falling past it lands on live video and drops a marker.
        @Suppress("ClickableViewAccessibility")
        visionAssistView.setOnTouchListener { _, ev ->
            mapGestures.onTouchEvent(ev)
            true
        }
        mapView.getMapAsync { mapboxMap ->
            map = mapboxMap
            // Deliberately dead-simple, non-interactive mini-map (design decision):
            // no pan/zoom/rotate/tilt — north stays up (camera bearing is never set away from
            // its 0 default) and zoom stays pinned at the selected WIDE/NEAR level. The recenter in
            // updateHud() is the only thing that ever moves the camera.
            mapboxMap.uiSettings.setAllGesturesEnabled(false)
            // THE MAPBOX LOGO IS WRONG HERE, not merely in the way.
            //
            // MapLibre is a fork of Mapbox GL Native and kept its logo and info button switched
            // on by default. No Mapbox service is involved in this app: MaplibreStyle serves
            // OpenStreetMap raster tiles, ArcGIS World Imagery, or a custom tile URL.
            // So the badge credits a supplier of none of the data while OpenStreetMap, which
            // supplies most of it, went uncredited.
            //
            // Both are turned off and the real credit is given in the Field Guide instead. OSM's
            // guidance explicitly allows that for a display this small — a 130dp mini-map cannot
            // carry a legible attribution line and still be a map.
            mapboxMap.uiSettings.isLogoEnabled = false
            mapboxMap.uiSettings.isAttributionEnabled = false
            // Tapping an inbound contact locally hides it (stays on the server). Confirmed
            // independent of setAllGesturesEnabled(false) above — the locked mini-map's pan/
            // zoom/rotate stay off, only this explicit click hook is added.
            // Single tap hides an inbound marker. MapLibre routes this through its own
            // GestureDetector's onSingleTapConfirmed, which means it does NOT fire on the first
            // tap of a double tap — so this and the double-tap expand below are mutually
            // exclusive for free, and a marker hide stays immediate.
            mapboxMap.addOnMapClickListener { latLng ->
                val px = mapboxMap.projection.toScreenLocation(latLng)
                val hit = mapboxMap.queryRenderedFeatures(px, com.dji.sdk.sample.tak.TakMapMarkers.LAYER_ID)
                    .firstOrNull()
                val uid = hit?.getStringProperty(com.dji.sdk.sample.tak.TakMapMarkers.PROP_UID)
                if (uid != null) onInboundMarkerTapped(uid)
                uid != null
            }
            // Zoom + center immediately, before any GPS fix — otherwise the map sits at its
            // default continent-scale zoom until the drone locks GPS (the per-tick recenter that
            // also sets zoom is gated behind hasFix). Centered on DEFAULT_CENTER as a sensible
            // starting view; pans to the drone once a fix arrives.
            mapboxMap.cameraPosition = CameraPosition.Builder()
                .target(DEFAULT_CENTER)
                .zoom(currentMapZoom())
                .build()
            mapboxMap.setStyle(Style.Builder().fromJson(MaplibreStyle.selectedStyleJson(this))) { style ->
                // Home->aircraft line: added first so it renders underneath both markers.
                // Hidden until both a home point and a live GPS fix exist (see updateHud()).
                val emptyLine = LineString.fromLngLats(
                    listOf(Point.fromLngLat(0.0, 0.0), Point.fromLngLat(0.0, 0.0))
                )
                val lSource = GeoJsonSource(HOME_LINE_SOURCE_ID, emptyLine)
                style.addSource(lSource)
                homeLineSource = lSource
                val lLayer = LineLayer(HOME_LINE_LAYER_ID, HOME_LINE_SOURCE_ID).withProperties(
                    lineColor("#F44336"),
                    lineWidth(2.5f),
                    visibility(Property.NONE),
                )
                style.addLayer(lLayer)
                homeLineLayer = lLayer

                // Inbound TAK contacts/markers. Added here, before the aircraft and home
                // layers, so other operators' symbols always render UNDER our own aircraft
                // arrow and home pin — MapLibre draws layers in insertion order.
                //
                // ⚠ install() FIRST. It is what registers the TakManager listener and loads
                // the saved store; onMapReady alone is a canvas with nobody painting on it.
                // With every OTHER hook wired (onMapReady, tick, onMapDestroyed) and this one
                // call missing, the mini-map shows no inbound marker or contact EVER.
                // install() is idempotent, so calling it per map-ready is safe.
                com.dji.sdk.sample.tak.TakMapMarkers.install(applicationContext)
                com.dji.sdk.sample.tak.TakMapMarkers.onMapReady(style)

                style.addImage(AIRCRAFT_ICON_ID, decodeAircraftIcon())
                val source = GeoJsonSource(AIRCRAFT_SOURCE_ID, Point.fromLngLat(0.0, 0.0))
                style.addSource(source)
                aircraftSource = source
                val layer = SymbolLayer(AIRCRAFT_LAYER_ID, AIRCRAFT_SOURCE_ID).withProperties(
                    iconImage(AIRCRAFT_ICON_ID),
                    iconSize(1.0f),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                )
                style.addLayer(layer)
                aircraftLayer = layer

                // Home-point marker: hidden until DroneTakBridge reports a home location.
                style.addImage(HOME_ICON_ID, decodeHomeIcon())
                val hSource = GeoJsonSource(HOME_SOURCE_ID, Point.fromLngLat(0.0, 0.0))
                style.addSource(hSource)
                homeSource = hSource
                val hLayer = SymbolLayer(HOME_LAYER_ID, HOME_SOURCE_ID).withProperties(
                    iconImage(HOME_ICON_ID),
                    iconSize(1.0f),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    visibility(Property.NONE),
                )
                style.addLayer(hLayer)
                homeLayer = hLayer
            }
        }

        findViewById<ImageButton>(R.id.flightBackButton).setOnClickListener {
            AppLog.v(TAG, "tap: menu/back (leaving flight screen)")
            finish()
        }

        recordToggle = findViewById(R.id.flightRecordButton)
        recordToggle.setOnClickListener { onRecordToggleTapped() }

        rthButton = findViewById(R.id.flightRthButton)
        rthButton.setOnClickListener { onRthTapped() }
        rthButton.setOnLongClickListener { AppLog.v(TAG, "long-press: RTH — menu"); rthMenu(); true }

        findViewById<View>(R.id.toolbarTakButton).setOnClickListener {
            AppLog.v(TAG, "tap: TAK connection toggle")
            com.dji.sdk.sample.tak.TakAutoConnect.toggle(applicationContext) { _, msg ->
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
            }
        }

        // Same long-press idiom as the AR button and drop-pin: the short press does the common
        // thing, the long press opens what belongs to it.
        findViewById<View>(R.id.toolbarTakButton).setOnLongClickListener {
            AppLog.v(TAG, "long-press: TAK channels")
            onTakChannelsTapped()
            true
        }

        // VIDEO RE-SYNC IS NOT ON THE ACTION BAR. Re-sync is a rare
        // recovery action, not a per-flight one, so it is a long-press on the image it
        // repairs — where a pilot looking at a frozen picture will reach.
        fpvView.setOnLongClickListener {
            AppLog.v(TAG, "long-press: Video Re-Sync")
            fpvView.requestResync()
            Toast.makeText(this, "Re-syncing video…", Toast.LENGTH_SHORT).show()
            true
        }

        irButton = findViewById(R.id.flightIrButton)
        irButton.setOnClickListener { onIrTapped() }
        irPaletteButton = findViewById(R.id.flightIrPaletteButton)
        irPaletteButton.setOnClickListener { onIrPaletteTapped() }

        lightsButton = findViewById(R.id.flightLightsButton)

        // TAP = NAVIGATION LIGHTS, TOUCH AND HOLD = BEACON. Not one combined toggle: the two
        // do different jobs in the air, so a pilot must be able to kill one without losing
        // the other. The AIRCRAFT's state decides each direction, never a local flag — see
        // AircraftLights.
        lightsButton.setOnClickListener {
            val on = AircraftLights.motorLedsOn == true
            AppLog.v(TAG, "tap: motor LEDs (currently on=$on)")
            lightsButton.isEnabled = false
            AircraftLights.setMotorLeds(!on) { confirmed ->
                runOnUiThread {
                    lightsButton.isEnabled = true
                    renderLightsButton()
                    if (!confirmed) {
                        Toast.makeText(this, "The aircraft did not change the lights.",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        lightsButton.setOnLongClickListener {
            val on = AircraftLights.beaconOn == true
            AppLog.v(TAG, "long-press: beacon (currently on=$on)")
            lightsButton.isEnabled = false
            AircraftLights.setBeacon(!on) { confirmed ->
                runOnUiThread {
                    lightsButton.isEnabled = true
                    renderLightsButton()
                    // ALWAYS ANNOUNCED, unlike the tap. The beacon is on the aircraft, not on
                    // this screen, and it has no readout here (a state dot on the pill was
                    // tried and rejected as clutter) — so this toast is the pilot's
                    // only confirmation that a hidden gesture did anything.
                    Toast.makeText(this,
                        if (!confirmed) "The aircraft did not change the beacon."
                        else if (AircraftLights.beaconOn == true) "Beacon ON" else "Beacon OFF",
                        Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        AircraftLights.refresh { runOnUiThread { renderLightsButton() } }

        // The accessory panel (L2). Bound now, opened only on the button — and it reads the
        // aircraft on every open, so nothing is asked of the AL1 or the AS1 until a pilot wants
        // them.
        setupAccessoryPanel()
        setupVisionAssist()
        // The pill is derived from the focal length now, so it repaints when THAT moves.
        com.dji.sdk.sample.tak.CameraFov.onFovChanged = { runOnUiThread { renderZoomPill() } }
        // The accessory state is a PUSH feed. Without this the panel only ever showed what was
        // true at the moment it was opened, and a light switched from DJI Pilot 2 — or by our own
        // write landing after its callback — never reached the screen.
        AircraftAccessories.onChanged = { renderAccessoryPanel() }

        zoomButton = findViewById(R.id.flightZoomButton)
        zoomButton.setOnClickListener { onZoomTapped() }

        // Load the calibrated FOV before the overlay draws anything with it.
        com.dji.sdk.sample.tak.ArSettings.loadFov(this)
        com.dji.sdk.sample.tak.ArSettings.loadAimOffsets(this)

        arButton = findViewById(R.id.flightArButton)
        arButton.setOnClickListener { onArToggleTapped() }
        // Same long-press idiom as RTH (reset home) and drop-pin (markers list).
        arButton.setOnLongClickListener { onArOptionsTapped(); true }
        // ON WHEN THE SCREEN OPENS. Started here rather than in onResume deliberately:
        // onDestroy stops the overlay, thus each entry to the flight screen brings it up on,
        // while a mere pause — a dialog, the Field Guide — leaves it as the pilot set it.
        // Turning it off and returning to the screen does bring it back; that is a deliberate
        // choice of "always on" over "remember my last setting".
        arOverlay.start()
        refreshArButton()

        findViewById<ImageButton>(R.id.flightDropPinButton).setOnClickListener { onDropPinTapped() }
        // Long-press the drop button to manage already-dropped pins (move/rename/retype/
        // re-send/delete) — no map interaction needed, consistent with the locked mini-map.
        findViewById<ImageButton>(R.id.flightDropPinButton).setOnLongClickListener {
            onMarkersListTapped(); true
        }
        // TakDropMarkers has no Context of its own for user-facing feedback; this screen owns
        // the toasts. Cleared in onDestroy so a dead Activity is never toasted through.
        TakDropMarkers.ui = object : TakDropMarkers.Ui {
            override fun toast(msg: String) {
                runOnUiThread { Toast.makeText(this@TAKPilot2GoFlightActivity, msg, Toast.LENGTH_SHORT).show() }
            }
        }

        // NO SHUTTER PILL. The controller's own shutter button takes the still,
        // so [flightShootPhotoButton] stays null for the life of the screen and [setShutterBusy]
        // is a no-op. The photo sequence below is kept whole and working for the day a caller
        // needs it again — a marker with a still attached is the obvious one.

        liveToggle = findViewById(R.id.flightStreamButton)
        liveToggle.setOnClickListener { onLiveToggleTapped() }
        // Long-press: the video-quality tiers, changeable IN FLIGHT.
        // The quality profile is a live in-flight choice by design; changing it
        // must not mean leaving for Pre-Flight. Same long-press idiom as RTH, the TAK
        // badge, AR and drop-pin.
        liveToggle.setOnLongClickListener { onVideoQualityTapped(); true }
        // Refreshed whenever VideoStreamerHolder's state changes, from any trigger (this
        // button, a network-drop auto-reconnect, or the reconnect window giving up), not just
        // our own taps. RECONNECTING (amber, blinking) tells the pilot the app is retrying a
        // dropped link on its own — don't tap LIVE thinking it's off; tapping it now cancels
        // the retry instead of starting fresh.
        var lastLiveState: LiveToggleView.State? = null
        val refreshLiveToggle = Runnable {
            val state = when {
                VideoStreamerHolder.isRunning -> LiveToggleView.State.LIVE
                VideoStreamerHolder.isReconnecting -> LiveToggleView.State.RECONNECTING
                else -> LiveToggleView.State.OFF
            }
            // Edge-triggered: the holder can notify repeatedly for the same state (every
            // reconnect attempt), and logging each one would spam the file during a long
            // network outage — only the actual transitions are interesting.
            if (state != lastLiveState) {
                AppLog.i(TAG, "LIVE pill state -> $state")
                lastLiveState = state
            }
            liveToggle.setState(state)
        }
        VideoStreamerHolder.onStateChanged = refreshLiveToggle
        refreshLiveToggle.run()

        // Exposure control — the camera's exposure mode is forced to shutter-priority +
        // auto-ISO on connect (see ExposureController + DroneTakBridge); this slider biases it
        // brighter/darker (-2..+2 EV). Live ISO/shutter readout is filled in updateHud().
        val evSlider = findViewById<EvSliderView>(R.id.evSlider)
        evSlider.steps = ExposureController.sliderMax
        evSlider.index = ExposureController.savedSliderIndex(this)
        evSlider.onIndexChanged = { idx, fromUser ->
            if (fromUser) {
                // v() not i(): a slider drag fires this on every step, so keep it in the
                // verbose tier where it won't flood a Standard-level capture.
                AppLog.v(TAG, "EV slider -> ${ExposureController.labelAt(idx)} (index $idx)")
                ExposureController.setEvAt(applicationContext, idx) {}
            }
        }
    }

    /** The in-flight video-quality picker, over
     *  [com.dji.sdk.sample.tak.StreamProfile]. Selecting a tier saves it and,
     *  when the stream is live, restarts the push at the new profile with no permission
     *  dialog (the service reuses its projection — see the Android-14 note there). */
    private fun onVideoQualityTapped() {
        AppLog.v(TAG, "long-press: video quality")
        val prefs = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        val current = com.dji.sdk.sample.tak.StreamProfile
            .fromPref(prefs.getString("video_profile", null))

        val view = layoutInflater.inflate(R.layout.dialog_video_quality, null)
        val group = view.findViewById<android.widget.RadioGroup>(R.id.videoQualityGroup)

        // Built from the enum, not from XML — same reason as the AR category rows.
        val ids = com.dji.sdk.sample.tak.StreamProfile.values()
            .associateWith { profile ->
                val button = layoutInflater.inflate(R.layout.row_video_quality, group, false)
                    as android.widget.RadioButton
                button.id = View.generateViewId()
                button.text = profile.label
                group.addView(button)
                button.id
            }
        group.check(ids.getValue(current))
        group.setOnCheckedChangeListener { _, checkedId ->
            val chosen = ids.entries.firstOrNull { it.value == checkedId }?.key
                ?: return@setOnCheckedChangeListener
            if (chosen == current) return@setOnCheckedChangeListener
            // ⚠ THE SLOT TOO, NOT ONLY THE PLAIN KEY. This wrote video_profile alone, and the
            // mirror rewrites that key FROM THE ACTIVE SLOT — so an in-flight quality change
            // silently reverted at the next Pre-Flight visit, and with one mirror now, at any
            // mirror. The slot is the source; the plain key is written as well so a restart
            // below reads the new value at once without waiting for a mirror.
            val slot = if (prefs.getInt(
                    com.dji.sdk.sample.tak.VideoServersActivity.KEY_V_ACTIVE_SLOT, 1) == 2) 2 else 1
            prefs.edit()
                .putString(com.dji.sdk.sample.tak.VideoServersActivity.vKey(slot, "profile"),
                    chosen.prefValue)
                .putString("video_profile", chosen.prefValue)
                .apply()
            AppLog.i(TAG, "video quality -> ${chosen.label}")
            if (com.dji.sdk.sample.tak.VideoStreamerHolder.isActive) {
                com.dji.sdk.sample.tak.ScreenCaptureService.restart(applicationContext)
                Toast.makeText(this, "Video quality: ${chosen.label} — restarting stream",
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Video quality: ${chosen.label}", Toast.LENGTH_SHORT).show()
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Video Quality")
            .setView(view)
            .setPositiveButton("Done", null)
            .create()
        wireEmergencyBroadcastRow(view, dialog)
        dialog.show()
    }

    private fun onLiveToggleTapped() {
        if (VideoStreamerHolder.isActive) {
            AppLog.i(TAG, "tap: LIVE — stopping active stream " +
                "(running=${VideoStreamerHolder.isRunning}, reconnecting=${VideoStreamerHolder.isReconnecting})")
            VideoStreamerHolder.stop()
            Toast.makeText(this, "Video stream stopped", Toast.LENGTH_SHORT).show()
            return
        }
        // Guard on config before prompting for anything.
        val p = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        if ((p.getString("video_host", "") ?: "").isEmpty() ||
            (p.getString("video_streamid", "") ?: "").isEmpty()) {
            AppLog.w(TAG, "tap: LIVE ignored — video server not configured in Pre-Flight Setup")
            Toast.makeText(this, "Set up the video server in Pre-Flight Setup first", Toast.LENGTH_SHORT).show()
            return
        }
        // A saved "original" (a legacy passthrough profile) used to branch to a projection-less
        // start here. This app has no passthrough path, so that branch could only ever fail,
        // and it left the holder "active" — the next tap then read as STOP and the pilot was
        // stuck alternating two toasts, never streaming. Every profile now takes the
        // screen-capture route; VideoStreamerHolder maps the legacy value onto "standard".
        val profile = p.getString("video_profile", "standard") ?: "standard"
        // Transcode profile → screen-capture stream: request the one-time MediaProjection
        // permission. onActivityResult starts the foreground service, which starts the stream.
        AppLog.i(TAG, "tap: LIVE — requesting screen-capture permission (profile=$profile)")
        val mpm = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        Toast.makeText(this, "Starting screen stream…", Toast.LENGTH_SHORT).show()
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                AppLog.i(TAG, "screen-capture permission GRANTED — starting ScreenCaptureService")
                com.dji.sdk.sample.tak.ScreenCaptureService.start(this, resultCode, data)
            } else {
                AppLog.w(TAG, "screen-capture permission DENIED (resultCode=$resultCode) — no stream started")
                // ⚠ A DENIAL TAKES THE OVERRIDE DOWN WITH IT. Without this the pilot refuses
                // the system dialog and is left with a 15-minute notice promising video on
                // every channel and no stream to put on them — the state this change exists to
                // make impossible.
                if (projectionRequestedForEmergency &&
                    TakManager.getInstance().isEmergencyBroadcastActive
                ) {
                    AppLog.w(TAG, "Emergency Broadcast cancelled — screen capture was refused")
                    TakManager.getInstance().cancelEmergencyBroadcast()
                    showNotice("Screen capture refused — Emergency Broadcast stopped", refused = true)
                } else {
                    Toast.makeText(this, "Screen capture permission denied — no stream started",
                        Toast.LENGTH_LONG).show()
                }
            }
            projectionRequestedForEmergency = false
        }
    }

    /** Touch RTH: confirms before sending the aircraft home. During a return it opens the RTH
     *  menu instead, where Cancel Return is — see [rthMenu]. */
    private fun onRthTapped() {
        AppLog.v(TAG, "tap: RTH")
        if (!DjiSdkBridge.isProductConnected) {
            AppLog.w(TAG, "RTH ignored — aircraft not connected")
            Toast.makeText(this, "Aircraft not connected", Toast.LENGTH_SHORT).show()
            return
        }
        // ⚠ A TOUCH DURING A RETURN USED TO CANCEL IT AT ONCE, with nothing on screen to say a
        // touch would do that. A pilot
        // touching RTH to make sure the aircraft IS returning stopped the return instead. Now it
        // opens the menu, and the cancel is the one deliberate button there.
        if (TakBridgeHolder.hud()?.isGoingHome == true) {
            AppLog.i(TAG, "RTH touched during a return — menu")
            rthMenu()
            return
        }
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Return to Home")
            .setMessage("Send the aircraft home now?")
            .setPositiveButton("Return Home") { _, _ ->
                AppLog.i(TAG, "RTH confirmed — sending KeyStartGoHome")
                performAction(FlightControllerKey.KeyStartGoHome, "Returning home", "RTH failed")
            }
            .setNegativeButton("Cancel") { _, _ -> AppLog.i(TAG, "RTH cancelled at confirm dialog") }
            .show()
    }

    /**
     * The RTH menu (touch and hold RTH, or touch it during a return): cancel a return that is
     * running, or move the home point.
     *
     * ⚠ **THE AIRCRAFT CAN RETURN HOME ON ITS OWN** (low battery, signal lost), and the pilot
     * must be able to stop it from the screen they fly from. Cancel is also how the pilot gets
     * the sticks back.
     *
     * ⚠ **NO CONFIRMATION ON CANCEL** (design decision): it is the UNDO of a
     * return, the pilot is busy with the aircraft while reading it, and choosing it from this
     * menu is already a deliberate act.
     *
     * ⚠ **AN ITEM IS OFFERED ONLY WHEN IT APPLIES.** Cancel Return shows only while the aircraft
     * reports GO_HOME. A menu that offers to cancel something that is not running teaches a
     * pilot that the menu does not mean what it says.
     */
    private fun rthMenu() {
        val returning = TakBridgeHolder.hud()?.isGoingHome == true
        val b = AlertDialog.Builder(this, R.style.TakDialogTheme).setTitle("Return to Home")
        b.setMessage(if (returning) "The aircraft is returning home." else null)
        if (returning && DjiSdkBridge.isProductConnected) {
            b.setPositiveButton("Cancel Return") { _, _ -> cancelReturn() }
        }
        b.setNeutralButton("Reset Home Point…") { _, _ -> onRthLongPressed() }
        b.setNegativeButton("Close", null)
        b.show()
    }

    /**
     * Stops a return, then CHECKS THE AIRCRAFT AGREED. `onSuccess` means the
     * command was taken, not that the aircraft stopped: after [RTH_CANCEL_CONFIRM_MS] the flight
     * mode decides what the pilot is told. On the notice line, not a Toast — the flight screen
     * is the TAK video feed and a Toast is not in the capture.
     */
    private fun cancelReturn() {
        AppLog.w(TAG, "CANCEL RETURN requested by the pilot — sending KeyStopGoHome")
        showNotice("Cancelling the return")
        runCatching {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(FlightControllerKey.KeyStopGoHome),
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(t: EmptyMsg?) { AppLog.i(TAG, "KeyStopGoHome: accepted") }
                    override fun onFailure(error: IDJIError) {
                        AppLog.e(TAG, "KeyStopGoHome FAILED: ${describeError(error)}")
                        runOnUiThread { showNotice("The aircraft refused to stop the return", refused = true) }
                    }
                },
            )
        }.onFailure { AppLog.e(TAG, "KeyStopGoHome threw: ${it.message}") }
        handler.postDelayed({
            if (TakBridgeHolder.hud()?.isGoingHome == true) {
                AppLog.e(TAG, "RETURN DID NOT STOP — still GO_HOME ${RTH_CANCEL_CONFIRM_MS}ms after KeyStopGoHome")
                showNotice("The return did not stop", refused = true)
            } else {
                AppLog.i(TAG, "return cancelled")
                showNotice("Return cancelled. You have the aircraft.")
            }
        }, RTH_CANCEL_CONFIRM_MS)
    }

    /** Reset Home Point (from the RTH menu): reset the aircraft's home point to the pilot's current position (the
     *  phone's GPS — RC-N1 has no GPS of its own, so the phone standing in for "the
     *  controller's location" is the only sensible reading of that). Useful when the pilot
     *  has walked/driven somewhere else since the aircraft auto-set home at takeoff.
     *  Confirmed first — this changes where RTH sends the aircraft, so a stale/bad GPS fix
     *  here is a real safety concern, unlike RTH-cancel which is always safe. */
    private fun onRthLongPressed() {
        AppLog.v(TAG, "RTH menu: reset home point")
        if (!DjiSdkBridge.isProductConnected) {
            AppLog.w(TAG, "reset home point ignored — aircraft not connected")
            Toast.makeText(this, "Aircraft not connected", Toast.LENGTH_SHORT).show()
            return
        }
        // Via OperatorLocation, not getLastKnownLocation. Two reasons, and the second is the
        // safety one: that method reads a cache nothing fills unless an app has requested
        // updates, so on a flying phone it returned null for ever; and the cache has NO expiry,
        // so it can hand back a fix from days ago at wherever the phone last saw sky.
        // OperatorLocation issues the real request and refuses a stale seed — which matters
        // here more than anywhere, because this value decides where RTH flies the aircraft.
        OperatorLocation.start(this)
        val loc = OperatorLocation.latest
        if (loc == null) {
            AppLog.w(TAG, "reset home point aborted — no phone GPS fix from GPS/NETWORK providers")
            Toast.makeText(this, "No phone GPS fix available", Toast.LENGTH_SHORT).show()
            return
        }
        AppLog.i(TAG, "reset home point: phone fix ${"%.6f, %.6f".format(loc.latitude, loc.longitude)} " +
            "(provider=${loc.provider}, age=${(System.currentTimeMillis() - loc.time) / 1000}s, acc=${loc.accuracy}m)")
        // Destructive variant (red accent), matching the marker Delete / Clear All confirms:
        // this doesn't delete anything, but it changes where RTH will fly the aircraft, and a
        // stale phone fix here is a genuine safety problem — the same "read this before you
        // tap" signal the rest of the app's red confirms carry.
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Reset Home Point")
            .setMessage("Set the aircraft's home point to your current location " +
                "(%.6f, %.6f)? This changes where Return to Home will send it.".format(loc.latitude, loc.longitude))
            .setPositiveButton("Set Home Here") { _, _ ->
                AppLog.i(TAG, "reset home point confirmed — setting KeyHomeLocation")
                KeyManager.getInstance().setValue(
                    KeyTools.createKey(FlightControllerKey.KeyHomeLocation),
                    LocationCoordinate2D(loc.latitude, loc.longitude),
                    toastResultCallback("Home point updated", "Set home failed"),
                )
            }
            .setNegativeButton("Cancel") { _, _ -> AppLog.i(TAG, "reset home point cancelled at confirm dialog") }
            .show()
    }

    /** MSDK v5 action-key runner: a toast on the result, logged either way. */
    private fun performAction(
        keyInfo: DJIActionKeyInfo<EmptyMsg, EmptyMsg>,
        successMsg: String,
        failurePrefix: String,
    ) {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(keyInfo),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) {
                    AppLog.i(TAG, "$successMsg -> OK")
                    runOnUiThread { Toast.makeText(this@TAKPilot2GoFlightActivity, successMsg, Toast.LENGTH_SHORT).show() }
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.i(TAG, "$successMsg -> ${describeError(error)}")
                    runOnUiThread {
                        Toast.makeText(this@TAKPilot2GoFlightActivity,
                            "$failurePrefix: ${describeError(error)}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    private fun toastResultCallback(successMsg: String, failurePrefix: String) =
        object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                AppLog.i(TAG, "$successMsg -> OK")
                runOnUiThread { Toast.makeText(this@TAKPilot2GoFlightActivity, successMsg, Toast.LENGTH_SHORT).show() }
            }

            override fun onFailure(error: IDJIError) {
                AppLog.i(TAG, "$successMsg -> ${describeError(error)}")
                runOnUiThread {
                    Toast.makeText(this@TAKPilot2GoFlightActivity,
                        "$failurePrefix: ${describeError(error)}", Toast.LENGTH_SHORT).show()
                }
            }
        }

    /** Tapping while already recording stops it; otherwise switches the camera to video mode
     *  and starts recording — no confirmation needed, unlike RTH, since recording is easily
     *  reversible and not flight-safety-critical.
     *
     *  The Mini 2 (and other recent aircraft) reject the legacy `setMode(RECORD_VIDEO)` with
     *  "not supported by the current firmware version" — they use "flat camera mode" instead,
     *  so we switch via `setFlatMode(VIDEO_NORMAL)` when the camera reports it's supported,
     *  falling back to the legacy call for older aircraft. */
    private fun onRecordToggleTapped() {
        AppLog.v(REC_TAG, "tap: REC")
        if (!DjiSdkBridge.isProductConnected) {
            AppLog.w(REC_TAG, "record ignored — aircraft not connected")
            Toast.makeText(this, "Aircraft not connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (TakBridgeHolder.hud()?.isRecording == true) {
            AppLog.i(REC_TAG, "already recording — sending KeyStopRecord")
            performRecordAction(CameraKey.KeyStopRecord, "Recording stopped", "Stop failed", "stopRecord")
            return
        }
        // ⚠ REC RECORDS FROM ANY MODE: the pill puts the
        // camera in video if it is not there, starts, and leaves it in video. The pill always
        // reads REC; the media switch names the mode.
        if (TakBridgeHolder.hud()?.cameraMode == CameraMode.VIDEO_NORMAL) {
            AppLog.i(REC_TAG, "camera reports VIDEO_NORMAL — starting")
            performRecordAction(CameraKey.KeyStartRecord, "Recording started", "Start failed", "startRecord")
            return
        }
        AppLog.i(REC_TAG, "starting recording — switching to VIDEO_NORMAL first")
        KeyManager.getInstance().setValue(
            KeyTools.createKey(CameraKey.KeyCameraMode, MAIN_CAM),
            CameraMode.VIDEO_NORMAL,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(REC_TAG, "set video mode result: OK — waiting for the camera to report it")
                    // ⚠ THE ACK IS NOT READINESS. Measured on another airframe: a start 2 ms
                    // and 800 ms after the ack was silently ignored, and a fixed 2000 ms wait
                    // was refused. So: wait for the LISTENED mode to say VIDEO_NORMAL (poll
                    // 250 ms, limit 6 s), settle, then start. The 1200 ms settle is that
                    // airframe's figure until the M4TD is measured; the log carries the wait
                    // so it can be.
                    runOnUiThread { startRecordWhenCameraReportsVideo(android.os.SystemClock.elapsedRealtime()) }
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.i(REC_TAG, "set video mode result: ${describeError(error)}")
                    runOnUiThread {
                        Toast.makeText(this@TAKPilot2GoFlightActivity,
                            "Couldn't switch to video mode: ${describeError(error)}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    /** Toggles the camera's digital zoom between 1x and 2x — the Mini 2 (and most MSDK
     *  aircraft without a zoom lens) only support pure digital crop-zoom, not optical/hybrid,
     *  so 1x/2x is a simple, broadly-compatible pair rather than trying to expose the
     *  aircraft's full (model-dependent) zoom range. Affects the actual encoded video feed,
     *  so it changes both on-screen FPV and whatever's going out over the RTSP push. */
    /**
     * FAA UASFM ceiling readout. Hidden entirely when the pilot hasn't downloaded any data, so
     * the feature costs nothing on screen if unused.
     *
     * **Advisory only.** UASFM shows what the FAA is likely to authorise, not what's authorised,
     * and the downloaded copy ages. Nothing here touches the aircraft's altitude limit.
     *
     * The ceiling is compared against [agl], which is terrain-corrected via DTED when coverage
     * allows (see `TerrainAgl`) — a UASFM ceiling is height above the ground under the aircraft,
     * so comparing it to a takeoff-relative altitude would misjudge the moment the aircraft
     * leaves the elevation it launched from. Without DTED coverage the comparison falls back to
     * the uncorrected figure, and the readout marks itself `~` so the pilot can see the warning
     * is only as good as flat ground.
     */
    /** Not persisted, deliberately: the flight screen always opens with a compact map. An
     *  expanded map covers the readouts, and inheriting that from a previous flight is not
     *  something a pilot asked for. */
    private var mapExpanded = false

    /**
     * WIDE shows the whole permitted flight area; NEAR is this screen's long-standing zoom.
     *
     * DEFAULTS TO NEAR: a new pilot gets a street-scale view of the aircraft and the near team,
     * and this screen's existing view is unchanged for anyone who never touches the button.
     */
    private var mapWide = false

    /**
     * Pushes [mapWide] to the map and relabels the button.
     *
     * The BUTTON SHOWS THE STATE, not the action — it reads WIDE when the map is wide. A button
     * labelled with what it will do next reads as a claim about what you are looking at, and on
     * a 130dp map with no scale bar there is nothing else to disambiguate it.
     */
    private fun applyMapZoom() {
        mapZoomButton.text = if (mapWide) "WIDE" else "NEAR"
        val m = map ?: return
        m.cameraPosition = CameraPosition.Builder()
            .target(m.cameraPosition.target)
            .zoom(currentMapZoom())
            .build()
    }

    private fun currentMapZoom(): Double = if (mapWide) MAP_ZOOM_WIDE else MAP_ZOOM_NEAR

    /**
     * Double-tap: the mini-map grows to twice its size, and back.
     *
     * It grows OVER the video and the readouts rather than pushing them. Width is free because
     * the HUD column is end-aligned; height comes from a negative top margin, so the extra size
     * goes upward across the readouts instead of down off the bottom of a 411dp-tall screen.
     * Elevation lifts it above its siblings so the readouts do not draw on top of it.
     *
     * THE ZOOM IS UNCHANGED. A bigger map at the same zoom shows four times the GROUND, which is
     * what a pilot double-tapping a mini-map wants. Magnifying the same ground would be the
     * WIDE/NEAR control's job, and this is not that.
     */
    private fun toggleMapExpanded() {
        val base = resources.getDimensionPixelSize(R.dimen.flight_map_size)
        mapExpanded = !mapExpanded
        val size = if (mapExpanded) base * 2 else base
        val lp = mapContainer.layoutParams as android.widget.LinearLayout.LayoutParams
        lp.width = size
        lp.height = size
        lp.topMargin = if (mapExpanded) -(size - base) else 0
        mapContainer.layoutParams = lp
        mapContainer.elevation = if (mapExpanded) 8f * resources.displayMetrics.density else 0f
        AppLog.v(TAG, "mini-map ${if (mapExpanded) "expanded" else "compact"} (${size}px)")
    }

    private var hudTickCount = 0

    /**
     * Fills the debug resource row, every fourth HUD tick — 2 seconds.
     *
     * Not every tick: snapshot() reads /proc and takes a binder IPC for PSS, and none of these
     * numbers move meaningfully in half a second. Skipped entirely when the row is hidden, which
     * is the default, so it costs nothing in normal flight.
     */
    private fun updateResourceRow() {
        if (resourceMonitorRow.visibility != View.VISIBLE) return
        hudTickCount++
        if (hudTickCount % 4 != 0) return
        runCatching {
            val segments = com.dji.sdk.sample.tak.ResourceMonitor.formattedSegments(applicationContext)
            segments.forEachIndexed { i, text -> resourceMonitorCells[i].text = text }
        }
    }

    private var lastResourceLogAt = 0L

    /**
     * One resource line every [RESOURCE_LOG_INTERVAL_MS] while the flight screen is up.
     *
     * On the HUD tick rather than a timer of its own, so it stops when the screen does. The
     * interval is long because this is a trend, not an instrument: the thing being watched is
     * whether the contact count and the memory figures CLIMB across a flight, which is what
     * precedes an OOM kill. A line every half second would bury that in noise
     * and rotate the log file out from under the evidence.
     */
    private fun logResourcesPeriodically() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastResourceLogAt < RESOURCE_LOG_INTERVAL_MS) return
        lastResourceLogAt = now
        runCatching {
            AppLog.i(RESOURCE_TAG,
                com.dji.sdk.sample.tak.ResourceMonitor.formattedLine(applicationContext))
        }
    }

    /** The one place the ceiling readout says "I do not know", so every unknown looks the same
     *  and none of them look like an answer. */
    private fun showFaaUnknown(text: String) {
        fpvFaaCeiling.visibility = View.VISIBLE
        fpvFaaCeiling.text = text
        fpvFaaCeiling.setTextColor(
            ContextCompat.getColor(applicationContext, R.color.tp_state_unknown))
    }

    private fun updateFaaCeiling(
        hud: com.dji.sdk.sample.tak.DroneTakBridge.Hud?,
        agl: com.dji.sdk.sample.tak.TerrainAgl.Reading,
    ) {
        // NEVER HIDDEN. This used to disappear entirely when no ceilings had been downloaded,
        // and an absent readout reads as "nothing to worry about" rather than "I do not know" —
        // the pilot cannot tell a 400 ft answer they have not been given from one that did not
        // need giving. Unknown is its own state, in its own colour (standing rule).
        if (!com.dji.sdk.sample.tak.UasfmIndex.hasCoverage(this)) {
            showFaaUnknown("FAA — no data downloaded")
            return
        }
        if (hud == null || !hud.hasFix) {
            showFaaUnknown("FAA — no fix")
            return
        }

        val row = com.dji.sdk.sample.tak.UasfmIndex.gridRowFor(hud.lat)
        val col = com.dji.sdk.sample.tak.UasfmIndex.gridColFor(hud.lon)
        if (row != lastFaaGridRow || col != lastFaaGridCol) {
            lastFaaGridRow = row
            lastFaaGridCol = col
            cachedFaaCeilingFt = com.dji.sdk.sample.tak.UasfmIndex.ceilingFtAt(this, hud.lat, hud.lon)
            cachedFaaWithinDownloadedArea =
                com.dji.sdk.sample.tak.UasfmIndex.isWithinDownloadedArea(this, hud.lat, hud.lon)
            AppLog.v(TAG, "FAA cell ($row,$col): ceiling=${cachedFaaCeilingFt ?: "none"} " +
                "withinDownloaded=$cachedFaaWithinDownloadedArea")
        }

        val aglFt = Units.metersToFeet(agl.meters)
        // Marks a ceiling judged against an uncorrected altitude — the comparison is only valid
        // over ground level with the takeoff point, and the pilot should know which they've got.
        val approx = if (agl.terrainCorrected) "" else "~"
        val ceiling = cachedFaaCeilingFt
        fpvFaaCeiling.visibility = View.VISIBLE
        when {
            // "AGL" is spelled out because the readout directly above this now shows MSL, and a
            // bare "FAA 200 ft" next to a "413 ft MSL" invites reading the ceiling as an MSL
            // figure. UASFM ceilings are always height above ground.
            ceiling != null -> {
                fpvFaaCeiling.text = "FAA $ceiling ft AGL$approx"
                fpvFaaCeiling.setTextColor(
                    if (aglFt > ceiling) ContextCompat.getColor(applicationContext, R.color.tp_btn_danger_dialog)
                    else android.graphics.Color.WHITE
                )
            }
            // Inside what was downloaded but in no cell: the FAA publishes no facility map
            // here, which means uncontrolled airspace and the plain Part 107 ceiling. Shown
            // grey and labelled so it never reads as "the facility map says 400".
            cachedFaaWithinDownloadedArea -> {
                val part107 = com.dji.sdk.sample.tak.UasfmIndex.PART_107_DEFAULT_CEILING_FT
                fpvFaaCeiling.text = "Class G · $part107 ft AGL$approx"
                fpvFaaCeiling.setTextColor(
                    if (aglFt > part107) ContextCompat.getColor(applicationContext, R.color.tp_btn_danger_dialog)
                    else ContextCompat.getColor(applicationContext, R.color.tp_text_secondary)
                )
            }
            // Outside the downloaded box entirely — we genuinely don't know. Amber, because
            // silently implying 400 ft here would be a guess dressed up as information.
            else -> showFaaUnknown("FAA — no data here")
        }
    }

    /**
     * THE CONTROLLER'S L BUTTONS:
     *
     *   L1 tap  — the quick (dynamic) marker: same function as touching the crosshair.
     *   L1 hold — a static Unknown marker: same function as holding the crosshair.
     *   L2      — the accessory panel: the AL1 light and the AS1 speaker.
     *   L3      — thermal on/off: same function as the IR pill.
     *   R1      — push-to-talk: same function as HOLD TO TALK in the accessory panel.
     *   R3      — swaps the bottom-right corner between the mini-map and the vision-assist
     *             view (the aircraft's obstacle-sensing cameras). No on-screen equivalent, so
     *             the corner carries an "R3" label — see [renderVisionAssistLabel].
     *
     * One button for both marker kinds, the same tap/hold split the crosshair itself has —
     * so L1 IS the crosshair, in button form. Each route ends in the SAME function as its
     * on-screen control; the hint chips on the screen's left edge
     * say what the buttons do, DJI Pilot 2's own idiom for these slots.
     *
     * ⚠ L2 IS THE ONE EXCEPTION TO THAT RULE: the accessory panel has no on-screen control,
     * so L2 is the only way to it. That is deliberate — the action bar has
     * overflowed its width before and a fourth pill was not worth the room. The consequence is
     * that the ◀ ACC chip is not a reminder here, it is the ONLY sign the panel exists, so it
     * must never be hidden, and the function is in the Field Guide for the same reason.
     *
     * The tap/hold split uses the framework's own tracking: startTracking() on the down,
     * onKeyLongPress for the hold, and the up only fires the tap when the long press has not
     * (isCanceled). No hand-rolled timers.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_F1 -> {
                event.startTracking()
                return true
            }
            android.view.KeyEvent.KEYCODE_F6 -> {
                // R3 — swap the corner between the mini-map and the vision-assist view.
                if (event.repeatCount == 0) {
                    AppLog.i(TAG, "controller button R3 — swap the corner view")
                    toggleVisionAssist()
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_F4 -> {
                // R1 IS THE SAME FUNCTION AS THE TALK BUTTON, held the same way. repeatCount
                // filters the auto-repeat the framework sends while a key is down; the channel
                // opens once and the key-up closes it.
                if (event.repeatCount == 0) {
                    AppLog.i(TAG, "controller button R1 — talk")
                    startTalking()
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_F2 -> {
                if (event.repeatCount == 0) {
                    AppLog.i(TAG, "controller button L2 — accessory panel")
                    toggleAccessoryPanel()
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_F3 -> {
                if (event.repeatCount == 0) {
                    AppLog.i(TAG, "controller button L3 — IR toggle")
                    if (irButton.isEnabled) onIrTapped()
                }
                return true
            }
            // THE SHOULDERS (measured on an M4TD). The left reaches
            // here as BUTTON_L1 (BTN_TL), the right as BUTTON_R1 (BTN_TR).
            //
            // LEFT = RECORD. In VIDEO the FIRMWARE starts and stops the recording on its own
            // (the REC pill followed KeyIsRecording with no app involvement), so the app stays
            // out of its way there — a handler would double the start or cancel the stop. In
            // PHOTO the firmware does nothing at all, and that is the gap:
            // the app takes the press through the same REC-from-any-mode path as the pill —
            // VIDEO_NORMAL, wait for the camera to report it, settle, start. One rule for the
            // pill and the button. Unknown mode counts as "not video": the path re-asserts it.
            android.view.KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (event.repeatCount == 0) {
                    val mode = TakBridgeHolder.hud()?.cameraMode
                    if (mode == CameraMode.VIDEO_NORMAL) {
                        AppLog.i(TAG, "controller LEFT shoulder — camera in VIDEO, the firmware records")
                    } else {
                        AppLog.i(TAG, "controller LEFT shoulder — camera in $mode, recording from any mode")
                        onRecordToggleTapped()
                    }
                }
                return true
            }
            // RIGHT = SHUTTER, AND THE APP CANNOT TAKE IT. The half-press (focus) and the full
            // press arrive as the SAME key — both BTN_TR in a key capture — so a
            // handler here would take a still on every focus. The firmware owns this button;
            // whether it shoots on a full press in PHOTO mode is the firmware's to decide. Logged.
            android.view.KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (event.repeatCount == 0) AppLog.i(TAG, "controller RIGHT shoulder (BUTTON_R1) — firmware owns it (half-press is the same key)")
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_F1) {
            AppLog.i(TAG, "controller button L1 held — static marker")
            onUnknownMarkerAction()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_F4) {
            AppLog.i(TAG, "controller button R1 released — talk ends")
            SpeakerTalk.stop()
            return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_F1) {
            // isCanceled is true when the long press already fired — the up then does nothing.
            if (!event.isCanceled) {
                AppLog.i(TAG, "controller button L1 — quick marker")
                onQuickDropTapped()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * THE ZOOM CAMERA IS THE ONLY VISIBLE-LIGHT SOURCE. Measured: the tele stream at 1x is
     * the same framing as the wide camera — DJI's ratio
     * scale is wide-referenced and the zoom stream covers 1x-28x continuously — so the wide
     * lens bought this app nothing but a second state to manage, and a set
     * of lens-crossing races to manage it with. Pilot 2 keeps its Wide for sensor-quality
     * reasons that matter to recording, not to a TAK observation stream.
     *
     * So: the DIAL is the zoom, firmware-wired, smooth, 1x to 28x, and the display follows.
     * THE PILL IS SNAP-TO-1X — the one thing a zoomed-in pilot actually wants a button for.
     */
    private fun onZoomTapped() {
        AppLog.v(TAG, "tap: snap to 1X (currently ${"%.1f".format(zoomRatio)}x)")
        if (!DjiSdkBridge.isProductConnected) {
            Toast.makeText(this, "Aircraft not connected", Toast.LENGTH_SHORT).show()
            return
        }
        KeyManager.getInstance().setValue(
            KeyTools.createCameraKey(
                CameraKey.KeyCameraZoomRatios, MAIN_CAM, CameraLensType.CAMERA_LENS_ZOOM),
            1.0,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    // The follow listener hears the echo and settles the display; nothing
                    // more to do here.
                    AppLog.i(TAG, "snap to 1X: OK")
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "snap to 1X refused: ${describeError(error)}")
                    runOnUiThread {
                        Toast.makeText(this@TAKPilot2GoFlightActivity,
                            "Zoom reset failed: ${describeError(error)}", Toast.LENGTH_SHORT).show()
                    }
                }
            })
    }

    /** The pill: the live value, lit while zoomed in so 1X reads at a glance. A tap snaps
     *  back to 1X — see onZoomTapped. */
    private fun renderZoomPill() {
        // ⚠ FROM THE FOCAL LENGTH, NOT FROM THE RATIO KEY. See CameraFov.lastF35Mm: the ratio
        // key's scale changed under us mid-flight and put "1.5X" on the pill while the camera
        // sat at its widest. The focal length is what actually sets the framing and it has never
        // disagreed with the picture. The ratio key is still followed for CHANGE EVENTS — it is
        // a fine trigger, just not a fine number.
        val shown = com.dji.sdk.sample.tak.CameraFov.displayRatio() ?: zoomRatio
        zoomButton.setBackgroundResource(
            if (shown > 1.05) R.drawable.bg_pill_active else R.drawable.bg_zoom_pill)
        zoomButton.text = ZoomLadder.label(shown)
    }

    /**
     * Reads the camera's OWN ratio and puts it on the pill.
     *
     * ⚠ THE RAMP IS NOT INSTANT. A lens switch leaves the camera moving toward the ratio it was
     * given, so a read taken the moment the switch returns catches it mid-travel. The read is
     * repeated until the value settles or the attempts run out — and the LAST answer is the one
     * displayed, because the camera is the authority on where it ended up.
     */
    private fun adoptZoomRatioFromCamera(attempt: Int = 1) {
        if (attempt > ZOOM_ADOPT_MAX_ATTEMPTS) return
        val key = KeyTools.createCameraKey(
            CameraKey.KeyCameraZoomRatios, MAIN_CAM, CameraLensType.CAMERA_LENS_ZOOM)
        KeyManager.getInstance().getValue(key,
            object : CommonCallbacks.CompletionCallbackWithParam<Double> {
                override fun onSuccess(value: Double?) {
                    if (value == null || value <= 0) return
                    runOnUiThread {
                        if (irOn || sourceSwitchPending) return@runOnUiThread
                        val settled = kotlin.math.abs(value - zoomRatio) < 0.01
                        if (!settled) {
                            AppLog.i(TAG, "zoom adopted from the camera: $value " +
                                "(pill was $zoomRatio)")
                            zoomRatio = value
                            renderZoomPill()
                            TakBridgeHolder.setZoomFactor(value)
                            com.dji.sdk.sample.tak.CameraFov.refresh(irOn, value)
                        }
                        // Keep asking while it is still moving; stop once two reads agree.
                        if (!settled) {
                            handler.postDelayed(
                                { adoptZoomRatioFromCamera(attempt + 1) }, ZOOM_ADOPT_RETRY_MS)
                        }
                    }
                }

                override fun onFailure(error: IDJIError) {
                    handler.postDelayed(
                        { adoptZoomRatioFromCamera(attempt + 1) }, ZOOM_ADOPT_RETRY_MS)
                }
            })
    }

    private fun onCameraZoomChanged(ratio: Double) {
        if (irOn || sourceSwitchPending) return
        if (kotlin.math.abs(ratio - zoomRatio) < 0.01) return
        zoomRatio = ratio
        renderZoomPill()
        fpvView.setDigitalCrop(1.0)
        TakBridgeHolder.setDigitalCrop(1.0)
        TakBridgeHolder.setZoomFactor(ratio)
        com.dji.sdk.sample.tak.CameraFov.refresh(irOn, ratio)
    }

    private fun switchSourceThenFinish(
        sourceKey: dji.sdk.keyvalue.key.DJIKey<CameraVideoStreamSourceType>,
        targetSource: CameraVideoStreamSourceType,
        gearHeld: Double,
        crop: Double,
    ) {
        KeyManager.getInstance().setValue(sourceKey, targetSource,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    val nowSource = runCatching {
                        KeyManager.getInstance().getValue(
                            sourceKey, CameraVideoStreamSourceType.UNKNOWN)
                    }.getOrNull()
                    AppLog.i(TAG, "zoom: stream source read-back = $nowSource")
                    runOnUiThread {
                        teleLive = nowSource == CameraVideoStreamSourceType.ZOOM_CAMERA
                        sourceSwitchPending = false
                        cameraStateSynced = true   // an entry migration counts as the adoption
                        // ⚠ THE COMMENT HERE USED TO CLAIM A READ-BACK THAT DID NOT EXIST. It
                        // said "the camera's answer, never the request" while assigning
                        // `gearHeld * crop` — the value we ASKED for. On this path the camera is
                        // still ramping from wherever it was left (7x, measured)
                        // and onCameraZoomChanged DISCARDS every ratio update while
                        // sourceSwitchPending is true, so nothing afterwards necessarily
                        // corrects it. That is how the pill came to read 1.5X over a 24mm
                        // picture: the pill
                        // was never showing a measured number in the first place.
                        //
                        // Provisional display so the pill is not blank, then ASK THE CAMERA.
                        zoomRatio = gearHeld * crop
                        renderZoomPill()
                        fpvView.setDigitalCrop(crop)
                        TakBridgeHolder.setDigitalCrop(crop)
                        TakBridgeHolder.setZoomFactor(zoomRatio)
                        com.dji.sdk.sample.tak.CameraFov.refresh(irOn, zoomRatio)
                        adoptZoomRatioFromCamera()
                    }
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "zoom: stream source switch refused: ${describeError(error)}")
                    runOnUiThread {
                        sourceSwitchPending = false
                        Toast.makeText(this@TAKPilot2GoFlightActivity,
                            "Could not switch lens: ${describeError(error)}",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            })
    }


    /** Shutter button: takes a single still photo, saved to the aircraft's SD card (not the
     *  phone) — same storage target as video recording. First cut of "quickpic" (a later
     *  version may drop a TAK marker with the image attached); for now this just captures the still.
     *  Switches to PHOTO_SINGLE flat mode to shoot, then restores VIDEO_NORMAL afterward so the
     *  live FPV feed (this screen's primary job) isn't left in photo mode.
     *
     *  Field-found: a bare `setFlatMode(VIDEO_NORMAL)` after the shoot left the feed
     *  dark and stuck (~ISO 800 · 1/640, EV slider dead) — the PHOTO_SINGLE round-trip resets
     *  the camera's exposure mode off PROGRAM, and nothing was re-forcing it back. Fix: restore
     *  through [ExposureController.applyDefaults], the same call the aircraft's initial connect
     *  uses — it does the VIDEO_NORMAL switch itself AND re-applies PROGRAM + the biased EV, so
     *  a photo can no longer leave the feed in a different exposure state than before it. */
    /**
     * Drop a TAK marker at whatever the camera is pointed at.
     *
     * The mini-map is locked (no pan/zoom, by design), so there is no tap-the-map
     * placement — [TakBridgeHolder.lookPoint] is the cursor, giving the DTED-terrain-corrected
     * ground intersection of the camera's line of sight. If that's unavailable (no GPS fix or
     * no gimbal attitude yet) the drop is refused outright: placing a marker at a plausible-
     * looking but wrong position is worse for the shared picture than not placing one.
     */
    /** Restyles a platform AlertDialog neutral button (Reset Numbering / Clear All Markers) as
     *  a compact red button: same red-fill/outline as the rest of the marker-dropper UI, but
     *  at roughly half the system default's height — the system button style's built-in
     *  min-height + vertical padding is sized for a full-width Material button, not a small
     *  in-line action, so both are stripped/shrunk here while leaving the font size untouched. */
    private fun styleRedButton(button: android.widget.Button) {
        button.setTextColor(android.graphics.Color.WHITE)
        button.setBackgroundResource(R.drawable.bg_button_red)
        button.setAllCaps(false)
        button.minHeight = 0
        button.minimumHeight = 0
        val vPad = (4 * resources.displayMetrics.density).toInt()
        button.setPadding(button.paddingLeft, vPad, button.paddingRight, vPad)
    }

    /**
     * AR overlay on/off. Off by default every time the flight screen opens — it draws over the
     * video, so it should be something the pilot switches on deliberately rather than something
     * they inherit from a previous flight and have to notice.
     */
    /**
     * Gimbal pitch readout + the crosshair's accuracy tint.
     *
     * Both are driven from the same value so the number and the reticle can't disagree. The
     * thresholds mark how much a marker drop can be trusted: ground error scales as
     * 1/sin^2(pitch), so steeper is dramatically better, and nothing else on screen tells the
     * pilot that. Two threshold pairs — WITH DTED coverage at the aircraft's current position
     * and WITHOUT — since the no-DTED flat-ground assumption stacks its own error on top of the
     * same geometric term; field-calibrated for both, see [CrosshairView]'s constants.
     */
    private fun updateGimbalPitch(hud: com.dji.sdk.sample.tak.DroneTakBridge.Hud?) {
        val pitch = hud?.gimbalPitch
        // Whether a marker dropped RIGHT NOW would get CameraSlantPoint's terrain-corrected
        // solve — the same question CameraSlantPoint.compute itself asks (DTED coverage at the
        // aircraft's OWN current position, not just whether any DTED is loaded anywhere).
        val dtedAvailable = hud != null &&
            com.dji.sdk.sample.tak.DtedIndex.elevationAt(this, hud.lat, hud.lon) != null
        crosshairView.setGimbalPitch(pitch, dtedAvailable)
        // Look-point distance and bearing at the reticle's lower-right. Null — and no
        // text — when the camera is at/above the horizon or telemetry is not ready;
        // Units.distance keeps the range in the HUD's imperial convention. The bearing is
        // the camera's own true bearing, the same model the SPI and a marker drop use, so
        // the reticle cannot disagree with them.
        crosshairView.setRangeText(
            TakBridgeHolder.lookRangeMeters()?.let { range ->
                val brg = TakBridgeHolder.cameraPose()?.bearingDeg
                if (brg == null) Units.distance(range)
                else "%s  %03.0f°T".format(java.util.Locale.US, Units.distance(range), brg)
            })
        if (pitch == null) {
            fpvGimbalPitch.text = "GIMBAL —"
            fpvGimbalPitch.setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
            return
        }
        // Sign dropped in favour of an explicit DOWN/UP word: "-20" reads as a negative number
        // rather than as a look angle, and down is the only direction that matters for drops.
        val label = when {
            pitch <= -1.0 -> "GIMBAL %.0f° DOWN".format(-pitch)
            pitch >= 1.0 -> "GIMBAL %.0f° UP".format(pitch)
            else -> "GIMBAL LEVEL"
        }
        fpvGimbalPitch.text = label
        // Shared classifier, not a second copy of the thresholds — these two displays drifting
        // apart would be worse than having only one of them.
        fpvGimbalPitch.setTextColor(CrosshairView.accuracyColorFor(this, pitch, dtedAvailable))
    }

    private fun onArToggleTapped() {
        if (arOverlay.isRunning) arOverlay.stop() else arOverlay.start()
        AppLog.v(TAG, "tap: AR overlay -> ${if (arOverlay.isRunning) "ON" else "OFF"}")
        refreshArButton()
    }

    /**
     * AR options — what the overlay may draw, and how far out it draws air traffic.
     *
     * One dialog showing every switch at once rather than a sequence of prompts: these are
     * independent settings the pilot flips while looking at a picture that is already too busy,
     * so they need the current state of all of them in view. Everything applies LIVE — the
     * overlay reads [ArSettings] every frame — so an adjustment clears or repopulates the video
     * immediately instead of on next entry, which is what makes decluttering a usable in-flight
     * action rather than a setup step.
     *
     * Uses a custom view instead of `setMultiChoiceItems`: the stock two-line list item was the
     * one menu in the app that didn't match the rest of it, and it had nowhere to put a range
     * control.
     */
    private fun onArOptionsTapped() {
        AppLog.v(TAG, "long-press: AR options")
        val view = layoutInflater.inflate(R.layout.dialog_ar_options, null)

        // Rows built from the enum, not written out in XML — a category added later can't be
        // silently missing from the menu that controls it.
        val container = view.findViewById<LinearLayout>(R.id.arCategoryContainer)
        for (category in ArSettings.Category.values()) {
            val row = layoutInflater.inflate(R.layout.row_ar_category, container, false)
                as android.widget.CheckBox
            row.text = category.label
            row.isChecked = ArSettings.isEnabled(this, category)
            row.setOnCheckedChangeListener { _, isChecked ->
                ArSettings.setEnabled(this, category, isChecked)
            }
            container.addView(row)
        }

        val group = view.findViewById<android.widget.RadioGroup>(R.id.arRangeGroup)
        val rangeIds = mapOf(
            ArSettings.AirRange.MI_2_5 to R.id.arRange25,
            ArSettings.AirRange.MI_5 to R.id.arRange5,
            ArSettings.AirRange.MI_15 to R.id.arRange15,
        )
        group.check(rangeIds.getValue(ArSettings.airRange(this)))
        group.setOnCheckedChangeListener { _, checkedId ->
            rangeIds.entries.firstOrNull { it.value == checkedId }?.let {
                ArSettings.setAirRange(this, it.key)
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("AR Overlay")
            .setView(view)
            .setPositiveButton("Done", null)
            .setNeutralButton("Calibrate FOV…") { _, _ -> onArCalibrateTapped() }
            .setNegativeButton("Aim Offsets…") { _, _ -> onAimOffsetsTapped() }
            .create()

        // Straight to the guide's AR entry, where the accuracy limits are written out. The AR
        // overlay is the one control whose caveats matter more than its settings.
        view.findViewById<TextView>(R.id.arFieldGuideLink).setOnClickListener {
            dialog.dismiss()
            AppLog.v(TAG, "AR menu: opening field guide at the AR section")
            startActivity(FieldGuideActivity.intent(this, FieldGuideActivity.ANCHOR_AR))
        }
        dialog.show()
    }

    /**
     * The in-flight aim calibration: a live pitch/bearing bias the pilot walks onto a known
     * target. Without it
     * the bearing correction was a compile-time constant, so a gimbal strike, a repair or an
     * airframe swap had no field recovery for marker accuracy.
     */
    private fun onAimOffsetsTapped() {
        AppLog.v(TAG, "aim calibration opened")
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        var pitch = TakBridgeHolder.currentPitchOffset
        var bearing = TakBridgeHolder.currentBearingOffset

        val hint = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
            // Both directions spelled out for BOTH rows: a pilot mid-calibration should not
            // have to infer that "−" is the
            // opposite of the one direction the hint happened to name.
            text = "Pitch +  sends the marker FARTHER from the aircraft, −  brings it " +
                "closer.\nBearing +  swings it clockwise, −  swings it counter-clockwise.\n\n" +
                "Aim at a known object with the gimbal 25° DOWN — a bias is nearly " +
                "invisible looking straight down.\n\nFastest with a second TAK device: watch " +
                "the camera point (name ends \"-SPI\") slide onto the target as you adjust." +
                "\n\nDefault is 0.00° / 0.00° (uncalibrated)."
        }

        // Built in code rather than XML: two near-identical stepper rows, and a layout file
        // would need its own ids for each without buying any clarity.
        fun stepperRow(label: String, get: () -> Double, set: (Double) -> Unit): android.view.View {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
            }
            val name = TextView(this).apply {
                text = label; textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val value = TextView(this).apply {
                textSize = 18f; minWidth = (90 * resources.displayMetrics.density).toInt()
                gravity = android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_accent))
            }
            fun show() { value.text = "%+.2f°".format(java.util.Locale.US, get()) }
            show()
            fun button(text: String, delta: Double) = android.widget.Button(this).apply {
                this.text = text
                setOnClickListener {
                    set(get() + delta)
                    com.dji.sdk.sample.tak.ArSettings.saveAimOffsets(
                        this@TAKPilot2GoFlightActivity, pitch, bearing)
                    // Read back: the holder clamps, so the display must show what was
                    // ACCEPTED, not what was asked for — otherwise the pilot keeps tapping
                    // past the limit.
                    pitch = TakBridgeHolder.currentPitchOffset
                    bearing = TakBridgeHolder.currentBearingOffset
                    show()
                }
            }
            row.addView(name)
            row.addView(button("−", -0.25))
            row.addView(value)
            row.addView(button("+", 0.25))
            return row
        }

        root.addView(stepperRow("Pitch offset", { pitch }, { pitch = it }))
        root.addView(stepperRow("Bearing offset", { bearing }, { bearing = it }))
        root.addView(hint)

        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Aim Calibration")
            .setView(root)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset to 0") { _, _ ->
                com.dji.sdk.sample.tak.ArSettings.resetAimOffsets(this)
                Toast.makeText(this, "Aim calibration reset", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * AR field-of-view calibration.
     *
     * The base FOV is derived from published specs, not measured, and the projection is most
     * sensitive to it at the FRAME EDGES — an FOV error is invisible dead centre and grows
     * outward. So rather than a pass/fail sweep test, this lets the pilot put a marker on a
     * known object near the edge and adjust until the icon sits on it, watching it converge
     * live. Changes apply to the running overlay immediately and persist.
     *
     * Deliberately adjusts the 1x base: the zoom correction is applied on top of it, so
     * calibrating at 1x fixes every zoom level at once.
     */
    private fun onArCalibrateTapped() {
        AppLog.v(TAG, "AR FOV calibration opened")
        val view = layoutInflater.inflate(R.layout.dialog_ar_fov, null)
        val hValue = view.findViewById<TextView>(R.id.arFovHValue)
        val vValue = view.findViewById<TextView>(R.id.arFovVValue)
        val hint = view.findViewById<TextView>(R.id.arFovHint)

        var h = TakBridgeHolder.currentHFovBase
        var v = TakBridgeHolder.currentVFovBase

        fun apply() {
            com.dji.sdk.sample.tak.ArSettings.saveFov(this, h)
            h = TakBridgeHolder.currentHFovBase
            v = TakBridgeHolder.currentVFovBase   // derived, shown read-only
            hValue.text = "%.1f°".format(java.util.Locale.US, h)
            vValue.text = "%.1f°".format(java.util.Locale.US, v)
            hint.text = if (TakBridgeHolder.hasCameraFov) {
                ("THE CAMERA NOW REPORTS ITS OWN FIELD OF VIEW (%.1f° × %.1f° right now), " +
                    "and the app uses that answer. This manual value is only the fallback " +
                    "for a camera that has not reported.")
                    .format(
                        java.util.Locale.US,
                        com.dji.sdk.sample.tak.DroneTakBridge.hFovDeg(),
                        com.dji.sdk.sample.tak.DroneTakBridge.vFovDeg())
            } else if (TakBridgeHolder.currentZoomFactor > 1.0) {
                "Effective at %.0fx zoom: %.1f° × %.1f°".format(
                    TakBridgeHolder.currentZoomFactor,
                    com.dji.sdk.sample.tak.DroneTakBridge.hFovDeg(TakBridgeHolder.currentZoomFactor),
                    com.dji.sdk.sample.tak.DroneTakBridge.vFovDeg(TakBridgeHolder.currentZoomFactor),
                )
            } else {
                "CALIBRATE ON THE TOP OR BOTTOM EDGE. Put a marker on a known object near " +
                    "the top or bottom of the picture and adjust until the icon sits on it. " +
                    "The vertical is derived from the horizontal and the live picture shape, " +
                    "so there is one control, and the vertical is where an error shows.\n\n" +
                    "Marker too far OUT from centre → reduce. Too far IN → increase."
            }
        }
        apply()

        view.findViewById<Button>(R.id.arFovHMinus).setOnClickListener { h -= FOV_STEP_DEG; apply() }
        view.findViewById<Button>(R.id.arFovHPlus).setOnClickListener { h += FOV_STEP_DEG; apply() }

        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Calibrate AR field of view")
            .setView(view)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset") { _, _ ->
                com.dji.sdk.sample.tak.ArSettings.resetFov(this)
                Toast.makeText(this, "FOV reset to published specs", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * AR pill on/off state.
     *
     * Running reads as the app's status green (#4CAF50) on a green-filled pill; off is the plain
     * white pill at reduced alpha, like the other inactive toolbar controls. The earlier version
     * only shifted the label from white to the #9AC4FF accent, which is the same tint used for
     * ordinary emphasis all over this screen — at 12sp over moving video that was not a state
     * indication so much as a hint. Green is the colour this app already means "on/good" with
     * (TAK dot, GPS icon, crosshair accuracy ring), and it carries at a glance.
     *
     * Size is deliberately unchanged between states: a control that grows on tap reads as a
     * different control.
     */
    private fun refreshArButton() {
        val on = arOverlay.isRunning
        arButton.alpha = if (on) 1f else 0.45f
        arButton.setBackgroundResource(
            if (on) R.drawable.bg_pill_active else R.drawable.bg_zoom_pill
        )
        arButton.setTextColor(
            if (on) ContextCompat.getColor(applicationContext, R.color.tp_state_go) else android.graphics.Color.WHITE
        )
    }

    /**
     * Why a marker may NOT be placed right now, or null if it may.
     *
     * Returns a REASON rather than a boolean so the pilot is told which rule stopped them.
     * "The app did nothing" is the failure mode that makes people press harder and then stop
     * trusting the control.
     *
     * Two independent rules, both about whether a computed ground point means anything:
     *
     *  1. **Look angle.** Asks [CrosshairView.accuracyColorFor] — the SAME call that tints the
     *     reticle and the HUD gimbal readout — so "red reticle" and "drop refused" can never
     *     disagree. A separate threshold here would eventually drift and the pilot would see a
     *     red reticle accept a drop.
     *  2. **Height above ground.** Below [MIN_DROP_AGL_FT] the geometry is worthless: ground
     *     range is height / tan(pitch), so as height goes to zero the solved point collapses
     *     onto the aircraft's own position no matter where the camera looks. A marker placed on
     *     take-off or during landing would land on the pilot, and it would look deliberate to
     *     everyone receiving it.
     *
     * Uses the same AGL the HUD shows — terrain-corrected where DTED covers the aircraft,
     * otherwise height above the take-off point — so the number the pilot reads is the number
     * being judged.
     *
     * Checking only that a look point exists is not enough: that accepts a drop on the ground.
     */
    private fun dropRefusalReason(): String? {
        val hud = TakBridgeHolder.hud() ?: return "waiting on GPS + gimbal"
        val pitch = hud.gimbalPitch ?: return "waiting on GPS + gimbal"

        val aglFt = Units.metersToFeet(
            com.dji.sdk.sample.tak.TerrainAgl.reading(this, hud).meters)
        if (aglFt < MIN_DROP_AGL_FT) {
            return "too low — climb above ${MIN_DROP_AGL_FT.toInt()} ft AGL to place a marker"
        }

        // The same question CameraSlantPoint asks: DTED coverage at the aircraft's OWN position,
        // not whether any DTED is loaded somewhere.
        val dtedAvailable = hud.hasFix &&
            com.dji.sdk.sample.tak.DtedIndex.elevationAt(this, hud.lat, hud.lon) != null
        if (CrosshairView.accuracyColorFor(this, pitch, dtedAvailable) ==
            ContextCompat.getColor(applicationContext, R.color.tp_hud_accuracy_poor)) {
            return "look angle too shallow — tilt the gimbal down"
        }
        return null
    }

    /** True when a marker must not be placed. Kept as a predicate for the call sites that only
     *  need the yes/no; the reason itself comes from [dropRefusalReason]. */
    private fun aimTooPoorToDrop(): Boolean = dropRefusalReason() != null

    /** Shared refusal, so every drop route gives the pilot the same — and specific — reason. */
    private fun refuseDropForAim() {
        val why = dropRefusalReason() ?: return
        AppLog.w(TAG, "marker drop refused — $why")
        showNotice("Cannot place the marker. $why", refused = true)
    }

    /**
     * Transient notice over the top-left of the video, auto-hidden.
     *
     * One implementation shared by every caller so they can't drift on placement or timeout.
     * Distinct from a Toast on purpose: this says "the app did the thing", where the toasts
     * [TakDropMarkers] raises say "the TAK server has it" — during a comms outage the difference
     * between those two is exactly what the pilot needs to see.
     *
     * [refused] tells the pilot the app did NOT do the thing, and makes the text amber instead
     * of green. The colour is set here and never at a call site, so a refusal cannot reach the
     * screen wearing the acknowledgement colour — a pilot reads the colour before the words.
     *
     * A refusal goes here and never to a Toast, because the flight screen IS the TAK video
     * feed: a Toast is not in the screen capture, so a refused marker would leave the team
     * waiting for a mark that is never coming.
     */
    private fun showNotice(text: String, refused: Boolean = false) {
        fpvNotice.text = text
        fpvNotice.setTextColor(ContextCompat.getColor(applicationContext,
            if (refused) R.color.tp_state_caution else R.color.tp_state_go))
        fpvNotice.visibility = View.VISIBLE
        handler.removeCallbacks(hideNotice)
        handler.postDelayed(hideNotice, HOME_NOTICE_MS)
    }


    /**
     * Quick marker — tap the reticle, THE one quick marker goes to the look point. No dialog,
     * no menu.
     *
     * The toolbar drop button asks for a name and an affiliation because those drops are a
     * record. This one is a live pointer: the pilot has seen something, wants the rest of the
     * picture looking at it now, and any interaction between seeing it and marking it is
     * interaction spent not watching. So it is always [TakDropMarkers.Affiliation.UNKNOWN] (a
     * marker placed in under a second is unverified by definition) with a fixed callsign, and
     * only one of it can exist.
     *
     * ⚠ THE TAP NEVER REFUSES. It re-aims the quick marker when one is already down, and places
     * it when one is not. It used to refuse the second tap and scold the pilot to long-press
     * instead — one marker with two verbs, which meant the pilot had to remember which gesture
     * the app currently wanted before they could mark anything.
     *
     * The split that replaced it is NOT the same split, and this note is here so nobody removes
     * the new one for the old reason. Two different KINDS of marker, one verb each:
     *   SHORT — this function. The one quick marker, re-aimed at whatever the camera looks at.
     *   LONG  — [onUnknownMarkerAction]. A NEW stationary Unknown marker, numbered, that stays.
     * Neither gesture refuses, they give different results, and both are useful.
     */
    private fun onQuickDropTapped() {
        AppLog.v(TAG, "tap: reticle (quick marker)")
        if (aimTooPoorToDrop()) { refuseDropForAim(); return }
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            AppLog.w(TAG, "quick marker refused — no look point (GPS/gimbal not ready)")
            showNotice("Cannot place the marker. Wait for GPS and the gimbal.", refused = true)
            return
        }
        val (lat, lon, elev) = look
        // Move first: moveQuick keeps the uid, so the marker slides in place on every other TAK
        // client rather than the team seeing a delete and a new contact.
        if (TakDropMarkers.moveQuick(lat, lon, elev)) {
            showNotice("${TakDropMarkers.QUICK_NAME} re-aimed")
        } else if (TakDropMarkers.placeQuick(lat, lon, elev)) {
            showNotice("${TakDropMarkers.QUICK_NAME} dropped")
        }
    }

    /**
     * Long-press the reticle — drop a NEW stationary marker of the type Unknown at the look
     * point, and send it immediately.
     *
     * This is a SHORTCUT for the toolbar marker button plus "Unknown" in the type list, and
     * nothing more. The same name from the same shared counter, the same entry in the markers
     * list, the same re-broadcast. It is NOT the quick marker: it stays where it is put, and a
     * second long press makes a second marker.
     *
     * It sends with no Send / Do not Send question. This gesture is for the moment when the
     * pilot cannot give a dialog any attention; the toolbar button keeps the full flow.
     */
    private fun onUnknownMarkerAction() {
        AppLog.v(TAG, "long-press: reticle (Unknown marker)")
        if (aimTooPoorToDrop()) { refuseDropForAim(); return }
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            AppLog.w(TAG, "Unknown marker refused — no look point (GPS/gimbal not ready)")
            showNotice("Cannot place the marker. Wait for GPS and the gimbal.", refused = true)
            return
        }
        val (lat, lon, elev) = look
        // Take the auto name first and hand it straight back, the same way the drop-pin dialog
        // does: placeAt consumes the counter only when the name it gets is the one it offered,
        // so a custom name never leaves a gap in the numbering.
        val name = TakDropMarkers.nextAutoName()
        TakDropMarkers.placeAt(TakDropMarkers.Affiliation.UNKNOWN, lat, lon, elev, name)
        showNotice("$name dropped")
    }

    private fun onDropPinTapped() {
        AppLog.v(TAG, "tap: drop pin")
        if (aimTooPoorToDrop()) { refuseDropForAim(); return }
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            AppLog.w(TAG, "drop pin refused — no look point (GPS/gimbal not ready)")
            showNotice("Cannot place the marker. Wait for GPS and the gimbal.", refused = true)
            return
        }
        val (lat, lon, elev) = look

        val view = layoutInflater.inflate(R.layout.dialog_drop_pin, null)
        val nameField = view.findViewById<android.widget.EditText>(R.id.dropPinName)
        // Only a preview of the next number — TakDropMarkers consumes the counter solely when
        // this exact string comes back unedited, so a custom name doesn't leave a gap.
        var autoName = TakDropMarkers.nextAutoName()
        nameField.setText(autoName)
        nameField.setSelection(autoName.length)
        view.findViewById<TextView>(R.id.dropPinLocation).text =
            // Display only — the elevation sent in the CoT stays in metres (CotBuilder's
            // contract), this is just what the pilot reads before confirming the drop.
            "%.5f, %.5f  ·  %s elev".format(java.util.Locale.US, lat, lon, Units.feet(elev))

        // The affiliation icons themselves are the picker (no radio dot) — tapping one outlines
        // it via bg_marker_type_selected and clears the others. Defaults to Unknown: an
        // unverified drop shouldn't read as an affirmative Friendly/Hostile/Neutral call until
        // the pilot actually picks one.
        val chips = mapOf(
            TakDropMarkers.Affiliation.FRIENDLY to view.findViewById<View>(R.id.dropPinFriendly),
            TakDropMarkers.Affiliation.HOSTILE to view.findViewById<View>(R.id.dropPinHostile),
            TakDropMarkers.Affiliation.NEUTRAL to view.findViewById<View>(R.id.dropPinNeutral),
            TakDropMarkers.Affiliation.UNKNOWN to view.findViewById<View>(R.id.dropPinUnknown),
        )
        var selectedAff = TakDropMarkers.Affiliation.UNKNOWN
        fun refreshChipSelection() {
            for ((aff, chip) in chips) {
                chip.setBackgroundResource(
                    if (aff == selectedAff) R.drawable.bg_marker_type_selected
                    else android.R.color.transparent)
            }
        }
        for ((aff, chip) in chips) {
            chip.setOnClickListener {
                selectedAff = aff
                refreshChipSelection()
            }
        }
        refreshChipSelection()

        val dialog = AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Drop Marker at Crosshair")
            .setView(view)
            .setPositiveButton("Drop") { _, _ ->
                AppLog.i(TAG, "drop pin confirmed: ${selectedAff.label} @ $lat,$lon elev=$elev")
                TakDropMarkers.placeAt(selectedAff, lat, lon, elev, nameField.text.toString())
            }
            .setNegativeButton("Cancel") { _, _ -> AppLog.v(TAG, "drop pin cancelled") }
            // Placeholder text/listener — restyled and rewired in setOnShowListener below, since
            // the button bar's Views don't exist until the dialog is actually shown.
            .setNeutralButton("Reset Numbering", null)
            .create()
        // Bottom-left, in line with Drop/Cancel — that's simply where AlertDialog puts the
        // neutral button, so red-button.setOnClickListener replaces the (dismissing) listener
        // registered via setNeutralButton above with one that resets the counter in place and
        // leaves the dialog open, rather than closing the whole drop flow on tap.
        dialog.setOnShowListener {
            val resetBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            styleRedButton(resetBtn)
            resetBtn.setOnClickListener {
                AppLog.i(TAG, "auto-name counter reset from drop dialog")
                TakDropMarkers.resetAutoNameCounter()
                val newAutoName = TakDropMarkers.nextAutoName()
                // Only overwrite the field if the pilot hasn't already typed something of
                // their own — same "don't clobber an edit" rule the counter-consume logic
                // itself follows.
                if (nameField.text.toString() == autoName) {
                    nameField.setText(newAutoName)
                    nameField.setSelection(newAutoName.length)
                }
                autoName = newAutoName
                Toast.makeText(this, "Numbering reset — next is $newAutoName", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    /** Local-hide confirm for a tapped inbound contact — the map click listener above
     *  already resolved which uid was hit; this just confirms before dismissing it, since it's
     *  someone else's marker (or one of ours that reappeared after a delete). */
    private fun onInboundMarkerTapped(uid: String) {
        val tm = com.dji.sdk.sample.tak.TakMapMarkers
        val user = tm.inboundUser(uid) ?: return
        AppLog.v(TAG, "tap: inbound marker ${user.callsign} ($uid)")
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle(user.callsign ?: uid)
            .setMessage("Hide this marker from your map? It stays on the TAK server and may " +
                "reappear if another client re-broadcasts it.")
            .setPositiveButton("Hide") { _, _ ->
                AppLog.i(TAG, "inbound marker hide confirmed: $uid")
                tm.hideInbound(uid)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Markers list panel (dialog_markers.xml) — two red action buttons up top
     *  (Reset Numbering, Clear All Markers), then one row per dropped pin (a red X for a quick
     *  individual delete, its affiliation icon, range/bearing from the aircraft). Tapping a
     *  row's body (not its X) opens the full action menu (move/rename/retype/re-send/delete);
     *  tapping the X deletes that pin immediately and refreshes the list in place, since
     *  [row_marker_type.xml]'s X is a separate clickable child that consumes the touch before
     *  the enclosing ListView's own item-click ever fires. No map interaction needed, matching
     *  the locked mini-map. */
    /**
     * One row per marker: the pilot's own pins, then what the team shared. [ownPin] being
     * null marks a row shared — a shared one can be removed from this map and RE-SENT, but
     * never moved, renamed or retyped, because those edit it on every other client.
     */
    private data class MarkerRow(
        val label: String,
        val iconRes: Int,
        val ownPin: TakDropMarkers.PinInfo?,
        val sharedUid: String?,
    )

    private fun buildMarkerRows(): List<MarkerRow> {
        val hud = TakBridgeHolder.hud()
        // Range/bearing from the AIRCRAFT to each marker, so the list is orderable by "what's
        // near me" in the air rather than just drop order.
        fun range(lat: Double, lon: Double): String =
            if (hud != null && hud.hasFix) {
                val d = CameraSlantPoint.distanceMeters(hud.lat, hud.lon, lat, lon)
                val b = CameraSlantPoint.initialBearingDeg(hud.lat, hud.lon, lat, lon)
                // Units.distance (not .feet): a marker has no geofence bound the way the
                // aircraft's own position does, so this can run to five digits of feet where
                // miles read better.
                "  ·  %s @ %03.0f°".format(Units.distance(d), b)
            } else ""

        val own = TakDropMarkers.listPins().map {
            MarkerRow("${it.affiliation.label}: ${it.name}${range(it.lat, it.lon)}",
                it.affiliation.res, it, null)
        }
        val shared = com.dji.sdk.sample.tak.TakMapMarkers.listShared().map {
            // "Team:" prefix rather than an affiliation word — the useful distinction in this
            // list is who can edit it, and the affiliation is already carried by the icon.
            MarkerRow("Team: ${it.callsign}${range(it.lat, it.lon)}",
                com.dji.sdk.sample.tak.TakMapMarkers.sharedIconRes(it.type)
                    ?: R.drawable.marker_unknown, null, it.uid)
        }
        return own + shared
    }

    /**
     * The markers list, with a check box on every row and bulk Delete / Resend.
     *
     * SHORT TAP TICKS THE BOX, LONG PRESS EDITS. The per-row action menus are on the long
     * press so the short tap can be the selection gesture a check-box list needs.
     */
    private fun onMarkersListTapped() {
        // Themed inflater, by design. A view built with the ACTIVITY's context
        // inherits the activity theme, not the dialog's, and the row label lands white on white.
        val themed = android.view.ContextThemeWrapper(this, R.style.TakDialogTheme)
        val view = android.view.LayoutInflater.from(themed)
            .inflate(R.layout.dialog_markers, null)
        val container = view.findViewById<LinearLayout>(R.id.markersContainer)
        val empty = view.findViewById<TextView>(R.id.markersEmpty)
        val resendButton = view.findViewById<android.widget.Button>(R.id.markersResendButton)
        val deleteButton = view.findViewById<android.widget.Button>(R.id.markersDeleteButton)

        // Selection lives only as long as the dialog. It holds the ROW KEY — a pin key for an
        // own marker, a CoT uid for a shared one — because a list position stops being valid
        // the moment a row is deleted underneath it.
        val selected = mutableSetOf<String>()

        fun rowKey(row: MarkerRow): String? = row.ownPin?.key ?: row.sharedUid

        fun refreshButtons() {
            val any = selected.isNotEmpty()
            for (b in listOf(resendButton, deleteButton)) {
                b.isEnabled = any
                b.alpha = if (any) 1f else 0.45f
            }
        }

        fun populate() {
            container.removeAllViews()
            val rows = buildMarkerRows()
            // A key that no longer exists — its marker was deleted — must not stay selected, or
            // the next Delete would act on nothing and the count would lie.
            selected.retainAll(rows.mapNotNull { rowKey(it) }.toSet())
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            for (row in rows) {
                val key = rowKey(row) ?: continue
                val item = android.view.LayoutInflater.from(themed)
                    .inflate(R.layout.row_marker_select, container, false)
                val check = item.findViewById<android.widget.CheckBox>(R.id.markerRowCheck)
                item.findViewById<android.widget.ImageView>(R.id.markerRowIcon)
                    .setImageResource(row.iconRes)
                item.findViewById<TextView>(R.id.markerRowLabel).text = row.label
                check.isChecked = selected.contains(key)
                item.setOnClickListener {
                    if (!selected.add(key)) selected.remove(key)
                    check.isChecked = selected.contains(key)
                    refreshButtons()
                }
                item.setOnLongClickListener {
                    if (row.ownPin != null) onMarkerRowTapped(row.ownPin)
                    else onSharedMarkerRowTapped(row) { populate() }
                    true
                }
                container.addView(item)
            }
            refreshButtons()
        }
        populate()

        val dialog = AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Markers")
            .setView(view)
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear All") { _, _ -> onClearAllMarkersTapped {} }
            .create()

        // NO CONFIRMATION ON BULK DELETE (design decision). It is local-only:
        // there is no delete CoT in this application, thus the markers stay on the server until
        // they go stale and a shared one the team sends again comes straight back. Nothing here
        // reaches another operator's screen.
        deleteButton.setOnClickListener {
            val rows = buildMarkerRows().filter { rowKey(it) in selected }
            AppLog.i(TAG, "markers: bulk delete of ${rows.size}")
            for (row in rows) {
                val pin = row.ownPin
                // hideInbound, NOT clearAllShared's path: the per-uid delete also marks the uid
                // hidden, without which the next inbound copy would put the marker straight back.
                if (pin != null) TakDropMarkers.delete(pin.key)
                else row.sharedUid?.let { com.dji.sdk.sample.tak.TakMapMarkers.hideInbound(it) }
            }
            selected.clear()
            populate()
        }

        // SILENT (design decision) — no notice, no toast.
        resendButton.setOnClickListener {
            val rows = buildMarkerRows().filter { rowKey(it) in selected }
            AppLog.i(TAG, "markers: bulk re-send of ${rows.size}")
            for (row in rows) {
                val pin = row.ownPin
                if (pin != null) TakDropMarkers.resend(pin.key)
                else row.sharedUid?.let { com.dji.sdk.sample.tak.TakMapMarkers.resendShared(it) }
            }
        }

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.let { styleRedButton(it) }
    }

    /**
     * A marker somebody else shared: remove it from this map, or send it again. Re-sending a
     * received marker is ordinary TAK behaviour and goes out under the marker's OWN uid and
     * CoT type, thus it updates rather than duplicates. Rename, retype and move are absent on
     * purpose: editing another operator's marker is a larger question than re-broadcasting one.
     */
    private fun onSharedMarkerRowTapped(row: MarkerRow, onChanged: () -> Unit) {
        val uid = row.sharedUid ?: return
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle(row.label.substringAfter("Team: ").substringBefore("  ·"))
            .setItems(arrayOf("Re-send", "Remove from my map")) { _, index ->
                when (index) {
                    0 -> {
                        AppLog.i(TAG, "shared marker re-send: $uid")
                        com.dji.sdk.sample.tak.TakMapMarkers.resendShared(uid)
                    }
                    1 -> {
                        com.dji.sdk.sample.tak.TakMapMarkers.hideInbound(uid)
                        onChanged()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Clears BOTH sets, and says so before committing.
     *
     * It used to clear only the pilot's own, which left a "cleared" map still carrying every
     * marker the team had shared — the one outcome a pilot pressing Clear All is not expecting.
     * The two counts are stated separately because the consequences differ: the pilot's own
     * markers stay live on the server and may come back to other clients, while a shared one is
     * only being taken off this picture.
     */
    private fun onClearAllMarkersTapped(onCleared: () -> Unit) {
        val ownCount = TakDropMarkers.listPins().size
        val sharedCount = com.dji.sdk.sample.tak.TakMapMarkers.listShared().size
        // No figure here, for the reason given at onDeleteMarkerTapped.
        val body = buildString {
            append("Remove ")
            append(if (ownCount == 1) "1 marker you dropped" else "$ownCount markers you dropped")
            append(" and ")
            append(if (sharedCount == 1) "1 shared marker" else "$sharedCount shared markers")
            append(" from your map?\n\nThis is local only. Your own markers stay on the TAK ")
            append("server until they go stale and may still show on other clients. ")
            append("Shared markers come back if the team sends them again.")
        }
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Clear All Markers")
            .setMessage(body)
            .setPositiveButton("Clear All Markers") { _, _ ->
                AppLog.i(TAG, "markers: clear all confirmed ($ownCount own, $sharedCount shared)")
                TakDropMarkers.clearAll()
                com.dji.sdk.sample.tak.TakMapMarkers.clearAllShared()
                onCleared()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onMarkerRowTapped(pin: TakDropMarkers.PinInfo) {
        val actions = arrayOf("Move to crosshair", "Rename", "Change type", "Re-send", "Delete")
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle(pin.name)
            .setItems(actions) { _, index ->
                when (index) {
                    0 -> onMoveMarkerTapped(pin)
                    1 -> onRenameMarkerTapped(pin)
                    2 -> onChangeTypeTapped(pin)
                    3 -> {
                        AppLog.i(TAG, "marker re-send: ${pin.key}")
                        TakDropMarkers.resend(pin.key)
                    }
                    4 -> onDeleteMarkerTapped(pin)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onMoveMarkerTapped(pin: TakDropMarkers.PinInfo) {
        if (aimTooPoorToDrop()) { refuseDropForAim(); return }
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            AppLog.w(TAG, "marker move refused — no look point (GPS/gimbal not ready)")
            showNotice("Cannot move the marker. Wait for GPS and the gimbal.", refused = true)
            return
        }
        val (lat, lon, elev) = look
        AppLog.i(TAG, "marker move: ${pin.key} -> $lat,$lon elev=$elev")
        TakDropMarkers.moveToLookPoint(pin.key, lat, lon, elev)
    }

    private fun onRenameMarkerTapped(pin: TakDropMarkers.PinInfo) {
        val field = android.widget.EditText(this).apply {
            setText(pin.name)
            setSelection(pin.name.length)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Rename Marker")
            .setView(field)
            .setPositiveButton("Rename") { _, _ ->
                AppLog.i(TAG, "marker rename: ${pin.key} -> '${field.text}'")
                TakDropMarkers.rename(pin.key, field.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onChangeTypeTapped(pin: TakDropMarkers.PinInfo) {
        val affiliations = TakDropMarkers.Affiliation.values()
        val adapter = IconListAdapter(this)
        adapter.setRows(affiliations.map { IconListAdapter.Row(it.label, it.res, pin = null) })
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Change Type")
            .setAdapter(adapter) { _, index ->
                AppLog.i(TAG, "marker retype: ${pin.key} -> ${affiliations[index].label}")
                TakDropMarkers.changeType(pin.key, affiliations[index])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Icon+label row adapter shared by the markers-list panel and the change-type picker
     *  (row_marker_type.xml) — plain [AlertDialog.setItems] has no icon/delete-X slot, so both
     *  dialogs use setAdapter instead. [setRows] + notifyDataSetChanged lets the markers list
     *  refresh itself in place after an X-delete without closing the dialog. */
    private class IconListAdapter(
        context: android.content.Context,
    ) : android.widget.BaseAdapter() {
        /** Exactly one of [pin] and [shared] is set on a markers-list row; both are null on the
         *  change-type rows, which reuse this adapter and are neither. */
        data class Row(
            val label: String,
            val iconRes: Int?,
            val pin: TakDropMarkers.PinInfo?,
            val shared: com.dji.sdk.sample.tak.TakMapMarkers.SharedMarker? = null,
        )

        /** Fired when a row's delete-X is tapped (markers-list only; null for change-type rows,
         *  which never show an X). */
        var onDeleteX: ((Row) -> Unit)? = null

        private var rows: List<Row> = emptyList()
        private val inflater = android.view.LayoutInflater.from(context)

        fun setRows(newRows: List<Row>) { rows = newRows; notifyDataSetChanged() }
        fun rowAt(position: Int): Row = rows[position]

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            // Not reusing convertView: rows carry per-position click state (the X's target
            // pin), and this list is short enough (a handful of dropped pins) that the
            // simplicity of always inflating fresh is worth more than view recycling here.
            val view = inflater.inflate(R.layout.row_marker_type, parent, false)
            val row = rows[position]

            val icon = view.findViewById<ImageView>(R.id.rowMarkerTypeIcon)
            if (row.iconRes != null) {
                icon.setImageResource(row.iconRes)
                icon.visibility = View.VISIBLE
            } else {
                icon.visibility = View.INVISIBLE
            }

            view.findViewById<TextView>(R.id.rowMarkerTypeLabel).text = row.label

            val deleteX = view.findViewById<ImageView>(R.id.rowMarkerDeleteX)
            // A shared row gets an X too — it removes the marker from THIS picture only. The
            // change-type rows (neither pin nor shared) get none.
            if (row.pin != null || row.shared != null) {
                deleteX.visibility = View.VISIBLE
                deleteX.setOnClickListener { onDeleteX?.invoke(row) }
            } else {
                deleteX.visibility = View.GONE
                deleteX.setOnClickListener(null)
            }
            return view
        }
    }

    private fun onDeleteMarkerTapped(pin: TakDropMarkers.PinInfo) {
        // ⚠ THE LIFETIME IS DELIBERATELY NOT STATED. It is CotBuilder.MARKER_STALE_DURATION_MS,
        // which is private and lives in shared taklite code. This dialog once carried a stale
        // figure long after that constant was raised, because a copied figure cannot follow its
        // source. Describe the behaviour; do not restate the number.
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Delete Marker")
            .setMessage("Remove '${pin.name}' from your map? This is local-only — the marker " +
                "stays on the TAK server until it goes stale and may reappear on other " +
                "clients' pictures until then.")
            .setPositiveButton("Delete") { _, _ ->
                AppLog.i(TAG, "marker delete: ${pin.key}")
                TakDropMarkers.delete(pin.key)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * @param stayInStills true from the HARDWARE shutter: the camera is left in
     *   PHOTO_NORMAL, the mode the pilot chose by pressing it; the media switch shows it and
     *   REC records from any mode. False restores VIDEO_NORMAL afterwards, the old pill's
     *   behaviour, kept for any caller that still wants it.
     */
    private fun onShootPhotoTapped(stayInStills: Boolean = false) {
        AppLog.v(REC_TAG, if (stayInStills) "shutter key: photo (stay in stills)" else "tap: shutter (photo)")
        if (!DjiSdkBridge.isProductConnected) {
            AppLog.w(REC_TAG, "photo ignored — aircraft not connected")
            Toast.makeText(this, "Aircraft not connected", Toast.LENGTH_SHORT).show()
            return
        }
        // One sequence at a time. See [photoSequenceActive]: overlapping sequences make the
        // camera refuse all but one shot, and the refusal reaches the pilot as "null".
        if (photoSequenceActive) {
            AppLog.i(REC_TAG, "photo ignored — a sequence is already in flight")
            return
        }
        photoSequenceActive = true
        setShutterBusy(true)
        // The shoot itself, once the camera is in PHOTO_NORMAL. Re-pushes the same
        // metering/exposure-mode/EV used for video onto photo mode first — photo mode has its
        // own separately-persisted exposure state, so without this the still's EV wouldn't
        // necessarily match what the live feed showed.
        val shoot = {
            ExposureController.applyExposureSettings(applicationContext) {
                KeyManager.getInstance().performAction(
                    KeyTools.createKey(CameraKey.KeyStartShootPhoto, MAIN_CAM),
                    object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                        override fun onSuccess(t: EmptyMsg?) {
                            AppLog.i(REC_TAG, "shoot photo result: OK")
                            runOnUiThread {
                                Toast.makeText(this@TAKPilot2GoFlightActivity,
                                    "Photo saved to aircraft SD card", Toast.LENGTH_SHORT).show()
                            }
                            if (stayInStills) endPhotoSequence() else restoreVideoModeAfterPhoto()
                        }

                        override fun onFailure(error: IDJIError) {
                            AppLog.i(REC_TAG, "shoot photo result: ${describeError(error)}")
                            runOnUiThread {
                                Toast.makeText(this@TAKPilot2GoFlightActivity,
                                    "Photo failed: ${describeError(error)}", Toast.LENGTH_SHORT).show()
                            }
                            if (stayInStills) endPhotoSequence() else restoreVideoModeAfterPhoto()
                        }
                    },
                )
            }
        }
        // ⚠ NO MODE WRITE WHEN THE CAMERA ALREADY REPORTS PHOTO_NORMAL (measured): this
        // camera REFUSES a write of the mode it is already in, and the sequence
        // died there on the hardware shutter — "set PHOTO_NORMAL mode: refused", no
        // still taken, an error toast for a press that should have been the simplest case.
        // The listened mode (what the camera holds, not what was asked) says whether the write is needed at all.
        // ⚠ NOT WHILE THE CAMERA IS ALREADY SHOOTING. On the hardware shutter the firmware may
        // take the still itself; a second KeyStartShootPhoto on top of it is refused with no
        // reason (measured) and reaches the pilot as a failure for a still that
        // was taken. The camera's own KeyIsShootingPhoto decides; its still gets the notice
        // through TakBridgeHolder.onStillTaken like any other.
        if (TakBridgeHolder.photoInProgress()) {
            AppLog.i(REC_TAG, "photo: the camera is already shooting — not sending a second")
            endPhotoSequence()
            return
        }
        if (TakBridgeHolder.hud()?.cameraMode == CameraMode.PHOTO_NORMAL) {
            AppLog.i(REC_TAG, "photo: camera already reports PHOTO_NORMAL — shooting")
            shoot()
            return
        }
        AppLog.i(REC_TAG, "photo: switching to PHOTO_NORMAL")
        KeyManager.getInstance().setValue(
            KeyTools.createKey(CameraKey.KeyCameraMode, MAIN_CAM),
            CameraMode.PHOTO_NORMAL,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(REC_TAG, "photo: set PHOTO_NORMAL mode: OK")
                    shoot()
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.i(REC_TAG, "photo: set PHOTO_NORMAL mode: ${describeError(error)}")
                    // The sequence ends here — nothing was shot, so no restore runs and the
                    // button must be released by this branch or it stays dead for the flight.
                    endPhotoSequence()
                    runOnUiThread {
                        Toast.makeText(this@TAKPilot2GoFlightActivity,
                            "Could not switch to photo mode: ${describeError(error)}",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    /**
     * Puts the camera back in VIDEO_NORMAL after a still — but only once it is actually willing
     * to change mode, and verified rather than assumed.
     *
     * `startShootPhoto`'s completion callback means "the shutter fired", NOT "the camera is
     * done". While the still is still being written the camera rejects a mode change outright.
     * Field-observed on the Air 2: the restore ran 14ms after the shoot callback and
     * every call returned "Undefined Error", so the camera stayed in photo mode for the rest of
     * the flight with nothing on screen saying so — the live FPV is this screen's primary job, so
     * that is not a cosmetic failure.
     *
     * So: wait for [TakBridgeHolder.photoInProgress] to clear, then switch, then retry if the
     * switch itself is still refused. Both waits share one attempt budget — after
     * [PHOTO_RESTORE_MAX_ATTEMPTS] it tries anyway rather than waiting forever on a camera state
     * that may never arrive, and if that final attempt fails the pilot is TOLD, because a camera
     * silently left in photo mode is exactly the failure this is here to prevent.
     */
    /**
     * Paints the one warning that currently owns the banner, or hides it.
     *
     * NOTHING IS FILTERED OUT ON THE PILOT'S BEHALF — that rule is unchanged and is why
     * "Cannot takeoff in a no-fly zone" is on this banner at all, after it stayed invisible
     * through two flights. What changed is that the aircraft's faults now arrive through
     * [FlightWarnings] alongside the conditions DJIDiagnostics has no equivalent for, and the
     * banner shows the WORST one at a time with a +N for the rest, instead of concatenating an
     * unbounded list that grew down over the video and strobed whenever a fault flapped.
     *
     * Polled from the HUD tick as well as pushed from the diagnostics callback: the hold and
     * the queue advance with time, not only with events, so the banner has to be re-asked.
     */
    /**
     * Colours the toolbar gauge from the SAME two settings Pre-Flight sends to the aircraft:
     * amber from Battery Warning, red from Battery Critical. Hard-coded edges here would drift
     * from the thresholds every time they were retuned, which is how the gauge previously
     * ended up showing amber while the aircraft was seconds from acting.
     */
    /**
     * BVLOS antenna aim: the controller's antennas
     * are directional, and during authorized BVLOS work the pilot cannot see the aircraft to
     * face it. The bearing is CONTROLLER→AIRCRAFT from the pilot's own GPS fix — the home
     * point would be wrong the moment the pilot walks. No text fallback (design
     * decision): a bearing number without an on-screen compass gives the pilot
     * nothing to act on.
     */
    private fun updateAntennaAim(hud: com.dji.sdk.sample.tak.DroneTakBridge.Hud?) {
        val fix = com.dji.sdk.sample.tak.OperatorLocation.latest
        val facing = com.dji.sdk.sample.tak.ControllerCompass.azimuthTrueDeg()
        if (hud == null || !hud.hasFix || fix == null || facing == null) {
            fpvAntennaArc.setRelativeBearing(null)
            return
        }
        val bearing = CameraSlantPoint.initialBearingDeg(
            fix.latitude, fix.longitude, hud.lat, hud.lon)
        // Signed relative turn, -180..+180: which way and how far the pilot must rotate.
        fpvAntennaArc.setRelativeBearing(((bearing - facing + 540.0) % 360.0) - 180.0)
    }

    /**
     * Adopts the CAMERA'S OWN state — lens, zoom and palette — instead of assuming it.
     *
     * ⚠ THE SCREEN USED TO OPEN AT "WIDE, 1X, WHITE HOT" WHATEVER THE AIRCRAFT WAS DOING.
     * Measured: the aircraft was left in thermal, the app was restarted,
     * the picture came back thermal and every control said visible-camera. The camera keeps
     * its state across an app restart; three local fields here did not, and nothing ever
     * asked. Same defect the lights pill had, and the same rule broken —
     * "UI state must show what the AIRCRAFT holds, not what was requested".
     *
     * Runs from the HUD tick until it gets an answer, because the aircraft link comes up
     * after this screen does. Reads only, which is allowed on a tick — only writes are barred from a timer.
     */
    private fun syncCameraFromAircraft() {
        if (sourceSwitchPending) return
        // ⚠ THE ASYNCHRONOUS getValue. The one-argument form reads MSDK's LOCAL CACHE, and a
        // key nothing has fetched yet is absent from it — so on a fresh start it answers null
        // for ever and the controls keep their assumed defaults. Same trap the lights pill
        // fell into.
        val sourceKey = KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, MAIN_CAM)
        KeyManager.getInstance().getValue(sourceKey,
            object : CommonCallbacks.CompletionCallbackWithParam<CameraVideoStreamSourceType> {
                override fun onSuccess(value: CameraVideoStreamSourceType?) {
                    if (value == null || value == CameraVideoStreamSourceType.UNKNOWN) return
                    runOnUiThread { adoptCameraSource(value) }
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.v(TAG, "camera source read failed: ${describeError(error)}")
                }
            })
    }

    /** Applies the lens the aircraft reported, then chases the zoom or palette that goes
     *  with it. Split out so the async callbacks stay readable. */
    private fun adoptCameraSource(source: CameraVideoStreamSourceType) {
        // A leftover WIDE source (DJI Pilot 2's Wide button, or an old build of this app)
        // is moved to the zoom camera AT 1x. The framing the pilot chose on Wide IS 1x, but
        // the tele idles at whatever ratio it last held — possibly 28x from a previous flight —
        // so the ratio is written FIRST (invisible on the off-screen lens, the proven order)
        // and the lens switches second. cameraStateSynced stays false until the migration
        // lands, so the HUD tick retries the whole adoption if any step is refused; the
        // pending flag keeps those retries from overlapping.
        if (source == CameraVideoStreamSourceType.WIDE_CAMERA) {
            if (sourceSwitchPending) return
            AppLog.i(TAG, "entry found the WIDE camera live — moving to the zoom camera at 1x")
            sourceSwitchPending = true
            KeyManager.getInstance().setValue(
                KeyTools.createCameraKey(
                    CameraKey.KeyCameraZoomRatios, MAIN_CAM, CameraLensType.CAMERA_LENS_ZOOM),
                1.0,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        switchSourceThenFinish(
                            KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, MAIN_CAM),
                            CameraVideoStreamSourceType.ZOOM_CAMERA, 1.0, 1.0)
                    }

                    override fun onFailure(error: IDJIError) {
                        AppLog.w(TAG, "entry migration: ratio write refused: " +
                            describeError(error))
                        sourceSwitchPending = false   // the tick will retry the adoption
                    }
                })
            return
        }

        cameraStateSynced = true
        // ⚠ MEASURED, and worth keeping written down: this camera declares
        // ZoomRatiosRange{isContinuous=false, gears=[1, 3, 7, 14, 28, 56, 112]} — so 1X exists
        // (a "the floor is 1.5" theory was wrong), and the dial reaches 112x, not the 28x this
        // application once assumed was the top. The ratio is wide-referenced: f35 = ratio x 24,
        // exact at twenty samples.
        irOn = source == CameraVideoStreamSourceType.INFRARED_CAMERA
        teleLive = source == CameraVideoStreamSourceType.ZOOM_CAMERA
        renderIrButtons()
        com.dji.sdk.sample.tak.CameraFov.refresh(irOn, zoomRatio)
        AppLog.i(TAG, "camera state adopted from the aircraft: source=$source")

        if (source == CameraVideoStreamSourceType.ZOOM_CAMERA) {
            val zoomKey = KeyTools.createCameraKey(
                CameraKey.KeyCameraZoomRatios, MAIN_CAM, CameraLensType.CAMERA_LENS_ZOOM)
            KeyManager.getInstance().getValue(zoomKey,
                object : CommonCallbacks.CompletionCallbackWithParam<Double> {
                    override fun onSuccess(value: Double?) {
                        if (value == null || value <= 0) return
                        runOnUiThread {
                            zoomRatio = value
                            // ⚠ renderZoomPill(), NOT ZoomLadder.label(). label() is
                            // "${'$'}{ratio.toInt()}X" and toInt() TRUNCATES, so the camera
                            // reporting 6.9958 — which is its gear 7 — was drawn as "6X".
                            // Measured after a restart with the camera left at 7x.
                            // label()'s own documentation says it is for whole numbers only and
                            // that fractional ratios are the caller's job; this caller was the
                            // one place that ignored it and set the text itself.
                            renderZoomPill()
                            TakBridgeHolder.setZoomFactor(value)
                            AppLog.i(TAG, "zoom adopted from the aircraft: $value")
                        }
                    }

                    override fun onFailure(error: IDJIError) {}
                })
        } else if (!irOn) {
            // The wide camera IS the ladder's 1X rung.
            zoomRatio = ZoomLadder.MIN
            zoomButton.text = ZoomLadder.label(ZoomLadder.MIN)
            TakBridgeHolder.setZoomFactor(ZoomLadder.MIN)
        }

        if (irOn) {
            val paletteKey = KeyTools.createCameraKey(
                CameraKey.KeyThermalPalette, MAIN_CAM, CameraLensType.CAMERA_LENS_THERMAL)
            KeyManager.getInstance().getValue(paletteKey,
                object : CommonCallbacks.CompletionCallbackWithParam<CameraThermalPalette> {
                    override fun onSuccess(value: CameraThermalPalette?) {
                        val i = IR_PALETTES.indexOfFirst { it.first == value }
                        if (i < 0) return
                        runOnUiThread {
                            irPalette = i
                            irPaletteButton.text = IR_PALETTES[i].second
                            AppLog.i(TAG, "palette adopted from the aircraft: " +
                                IR_PALETTES[i].second)
                        }
                    }

                    override fun onFailure(error: IDJIError) {}
                })
        }
    }

    /** Polls the listened camera mode after a VIDEO_NORMAL write — see onRecordToggleTapped. */
    private fun startRecordWhenCameraReportsVideo(startedAt: Long) {
        val hud = TakBridgeHolder.hud()
        if (hud?.isRecording == true) return                       // the camera got there itself
        val waited = android.os.SystemClock.elapsedRealtime() - startedAt
        when {
            hud?.cameraMode == CameraMode.VIDEO_NORMAL -> {
                AppLog.i(REC_TAG, "camera reports VIDEO_NORMAL after ${waited}ms — starting in ${MODE_READY_SETTLE_MS}ms")
                handler.postDelayed({
                    // ⚠ CHECKED AGAIN AFTER THE SETTLE (measured). From the left
                    // shoulder in PHOTO, this path put the camera in VIDEO and the FIRMWARE then
                    // started the recording on that same press — the pill lit red, and our own
                    // start 1.2 s later was refused for a recording already running, which
                    // reached the pilot as a failure toast for a recording that had started.
                    // A recording the camera reports is the outcome asked for, whoever began it.
                    if (TakBridgeHolder.hud()?.isRecording == true) {
                        AppLog.i(REC_TAG, "camera already recording after the settle — the firmware started it; nothing to send")
                        return@postDelayed
                    }
                    performRecordAction(CameraKey.KeyStartRecord, "Recording started", "Start failed", "startRecord")
                }, MODE_READY_SETTLE_MS)
            }
            waited >= MODE_POLL_LIMIT_MS -> {
                AppLog.w(REC_TAG, "camera never reported VIDEO_NORMAL in ${waited}ms (reports ${hud?.cameraMode}) — not starting")
                showNotice("The camera did not switch to video. Not recording.", refused = true)
            }
            else -> handler.postDelayed({ startRecordWhenCameraReportsVideo(startedAt) }, MODE_POLL_MS)
        }
    }

    /**
     * The pilot tapped a half of the media-mode switch. Refused while recording — the camera
     * will not change what it is writing, and the record path owns that state. Otherwise one
     * write and nothing taken; the camera's reported mode moves the thumb.
     */
    private fun onMediaModeRequested(wanted: MediaModeView.Mode) {
        if (!DjiSdkBridge.isProductConnected) { showNotice("Aircraft not connected", refused = true); return }
        if (TakBridgeHolder.hud()?.isRecording == true) {
            AppLog.w(REC_TAG, "media mode $wanted refused — the camera is recording")
            showNotice("Stop the recording to change the camera mode", refused = true)
            return
        }
        val target = if (wanted == MediaModeView.Mode.VIDEO) CameraMode.VIDEO_NORMAL else CameraMode.PHOTO_NORMAL
        if (TakBridgeHolder.hud()?.cameraMode == target) return
        AppLog.i(REC_TAG, "tap: media mode -> $target")
        KeyManager.getInstance().setValue(
            KeyTools.createKey(CameraKey.KeyCameraMode, MAIN_CAM), target,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { AppLog.i(REC_TAG, "set $target: OK") }
                override fun onFailure(error: IDJIError) {
                    AppLog.w(REC_TAG, "set $target: ${describeError(error)}")
                    runOnUiThread { showNotice("Camera refused $target", refused = true) }
                }
            },
        )
    }

    /**
     * Paints the switch from what the CAMERA reports. Unknown is its own state, amber.
     * The modes this application does not drive (burst, interval, …) are shown by NAME rather
     * than mapped onto one of the two it knows. ANNOUNCES A CHANGE, never the first answer:
     * the hardware shutter moves the mode without the pilot asking.
     */
    private fun renderMediaMode(mode: dji.sdk.keyvalue.value.camera.CameraMode?) {
        if (!::fpvMediaMode.isInitialized) return
        when (mode) {
            null -> fpvMediaMode.setMode(null)
            CameraMode.VIDEO_NORMAL -> fpvMediaMode.setMode(MediaModeView.Mode.VIDEO)
            CameraMode.PHOTO_NORMAL -> fpvMediaMode.setMode(MediaModeView.Mode.PHOTO)
            else -> fpvMediaMode.setMode(null, unhandledName = mode.name)
        }
        if (mode != null && cameraModeEverPainted && mode != lastPaintedCameraMode) {
            AppLog.i(REC_TAG, "camera mode changed: $lastPaintedCameraMode -> $mode")
            showNotice("Camera: " + when (mode) {
                CameraMode.PHOTO_NORMAL -> "PHOTO"; CameraMode.VIDEO_NORMAL -> "VIDEO"; else -> mode.name
            })
        }
        if (mode != null) { lastPaintedCameraMode = mode; cameraModeEverPainted = true }
    }

    /**
     * THERMAL ON/OFF — the same stream-source key the zoom ladder drives, with INFRARED as a
     * third source. The mechanism is the one the zoom work proved on this
     * aircraft: set [CameraKey.KeyCameraVideoStreamSource], then READ IT BACK and render from
     * the answer. A source switch that returns OK and does not take is exactly how a zoom
     * button ends up lying about its magnification.
     *
     * Leaving IR returns to the WIDE camera, which is the ladder's 1X rung, so the zoom label
     * and the camera agree again the moment IR goes off.
     */
    private fun onIrTapped() {
        // ZOOM_CAMERA, not WIDE: the zoom stream is the only visible-light source this app
        // uses — its 1x IS the wide framing. See onZoomTapped.
        val target = if (irOn) CameraVideoStreamSourceType.ZOOM_CAMERA
                     else CameraVideoStreamSourceType.INFRARED_CAMERA
        val sourceKey = KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, MAIN_CAM)
        AppLog.i(TAG, "IR: switching stream source to $target")
        irButton.isEnabled = false
        KeyManager.getInstance().setValue(sourceKey, target,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    val nowSource = runCatching {
                        KeyManager.getInstance().getValue(
                            sourceKey, CameraVideoStreamSourceType.UNKNOWN)
                    }.getOrNull()
                    AppLog.i(TAG, "IR: stream source read-back = $nowSource")
                    runOnUiThread {
                        irButton.isEnabled = true
                        // THE READ-BACK DECIDES, not the request.
                        irOn = nowSource == CameraVideoStreamSourceType.INFRARED_CAMERA
                        teleLive = nowSource == CameraVideoStreamSourceType.ZOOM_CAMERA
                        com.dji.sdk.sample.tak.CameraFov.refresh(irOn, zoomRatio)
                        if (!irOn) {
                            // Leaving IR lands on the zoom camera at whatever ratio it held;
                            // the follow listener and CameraFov pick it up from there.
                            renderZoomPill()
                        }
                        fpvView.setDigitalCrop(1.0)
                        TakBridgeHolder.setDigitalCrop(1.0)
                        renderIrButtons()
                    }
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "IR: stream source switch refused: ${describeError(error)}")
                    runOnUiThread {
                        irButton.isEnabled = true
                        Toast.makeText(this@TAKPilot2GoFlightActivity,
                            "Could not switch to thermal: ${describeError(error)}",
                            Toast.LENGTH_LONG).show()
                    }
                }
            })
    }

    /** White hot -> black hot -> ironbow, then round again. Only reachable while [irOn]. */
    private fun onIrPaletteTapped() {
        val next = (irPalette + 1) % IR_PALETTES.size
        val (palette, label) = IR_PALETTES[next]
        val key = KeyTools.createCameraKey(
            CameraKey.KeyThermalPalette, MAIN_CAM, CameraLensType.CAMERA_LENS_THERMAL)
        AppLog.i(TAG, "IR palette: asking for $label")
        KeyManager.getInstance().setValue(key, palette,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    // A success callback is not proof — read back what the
                    // camera actually holds, same shape as the screen-entry adoption above,
                    // rather than painting the pill from the request we just sent.
                    KeyManager.getInstance().getValue(key,
                        object : CommonCallbacks.CompletionCallbackWithParam<CameraThermalPalette> {
                            override fun onSuccess(value: CameraThermalPalette?) {
                                val i = IR_PALETTES.indexOfFirst { it.first == value }
                                if (i < 0) {
                                    AppLog.w(TAG, "IR palette read-back $value not in known list")
                                    return
                                }
                                runOnUiThread {
                                    irPalette = i
                                    irPaletteButton.text = IR_PALETTES[i].second
                                    AppLog.i(TAG, "IR palette now ${IR_PALETTES[i].second} (read back)")
                                }
                            }

                            override fun onFailure(error: IDJIError) {
                                AppLog.w(TAG, "IR palette read-back failed: ${describeError(error)}")
                            }
                        })
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "IR palette refused: ${describeError(error)}")
                    runOnUiThread {
                        Toast.makeText(this@TAKPilot2GoFlightActivity,
                            "Could not change the palette: ${describeError(error)}",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            })
    }

    /**
     * Paints the IR pill, shows the palette button only while thermal is live, and GREYS THE
     * ZOOM PILL while IR is on.
     *
     * The zoom ladder and IR are the same SDK control — one key picks WIDE, ZOOM or INFRARED
     * — so zoom is genuinely unavailable in thermal, and the honest thing is to say so rather
     * than invent a behaviour. Same idiom as the shutter dimming during a recording.
     */
    private fun renderIrButtons() {
        irButton.setBackgroundResource(
            if (irOn) R.drawable.bg_pill_active else R.drawable.bg_zoom_pill)
        irButton.setTextColor(
            if (irOn) ContextCompat.getColor(applicationContext, R.color.tp_state_go)
            else android.graphics.Color.WHITE)
        irPaletteButton.visibility = if (irOn) View.VISIBLE else View.GONE
        zoomButton.isEnabled = !irOn
        zoomButton.alpha = if (irOn) 0.45f else 1f
    }

    /**
     * The icon is the MOTOR LEDs — what a tap works. Unknown keeps its own dimmed look and is
     * never collapsed into "off".
     *
     * The beacon has no indicator here on purpose: its state is
     * reported by the toast on every touch-and-hold, and a second state light on a 46dp pill
     * was rejected as clutter.
     */
    /**
     * Draws the exterior-lights button from the AIRCRAFT's reported lamp state.
     *
     * Plain bulb = lit, slashed bulb = dark, matching the IR button's convention that the icon
     * shows the state of the hardware rather than what the next tap would do.
     *
     * ⚠ **THE PILL COLOUR IS THE STATE, AND THE GLYPH ONLY AGREES WITH IT.**
     * This button sits in the actions capsule beside AR and IR, and those two have always
     * said ON with a green pill. The lights said it with a glyph alone, so three toggles in one
     * capsule carried two conventions — and the odd one out was the only one reporting a state
     * of the AIRCRAFT.
     *
     * The third state is the one AR and IR do not have. The lamps are read BACK from the
     * aircraft, thus "we have not been told" is real and is AMBER — never collapsed into off,
     * which is the rule for every state readout in this application.
     *
     * ⚠ **DO NOT CARRY UNKNOWN AS A 50 % ALPHA.** Dimming
     * reads as disabled, and this button genuinely disables itself — with no colour change —
     * while a write is in flight (see the click and long-press handlers). One appearance for
     * two meanings.
     *
     * ⚠ **TINT ONLY THE LIT BULB, NEVER THE SLASHED ONE.** ic_led_off draws its slash TWICE,
     * dark under white, so that it reads as a gap cut through the glass. A tint is applied to
     * every path of a vector, thus it would flatten both passes to one colour and the slash
     * would disappear into the bulb. This is safe as written because the slashed bulb is shown
     * ONLY for the dark state, which is the one state that takes the colourless idle pill. Keep
     * those two facts together: if the dark state ever gains a colour, the icon has to gain a
     * second drawable first.
     *
     * ⚠ The flag here is `motorLedsOn`, so TRUE means LIT — not a "dark" flag. Read the
     * branches with that in mind.
     */
    private fun renderLightsButton() {
        val on = AircraftLights.motorLedsOn
        lightsButton.setImageResource(
            if (on == false) R.drawable.ic_led_off else R.drawable.ic_led_on)
        lightsButton.setBackgroundResource(when (on) {
            true -> R.drawable.bg_pill_active    // lit
            false -> R.drawable.bg_zoom_pill     // dark
            null -> R.drawable.bg_pill_unknown   // the aircraft has not answered
        })
        lightsButton.imageTintList = when (on) {
            true -> androidx.core.content.ContextCompat.getColorStateList(this, R.color.tp_state_go)
            null -> androidx.core.content.ContextCompat.getColorStateList(this, R.color.tp_state_unknown)
            false -> null
        }
        lightsButton.contentDescription = when (on) {
            true -> "Exterior lights are on"
            false -> "Exterior lights are off"
            null -> "Exterior lights, state unknown"
        }
    }

    private fun refreshBatteryBands() {
        // AIRCRAFT FIRST, pref only as a stand-in. The pilot's saved value is what they
        // INTEND; it differs from the aircraft's whenever a level was edited but not applied,
        // or an apply failed. A gauge is read to judge how much flying is left, so it has to
        // be coloured from the levels the aircraft will actually act on.
        val warn = FlightLimitsController.aircraftWarningPct?.toFloat()
            ?: FlightLimitsController.savedLowBatteryPct(this).trim().toFloatOrNull() ?: 30f
        val crit = FlightLimitsController.aircraftCriticalPct?.toFloat()
            ?: FlightLimitsController.savedCriticalBatteryPct(this).trim().toFloatOrNull() ?: 15f
        toolbarBattery.setBands(crit, warn)
    }

    /**
     * Binds the accessory panel. Called once from onCreate; the panel itself stays hidden until
     * L2.
     */
    private fun setupAccessoryPanel() {
        accessoryPanel = findViewById(R.id.accessoryPanel)
        accessoryNone = findViewById(R.id.accessoryNone)
        accessoryLightBlock = findViewById(R.id.accessoryLightBlock)
        accessoryLightBrightnessRow = findViewById(R.id.accessoryLightBrightnessRow)
        accessoryLightLow = findViewById(R.id.accessoryLightLow)
        accessoryLightHigh = findViewById(R.id.accessoryLightHigh)
        accessoryLightBlink = findViewById(R.id.accessoryLightBlink)
        accessorySpeakerBlock = findViewById(R.id.accessorySpeakerBlock)
        accessorySpeakerMessageRow = findViewById(R.id.accessorySpeakerMessageRow)
        accessorySpeakerNoMessages = findViewById(R.id.accessorySpeakerNoMessages)
        hintL2 = findViewById(R.id.hintL2)
        // Tappable while a message is going out, so the stop is one touch from the indicator.
        hintL2.setOnClickListener { if (SpeakerBroadcast.live) showAccessoryPanel() }
        accessorySpeakerRepeat = findViewById(R.id.accessorySpeakerRepeat)
        accessorySpeakerRepeat.setOnClickListener {
            speakerRepeatArmed = !speakerRepeatArmed
            AppLog.i(TAG, "repeat ×${SpeakerBroadcast.REPEAT_TIMES} " +
                if (speakerRepeatArmed) "armed" else "off")
            renderAccessoryPanel()
        }
        accessorySpeakerMsgPills = listOf(
            findViewById(R.id.accessorySpeakerMsg1),
            findViewById(R.id.accessorySpeakerMsg2),
            findViewById(R.id.accessorySpeakerMsg3),
        )
        accessorySpeakerMsgPills.forEachIndexed { i, pill ->
            pill.setOnClickListener { onMessagePillTapped(i + 1) }
        }
        // The panel repaints as the upload runs and when the message ends by itself.
        SpeakerBroadcast.onChanged = {
            renderOnAirChip()
            renderAccessoryPanel()
        }
        accessorySpeakerVolumeLabel = findViewById(R.id.accessorySpeakerVolumeLabel)
        accessorySpeakerVolume = findViewById(R.id.accessorySpeakerVolume)
        accessorySpeakerStatus = findViewById(R.id.accessorySpeakerStatus)
        accessorySpeakerTalk = findViewById(R.id.accessorySpeakerTalk)
        // HOLD, not tap. The touch listener is the control: down opens the channel, up or cancel
        // closes it. A click listener would be a toggle, and a toggle can leave the microphone
        // open — see SpeakerTalk.
        accessorySpeakerTalk.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { v.isPressed = true; startTalking() }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> { v.isPressed = false; SpeakerTalk.stop() }
            }
            true
        }
        SpeakerTalk.onChanged = {
            runOnUiThread {
                renderOnAirChip()
                renderAccessoryPanel()
            }
        }

        // The panel eats every touch that lands on it. Without this the taps between the
        // controls fall through to the video and drop a marker — the same hazard noted
        // for the warning banner's tap.
        accessoryPanel.setOnClickListener { }
        findViewById<TextView>(R.id.accessoryClose).setOnClickListener { hideAccessoryPanel() }

        accessoryLightLow.setOnClickListener { requestLightMode(LIGHT_LOW) }
        accessoryLightHigh.setOnClickListener { requestLightMode(LIGHT_HIGH) }
        accessoryLightBlink.setOnClickListener { requestLightMode(LIGHT_STROBE) }

        accessorySpeakerVolume.max = AircraftAccessories.speakerVolumeRange.last
        accessorySpeakerVolume.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar, p: Int, byUser: Boolean) {
                    if (byUser) accessorySpeakerVolumeLabel.text = "Volume  $p"
                }

                override fun onStartTrackingTouch(bar: android.widget.SeekBar) {
                    accessorySliderDragging = true
                }

                override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                    accessorySliderDragging = false
                    if (!claimAccessoryWrite("volume")) return
                    SpeakerMessages.saveVolume(this@TAKPilot2GoFlightActivity, bar.progress)
                    AircraftAccessories.setSpeakerVolume(bar.progress) { confirmed ->
                        runOnUiThread {
                            releaseAccessoryWrite("volume")
                            renderAccessoryPanel()
                            if (!confirmed) showNotice("The speaker refused", refused = true)
                        }
                    }
                }
            })
    }

    /**
     * The three brightness steps the pills write.
     *
     * Round numbers on the payload's own 0..100 scale, not thirds of it: LOW is meant to be
     * usable at close range without blinding anyone, HIGH is everything the light has.
     */
    /**
     * THE LIGHT'S THREE STATES, as the panel offers them. Each pill IS one of these, and each
     * pill is a toggle: press the one the light is already in and the light goes dark.
     */
    private val LIGHT_LOW = "low"
    private val LIGHT_HIGH = "high"
    private val LIGHT_STROBE = "strobe"

    private val BRIGHTNESS_LOW = 20
    private val BRIGHTNESS_HIGH = 100

    /**
     * Which of the three the aircraft is in, or null when the light is dark or has not answered.
     *
     * Brightness is read as a BAND and not an exact value, so a level set from DJI Pilot 2 still
     * lights the right pill. A blinking light is STROBE whatever its brightness — the pilot's
     * question about a flashing light is not how bright it is.
     */
    private fun currentLightMode(): String? {
        val on = AircraftAccessories.lightOn ?: return null
        if (!on) return null
        if (AircraftAccessories.lightBlink == true) return LIGHT_STROBE
        val bright = AircraftAccessories.lightBrightness ?: return null
        return if (bright <= 50) LIGHT_LOW else LIGHT_HIGH
    }

    /**
     * Puts the light into [mode], or turns it off when it is already there.
     *
     * All three pills share ONE in-flight claim, because they are one control: a press of HIGH
     * while LOW is still being written is the pilot changing their mind, not a second control.
     */
    /**
     * Opens the live channel, after making sure this application may use the microphone.
     *
     * ⚠ THE PERMISSION IS CHECKED HERE AND NOT ASSUMED. `RECORD_AUDIO` is declared in the
     * manifest but it is a RUNTIME permission, and a denied one makes the microphone produce
     * silence with no error at all — the pilot would hold the button, see ON AIR, and broadcast
     * nothing. The request is not made mid-press: a dialog over the flight screen while the
     * pilot is holding a button is its own hazard, so the press is refused with a notice and the
     * pilot can grant it between presses.
     */
    private fun startTalking() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            AppLog.w(TAG, "talk refused: no microphone permission")
            showNotice("No microphone permission", refused = true)
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        SpeakerTalk.start { ok ->
            runOnUiThread {
                renderAccessoryPanel()
                if (!ok) showNotice("The speaker did not take the talk", refused = true)
            }
        }
    }

    /**
     * Sends the message in [slot] and plays it, or stops it when it is the one going out.
     *
     * ⚠ ALL THREE PILLS SHARE ONE CLAIM, because they are one control — pressing SHELTER while
     * EVACUATE is uploading is the pilot changing their mind, not a second control being worked.
     * [SpeakerBroadcast] serialises the rest; a press while another is in flight is refused with
     * a reason rather than queued, because a queued broadcast is one nobody asked for by the
     * time it goes out.
     */
    private fun onMessagePillTapped(slot: Int) {
        if (SpeakerBroadcast.activeSlot == slot && SpeakerBroadcast.live) {
            AppLog.i(TAG, "message slot $slot: stopping")
            SpeakerBroadcast.stop { runOnUiThread { renderAccessoryPanel() } }
            return
        }
        if (!claimAccessoryWrite("message")) return
        SpeakerBroadcast.play(this, slot, repeat = speakerRepeatArmed) { ok, why ->
            runOnUiThread {
                releaseAccessoryWrite("message")
                renderAccessoryPanel()
                if (!ok) showNotice(why ?: "The message did not go out", refused = true)
            }
        }
    }

    private fun requestLightMode(mode: String) {
        if (!claimAccessoryWrite("light")) return
        val turningOff = currentLightMode() == mode
        val brightness = when {
            turningOff -> null
            // STROBE IS ALWAYS FULL BRIGHTNESS — see the layout's note. A blinking light is one
            // meant to be seen, and it does not share the LOW/HIGH choice.
            mode == LIGHT_STROBE -> BRIGHTNESS_HIGH
            mode == LIGHT_LOW -> BRIGHTNESS_LOW
            else -> BRIGHTNESS_HIGH
        }
        val target = if (turningOff) null else mode
        accessoryPending[LIGHT_LOW] = target == LIGHT_LOW
        accessoryPending[LIGHT_HIGH] = target == LIGHT_HIGH
        accessoryPending[LIGHT_STROBE] = target == LIGHT_STROBE
        renderAccessoryPanel()
        AircraftAccessories.applyLightState(
            on = !turningOff,
            brightness = brightness,
            blink = mode == LIGHT_STROBE && !turningOff,
        ) { confirmed ->
            runOnUiThread {
                releaseAccessoryWrite("light")
                clearPending(LIGHT_LOW)
                clearPending(LIGHT_HIGH)
                clearPending(LIGHT_STROBE)
                renderAccessoryPanel()
                if (!confirmed) showNotice("The light did not change", refused = true)
            }
        }
    }

    private val accessoryWritesInFlight = HashSet<String>()

    /**
     * What a control is SHOWING while its write is in flight, before the aircraft has answered.
     *
     * ⚠ THIS IS A DELIBERATE EXCEPTION TO "SHOW WHAT THE AIRCRAFT HOLDS", and
     * it is allowed here for reasons that do not generalise:
     *
     *  - The round trip is 240-840ms (measured), which is long enough that a
     *    pill that does not move reads as a control that did not work — and a pilot's first
     *    reaction is to press it again.
     *  - NOTHING HERE IS FLIGHT-CRITICAL. This is a lamp and a loudspeaker.
     *  - The guess cannot outlive the truth. It is dropped the moment the write resolves, a
     *    refusal reverts it AND says so on the notice, and the payload's push feed repaints the
     *    panel regardless.
     *
     * ⚠ DO NOT COPY THIS TO A CAMERA, A GIMBAL OR THE FLIGHT CONTROLLER. On those, a control that
     * claims a state the aircraft never took is the exact fault that rule exists to stop.
     */
    private val accessoryPending = HashMap<String, Boolean>()

    /** [actual] unless the control is mid-write, in which case what the pilot asked for. */
    private fun pendingOr(control: String, actual: Boolean?): Boolean? =
        accessoryPending[control] ?: actual

    /** Shows the asked-for state at once, then lets the write resolve it. */
    private fun showPending(control: String, value: Boolean) {
        accessoryPending[control] = value
        renderAccessoryPanel()
    }

    private fun clearPending(control: String) {
        accessoryPending.remove(control)
    }

    /**
     * @return true when the caller may proceed with a write to [control].
     *
     * ⚠ PER CONTROL, NOT PER PANEL. It was one flag for the whole panel, and a write that went
     * unanswered — which a write of an unchanged value always did — made every OTHER control
     * dead until the watchdog expired. One stuck accessory must never take the rest of them with
     * it.
     */
    private fun claimAccessoryWrite(control: String): Boolean {
        if (!accessoryWritesInFlight.add(control)) {
            AppLog.v(TAG, "accessory: $control press ignored, its write is still in flight")
            return false
        }
        return true
    }

    private fun releaseAccessoryWrite(control: String) {
        accessoryWritesInFlight.remove(control)
    }

    private fun toggleAccessoryPanel() {
        if (accessoryPanel.visibility == View.VISIBLE) hideAccessoryPanel() else showAccessoryPanel()
    }

    /**
     * Opens the panel on the AIRCRAFT'S state, never on the last one this screen drew.
     *
     * The read is issued every time the panel opens because the light and the speaker keep their
     * settings across an app restart and these fields do not — the trap that opened the flight
     * screen claiming "1X, white hot" while the aircraft was in thermal.
     */
    private fun showAccessoryPanel() {
        accessoryPanel.visibility = View.VISIBLE
        renderAccessoryPanel()
        AircraftAccessories.refresh { renderAccessoryPanel() }
        // The speaker has no push feed — it is polled while the panel is open so a sound that
        // ends by itself does not leave SOUND green. Stopped again in hideAccessoryPanel.
        AircraftAccessories.startSpeakerPolling()
    }

    private fun hideAccessoryPanel() {
        accessoryPanel.visibility = View.GONE
        accessoryPending.clear()
        AircraftAccessories.stopSpeakerPolling()
        // ⚠ CLOSING THE PANEL CLOSES THE MICROPHONE. See SpeakerTalk: no route through this
        // application may leave the aircraft broadcasting.
        SpeakerTalk.stop()
        accessorySliderDragging = false
    }

    /**
     * Paints the panel from what the aircraft has answered.
     *
     * ⚠ NOTHING HERE INVENTS A STATE. A null reads as "--" and its control is disabled, because
     * a light pill that says OFF when nothing has answered is a claim about the aircraft, and on
     * an aircraft in the dark it is the wrong one.
     */
    private fun renderAccessoryPanel() {
        renderOnAirChip()
        if (accessoryPanel.visibility != View.VISIBLE) return

        val hasLight = AircraftAccessories.lightOn != null
        val hasSpeaker = AircraftAccessories.speakerAnswered
        accessoryLightBlock.visibility = if (hasLight) View.VISIBLE else View.GONE
        accessorySpeakerBlock.visibility = if (hasSpeaker) View.VISIBLE else View.GONE
        accessoryNone.visibility = if (hasLight || hasSpeaker) View.GONE else View.VISIBLE
        if (!hasLight && !hasSpeaker) {
            accessoryNone.text = "No light and no speaker answered.\nCheck they are fitted and powered."
            return
        }

        if (hasLight) {
            val mode = currentLightMode()
            val known = AircraftAccessories.lightOn != null
            accessoryLightBrightnessRow.visibility = View.VISIBLE
            // Unknown leaves all three amber rather than claiming the light is dark.
            renderStatePill(accessoryLightLow,
                pendingOr(LIGHT_LOW, if (known) mode == LIGHT_LOW else null))
            renderStatePill(accessoryLightHigh,
                pendingOr(LIGHT_HIGH, if (known) mode == LIGHT_HIGH else null))
            renderStatePill(accessoryLightBlink,
                pendingOr(LIGHT_STROBE, if (known) mode == LIGHT_STROBE else null))
        }

        if (hasSpeaker) {
            // THE MESSAGE PILLS. Green is the one going out; amber is its upload; everything
            // else grey. Unlike the light there is NO optimistic green here — a message is not
            // "on" until the aircraft has the bytes, and painting it early would claim a
            // broadcast that has not happened.
            val messages = SpeakerMessages.all(this)
            val any = messages.isNotEmpty()
            accessorySpeakerMessageRow.visibility = if (any) View.VISIBLE else View.GONE
            accessorySpeakerNoMessages.visibility = if (any) View.GONE else View.VISIBLE
            val active = SpeakerBroadcast.activeSlot
            val sending = SpeakerBroadcast.phase == SpeakerBroadcast.Phase.UPLOADING ||
                SpeakerBroadcast.phase == SpeakerBroadcast.Phase.PREPARING ||
                SpeakerBroadcast.phase == SpeakerBroadcast.Phase.STARTING ||
                SpeakerBroadcast.phase == SpeakerBroadcast.Phase.WAITING
            // The toggle cannot be changed mid-run: the run's length is already decided.
            renderStatePill(accessorySpeakerRepeat, speakerRepeatArmed)
            accessorySpeakerRepeat.isEnabled = !SpeakerBroadcast.live
            accessorySpeakerMsgPills.forEachIndexed { i, pill ->
                val slot = i + 1
                val message = messages.firstOrNull { it.slot == slot }
                pill.visibility = if (message == null) View.GONE else View.VISIBLE
                if (message == null) return@forEachIndexed
                pill.text = message.label
                renderStatePill(pill, when {
                    active != slot -> false
                    sending -> null                       // amber: on its way, or between repeats
                    else -> SpeakerBroadcast.phase == SpeakerBroadcast.Phase.PLAYING
                })
            }

            // ⚠ THIS WAS DELETED BY A REFACTOR AND NOT NOTICED UNTIL A FLIGHT. Nothing set the
            // slider from the aircraft and nothing enabled it, so it sat at 0 whatever the
            // speaker was actually doing — the pilot saw the volume "resetting to 0" on
            // re-entry. The aircraft had NOT lost it: it reported vol=100 across every screen
            // cycle in the same log. The setting was fine; the control was blind.
            val vol = AircraftAccessories.speakerVolume
            accessorySpeakerVolume.isEnabled = vol != null
            if (!accessorySliderDragging) {
                // The remembered value stands in until the aircraft answers, so the slider never
                // shows a 0 that was never true.
                val show = vol ?: SpeakerMessages.savedVolume(this)
                accessorySpeakerVolume.progress = show.coerceIn(0, accessorySpeakerVolume.max)
            }
            accessorySpeakerVolumeLabel.text =
                if (vol == null) "Volume  ${SpeakerMessages.savedVolume(this)}  (asking…)"
                else "Volume  $vol"

            val status = AircraftAccessories.speakerStatus
            // ⚠ THE TALK CONTROL PAINTS ITSELF HERE, and losing these two lines in a refactor
            // made a WORKING push-to-talk read as a dead button — it was reported as
            // lost functionality while the log showed it going live and sending frames. A
            // control with no feedback is indistinguishable from a broken one.
            // ⚠ OPENING IS ITS OWN STATE. The aircraft takes 2-3s to start carrying the voice
            // (measured in flight) while this app is sending within ~80ms, so a pill
            // that said ON AIR on the press claimed a broadcast that had not started and the
            // pilot spoke into a dead channel. Amber while it opens, green when it carries.
            // Three states, and the third one matters: after the release the aircraft is still
            // speaking the pilot's last words for about 1.5s (see SpeakerTalk.draining). Going
            // grey there would tell the pilot the aircraft is silent while it is not.
            renderStatePill(accessorySpeakerTalk, when {
                SpeakerTalk.carrying -> true
                SpeakerTalk.talking || SpeakerTalk.draining -> null
                else -> false
            })
            accessorySpeakerTalk.text = when {
                SpeakerTalk.carrying -> "ON AIR"
                SpeakerTalk.talking -> "OPENING…  WAIT"
                SpeakerTalk.draining -> "FINISHING…"
                else -> "HOLD TO TALK"
            }

            val label = SpeakerBroadcast.activeSlot
                ?.let { slot -> messages.firstOrNull { it.slot == slot }?.label }
                ?: ""
            accessorySpeakerStatus.text = when (SpeakerBroadcast.phase) {
                SpeakerBroadcast.Phase.PREPARING -> "Preparing $label…"
                SpeakerBroadcast.Phase.UPLOADING ->
                    "Sending $label…  ${SpeakerBroadcast.progressPct}%"
                SpeakerBroadcast.Phase.STARTING -> "Starting $label…"
                SpeakerBroadcast.Phase.PLAYING ->
                    if (SpeakerBroadcast.totalPlays > 1)
                        "Playing $label.  ${SpeakerBroadcast.playNumber} of " +
                            "${SpeakerBroadcast.totalPlays}."
                    else "Playing $label."
                SpeakerBroadcast.Phase.WAITING ->
                    "$label again in ${SpeakerBroadcast.secondsToNextPlay}s.  " +
                        "${SpeakerBroadcast.playsLeft} left."
                SpeakerBroadcast.Phase.FAILED ->
                    "$label did not go out: ${SpeakerBroadcast.failure ?: "refused"}"
                SpeakerBroadcast.Phase.IDLE -> when {
                    !any -> ""
                    status == MegaphoneStatus.IN_EXCEPTION -> "The speaker reports a fault."
                    status == null -> "The speaker did not report its state."
                    speakerRepeatArmed ->
                        "Ready.  The next message goes out " +
                            "${SpeakerBroadcast.REPEAT_TIMES} times."
                    else -> "Ready.  ${messages.size} message" +
                        if (messages.size == 1) "." else "s."
                }
            }
        }
    }

    /**
     * Paints one accessory pill from a three-state value: on, off, or not yet known.
     *
     * ⚠ THE LABEL NEVER CHANGES — the colour is the state. This is the flight screen's own idiom
     * (the AR and IR pills) and it is here because the panel started out doing the opposite: the
     * light pill read "ON" or "OFF", and a pilot read the word "OFF"
     * as what the button WOULD DO and never pressed it, so the light was never switched on
     * while an SDK fault that was not there was chased instead.
     *
     * Green is on, neutral is off, amber is unknown — unknown is its own state and is
     * never collapsed into off. Every pill in the panel goes through here so the three cannot
     * drift apart.
     */
    /**
     * Turns the L2 hint chip into an ON AIR light while the aircraft is speaking.
     *
     * ⚠ NOT GATED ON THE PANEL BEING OPEN, unlike [renderAccessoryPanel]. The whole point is the
     * case where it is shut.
     */
    private fun renderOnAirChip() {
        if (!::hintL2.isInitialized) return
        // ⚠ THE CHIP CARRIES THE SAME THREE STATES AS THE PANEL, not just on/off. It used to go
        // GREEN "ON AIR" the moment anything started — including the ~1.5s while the channel is
        // still opening and the ~1.6s tail after a release — so from the flight screen the pilot
        // could not tell "the aircraft is speaking" from "wait" or from "finishing". Green means
        // sound is coming out of the aircraft NOW; amber means something is happening but it is
        // not that. Same colour language as every pill in the panel.
        val label: String?
        val green: Boolean
        when {
            SpeakerTalk.carrying -> { label = "ON AIR"; green = true }
            SpeakerTalk.draining -> { label = "FINISHING"; green = false }
            SpeakerTalk.talking -> { label = "OPENING"; green = false }
            SpeakerBroadcast.phase == SpeakerBroadcast.Phase.PLAYING -> {
                label = "ON AIR"; green = true
            }
            // The gap between repeats stays lit: the run is not over and the aircraft WILL speak
            // again. Flight-tested that way — do not quietly change it to grey.
            SpeakerBroadcast.phase == SpeakerBroadcast.Phase.WAITING -> {
                label = "AGAIN ${SpeakerBroadcast.secondsToNextPlay}s"; green = true
            }
            SpeakerBroadcast.live -> { label = "SENDING"; green = false }
            else -> { label = null; green = false }
        }

        if (label == null) {
            hintL2.text = "◀ ACC"
            hintL2.setBackgroundResource(0)
            hintL2.setBackgroundColor(0x66000000)
            return
        }
        hintL2.text = "◀ ACC ● $label"
        hintL2.setBackgroundResource(
            if (green) R.drawable.bg_pill_active else R.drawable.bg_pill_unknown)
    }

    private fun renderStatePill(pill: TextView, state: Boolean?) {
        // ⚠ NO-OP WHEN NOTHING CHANGED. The panel now repaints once a second through a repeat
        // run, and re-setting a background on a view the pilot is holding risks cancelling the
        // touch — which on HOLD TO TALK would cut the pilot off mid-sentence.
        val key = state?.toString() ?: "unknown"
        if (pill.getTag(R.id.accessoryPanel) == key) return
        pill.setTag(R.id.accessoryPanel, key)
        pill.setBackgroundResource(
            when (state) {
                true -> R.drawable.bg_pill_active
                false -> R.drawable.bg_zoom_pill
                null -> R.drawable.bg_pill_unknown
            }
        )
        // Unknown is still TOUCHABLE where the control has a sensible first action; it is the
        // colour that says the state is not known, not a dead button.
        pill.alpha = if (state == null) 0.75f else 1f
    }

    /**
     * THE VISION-ASSIST VIEW — the aircraft's obstacle-sensing cameras, sharing the mini-map's
     * slot. R3 swaps between them.
     *
     * ⚠ IT IS ENABLED WHENEVER THE AIRCRAFT OFFERS IT, not when the pilot looks at it. Enabling
     * is a WRITE, done once here on screen entry and never on a timer. Keeping
     * the stream warm is what makes the R3 swap instant — the alternative, starting it on the
     * swap, would put the measured start-up delay in front of a pilot who has just asked to see
     * something. Measured: no effect on the main video feed. ⚠ If range testing ever
     * shows the two streams competing, `ICameraStreamManager.setStreamPriority` and
     * `setStreamEncoderBitrate` are the levers, and enabling on demand is the fallback.
     *
     * ⚠ NOTHING STREAMS ON THE GROUND. The vision system does not produce frames until the
     * aircraft is airborne, so a blank view before takeoff is correct and must not be reported
     * as unavailable.
     */
    private fun setupVisionAssist() {
        visionAssistView = findViewById(R.id.flightVisionAssist)
        visionAssistLabel = findViewById(R.id.flightVisionAssistLabel)
        visionAssistView.onFirstFrame = { w, h ->
            AppLog.i(TAG, "vision assist stream is live at ${w}x$h")
            runOnUiThread { renderVisionAssistLabel() }
        }
        VisionAssist.onChanged = { renderVisionAssistLabel() }
        VisionAssist.start()
        VisionAssist.setEnabled(true) { ok, why ->
            if (!ok) AppLog.w(TAG, "vision assist unavailable: ${why ?: "no reason given"}")
        }
        // AUTO is DJI's own follow-the-direction-of-travel mode. There is no direction control on
        // the screen: a pilot who wants a particular side flies that way, and a manual direction
        // is a setting that can be left wrong.
        VisionAssist.setDirection(VisionAssistDirection.AUTO)
        renderVisionAssistLabel()
    }

    /**
     * Swaps the corner between the map and the vision view. R3.
     *
     * ⚠ IT REFUSES RATHER THAN SHOWING A DEAD BOX when the aircraft has no vision assist — an
     * empty grey square with no explanation is worse than the map the pilot already had.
     */
    private fun toggleVisionAssist() {
        if (!::visionAssistView.isInitialized) return
        val showingVision = visionAssistView.visibility == View.VISIBLE
        if (!showingVision && VisionAssist.available.isEmpty()) {
            AppLog.i(TAG, "R3: this aircraft offers no vision assist")
            showNotice("This aircraft has no vision assist view", refused = true)
            return
        }
        visionAssistView.visibility = if (showingVision) View.GONE else View.VISIBLE
        mapView.visibility = if (showingVision) View.VISIBLE else View.GONE
        // The map's own WIDE/NEAR control belongs to the map, not to the corner.
        findViewById<TextView>(R.id.flightMapZoomButton)?.visibility =
            if (showingVision) View.VISIBLE else View.GONE
        AppLog.i(TAG, "R3: corner shows ${if (showingVision) "the map" else "vision assist"}")
        renderVisionAssistLabel()
    }

    private fun renderVisionAssistLabel() {
        if (!::visionAssistLabel.isInitialized) return
        val showingVision = visionAssistView.visibility == View.VISIBLE
        // One word and an arrowhead, exactly like the L1/L2/L3 chips. The word names what is in
        // the corner right now; "R3" is gone because the arrow points at the button, which is
        // what the arrow is FOR.
        visionAssistLabel.text = when {
            !showingVision -> "MAP ▶"
            // ⚠ On the ground the vision system sends nothing, so an empty box would read as a
            // fault. This says which it is without claiming a picture that is not there.
            visionAssistView.frames == 0L -> "WAITING ▶"
            else -> "VISION ▶"
        }
    }

    private fun renderWarning() {
        val d = FlightWarnings.display()
        if (d == null) {
            flightDiagnosticsRow.visibility = View.GONE
            // Collapse with the banner. A pilot who opened it for the last set of faults must
            // not have the next one — a different fault, possibly worse — arrive pre-expanded
            // across the video.
            warningExpanded = false
            // The same rule for the close: nothing is standing, so nothing stays closed. The
            // next fault gets a fresh banner even if its text repeats what was closed before.
            warningDismissedSignature = null
            return
        }
        // Closed by the pilot, and the aircraft still says the SAME thing. Any change to the
        // set falls through here and repaints — see [warningDismissedSignature].
        //
        // The closable part of the banner is the aircraft's lines. DEBUG LOG ON is never
        // closed (design decision): with the rest dismissed it stands alone, amber, until
        // the log is turned off; a banner that is ONLY that line has nothing for the ✕ to do.
        val closable = FlightWarnings.dismissable(d.all)
        val signature = closable.joinToString("\n")
        val debugLine = d.all.firstOrNull { it !in closable }
        if (closable.isEmpty() || signature == warningDismissedSignature) {
            if (debugLine == null) {
                flightDiagnosticsRow.visibility = View.GONE
                return
            }
            flightDiagnostics.text = debugLine
            // Nothing left to close, so no ✕ — a control that does nothing teaches the pilot
            // that the banner does not mean what it says.
            flightDiagnosticsClose.visibility = View.GONE
            flightDiagnosticsRow.background?.setTint(
                ContextCompat.getColor(applicationContext, R.color.tp_warn_banner_amber))
            flightDiagnosticsRow.visibility = View.VISIBLE
            return
        }
        warningDismissedSignature = null
        flightDiagnosticsClose.visibility = View.VISIBLE
        // Collapsed: the worst warning and a count. Expanded: every warning on its own line,
        // worst first. The arrow is the only hint that the banner opens at all, so it is on the
        // line whenever there is something behind the count.
        //
        // The banner sits at the bottom-left and opens UPWARD: collapsed,
        // the arrow points up to say "more above"; open, the list stands above the arrow that
        // closes it, worst first from the top.
        val more = d.all.size > 1
        flightDiagnostics.text = when {
            warningExpanded -> d.all.joinToString("\n") + "\n▾"
            more -> "${d.text}  ▴"
            else -> d.text
        }
        // Severity goes on the BACKGROUND and the text stays white.
        // Tinting the text instead left a red-on-dark-red message that was the hardest thing
        // on the screen to read at the moment it mattered.
        flightDiagnosticsRow.background?.setTint(
            ContextCompat.getColor(
                applicationContext,
                if (d.red) R.color.tp_warn_banner_red else R.color.tp_warn_banner_amber,
            )
        )
        flightDiagnosticsRow.visibility = View.VISIBLE
    }

    /**
     * Ends the photo sequence and gives the shutter back to the pilot.
     *
     * EVERY terminal branch calls this — the mode-switch refusal, the restore success, and the
     * restore giving up. A branch that forgets leaves the button dead for the rest of the
     * session, which is worse than the overlap this guard exists to stop.
     */
    private fun endPhotoSequence() {
        photoSequenceActive = false
        runOnUiThread { setShutterBusy(false) }
    }

    /**
     * Dims the shutter and stops it taking touches while a photo is in progress.
     *
     * The mode switch alone takes about 1.5s. A button that does nothing visible for that long
     * invites a second press, and the second press is what produced "Photo failed: null"
     * (measured). The guard stops the overlap; this is what stops the pilot needing it.
     */
    private fun setShutterBusy(busy: Boolean) {
        flightShootPhotoButton?.apply {
            isEnabled = !busy
            alpha = if (busy) 0.4f else 1f
        }
    }

    /**
     * Error text that is never the word "null".
     *
     * description() is a Java method, thus Kotlin sees a platform type and a null goes through
     * to a pilot-facing Toast as "null". This aircraft returns null for real refusals — it is
     * what made "Photo failed: null" and what crashed the flight screen on a metering write.
     */
    private fun describeError(error: IDJIError?): String =
        error?.description()?.takeIf { it.isNotBlank() } ?: "refused (no reason given)"

    private fun restoreVideoModeAfterPhoto(attempt: Int = 1) {
        if (TakBridgeHolder.photoInProgress() && attempt < PHOTO_RESTORE_MAX_ATTEMPTS) {
            handler.postDelayed(
                { restoreVideoModeAfterPhoto(attempt + 1) }, PHOTO_RESTORE_RETRY_MS)
            return
        }
        AppLog.i(REC_TAG, "photo: restoring VIDEO_NORMAL + PROGRAM auto-exposure (attempt $attempt)")
        ExposureController.applyDefaults(applicationContext) { err ->
            if (err == null) {
                if (attempt > 1) AppLog.i(REC_TAG, "photo: VIDEO mode restored on attempt $attempt")
                endPhotoSequence()
                return@applyDefaults
            }
            if (attempt < PHOTO_RESTORE_MAX_ATTEMPTS) {
                AppLog.w(REC_TAG, "photo: VIDEO mode restore refused (${describeError(err)}) — " +
                    "camera still busy, retrying (attempt $attempt)")
                handler.postDelayed(
                    { restoreVideoModeAfterPhoto(attempt + 1) }, PHOTO_RESTORE_RETRY_MS)
            } else {
                AppLog.e(REC_TAG, "photo: VIDEO mode restore FAILED after $attempt attempts " +
                    "(${describeError(err)}) — camera left in PHOTO mode")
                endPhotoSequence()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Camera stuck in photo mode — tap Video Re-Sync or re-enter flight screen",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun performRecordAction(
        keyInfo: DJIActionKeyInfo<EmptyMsg, EmptyMsg>,
        successMsg: String,
        failurePrefix: String,
        op: String,
    ) {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(keyInfo, MAIN_CAM),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) {
                    AppLog.i(REC_TAG, "$op result: OK")
                    runOnUiThread {
                        Toast.makeText(this@TAKPilot2GoFlightActivity, successMsg, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.i(REC_TAG, "$op result: ${describeError(error)}")
                    runOnUiThread {
                        Toast.makeText(this@TAKPilot2GoFlightActivity,
                            "$failurePrefix: ${describeError(error)}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    /** Aircraft marker icon: a cyan heading arrow (rasterized from the vector), sized for the
     *  mini-map — small enough to point accurately, big enough to read. */
    private fun decodeAircraftIcon(): Bitmap {
        val sizePx = (AIRCRAFT_ICON_DP * resources.displayMetrics.density).toInt()
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_self_marker)!!
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }

    /** Home-point marker icon, small — stationary reference point, doesn't need to read as
     *  large as the aircraft. */
    private fun decodeHomeIcon(): Bitmap {
        val sizePx = (HOME_ICON_DP * resources.displayMetrics.density).toInt()
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_home_marker)!!
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }

    private fun updateHud() {
        val hud = TakBridgeHolder.hud()
        val takOk = TakManager.getInstance().isConnected

        // A contact going stale is a rendering change that no inbound CoT announces, so the
        // marker layer needs a clock of its own. Piggybacks on this tick; no-ops unless an
        // icon actually needs regenerating. Deliberately above the no-GPS-fix early return —
        // other operators' markers don't depend on OUR aircraft having a fix.
        com.dji.sdk.sample.tak.TakMapMarkers.tick()

        // Same reasoning as the marker tick: the warning banner's hold and its queue advance
        // with the clock, not only when a warning changes, so it has to be re-asked. Also above
        // the no-GPS-fix early return — losing the fix is itself a condition worth showing.
        //
        // "DEBUG LOG ON" goes through the WARNING BANNER (design decision): the file log
        // costs the screen some jitter, and a pilot who did not turn it on must be made to
        // look and go and turn it off. Read on the tick so a change on the Debug screen shows
        // within half a second of coming back; renderWarning() draws it.
        FlightWarnings.debugLogOn = AppLog.enabled
        renderWarning()
        logResourcesPeriodically()
        updateResourceRow()

        // Elevated connection (cert B) health. Only meaningful once configured — an
        // unconfigured split has no video channel to be "down". Fires the amber notice on a
        // CHANGE only (see lastVideoChannelDown's doc), and only while the stream is actually
        // running — a down Elevated connection with nothing streaming has nothing to warn about
        // yet. Above the no-GPS-fix early return on purpose: neither this nor the countdown
        // below depends on the AIRCRAFT having a fix.
        if (TakManager.getInstance().isVideoChannelConfigured) {
            val videoChannelDown = !TakManager.getInstance().isVideoChannelConnected
                && VideoStreamerHolder.isRunning
            if (lastVideoChannelDown == false && videoChannelDown) {
                showNotice("Video link down — feed not advertised", refused = true)
            }
            lastVideoChannelDown = videoChannelDown
        }
        // Emergency Broadcast countdown — the listener paints on start/stop; this keeps the
        // notice's mm:ss moving while it is active, on the same tick as everything else on
        // this screen.
        if (TakManager.getInstance().isEmergencyBroadcastActive) {
            // Repaints the whole line, not just the clock: the NO VIDEO STREAM state above is
            // decided here, on the tick, so the notice follows the stream and not the button.
            paintEmergencyBroadcast(true, TakManager.getInstance().emergencyBroadcastExpiresAtEpochMs())
        }

        fpvClock.text = clockFormat.format(java.util.Date())

        // What the AIRCRAFT holds, not what Pre-Flight asked for. "RTH --" until it answers:
        // an unknown return height must not be shown as a number the pilot can rely on.
        fpvRthAltitude.text = hud?.rthHeightM
            ?.let { "RTH ${Units.feet(it.toDouble())}" } ?: "RTH --"
        // AMBER WHILE UNKNOWN. This line once printed "RTH --" in the
        // ordinary white of every other readout, so "the aircraft confirmed this height" and
        // "the aircraft never answered" looked identical. That is the readout behind an
        // incident in which a pilot flew two sorties believing an RTH altitude the aircraft
        // did not hold.
        fpvRthAltitude.setTextColor(
            ContextCompat.getColor(
                applicationContext,
                if (hud?.rthHeightM != null) R.color.tp_text_secondary
                else R.color.tp_state_unknown,
            )
        )

        // Directly under RTH: how far home is, and how high the aircraft will climb to get
        // there. Its own view rather than a line in the telemetry block.
        val homeKnown = hud != null && hud.hasFix && hud.homeSet
        fpvHomeDistance.text = if (homeKnown) {
            val dist = CameraSlantPoint.distanceMeters(hud!!.homeLat, hud.homeLon, hud.lat, hud.lon)
            val bearing = CameraSlantPoint.initialBearingDeg(hud.homeLat, hud.homeLon, hud.lat, hud.lon)
            "HOME %s  %03.0f°T".format(Units.feet(dist), bearing)
        } else {
            "HOME — ft  —°T"
        }
        // Grey like RTH and the airspace row (design decision) — the three advisory
        // lines read as one block. Amber while unknown, the same known/unknown convention
        // as RTH: dashes in the ordinary colour would make "no home point" look routine.
        fpvHomeDistance.setTextColor(
            ContextCompat.getColor(
                applicationContext,
                if (homeKnown) R.color.tp_text_secondary else R.color.tp_state_unknown,
            )
        )

        toolbarBattery.setPercent(hud?.batteryPct)
        // Re-asked every tick, not just at entry: the bands pick up the aircraft's read-back
        // the moment it lands (a connect can finish after this screen opens), and setBands
        // no-ops when nothing moved. See refreshBatteryBands for why the values are the
        // AIRCRAFT's.
        refreshBatteryBands()
        updateAntennaAim(hud)
        // The lights read is asked again ONLY while the answer is unknown. The aircraft link
        // comes up after this screen does, so a single read at setup left the pill greyed for
        // the whole flight (measured) — and a grey pill is a control the pilot
        // cannot use, because a write with no known state is refused. This stops the moment
        // the aircraft answers, and it is a READ, which is allowed on a tick — only writes are barred from a timer.
        if (AircraftLights.motorLedsOn == null) {
            AircraftLights.refresh { renderLightsButton() }
        }
        // Same reason, same shape: adopt the camera's real lens/zoom/palette once the
        // aircraft is talking. See syncCameraFromAircraft.
        if (!cameraStateSynced) syncCameraFromAircraft()

        // Show the real satellite count whenever telemetry exists, even below lock threshold —
        // "—" used to mean "no fix," but visually that's indistinguishable from "no telemetry
        // at all," and a pilot watching the count creep up while acquiring a fix is more useful
        // than it vanishing. The icon's color still carries the fix/no-fix distinction.
        toolbarGps.text = hud?.satCount?.toString() ?: "—"
        toolbarGpsIcon.setColorFilter(
            if (hud != null && hud.hasFix) 0xFF4CAF50.toInt() else 0xFFAAAAAA.toInt()
        )

        toolbarTakDot.setColorFilter(if (takOk) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())

        recordToggle.setRecording(hud?.isRecording == true)
        renderMediaMode(hud?.cameraMode)
        // THE SHUTTER LOCKS WHILE RECORDING (a per-tick guard). A still mid-record drags the
        // camera VIDEO->PHOTO->VIDEO under the team's live feed. Merged with the photo-sequence
        // busy state rather than fighting it: recording OR an in-flight photo sequence each dim
        // the button, and the tick releases it only when neither holds.
        if (!photoSequenceActive) setShutterBusy(hud?.isRecording == true)

        // Home point: independent of the aircraft's current GPS fix (the home location, once
        // set, stays valid even if the live fix drops momentarily) — so this isn't gated behind
        // hud.hasFix like the marker/camera-follow logic below.
        val homeSet = hud?.homeSet == true
        rthButton.setImageResource(if (homeSet) R.drawable.ic_rth_home_set else R.drawable.ic_rth)
        if (homeSet) {
            // The home point does not move once set — rebuilding its GeoJSON every 500ms tick
            // was pure allocation for a value that changes once per connect.
            if (hud!!.homeLat != lastHomeLat || hud.homeLon != lastHomeLon) {
                homeSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(hud.homeLon, hud.homeLat)))
                lastHomeLat = hud.homeLat
                lastHomeLon = hud.homeLon
            }
            homeLayer?.setProperties(visibility(Property.VISIBLE))
        } else {
            homeLayer?.setProperties(visibility(Property.NONE))
            lastHomeLat = null
            lastHomeLon = null
        }
        if (homeSet && !lastHomeSet) showNotice("Home Point Set")
        lastHomeSet = homeSet

        // SignalBarsView handles its own dot color + bar count from the raw %; the text just
        // shows the bucketed value alongside it.
        val signalPct = hud?.uplinkSignalPct
        toolbarSignal.setPercent(signalPct)
        toolbarSignalText.text = if (signalPct != null) "${bucketSignalPct(signalPct)}%" else "—%"
        toolbarSignalText.alpha = if (signalPct != null) 1.0f else 0.4f

        // Computed once per tick and shared: the AGL readout and the FAA ceiling check both
        // want height above the ground *under the aircraft*, and they must never disagree
        // about it — a readout saying one number while the ceiling warning judges another
        // would be worse than having no correction at all.
        val aglReading = if (hud != null) com.dji.sdk.sample.tak.TerrainAgl.reading(this, hud)
            else com.dji.sdk.sample.tak.TerrainAgl.Reading(0.0, terrainCorrected = false, mslMeters = null)

        updateGimbalPitch(hud)

        updateFaaCeiling(hud, aglReading)

        // FOUR lines. The right-hand column has to hold the exposure block, this readout AND the
        // mini-map inside one landscape screen height, and it overflowed on a 448dp-tall phone once MSL
        // and gimbal lines were added. The two heights were merged onto one line to buy that
        // height back; they were split again because the merge was the wrong saving — see the
        // AGL/MSL note below.
        //
        // ⚠ COLUMN HEIGHT BUDGET — check this before adding anything to flightHudColumn.
        // Fixed height, worst case (FAA ceiling visible), at 12sp bold ≈ 16dp a line:
        //   paddingTop 8 (was 60 before the actions column freed the band's end)
        //   + EV slider 24 + map @dimen/flight_map_size + paddingBottom 12
        //   + margins 20 + 16 x (4 readout lines + 6 single-line views)
        // Base bucket (map 130dp): 406dp against a 411dp landscape screen height.
        // h440dp bucket (map 160dp): 436dp against a ~448dp one.
        // That is 5dp and 12dp of slack. The weighted spacer absorbs nothing at this point, and
        // overflow CLIPS THE MAP SILENTLY — no warning, no log. If a line has to be added here,
        // take the height from @dimen/flight_map_size first.
        // ⚠ A SPANNABLE, NOT A PLAIN STRING: the height figure is large and
        // its unit small, and that is carried by RelativeSizeSpans rather
        // than by separate views. OutlinedTextView draws the text layout twice, so each run is
        // outlined at its OWN size — an implementation that re-renders the string would lose it.
        val overlayText = android.text.SpannableStringBuilder().apply {
            // LINE ORDER IS DELIBERATE (design decision):
            //   1 callsign + speed   2 height   3 lat/lon   4 home
            // Height is second because it is the number a pilot checks constantly. Lat/lon and
            // home are reference figures, looked up only when somebody asks for them.
            // The clock sits below the EV slider in its own view — see fpvClock.
            // The callsign on its OWN line, the speed on the next (four
            // lines). The column has the height now that the actions have left
            // the band — see the budget in dimens.xml.
            append(currentCallsign)
            append('\n')
            append(if (hud != null) Units.mph(hud.speedMs) else "— mph")
            append('\n')
            // "AGL" only when DTED actually corrected it to height-above-terrain-below;
            // otherwise "ALT", which is what the raw number really is (height above the takeoff
            // point) — labelling an uncorrected figure AGL is exactly the inaccuracy the terrain
            // correction exists to remove, so the label moves with it. MSL is computed
            // separately and can be present while the first still reads ALT. See TerrainAgl.
            val heightStart = length
            val heightText = if (hud != null && hud.hasFix) {
                "%s %s".format(
                    Units.feet(aglReading.meters),
                    if (aglReading.terrainCorrected) "AGL" else "ALT",
                )
            } else {
                "— ft AGL"
            }
            append(heightText)
            // THE FIGURE IS LARGE AND THE UNIT IS SMALL. Height is the number a pilot checks
            // most, and at one size it was lost among its neighbours.
            //
            // Split at the FIRST space: Units.feet is "<number> ft", so everything before it is
            // the figure and everything after is the unit and the label. Guarded, because a
            // format with no space would otherwise span the whole line at the large size and
            // blow this column's height budget — which on this device is about 14dp.
            val unitAt = heightText.indexOf(' ')
            if (unitAt > 0) {
                setSpan(android.text.style.RelativeSizeSpan(HEIGHT_FIGURE_SCALE),
                    heightStart, heightStart + unitAt,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.RelativeSizeSpan(HEIGHT_UNIT_SCALE),
                    heightStart + unitAt, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // AGL AND MSL GET THEIR OWN LINES, never "AGL · MSL" on one.
            //
            // They once shared a line here, to save column height. The saving was
            // real and the cost was worse: at this column width the pair does not fit, and the
            // wrap falls between a number and its unit — "988 ft" on one line and "MSL" on the
            // next, which reads as a different quantity at a glance. Two lines cannot wrap that
            // way, and stay correct at five-digit altitudes where a wider column would break.
            // Height comes from the budget noted above.
            append('\n')
            val msl = aglReading.mslMeters
            append(if (msl != null) "%s MSL".format(Units.feet(msl)) else "— ft MSL")
            append('\n')
            // The coordinates recede. They are the line a pilot reads only when somebody asks
            // for them, and making them smaller is what lets the height stand out WITHOUT the
            // block growing — the unit and this line give back most of what the figure takes.
            val coordStart = length
            if (hud != null && hud.hasFix) {
                append("%.4f, %.4f".format(java.util.Locale.US, hud.lat, hud.lon))
            } else {
                append("—, —")
            }
            setSpan(android.text.style.RelativeSizeSpan(REFERENCE_SCALE),
                coordStart, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // NO FLIGHT TIMER HERE ANY MORE, and no home line — home moved to its own view
            // beneath the RTH height, where the two related numbers sit together.
            //
            // The timer was dropped for column height on a short landscape screen. Its elapsed
            // half is on the aircraft's own display, and its remaining half was the aircraft's
            // GoHomeAssessment estimate, which the battery gauge and the low-battery warnings
            // already cover more directly. The values stay in the Hud object and the flight
            // record; only the HUD line is gone.
        }
        // The String.format calls above still run every tick (the values are cheap to check
        // only by building the string first), but skipping the TextView assignment when the
        // result is byte-for-byte the same as last tick avoids an invalidate/relayout for
        // static telemetry — landed, hovering dead-still, or between fix updates.
        // ⚠ COMPARE THE PLAIN TEXT, NOT THE SPANNABLE. SpannableStringBuilder does not override
        // equals, so comparing the builder itself is an identity check that is ALWAYS false —
        // which would silently defeat this whole optimisation and assign on every tick. The
        // spans are a pure function of the text, so equal text means equal spans.
        val overlayPlain = overlayText.toString()
        if (overlayPlain != lastOverlayText) {
            fpvOverlayText.text = overlayText
            lastOverlayText = overlayPlain
        }

        if (hud == null || !hud.hasFix) return

        aircraftSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(hud.lon, hud.lat)))
        aircraftLayer?.setProperties(iconRotate(hud.headingDeg.toFloat()))
        // Skip the CameraPosition rebuild when nothing that feeds it has moved — parked on the
        // ground (or between fix updates) this ran the Builder/allocation twice a second for an
        // identical camera position.
        val mapZoom = currentMapZoom()
        if (hud.lat != lastMapLat || hud.lon != lastMapLon || mapZoom != lastMapZoom) {
            map?.cameraPosition = CameraPosition.Builder()
                .target(LatLng(hud.lat, hud.lon))
                .zoom(mapZoom)
                .build()
            lastMapLat = hud.lat
            lastMapLon = hud.lon
            lastMapZoom = mapZoom
        }

        // Home->aircraft line: the pilot's "which way back" reference on a map that otherwise
        // can't be panned to look around. Only meaningful once a home point exists. Guarded the
        // same way — the line only needs rebuilding when an endpoint (aircraft or home) moved.
        if (homeSet) {
            if (hud.lat != lastHomeLineAircraftLat || hud.lon != lastHomeLineAircraftLon ||
                hud.homeLat != lastHomeLineHomeLat || hud.homeLon != lastHomeLineHomeLon
            ) {
                homeLineSource?.setGeoJson(
                    LineString.fromLngLats(listOf(
                        Point.fromLngLat(hud.homeLon, hud.homeLat),
                        Point.fromLngLat(hud.lon, hud.lat),
                    ))
                )
                lastHomeLineAircraftLat = hud.lat
                lastHomeLineAircraftLon = hud.lon
                lastHomeLineHomeLat = hud.homeLat
                lastHomeLineHomeLon = hud.homeLon
            }
            homeLineLayer?.setProperties(visibility(Property.VISIBLE))
        } else {
            lastHomeLineAircraftLat = null
            lastHomeLineAircraftLon = null
            lastHomeLineHomeLat = null
            lastHomeLineHomeLon = null
            homeLineLayer?.setProperties(visibility(Property.NONE))
        }
    }

    /** Bucket raw signal % into coarse steps for display (design decision): 0-10% shows as
     *  0%, otherwise round to the nearest of 25/50/75/100%. */
    private fun bucketSignalPct(pct: Int): Int {
        if (pct <= 10) return 0
        val buckets = intArrayOf(25, 50, 75, 100)
        return buckets.minByOrNull { kotlin.math.abs(it - pct) } ?: 0
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        // FpvTextureView (re)registers the video feed itself when its SurfaceTexture becomes
        // available, so there's no register/keyframe-reset dance to do here.

        // Same uid/callsign scheme as TakConnectActivity (shared "takpilot2_tak" prefs) so a
        // drone started here shows up under the same identity once/if TAK gets connected.
        val prefs = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        var uid = prefs.getString("uid", "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString("uid", uid).apply()
        }
        // Same fallback as TakConnectActivity and TakDropMarkers, which read this identical
        // key — a different default here meant a pilot who went straight to the flight screen
        // without visiting TAK Setup first saw "TAKPilot2 Go-Mini2" in the HUD until they
        // manually typed "sUAS" into that other screen, even though "sUAS" was always the
        // documented first-launch default.
        currentCallsign = prefs.getString("callsign", "sUAS") ?: "sUAS"
        TakBridgeHolder.start("$uid-DRONE", currentCallsign)
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Immersive-sticky flags get cleared by system dialogs/notification-shade swipes;
        // re-apply whenever we regain focus so the status bar doesn't creep back over the
        // toolbar.
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.v(TAG, "onResume")
        // BVLOS antenna aim; no-op on a device without the rotation-vector sensor.
        com.dji.sdk.sample.tak.ControllerCompass.start(this)
        // THE RIGHT DIAL BELONGS TO THE FIRMWARE — it drives the camera's zoom directly,
        // like the hardware record button, and this app cannot and must not intercept it.
        // The app FOLLOWS the camera instead: pill, crop and FOV track whatever ratio the
        // dial (or anything else) puts the camera at. See CameraZoomFollow.
        com.dji.sdk.sample.tak.CameraZoomFollow.arm { ratio -> onCameraZoomChanged(ratio) }
        mapView.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        // ⚠ NO HOT MICROPHONE IN THE BACKGROUND. See SpeakerTalk.
        SpeakerTalk.stop()
        AppLog.v(TAG, "onPause")
        com.dji.sdk.sample.tak.ControllerCompass.stop()
        com.dji.sdk.sample.tak.CameraZoomFollow.disarm()
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(hideNotice)
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        AppLog.v(TAG, "onStop")
        TakBridgeHolder.stop()
        // Leaving the flight screen (back to Home, or the app going to background/closing) —
        // don't keep pushing video nobody's watching the pilot fly against; also releases the
        // screen-capture projection so it doesn't linger as a background foreground service.
        if (VideoStreamerHolder.isActive) {
            AppLog.i(TAG, "onStop: stopping live stream (left flight screen / app backgrounded)")
            VideoStreamerHolder.stop()
        }
        lastHomeSet = false
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        com.dji.sdk.sample.tak.RcButtons.onC1 = null
        com.dji.sdk.sample.tak.RcButtons.onShutter = null
        TakBridgeHolder.onStillTaken = null
        com.dji.sdk.sample.tak.RcButtons.disarm()
        AppLog.v(TAG, "onDestroy")
        // The OOM-restart guard in onCreate can finish() before setContentView ever runs,
        // which leaves every lateinit view (arOverlay, mapView included) unassigned. onDestroy
        // still runs on that path, so a bare arOverlay.stop() threw
        // UninitializedPropertyAccessException and killed the exact recovery the guard exists
        // for. Guard each lateinit view touch.
        // Stop the AR redraw loop explicitly — it posts to a Handler several times a second and
        // would otherwise keep firing against a dead Activity.
        runCatching { TakManager.getInstance().removeEmergencyBroadcastListener(emergencyBroadcastListener) }
        if (::arOverlay.isInitialized) arOverlay.stop()
        VideoStreamerHolder.onStateChanged = null
        // Same reason as the line above: DjiSdkBridge is a process-wide singleton and would
        // otherwise hold this Activity alive through its diagnostics callback.
        DjiSdkBridge.onDiagnostics = null
        DjiObstacleState.removeChangeListener(obstacleListener)
        TakDropMarkers.ui = null
        SpeakerTalk.onChanged = null
        SpeakerTalk.stop()
        // ⚠ The screen carrying the ON AIR indicator is going away, so nothing may be left
        // broadcasting behind it. Closing the PANEL deliberately does not stop a message — a
        // bounded message should finish — but the screen dying is different.
        SpeakerBroadcast.reset()
        // ⚠ Turned OFF with the screen. Leaving a second video stream running on the aircraft's
        // radio link with nobody watching it is exactly the waste this is meant to avoid.
        VisionAssist.setEnabled(false)
        VisionAssist.stop()
        com.dji.sdk.sample.tak.CameraFov.onFovChanged = null
        // The accessory state belongs to the AIRFRAME that is plugged in, not to this
        // application, and AircraftAccessories is a process-wide object. Dropping it here stops
        // the next flight screen opening on the last aircraft's answers — including which key
        // route its light used, which a different aircraft need not share.
        AircraftAccessories.reset()
        com.dji.sdk.sample.tak.TakMapMarkers.onMapDestroyed()
        if (::mapView.isInitialized) mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    /**
     * The TAK channels, from the flight screen.
     *
     * A pilot must be able to change the scope of this aircraft IN FLIGHT. Pre-Flight can do it,
     * but going there stops the video to the team, which is the wrong thing to do in the middle
     * of a sortie.
     *
     * THE SERVER HOLDS THE STATE. This screen reads it, writes to it and follows it. It is not
     * the control — an administrator can change the same thing from TAK Portal, and the rows
     * follow that within about a second because of the t-x-g-c listener below.
     *
     * LOCKED BY THE TAK CONFIGURATION LOCK. When the lock is on, the rows show the channels and
     * refuse a change. Reading is never locked: a pilot must always be able to SEE the scope of
     * the aircraft, and the lock exists to stop an accidental change and not to hide the truth
     * (design decision).
     */
    /**
     * True when the pilot has entered the unlock password on THIS visit to the flight screen.
     *
     * SESSION ONLY — it is never written to the preferences. The pilot wants to change a channel
     * in flight, not to leave the TAK configuration unlocked after they land. Pre-Flight keeps
     * its own lock, and this does not touch it. Leaving the flight screen clears this.
     */
    private var takChannelsUnlockedThisSession = false

    /**
     * Asks for the unlock password, in the flight dialog.
     *
     * Going to Pre-Flight to unlock defeats the point of a control on the flight screen
     * (design decision). The password is Pre-Flight's own constant, not a copy of it —
     * one password and one idea of "locked", with no second string to drift.
     *
     * A wrong password and Cancel do the same thing, the same as Pre-Flight: the only way out
     * with the rows editable is the right password.
     */
    private fun promptChannelUnlock(onUnlocked: () -> Unit) {
        // ⚠ BASIC: NO PASSWORD — THE FOURTH PROMPT, AND THE EASIEST ONE TO MISS. This is
        // the TAK lock's in-flight face, built outside ConfigLock, so ConfigLock's Basic rule
        // does not reach it. Without this a Basic pilot meets the password in the AIR, which
        // AppMode.unlockNeedsPassword says a Basic user never does. Session-only, exactly as the
        // password path: the flag below, never KEY_TAK_LOCKED.
        if (!AppMode.unlockNeedsPassword(modePrefs())) {
            takChannelsUnlockedThisSession = true
            AppLog.i(TAG, "channels unlocked for this session (Basic mode, no password)")
            onUnlocked()
            return
        }
        // STYLED EXACTLY AS PRE-FLIGHT'S UNLOCK FIELD. A programmatic EditText takes the
        // PLATFORM's colours and no background at all, thus the first version was a bare line
        // of text across the full width of the dialog — hard to see and hard to hit. The
        // background and the padding are what make it look like a field, and the wrapper is
        // what keeps it off the edges. Do not simplify either away.
        val field = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            // Pre-Flight's field has this and this one did not, despite the line above: on a
            // landscape-locked screen the full-screen extract keyboard covers the dialog.
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            hint = "Password"
            textSize = 15f
            setTextColor(androidx.core.content.ContextCompat.getColor(
                applicationContext, R.color.tp_text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(
                applicationContext, R.color.tp_text_hint))
            setBackgroundResource(R.drawable.bg_dialog_field)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val wrap = android.widget.FrameLayout(this).apply {
            val padH = (16 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(field)
        }
        // Destructive theme, as Pre-Flight's unlock uses: getting this wrong changes who sees
        // the aircraft.
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            // No body text. It is a password prompt, and the pilot already knows what they
            // touched. The first version explained the session rule here, which is a thing to
            // read in the air and not a thing to decide.
            .setTitle("Unlock channels")
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Unlock") { _, _ ->
                if (field.text.toString() == com.dji.sdk.sample.tak.TakConnectActivity.UNLOCK_PASSWORD) {
                    takChannelsUnlockedThisSession = true
                    AppLog.i(TAG, "channels unlocked for this flight-screen session")
                    onUnlocked()
                } else {
                    AppLog.w(TAG, "channel unlock refused — wrong password")
                    showNotice("The password is wrong.", refused = true)
                }
            }
            .show()
    }


    /**
     * The compact metrics both channel lists in the flight dialog use (design decision).
     *
     * One function so the Standard and Elevated lists cannot drift apart: they sit one above
     * the other in the same dialog, and two row heights there read as a fault. The numbers and
     * the reason they are not cut further live in dimens.xml — the row stays a touch target
     * over live video.
     */

    /**
     * True while a screen-capture consent request was raised BY an Emergency Broadcast rather
     * than by the LIVE pill. A denial on that path has to take the override back down with it —
     * see [onActivityResult].
     */
    private var projectionRequestedForEmergency = false

    /**
     * Makes sure a stream is running, or on its way, before an Emergency Broadcast starts
     * (design decision).
     *
     * @return true if the broadcast may start. False means it must NOT: the pilot has been told
     * why, and starting a 15-minute authorisation that can never advertise anything is exactly
     * the false affirmative this exists to prevent.
     */
    private fun startStreamForEmergencyBroadcast(): Boolean {
        if (VideoStreamerHolder.isActive) return true   // already streaming, or connecting

        // The same guard the LIVE pill applies, and the same words. A broadcast cannot fix an
        // unconfigured video server, so it must not pretend to.
        val p = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        if ((p.getString("video_host", "") ?: "").isEmpty() ||
            (p.getString("video_streamid", "") ?: "").isEmpty()
        ) {
            AppLog.w(TAG, "Emergency Broadcast refused — video server not configured")
            showNotice("Set up the video server in Pre-Flight Setup first", refused = true)
            return false
        }

        // ⚠ THE OVERRIDE STARTS NOW AND THE STREAM FOLLOWS, deliberately. The pilot pressed a
        // button in an incident; the override is an AUTHORISATION and does not have to wait on
        // a socket. videoFor reads the url at every send, so the link starts going out the
        // moment the stream has one — and until then the notice says NO VIDEO STREAM rather
        // than claiming otherwise (see paintEmergencyBroadcast).
        AppLog.i(TAG, "Emergency Broadcast — starting the screen stream with it")
        projectionRequestedForEmergency = true
        val mpm = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        return true
    }

    private fun compactChannelRow(box: android.widget.CheckBox) {
        box.textSize = resources.getDimension(R.dimen.chan_dialog_row_text_size) /
            resources.displayMetrics.scaledDensity
        box.minHeight = resources.getDimensionPixelSize(R.dimen.chan_dialog_row_min_height)
        box.minimumHeight = resources.getDimensionPixelSize(R.dimen.chan_dialog_row_min_height)
        val pad = resources.getDimensionPixelSize(R.dimen.chan_dialog_gap) / 2
        box.setPadding(box.paddingLeft, pad, box.paddingRight, pad)
    }

    private fun onTakChannelsTapped() {
        if (!TakManager.getInstance().isConnected) {
            showNotice("TAK is not connected. The channels are on the server.", refused = true)
            return
        }
        val themed = android.view.ContextThemeWrapper(this, R.style.TakDialogTheme)
        val view = android.view.LayoutInflater.from(themed)
            .inflate(R.layout.dialog_tak_channels, null)
        val list = view.findViewById<android.widget.LinearLayout>(R.id.takChanList)
        val status = view.findViewById<TextView>(R.id.takChanStatus)
        val configLocked = getSharedPreferences(com.dji.sdk.sample.tak.TakConnectActivity.PREFS, MODE_PRIVATE)
            .getBoolean(com.dji.sdk.sample.tak.TakConnectActivity.KEY_TAK_LOCKED, false)
        // The session unlock is what the pilot just typed; the pref is what Pre-Flight holds.
        var locked = configLocked && !takChannelsUnlockedThisSession
        val lockedNote = view.findViewById<TextView>(R.id.takChanLocked)

        var channels: List<com.taklite.client.tak.TakMissionClient.Channel> = emptyList()
        var painting = false

        fun paint(chans: List<com.taklite.client.tak.TakMissionClient.Channel>) {
            channels = chans
            painting = true
            list.removeAllViews()
            if (chans.isEmpty()) {
                // Channels turned off on this server. Show nothing to change — a write to such
                // a server is reported to cause real trouble on it.
                status.text = "This server has no channels."
                painting = false
                return
            }
            for (ch in chans) {
                val row = android.widget.CheckBox(themed).apply {
                    // Two-way is the normal case and gets no label — a note on every row is
                    // noise, and the exception is what a pilot needs to see (design decision).
                    text = when {
                        ch.canSend && ch.canReceive -> ch.name
                        ch.canReceive -> "${ch.name} - Rx Only"
                        ch.canSend -> "${ch.name} - Tx Only"
                        else -> "${ch.name} - no direction"
                    }
                    // ⚠ LOCKED IS NOT DISABLED — see the same note in TakConnectActivity.
                    // Disabling greys the tick, and the tick is what the pilot came to read.
                    setTextColor(androidx.core.content.ContextCompat.getColor(
                        applicationContext,
                        if (locked) R.color.tp_text_secondary else R.color.tp_text_primary))
                    isChecked = ch.active
                    isClickable = !locked
                    isFocusable = !locked
                    buttonTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(
                            applicationContext, R.color.tp_accent))
                    compactChannelRow(this)
                    setOnCheckedChangeListener { _, checked ->
                        if (painting) return@setOnCheckedChangeListener
                        ch.active = checked
                        val bits = channels.filter { it.active && it.bitpos >= 0 }.map { it.bitpos }
                        status.text = "Sending ${bits.size} active channel(s)…"
                        // The COMPLETE set every time — activebits is absolute.
                        com.dji.sdk.sample.tak.TakMissionManager.setActiveChannels(bits) { ok ->
                            status.text = if (ok) "The server has ${bits.size} active channel(s)."
                                          else "The server refused the change."
                        }
                    }
                }
                list.addView(row)
            }
            painting = false
        }

        fun reload() = com.dji.sdk.sample.tak.TakMissionManager.listChannels { paint(it) }
        lockedNote.visibility = if (locked) View.VISIBLE else View.GONE
        reload()

        // The ELEVATED account's channels under the Standard's (design decision): the whole
        // scope of this aircraft, in flight, on one screen. WRITABLE, the same as the Standard
        // rows above and behind the same lock — see TakConnectActivity.renderVideoChannels for
        // why a tick here is also the only possible "ignore incoming" control, and for what it
        // costs the fleet.
        // Read once at open: the server's t-x-g-c change notice arrives on the Standard
        // connection only.
        val elevatedLabel = view.findViewById<TextView>(R.id.takChanElevatedLabel)
        val elevatedList = view.findViewById<android.widget.LinearLayout>(R.id.takChanElevatedList)
        var elevated: List<com.taklite.client.tak.TakMissionClient.Channel> = emptyList()
        var paintingElevated = false

        fun paintElevated(chans: List<com.taklite.client.tak.TakMissionClient.Channel>) {
            elevated = chans
            paintingElevated = true
            elevatedList.removeAllViews()
            if (chans.isEmpty()) {
                elevatedList.addView(TextView(themed).apply {
                    text = "The server returned no channels for the Elevated account."
                    setTextColor(androidx.core.content.ContextCompat.getColor(
                        applicationContext, R.color.tp_text_secondary))
                    textSize = resources.getDimension(R.dimen.chan_dialog_text_size) /
                        resources.displayMetrics.scaledDensity
                })
            } else for (ch in chans) {
                elevatedList.addView(android.widget.CheckBox(themed).apply {
                    text = com.dji.sdk.sample.tak.TakMissionManager.channelLabel(ch)
                    // ⚠ LOCKED IS NOT DISABLED — the same rule as every other channel row.
                    setTextColor(androidx.core.content.ContextCompat.getColor(
                        applicationContext,
                        if (locked) R.color.tp_text_secondary else R.color.tp_text_primary))
                    isChecked = ch.active
                    isClickable = !locked
                    isFocusable = !locked
                    buttonTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(
                            applicationContext, R.color.tp_accent))
                    compactChannelRow(this)
                    setOnCheckedChangeListener { _, checked ->
                        if (paintingElevated) return@setOnCheckedChangeListener
                        ch.active = checked
                        val bits = elevated.filter { it.active && it.bitpos >= 0 }.map { it.bitpos }
                        status.text = "Sending ${bits.size} Elevated channel(s)…"
                        com.dji.sdk.sample.tak.TakMissionManager
                            .setElevatedActiveChannels(this@TAKPilot2GoFlightActivity, bits) { ok ->
                                status.text = if (ok) {
                                    "The server has ${bits.size} Elevated channel(s). " +
                                        "This applies to every controller on that account."
                                } else {
                                    "The server refused the Elevated change."
                                }
                            }
                    }
                })
            }
            paintingElevated = false
        }

        // ⚠ EXPLICIT, NOT INHERITED. These rows are WRITABLE and change the fleet-shared Elevated
        // account's activebits. They were hidden in Basic only because the split happens to be
        // gone — the same inherited gating the Emergency Broadcast row rejects. A stray split in
        // Basic would put live fleet-wide controls in front of a pilot over video.
        if (AppMode.elevatedAccountAllowed(modePrefs()) &&
            TakManager.getInstance().isVideoChannelConfigured
        ) {
            com.dji.sdk.sample.tak.TakMissionManager.listElevatedChannels(this) { chans ->
                if (chans == null) return@listElevatedChannels
                paintElevated(chans)
                elevatedLabel.visibility = View.VISIBLE
                elevatedList.visibility = View.VISIBLE
            }
        }

        // Follow the server while the dialog is open, and stop when it closes.
        val onGroups = TakManager.GroupChangeListener {
            AppLog.i(TAG, "channels changed on the server — re-reading (flight screen)")
            reload()
        }
        TakManager.getInstance().addGroupChangeListener(onGroups)

        val dialog = AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("TAK Channels")
            .setView(view)
            .setNegativeButton("Close", null)
            // Unlock HERE. Going to Pre-Flight for it defeats the point of this dialog. In Basic
            // there is no password, so no ellipsis: "…" promises a further step that never comes.
            .apply {
                if (locked) setNeutralButton(
                    if (AppMode.unlockNeedsPassword(modePrefs())) "Unlock…" else "Unlock", null)
            }
            .setOnDismissListener {
                TakManager.getInstance().removeGroupChangeListener(onGroups)
            }
            .create()
        dialog.show()
        // Set after show() so the dialog does NOT close when the password prompt opens over it.
        if (locked) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                promptChannelUnlock {
                    locked = false
                    lockedNote.visibility = View.GONE
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.GONE
                    paint(channels)     // repaint the rows, now editable
                    // ⚠ BOTH LISTS. The Elevated rows are ticks behind the same lock and read
                    // `locked` when painted, so an unlock that repainted only the Standard ones
                    // left half the dialog refusing touches with nothing to say why — after the
                    // pilot had just given the password.
                    if (elevated.isNotEmpty()) paintElevated(elevated)
                }
            }
        }
    }

    companion object {
        // TELEMETRY SIZE HIERARCHY. The height figure is large, its unit
        // and the position line are small. The values are RATIOS rather than sizes, so they
        // hold at whatever base size the readouts are drawn (12sp here).
        //
        // ⚠ THE UNIT AND THE POSITION LINE ARE NOT DECORATION — they are what pays for the
        // figure. Raising HEIGHT_FIGURE_SCALE without lowering the other two grows the block by
        // the full increase, and this column has about 14dp of slack on this device.
        private const val HEIGHT_FIGURE_SCALE = 1.55f
        private const val HEIGHT_UNIT_SCALE = 0.80f
        private const val REFERENCE_SCALE = 0.85f

        /** Flight-screen lifecycle + toolbar actions (RTH, zoom, TAK toggle, LIVE, nav). */
        private const val TAG = "TP2Flight"
        /** Camera capture operations specifically — recording and stills. */
        private const val REC_TAG = "TP2Record"

        /** Wide-crop dial: below this normalised deflection the dial reads as centred. */
        private const val DIAL_DEADZONE = 0.15f
        private const val WIDE_DIAL_TICK_MS = 50L

        /** Three palettes, in this order. Kept short on purpose: a
         *  cycle button with ten entries is a button nobody can aim. */
        private val IR_PALETTES = listOf(
            CameraThermalPalette.WHITE_HOT to "WHITE HOT",
            CameraThermalPalette.BLACK_HOT to "BLACK HOT",
            // IRONBOW1, not "IRONBOW": the SDK ships two ironbow ramps and there is no
            // unnumbered one. The first is the conventional ramp.
            CameraThermalPalette.IRONBOW1 to "IRONBOW",
        )

        /** Within this many degrees of the aircraft bearing, the antenna-aim marker reads
         *  GREEN — close enough for the controller's antenna lobe. Read by [AntennaAimView]
         *  so the arc and this screen judge "aligned" identically. */
        const val ANTENNA_ALIGNED_DEG = 10.0

        /** The gimbal camera. Every camera key on this screen names it explicitly — an
         *  untargeted key was accepted and discarded by this aircraft (measured). */
        private val MAIN_CAM = ComponentIndexType.LEFT_OR_MAIN
        /** The REC-from-any-mode wait. Another airframe's figures until the M4TD is measured. */
        private const val MODE_POLL_MS = 250L
        private const val MODE_POLL_LIMIT_MS = 6_000L
        private const val MODE_READY_SETTLE_MS = 1_200L

        private const val REQUEST_MEDIA_PROJECTION = 3001

        /** The lens-switch ramp settles well inside this; see adoptZoomRatioFromCamera. */
        private const val ZOOM_ADOPT_MAX_ATTEMPTS = 8
        private const val ZOOM_ADOPT_RETRY_MS = 400L

        /** Push-to-talk needs the microphone at runtime — see startTalking. */
        private const val REQ_MIC = 3002
        private const val HUD_INTERVAL_MS = 500L

        private const val RESOURCE_LOG_INTERVAL_MS = 30_000L

        /** 24-hour with seconds. The HUD ticks at 500ms, so the seconds digit is live —
         *  which is what a pilot timing a leg or noting the moment of an event needs. */
        private val clockFormat =
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

        /** Separate from [TAG] on purpose: [TAG] is in AppLog's TAK_TAGS and disappears when an
         *  pilot filters TAK logging off — which is exactly when they are chasing a memory
         *  problem and need this most. Same reasoning as the bridge's readiness tag. */
        private const val RESOURCE_TAG = "TP2Resources"
        /** Post-still VIDEO-mode restore: poll interval and total attempts (~3.6s of patience).
         *  Comfortably longer than the Air 2 takes to write a still, while still giving up in
         *  time to warn the pilot rather than retrying silently forever. */
        private const val PHOTO_RESTORE_RETRY_MS = 300L
        private const val PHOTO_RESTORE_MAX_ATTEMPTS = 12
        /** FOV calibration step. 0.5 deg is finer than the eye can judge at the frame edge,
         *  so it never limits how closely the pilot can converge. */
        private const val FOV_STEP_DEG = 0.5
        private const val AIRCRAFT_ICON_ID = "aircraft-icon"
        private const val AIRCRAFT_SOURCE_ID = "aircraft-source"
        private const val AIRCRAFT_LAYER_ID = "aircraft-layer"
        // 16, from 28 (design decision): 28dp on a 148dp map was a fifth of the map.
        private const val AIRCRAFT_ICON_DP = 16
        private const val HOME_ICON_ID = "home-icon"
        private const val HOME_SOURCE_ID = "home-source"
        private const val HOME_LAYER_ID = "home-layer"
        private const val HOME_LINE_SOURCE_ID = "home-line-source"
        private const val HOME_LINE_LAYER_ID = "home-line-layer"
        private const val HOME_ICON_DP = 12
        private const val HOME_NOTICE_MS = 5000L
        /** How long after KeyStopGoHome the flight mode is read to see whether the return stopped. */
        private const val RTH_CANCEL_CONFIRM_MS = 1500L

        /** Minimum height above ground for a marker drop, feet. Below this the slant
         *  solve degenerates onto the aircraft's own position — see dropRefusalReason. */
        private const val MIN_DROP_AGL_FT = 25.0

        // Mini-map zoom, street level — every hud tick rebuilds the CameraPosition, and an
        // unspecified zoom() reset it to the map's default (continent-scale) on each update,
        // which is why it looked "stuck" zoomed out rather than just starting there.
        /**
         * The two mini-map zooms, chosen for THIS map size and THIS aircraft's limits rather
         * than inherited from a larger map with a shorter distance limit.
         *
         * On a 130dp map (341px) the ground covered scales with cos(latitude): WIDE spans about
         * 3.3 km × cos(lat) across and NEAR about 410 m × cos(lat) — at 45°N roughly 2.3 km
         * and 290 m. The default max distance in Pre-Flight is 5280ft (1609m), so WIDE holds
         * most or all of the permitted area instead of letting the home point leave the map
         * before the limit does.
         *
         * NEAR was 15.0 for a long time — "the zoom this screen has always used", inherited and
         * never re-judged. It was brought to 16.0 (17 was tried and read too close): the
         * mini-map's job is "where is the aircraft relative to me and the near team", which is
         * a street-scale question. WIDE was left alone deliberately — its job is context, and
         * a couple of kilometres of context is the point of it.
         */
        private const val MAP_ZOOM_WIDE = 13.0
        private const val MAP_ZOOM_NEAR = 16.0

        private const val KEY_MAP_WIDE = "flight_map_wide"

        // Where the mini-map centers before the drone has a GPS fix. A neutral default,
        // chosen deliberately so the first view isn't a pilot's home area or a public-safety
        // facility; the map pans to the aircraft once a fix arrives.
        private val DEFAULT_CENTER = LatLng(45.0000, -100.0000)
    }
}
