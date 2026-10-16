package com.dji.sdk.sample.tak

import java.util.Locale
import androidx.core.content.ContextCompat
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.taklite.client.tak.CotBuilder
import com.taklite.client.tak.TakCertEnroller
import com.taklite.client.tak.TakManager
import com.taklite.client.tak.TakMissionClient
import com.dji.sdk.sample.R
import com.dji.sdk.sample.takpilot2.MaplibreStyle
import com.taklite.util.AppLog
import java.io.File
import java.util.UUID

/**
 * Minimal TAK enroll + connect screen for TAKPilot2.
 *
 * Reuses taklite's TakCertEnroller (cert enrollment over HTTPS) and TakManager
 * (TLS CoT client). On success it starts a DroneTakBridge that streams the M30's
 * position to the server as an air track. This is the fast path to verify
 * drone -> CoT -> TAK end-to-end; the full QR enrollment wizard comes later.
 */
class TakConnectActivity : AppCompatActivity() {

    private lateinit var status: TextView

    /** True only while code writes the battery fields for display — see the watcher in
     *  [setupBattery], which must not mistake that for the pilot typing. */
    private var suppressBatterySave = false

    /** This instance's speaker callbacks, kept so [onDestroy] can tell them apart. */
    private var speakerRecorderCb: (() -> Unit)? = null
    private var speakerBroadcastCb: (() -> Unit)? = null

    override fun onDestroy() {
        // ⚠ CLEAR ONLY OUR OWN. A mode change rebuilds this screen
        // with finish() + startActivity(), so the NEW instance's onCreate sets these callbacks
        // before the OLD instance's onDestroy runs. Nulling them unconditionally left the new
        // screen's voice-message rows deaf until it was opened again.
        if (SpeakerRecorder.onChanged === speakerRecorderCb) SpeakerRecorder.onChanged = null
        if (SpeakerBroadcast.onChanged === speakerBroadcastCb) SpeakerBroadcast.onChanged = null
        if (SpeakerRecorder.recording) SpeakerRecorder.cancel()
        // The TAK listeners went with the TAK configuration to TakServerActivity, which
        // attaches and detaches its own. This screen holds none of those — but it DOES hold
        // the avoidance one, and DjiObstacleState outlives the Activity.
        runCatching { DjiObstacleState.removeChangeListener(avoidanceListener) }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tak_connect)

        // Menu button on the left of the action bar, on every screen you can reach from Home.
        // Returns to the home screen, same as the system back gesture — a pilot should not have
        // to learn a different way out of each screen.
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }
        AppLog.v(TAG, "onCreate")

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setupSdCardSection()
        setupDroneSettings(prefs)
        setupMapDisplay()
        setupDtedSection()
        setupUasfmSection()
        setupSpeakerMessagesSection()
        setupVideoControls(prefs)

        // ⚠ THE TAK CONFIGURATION IS NOT ON THIS SCREEN ANY MORE — it is in
        // TakServerActivity, the same move VideoServersActivity made for the video settings.
        // What stays is the summary a pilot reads before a flight. See paintTakSummary.
        findViewById<Button>(R.id.takConfigureServer).setOnClickListener {
            AppLog.v(TAG, "tap: Configure TAK Server")
            if (refuseWhileModeCheckPending()) return@setOnClickListener
            startActivity(android.content.Intent(this, TakServerActivity::class.java))
        }
        paintTakSummary()
        setupAppModeSection(prefs)
        // LAST, deliberately. Every setup above populates and enables its own fields, so a lock
        // applied before them would be undone on the way past.
        setupConfigLocks()
    }

    /**
     * Section 8 — ENHANCED or BASIC. The mode itself is decided on first run by
     * TAKPilot2Application, never here; this screen only offers the change.
     *
     * ⚠ **THE PASSWORD IS ON THIS CONTROL AND IN BOTH DIRECTIONS** (design decision). In
     * Basic the configuration locks take no password at all, so this is the only gate left:
     * without it, a user could reach Basic, untick a lock for free, and return to Enhanced with
     * every section editable. And Basic -> Enhanced is NOT the safe direction — it re-narrows the
     * audience, and a team that can see the video loses it — so it asks too. A deliberate
     * departure from [ConfigLock]'s own asymmetry, which holds only because locking is safe.
     *
     * ⚠ **REFUSED IN FLIGHT AND WITH A STREAM LIVE — AND CHECKED TWICE.** The transition closes a
     * socket that carries the aircraft's position, camera point and markers, not only the video
     * link: a supervisor on the Elevated channel would lose the TRACK. The airborne flag alone
     * is not enough — a live stream must refuse it too.
     * Asked before the dialog, so nobody types a password for nothing, and again at the moment
     * of the change, so a takeoff while the dialog was open is still caught.
     */
    private fun setupAppModeSection(prefs: android.content.SharedPreferences) {
        paintAppModeSummary(prefs)
        findViewById<Button>(R.id.appModeButton).setOnClickListener {
            val target =
                if (AppMode.of(prefs) == AppMode.ENHANCED) AppMode.BASIC else AppMode.ENHANCED
            AppLog.v(TAG, "tap: Change Mode (-> $target)")
            refuseInFlightOrLive { promptForModeChange(prefs, target) }
        }
    }

    /**
     * Rebuilds this screen from the preferences alone, after a mode change.
     *
     * ⚠ **NOT `recreate()`.**
     * `recreate()` saves and RESTORES view state, as a configuration change does. The restore
     * runs after onCreate, so the radio listeners are already attached, and each radio replays
     * the OLD screen's checked state through setChecked — a GONE parent does not stop it. On the
     * way to BASIC with Server 2 active, the hidden active-server radio replayed "Server 2" and
     * its listener wrote video_active_slot=2 straight back over the transition's forced 1: LIVE
     * then published to the server BASIC hides. The return to ENHANCED was undone the same way.
     * Every control here is rebuilt from the preferences anyway, so restored view state can only
     * ever be stale: a new instance with no saved state is the whole fix. (The active-server
     * listener refuses Server 2 in BASIC as well — defence in depth.)
     *
     * A new onCreate also re-runs setupConfigLocks LAST, which is the ordering this screen
     * depends on.
     */
    private fun rebuildWithoutSavedState() {
        val fresh = android.content.Intent(intent)
        finish()
        overridePendingTransition(0, 0)
        startActivity(fresh)
        overridePendingTransition(0, 0)
    }

    /**
     * Runs [proceed] on the main thread only if no stream is live and the AIRCRAFT says it is on
     * the ground. See [AircraftFlightCheck] for why this is a fresh read and not the bridge's
     * cached HUD, which reads "grounded" on a flying aircraft whenever the flight screen has
     * stopped the bridge.
     */
    private fun refuseInFlightOrLive(proceed: () -> Unit) {
        if (modeCheckPending) return
        if (VideoStreamerHolder.isActive) {
            Toast.makeText(this, "Stop LIVE before you change the mode.", Toast.LENGTH_LONG).show()
            AppLog.i(TAG, "mode change refused: a stream is live")
            return
        }
        setModeCheckPending(true)
        AircraftFlightCheck.ask { flying ->
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setModeCheckPending(false)
                // ⚠ THE ANSWER CAN ARRIVE AFTER THIS SCREEN HAS GONE. Showing the password
                // dialog on a destroyed activity throws
                // BadTokenException and kills the application; changing the mode from a screen
                // the pilot has left is no better. A late answer is dropped.
                // ⚠ And NOT ONLY when the screen is finishing: a child
                // screen opened during the check stays on the stack, built for the OLD mode, and
                // the rebuild puts the new Pre-Flight on top of it. So the answer is used only
                // while this screen is in front, and the pilot is told when it was not.
                if (isFinishing || !lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    AppLog.i(TAG, "flying-state answer arrived after the pilot left the screen — " +
                        "the mode was not changed")
                    Toast.makeText(applicationContext, "You left Pre-Flight during the check. " +
                        "The mode was not changed.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                when (flying) {
                    false -> proceed()
                    true -> {
                        Toast.makeText(this, "The aircraft is flying. Land before you change " +
                            "the mode.", Toast.LENGTH_LONG).show()
                        AppLog.i(TAG, "mode change refused: aircraft flying")
                    }
                    null -> {
                        Toast.makeText(this, "The aircraft did not say it is on the ground. " +
                            "The mode was not changed.", Toast.LENGTH_LONG).show()
                        AppLog.i(TAG, "mode change refused: flying state unknown")
                    }
                }
            }
        }
    }

    /** True while [AircraftFlightCheck] is answering a mode change. See [refuseInFlightOrLive]. */
    private var modeCheckPending = false

    /** The Application Mode button stays disabled for the check, so a second tap cannot start a second one. */
    private fun setModeCheckPending(pending: Boolean) {
        modeCheckPending = pending
        findViewById<Button>(R.id.appModeButton)?.isEnabled = !pending
    }

    /** The child screens wait for the check: one opened now would be built for the old mode. */
    private fun refuseWhileModeCheckPending(): Boolean {
        if (!modeCheckPending) return false
        Toast.makeText(this, "Checking the aircraft for the mode change. Wait a moment.",
            Toast.LENGTH_SHORT).show()
        return true
    }

    /** The disclosure, then the password, then the one ordered transition. */
    private fun promptForModeChange(
        prefs: android.content.SharedPreferences,
        target: AppMode,
    ) {
        // Read BEFORE the change: the disclosure describes what is about to happen.
        val facts = AppModeTransition.facts(
            prefs, TakMissionManager.hasElevatedEnrollment(this), target,
            emergencyActive = TakManager.getInstance().isEmergencyBroadcastActive)
        // The field is ConfigLock's, not the flight screen's: the flight prompt's EditText lacks
        // IME_FLAG_NO_EXTRACT_UI, and on a landscape-locked screen the full-screen extract
        // keyboard would cover the dialog.
        val pw = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            hint = "Password"
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@TakConnectActivity, R.color.tp_text_primary))
            setHintTextColor(ContextCompat.getColor(this@TakConnectActivity, R.color.tp_text_hint))
            setBackgroundResource(R.drawable.bg_dialog_field)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val wrap = android.widget.FrameLayout(this).apply {
            val padH = (16 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(pw)
        }
        android.app.AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            // ⚠ From AppModeTransition.dialogTitle, which a test holds, so a literal cannot
            // escape its own template and show the pilot raw Kotlin.
            .setTitle(AppModeTransition.dialogTitle(target))
            .setMessage(AppModeTransition.describeAudienceChange(target, facts))
            .setView(wrap)
            .setPositiveButton("Change Mode") { _, _ ->
                // The entered text is never logged, right or wrong — the same rule as every other
                // credential in this application.
                if (pw.text.toString() != UNLOCK_PASSWORD) {
                    Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show()
                    AppLog.i(TAG, "mode change refused (wrong password)")
                    return@setPositiveButton
                }
                // The second check, at the moment of the change.
                refuseInFlightOrLive {
                    val changed = AppModeTransition.apply(prefs, target) {
                        TakAutoConnect.dialVideoChannelIfAllowed(applicationContext)
                    }
                    if (changed) rebuildWithoutSavedState()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * What section 8 says, GENERATED from the state — never typed. A fixed
     * "the live-video link goes only to the Elevated account's channel" is
     * false on an Enhanced controller with no Elevated account connected.
     */
    private fun paintAppModeSummary(prefs: android.content.SharedPreferences) {
        val mode = AppMode.of(prefs)
        val f = AppModeTransition.facts(prefs, TakMissionManager.hasElevatedEnrollment(this), mode)
        // ⚠ Every claim about the video link is conditioned on the ADVERTISEMENT: with it off,
        // nobody gets a link, whatever the accounts — including the state BASIC itself creates
        // when the CoT named Server 2.
        val advertised = f.advertNow != null
        findViewById<TextView>(R.id.appModeSummary).text = when (mode) {
            AppMode.ENHANCED ->
                "Elevated. Two TAK accounts, two video servers, Emergency Broadcast. " + when {
                    !advertised -> "No video address is advertised, so no channel gets a video link."
                    f.elevatedInUse -> "The live-video link goes only to the Elevated account's channel."
                    // Not "no Elevated account": it is set up, only switched off.
                    f.elevatedEnrolled -> "The Elevated account is switched off, so the live-video " +
                        "link goes to every active channel."
                    else -> "No Elevated account is set up, so the live-video link goes to every " +
                        "active channel."
                }
            AppMode.BASIC ->
                "Basic. One TAK account and one video server. Every active channel receives " +
                    "this aircraft" +
                    (if (advertised) " and its video link." else ". No video address is advertised.") +
                    " The locks need no password."
        }
    }

    /**
     * The three lines Pre-Flight keeps of the TAK configuration.
     *
     * ⚠ **GENERATED, NEVER TYPED.** Each line is built from the live state every time this
     * screen appears, so none of them can say something the application stopped doing — which
     * is the failure mode a hand-written summary has, when the only thing tying the words to
     * the truth is a comment asking a human to remember.
     *
     * What a pilot needs before a flight is WHO this aircraft is signed in as and WHO can see
     * it. Everything that changes those answers is one tap away in [TakServerActivity].
     */
    private fun paintTakSummary() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val tm = TakManager.getInstance()
        val statusLine = findViewById<TextView>(R.id.takSummaryStatus) ?: return
        val channelLine = findViewById<TextView>(R.id.takSummaryChannels)
        val elevatedLine = findViewById<TextView>(R.id.takSummaryElevated)

        val user = prefs.getString(KEY_USERNAME, "") ?: ""
        val callsign = prefs.getString(KEY_CALLSIGN, "") ?: ""
        when {
            tm.isConnected -> {
                statusLine.text = "Connected as \"$user\"" +
                    if (callsign.isNotEmpty()) " — the aircraft is \"$callsign\"." else "."
                statusLine.setTextColor(
                    ContextCompat.getColor(applicationContext, R.color.tp_state_go))
            }
            user.isEmpty() -> {
                statusLine.text = "No TAK server is set up. Touch Configure TAK Server."
                statusLine.setTextColor(
                    ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
            }
            else -> {
                // ⚠ "Not connected" and "not set up" are different answers and must look
                // different. An enrolled controller that cannot reach its server is a fault to
                // go and fix; an unconfigured one is a job not started.
                statusLine.text = "Not connected. Signed in as \"$user\"."
                statusLine.setTextColor(
                    ContextCompat.getColor(applicationContext, R.color.tp_state_unknown))
            }
        }

        // The channels come from the SERVER, so there is nothing to show until it answers.
        // Until then the line says that rather than showing an empty list, which would read as
        // "this aircraft reaches nobody".
        channelLine.text = if (tm.isConnected) "Reading the channels…" else ""
        if (tm.isConnected) {
            TakMissionManager.listChannels { chans ->
                val active = chans.filter { it.active }
                channelLine.text = when {
                    chans.isEmpty() -> "This server has no channels."
                    active.isEmpty() -> "No channel is active — nobody receives this aircraft."
                    else -> "Active: " + active.joinToString(", ") { TakMissionManager.channelLabel(it) }
                }
            }
        }

        val enrolled = TakMissionManager.hasElevatedEnrollment(this) &&
            !prefs.getBoolean("chb_logged_out", false)
        // ⚠ EVERY BRANCH IS CONDITIONED ON THE ADVERTISEMENT, as the Application Mode summary is. With it off no
        // channel gets a link, whatever the accounts, and the
        // two generated lines on this one screen must not disagree. From the same Facts as that summary.
        val advertised = AppModeTransition.facts(
            prefs, TakMissionManager.hasElevatedEnrollment(this), AppMode.of(prefs)).advertNow != null
        val noAdvert = "No video address is advertised."
        if (!AppMode.elevatedAccountAllowed(prefs)) {
            // ⚠ BASIC: ONE ACCOUNT, AND THE LINE SAYS WHAT THAT MEANS. Tested FIRST and never
            // keyed off the saved certificates: those survive the switch to Basic on purpose
            // (the mode is a mute, not a removal), so a summary that read them would promise a
            // split that AppModeTransition has already torn down.
            elevatedLine.text = "One TAK account — every active channel receives this aircraft" +
                if (advertised) " and its video." else ". $noAdvert"
        } else if (enrolled && !prefs.getBoolean(KEY_CHB_ENABLED, false)) {
            // ⚠ An enrolled account that is merely switched off is NOT "no Elevated account".
            // If that line were shown for both, the video half would be true and the
            // account half would not, and a pilot reading it would go looking to enroll one.
            elevatedLine.text = if (advertised) "Elevated account switched off — the video link " +
                "goes to every active channel." else "Elevated account switched off. $noAdvert"
        } else if (enrolled) {
            elevatedLine.text = "Elevated account: reading the channels…"
            TakMissionManager.listElevatedChannels(this) { chans ->
                val active = chans?.filter { it.active }.orEmpty()
                elevatedLine.text = when {
                    active.isEmpty() -> "Elevated account: no active channel — the video link " +
                        "reaches nobody."
                    advertised -> "Video link on: " +
                        active.joinToString(", ") { TakMissionManager.channelLabel(it) }
                    else -> "Elevated account on: " +
                        active.joinToString(", ") { TakMissionManager.channelLabel(it) } +
                        ". $noAdvert"
                }
            }
        } else {
            // No second account. Say what that MEANS rather than leaving a blank: with no
            // split, the video link rides the Standard connection and everybody in its
            // channels can play it.
            elevatedLine.text = if (advertised) "No Elevated account — the video link goes to " +
                "every active channel." else "No Elevated account. $noAdvert"
        }
    }

    /** Drone flight-limit fields — auto-saved as the pilot types (no submit action needed;
     *  these are just local prefs, applied to the aircraft on its next connect by
     *  FlightLimitsController, not sent anywhere from here). */
    private fun setupDroneSettings(prefs: android.content.SharedPreferences) {
        val maxAlt = findViewById<EditText>(R.id.limitMaxAltitude)
        val maxRadius = findViewById<EditText>(R.id.limitMaxRadius)
        val rthAlt = findViewById<EditText>(R.id.limitRthAltitude)

        maxAlt.setText(FlightLimitsController.savedMaxAltitudeFt(this))
        maxRadius.setText(FlightLimitsController.savedMaxRadiusFt(this))
        rthAlt.setText(FlightLimitsController.savedRthAltitudeFt(this))

        val limitsWarning = findViewById<TextView>(R.id.limitsWarning)

        /**
         * RTH ALTITUDE ABOVE MAX ALTITUDE IS A REAL CONFIGURATION, AND A BAD ONE.
         *
         * Each field is validated only against the aircraft's own accepted range (20-500m), so
         * both can be individually legal while contradicting each other. The aircraft is then
         * told to climb to an RTH height its own ceiling forbids, and what it does about that is
         * firmware's decision, not the pilot's — it may clamp, or it may refuse the climb and
         * come home low, straight through whatever the pilot set a ceiling to stay above.
         *
         * A warning rather than a block: the pilot may be mid-edit, and refusing input on the way
         * to a valid pair is its own hazard. It says what will happen and lets them fix it.
         */
        fun checkLimits() {
            val maxFt = maxAlt.text.toString().trim().toDoubleOrNull()
            val rthFt = rthAlt.text.toString().trim().toDoubleOrNull()
            if (maxFt != null && rthFt != null && rthFt > maxFt) {
                limitsWarning.text = "RTH altitude (${rthFt.toInt()} ft) is above max altitude " +
                    "(${maxFt.toInt()} ft). The aircraft cannot climb to its return height."
                limitsWarning.visibility = View.VISIBLE
            } else {
                limitsWarning.visibility = View.GONE
            }
        }

        val save = {
            FlightLimitsController.save(
                this, maxAlt.text.toString(), maxRadius.text.toString(), rthAlt.text.toString(),
            )
            checkLimits()
        }
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = save()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(maxAlt, maxRadius, rthAlt).forEach { it.addTextChangedListener(watcher) }
        // Run once on open: a contradiction saved by an earlier build must surface without the
        // pilot having to touch a field.
        checkLimits()

        setupBattery()
        setupStickMode()
        setupControlResponse()
        setupFailsafe()
        setupAvoidance()
        setupApplyButton()
    }

    /** Battery warning/critical levels. Saved as typed, like the limits above; pushed to the
     *  aircraft only on Apply, because these decide when it comes home on its own. */
    private fun setupBattery() {
        val low = findViewById<EditText>(R.id.limitLowBattery)
        val crit = findViewById<EditText>(R.id.limitCriticalBattery)
        low.setText(FlightLimitsController.savedLowBatteryPct(this))
        crit.setText(FlightLimitsController.savedCriticalBatteryPct(this))
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                // The read-back writes into these fields when the aircraft refuses the write.
                // Without this guard that write would be saved back as a pilot edit.
                if (suppressBatterySave) return
                FlightLimitsController.saveBattery(
                    this@TakConnectActivity, low.text.toString(), crit.text.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(low, crit).forEach { it.addTextChangedListener(watcher) }
        renderLimitsReadBack()
    }

    /** Shows what the AIRCRAFT reports, not what was typed. Blank until a read-back has landed —
     *  "unknown" and "what you asked for" must not look the same. */
    private fun renderLimitsReadBack() {
        val f = FlightLimitsController
        // Metres in, feet out: the fields above take feet, so a read-back in metres would make
        // the pilot convert to check their own entry.
        fun ft(m: Int?) = m?.let { "${Math.round(it * 3.28084)} ft" } ?: "—"
        val parts = listOfNotNull(
            f.aircraftMaxAltM?.let { "max alt ${ft(it)}" },
            f.aircraftMaxRadiusM?.let { "max dist ${ft(it)}" },
            f.aircraftRthAltM?.let { "RTH ${ft(it)}" },
            f.aircraftWarningPct?.let { "warning $it%" },
            f.aircraftCriticalPct?.let { "critical $it%" },
            f.aircraftFailsafe?.let { "signal loss ${it.name.replace('_', ' ').lowercase()}" },
        )
        findViewById<TextView>(R.id.limitReadBackStatus).text =
            if (parts.isEmpty()) "" else "Aircraft reports: " + parts.joinToString(" · ")

        // Once the aircraft has refused them, the two battery fields stop pretending. They are
        // left in place rather than removed: the getters still work, so the levels the aircraft
        // holds are worth showing, and an airframe that DOES accept them should get working
        // fields. A field that takes a number and silently discards it is the worst of the three.
        if (f.batteryThresholdsRefused) {
            val low = findViewById<EditText>(R.id.limitLowBattery)
            val crit = findViewById<EditText>(R.id.limitCriticalBattery)
            suppressBatterySave = true
            try {
                f.aircraftWarningPct?.let { low.setText(it.toString()) }
                f.aircraftCriticalPct?.let { crit.setText(it.toString()) }
            } finally {
                suppressBatterySave = false
            }
            for (field in listOf(low, crit)) {
                field.isEnabled = false
                field.isFocusable = false
                field.isFocusableInTouchMode = false
            }
            findViewById<TextView>(R.id.limitBatteryNotice).apply {
                visibility = View.VISIBLE
                text = "This aircraft sets its own battery levels. The app cannot change them. " +
                    "It warns at ${f.aircraftWarningPct ?: "—"}% and lands at " +
                    "${f.aircraftCriticalPct ?: "—"}%."
            }
        }
    }

    /**
     * Control response. Applies on selection rather than on the Apply button: it is a camera-feel
     * setting the pilot wants to try, not a flight limit, and waiting for Apply to feel it would
     * make it hard to compare the two.
     */
    private fun setupControlResponse() {
        val group = findViewById<RadioGroup>(R.id.controlResponseGroup)
        group.check(
            if (ControlResponse.saved(this) == ControlResponse.Mode.PRECISION)
                R.id.controlResponsePrecision else R.id.controlResponseNormal
        )
        renderControlResponse()
        group.setOnCheckedChangeListener { _, id ->
            val mode = if (id == R.id.controlResponsePrecision) ControlResponse.Mode.PRECISION
                       else ControlResponse.Mode.NORMAL
            ControlResponse.save(this, mode)
            ControlResponse.apply(this) { runOnUiThread { renderControlResponse() } }
        }
    }

    /** What the GIMBAL reports holding. Blank until a read-back lands. */
    private fun renderControlResponse() {
        val v = ControlResponse.aircraftPitchSpeed
        findViewById<TextView>(R.id.controlResponseStatus).text =
            if (v == null) "" else "Aircraft reports: gimbal speed $v"
    }

    private fun setupStickMode() {
        val group = findViewById<RadioGroup>(R.id.stickModeGroup)
        fun idFor(m: FlightLimitsController.StickMode) = when (m) {
            FlightLimitsController.StickMode.MODE_1 -> R.id.stickMode1
            FlightLimitsController.StickMode.MODE_2 -> R.id.stickMode2
            FlightLimitsController.StickMode.MODE_3 -> R.id.stickMode3
        }
        group.check(idFor(FlightLimitsController.savedStickMode(this)))
        group.setOnCheckedChangeListener { _, checkedId ->
            val choice = when (checkedId) {
                R.id.stickMode1 -> FlightLimitsController.StickMode.MODE_1
                R.id.stickMode3 -> FlightLimitsController.StickMode.MODE_3
                else -> FlightLimitsController.StickMode.MODE_2
            }
            FlightLimitsController.saveStickMode(this, choice)
            AppLog.i(TAG, "stick mode selected: ${choice.label} (sent on Apply)")
        }
    }

    /**
     * Pushes everything in this section to the aircraft now, and reads it back.
     *
     * The button disables for the duration. Without that a pilot can queue a second push on top
     * of a running one, and the SDK's callbacks then interleave in an order nobody can reason
     * about — for settings that decide when the aircraft flies itself home.
     */
    private fun setupApplyButton() {
        val button = findViewById<Button>(R.id.limitApplyButton)
        val bar = findViewById<android.widget.ProgressBar>(R.id.limitApplyProgress)
        val status = findViewById<TextView>(R.id.limitApplyStatus)
        button.setOnClickListener {
            AppLog.v(TAG, "tap: Apply to Aircraft")
            button.isEnabled = false
            bar.visibility = View.VISIBLE
            bar.progress = 0
            status.setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_tertiary))
            FlightLimitsController.applyToAircraft(
                this,
                onProgress = { done, total, name ->
                    bar.max = total
                    bar.progress = done
                    status.text = "Applying ($done/$total): $name …"
                },
                onDone = { ok, summary ->
                    button.isEnabled = true
                    bar.visibility = View.GONE
                    status.text = summary
                    status.setTextColor(ContextCompat.getColor(applicationContext,
                        // The apply finished and something did not take. That is an answer,
                        // so it is caution — unknown is for "the aircraft never replied".
                        if (ok) R.color.tp_state_go else R.color.tp_state_caution))
                    renderLimitsReadBack()
                },
            )
        }
    }


    /** Codec and TCP transport are part of WHAT the stream is — the wrong codec breaks playback
     *  outright for some viewers, so they lock with the server fields. The quality profile stays
     *  live; it is an in-flight choice about bandwidth, not part of what the stream IS. The two
     *  codec RadioButtons are listed individually because disabling a RadioGroup does not
     *  disable its children. */
    /**
     * ⚠ EMPTY ON PURPOSE. The video configuration fields moved to [VideoServersActivity], so
     * there is nothing on THIS screen for the generic lock to disable. The lock still does its
     * two jobs: `afterChange` stops the active-server toggle taking touches, and the servers
     * screen reads `video_config_locked` when it opens.
     */
    private val videoLockedFields = emptyList<Int>()
    // ⚠ videoServer1/videoServer2 are NOT in that list, and this is deliberate. applyLock dims
    // to 45%, which on a radio button greys the DOT as well as the label — and the dot is the
    // one thing a pilot must be able to read while locked: which server the video is going to.
    // The toggle is locked by lockVideoServerToggle instead: full contrast, no touch response.

    /**
     * Per-section locks over settings that are painful to get wrong and rarely need changing.
     *
     * Unlocking asks for a password; locking does not. The asymmetry is deliberate — locking is
     * the safe direction, and gating it would only train people to dismiss dialogs.
     */
    /** THE TWO BATTERY PERCENTAGES ONLY. The numeric limit fields stay editable: editing one
     *  only saves it locally, and nothing reaches the aircraft without Apply or a connect.
     *
     *  ⚠ THE STICK MODE AND THE SIGNAL-LOSS BEHAVIOUR ARE NOT ON THIS LIST. The checkbox says
     *  "Lock battery levels", and it once locked three unrelated groups — a control that does
     *  more than its label says is a control a pilot cannot reason about. This lock's scope is
     *  the battery levels, exactly the two percentages.
     *
     *  ⚠ APPLY IS NOT ON THIS LIST. The lock guards what the configuration IS, not what you do
     *  with it. A locked, known-good configuration must still be pushable to a freshly connected
     *  aircraft — needing to re-send it is exactly when a pilot must not be fighting a lock. */
    private val aircraftLockedFields = listOf(
        R.id.limitLowBattery, R.id.limitCriticalBattery,
    )

    // ======================= 7. SPEAKER MESSAGES =======================
    //
    // Recording belongs on the ground, not over live video, so the flight panel carries only the
    // three fire buttons and this screen carries everything that makes them.
    //
    // ⚠ NO CONFIG LOCK ON THIS SECTION, deliberately. The three configuration locks guard aircraft
    // CONFIGURATION; a message is CONTENT, and locking it would stop a pilot re-recording in
    // the field, which is the point of the feature. Delete asks for confirmation instead — the
    // right speed bump for the one action that cannot be undone.

    private val speakerRowIds = listOf(
        // name, status, record, play, delete
        arrayOf(R.id.speakerMsg1Name, R.id.speakerMsg1Status, R.id.speakerMsg1Record,
            R.id.speakerMsg1Play, R.id.speakerMsg1Delete, R.id.speakerMsg1Export,
            R.id.speakerMsg1Import),
        arrayOf(R.id.speakerMsg2Name, R.id.speakerMsg2Status, R.id.speakerMsg2Record,
            R.id.speakerMsg2Play, R.id.speakerMsg2Delete, R.id.speakerMsg2Export,
            R.id.speakerMsg2Import),
        arrayOf(R.id.speakerMsg3Name, R.id.speakerMsg3Status, R.id.speakerMsg3Record,
            R.id.speakerMsg3Play, R.id.speakerMsg3Delete, R.id.speakerMsg3Export,
            R.id.speakerMsg3Import),
    )

    private fun setupSpeakerMessagesSection() {
        for ((i, ids) in speakerRowIds.withIndex()) {
            val slot = i + 1
            val name = findViewById<android.widget.EditText>(ids[0])
            name.setText(SpeakerMessages.label(this, slot))
            // Saved as it is typed, like the limit fields. Upper-cased and capped by the store,
            // because the label is a pill face and the pill is 78dp wide.
            name.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) {
                    SpeakerMessages.saveLabel(this@TakConnectActivity, slot, e?.toString() ?: "")
                }

                override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            })

            findViewById<android.widget.Button>(ids[2]).setOnClickListener {
                onRecordTapped(slot)
            }
            findViewById<android.widget.Button>(ids[3]).setOnClickListener {
                onSendTapped(slot)
            }
            findViewById<android.widget.Button>(ids[4]).setOnClickListener {
                confirmDeleteMessage(slot)
            }
            findViewById<TextView>(ids[5]).setOnClickListener { onExportTapped(slot) }
            findViewById<TextView>(ids[6]).setOnClickListener { onImportTapped(slot) }
        }
        speakerRecorderCb = { runOnUiThread { renderSpeakerMessages() } }
        speakerBroadcastCb = { runOnUiThread { renderSpeakerMessages() } }
        SpeakerRecorder.onChanged = speakerRecorderCb
        SpeakerBroadcast.onChanged = speakerBroadcastCb
        renderSpeakerMessages()
    }

    private fun renderSpeakerMessages() {
        for ((i, ids) in speakerRowIds.withIndex()) {
            val slot = i + 1
            val status = findViewById<TextView>(ids[1]) ?: continue
            val record = findViewById<android.widget.Button>(ids[2])
            val play = findViewById<android.widget.Button>(ids[3])
            val delete = findViewById<android.widget.Button>(ids[4])
            val recordingThis = SpeakerRecorder.recording && SpeakerRecorder.slot == slot
            val message = SpeakerMessages.saved(this, slot)

            // Clears any red refusal text from a previous import attempt.
            status.setTextColor(androidx.core.content.ContextCompat.getColor(
                this, R.color.tp_text_tertiary))
            status.text = when {
                recordingThis -> "Recording…  %.1f s".format(SpeakerRecorder.elapsedMs / 1000.0)
                message == null -> "Not recorded."
                else -> "%.1f s · %d KB".format(
                    message.durationMs / 1000.0, message.bytes / 1024)
            }
            // A changing label is right HERE and wrong on the flight screen: this is an action in
            // progress, not a state pill, and the rule against changing labels is about the latter.
            record.text = if (recordingThis) "Stop" else if (message == null) "Record" else "Re-record"
            record.isEnabled = !SpeakerRecorder.recording || recordingThis
            play.isEnabled = message != null && !SpeakerRecorder.recording
            delete.isEnabled = message != null && !SpeakerRecorder.recording
        }
    }

    private fun speakerStatus(text: String) {
        findViewById<TextView>(R.id.speakerMsgStatus)?.text = text
    }

    private fun onRecordTapped(slot: Int) {
        if (SpeakerRecorder.recording) {
            SpeakerRecorder.stop { durationMs, bytes, ok ->
                val take = SpeakerRecorder.lastTake()
                val stored = ok && take != null &&
                    SpeakerMessages.store(this, slot, take, durationMs)
                runOnUiThread {
                    speakerStatus(
                        if (stored) "Message $slot recorded, %.1f s.".format(durationMs / 1000.0)
                        else "That recording was too short to keep.")
                    renderSpeakerMessages()
                }
            }
            return
        }
        // ⚠ A DENIED RECORD_AUDIO GIVES SILENCE WITH NO ERROR — the pilot would only find out
        // when the message they thought they made turns out to be nothing. Ask before recording,
        // and refuse this press rather than raising a dialog over one.
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC_PREFLIGHT)
            speakerStatus("Allow the microphone, then touch Record again.")
            return
        }
        SpeakerRecorder.start(this, slot) { ok, why ->
            runOnUiThread {
                speakerStatus(if (ok) "Speak now. Touch Stop when you are finished."
                              else "Could not record: ${why ?: "the microphone did not open"}")
                renderSpeakerMessages()
            }
        }
    }

    private fun onSendTapped(slot: Int) {
        speakerStatus("Sending message $slot to the aircraft…")
        SpeakerBroadcast.play(this, slot) { ok, why ->
            runOnUiThread {
                speakerStatus(if (ok) "Playing message $slot on the aircraft."
                              else "It did not go out: ${why ?: "refused"}")
            }
        }
    }

    /**
     * The slot a file picker is working on.
     *
     * ⚠ PERSISTED, NOT HELD IN MEMORY. The Storage Access Framework hands back a Uri and nothing
     * else — no extras survive the round trip — and this Activity can be destroyed while the
     * picker is in front of it. A slot held only in a field comes back null and the import lands
     * in the wrong place, or nowhere.
     */
    private var pendingMessageSlot: Int
        get() = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("msg_pending_slot", 0)
        set(v) { getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("msg_pending_slot", v).apply() }

    private fun onExportTapped(slot: Int) {
        if (SpeakerMessages.saved(this, slot) == null) {
            speakerStatus("Record message $slot before you export it.")
            return
        }
        pendingMessageSlot = slot
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, SpeakerMessages.bundleName(this@TakConnectActivity, slot))
            }, REQUEST_CODE_MSG_EXPORT)
    }

    private fun onImportTapped(slot: Int) {
        pendingMessageSlot = slot
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, REQUEST_CODE_MSG_IMPORT)
    }

    private fun onMessageExportPicked(uri: Uri) {
        val slot = pendingMessageSlot
        if (slot !in SpeakerMessages.SLOTS) return
        speakerStatus("Exporting message $slot…")
        // Off the main thread — small files, but the pattern is the pattern.
        Thread {
            // ⚠ NOT AN ELVIS. exportTo returns NULL FOR SUCCESS, so `?.use { … } ?: "failed"`
            // turns every success into a failure — the stream being null and the export
            // succeeding both produce null and become indistinguishable. That exact mistake made
            // a working import report a failure and skip its own success path.
            val stream = try { contentResolver.openOutputStream(uri) } catch (t: Throwable) { null }
            val why = if (stream == null) "Could not open that location"
                      else stream.use { SpeakerMessages.exportTo(this, slot, it) }
            runOnUiThread {
                speakerStatus(why ?: "Message $slot exported. Copy it to another controller " +
                    "and import it there.")
            }
        }.start()
    }

    private fun onMessageImportPicked(uri: Uri) {
        val slot = pendingMessageSlot
        if (slot !in SpeakerMessages.SLOTS) return
        speakerStatus("Importing into message $slot…")
        Thread {
            // See the note in onMessageExportPicked: null means SUCCESS here, not "no stream".
            val stream = try { contentResolver.openInputStream(uri) } catch (t: Throwable) { null }
            val why = if (stream == null) "Could not open that file"
                      else stream.use { SpeakerMessages.importFrom(this, slot, it) }
            runOnUiThread {
                if (why == null) {
                    // The label came from the bundle — put it in the field the pilot sees.
                    findViewById<android.widget.EditText>(speakerRowIds[slot - 1][0])
                        ?.setText(SpeakerMessages.label(this, slot))
                    speakerStatus("Message $slot imported.")
                } else {
                    // ⚠ THE REFUSAL GOES WHERE THE PILOT IS LOOKING. It used to go only to
                    // the one status line at the BOTTOM of section 7 — below slot 3, on a
                    // scrolling form — so importing into slot 1 put the reason two rows off the
                    // screen and it read as a silent failure. A refusal
                    // the pilot cannot see is the same as no refusal at all.
                    findViewById<TextView>(speakerRowIds[slot - 1][1])?.apply {
                        text = why
                        setTextColor(androidx.core.content.ContextCompat.getColor(
                            this@TakConnectActivity, R.color.tp_btn_danger_dialog))
                    }
                    speakerStatus(why)
                    // And a dialog, because an import is a deliberate act with a file the
                    // pilot chose: they are entitled to be told plainly that it was rejected.
                    androidx.appcompat.app.AlertDialog.Builder(this, R.style.TakDialogTheme)
                        .setTitle("Message $slot not imported")
                        .setMessage("$why\n\nOnly a .tp2msg file exported from TAKPilot2 can " +
                            "be imported. Audio from anywhere else is not in the form the " +
                            "speaker accepts.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                renderSpeakerMessages()
            }
        }.start()
    }

    private fun confirmDeleteMessage(slot: Int) {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Delete message $slot?")
            .setMessage("The recording is removed from this controller. There is no other copy " +
                "and this cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                SpeakerMessages.delete(this, slot)
                speakerStatus("Message $slot deleted.")
                renderSpeakerMessages()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * ⚠ ONE IMPLEMENTATION, IN [ConfigLock]. The two private methods that
     * used to live here moved there when [TakServerActivity] took the TAK configuration and
     * needed the same lock. Both of its rules — locked is not hidden, and a control whose state
     * IS its tick must not be dimmed — came from real faults, and a second copy is how one
     * screen keeps them while the other quietly stops.
     */
    private fun setupConfigLocks() {
        ConfigLock.install(
            this, R.id.limitBatteryLock, KEY_AIRCRAFT_LOCKED, aircraftLockedFields,
            "Unlock battery levels?",
            "These decide when the aircraft warns you and when it lands on its own. A wrong " +
                "value can force a landing away from the pilot.",
        )
        // ⚠ The TAK lock is NOT here any more — it went with the fields it guards, to
        // TakServerActivity. Nothing of the TAK configuration stayed on
        // this screen, so there is nothing left for it to protect. The video lock DID stay,
        // because the active-server toggle it also guards stayed.
        ConfigLock.install(
            this, R.id.videoLockConfig, KEY_VIDEO_LOCKED, videoLockedFields,
            "Unlock video server settings?",
            "These fields are locked so a working stream configuration is not changed by " +
                "accident. Editing them can stop your team seeing the video.",
            // The toggle is a radio button pair built in the layout, but it must not be dimmed
            // — see the note on videoLockedFields.
            afterChange = { lockVideoServerToggle(it) },
        )
    }


    /**
     * Obstacle-avoidance toggles.
     *
     * Different in kind from the limits above: those are offered to the aircraft, these are
     * ENFORCED on it at every connect (see [DjiObstacleState.applyAtConnect]). The status line
     * therefore reports what the AIRCRAFT currently says, not what the checkbox says — the whole
     * hazard being addressed is a pilot who believes avoidance is on because a box is ticked.
     */
    /**
     * The avoidance section — A MIRROR, NOT A CONTROL.
     *
     * It had three check boxes and wrote a selection to the aircraft at every connect. All of
     * that is gone. The reasons are in [DjiObstacleState]'s own note; the short version is that
     * the one API it used is REJECTED by this airframe, which made the write blind, and that
     * these settings live in the AIRCRAFT with DJI Pilot 2 already on this controller to set
     * them properly.
     *
     * ⚠ WHAT MUST NOT BE LOST IS THE READING. The incident this pattern came from was
     * not really about enforcement — it was about a screen showing a protection the aircraft
     * did not have. So the state still appears here, sourced from the aircraft every connect,
     * and an unread value says "not available" rather than quietly looking like "off".
     */
    private fun setupAvoidance() {
        renderAvoidance()
        // The reads land on DJI's callback thread and arrive after this screen is built.
        DjiObstacleState.addChangeListener(avoidanceListener)
    }

    /** Held as a field so it can be REMOVED. A lambda created at the call site is a new
     *  object every time and could never be unregistered — which is how a listener list leaks
     *  a dead Activity. */
    private val avoidanceListener: () -> Unit = { runOnUiThread { renderAvoidance() } }

    private fun renderAvoidance() {
        findViewById<TextView>(R.id.avoidStatus)?.text = avoidanceStatusText()
    }

    /**
     * What the AIRCRAFT reports, in the pilot's terms.
     *
     * ⚠ THREE STATES, NEVER TWO. "on", "OFF" and "not available" are different facts. The old
     * version rendered the third as "not read yet", which reads as "it is coming" — and for
     * landing protection it could never become anything else, because nothing ever read it.
     */
    private fun avoidanceStatusText(): String {
        fun s(v: Boolean?) = when (v) {
            true -> "on"
            false -> "OFF"
            null -> "not available"
        }
        val cushion = when (DjiObstacleState.cushionedLanding) {
            true -> "The aircraft should cushion a descent."
            false -> "⚠ The aircraft will NOT cushion a descent."
            null -> "Whether it will cushion a descent is not known."
        }
        val braking = DjiObstacleState.downwardBrakingM?.let {
            " Downward braking distance %.1f m.".format(it)
        } ?: ""
        return "The aircraft reports: avoidance ${s(DjiObstacleState.overallAvoidance)}, " +
            "downward ${s(DjiObstacleState.downwardAvoidance)}, " +
            "vision positioning ${s(DjiObstacleState.visionPositioning)}, " +
            "precision landing ${s(DjiObstacleState.precisionLanding)}, " +
            "RTH avoidance ${s(DjiObstacleState.rthAvoidance)}.$braking $cushion"
    }

    private fun setupFailsafe() {
        val group = findViewById<RadioGroup>(R.id.limitFailsafeGroup)
        val status = findViewById<TextView>(R.id.limitFailsafeStatus)

        val idFor = { f: FlightLimitsController.Failsafe ->
            when (f) {
                FlightLimitsController.Failsafe.GO_HOME -> R.id.failsafeGoHome
                FlightLimitsController.Failsafe.HOVER -> R.id.failsafeHover
                FlightLimitsController.Failsafe.LAND -> R.id.failsafeLand
            }
        }
        group.check(idFor(FlightLimitsController.savedFailsafe(this)))

        status.text = "Sent to the aircraft the next time it connects. " +
            "Check the Debug Log for \"signal-loss behavior is now\" to confirm it took."

        group.setOnCheckedChangeListener { _, checkedId ->
            val choice = when (checkedId) {
                R.id.failsafeHover -> FlightLimitsController.Failsafe.HOVER
                R.id.failsafeLand -> FlightLimitsController.Failsafe.LAND
                else -> FlightLimitsController.Failsafe.GO_HOME
            }
            AppLog.i("TP2Limits", "signal-loss failsafe set to '${choice.label}' (applies on next connect)")
            FlightLimitsController.saveFailsafe(this, choice)
        }
    }

    /** Mini-map style choice — explicit "Save" button since a URL field benefits from not
     *  saving mid-edit on every keystroke, unlike the drone-limit fields above. */
    private fun setupMapDisplay() {
        val group = findViewById<RadioGroup>(R.id.mapStyleGroup)
        val customUrl = findViewById<EditText>(R.id.mapCustomUrl)

        when (MaplibreStyle.savedStyleChoice(this)) {
            "street" -> group.check(R.id.mapStyleStreet)
            "custom" -> group.check(R.id.mapStyleCustom)
            else -> group.check(R.id.mapStyleHybrid)
        }
        customUrl.setText(MaplibreStyle.savedCustomUrl(this))

        findViewById<Button>(R.id.mapDisplaySaveButton).setOnClickListener {
            val choice = when (group.checkedRadioButtonId) {
                R.id.mapStyleStreet -> "street"
                R.id.mapStyleCustom -> "custom"
                else -> "hybrid"
            }
            // Validated HERE, while the pilot is still looking at the field. An http:// URL
            // or one without the {z}/{x}/{y} placeholders used to save happily and then show as
            // a blank map on the flight screen, with nothing tying the dead map back to what was
            // typed two screens earlier.
            if (choice == "custom") {
                val problem = MaplibreStyle.validateCustomUrl(customUrl.text.toString())
                if (problem != null) {
                    Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }
            MaplibreStyle.saveStyleChoice(this, choice, customUrl.text.toString())
            Toast.makeText(this, "Map display saved", Toast.LENGTH_SHORT).show()
        }
    }

    /** DTED elevation-region management — upload a region .zip via the system document picker
     *  (any file; DTED extensions aren't a registered MIME type so we don't filter by type),
     *  list imported regions (one row each — never individual tiles, see DtedStore/TerrainDatabase),
     *  allow deleting a whole region. */
    private fun setupDtedSection() {
        findViewById<Button>(R.id.dtedUploadButton).setOnClickListener {
            AppLog.v(TAG, "tap: Import Region")
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQUEST_CODE_DTED_PICK)
        }
        findViewById<Button>(R.id.dtedCleanButton).setOnClickListener {
            AppLog.v(TAG, "tap: Clean Unused Tiles")
            val removed = DtedStore.cleanUnreferencedTiles(this)
            Toast.makeText(this, "Removed $removed unreferenced tile file(s)", Toast.LENGTH_SHORT).show()
        }
        renderDtedRegions()
    }

    // ---- 6. FAA Airspace Ceilings (UASFM) ----

    /** Download UASFM ceilings for an area. Deliberately a manual, explicit action on wifi
     *  rather than anything automatic in flight: the flight screen must never depend on having
     *  a network, and a silent background fetch would be exactly the wrong thing to discover
     *  had failed while airborne. */
    private fun setupUasfmSection() {
        val latField = findViewById<EditText>(R.id.uasfmLat)
        val lonField = findViewById<EditText>(R.id.uasfmLon)
        val radiusField = findViewById<EditText>(R.id.uasfmRadius)
        val status = findViewById<TextView>(R.id.uasfmStatus)
        val downloadBtn = findViewById<Button>(R.id.uasfmDownloadButton)
        val checkBtn = findViewById<Button>(R.id.uasfmCheckButton)

        radiusField.setText("50")
        renderUasfmStatus()

        /** Reads the three fields, or null (with a toast) if they don't make sense. */
        fun readBbox(): UasfmStore.Bbox? {
            val lat = latField.text.toString().trim().toDoubleOrNull()
            val lon = lonField.text.toString().trim().toDoubleOrNull()
            val radius = radiusField.text.toString().trim().toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                Toast.makeText(this, "Enter a valid centre latitude and longitude", Toast.LENGTH_SHORT).show()
                return null
            }
            if (radius == null || radius <= 0 || radius > 500) {
                Toast.makeText(this, "Enter a radius between 1 and 500 miles", Toast.LENGTH_SHORT).show()
                return null
            }
            return UasfmStore.bboxAround(lat, lon, radius)
        }

        findViewById<Button>(R.id.uasfmUseLocationButton).setOnClickListener {
            AppLog.v(TAG, "tap: UASFM Use My Location")
            val loc = lastKnownPhoneLocation()
            if (loc == null) {
                Toast.makeText(this, "No phone GPS fix available", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            latField.setText("%.4f".format(Locale.US, loc.first))
            lonField.setText("%.4f".format(Locale.US, loc.second))
        }

        checkBtn.setOnClickListener {
            val bbox = readBbox() ?: return@setOnClickListener
            AppLog.v(TAG, "tap: UASFM Check Size")
            checkBtn.isEnabled = false
            status.text = "Checking…"
            UasfmStore.countAsync(bbox) { result ->
                checkBtn.isEnabled = true
                // Clears any red refusal text from a previous import attempt.
            status.setTextColor(androidx.core.content.ContextCompat.getColor(
                this, R.color.tp_text_tertiary))
            status.text = when {
                    result.error != null -> "Couldn't reach the FAA service: ${result.error}"
                    result.count == 0 ->
                        "No facility-map cells in that area — it's likely all uncontrolled " +
                            "airspace, where the Part 107 400 ft limit applies."
                    else -> "${result.count} cell(s) in that area. Tap Download to store them."
                }
            }
        }

        downloadBtn.setOnClickListener {
            val bbox = readBbox() ?: return@setOnClickListener
            val label = "%.3f, %.3f  ·  %s mi".format(
                latField.text.toString().trim().toDoubleOrNull() ?: 0.0,
                lonField.text.toString().trim().toDoubleOrNull() ?: 0.0,
                radiusField.text.toString().trim(),
            )
            AppLog.i(TAG, "UASFM download starting for $label")
            downloadBtn.isEnabled = false
            status.text = "Downloading…"
            UasfmStore.downloadAsync(
                context = this,
                bbox = bbox,
                areaLabel = label,
                onProgress = { count -> status.text = "Downloading… $count cell(s)" },
                onDone = { result ->
                    downloadBtn.isEnabled = true
                    if (result.error != null) {
                        AppLog.w(TAG, "UASFM download failed: ${result.error}")
                        status.text = result.error
                    } else {
                        // Surface off-grid skips rather than burying them: a non-zero count
                        // means the FAA moved off the 1/120 degree grid this design assumes,
                        // and the pilot would otherwise have coverage holes with no hint why.
                        val warn = if (result.offGridSkipped > 0)
                            "\n⚠ ${result.offGridSkipped} cell(s) skipped — unexpected grid, report this."
                        else ""
                        renderUasfmStatus(extra = warn)
                        Toast.makeText(this, "FAA ceilings downloaded", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        findViewById<Button>(R.id.uasfmClearButton).setOnClickListener {
            AppLog.v(TAG, "tap: UASFM Clear Data")
            UasfmStore.clear(this)
            renderUasfmStatus()
            Toast.makeText(this, "FAA ceiling data cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderUasfmStatus(extra: String = "") {
        val status = findViewById<TextView>(R.id.uasfmStatus)
        val meta = UasfmStore.meta(this)
        status.text = if (meta == null) {
            "No FAA ceiling data downloaded — the flight HUD will show the Part 107 400 ft default."
        } else {
            "${meta.cellCount} cell(s) for ${meta.areaLabel}\n" +
                "Downloaded ${dtedDateFormat.format(java.util.Date(meta.downloadedAtMs))}  ·  " +
                "FAA effective ${meta.effectiveLabel}$extra"
        }
    }

    /**
     * Most recent GPS/network fix from the phone, or null. The RC-N1 has no GPS of its own, so
     * the phone's position IS the controller's position.
     *
     * Goes through [OperatorLocation] rather than calling `getLastKnownLocation` here, because
     * that method reads a CACHE that nothing fills unless some app has asked for position
     * updates. On a phone dedicated to flying, nothing has, so it returned null for ever — which
     * reads as a permission fault when it is not one. [OperatorLocation.start] issues the real
     * `requestLocationUpdates` that fills it, and applies an age gate so a fix from days ago at
     * some other location is not offered up as "here".
     */
    private fun lastKnownPhoneLocation(): Pair<Double, Double>? {
        OperatorLocation.start(this)
        val loc = OperatorLocation.latest ?: return null
        return loc.latitude to loc.longitude
    }

    private val dtedDateFormat = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)

    /** One compact row per imported region — a name/date/file-count/size summary plus a
     *  delete button, never a per-tile listing (that's what used to run the screen on for a
     *  long, mostly-empty scroll with a full multi-hundred-tile region). */
    private fun renderDtedRegions() {
        val container = findViewById<LinearLayout>(R.id.dtedFileList)
        val status = findViewById<TextView>(R.id.dtedStatus)
        container.orientation = LinearLayout.VERTICAL
        container.removeAllViews()
        val regions = DtedStore.listRegions(this)
        status.text = if (regions.isEmpty()) "No terrain regions imported."
            else "${regions.size} region(s) imported."
        for (region in regions) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_dialog))
                setPadding(12, 10, 12, 10)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 6 }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = region.name
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            info.addView(TextView(this).apply {
                val mb = region.totalBytes / 1024.0 / 1024.0
                val sizeStr = if (mb >= 1024) "%.1f GB".format(Locale.US, mb / 1024.0)
                    else "%.0f MB".format(Locale.US, mb)
                text = "Imported ${dtedDateFormat.format(java.util.Date(region.importedAtMs))} · " +
                    "${region.fileCount} file(s) · $sizeStr"
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_tertiary))
                textSize = 12f
            })
            row.addView(info)
            row.addView(TextView(this).apply {
                text = "Delete"
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_btn_danger_dialog))
                textSize = 13f
                setPadding(20, 8, 4, 8)
                setOnClickListener {
                    AppLog.v(TAG, "tap: delete DTED region ${region.name} (#${region.id})")
                    DtedStore.deleteRegion(this@TakConnectActivity, region)
                    renderDtedRegions()
                }
            })
            container.addView(row)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        // ⚠ THIS WAS `if (requestCode != REQUEST_CODE_DTED_PICK) return`, and adding a second
        // picker to that shape would have dropped its result on the floor with no error at all —
        // the silent-failure class this application keeps finding. Every request code is handled here.
        when (requestCode) {
            REQUEST_CODE_MSG_EXPORT -> { onMessageExportPicked(uri); return }
            REQUEST_CODE_MSG_IMPORT -> { onMessageImportPicked(uri); return }
            REQUEST_CODE_DTED_PICK -> Unit
            else -> return
        }
        val name = queryDisplayName(uri) ?: "Region-${System.currentTimeMillis()}"
        val status = findViewById<TextView>(R.id.dtedStatus)
        val importButton = findViewById<Button>(R.id.dtedUploadButton)
        // Off the main thread. A region zip is tens to hundreds of megabytes to decompress
        // and write — an ANR every time, on a screen that also showed no sign of working.
        importButton?.isEnabled = false
        status.text = "Importing $name…"
        DtedStore.importAsync(
            this, uri, name,
            onProgress = { tiles -> status.text = "Importing $name… $tiles tile(s)" },
        ) { result ->
            importButton?.isEnabled = true
            // Clears any red refusal text from a previous import attempt.
            status.setTextColor(androidx.core.content.ContextCompat.getColor(
                this, R.color.tp_text_tertiary))
            status.text = when {
                result.error != null && result.importedCount == 0 -> "Failed to import $name: ${result.error}"
                result.error != null -> "Imported ${result.importedCount} tile(s) from $name (${result.error})"
                else -> "Imported ${result.importedCount} tile(s) from $name."
            }
            if (result.importedCount == 0) Toast.makeText(this, status.text, Toast.LENGTH_SHORT).show()
            renderDtedRegions()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    /**
     * The video controls that change in the field: the quality, every flight, and which server
     * is active, per callout.
     *
     * ⚠ **Everything else moved to [VideoServersActivity].** Twenty
     * set-once controls in one column made the sub-sections easy to mix up, and the worst of
     * it was that every field belonged to whichever server the toggle above had selected —
     * nothing said which one once the pilot had scrolled into them. The servers screen shows
     * both at once and each field writes its own.
     *
     * The summary line is generated so it cannot go stale: it states where the video is pushed
     * and what the CoT will tell the team, which is what a pilot needs to read before a flight.
     */
    private fun setupVideoControls(prefs: android.content.SharedPreferences) {
        // ⚠ The two one-shot migrations that sat here run from TAKPilot2Application now, BEFORE
        // the mode is read or enforced. A migration must never sit behind something a mode can
        // gate — and the first-run rule reads the slots, so they must exist before it runs.
        val vServerGroup = findViewById<android.widget.RadioGroup>(R.id.videoServerGroup)
        val vServer1 = findViewById<android.widget.RadioButton>(R.id.videoServer1)
        val vServer2 = findViewById<android.widget.RadioButton>(R.id.videoServer2)
        val vProfileGroup = findViewById<android.widget.RadioGroup>(R.id.videoProfileGroup)
        val vSummary = findViewById<TextView>(R.id.videoSummary)

        // ⚠ BASIC HAS ONE VIDEO SERVER, so the choice between two is hidden — but ONLY the label
        // and the radios, never the row. The row also holds videoLockConfig. Hiding it would
        // NOT trip ConfigLock's early return (findViewById still finds a GONE view; that return
        // fires only on an id missing from the layout) — it would leave the lock APPLIED AND
        // UNREACHABLE: a locked controller whose video configuration nobody can unlock, in the
        // mode whose whole point is the free unlock.
        // INVISIBLE, not GONE, for the radios: the row is weighted, and
        // with the group GONE the lock box stretched into a full-width, unlabelled touch target
        // directly under the quality radios. INVISIBLE keeps the box where it always is. An
        // INVISIBLE view takes no touches.
        // ⚠ The LABEL is INVISIBLE too: GONE pulled the lock row up under
        // "High", the most-touched control here, and a low tap on it unlocked with no prompt.
        if (!AppMode.secondVideoServerAllowed(prefs)) {
            findViewById<TextView>(R.id.videoActiveServerLabel).visibility = android.view.View.INVISIBLE
            vServerGroup.visibility = android.view.View.INVISIBLE
        }

        fun selectedProfile(): String = when (vProfileGroup.checkedRadioButtonId) {
            R.id.videoProfileLow -> "low"
            R.id.videoProfileHigh -> "high"
            else -> "standard"
        }

        // The quality belongs to the SLOT, so both servers keep their own — an external server
        // often wants a different one from an internal server. It is written straight through
        // because the flight screen's LIVE button reads the mirror, not this screen.
        vProfileGroup.setOnCheckedChangeListener { _, _ ->
            val slot = activeVideoSlot(prefs)
            prefs.edit()
                .putString(vKey(slot, "profile"), selectedProfile())
                .putString(KEY_V_PROFILE, selectedProfile())
                .apply()
            AppLog.v(TAG, "video profile -> ${selectedProfile()}")
        }

        /**
         * The active-server choice. Selecting one makes it live, which is acceptable here and
         * nowhere else: the flight screen stops the stream in onStop, so nothing can be
         * streaming while this screen is showing.
         */
        vServerGroup.setOnCheckedChangeListener { _, checkedId ->
            val slot = if (checkedId == R.id.videoServer2) 2 else 1
            // ⚠ Refused at the WRITE, not only hidden: a hidden radio can still be checked by a
            // view-state restore, and its listener then writes. See rebuildWithoutSavedState.
            if (slot == 2 && !AppMode.secondVideoServerAllowed(prefs)) {
                AppLog.i(TAG, "Basic mode: active server 2 refused")
                return@setOnCheckedChangeListener
            }
            if (slot == activeVideoSlot(prefs)) return@setOnCheckedChangeListener
            prefs.edit().putInt(KEY_V_ACTIVE_SLOT, slot).apply()
            mirrorActiveSlot(prefs)
            when (prefs.getString(vKey(slot, "profile"), "standard")) {
                "low" -> vProfileGroup.check(R.id.videoProfileLow)
                "high" -> vProfileGroup.check(R.id.videoProfileHigh)
                else -> vProfileGroup.check(R.id.videoProfileStandard)
            }
            paintVideoSummary(prefs)
            // The TAK Server and Application Mode summaries both say who receives the video link, and the link follows the ACTIVE
            // slot — so a change here makes both stale on this same screen.
            paintTakSummary()
            paintAppModeSummary(prefs)
            AppLog.i(TAG, "active video server -> slot $slot (${slotName(prefs, slot)})")
        }

        findViewById<android.widget.Button>(R.id.videoConfigureServers).setOnClickListener {
            if (refuseWhileModeCheckPending()) return@setOnClickListener
            startActivity(android.content.Intent(this, VideoServersActivity::class.java))
        }

        vServerGroup.check(
            if (activeVideoSlot(prefs) == 2) R.id.videoServer2 else R.id.videoServer1)
        when (prefs.getString(vKey(activeVideoSlot(prefs), "profile"), "standard")) {
            "low" -> vProfileGroup.check(R.id.videoProfileLow)
            "high" -> vProfileGroup.check(R.id.videoProfileHigh)
            else -> vProfileGroup.check(R.id.videoProfileStandard)
        }
        paintVideoSummary(prefs)
        mirrorActiveSlot(prefs)
    }

    /**
     * ⚠ The servers screen can rename a server, change its transport or change where it
     * advertises, and all three show in section 2. Without this the pilot comes back to a
     * summary line describing the configuration as it was BEFORE they edited it — which is
     * worse than no summary, because it reads as authoritative.
     */
    override fun onResume() {
        super.onResume()
        // The avoidance section's values come from a bounded read that gives up. Opening
        // this screen is a pilot asking for them, so ask the aircraft again if any are still
        // unknown — reads only. See DjiObstacleState.refreshIfIncomplete.
        DjiObstacleState.refreshIfIncomplete()
        val p = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        paintVideoSummary(p)
        mirrorActiveSlot(p)
        // The Application Mode summary is GENERATED from state that the TAK Server and Video Servers screens change, so it
        // is repainted here as well as in onCreate — painted once, it went stale about who
        // receives the video when the pilot came back from either screen.
        paintAppModeSummary(p)
        // ⚠ RETRY THE TAK CONNECTION HERE. Before the TAK configuration moved to its own
        // screen this was Pre-Flight's job and nobody noticed, because the old onCreate did it
        // on the way past. Pre-Flight is the screen a pilot actually opens before a flight, so
        // it is the right place for it — see TakAutoConnect.retryIfDown.
        TakAutoConnect.retryIfDown(applicationContext)
        // Repainted on EVERY resume, not only on create: coming back from TakServerActivity is
        // the one moment the summary is most likely to be wrong, because the pilot just went
        // there to change exactly what it reports.
        paintTakSummary()
        sdHandler.post(sdTick)
    }

    override fun onPause() {
        super.onPause()
        sdHandler.removeCallbacks(sdTick)
    }

    // ---------------------------------------------------------------- 0. Memory Card

    private val sdHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Re-reads the card once a second while the screen is up: the state after a format
     *  arrives this way, and so does a card inserted while the pilot watches. */
    private val sdTick = object : Runnable {
        override fun run() {
            if (DjiSdkBridge.isProductConnected) AircraftStorage.refresh { runOnUiThread { renderSdCard() } }
            else renderSdCard()
            sdHandler.postDelayed(this, 1000)
        }
    }

    private fun setupSdCardSection() {
        findViewById<android.widget.Button>(R.id.sdCardFormatButton).setOnClickListener { confirmFormatSdCard() }
        renderSdCard()
    }

    /** Human text for the card state. Every value this SDK can report is named: an unnamed
     *  state would read as a fault when several of them are normal. */
    private fun sdStateText(s: dji.sdk.keyvalue.value.camera.CameraSDCardState?): String = when (s) {
        null -> "Not known"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.NORMAL -> "Ready"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.NOT_INSERTED -> "No card"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.FULL -> "Full"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.READ_ONLY -> "Write protected"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.FORMATTING -> "Formatting"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.FORMAT_NEEDED -> "Needs a format"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.FORMAT_RECOMMENDED -> "Format recommended"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.INVALID -> "Invalid card"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.INVALID_FILE_SYSTEM -> "Unknown file system"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.SLOW, dji.sdk.keyvalue.value.camera.CameraSDCardState.WRITING_SLOWLY -> "Slow card"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.BUSY -> "Busy"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.INITIALIZING -> "Starting"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.RECOVERING_FILES -> "Recovering files"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.NO_REMAINING_FILE_INDICES -> "No file numbers left"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.USB_CONNECTED -> "USB connected"
        dji.sdk.keyvalue.value.camera.CameraSDCardState.UNKNOWN_ERROR -> "Error"
        else -> s.name.replace('_', ' ')
    }

    private fun gb(mb: Int): String = if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0) else "$mb MB"

    /** Free OF total when the SDK has given both; it reports the capacity. */
    private fun sdFreeText(): String {
        val free = AircraftStorage.sdFreeMb ?: return "Not known"
        val total = AircraftStorage.sdTotalMb
        return if (total != null && total > 0) "${gb(free)} of ${gb(total)}" else gb(free)
    }

    /** Why the Format button is not available, or null when it is. Separate strings: the pilot
     *  needs to know WHICH reason applies — a disabled button with no reason is the fault the
     *  exterior-lights button already taught. */
    private fun formatBlockedReason(): String? {
        val hud = TakBridgeHolder.hud()
        val s = AircraftStorage.sdState
        return when {
            !DjiSdkBridge.isProductConnected -> "No aircraft"
            hud?.isFlying == true -> "Not while the aircraft is flying"
            hud?.isRecording == true -> "Not while recording"
            s == null -> "Waiting for the camera"
            s == dji.sdk.keyvalue.value.camera.CameraSDCardState.NOT_INSERTED -> "No card"
            s == dji.sdk.keyvalue.value.camera.CameraSDCardState.READ_ONLY -> "Card is write protected"
            s == dji.sdk.keyvalue.value.camera.CameraSDCardState.FORMATTING -> "Formatting"
            else -> null
        }
    }

    private fun renderSdCard() {
        val stateView = findViewById<TextView>(R.id.sdCardState) ?: return
        val status = findViewById<TextView>(R.id.sdCardStatus)
        val button = findViewById<android.widget.Button>(R.id.sdCardFormatButton)
        stateView.text = sdStateText(AircraftStorage.sdState)
        findViewById<TextView>(R.id.sdCardFree).text = sdFreeText()
        val blocked = formatBlockedReason()
        button.isEnabled = blocked == null
        button.alpha = if (blocked == null) 1.0f else 0.45f
        val text = when {
            blocked != null -> blocked
            AircraftStorage.recordingToInternal -> "The camera is recording to internal storage, not this card."
            else -> ""
        }
        status.text = text
        status.visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** ⚠ IRREVERSIBLE, AND ON A PUBLIC-SAFETY AIRFRAME THE FILES MAY BE EVIDENCE. The free space is
     *  quoted so the pilot can see whether the card holds anything; the button says what it does. */
    private fun confirmFormatSdCard() {
        android.app.AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Format the memory card?")
            .setMessage("This erases everything on the SD card in the aircraft. Photographs and " +
                "video cannot be recovered.\n\nFree space now: ${sdFreeText()}")
            .setPositiveButton("Format") { _, _ ->
                // ⚠ RE-VERIFIED AT THE MOMENT OF THE ACTION, ASKED OF THE AIRCRAFT. The enabled
                // state of this button comes from formatBlockedReason, which reads
                // TakBridgeHolder.hud() — null whenever the flight screen has stopped the
                // bridge, so it reads "grounded" on a flying aircraft with TAK up. That stays
                // as the advisory display; the irreversible act fails CLOSED. (The recording
                // half of that function has the same shape and is not re-verified here.)
                AircraftFlightCheck.ask { flying ->
                    runOnUiThread {
                        // Never format from a screen the pilot has left: the answer can arrive late.
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (flying == false) {
                            doFormatSdCard()
                        } else {
                            android.widget.Toast.makeText(this,
                                if (flying == true) "Not formatted — the aircraft is flying."
                                else "Not formatted — the aircraft did not say it is on the ground.",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doFormatSdCard() {
        val button = findViewById<android.widget.Button>(R.id.sdCardFormatButton)
        val status = findViewById<TextView>(R.id.sdCardStatus)
        button.isEnabled = false; button.alpha = 0.45f
        status.text = "Formatting…"; status.visibility = android.view.View.VISIBLE
        AircraftStorage.formatSdCard { error ->
            runOnUiThread {
                // NOT "done": the camera has taken the request; the CARD STATE, re-read by the
                // poll, is what says it finished.
                status.text = if (error == null) "The camera accepted the request."
                              else "The aircraft did not format the card."
                status.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun slotName(prefs: android.content.SharedPreferences, slot: Int): String =
        prefs.getString(vKey(slot, "name"), "")?.takeIf { it.isNotBlank() } ?: "Server $slot"

    /**
     * The server buttons and the one-line summary — everything in section 2 that the servers
     * screen can change.
     *
     * Derived text only, so it is safe to call on every resume: it attaches no listener and
     * touches no field the pilot is editing.
     */
    private fun paintVideoSummary(prefs: android.content.SharedPreferences) {
        val summary = findViewById<TextView>(R.id.videoSummary) ?: return
        findViewById<android.widget.RadioButton>(R.id.videoServer1)?.text = slotName(prefs, 1)
        findViewById<android.widget.RadioButton>(R.id.videoServer2)?.text = slotName(prefs, 2)

        val slot = activeVideoSlot(prefs)
        val host = prefs.getString(vKey(slot, "host"), "") ?: ""
        if (host.isEmpty()) {
            summary.text = "No video server set. Touch Configure Video Servers."
            return
        }
        val transport = VideoTransport.fromPref(prefs.getString(vKey(slot, "transport"), null))
        val port = prefs.getInt(
            vKey(slot, if (transport == VideoTransport.SRT) "srt_port" else "rtsp_port"),
            transport.defaultPort)
        val other = if (slot == 1) 2 else 1
        val team = when (prefs.getString(vKey(slot, "advertise"), "self")) {
            "off" -> "the team gets no video address"
            "other" -> "team plays from ${prefs.getString(vKey(other, "host"), "") ?: ""}:" +
                    prefs.getInt(vKey(other, "rtsp_port"), VideoTransport.RTSP.defaultPort)
            else -> "team plays from $host:" +
                    prefs.getInt(vKey(slot, "rtsp_port"), VideoTransport.RTSP.defaultPort)
        }
        // The path on its own line: it is the name the server sees, and with the random token
        // on it cannot be read from the broadcast id alone.
        val path = StreamPath.compose(
            prefs.getString(vKey(slot, "streamid"), "") ?: "",
            prefs.getBoolean(vKey(slot, "random_path"), false))
        summary.text = "${slotName(prefs, slot)} · $host · ${transport.label} $port · $team" +
            "\nStream path $path"
    }

    /**
     * Copies the ACTIVE slot onto the plain `video_*` keys that [DroneVideoStreamer] reads.
     * A short name for [VideoServersActivity.mirrorActiveSlot], the one mirror.
     *
     * ⚠ The streamer knows nothing about slots, so an edit that is not mirrored is an edit the
     * stream never sees. Two places change WHICH slot is active — this screen's radios and
     * [AppModeTransition] — and both mirror.
     */
    private fun mirrorActiveSlot(prefs: android.content.SharedPreferences) =
        // ⚠ THIS USED TO BE A SECOND COPY, AND IT HAD DRIFTED: it never wrote the three
        // read-leg keys, so switching the active server here sent the new server's host with
        // the old server's read scheme, port and passphrase. One mirror now — see its doc.
        VideoServersActivity.mirrorActiveSlot(prefs)


    /** Preference key for one field of one video server slot. */
    private fun vKey(slot: Int, base: String) = "video_s${slot}_$base"

    /** The server the video goes to now: 1 or 2. */
    private fun activeVideoSlot(prefs: android.content.SharedPreferences): Int =
        if (prefs.getInt(KEY_V_ACTIVE_SLOT, 1) == 2) 2 else 1


    /**
     * Locks the active-server toggle without hiding which server is active.
     *
     * LOCKED IS NOT DISABLED — the same rule the channel rows follow. The buttons keep full
     * contrast and their tint, and stop taking touches. A pilot must always be able to SEE
     * where the video is going; the lock exists to stop an accidental swap, not to hide the
     * destination.
     */
    private fun lockVideoServerToggle(locked: Boolean) {
        for (id in listOf(R.id.videoServer1, R.id.videoServer2)) {
            findViewById<android.widget.RadioButton>(id)?.apply {
                isClickable = !locked
                isFocusable = !locked
            }
        }
    }



    companion object {
        private const val TAG = "TakConnectActivity"
        private const val REQUEST_CODE_DTED_PICK = 2001
        private const val REQUEST_CODE_MSG_EXPORT = 2002
        private const val REQUEST_CODE_MSG_IMPORT = 2003
        /** Shared with the flight screen, which reads the TAK lock to gate its channel dialog. */
        internal const val PREFS = "takpilot2_tak"

        /** Push-to-record needs the microphone at runtime — see onRecordTapped. */
        private const val REQ_MIC_PREFLIGHT = 4101
        private const val KEY_HOST = "host"
        private const val KEY_ENROLL_PORT = "enroll_port"
        private const val KEY_COT_PORT = "cot_port"
        private const val KEY_USERNAME = "username"
        private const val KEY_CALLSIGN = "callsign"
        private const val KEY_CAMERA_POINT = "camera_point"
        private const val KEY_CHANNELS = "channels"          // CSV of selected channel names
        private const val KEY_LOGGED_OUT = "logged_out"      // true = user logged out; block auto-reconnect
        private const val KEY_UID = "uid"
        private const val KEY_TRUSTSTORE = "truststore_path"
        private const val KEY_CLIENTCERT = "clientcert_path"

        // ---- Elevated account (cert B). "CHB" = "channel B", distinct from the KEY_V_*
        // family below, which means "video STREAM config" (RTSP/SRT host/port/codec), a
        // completely different concept. Cert B reuses KEY_HOST/KEY_ENROLL_PORT/KEY_COT_PORT/
        // KEY_CALLSIGN from cert A above — one aircraft, one controller, two certificates. ----
        internal const val KEY_CHB_ENABLED = "chb_enabled"
        private const val KEY_CHB_USERNAME = "chb_username"
        private const val KEY_CHB_UID = "chb_uid"
        private const val KEY_CHB_TRUSTSTORE = "chb_truststore_path"
        private const val KEY_CHB_CLIENTCERT = "chb_clientcert_path"
        private const val KEY_CHB_LOGGED_OUT = "chb_logged_out"

        private const val KEY_V_HOST = "video_host"
        private const val KEY_V_PORT = "video_port"
        /**
         * ⚠ **A speed bump, not security, and it must not be mistaken for one.** The string
         * ships in the APK in plain text — anyone with the file and `strings`, or with adb,
         * reads it in seconds. That is accepted: the threat model is a user tapping into
         * settings they should not adjust, not an adversary. Do not "harden" this with hashing
         * or a per-device secret — that is a stronger lock on the front door of an unlocked
         * house, and it would cost the field recoverability a shared fixed password exists to
         * provide.
         *
         * The entered attempt is never logged, right or wrong.
         */
        internal const val UNLOCK_PASSWORD = "takpilot"

        /** Companion twin of the instance [vKey], for the migrations below. */
        private fun slotKey(slot: Int, base: String) = "video_s${slot}_$base"

        /**
         * Moves a single-server configuration into slot 1, once.
         *
         * An install upgrading from a build that had ONE video server keeps its settings on the
         * plain `video_*` keys. Copying them into slot 1 is what stops the upgrade looking like the
         * video configuration was wiped.
         *
         * It runs one time and marks itself done. It must not run again: after the first edit the
         * slot is the truth and the plain keys are only a mirror of it, so copying back would undo
         * whatever the pilot last did on the other server.
         */
        internal fun migrateVideoSlots(prefs: android.content.SharedPreferences) {
            if (prefs.getBoolean(KEY_V_SLOTS_MIGRATED, false)) return
            prefs.edit()
                .putString(slotKey(1, "name"), "Server 1")
                .putString(slotKey(1, "host"), prefs.getString(KEY_V_HOST, "") ?: "")
                .putInt(slotKey(1, "port"), prefs.getInt(KEY_V_PORT, 8554))
                .putString(slotKey(1, "user"), prefs.getString(KEY_V_USER, "") ?: "")
                .putString(slotKey(1, "pass"), prefs.getString(KEY_V_PASS, "") ?: "")
                .putString(slotKey(1, "streamid"), prefs.getString(KEY_V_STREAMID, "") ?: "")
                // The old `video_tcp` boolean is NOT read across. It could only say UDP or TCP,
                // and UDP no longer exists; every upgrading controller starts on RTSP, which is
                // what all of them were flying.
                .putString(slotKey(1, "transport"), VideoTransport.RTSP.prefValue)
                .putString(slotKey(1, "profile"), prefs.getString(KEY_V_PROFILE, "standard") ?: "standard")
                .putString(slotKey(1, "codec"), prefs.getString(KEY_V_CODEC, null) ?: VideoCodec.H264.prefValue)
                // Slot 2 starts empty and inherits only the defaults. A half-filled second server
                // would be worse than an obviously blank one.
                .putString(slotKey(2, "name"), "Server 2")
                .putInt(slotKey(2, "port"), 8554)
                .putString(slotKey(2, "transport"), VideoTransport.RTSP.prefValue)
                .putString(slotKey(2, "profile"), "standard")
                .putString(slotKey(2, "codec"), VideoCodec.H264.prefValue)
                .putInt(KEY_V_ACTIVE_SLOT, 1)
                .putBoolean(KEY_V_SLOTS_MIGRATED, true)
                .apply()
            AppLog.i(TAG, "video config migrated to slot 1")
        }

        /**
         * Second slot migration: ONE port became a port PER PROTOCOL.
         *
         * It needs its own flag because [migrateVideoSlots] has already run on every upgraded
         * controller and will never run again.
         *
         * ⚠ **Which protocol the old port belonged to depends on the slot's transport.** A slot
         * left on RTSP had an RTSP port; a slot already switched to SRT had an SRT ingest port, and
         * its RTSP port was never asked for — the build used the constant 8554 for it, so that is
         * what goes in.
         *
         * The login is NOT split. One pair authorises the publish on either protocol: SRT has no
         * login of its own, and the media server reads the same credentials out of the stream id.
         *
         * ⚠ It also recovers from a SHORT-LIVED SPLIT LAYOUT that earlier development builds
         * wrote (`rtsp_user`/`rtsp_pass`). Those keys are read back into the one login if the
         * original is empty, so a controller that ran one of those builds does not come up with
         * no credentials.
         */
        internal fun migrateVideoProtocolSplit(prefs: android.content.SharedPreferences) {
            if (prefs.getBoolean(KEY_V_PROTO_SPLIT_MIGRATED, false)) return
            val e = prefs.edit()
            for (slot in 1..2) {
                val oldPort = prefs.getInt(slotKey(slot, "port"), VideoTransport.RTSP.defaultPort)
                val onSrt = VideoTransport.fromPref(prefs.getString(slotKey(slot, "transport"), null)) ==
                        VideoTransport.SRT
                if (!prefs.contains(slotKey(slot, "rtsp_port"))) {
                    e.putInt(slotKey(slot, "rtsp_port"),
                        if (onSrt) VideoTransport.RTSP.defaultPort else oldPort)
                }
                if (!prefs.contains(slotKey(slot, "srt_port"))) {
                    e.putInt(slotKey(slot, "srt_port"),
                        if (onSrt) oldPort else VideoTransport.SRT.defaultPort)
                }
                // Recover a login left behind by the split-layout builds.
                if ((prefs.getString(slotKey(slot, "user"), "") ?: "").isEmpty()) {
                    prefs.getString(slotKey(slot, "rtsp_user"), null)?.takeIf { it.isNotEmpty() }
                        ?.let { e.putString(slotKey(slot, "user"), it) }
                }
                if ((prefs.getString(slotKey(slot, "pass"), "") ?: "").isEmpty()) {
                    prefs.getString(slotKey(slot, "rtsp_pass"), null)?.takeIf { it.isNotEmpty() }
                        ?.let { e.putString(slotKey(slot, "pass"), it) }
                }
                // The passphrase only changed key name.
                if (!prefs.contains(slotKey(slot, "srt_phrase"))) {
                    e.putString(slotKey(slot, "srt_phrase"),
                        prefs.getString(slotKey(slot, "srtpass"), "") ?: "")
                }
            }
            e.putBoolean(KEY_V_PROTO_SPLIT_MIGRATED, true).apply()
            AppLog.i(TAG, "video config migrated to a port per protocol")
        }


        /** Internal: the first-run rule reads all three locks; this is the battery lock. */
        internal const val KEY_AIRCRAFT_LOCKED = "aircraft_config_locked"
        internal const val KEY_TAK_LOCKED = "tak_config_locked"
        internal const val KEY_VIDEO_LOCKED = "video_config_locked"

        private const val KEY_V_USER = "video_user"
        /** Named constant, not a literal. The save site used a bare "video_pass" while the
         *  restore site did not exist at all — a constant makes the pair impossible to miss. */
        private const val KEY_V_PASS = "video_pass"
        private const val KEY_V_STREAMID = "video_streamid"
        // The plain transport, port-per-protocol, passphrase and advertisement keys
        // (video_transport, video_rtsp_port, video_srt_port, video_srt_passphrase, video_adv_*)
        // had constants here for this screen's own copy of the mirror. That copy is gone — it had
        // drifted, see VideoServersActivity.mirrorActiveSlot — and the one mirror names every key
        // it writes in one place. Do not reintroduce a second set of names for them here.

        /** Guard for [migrateVideoProtocolSplit]. Separate from the slot-migration flag, which
         *  has already run everywhere and cannot carry a second meaning. */
        internal const val KEY_V_PROTO_SPLIT_MIGRATED = "video_port_per_protocol_migrated"

        private const val KEY_V_PROFILE = "video_profile"
        /** The outbound codec ("h264"/"h265") — read by VideoStreamerHolder.buildConfig. */
        private const val KEY_V_CODEC = "video_codec"
        /** Which of the two video-server slots is live: 1 or 2. The slot keys themselves are
         *  built by [vKey]; these plain keys stay as the mirror every consumer reads. */
        private const val KEY_V_ACTIVE_SLOT = "video_active_slot"
        /** One-shot marker for [migrateVideoSlots]. */
        internal const val KEY_V_SLOTS_MIGRATED = "video_slots_migrated"
    }

    /** Action-bar menu button behaves the same as the system back gesture. */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

}

