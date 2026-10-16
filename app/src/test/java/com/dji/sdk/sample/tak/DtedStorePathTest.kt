package com.dji.sdk.sample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tile-name resolver is a SECURITY CONTROL, not a formatting helper, so it is pinned
 * the same way `OutboundLogRedactionTest` pins the outbound-log redaction.
 *
 * The defect: `DtedStore.importSingleFile` built `File(pool, displayName)` straight from the SAF
 * display name — chosen by whoever authored the file — so a name containing a separator wrote
 * outside the tile pool. The zip path had always flattened separators; this one never did. Both
 * go through `poolFile` now, and these cases are what "safe" has to mean for it.
 */
class DtedStorePathTest {

    private fun pool(): File =
        File(System.getProperty("java.io.tmpdir"), "dted-pool-test").apply {
            deleteRecursively(); mkdirs()
        }

    @Test
    fun separatorsAreFlattenedRatherThanFollowed() {
        val pool = pool()
        // The documented zip behaviour: a nested entry becomes one pooled file.
        val f = DtedStore.poolFile(pool, "w101/n45.dt2")
        assertNotNull(f)
        assertEquals("w101_n45.dt2", f!!.name)
        assertEquals(pool.canonicalPath, f.parentFile!!.canonicalPath)
    }

    /**
     * The actual contract: whatever comes in, the result is either refused (null) or lands
     * DIRECTLY in the pool. It is deliberately not "hostile input is always refused" — "/"
     * flattens to the harmless name "_", which is safe, and which `isTileName` rejects at both
     * call sites long before anything is written. Asserting refusal there would be pinning an
     * assumption rather than the property that matters.
     */
    @Test
    fun traversalCannotEscapeThePool() {
        val pool = pool()
        val hostile = listOf(
            "../evil.dt2",
            "../../evil.dt2",
            "/etc/passwd.dt2",
            "..\\evil.dt2",
            "sub/../../evil.dt2",
            "....//evil.dt2",
            "/",
            "..",
        )
        for (name in hostile) {
            val f = DtedStore.poolFile(pool, name) ?: continue
            assertTrue(
                "\"$name\" resolved outside the pool: ${f.canonicalPath}",
                f.canonicalPath.startsWith(pool.canonicalPath + File.separator),
            )
            // And directly in the pool — never in a subdirectory it invented on the way.
            assertEquals(pool.canonicalPath, f.parentFile!!.canonicalPath)
        }
    }

    @Test
    fun namesThatReduceToNothingAreRefused() {
        val pool = pool()
        assertNull(DtedStore.poolFile(pool, ""))
        assertNull(DtedStore.poolFile(pool, "   "))
        assertNull(DtedStore.poolFile(pool, "..."))
    }

    @Test
    fun anOrdinaryTileNameIsUntouched() {
        val pool = pool()
        val f = DtedStore.poolFile(pool, "n45_w101.dt2")
        assertNotNull(f)
        assertEquals("n45_w101.dt2", f!!.name)
    }
}
