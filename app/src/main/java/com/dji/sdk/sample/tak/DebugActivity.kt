package com.dji.sdk.sample.tak

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dji.sdk.sample.R
import com.taklite.util.AppLog
import java.io.RandomAccessFile

/**
 * Debug screen: the logging switch with its options under it, clear/delete the active
 * log, and watch it fill live. Only reads/writes AppLog's own file sink — no full logcat.
 */
class DebugActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var meta: TextView

    // Only re-render when the file actually changed, and tail it so a near-cap 1MB
    // file doesn't get re-laid-out into the TextView every tick.
    private var lastRenderedLength = -1L
    private val maxTailBytes = 500 * 1024L

    // Explicit, touch-driven "follow the tail" state — more robust than re-deriving it
    // from scroll geometry on every poll, which is sensitive to layout-pass timing.
    // The instant the user puts a finger down on the log, we stop auto-scrolling; we
    // only resume following once they've scrolled back to the bottom themselves.
    private var pinnedToBottom = true

    private val poll = object : Runnable {
        override fun run() {
            refreshLogView()
            handler.postDelayed(this, 1000)
        }
    }

    companion object {
        private const val TAG = "DebugActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        // Menu button on the left of the action bar, on every screen you can reach from Home.
        // Returns to the home screen, same as the system back gesture — a pilot should not have
        // to learn a different way out of each screen.
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }

        AppLog.sweepExpiredLogs()
        AppLog.v(TAG, "onCreate")

        logText = findViewById(R.id.debugLogText)
        logScroll = findViewById(R.id.debugLogScroll)
        meta = findViewById(R.id.debugLogMeta)

        logScroll.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                pinnedToBottom = false
            } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                // Let the fling/settle finish, then check if they landed back at the bottom.
                logScroll.postDelayed({ pinnedToBottom = isScrolledToBottom() }, 300)
            }
            false   // don't consume — ScrollView still needs this to handle the drag/fling
        }

        val verboseToggle = findViewById<CheckBox>(R.id.debugVerboseToggle)
        val takToggle = findViewById<CheckBox>(R.id.debugTakToggle)
        val obstacleToggle = findViewById<CheckBox>(R.id.debugObstacleToggle)
        val resourceToggle = findViewById<CheckBox>(R.id.debugResourceToggle)
        val monitorToggle = findViewById<CheckBox>(R.id.debugResourceMonitorToggle)
        val options = listOf(verboseToggle, takToggle, obstacleToggle, resourceToggle, monitorToggle)
        val loggingLabel = findViewById<TextView>(R.id.debugLoggingLabel)
        val rows = listOf(R.id.debugVerboseRow, R.id.debugTakRow, R.id.debugObstacleRow,
            R.id.debugResourceRow, R.id.debugResourceMonitorRow).map { findViewById<View>(it) }
        // The five are options OF the log: greyed while it is off, because none of them does
        // anything without it. Greyed by the ROW's alpha: a tinted check box and a separate
        // label do not dim on their own when disabled, so a first build showed bright boxes
        // that did not respond. The label says the state in words too.
        fun renderSubOptions(on: Boolean) {
            loggingLabel.text = if (on) "Logging Enabled" else "Logging Disabled"
            options.forEach { it.isEnabled = on }
            rows.forEach { it.alpha = if (on) 1f else 0.35f }
        }
        // THE SWITCH SETS THE OPTIONS: on checks all and
        // the pilot then clears what they do not want; off clears all. A checked box under an
        // off switch was still showing the resource row on the flight screen. The AppLog
        // getters are gated on `enabled` as well, so the two cannot disagree.
        fun setAllOptions(on: Boolean) {
            AppLog.verbose = on
            AppLog.takLogging = on
            AppLog.obstacleLogging = on
            AppLog.resourceLogging = on
            AppLog.resourceMonitor = on
            options.forEach { it.isChecked = on }
        }

        val toggle = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.debugLoggingToggle)
        toggle.isChecked = AppLog.enabled
        renderSubOptions(AppLog.enabled)
        toggle.setOnCheckedChangeListener { _, on ->
            AppLog.enabled = on
            setAllOptions(on)
            renderSubOptions(on)
            // Log.i as well as the file: this line must reach logcat when the file is OFF.
            android.util.Log.i(TAG, "logging ${if (on) "enabled" else "disabled"}")
            AppLog.v(TAG, "logging ${if (on) "enabled" else "disabled"}")
        }
        // THE ROW IS THE TARGET, the control inside it is not clickable on its own: a switch
        // invites a tap on its words, and a tap on the label did nothing. The listener above
        // fires through toggle() exactly as through a direct tap.
        findViewById<View>(R.id.debugLoggingRow).setOnClickListener { toggle.toggle() }
        rows.zip(options).forEach { (row, box) ->
            row.setOnClickListener { if (box.isEnabled) box.toggle() }
        }

        verboseToggle.isChecked = AppLog.verbose
        verboseToggle.setOnCheckedChangeListener { _, on ->
            AppLog.verbose = on
            AppLog.v(TAG, "detail level set to ${if (on) "Detailed" else "Standard"}")
        }

        takToggle.isChecked = AppLog.takLogging
        takToggle.setOnCheckedChangeListener { _, on ->
            AppLog.takLogging = on
            // Logged from DebugActivity (an app-side tag), so this line survives either way —
            // it marks the point in the log where the filter changed.
            AppLog.i(TAG, "TAK/CoT logs ${if (on) "INCLUDED" else "HIDDEN"}")
        }

        obstacleToggle.isChecked = AppLog.obstacleLogging
        obstacleToggle.setOnCheckedChangeListener { _, on ->
            AppLog.obstacleLogging = on
            // Same reasoning as the TAK toggle: an app-side tag, so the line survives the
            // filter it is describing.
            AppLog.i(TAG, "obstacle distance logs ${if (on) "INCLUDED" else "HIDDEN"}")
        }

        resourceToggle.isChecked = AppLog.resourceLogging
        resourceToggle.setOnCheckedChangeListener { _, on ->
            AppLog.resourceLogging = on
            AppLog.i(TAG, "system resource logs ${if (on) "INCLUDED" else "HIDDEN"}")
        }

        monitorToggle.isChecked = AppLog.resourceMonitor
        monitorToggle.setOnCheckedChangeListener { _, on ->
            AppLog.resourceMonitor = on
            AppLog.i(TAG, "flight-screen resource row ${if (on) "SHOWN" else "HIDDEN"}")
        }

        // There is no "Export Log" button: the Downloads archive is the one route off the
        // device.
        // Both deletes confirm first, like every other destructive action in the app (marker
        // delete, Clear All Markers, reset home point), with TakDialogTheme_Destructive. A stray
        // tap here would otherwise destroy the active log (Clear) or every rotated log file
        // (Delete All) with no way back, and Delete All in particular takes files the archive
        // can't reach — only the active log is ever exported, so a rotated log has no other way
        // off the device once deleted.
        setupSrtLatencyControl()
        findViewById<android.widget.Button>(R.id.debugClearButton).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
                .setTitle("Clear Log")
                .setMessage("Erase the active log? This cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    AppLog.i(TAG, "debug: clear active log confirmed")
                    AppLog.clearActive()
                    lastRenderedLength = -1
                    pinnedToBottom = true
                    refreshLogView()
                    toast("Log cleared")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<android.widget.Button>(R.id.debugDeleteButton).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
                .setTitle("Delete All Logs")
                .setMessage("Delete every log file on this device, including rotated logs from " +
                    "earlier sessions? Only the active log can be exported — anything else " +
                    "deleted here has no other way off the device. This cannot be undone.")
                .setPositiveButton("Delete All") { _, _ ->
                    AppLog.i(TAG, "debug: delete all logs confirmed")
                    AppLog.deleteAll()
                    lastRenderedLength = -1
                    pinnedToBottom = true
                    refreshLogView()
                    toast("All logs deleted")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun refreshLogView() {
        val file = AppLog.activeLogFile()
        if (!file.exists()) {
            if (lastRenderedLength != 0L) {
                logText.text = "(No log yet. Turn on Logging enabled to start.)"
                lastRenderedLength = 0
            }
            meta.text = ""
            return
        }
        val length = file.length()
        if (length == lastRenderedLength) return
        lastRenderedLength = length

        val tail = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val start = maxOf(0L, length - maxTailBytes)
                raf.seek(start)
                val bytes = ByteArray((length - start).toInt())
                raf.readFully(bytes)
                String(bytes)
            }
        }.getOrDefault("(The app cannot read the log file.)")

        logText.text = tail
        meta.text = "${file.name} — ${length / 1024} KB"

        // Only auto-scroll if the user hasn't manually scrolled away from the bottom —
        // otherwise a 1s poll would yank them back down mid scroll-back through history.
        if (pinnedToBottom) {
            logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun isScrolledToBottom(): Boolean {
        if (logText.height == 0) return true   // nothing laid out yet — treat as "at bottom"
        val slop = (8 * resources.displayMetrics.density).toInt()
        val bottom = logScroll.scrollY + logScroll.height
        return bottom >= logText.height - slop
    }

    /**
     * The SRT latency override, in MILLISECONDS.
     *
     * Here rather than on Pre-Flight because it is a property of the network, not a flight
     * decision — and on a screen at all rather than a constant because 500 ms comes from one
     * ground test, and controllers in the field must be able to act on field evidence without a new build
     * reaching an aircraft that is flying. See [VideoTransport.SRT_LATENCY_DEFAULT_MS] for the
     * measurements and for how to tell whether the value is right.
     *
     * ⚠ **The status line under the box states what was SAVED, never what was typed.** A value
     * outside the sane range is refused and the default is stored, thus a pilot who types the
     * microsecond form (500000) is told that the value is 500 and not left believing the box.
     *
     * The value is read at every stream start, so a change here takes effect at the next LIVE.
     */
    private fun setupSrtLatencyControl() {
        val field = findViewById<android.widget.EditText>(R.id.debugSrtLatency)
        val status = findViewById<TextView>(R.id.debugSrtLatencyStatus)
        val prefs = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)

        fun renderStatus() {
            val saved = VideoTransport.srtLatencyMs(prefs)
            status.text = "In use: ${saved} ms" +
                    if (saved == VideoTransport.SRT_LATENCY_DEFAULT_MS) " (default)" else ""
        }

        field.setText(VideoTransport.srtLatencyMs(prefs).toString())
        renderStatus()

        // Saved when the box loses the focus, not on each keystroke: a partly typed "5" out of
        // "500" is inside the sane range and would be stored as a real value.
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val typed = field.text.toString().trim().toIntOrNull()
            val use = VideoTransport.clampLatencyMs(typed ?: VideoTransport.SRT_LATENCY_DEFAULT_MS)
            prefs.edit().putInt(VideoTransport.KEY_SRT_LATENCY_MS, use).apply()
            if (typed != null && typed != use) {
                toast("$typed ms is outside ${VideoTransport.SRT_LATENCY_MIN_MS}–" +
                        "${VideoTransport.SRT_LATENCY_MAX_MS} ms — using $use ms")
            }
            field.setText(use.toString())
            renderStatus()
            AppLog.i(TAG, "SRT latency -> $use ms")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** Action-bar menu button behaves the same as the system back gesture. */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

}
