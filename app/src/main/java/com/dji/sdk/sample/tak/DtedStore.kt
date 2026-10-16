package com.dji.sdk.sample.tak

import android.content.Context
import android.net.Uri
import com.taklite.util.AppLog
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Stores pilot-uploaded DTED (Digital Terrain Elevation Data, .dt0 to .dt5) files in a
 * single shared `terrain/` directory (no per-region subfolders — see [TerrainDatabase]'s doc
 * for why), while [TerrainDao] tracks which imported "region" (one row per uploaded .zip, e.g.
 * "Training area") references which tile filenames. Users manage regions, not individual tiles;
 * overlapping tiles between regions are physically stored once and shared.
 *
 * Purely storage/management today — [CameraSlantPoint]'s flat-ground assumption doesn't yet
 * consult these files to correct the Sensor Point of Interest; wiring an actual DTED reader
 * into that math (binary DTED parsing + terrain-aware slant-range solving) is a separate,
 * larger follow-up. This just gets files onto the device and lets the pilot manage them.
 */
object DtedStore {
    private const val TAG = "DtedStore"
    private const val DIR_NAME = "terrain"
    private const val LEGACY_DIR_NAME = "dted" // pre-Room per-region-folder layout, discarded
    /**
     * The tile extensions an import accepts.
     *
     * ⚠ **`dt3`-`dt5` WERE ADDED LATER, AND NOTHING ELSE HAD TO CHANGE.**
     * `DtedTile.open` takes the post spacing, the grid size and the origin from the UHL HEADER
     * and never looks at the filename, so a finer tile was always readable — the importer was
     * the only thing refusing it. NGA defines levels past 2 and tooling exists to build them.
     *
     * ⚠ **THE FAULT THIS FIXES WAS SILENT, AND THAT IS WHY IT MATTERED.** A zip holding ONLY
     * unaccepted tiles fails loudly ("No DTED tiles found"). A MIXED zip did not: the accepted
     * entries imported, the region appeared with a tile count, and the rest were dropped with
     * no mention anywhere. `DtedIndex`'s `loaded N/M` line could not reveal it either — `M`
     * counts files already in the pool, and a refused tile never reaches the pool.
     */
    private val TILE_EXTENSIONS = setOf("dt0", "dt1", "dt2", "dt3", "dt4", "dt5")

    // ---- Import bounds ----
    // A full DTED2 1°x1° cell is roughly 26 MB, DTED1 about 2.9 MB, DTED0 about 34 KB. Anything
    // far past the DTED2 figure is not a tile, whatever the archive claims, so a per-entry cap
    // stops a zip bomb without a legitimate tile ever reaching it.
    private const val MAX_TILE_BYTES = 96L * 1024 * 1024
    /** Whole-import ceiling. A real multi-cell region is comfortably inside this. */
    private const val MAX_IMPORT_BYTES = 8L * 1024 * 1024 * 1024
    /** Entry-count ceiling, for an archive that is small per entry but endless. */
    private const val MAX_TILES_PER_IMPORT = 4_000
    /** Never write the controller down to nothing. Filling internal storage does not just break
     *  this app — it destabilises the whole device, mid-flight if the pilot is importing then. */
    private const val FREE_SPACE_RESERVE_BYTES = 500L * 1024 * 1024

    data class ImportResult(val importedCount: Int, val error: String? = null)
    data class Region(
        val id: Long,
        val name: String,
        val importedAtMs: Long,
        val fileCount: Int,
        val totalBytes: Long,
    )

    fun dir(context: Context): File {
        wipeLegacyDirOnce(context)
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** One-shot: the old per-region-folder layout is being retired wholesale (no migration —
     *  pilots re-import their region zips fresh against the new mechanism). */
    @Volatile private var legacyWiped = false
    private fun wipeLegacyDirOnce(context: Context) {
        if (legacyWiped) return
        legacyWiped = true
        val legacy = File(context.filesDir, LEGACY_DIR_NAME)
        if (legacy.exists()) {
            val ok = legacy.deleteRecursively()
            AppLog.i(TAG, "wiped legacy DTED dir ($LEGACY_DIR_NAME) -> $ok")
        }
    }

    /** Every tile file in the shared pool — flat, for [DtedIndex]'s elevation lookups (which
     *  don't care which region a tile came from, only where on Earth it is). Also sweeps any
     *  `pending` import row left behind by a crashed/interrupted import (see [TerrainDatabase]
     *  doc) — its partially-extracted files, if any, are harmless unreferenced leftovers. */
    fun listFiles(context: Context): List<File> {
        reconcileIncompleteImports(context)
        return dir(context).listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
    }

    /** One entry per completed import ("region"), name-sorted. */
    fun listRegions(context: Context): List<Region> {
        reconcileIncompleteImports(context)
        return TerrainDatabase.get(context).terrainDao().listImports()
            .map { Region(it.id, it.displayName, it.importedAtMs, it.fileCount, it.totalBytes) }
    }

    private fun reconcileIncompleteImports(context: Context) {
        val dao = TerrainDatabase.get(context).terrainDao()
        val stuck = dao.listIncompleteImports()
        for (imp in stuck) {
            AppLog.w(TAG, "dropping incomplete import #${imp.id} \"${imp.displayName}\" (interrupted mid-import)")
            dao.deleteImportRow(imp.id)
        }
    }

    /** Deletes any tile file in the shared pool with zero remaining references — leftovers
     *  from an interrupted import or an interrupted deletion (see [TerrainDatabase] doc).
     *  Returns the number of files removed. */
    fun cleanUnreferencedTiles(context: Context): Int {
        val dao = TerrainDatabase.get(context).terrainDao()
        val referenced = dao.allReferencedTiles().toHashSet()
        val orphaned = dir(context).listFiles { f -> f.isFile && f.name !in referenced } ?: emptyArray()
        var removed = 0
        for (f in orphaned) if (runCatching { f.delete() }.getOrDefault(false)) removed++
        if (removed > 0) AppLog.i(TAG, "cleaned $removed unreferenced tile file(s)")
        return removed
    }

    /** Imports the picked document as a new region named after [displayName] (extension
     *  stripped): a .zip has every .dt0-.dt5 entry extracted into the shared pool
     *  (overwriting any same-named tile in place — the newest import always wins, no
     *  confirmation needed, by design); anything else is imported as a lone-tile region.
     *  Crash-safe: the import row is inserted `pending` before any file I/O and only flipped
     *  to `complete` after every tile is on disk — see [TerrainDatabase]. */
    /**
     * [import] on a worker thread, with both callbacks delivered on the MAIN thread.
     *
     * The import used to run synchronously in `onActivityResult`. A real region zip is tens to
     * hundreds of megabytes of decompress-and-write, i.e. tens of seconds at best — far past
     * the ANR budget — and the screen showed its previous text the whole time, because the
     * status line was only written after the call returned. Same shape as
     * [UasfmStore.downloadAsync], which is how this screen already runs its other long job.
     */
    fun importAsync(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (Int) -> Unit = {},
        onDone: (ImportResult) -> Unit,
    ) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val r = try {
                import(context, uri, displayName) { tiles -> main.post { onProgress(tiles) } }
            } catch (t: Throwable) {
                AppLog.w(TAG, "DTED import failed: ${t.message}")
                ImportResult(0, t.message ?: "Import failed")
            }
            main.post { onDone(r) }
        }.start()
    }

    fun import(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (Int) -> Unit = {},
    ): ImportResult {
        val regionName = sanitizeRegionName(displayName.substringBeforeLast('.'))
        val dao = TerrainDatabase.get(context).terrainDao()
        val importId = dao.insertImport(
            ImportEntity(
                displayName = regionName,
                importedAtMs = System.currentTimeMillis(),
                fileCount = 0,
                totalBytes = 0,
                status = "pending",
            )
        )
        val pool = dir(context)
        val result = if (displayName.lowercase().endsWith(".zip")) {
            importZip(context, uri, pool, onProgress)
        } else {
            importSingleFile(context, uri, displayName, pool)
        }
        if (result.tileNames.isEmpty()) {
            dao.deleteImportRow(importId) // don't leave an empty/failed region behind
            return ImportResult(0, result.error ?: "No DTED tiles found (.dt0 to .dt5)")
        }
        val totalBytes = result.tileNames.sumOf { File(pool, it).length() }
        dao.finishImport(importId, result.tileNames, totalBytes)
        DtedIndex.invalidate()
        AppLog.i(TAG, "import #$importId \"$regionName\": ${result.tileNames.size} tile(s), $totalBytes bytes")
        return ImportResult(result.tileNames.size, result.error)
    }

    private fun sanitizeRegionName(name: String): String {
        val cleaned = name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        return cleaned.ifEmpty { "Region-${System.currentTimeMillis()}" }
    }

    private data class ExtractResult(val tileNames: List<String>, val error: String? = null)

    /**
     * Resolves one tile file INSIDE the pool, or null if the name cannot be made safe.
     *
     * The single-file path used to build `File(pool, displayName)` straight from the SAF display
     * name. That name is chosen by whoever authored the file, and a `/` in it walks out of the
     * pool — the zip path had always flattened separators, this one never did. Both paths share
     * this now so they cannot drift apart again.
     *
     * Flattening separators is also what produces the intended "w101/n45.dt2" -> "w101_n45.dt2"
     * behaviour the zip import documents. The canonical-path check afterwards is belt and
     * braces: it proves the resolved file really is under the pool rather than trusting the
     * string rewriting to have caught every form.
     */
    // `internal` rather than private ONLY so DtedStorePathTest can pin it. This is a security
    // control, not a formatting helper — the same reasoning that makes OutboundLogRedactionTest
    // a test worth having. Do not widen it further.
    internal fun poolFile(pool: File, rawName: String): File? {
        val flat = rawName.replace('/', '_').replace('\\', '_').trim().trimStart('.')
        if (flat.isEmpty()) return null
        val f = File(pool, flat)
        return if (f.canonicalPath.startsWith(pool.canonicalPath + File.separator)) f else null
    }

    /** True when the name ends in a DTED tile extension. The zip path has always filtered on
     *  this; the single-file path used to accept anything at all. */
    private fun isTileName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in TILE_EXTENSIONS

    private fun importSingleFile(context: Context, uri: Uri, displayName: String, pool: File): ExtractResult {
        return try {
            if (!isTileName(displayName)) {
                AppLog.w(TAG, "refusing non-tile import \"$displayName\" — not a .dt0 to .dt5")
                return ExtractResult(emptyList(), "Not a DTED tile: $displayName")
            }
            val dest = poolFile(pool, displayName)
                ?: return ExtractResult(emptyList(), "Unusable file name: $displayName")
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                writeTileAtomically(dest) { output -> copyAtMost(input, output, MAX_TILE_BYTES) }
                true
            } ?: false
            if (!copied) return ExtractResult(emptyList(), "Failed to save $displayName")
            ExtractResult(listOf(dest.name))
        } catch (t: Throwable) {
            AppLog.w(TAG, "DTED file import failed: ${t.message}")
            ExtractResult(emptyList(), t.message)
        }
    }

    /** Extracts every .dt0-.dt5 entry from the zip into the shared pool, flattening each
     *  entry's path ("w101/n45.dt2" -> "w101_n45.dt2") so same-named tiles from different
     *  longitude folders don't collide with each other WITHIN this zip. Across zips, an
     *  identical flattened name is treated as the same physical tile and overwritten (the
     *  intended "duplicate imports are fine, newest wins" behavior). */
    private fun importZip(
        context: Context,
        uri: Uri,
        pool: File,
        onProgress: (Int) -> Unit = {},
    ): ExtractResult {
        val tileNames = mutableListOf<String>()
        // The extract used to have NO bound of any kind — not on entry count, not on bytes written,
        // and no check that the device could hold the result. A crafted (or simply enormous)
        // zip filled internal storage until the disk was full, which on this controller takes
        // the whole system down with it, not just this app. Three independent bounds, because
        // each catches a different shape: a zip bomb (few entries, vast output), a zip with
        // absurdly many entries, and an honest-but-too-big region on a nearly-full device.
        var written = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && isTileName(entry.name)) {
                            if (tileNames.size >= MAX_TILES_PER_IMPORT) {
                                return ExtractResult(tileNames,
                                    "Stopped at $MAX_TILES_PER_IMPORT tiles — is this a DTED region?")
                            }
                            if (pool.usableSpace < FREE_SPACE_RESERVE_BYTES) {
                                return ExtractResult(tileNames,
                                    "Stopped: the controller is running out of storage")
                            }
                            // Same resolver as the single-file path, so the two cannot
                            // drift apart on what counts as a safe name again.
                            val dest = poolFile(pool, entry.name)
                            if (dest == null) {
                                AppLog.w(TAG, "skipping zip entry with an unusable name: ${entry.name}")
                            } else {
                                // Copy through a counting limit rather than trusting the entry's
                                // declared size, which a hostile zip simply lies about.
                                val n = writeTileAtomically(dest) { out ->
                                    copyAtMost(zip, out, MAX_TILE_BYTES)
                                }
                                written += n
                                tileNames.add(dest.name)
                                onProgress(tileNames.size)
                                if (written > MAX_IMPORT_BYTES) {
                                    return ExtractResult(tileNames,
                                        "Stopped at ${written / (1024 * 1024)} MB — the archive is too large")
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return ExtractResult(emptyList(), "Could not open zip")
        } catch (t: Throwable) {
            AppLog.w(TAG, "DTED zip import failed: ${t.message}")
            return ExtractResult(tileNames, "Zip import failed: ${t.message}")
        }
        return ExtractResult(tileNames)
    }

    /** Copies at most [limit] bytes, then stops. Returns what was written. A tile past the
     *  limit is truncated rather than allowed to run away; [DtedTile.open] rejects it on the
     *  next load, which is the correct outcome for a file that was never a real tile. */
    private fun copyAtMost(input: java.io.InputStream, out: java.io.OutputStream, limit: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return total
    }

    /**
     * Writes one tile via a temporary file and a rename, so [dest] only ever exists as a whole
     * tile.
     *
     * Tiles used to be streamed straight onto the pooled filename. The pool is SHARED and an
     * identical name is deliberately overwritten ("newest import wins"), so a copy that died
     * part-way — the process being killed for an ANR was the likeliest cause — left a truncated
     * file where a region that is still marked `complete` expects a good tile. The pending ->
     * complete row protocol cannot catch that: the damaged tile belongs to the OLD region,
     * whose row is correct. A rename is atomic on this filesystem, so a failed import now
     * leaves the previous tile exactly as it was.
     */
    private fun writeTileAtomically(dest: File, write: (java.io.OutputStream) -> Long): Long {
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            val n = tmp.outputStream().use { out -> write(out) }
            if (!tmp.renameTo(dest)) {
                // Same directory, so this should not happen; fall back rather than lose the
                // tile, accepting the non-atomic window in the rare case.
                tmp.copyTo(dest, overwrite = true)
            }
            return n
        } finally {
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }

    /** Deletes a region: removes its DB row and any tile file that only it referenced, leaving
     *  tiles still used by another region intact. */
    fun deleteRegion(context: Context, region: Region): Boolean {
        val dao = TerrainDatabase.get(context).terrainDao()
        val orphans = dao.deleteImportAndReturnOrphans(region.id)
        val pool = dir(context)
        for (name in orphans) runCatching { File(pool, name).delete() }
        AppLog.i(TAG, "deleted region \"${region.name}\" (#${region.id}): ${orphans.size} tile(s) freed")
        DtedIndex.invalidate()
        return true
    }
}
