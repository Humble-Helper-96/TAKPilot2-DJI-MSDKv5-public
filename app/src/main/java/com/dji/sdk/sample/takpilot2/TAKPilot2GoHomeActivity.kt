package com.dji.sdk.sample.takpilot2

import androidx.core.content.ContextCompat
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dji.sdk.sample.BuildConfig
import com.dji.sdk.sample.R
import com.dji.sdk.sample.tak.NetworkStatus
import com.dji.sdk.sample.DataSyncActivity
import com.dji.sdk.sample.tak.DebugActivity
import com.dji.sdk.sample.tak.DjiSdkBridge
import com.dji.sdk.sample.tak.AircraftStorage
import com.dji.sdk.sample.tak.DjiObstacleState
import com.dji.sdk.sample.tak.ControlResponse
import com.dji.sdk.sample.tak.FlightLimitsController
import com.dji.sdk.sample.tak.FlightPathLogger
import com.dji.sdk.sample.tak.MediaServerProbe
import com.dji.sdk.sample.tak.VideoTransport
import com.dji.sdk.sample.tak.TakAutoConnect
import com.dji.sdk.sample.tak.TakBridgeHolder
import com.dji.sdk.sample.tak.TakShutdown
import com.dji.sdk.sample.tak.TakConnectActivity
import com.dji.sdk.sample.tak.TakForegroundService
import com.dji.sdk.sample.tak.VideoStreamerHolder
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.flightcontroller.FailsafeAction
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.common.error.IDJIError
import dji.v5.common.callback.CommonCallbacks
import dji.sdk.keyvalue.value.remotecontroller.ControlMode
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.v5.manager.KeyManager

/**
 * TAKPilot2 Go home screen — the app's launcher activity, replacing DJI's stock landing
 * screen. Quick Controls card (TAK Setup / Data Sync) + a large "Enter Flight" card that opens
 * the custom flight screen ([TAKPilot2GoFlightActivity]).
 *
 * Registers with the DJI SDK and starts the product connection itself on launch via
 * [DjiSdkBridge] — there is no stock "Register App" + "Open" screen to visit first.
 * [updateStatus] polls the product and renders "Not connected" gracefully until one connects,
 * so no separate connecting-state UI is needed.
 */
class TAKPilot2GoHomeActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var aircraft: TextView
    private lateinit var sdk: TextView
    private lateinit var batteryLevels: TextView
    private lateinit var storage: TextView
    private lateinit var avoidance: TextView
    private lateinit var signalLoss: TextView
    private lateinit var stickMode: TextView
    private lateinit var controlResponse: TextView
    private lateinit var initializing: TextView
    /** The controller's stick mapping as the RC reports it. Written by Pre-Flight and, until
     *  now, never read back — so this row is the first thing that can contradict it. */
    @Volatile private var aircraftStickMode: ControlMode? = null
    /** The failsafe, pitch speed and battery thresholds READ HERE rather than borrowed from
     *  whatever another screen happened to leave behind. See [readReadinessState]. */
    @Volatile private var aircraftFailsafeHere: FailsafeAction? = null
    @Volatile private var aircraftPitchSpeedHere: Int? = null
    @Volatile private var warnPctHere: Int? = null
    @Volatile private var critPctHere: Int? = null
    private lateinit var takStatus: TextView
    private lateinit var takDot: android.view.View
    private lateinit var network: TextView
    private lateinit var networkDot: android.view.View
    private lateinit var permissionsRow: android.view.View
    private lateinit var permissionsStatus: TextView
    private lateinit var permissionsDot: android.view.View
    private lateinit var mediaStatus: TextView
    private lateinit var mediaDot: android.view.View

    /** When the aircraft became reachable, for the "READING AIRCRAFT…" deadline. 0 = not
     *  connected. Elapsed-realtime, so a clock change cannot extend or end the hold. */
    private var connectedAtMs = 0L

    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything else that can throw: the flight screen's OOM-restart guard reads this
        // to tell "the pilot walked here" from "Android resurrected the task into a cold process".
        visitedThisProcess = true
        setContentView(R.layout.activity_takpilot2go_home)
        AppLog.v(TAG, "onCreate")

        aircraft = findViewById(R.id.homeAircraft)
        sdk = findViewById(R.id.homeSdk)
        batteryLevels = findViewById(R.id.homeBatteryLevels)
        storage = findViewById(R.id.homeStorage)
        avoidance = findViewById(R.id.homeAvoidance)
        signalLoss = findViewById(R.id.homeSignalLoss)
        stickMode = findViewById(R.id.homeStickMode)
        controlResponse = findViewById(R.id.homeControlResponse)
        initializing = findViewById(R.id.homeInitializing)
        takStatus = findViewById(R.id.homeTakStatus)
        takDot = findViewById(R.id.homeTakDot)
        network = findViewById(R.id.homeNetwork)
        networkDot = findViewById(R.id.homeNetworkDot)
        permissionsRow = findViewById(R.id.homePermissionsRow)
        permissionsStatus = findViewById(R.id.homePermissionsStatus)
        permissionsDot = findViewById(R.id.homePermissionsDot)
        mediaStatus = findViewById(R.id.homeMediaStatus)
        mediaDot = findViewById(R.id.homeMediaDot)
        // ⚠ A STATUS LINE A PILOT CANNOT CLEAR IS ONLY HALF A FIX. Tapping the row raises the
        // permission dialog for whatever is still missing.
        permissionsRow.setOnClickListener {
            if (DjiSdkBridge.hasMissingPermissions(this)) {
                AppLog.i(TAG, "tap: permissions row — requesting what is missing")
                DjiSdkBridge.requestMissingPermissions(this)
            }
        }

        // Fixed at build time, not runtime state — set once, never touched in updateStatus().
        // BuildConfig.VERSION_NAME rather than the PackageManager: same string, no IPC, and it
        // cannot disagree with what the TAK server was told (TakManager reports it as
        // <takv version>). versionCode is deliberately absent — an internal integer with no
        // semver meaning, and BUILD_TIME already identifies a build more precisely.
        findViewById<TextView>(R.id.homeVersion).text =
            "v${BuildConfig.VERSION_NAME}  ·  built ${BuildConfig.BUILD_TIME}"

        if (DjiSdkBridge.hasMissingPermissions(this)) {
            DjiSdkBridge.requestMissingPermissions(this)
        } else {
            DjiSdkBridge.registerAndConnect(this)
        }

        // If a TAK server is configured (saved enrollment), connect and pull channels now —
        // one shot per process, so the pilot never has to open Pre-Flight Setup just to get
        // back online.
        TakAutoConnect.attemptOnAppLaunch(applicationContext)

        findViewById<android.view.View>(R.id.homeEnterFlight).setOnClickListener {
            AppLog.v(TAG, "tap: Enter Flight")
            startActivity(Intent(this, TAKPilot2GoFlightActivity::class.java))
        }
        findViewById<Button>(R.id.homeTakSetup).setOnClickListener {
            AppLog.v(TAG, "tap: Pre-Flight Setup")
            startActivity(Intent(this, TakConnectActivity::class.java))
        }
        findViewById<Button>(R.id.homeFieldGuide).setOnClickListener {
            AppLog.v(TAG, "tap: Field Guide")
            startActivity(Intent(this, FieldGuideActivity::class.java))
        }
        findViewById<Button>(R.id.homeDataSync).setOnClickListener {
            AppLog.v(TAG, "tap: Data Sync")
            startActivity(Intent(this, DataSyncActivity::class.java))
        }
        findViewById<Button>(R.id.homeDebugLog).setOnClickListener {
            AppLog.v(TAG, "tap: Debug Log")
            startActivity(Intent(this, DebugActivity::class.java))
        }
        findViewById<Button>(R.id.homeQuit).setOnClickListener {
            AppLog.v(TAG, "tap: STOP/QUIT")
            confirmQuit()
        }
    }

    /** The "nuclear option": tear down every long-lived TAKPilot2 process (video stream +
     *  screen capture, TAK connection + its foreground service, telemetry bridge) and then
     *  kill this process outright, so a relaunch starts completely clean — for clearing out
     *  any stuck state found mid-operation without having to know which subsystem is wedged. */
    private fun confirmQuit() {
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Stop & Quit")
            .setMessage("Force-stop TAKPilot2 Go and all its background processes (video stream, TAK connection, telemetry)? You'll need to relaunch the app.")
            .setPositiveButton("Stop & Quit") { _, _ -> doQuit() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doQuit() {
        AppLog.i(TAG, "STOP/QUIT — tearing down and killing process")
        // The stream, the flight record, the bridge and BOTH TAK connections — the Elevated one
        // must not be left to killProcess. The GPX write is posted to the logger's worker,
        // and the 200ms delay before killProcess below is what lets it land; if it does not, the
        // orphan sweep completes it at the next launch — the CSV is already on disk either way.
        TakShutdown.closeEverything("stop/quit")
        runCatching { TakForegroundService.stop(applicationContext) }
        handler.removeCallbacksAndMessages(null)
        finishAffinity()
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 200)
    }

    override fun onResume() {
        super.onResume()
        AppLog.v(TAG, "onResume")
        // ⚠ ON RESUME, NOT ONLY ON CREATE. This screen is singleTask, and the foreground
        // service keeps the process alive across a swipe-away — so onCreate's one-shot
        // attempt can be the only one a pilot ever gets, even when they think they have
        // relaunched the app. See TakAutoConnect.retryIfDown.
        TakAutoConnect.retryIfDown(applicationContext)
        // Same reasoning, same trap, a different reader: the avoidance read is bounded and
        // gives up, so a pilot looking at this card is the moment to ask again. No-op when
        // the aircraft is absent or everything is already known. See
        // DjiObstacleState.refreshIfIncomplete.
        DjiObstacleState.refreshIfIncomplete()
        handler.post(refresh)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() in step with what actually arrived. Nothing reads it today, so this
        // changes no behaviour — it removes the trap where the next person to add intent
        // handling here reads the original launch intent and cannot see why.
        setIntent(intent)
        // USB accessory attach does not arrive here. It goes to UsbAttachActivity, which shows
        // nothing and finishes, because reaching THIS activity would mean destroying the
        // flight screen (Home is singleTask) and taking the TAK feed and the video with it.
        // MSDK v5 handles the accessory internally once the SDK is initialized anyway; there is
        // no relay broadcast to forward.
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == DjiSdkBridge.PERMISSION_REQUEST_CODE && !DjiSdkBridge.hasMissingPermissions(this)) {
            DjiSdkBridge.registerAndConnect(this)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun updateStatus() {
        // Synchronous cached read — null until an aircraft has connected. Rendering "Not
        // connected" from a null read predates v5 and needs no new connecting-state UI.
        val productType = if (DjiSdkBridge.isProductConnected) {
            runCatching {
                KeyManager.getInstance().getValue(KeyTools.createKey(ProductKey.KeyProductType))
            }.getOrNull()
        } else null
        aircraft.text = productType?.toString() ?: "Not connected"
        sdk.text = "MSDK 5.18"

        // Battery levels, straight from the AIRCRAFT — never the saved preference. If Apply
        // did not take, this is where it shows, so falling back to what the pilot typed would
        // hide the one failure this line exists to catch. Blank before a product is connected
        // and a dash until the aircraft answers: unknown must not look like a value.
        // ONE writer for this row — see refreshBatteryRow. It used to be painted here from
        // FlightLimitsController's statics, which are only populated once Pre-Flight has run;
        // this screen now reads the thresholds itself and that call would have overwritten
        // the fresh values with the stale ones on every resume.
        refreshBatteryRow()

        // WHERE THE CAMERA WILL WRITE. Blunt about the one case that loses footage: red when
        // the target is internal memory or the card cannot be written, green for a verified
        // card, amber while the camera has not answered. See AircraftStorage.
        if (productType != null) {
            AircraftStorage.refresh { runOnUiThread { renderStorage(); renderReadiness() } }
            readReadinessState()
        }
        renderStorage()
        renderReadiness()

        val connected = TakManager.getInstance().isConnected
        val color = if (connected) ContextCompat.getColor(applicationContext, R.color.tp_state_go) else ContextCompat.getColor(applicationContext, R.color.tp_state_danger)
        takStatus.text = if (connected) "TAK: Connected" else "TAK: Disconnected"
        takStatus.setTextColor(color)
        (takDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
            ?: takDot.background?.setTint(color)

        updateNetwork()
        updatePermissions()
        updateMediaServer()
    }

    /**
     * The network line. Green only when the system has CONFIRMED reachability — an attached
     * network that goes nowhere reads amber, which is the case that otherwise looks like a
     * broken TAK server. See [NetworkStatus].
     */
    /**
     * App permissions — two states, never three. A permission is granted or it is not.
     *
     * ⚠ The answer comes from [DjiSdkBridge], which validates its required set against what the
     * manifest actually declares — that gate carries the fix for the VIBRATE permission that
     * once stopped the SDK registering because it was requested and never declared. There is
     * deliberately no second permissions helper: two notions of "permissions" in one app would
     * be worse than the duplication it saves.
     */
    private fun updatePermissions() {
        val ok = !DjiSdkBridge.hasMissingPermissions(this)
        permissionsStatus.text =
            if (ok) "APP PERMISSIONS: Granted" else "APP PERMISSIONS: Denied"
        val color = ContextCompat.getColor(applicationContext,
            if (ok) R.color.tp_state_go else R.color.tp_state_danger)
        permissionsStatus.setTextColor(color)
        (permissionsDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
            ?: permissionsDot.background?.setTint(color)
    }

    /** The probe runs off the UI thread; these hold its last answer between refreshes. */
    @Volatile private var mediaProbe: MediaServerProbe.Result? = null
    @Volatile private var mediaProbeAtMs = 0L
    @Volatile private var mediaProbeRunning = false

    /**
     * Draws the media-server line, and refreshes the probe behind it when it is stale.
     *
     * ⚠ **GREEN MEANS THE SERVER IS UP, NOT THAT IT WILL TAKE THE STREAM.** Whether this
     * publisher is accepted depends on credentials, on whether the path may be published to,
     * and on the codec — all answered during a real publish. The LIVE pill on the flight screen
     * stays the authority on that. See [MediaServerProbe].
     */
    private fun updateMediaServer() {
        val p = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        val host = p.getString("video_host", "") ?: ""
        val port = p.getInt("video_rtsp_port", VideoTransport.RTSP.defaultPort)

        val now = android.os.SystemClock.elapsedRealtime()
        if (!mediaProbeRunning && now - mediaProbeAtMs > MEDIA_PROBE_PERIOD_MS) {
            mediaProbeRunning = true
            Thread {
                val r = MediaServerProbe.probe(host, port)
                mediaProbe = r
                mediaProbeAtMs = android.os.SystemClock.elapsedRealtime()
                mediaProbeRunning = false
                runOnUiThread { if (!isFinishing) updateMediaServer() }
            }.apply { isDaemon = true; name = "media-probe" }.start()
        }

        // Amber until the first answer — unknown is its own state, and a server that has not
        // been asked yet must not be drawn as either up or down.
        val res = mediaProbe
        mediaStatus.text = when (res) {
            MediaServerProbe.Result.REACHABLE -> "MEDIA SERVER: Reachable"
            MediaServerProbe.Result.UNREACHABLE -> "MEDIA SERVER: Not reachable"
            MediaServerProbe.Result.NOT_CONFIGURED -> "MEDIA SERVER: Not set"
            null -> "MEDIA SERVER: —"
        }
        val color = ContextCompat.getColor(applicationContext, when (res) {
            MediaServerProbe.Result.REACHABLE -> R.color.tp_state_go
            MediaServerProbe.Result.UNREACHABLE -> R.color.tp_state_danger
            else -> R.color.tp_state_unknown
        })
        mediaStatus.setTextColor(color)
        (mediaDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
            ?: mediaDot.background?.setTint(color)
    }

    private fun updateNetwork() {
        val net = NetworkStatus.read(this)
        val bars = net.bars()
        val suffix = if (bars.isEmpty()) "" else "  $bars"
        // ⚠ THE WORDING IS "WIFI:", DELIBERATELY. This row once said "Network:" while the same
        // element elsewhere said "WIFI:" — the same status in two languages, which is exactly
        // what the shared-wording rule exists to stop. The SIZES stay per-device; only the wording is shared.
        network.text = when (net.state) {
            NetworkStatus.State.CONNECTED -> "WIFI: ${net.label}$suffix"
            NetworkStatus.State.NO_INTERNET -> "WIFI: ${net.label} — NO INTERNET$suffix"
            NetworkStatus.State.OFF -> "WIFI: NOT CONNECTED"
        }
        val color = ContextCompat.getColor(
            applicationContext,
            when (net.state) {
                NetworkStatus.State.CONNECTED -> R.color.tp_state_go
                // We KNOW there is no route out — that is caution, not unknown.
                NetworkStatus.State.NO_INTERNET -> R.color.tp_state_caution
                NetworkStatus.State.OFF -> R.color.tp_state_danger
            }
        )
        network.setTextColor(color)
        // Same dot treatment as the TAK line it sits under, so the two read as one status block
        // rather than a status and a caption.
        (networkDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
            ?: networkDot.background?.setTint(color)
    }

    companion object {
        /** How long "READING AIRCRAFT…" may claim values are still arriving. Comfortably
         *  past DjiObstacleState's own re-read ladder (about 29 s), so the hold outlives the
         *  slowest honest read and ends soon after it gives up. */
        private const val HOLD_TIMEOUT_MS = 35_000L
        /** How often the media-server probe is allowed to run again. Long enough that the
         *  home screen's refresh tick does not hammer the server, short enough that a pilot
         *  who fixes the network sees it go green without leaving the screen. */
        private const val MEDIA_PROBE_PERIOD_MS = 10_000L

        private const val TAG = "TAKPilot2GoHome"

        /**
         * True once this PROCESS has passed through Home, which is the only place the DJI SDK is
         * registered and the product connection is started.
         *
         * Deliberately a plain static, not a persisted flag: it must reset when the process dies.
         * That is the whole signal — see the OOM-restart guard in [TAKPilot2GoFlightActivity].
         */
        @Volatile
        var visitedThisProcess = false
            private set
    }

    /**
     * Paints the storage row from what the CAMERA reported. Unknown is amber and is never
     * collapsed into "fine" — a blank or dashed row must not read as a working card.
     *
     * This, [refreshBatteryRow] and [renderReadiness] once read the connection with a BARE
     * `KeyManager.getValue(KeyConnection, false)`, while [updateStatus] wrapped its own SDK read
     * in `runCatching` — so the guarded call was protected and the three unguarded ones, on the
     * same 1.5s tick, could throw and kill the refresh loop outright. The whole readiness card
     * would then freeze on stale values with nothing to say it had stopped.
     *
     * All three now use [DjiSdkBridge.isProductConnected], which is the safer answer rather than
     * three more try/catches: it is a @Volatile flag pushed by the SDK's own connect/disconnect
     * callbacks, so it cannot throw AND it is not subject to the stale-cache trap the
     * synchronous getValue sits on. It is also what updateStatus already gates on, so the card's
     * rows can no longer disagree with each other about whether an aircraft is attached.
     */
    private fun renderStorage() {
        val connected = DjiSdkBridge.isProductConnected
        storage.text = if (!connected) "" else AircraftStorage.label()
        storage.setTextColor(ContextCompat.getColor(applicationContext, when {
            !connected -> R.color.tp_text_secondary
            AircraftStorage.recordingToInternal -> R.color.tp_state_danger
            AircraftStorage.willRecord -> R.color.tp_state_go
            AircraftStorage.location == null -> R.color.tp_state_unknown
            else -> R.color.tp_state_danger
        }))
    }

    /** Asks the REMOTE CONTROLLER what stick mapping it actually holds. Pre-Flight writes
     *  this key and nothing ever read it, so a write that did not take was invisible. */
    /**
     * Reads every readiness value STRAIGHT FROM THE AIRCRAFT.
     *
     * ⚠ IT USED TO BORROW THEM FROM OTHER SCREENS' STATICS, AND THREE ROWS SAT EMPTY. The
     * battery thresholds and the failsafe are populated by Pre-Flight, and the pitch speed by
     * the bridge when the gimbal comes up — so on a fresh start, with the pilot going straight
     * to this card, none of them had a value. A readiness card whose rows are blank until you
     * have visited other screens is not a readiness card. Same lesson as the camera state:
     * ask the aircraft, never a leftover.
     */
    private fun readReadinessState() {
        readStickMode()

        KeyManager.getInstance().getValue(
            KeyTools.createKey(FlightControllerKey.KeyFailsafeAction),
            object : CommonCallbacks.CompletionCallbackWithParam<FailsafeAction> {
                override fun onSuccess(value: FailsafeAction?) {
                    aircraftFailsafeHere = value
                    runOnUiThread { renderReadiness() }
                }
                override fun onFailure(error: IDJIError) {}
            })

        KeyManager.getInstance().getValue(
            KeyTools.createKey(GimbalKey.KeyPitchControlMaxSpeed, ComponentIndexType.LEFT_OR_MAIN),
            object : CommonCallbacks.CompletionCallbackWithParam<Int> {
                override fun onSuccess(value: Int?) {
                    aircraftPitchSpeedHere = value
                    runOnUiThread { renderReadiness() }
                }
                override fun onFailure(error: IDJIError) {}
            })

        KeyManager.getInstance().getValue(
            KeyTools.createKey(FlightControllerKey.KeyLowBatteryWarningThreshold),
            object : CommonCallbacks.CompletionCallbackWithParam<Int> {
                override fun onSuccess(value: Int?) {
                    warnPctHere = value
                    runOnUiThread { refreshBatteryRow() }
                }
                override fun onFailure(error: IDJIError) {}
            })

        KeyManager.getInstance().getValue(
            KeyTools.createKey(FlightControllerKey.KeySeriousLowBatteryWarningThreshold),
            object : CommonCallbacks.CompletionCallbackWithParam<Int> {
                override fun onSuccess(value: Int?) {
                    critPctHere = value
                    runOnUiThread { refreshBatteryRow() }
                }
                override fun onFailure(error: IDJIError) {}
            })
    }

    /** The battery row, from this screen's own read — falling back to whatever Pre-Flight
     *  left behind, which is better than a dash when it happens to be there. */
    private fun refreshBatteryRow() {
        val warn = warnPctHere ?: FlightLimitsController.aircraftWarningPct
        val crit = critPctHere ?: FlightLimitsController.aircraftCriticalPct
        val connected = DjiSdkBridge.isProductConnected
        batteryLevels.text = when {
            !connected -> ""
            warn != null && crit != null -> "BATTERY: WARN $warn% \u00b7 CRIT $crit%"
            else -> "BATTERY: \u2014"
        }
        batteryLevels.setTextColor(ContextCompat.getColor(applicationContext,
            if (connected && (warn == null || crit == null)) R.color.tp_state_unknown
            else R.color.tp_text_secondary))
        renderReadiness()
    }

    private fun readStickMode() {
        KeyManager.getInstance().getValue(
            KeyTools.createKey(RemoteControllerKey.KeyControlMode),
            object : CommonCallbacks.CompletionCallbackWithParam<ControlMode> {
                override fun onSuccess(value: ControlMode?) {
                    aircraftStickMode = value
                    runOnUiThread { renderReadiness() }
                }

                override fun onFailure(error: IDJIError) {}
            })
    }

    /**
     * The pre-flight readiness rows, each from the AIRCRAFT's own answer.
     *
     * ⚠ AMBER MEANS "NOT READ YET" AND IS NOT THE SAME AS OFF. Telling a pilot avoidance is
     * off when the aircraft simply has not answered is a lie with consequences; so is the
     * reverse. Every row here is blank before a product connects and amber until its value
     * lands.
     */
    private fun renderReadiness() {
        val connected = DjiSdkBridge.isProductConnected
        val unknown = ContextCompat.getColor(applicationContext, R.color.tp_state_unknown)
        val info = ContextCompat.getColor(applicationContext, R.color.tp_text_secondary)

        // The COMPOSITE, not one switch: downward sensing, vision positioning and the master
        // all have to be on before this aircraft protects a descent. See DjiObstacleState.
        val avoid = DjiObstacleState.cushionedLanding
        avoidance.text = when {
            !connected -> ""
            avoid == true -> "OBSTACLE AVOIDANCE: ON"
            avoid == false -> "OBSTACLE AVOIDANCE: OFF"
            else -> "OBSTACLE AVOIDANCE: \u2014"
        }
        avoidance.setTextColor(ContextCompat.getColor(applicationContext, when {
            avoid == true -> R.color.tp_state_go
            avoid == false -> R.color.tp_state_danger
            else -> R.color.tp_state_unknown
        }))

        // Signal loss is the failsafe the aircraft will actually perform. This SDK exposes it,
        // so the row states the real value.
        val fs = aircraftFailsafeHere ?: FlightLimitsController.aircraftFailsafe
        signalLoss.text = when {
            !connected -> ""
            fs != null -> "SIGNAL LOSS: " + fs.name.replace('_', ' ')
            else -> "SIGNAL LOSS: \u2014"
        }
        signalLoss.setTextColor(if (fs == null) unknown else info)

        val stick = aircraftStickMode
        stickMode.text = when {
            !connected -> ""
            stick != null -> "STICK MODE: " + when (stick) {
                ControlMode.JP -> "1"
                ControlMode.USA -> "2"
                ControlMode.CH -> "3"
                else -> stick.name
            }
            else -> "STICK MODE: \u2014"
        }
        stickMode.setTextColor(if (stick == null) unknown else info)

        val pitch = aircraftPitchSpeedHere ?: ControlResponse.aircraftPitchSpeed
        val mode = ControlResponse.Mode.values().firstOrNull { it.pitchSpeed == pitch }
        controlResponse.text = when {
            !connected -> ""
            mode != null -> "CONTROL RESPONSE: " + mode.label.uppercase()
            else -> "CONTROL RESPONSE: \u2014"
        }
        controlResponse.setTextColor(if (mode == null) unknown else info)

        // The hold: visible while any row is still waiting on the aircraft — BUT NOT FOR EVER.
        //
        // ⚠ WITHOUT A DEADLINE THIS COULD HANG FOR THE WHOLE FLIGHT. Every term below can
        // legitimately stay null: each read is bounded, gives up, and leaves its value
        // unknown. An unbounded hold then goes on promising values that have stopped coming,
        // with the rows beneath it showing "—" — "it is arriving" where the truth is "it did
        // not arrive". Seen on the controller with obstacle avoidance actually ON.
        //
        // A hold that cannot end is worse than no hold: it teaches a pilot to wait for a
        // number instead of reading the dash and asking why. After the deadline the card
        // shows what it knows and says "—" for the rest, which is honest and actionable.
        if (!connected) connectedAtMs = 0L
        else if (connectedAtMs == 0L) connectedAtMs = android.os.SystemClock.elapsedRealtime()

        val missing = avoid == null || fs == null || stick == null ||
            mode == null || AircraftStorage.location == null ||
            (warnPctHere ?: FlightLimitsController.aircraftWarningPct) == null
        val withinHold = connectedAtMs != 0L &&
            android.os.SystemClock.elapsedRealtime() - connectedAtMs < HOLD_TIMEOUT_MS
        val waiting = connected && missing && withinHold
        initializing.visibility = if (waiting) android.view.View.VISIBLE else android.view.View.GONE
    }
}
