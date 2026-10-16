package com.dji.sdk.sample.tak

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.taklite.client.tak.TakManager
import java.io.File

/**
 * Memory, CPU and contact-count diagnostics, written to the log while a flight is running.
 *
 * The contact count is here deliberately. A stale-retention bug in
 * [com.taklite.client.tak.CotParser] once held every distinct ADS-B contact for at least ten
 * minutes regardless of how briefly it was live: 161 "known" contacts against a handful on a
 * second TAK client, ending in a sequence of app-process OOM kills in the air. The bug is
 * fixed, but the count stays as the fastest way to see whether it
 * — or a future variant — is coming back, alongside the raw memory figures, right up to a crash.
 *
 * CPU is instantaneous load, not memory. A software encoder pegging a core shows here first and
 * does not show in the memory figures at all.
 *
 * GPU comes from this SoC's Adreno sysfs node (`/sys/class/kgsl/kgsl-3d0/`), CONFIRMED
 * READABLE BY AN ORDINARY APP PROCESS on the RC Plus 2 — verified with `run-as`,
 * which answered 25%. That is a device and build property, not an Android guarantee: SELinux
 * locks this node to root on plenty of devices, so every read is wrapped and degrades to "—".
 *
 * ⚠ This application has ONE target — the RC Plus 2 — and the node is readable on it. An app
 * that runs on whatever phone is attached to an RC-N1 could not assume that; this one can, and
 * the premise was checked against the hardware this app actually runs on.
 *
 * ⚠ `cpu=` may show "—/x%" on a modern Android. The system figure comes from `/proc/stat`, which
 * SELinux hides from apps on API 26 and above; the app's own figure uses the official
 * [Process.getElapsedCpuTime] and is always available. The read is wrapped and degrades to null
 * rather than throwing, so this is expected rather than a fault to chase.
 *
 * TWO SINKS, TWO SWITCHES. [formattedLine] goes to the log every 30s and is on by default;
 * [formattedSegments] fills the flight screen's overlay row and is off by default. A log line
 * costs nothing and is read afterwards; an overlay covers live video while a pilot is flying.
 */
object ResourceMonitor {

    data class Snapshot(
        val sysAvailMb: Int,
        val sysTotalMb: Int,
        val lowMemory: Boolean,
        val appPssMb: Int,
        val heapUsedMb: Int,
        val heapMaxMb: Int,
        val contactCount: Int,
        /** Null on the first sample only — both need a delta against the previous call. */
        val sysCpuPct: Int?,
        val appCpuPct: Int?,
        /** Null if this device/build does not expose GPU sysfs to an app process. */
        val gpuBusyPct: Int?,
        val gpuClockMhz: Int?,
    )

    // Previous-sample state for the CPU deltas. SystemClock.elapsedRealtime() (monotonic, immune
    // to RTC/timezone changes) is the wall clock; -1 means "no previous sample yet".
    @Volatile private var lastWallMs: Long = -1
    @Volatile private var lastSysTotalJiffies: Long = -1
    @Volatile private var lastSysIdleJiffies: Long = -1
    @Volatile private var lastAppCpuMs: Long = -1

    fun snapshot(context: Context): Snapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(memInfo) }

        // Process-wide PSS (Proportional Set Size) — what ActivityManager itself weighs an app's
        // footprint by, so this is the same number the OS's own low-memory decision is based on.
        val pssMb = runCatching {
            am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
                ?.let { it.totalPss / 1024 } ?: 0
        }.getOrDefault(0)

        val rt = Runtime.getRuntime()
        val heapUsedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        val heapMaxMb = (rt.maxMemory() / (1024 * 1024)).toInt()

        val contactCount = runCatching { TakManager.getInstance().takUsers.size }.getOrDefault(-1)

        // ---- CPU: delta against the previous snapshot ----
        val nowWallMs = SystemClock.elapsedRealtime()
        val sysJiffies = readSystemCpuJiffies()
        val appCpuMs = Process.getElapsedCpuTime()   // official API: this process's CPU time, in ms

        var sysCpuPct: Int? = null
        var appCpuPct: Int? = null
        if (lastWallMs >= 0) {
            val wallDeltaMs = nowWallMs - lastWallMs
            if (sysJiffies != null && lastSysTotalJiffies >= 0 && wallDeltaMs > 0) {
                val totalDelta = sysJiffies.first - lastSysTotalJiffies
                val idleDelta = sysJiffies.second - lastSysIdleJiffies
                if (totalDelta > 0) {
                    sysCpuPct = (100 * (totalDelta - idleDelta) / totalDelta).toInt().coerceIn(0, 100)
                }
            }
            if (wallDeltaMs > 0) {
                // NOT clamped to 100 — a multi-threaded process can legitimately use more than
                // one core's worth of wall-clock time, same convention `top` uses.
                appCpuPct = (100 * (appCpuMs - lastAppCpuMs) / wallDeltaMs).toInt().coerceAtLeast(0)
            }
        }
        lastWallMs = nowWallMs
        if (sysJiffies != null) { lastSysTotalJiffies = sysJiffies.first; lastSysIdleJiffies = sysJiffies.second }
        lastAppCpuMs = appCpuMs

        return Snapshot(
            sysAvailMb = (memInfo.availMem / (1024 * 1024)).toInt(),
            sysTotalMb = (memInfo.totalMem / (1024 * 1024)).toInt(),
            lowMemory = memInfo.lowMemory,
            appPssMb = pssMb,
            heapUsedMb = heapUsedMb,
            heapMaxMb = heapMaxMb,
            contactCount = contactCount,
            gpuBusyPct = readGpuBusyPct(),
            gpuClockMhz = readGpuClockMhz(),
            sysCpuPct = sysCpuPct,
            appCpuPct = appCpuPct,
        )
    }

    /** (total, idle+iowait) jiffies from /proc/stat's aggregate "cpu " line, or null if unreadable. */
    private fun readSystemCpuJiffies(): Pair<Long, Long>? = runCatching {
        val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
        // "cpu  57354 18884 56145 282250 1808 6151 3171 0 0 0"
        // fields: user nice system idle iowait irq softirq steal guest guest_nice
        val fields = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (fields.size < 4) return null
        val idle = fields[3] + (fields.getOrElse(4) { 0L })
        val total = fields.sum()
        total to idle
    }.getOrNull()

    /**
     * Five short strings for the flight-screen row: SYS / APP / CPU / GPU / TAK, one per fixed
     * cell. ALWAYS five, in this order, even when a value is unavailable (GPU shows "—" rather
     * than the cell disappearing) — a cell that disappears shifts the ones beside it, and this
     * row is watched for a value that CHANGES.
     */
    fun formattedSegments(context: Context): List<String> {
        val s = snapshot(context)
        val lowFlag = if (s.lowMemory) " !" else ""
        val gpu = if (s.gpuBusyPct != null || s.gpuClockMhz != null) {
            (s.gpuBusyPct?.let { "$it%" } ?: "—") + (s.gpuClockMhz?.let { " ${it}MHz" } ?: "")
        } else "—"
        return listOf(
            "SYS ${s.sysAvailMb}/${s.sysTotalMb}MB$lowFlag",
            "APP ${s.appPssMb}MB/${s.heapUsedMb}MB",
            "CPU ${s.sysCpuPct?.let { "$it%" } ?: "—"}/${s.appCpuPct?.let { "$it%" } ?: "—"}",
            "GPU $gpu",
            "TAK ${s.contactCount}",
        )
    }

    /**
     * One line for the log. Fixed field order so a flight's lines can be diffed or eyeballed
     * down a column.
     *
     * `tak=` is the number that mattered in the OOM incident: the contact map held 161 aircraft
     * while the live picture showed a handful, and the process was OOM-killed in the air. It
     * should oscillate around the size of the real picture, not climb across a flight.
     */
    fun formattedLine(context: Context): String {
        val s = snapshot(context)
        val lowFlag = if (s.lowMemory) " LOW-MEM" else ""
        val sysCpu = s.sysCpuPct?.let { "$it%" } ?: "—"
        val appCpu = s.appCpuPct?.let { "$it%" } ?: "—"
        return "sys=${s.sysAvailMb}/${s.sysTotalMb}MB$lowFlag " +
            "app=${s.appPssMb}MB heap=${s.heapUsedMb}/${s.heapMaxMb}MB " +
            "cpu=$sysCpu/$appCpu tak=${s.contactCount}"
    }
    private fun readGpuBusyPct(): Int? = runCatching {
        java.io.File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
            .readText().trim().takeWhile { it.isDigit() }.toIntOrNull()
    }.getOrNull()

    private fun readGpuClockMhz(): Int? = runCatching {
        (java.io.File("/sys/class/kgsl/kgsl-3d0/gpuclk").readText().trim().toLong() / 1_000_000L)
            .toInt()
    }.getOrNull()

}
