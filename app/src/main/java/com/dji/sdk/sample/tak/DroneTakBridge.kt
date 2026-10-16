package com.dji.sdk.sample.tak

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel
import dji.sdk.keyvalue.value.flightcontroller.WindWarning
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import kotlin.math.sqrt

/**
 * DroneTakBridge — MSDK v5 telemetry -> TAK air-track PLI.
 *
 * Every KeyManager listen writes an @Volatile cache, and [pushOnce] reads the
 * caches on a 2 s tick to build the PLI.
 *
 * Listener ownership rule: this bridge is the only client of
 * these keys. Consumers read the bridge's caches ([hud], [cameraPose],
 * [lookPoint]) and never register their own listens.
 */
class DroneTakBridge(
    private val appContext: Context,
    private val fallbackUid: String,
    private val droneCallsign: String,
    private val intervalMs: Long = 2000L,
) {
    private val tak = TakManager.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    // Prefer the real aircraft serial (stable per-aircraft so ATAK associates the sensor
    // cone correctly). Fetched async on start(); falls back to the provided uid until it
    // resolves.
    @Volatile private var droneUid: String = fallbackUid

    /** Optional RTSP/stream url to advertise. It rides the AIRCRAFT marker only (design
     *  decision); the pilot marker carries no url — see [sendPilotPli]. Which CONNECTIONS
     *  carry it is the shared core's decision, not this class's: `TakManager.videoFor`. */
    @Volatile
    var videoUrl: String? = null

    /** When true, also push the camera slant point (sensor point of interest). */
    @Volatile
    var cameraPointEnabled: Boolean = false

    private val spiUid: String get() = "$droneUid-SPI"

    // Sensor FOV cone state, refreshed when camera-point is enabled; embedded in the
    // drone PLI so ATAK/taklite draw the cone natively. -1 = omit.
    @Volatile private var sensorFov = -1.0
    @Volatile private var sensorVfov = -1.0

    /** Current digital zoom (1.0 = none). Set by the flight screen's zoom control via
     *  [TakBridgeHolder]; narrows both the published FOV cone and the AR projection. */
    @Volatile var zoomFactor: Double = 1.0
    @Volatile private var sensorAzimuth = -1.0
    @Volatile private var sensorElevation = 0.0
    @Volatile private var sensorRange = -1.0

    // ------------------------------------------------------------------
    // Cached key values. Each field is one KeyManager listen. v5 has no
    // frame object; the caches ARE the frame.
    // ------------------------------------------------------------------
    @Volatile private var lastLocation: LocationCoordinate3D? = null
    /** elapsedRealtime of the last location push, 0 = none. The drone CoT is only
     *  published while this is recent — see the freshness gate in [pushOnce]. */
    @Volatile private var lastStateMs = 0L
    @Volatile private var lastVelocity: Velocity3D? = null
    @Volatile private var lastHeading: Double? = null
    @Volatile private var lastFlightMode: FlightMode? = null
    @Volatile private var lastIsFlying = false
    @Volatile private var lastMotorsOn = false
    @Volatile private var lastSatCount = 0
    @Volatile private var lastGpsLevel: GPSSignalLevel? = null
    @Volatile private var lastHomeLocation: LocationCoordinate2D? = null
    @Volatile private var lastHomeSet = false
    /** Absolute (AMSL) altitude of the TAKEOFF POINT, from KeyTakeoffLocationAltitude. Null
     *  until the aircraft reports it. Added to the takeoff-relative height to get a real
     *  geodetic altitude for the CoT — see [absoluteAlt]. */
    @Volatile private var lastTakeoffAltMsl: Double? = null
    @Volatile private var lastFlightTimeSec = 0
    @Volatile private var lastWind: WindWarning? = null
    @Volatile private var lastBatteryPct = 0
    @Volatile private var lastBatteryMaxMah = 0
    @Volatile private var lastBatteryRemainMah = 0
    @Volatile private var lastVoltageMv = 0
    @Volatile private var lastLowBattThreshold: Int? = null
    @Volatile private var lastSeriousBattThreshold: Int? = null
    // RC-to-aircraft link quality, 0-100 — the "controller signal strength" a pilot cares
    // about (distinct from downlink/video quality).
    @Volatile private var lastUplinkQuality: Int? = null
    /** AIRCRAFT-to-RC link quality, 0-100 — the direction the VIDEO actually travels. Uplink
     *  says nothing about the link frames are lost on, so an FPV-artifacting investigation
     *  must watch this one. */
    @Volatile private var lastDownlinkQuality: Int? = null
    @Volatile private var lastGimbalAttitude: Attitude? = null
    @Volatile private var lastGimbalYawRel: Double? = null
    @Volatile private var lastIsRecording = false
    @Volatile private var lastCameraMode: dji.sdk.keyvalue.value.camera.CameraMode? = null
    @Volatile private var lastIsShootingPhoto = false
    /** Fed on the MAIN thread when the camera finishes a still, whoever started it. The
     *  flight screen shows the notice the firmware never does. */
    @Volatile var onStillTaken: (() -> Unit)? = null
    @Volatile private var lastRthHeight: Int? = null
    @Volatile private var lastRemainingFlightSec: Int? = null
    @Volatile private var lastIsoName: String? = null
    @Volatile private var lastShutterName: String? = null

    // One-shot guards: applied on the first sign of life from the component, which is the
    // reliable "it is actually up" signal.
    @Volatile private var exposureApplied = false
    @Volatile private var controlResponseApplied = false
    @Volatile private var limitsApplied = false
    @Volatile private var gimbalRangeApplied = false

    /** Listen holder cancelled on [stop]. */
    private val sessionHolder = Any()

    /**
     * Listen holder that is NEVER cancelled.
     *
     * The AirLink signal listens live here. Removing an RC signal listener at TAK stop has
     * been seen to kill the RC signal indicator for the life of the process (an SDK
     * asymmetry, found in flight). Whether v5's KeyManager shares the defect is
     * unverified, but keeping these armed costs nothing — they only write @Volatile caches
     * — and the signal bars on the flight screen must never depend on a TAK toggle.
     */
    private val signalHolder = Any()
    @Volatile private var signalListensArmed = false

    private val tick = object : Runnable {
        override fun run() {
            try {
                pushOnce()
            } catch (t: Throwable) {
                AppLog.w(TAG, "telemetry push failed: ${t.message}")
            }
            if (running) handler.postDelayed(this, intervalMs)
        }
    }

    /**
     * @param sameFlight true when [TakBridgeHolder] is replacing the bridge mid-flight (an
     * identity change — enroll, logout-then-reconnect) rather than starting a genuinely new
     * flight. [TerrainAgl.reset] used to run unconditionally here, which wiped the latched
     * takeoff-terrain reference on a same-flight restart too — the replacement bridge kept
     * the GPX session open, but every trackpoint until the reference re-latched got MSL=NaN,
     * splicing a gap into what was supposed to stay one continuous track.
     */
    fun start(sameFlight: Boolean = false) {
        if (running) return
        running = true

        // A genuinely new flight has a new takeoff point, so the latched terrain reference
        // from the last one must not carry over (see TerrainAgl). A same-flight restart keeps
        // it — the takeoff point has not moved.
        if (!sameFlight) TerrainAgl.reset()

        armSessionListens()
        armSignalListensOnce()
        resolveSerial()

        // The CONTROLLER's own position, for the pilot marker. Idempotent, and it must be a
        // real requestLocationUpdates — see OperatorLocation for why the cache alone is empty.
        OperatorLocation.start(appContext)

        // The at-limit warnings compare against the SAME configured values that get pushed to
        // the aircraft, read from the same place, so the banner cannot disagree with the limit
        // the aircraft is enforcing.
        FlightWarnings.setLimits(
            FlightLimitsController.ftToM(FlightLimitsController.savedMaxAltitudeFt(appContext))?.toDouble(),
            FlightLimitsController.ftToM(FlightLimitsController.savedMaxRadiusFt(appContext))?.toDouble(),
        )
        // A new bridge start must not inherit the last one's banner state.
        FlightWarnings.reset()
        // ⚠ AND THE AIRCRAFT'S OWN FAULTS MUST GO STRAIGHT BACK IN.
        //
        // reset() empties the active set on the documented assumption that it "rebuilds within
        // one frame". That is true of every warning computed from telemetry, because update()
        // recomputes them each frame. It is NOT true of AIRCRAFT_FAULT: that one arrives by
        // event, and the event is CHANGE-ONLY, so a fault that is already standing produces no
        // further callback and never comes back.
        //
        // The flight screen seeds the fault in its onCreate, and this runs after it, thus with
        // the aircraft on the ground the banner went active and was discarded 26ms later — with
        // the aircraft reporting four faults, two of them CAUTION, and the pilot shown nothing
        // for the rest of the flight.
        //
        // It was a RACE, not a clean failure, which is why it survived so long: open the flight
        // screen before the aircraft has finished reporting and a later change event repaints
        // the banner, so it looks like it works. Seeding here closes the race from both sides.
        FlightWarnings.onDiagnostics(DjiSdkBridge.diagnostics)

        handler.post(tick)
        AppLog.i(TAG, "DroneTakBridge started ($droneCallsign / $droneUid, every ${intervalMs}ms)")
    }

    private fun armSessionListens() {
        val h = sessionHolder
        FlightControllerKey.KeyAircraftLocation3D.create().listen(h) {
            lastLocation = it
            // Proof of life for the drone CoT — see the freshness gate in pushOnce().
            if (it != null) lastStateMs = android.os.SystemClock.elapsedRealtime()
            if (!limitsApplied && it != null) {
                limitsApplied = true
                FlightLimitsController.applyDefaults(appContext)
            }
        }
        FlightControllerKey.KeyAircraftVelocity.create().listen(h) { lastVelocity = it }
        FlightControllerKey.KeyCompassHeading.create().listen(h) { lastHeading = it }
        FlightControllerKey.KeyFlightMode.create().listen(h) { lastFlightMode = it }
        FlightControllerKey.KeyIsFlying.create().listen(h) {
            lastIsFlying = it == true
            // Fed onward, NOT subscribed twice (one listener per key). Non-null only: a link that is
            // dropping reports null, and that must not read as "landed" to the refusals that
            // ask whether the aircraft is flying. See AircraftFlightCheck.
            if (it != null) AircraftFlightCheck.noteFlying(it)
        }
        FlightControllerKey.KeyAreMotorsOn.create().listen(h) { lastMotorsOn = it == true }
        FlightControllerKey.KeyGPSSatelliteCount.create().listen(h) { lastSatCount = it ?: 0 }
        FlightControllerKey.KeyGPSSignalLevel.create().listen(h) { lastGpsLevel = it }
        // The absolute altitude of the takeoff point. DJI's own UX SDK builds its "AMSL"
        // readout as KeyAltitude + this, and KeyAircraftLocation3D.altitude IS KeyAltitude —
        // height above the takeoff point, NOT a geodetic altitude. See absoluteAlt().
        FlightControllerKey.KeyTakeoffLocationAltitude.create().listen(h) {
            lastTakeoffAltMsl = it
        }
        FlightControllerKey.KeyHomeLocation.create().listen(h) { lastHomeLocation = it }
        FlightControllerKey.KeyIsHomeLocationSet.create().listen(h) { lastHomeSet = it == true }
        FlightControllerKey.KeyFlightTimeInSeconds.create().listen(h) { lastFlightTimeSec = it ?: 0 }
        FlightControllerKey.KeyWindWarning.create().listen(h) { lastWind = it }
        FlightControllerKey.KeyGoHomeHeight.create().listen(h) {
            lastRthHeight = it?.takeIf { v -> v > 0 }
        }
        // The aircraft's own remaining-flight-time estimate (seconds), from its smart-RTH
        // model — unlike a percent-times-endurance guess, this models actual current draw.
        FlightControllerKey.KeyLowBatteryRTHInfo.create().listen(h) {
            lastRemainingFlightSec = it?.remainingFlightTime?.takeIf { v -> v > 0 }
        }
        FlightControllerKey.KeyLowBatteryWarningThreshold.create().listen(h) {
            lastLowBattThreshold = it
        }
        FlightControllerKey.KeySeriousLowBatteryWarningThreshold.create().listen(h) {
            lastSeriousBattThreshold = it
        }

        BatteryKey.KeyChargeRemainingInPercent.create().listen(h) { lastBatteryPct = it ?: 0 }
        BatteryKey.KeyFullChargeCapacity.create().listen(h) { lastBatteryMaxMah = it ?: 0 }
        BatteryKey.KeyChargeRemaining.create().listen(h) { lastBatteryRemainMah = it ?: 0 }
        BatteryKey.KeyVoltage.create().listen(h) { lastVoltageMv = it ?: 0 }

        GimbalKey.KeyGimbalAttitude.create().listen(h) {
            lastGimbalAttitude = it
            // Same one-shot-per-connect pattern as exposure/limits: first gimbal state is
            // the reliable "gimbal is actually up" signal.
            if (!gimbalRangeApplied && it != null) {
                gimbalRangeApplied = true
                applyPitchRangeExtension()
            }
        }
        GimbalKey.KeyYawRelativeToAircraftHeading.create().listen(h) { lastGimbalYawRel = it }

        // Control response at each connect, so the camera feels the same every flight whatever
        // the last flight or the DJI app left in the gimbal. Fires ONCE per connect: this is a
        // write to flight hardware, and a write to flight hardware is never put on a clock.
        GimbalKey.KeyConnection.create().listen(h) { connected ->
            if (connected == true && !controlResponseApplied) {
                controlResponseApplied = true
                ControlResponse.apply(appContext)
            } else if (connected != true) {
                controlResponseApplied = false
            }
        }

        CameraKey.KeyIsRecording.create().listen(h) { lastIsRecording = it == true }
        // THE CAMERA'S OWN MODE, LISTENED. Setting it on REC and on the shutter without ever
        // reading it back leaves the screen unable to say which mode the camera is in, and REC
        // with no way to wait for the camera to actually be in video before starting (a start
        // issued 800 ms after the mode-change ack has been measured silently ignored). A listen,
        // not the one-argument getValue, which reads an empty cache.
        CameraKey.KeyCameraMode.create().listen(h) { lastCameraMode = it }
        CameraKey.KeyIsShootingPhoto.create().listen(h) {
            val now = it == true
            if (now != lastIsShootingPhoto) {
                // Transition-logged: the hardware shutter's still is taken by whoever gets
                // there first — the firmware or this application — and this is the one signal
                // that says a still happened at all. Without it a still leaves no indication
                // on the screen.
                AppLog.i(TAG, if (now) "camera: shooting a still" else "camera: still done")
                lastIsShootingPhoto = now
                if (!now) onStillTaken?.let { cb -> handler.post { cb() } }
            }
        }
        CameraKey.KeyConnection.create().listen(h) { connected ->
            if (connected == true && !exposureApplied) {
                exposureApplied = true
                ExposureController.applyDefaults(appContext)
            }
        }
        // Live exposure readout for the HUD. Enum names, translated by ExposureController.
        CameraKey.KeyISO.create().listen(h) { lastIsoName = it?.name }
        CameraKey.KeyShutterSpeed.create().listen(h) { lastShutterName = it?.name }
    }

    private fun armSignalListensOnce() {
        if (signalListensArmed) return
        signalListensArmed = true
        AirLinkKey.KeyUpLinkQuality.create().listen(signalHolder) { lastUplinkQuality = it }
        // Downlink is the direction video travels — see lastDownlinkQuality's doc for why
        // watching only uplink misleads an artifacting investigation.
        AirLinkKey.KeyDownLinkQuality.create().listen(signalHolder) { lastDownlinkQuality = it }
    }

    private fun resolveSerial() {
        KeyManager.getInstance().getValue(
            KeyTools.createKey(FlightControllerKey.KeySerialNumber),
            object : CommonCallbacks.CompletionCallbackWithParam<String> {
                override fun onSuccess(serial: String?) {
                    if (!serial.isNullOrBlank()) {
                        droneUid = serial
                        AppLog.i(TAG, "drone uid = aircraft serial $serial")
                    }
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "serial read failed (${error.description()}) — using $fallbackUid")
                }
            },
        )
    }

    /**
     * Lets the gimbal tilt UP past level, not just down — so a pilot can visually acquire
     * air traffic overhead. Failure is logged and otherwise ignored: this is a nice-to-have,
     * and an aircraft that refuses it should still fly normally.
     *
     * A gimbal pitch-SPEED boost is deliberately NOT applied: v5 exposes no min/max
     * capability range to clamp against, and an unclamped write to an unknown range on a
     * new airframe is the kind of blind flight-configuration write this project does not
     * do. Revisit with the real dial response measured on the aircraft.
     */
    private fun applyPitchRangeExtension() {
        KeyManager.getInstance().setValue(
            KeyTools.createKey(GimbalKey.KeyPitchRangeExtensionEnabled),
            true,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "gimbal pitch range extended — camera can now look up")
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "gimbal pitch range extension refused: ${error.description()}")
                }
            },
        )
    }

    /**
     * @param finalizeFlight false when the caller is about to START A REPLACEMENT BRIDGE —
     * an identity change mid-flight (enroll, logout-then-reconnect) is the same physical
     * flight, and ending the logger session here would split one flight into two GPX files.
     */
    fun stop(finalizeFlight: Boolean = true) {
        running = false
        handler.removeCallbacks(tick)
        OperatorLocation.stop()
        // Close the track now rather than leaving it to the orphan sweep. The sweep is the
        // crash path; a clean stop should produce a complete GPX immediately.
        if (finalizeFlight) FlightPathLogger.endSession("bridge stopped")
        // The sessionHolder listens go; the AirLink signal listens on signalHolder deliberately stay
        // (see signalHolder's doc).
        runCatching { KeyManager.getInstance().cancelListen(sessionHolder) }
        lastLocation = null
        lastVelocity = null
        lastHeading = null
        lastFlightMode = null
        lastIsFlying = false
        lastMotorsOn = false
        lastSatCount = 0
        lastGpsLevel = null
        lastHomeLocation = null
        lastHomeSet = false
        lastTakeoffAltMsl = null
        lastFlightTimeSec = 0
        lastWind = null
        lastGimbalAttitude = null
        lastGimbalYawRel = null
        // The pose filter holds a bearing from the last run; easing into a new one from a
        // stale value would sweep the overlay on the first frames after a reconnect.
        smoothedBearing = null
        smoothedPitch = null
        poseFilterAtNs = 0L
        lastIsRecording = false
        lastCameraMode = null
        lastIsShootingPhoto = false
        lastIsoName = null
        lastShutterName = null
        exposureApplied = false
        limitsApplied = false
        gimbalRangeApplied = false
        AppLog.i(TAG, "DroneTakBridge stopped")
    }

    /**
     * One-line flight-readiness snapshot, logged only when something in it CHANGES.
     * Answers "why won't it arm" from the aircraft's own state.
     * Logs under [READY_TAG] so it survives the Debug screen's "TAK logging off" filter.
     */
    private var lastReadiness: String? = null

    private fun logReadinessIfChanged() {
        val line = "mode=$lastFlightMode motors=$lastMotorsOn " +
            "flying=$lastIsFlying sats=$lastSatCount " +
            "gps=$lastGpsLevel homeSet=$lastHomeSet"
        if (line != lastReadiness) {
            lastReadiness = line
            AppLog.i(READY_TAG, line)
        }
    }

    private fun isGoingHome(): Boolean = lastFlightMode == FlightMode.GO_HOME

    private fun warningsFrame() = FlightWarnings.Frame(
        flightMode = lastFlightMode,
        gpsLevel = lastGpsLevel,
        wind = lastWind,
        goingHome = isGoingHome(),
        batteryPct = lastBatteryPct,
        lowBatteryThresholdPct = lastLowBattThreshold,
        seriousBatteryThresholdPct = lastSeriousBattThreshold,
        homeSet = lastHomeSet,
    )

    private fun pushOnce() {
        // THE PILOT MARKER GOES FIRST, AND IS NOT GATED ON THE AIRCRAFT. Everything after this
        // line returns early on aircraft state — no location, stale telemetry, no GPS fix — and
        // the pilot is none of those things. The pilot must be in the team's contact list
        // whatever the aircraft is doing, so this publishes on EVERY tick: the real position
        // when the controller has a fix, the "position not known" form when it does not.
        // ⚠ The pilot marker does NOT carry the video url (design decision). Carrying it there
        // too would let a downed aircraft still leave a marker saying where the stream was, but
        // TAK Aware draws a team member that carries video as a 2525 square, not a dot, and the
        // dot won. The aircraft marker alone advertises the stream — see sendPilotPli.
        pushPilotPli()

        val loc = lastLocation ?: run {
            // Log hygiene: per-tick internals, not a state change/warning/error — AppLog's
            // own documented tier for exactly this is v(), gated on both enabled AND verbose.
            // At AppLog.d these built a string and wrote to the file (when enabled) on every
            // 2s bridge tick regardless of the verbose setting.
            AppLog.v(TAG, "tick: no aircraft location pushed yet")
            return
        }
        // FRESHNESS, not just presence — an aircraft that stopped talking must stop being
        // reported, or its CoT marker can never stale out on other clients.
        if (android.os.SystemClock.elapsedRealtime() - lastStateMs > TELEMETRY_FRESH_MS) {
            AppLog.v(TAG, "tick: telemetry stale — not publishing the aircraft")
            return
        }
        logReadinessIfChanged()

        val lat = loc.latitude
        val lon = loc.longitude
        if (!isValidLat(lat) || !isValidLon(lon)) {
            // No GPS fix yet — skip this tick rather than send a bogus 0,0 marker.
            AppLog.v(TAG, "tick: no valid GPS fix yet (lat=$lat lon=$lon)")
            return
        }
        // ⚠ NOT a geodetic altitude, despite KeyAircraftLocation3D reusing a field named
        // `altitude`: DJI reports height above the TAKEOFF POINT. Named for what it holds, so
        // the datum mix described at absoluteAlt() cannot return via someone trusting an
        // older, wronger name.
        val relAlt = loc.altitude

        // Horizontal ground speed from NED velocity components, m/s.
        val vel = lastVelocity
        val speed = if (vel != null) sqrt(vel.x * vel.x + vel.y * vel.y) else 0.0

        // True heading (deg). KeyCompassHeading can be -180..180; normalize to 0..360.
        val heading = (((lastHeading ?: 0.0) % 360.0) + 360.0) % 360.0

        val battery = lastBatteryPct
        val isFlying = lastIsFlying
        val flightTimeSec = lastFlightTimeSec

        // Flight record. FED from the caches, never its own subscription. Deliberately before
        // the TAK publish and outside its connected-check: the record must be written with no
        // server and no network. MSL is passed as NaN until the terrain reference latches so
        // the GPX falls back rather than inventing a datum.
        FlightPathLogger.onTelemetry(
            lat, lon,
            aircraftMsl(relAlt) ?: Double.NaN, relAlt,
            speed, heading, battery, lastSatCount,
        )

        // Warning policy, fed from the same caches for the same reason: the banner and the
        // PLI must never disagree about whether the aircraft was flying.
        FlightWarnings.onState(warningsFrame(), isFlying, relAlt, homeDistanceMeters(lat, lon))

        val gimbalPitch = lastGimbalAttitude?.pitch ?: 0.0
        val gimbalYaw = lastGimbalAttitude?.yaw ?: 0.0

        // Compute the camera look-point + sensor FOV BEFORE the PLI, so the PLI can carry
        // the <sensor> element (ATAK/taklite draw the FOV cone from it).
        if (cameraPointEnabled) {
            pushCameraPoint(lat, lon, relAlt, heading)
        } else {
            sensorFov = -1.0; sensorVfov = -1.0; sensorAzimuth = -1.0
            sensorElevation = 0.0; sensorRange = -1.0
        }

        val absAlt = absoluteAlt(relAlt)
        AppLog.v(TAG, "tick: lat=$lat lon=$lon relAlt=$relAlt absAlt=$absAlt " +
            "hdg=${"%.0f".format(heading)} " +
            "spd=${"%.1f".format(speed)} batt=$battery% flying=$isFlying tak.connected=${tak.isConnected}")

        // north reference = 0: the <sensor azimuth> is an ABSOLUTE true-north bearing.
        //
        // ⚠ THE DRONE MARKER ADVERTISES THE VIDEO. Passing null here means a pilot looking at
        // the aircraft on the TAK map finds no stream on it.
        //
        // THIS IS THE ONE MARKER THAT ADVERTISES THE STREAM (design decision). Putting the url
        // on the pilot marker as well would keep the stream findable when the aircraft has no
        // GPS and this message is not sent, but TAK Aware draws that pilot as a 2525 square,
        // and the dot won. See sendPilotPli.
        //
        // ⚠ WHETHER THIS URL REACHES THE WIRE IS NOT DECIDED HERE. With an Elevated account
        // configured the shared core strips it from the Standard connection and keeps it on
        // the Elevated one — `TakManager.videoFor`. Pass the url unconditionally; the policy
        // is one place and this is not it.
        // The CoT hae slot takes an ABSOLUTE altitude — never relAlt. See absoluteAlt().
        tak.sendDronePLI(droneUid, droneCallsign, lat, lon, absAlt, heading, speed, battery,
            videoUrl, spiUid,
            sensorFov, sensorVfov, sensorAzimuth, sensorElevation, sensorRange, 0.0,
            0.0, gimbalPitch, gimbalYaw,
            isFlying, flightTimeSec,
            lastBatteryMaxMah, lastBatteryRemainMah, lastVoltageMv / 1000.0)
    }

    // Transition flags for the two pilot-fix states below — logged on change, not on every
    // 2s tick.
    @Volatile private var pilotFixMissing = false
    @Volatile private var pilotFixOldLogged = false

    /**
     * The PILOT's marker — the person on the ground, at the controller's own position.
     * It carries no video url (see [sendPilotPli]); the aircraft marker does.
     *
     * ## With no fix, the marker still goes out
     *
     * If the method stopped when the controller had no fix, the application would publish no
     * pilot marker, and the controller would not be in the CONTACT LIST of the other clients.
     * Nobody can send a marker to a client that is not in their list. A controller indoors, or
     * with a cold GPS receiver, could not receive markers. That is a worse condition than an
     * unknown position.
     *
     * The behaviour, and its cost, live in the shared core: `TakManager.sendPilotPLI` takes a
     * null location and sends the "position not known" form (`how="h-g-i-g-o"`, 0,0, `hae`,
     * `ce` and `le` not known, no track, no GPS source). Read the note there. This method only
     * decides what to log, once, at each change of state.
     */
    private fun pushPilotPli() {
        val fix = OperatorLocation.latest
        if (fix == null) {
            // ⚠ THE ONE LINE THAT EXPLAINS A PILOT MARKER WITH NO POSITION. The message on the
            // wire shows it too: how="h-g-i-g-o" and ce not known, where a real fix carries
            // how="m-g" and the true ce.
            if (!pilotFixMissing) {
                pilotFixMissing = true
                AppLog.w(TAG, "the controller has no position fix — the pilot marker goes out " +
                    "in the \"position not known\" form (0,0). It stays in the contact list " +
                    "of the team, thus you can still send markers to this controller. The " +
                    "real position replaces it at the first fix. See OperatorLocation for " +
                    "what feeds this.")
            }
            // The age test below needs a real fix. Send, then stop here.
            sendPilotPli(null)
            return
        }
        if (pilotFixMissing) {
            pilotFixMissing = false
            AppLog.i(TAG, "the controller has a fix — the pilot marker publishes a real " +
                "position and no longer publishes 0,0")
        }

        // AGE OF THE FIX, not just its presence. A fix that stops refreshing keeps being
        // republished at the same point: the marker stays on the map and does NOT follow the
        // pilot — a different failure from the case above, and it looks nothing like it.
        // elapsedRealtime, never the wall clock: a clock correction mid-flight
        // must not read as an hour-old fix.
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000L
        if (ageMs > PILOT_FIX_OLD_MS) {
            if (!pilotFixOldLogged) {
                pilotFixOldLogged = true
                AppLog.w(TAG, "pilot position is %.0fs old (provider=%s) — the marker still "
                    .format(ageMs / 1000.0, fix.provider) +
                    "publishes, thus it will NOT go stale, but it stops following the pilot. " +
                    "This is normal if the pilot has not moved: the receiver only reports a " +
                    "new fix after a small distance.")
            }
        } else if (pilotFixOldLogged) {
            pilotFixOldLogged = false
            AppLog.i(TAG, "pilot position is fresh again")
        }

        sendPilotPli(fix)
    }

    /**
     * Sends one pilot PLI. [fix] is null when the controller has no position.
     *
     * ⚠ **NO VIDEO URL ON THE PILOT MARKER (design decision).** The aircraft marker alone
     * carries the stream. Passing [videoUrl] here as well would let a downed or GPS-less
     * aircraft still leave a marker on the network saying where the stream was. The cost,
     * measured against TAK Aware: a team member whose report carries a video entry is drawn as
     * a MIL-STD-2525 unit symbol (a cyan square) instead of the team-member dot, for as long
     * as the stream is up. Proved by the pilot turning back into a dot the moment the url was
     * absent, with nothing else changed. The dot won. The shared core keeps the parameter.
     */
    private fun sendPilotPli(fix: android.location.Location?) {
        runCatching {
            tak.sendPilotPLI(fix, droneCallsign, "Team Member", pilotBatteryPct(), null)
        }.onFailure { AppLog.w(TAG, "pilot PLI failed: ${it.message}") }
    }

    /**
     * CONTROLLER battery, not the aircraft's — here the RC Plus 2 itself. Cached for
     * [BATTERY_CACHE_MS]; on failure the LAST GOOD reading is returned, never 100.
     */
    private var batteryPctCache = 100
    private var batteryPctReadAt = 0L
    private fun pilotBatteryPct(): Int {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - batteryPctReadAt < BATTERY_CACHE_MS && batteryPctReadAt != 0L) return batteryPctCache
        runCatching {
            val bm = appContext.getSystemService(android.content.Context.BATTERY_SERVICE)
                as? android.os.BatteryManager ?: return@runCatching
            val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 0..100) {
                batteryPctCache = pct
                batteryPctReadAt = now
            }
        }.onFailure { AppLog.w(TAG, "controller battery read failed: ${it.message}") }
        return batteryPctCache
    }

    /**
     * True geographic bearing the camera points along. Prefers KeyYawRelativeToAircraftHeading
     * (heading-stable), falling back to rawYaw. The pilot's bearing calibration
     * ([TakBridgeHolder.currentBearingOffset]) applies to EITHER path — it used to apply
     * only on the fallback, so a dialed-in correction did nothing on the preferred path
     * (the one this airframe actually takes), same as the pitch offset already applies on both.
     */
    private fun cameraBearing(rawYaw: Double, aircraftHeading: Double): Double {
        val relYaw = lastGimbalYawRel
        val base = if (relYaw != null && relYaw.isFinite()) aircraftHeading + relYaw else rawYaw
        return CameraSlantPoint.norm360(base + TakBridgeHolder.currentBearingOffset)
    }

    private fun pushCameraPoint(lat: Double, lon: Double, aglMeters: Double, aircraftHeading: Double) {
        val gimbal = lastGimbalAttitude
        if (gimbal == null) {
            AppLog.v(TAG, "SPI skip: gimbal attitude not yet received")
            return
        }
        val pitch = gimbal.pitch
        val yaw = gimbal.yaw
        val bearing = cameraBearing(yaw, aircraftHeading)

        // Slant-range calibration bias — the pilot-adjustable aim offset. Ground error
        // from an aim bias scales as 1/sin²(pitch): at 200ft AGL one degree is ~5ft at 54°
        // down but ~320ft at 6°, so it hides at steep angles and must be measured shallow.
        val pitchAdj = pitch + TakBridgeHolder.currentPitchOffset

        // ABOVE THE HORIZON THERE IS NO LOOK-POINT, SO PUBLISH NOTHING. An absent SPI is
        // honest; a fabricated one is worse than none, because the team will act on it.
        // Threshold matches CameraSlantPoint's own `depression > 1.0` guard.
        if (pitchAdj > -1.0) {
            sensorFov = -1.0; sensorVfov = -1.0; sensorAzimuth = -1.0
            sensorElevation = pitchAdj; sensorRange = -1.0
            AppLog.v(TAG, "SPI suppressed: camera at or above horizon " +
                "(pitch ${"%.1f".format(pitchAdj)}) — no ground intersection to publish")
            return
        }

        val gp = CameraSlantPoint.compute(
            lat, lon, aglMeters, bearing, pitchAdj, ::elevationLookup, aircraftMsl(aglMeters))
        tak.sendCameraPoint(spiUid, droneUid, "$droneCallsign-SPI", gp.lat, gp.lon, gp.rangeMeters)

        // FOV cone: ATAK/taklite draw it natively from the drone PLI's <sensor> element.
        // Zoom-corrected, so the cone narrows when the pilot zooms in.
        sensorFov = hFovDeg(zoomFactor)
        sensorVfov = vFovDeg(zoomFactor)
        sensorAzimuth = bearing
        // pitchAdj, NOT raw pitch. Publishing the raw value here while the suppressed branch
        // above publishes the adjusted one is harmless while the offset is a 0.0 constant,
        // and wrong the moment it is calibratable: the cone's elevation would disagree
        // with the look-point the same calibration moves.
        sensorElevation = pitchAdj
        sensorRange = gp.rangeMeters
        AppLog.v(TAG, "SPI: pitch=$pitch yaw=$yaw heading=${"%.0f".format(aircraftHeading)} " +
            "az=${"%.0f".format(bearing)} alt=$aglMeters range=${Math.round(gp.rangeMeters)}m")
    }

    private fun isValidLat(v: Double) = v.isFinite() && v != 0.0 && v >= -90.0 && v <= 90.0
    private fun isValidLon(v: Double) = v.isFinite() && v != 0.0 && v >= -180.0 && v <= 180.0

    /** Snapshot of cached telemetry for the on-screen HUD. Reads the same caches pushOnce()
     *  uses, just without gating on the 2s CoT-push tick. */
    data class Hud(
        val lat: Double, val lon: Double, val alt: Double,
        val speedMs: Double, val headingDeg: Double, val batteryPct: Int,
        val satCount: Int, val gimbalPitch: Double?, val hasFix: Boolean,
        val homeLat: Double, val homeLon: Double, val homeSet: Boolean,
        val flightTimeSec: Int, val uplinkSignalPct: Int?, val isGoingHome: Boolean,
        val isRecording: Boolean, val liveIso: Int?, val liveShutter: String?,
        /** The AIRCRAFT's own remaining-flight-time estimate, seconds, or null if it isn't
         *  reporting one yet (v5: LowBatteryRTHInfo.remainingFlightTime). */
        val remainingFlightTimeSec: Int?,
        /** AIRCRAFT-to-RC link quality — the direction VIDEO travels. For diagnostics. */
        val downlinkSignalPct: Int? = null,
        /** OcuSync's reported video-link capacity in Mbps. v5 exposes no video data-rate
         *  callback; always null here, kept so the HUD shape stays stable for its consumers. */
        val videoDataRateMbps: Float? = null,
        /**
         * Return-to-home height IN METRES AS THE AIRCRAFT REPORTS IT, or null before it has
         * said. This is the aircraft's answer (KeyGoHomeHeight listen), not the Pre-Flight
         * preference — those can differ, and the difference is the point.
         */
        val rthHeightM: Int? = null,
        /** What the camera says it is set to save, or null until it has said. LAST, so the
         *  positional construction in hud() is untouched. */
        val cameraMode: dji.sdk.keyvalue.value.camera.CameraMode? = null,
        /** KeyIsFlying, for the refusals that must not act on an airborne aircraft. */
        val isFlying: Boolean = false,
    )

    /**
     * Whether the camera is still busy taking or writing a still. v5 reports this as the
     * single KeyIsShootingPhoto. False when no value has arrived: a missing subscription
     * must not deadlock the caller into waiting forever.
     */
    fun photoInProgress(): Boolean = lastIsShootingPhoto

    fun hud(): Hud {
        val loc = lastLocation
        val lat = loc?.latitude ?: Double.NaN
        val lon = loc?.longitude ?: Double.NaN
        val hasFix = isValidLat(lat) && isValidLon(lon)
        val vel = lastVelocity
        val speed = if (vel != null) sqrt(vel.x * vel.x + vel.y * vel.y) else 0.0
        val heading = (((lastHeading ?: 0.0) % 360.0) + 360.0) % 360.0
        val home = lastHomeLocation
        return Hud(
            lat, lon, loc?.altitude ?: 0.0, speed, heading,
            lastBatteryPct,
            lastSatCount,
            lastGimbalAttitude?.pitch, hasFix,
            home?.latitude ?: Double.NaN, home?.longitude ?: Double.NaN,
            lastHomeSet,
            lastFlightTimeSec, lastUplinkQuality,
            isGoingHome(),
            lastIsRecording,
            ExposureController.isoValue(lastIsoName),
            ExposureController.shutterLabel(lastShutterName),
            lastRemainingFlightSec,
            lastDownlinkQuality,
            null,
            lastRthHeight,
            cameraMode = lastCameraMode,
            isFlying = lastIsFlying,
        )
    }

    /** Where the camera is pointing: true-north bearing and pitch, both degrees. */
    data class CameraPose(val bearingDeg: Double, val pitchDeg: Double)

    /**
     * True if [candidate] is a uid THIS app publishes — our own aircraft PLI or its sensor
     * point. Neither is a target: drawing the SPI would pin a marker permanently under the
     * crosshair.
     */
    fun isOwnPublishedUid(candidate: String?): Boolean =
        candidate != null && (candidate == droneUid || candidate == spiUid)

    /**
     * Current camera pose, or null until GPS/gimbal state has arrived. Computed from the
     * SAME [cameraBearing] + [PITCH_OFFSET_DEG] model that [lookPoint] uses — one model,
     * one place, so the AR overlay and marker drops cannot disagree.
     */
    // ---- Camera-pose smoothing, for the DISPLAY only ----

    @Volatile private var smoothedBearing: Double? = null
    @Volatile private var smoothedPitch: Double? = null
    @Volatile private var poseFilterAtNs = 0L

    /**
     * The camera's pose for DRAWING, low-pass filtered.
     *
     * ⚠ **THE AR MARKERS JITTER IF NOTHING FILTERS THIS.** `ArOverlayView` redraws every
     * 100 ms; rebuilding the pose from the raw latest sensor values every frame puts every
     * sample of sensor noise on the screen. Measured with the aircraft ON THE GROUND and the
     * gimbal PERFECTLY STILL — `camPitch` standard deviation exactly 0.00 over 80 frames —
     * the bearing still moved:
     *
     *     camBrg   sd 0.21 deg, range 0.80 deg, changed on 73 % of frames
     *     screen x sd 3.1 px,   range 12 px,    moved on 86 % of frames
     *
     * With the gimbal static the only moving input is `aircraftHeading`, so **the noise is the
     * COMPASS**. And it scales with zoom: 0.80 deg is 22 px at the wide FOV and about 40 px at
     * 2X, which is why the jitter looks worse the closer you look.
     *
     * ## Why the filter advances on TIME and not on calls
     *
     * ⚠ There are TWO consumers at DIFFERENT RATES — the AR overlay at 10 Hz and the
     * crosshair's bearing readout on the 500 ms HUD tick. A fixed per-call alpha would smooth
     * by a different amount depending on WHO asked and HOW OFTEN, and two consumers in the
     * same frame would advance the filter twice. So alpha is derived from the elapsed time,
     * `1 - exp(-dt / TAU)`: any call pattern gives the same smoothing in seconds, and a second
     * call in the same millisecond moves it by almost nothing.
     *
     * ## What this must never touch
     *
     * ⚠ **THE SPI AND A DROPPED MARKER STAY RAW.** They call [cameraBearing] directly, not
     * this, and they must: a marker has to land where the crosshair is NOW, not where it was
     * a third of a second ago. Smoothing is a rendering choice and it stops at the things
     * being rendered. Do not "simplify" by routing those through here.
     */
    fun cameraPose(): CameraPose? {
        val gimbal = lastGimbalAttitude ?: return null
        if (lastLocation == null) return null
        val heading = CameraSlantPoint.norm360(lastHeading ?: 0.0)
        val rawBearing = cameraBearing(gimbal.yaw, heading)
        val rawPitch = gimbal.pitch + TakBridgeHolder.currentPitchOffset

        val nowNs = System.nanoTime()
        val prevBrg = smoothedBearing
        val prevPitch = smoothedPitch
        if (prevBrg == null || prevPitch == null) {
            // First pose after a start: adopt it whole rather than easing up from zero.
            smoothedBearing = rawBearing
            smoothedPitch = rawPitch
            poseFilterAtNs = nowNs
            return CameraPose(rawBearing, rawPitch)
        }
        val dtSec = ((nowNs - poseFilterAtNs).coerceAtLeast(0L)) / 1_000_000_000.0
        poseFilterAtNs = nowNs
        val alpha = (1.0 - kotlin.math.exp(-dtSec / POSE_TAU_SEC)).coerceIn(0.0, 1.0)

        // Shortest way round, so a pose either side of north does not take the long path and
        // sweep the whole overlay across the screen.
        val dBrg = ((rawBearing - prevBrg + 540.0) % 360.0) - 180.0
        val dPitch = rawPitch - prevPitch

        // ⚠ A REAL SLEW SNAPS, IT DOES NOT EASE. Filtering a deliberate pan would make the
        // overlay trail the picture, which reads as broken rather than smooth. Past the
        // threshold the pilot is MOVING the camera and the truth beats the smoothing.
        val newBrg = if (kotlin.math.abs(dBrg) > POSE_SNAP_DEG) rawBearing
                     else CameraSlantPoint.norm360(prevBrg + alpha * dBrg)
        val newPitch = if (kotlin.math.abs(dPitch) > POSE_SNAP_DEG) rawPitch
                       else prevPitch + alpha * dPitch
        smoothedBearing = newBrg
        smoothedPitch = newPitch
        return CameraPose(newBrg, newPitch)
    }

    /**
     * One-shot ground point the camera is currently aimed at (for the "drop marker at
     * look-point" flow). Returns (lat, lon, terrain elevation) or null if GPS/gimbal state
     * hasn't arrived yet.
     */
    /**
     * Ground distance to the look point, metres, or null when there is none (camera at or
     * above the horizon — same reason the SPI stays silent there). Feeds the reticle readout
     * on the HUD tick; the SAME [CameraSlantPoint] solve as [lookPoint], so the number and a
     * dropped marker cannot disagree.
     */
    fun lookRangeMeters(): Double? {
        val gimbal = lastGimbalAttitude ?: return null
        val loc = lastLocation ?: return null
        if (!isValidLat(loc.latitude) || !isValidLon(loc.longitude)) return null
        val pitchAdj = gimbal.pitch + TakBridgeHolder.currentPitchOffset
        if (pitchAdj > -1.0) return null
        val relAlt = loc.altitude
        val heading = (((lastHeading ?: 0.0) % 360.0) + 360.0) % 360.0
        val gp = CameraSlantPoint.compute(
            loc.latitude, loc.longitude, relAlt, cameraBearing(gimbal.yaw, heading), pitchAdj,
            ::elevationLookup, aircraftMsl(relAlt),
        )
        return gp.rangeMeters
    }

    fun lookPoint(): Triple<Double, Double, Double>? {
        val gimbal = lastGimbalAttitude ?: return null
        val loc = lastLocation ?: return null
        if (!isValidLat(loc.latitude) || !isValidLon(loc.longitude)) return null
        val relAlt = loc.altitude
        val heading = (((lastHeading ?: 0.0) % 360.0) + 360.0) % 360.0
        val bearing = cameraBearing(gimbal.yaw, heading)
        val gp = CameraSlantPoint.compute(
            loc.latitude, loc.longitude, relAlt, bearing, gimbal.pitch + TakBridgeHolder.currentPitchOffset,
            ::elevationLookup, aircraftMsl(relAlt),
        )
        return Triple(gp.lat, gp.lon, gp.elevationMeters)
    }

    /**
     * Ground distance from the home point, metres, or NaN when no home point is set yet.
     * Equirectangular approximation — error is centimetres at these ranges.
     */
    private fun homeDistanceMeters(lat: Double, lon: Double): Double {
        val home = lastHomeLocation ?: return Double.NaN
        val hLat = home.latitude
        val hLon = home.longitude
        if (!isValidLat(hLat) || !isValidLon(hLon)) return Double.NaN
        val meanLatRad = Math.toRadians((lat + hLat) / 2.0)
        val dLat = Math.toRadians(lat - hLat) * EARTH_RADIUS_M
        val dLon = Math.toRadians(lon - hLon) * EARTH_RADIUS_M * Math.cos(meanLatRad)
        return sqrt(dLat * dLat + dLon * dLon)
    }

    /** Aircraft altitude above MEAN SEA LEVEL, or null before the takeoff terrain reference
     *  latches. */
    private fun aircraftMsl(heightAboveTakeoff: Double): Double? =
        TerrainAgl.takeoffTerrainElevMsl?.plus(heightAboveTakeoff)

    /**
     * The aircraft's ABSOLUTE altitude for the CoT `<point hae>`, in metres.
     *
     * Publishing [heightAboveTakeoff] straight into the `hae` slot, which `CotBuilder`
     * documents as "height above ellipsoid", makes every aircraft marker low by the elevation
     * of the launch site, while the PILOT marker beside it publishes a true geodetic altitude
     * from Android's `Location.getAltitude()` — on a 500 m site the aircraft renders ~500 m
     * below the pilot standing under it. Keep the absolute and takeoff-relative altitudes
     * apart on purpose.
     *
     * Preference order, best datum first:
     *  1. `KeyTakeoffLocationAltitude` + the relative height. This is DJI's OWN "AMSL" formula
     *     — their UX SDK's altitude widget computes it exactly this way — and it works on every
     *     airframe here without terrain data.
     *  2. The DTED-derived [aircraftMsl], for the window before the aircraft reports its
     *     takeoff altitude.
     *  3. The relative height, unchanged, as the last resort. Wrong datum, but it is what
     *     shipped and it beats publishing a zero for a flying aircraft. Logged once so the
     *     degraded case is visible rather than silent.
     *
     * ⚠ Both real paths give MSL (DJI applies an EGM96 geoid), not strict WGS84 ellipsoid
     * height. The residual geoid offset is tens of metres, against the hundreds this fixes;
     * TerrainAgl already records that offset as a known open item for the whole app.
     */
    private fun absoluteAlt(heightAboveTakeoff: Double): Double {
        val takeoffAlt = lastTakeoffAltMsl
        if (takeoffAlt != null && takeoffAlt.isFinite() && takeoffAlt != 0.0) {
            return takeoffAlt + heightAboveTakeoff
        }
        aircraftMsl(heightAboveTakeoff)?.let { if (it.isFinite()) return it }
        if (!absoluteAltFallbackLogged) {
            absoluteAltFallbackLogged = true
            AppLog.w(TAG, "no absolute altitude yet (takeoffAlt=$takeoffAlt, no DTED reference) " +
                "— publishing height above takeoff in the CoT hae for now")
        }
        return heightAboveTakeoff
    }

    /** So the [absoluteAlt] degraded-path warning is logged once per bridge start, not per tick. */
    @Volatile private var absoluteAltFallbackLogged = false

    /** DTED-backed elevation lookup for [CameraSlantPoint], or null if no tile covers the
     *  point. */
    private fun elevationLookup(lat: Double, lon: Double): Double? =
        DtedIndex.elevationAt(appContext, lat, lon)

    companion object {
        private const val TAG = "DroneTakBridge"

        /**
         * Time constant of the camera-pose low-pass, in seconds. At the AR overlay's 10 Hz
         * this settles about 95 % of a step in roughly 0.75 s while removing the compass
         * shimmer measured at 0.21 deg standard deviation. Raising it buys smoothness and
         * costs responsiveness on a slow pan; lowering it does the reverse.
         */
        private const val POSE_TAU_SEC = 0.25

        /** Past this much change in one update the camera is being SLEWED, not jittering, so
         *  the pose snaps. See [cameraPose]. */
        private const val POSE_SNAP_DEG = 8.0

        /** How old the last location may be and still be worth publishing as the aircraft's
         *  position. */
        private const val TELEMETRY_FRESH_MS = 5_000L

        /** How long a controller-battery reading is reused before another binder IPC. */
        private const val BATTERY_CACHE_MS = 30_000L

        private const val EARTH_RADIUS_M = 6_371_000.0

        /** A pilot fix older than this stops following the pilot — logged once. */
        private const val PILOT_FIX_OLD_MS = 5 * 60_000L

        /** Flight-readiness snapshots — survives the "TAK logging off" filter. */
        private const val READY_TAG = "TP2Ready"

        // The aim offsets are LIVE VALUES on TakBridgeHolder, set from the flight screen's
        // Aim Calibration dialog and persisted by ArSettings — they are airframe property,
        // re-checked after a gimbal strike, a repair or an airframe swap. They are not
        // compile-time constants here: a constant nobody can adjust in the field is no use.

        /**
         * Camera field of view, corrected for digital zoom, shared with the AR overlay.
         * Digital zoom is a centre crop, so the angular width shrinks non-linearly:
         * effectiveHalfAngle = atan(tan(baseHalfAngle) / zoom).
         */
        // THE CAMERA'S OWN ANSWER FIRST (CameraFov). It already includes the zoom — the
        // hybrid focal length is live — so the nominal-rung division below is only the
        // fallback for a camera that has not reported. That fallback is known-wrong above 1x
        // (the tele lens is a different focal length); the report is not.
        //
        // THE FALLBACK FOLLOWS THE LENS: in thermal, before the focal read has answered, a
        // fallback to the calibrated VISIBLE base would be projected across a picture half as
        // wide. publishedHFov holds the rule and LensFovPolicyTest pins it. ONE accessor for
        // the overlay's projection AND the published <sensor> cone, so the screen and the
        // wire cannot disagree.
        fun hFovDeg(zoom: Double = 1.0) = publishedHFov(
            lens = TakBridgeHolder.activeLens,
            liveHFov = if (TakBridgeHolder.hasCameraFov) TakBridgeHolder.currentHFov() else null,
            calibratedHFov = zoomedFov(TakBridgeHolder.currentHFovBase, zoom),
            irHFov = TakBridgeHolder.irHFov,
        )

        /** The vertical that pairs with [hFovDeg] — derived from THAT horizontal, never from
         *  the calibrated base on its own. */
        fun vFovDeg(zoom: Double = 1.0) = TakBridgeHolder.vFovFor(hFovDeg(zoom))

        private fun zoomedFov(baseDeg: Double, zoom: Double): Double {
            if (!zoom.isFinite() || zoom <= 1.0) return baseDeg
            val halfRad = Math.toRadians(baseDeg / 2.0)
            return 2.0 * Math.toDegrees(Math.atan(Math.tan(halfRad) / zoom))
        }
    }
}
