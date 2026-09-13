package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #175 — coverage of [DefaultIntrinsicMediaSizeCache]: measured-metadata storage and FIFO eviction.
 * Failure memoization moved OUT of this cache with #960 — the TTL/retry/generation contract of
 * dead URLs is the [MediaAttemptLedger]'s and is pinned by [MediaAttemptLedgerTest].
 *
 * Content geometry and MIME merging belong to [MediaGeometryLedgerTest]. This bounded cache
 * only memoizes accepted metadata; a cache replacement is not a geometry decision.
 */
class IntrinsicMediaSizeCacheTest {

    private fun metadata(width: Int, height: Int, mimeType: String? = null) =
        IntrinsicMediaMetadata(IntSize(width, height), mimeType)

    @Test
    fun `success is stored and returned by url - size and mime atomically`() {
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccess("u", metadata(70, 50, "image/gif"))
        assertEquals(metadata(70, 50, "image/gif"), cache.get("u"))
    }

    @Test
    fun `unmeasured url returns null`() {
        assertNull(DefaultIntrinsicMediaSizeCache().get("never"))
    }

    @Test
    fun `putSuccessIfAbsent deposits on a miss and reports it`() {
        val cache = DefaultIntrinsicMediaSizeCache()
        assertTrue(cache.putSuccessIfAbsent("u", metadata(320, 240)))
        assertEquals(metadata(320, 240), cache.get("u"))
    }

    @Test
    fun `putSuccessIfAbsent leaves an existing memo untouched`() {
        // The legacy smiley memo operation remains available; it does not own content geometry.
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccess("u", metadata(800, 600))
        assertFalse(cache.putSuccessIfAbsent("u", metadata(320, 240)))
        assertEquals(metadata(800, 600), cache.get("u"))
    }

    @Test
    fun `memo can reflect the ledger mime enrichment in one replacement`() {
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccess("u", metadata(320, 240))
        cache.putSuccess("u", metadata(320, 240, "image/gif"))
        assertEquals(metadata(320, 240, "image/gif"), cache.get("u"))
    }

    @Test
    fun `FIFO eviction drops the oldest entry past the bound`() {
        val cache = DefaultIntrinsicMediaSizeCache(maxEntries = 2)
        cache.putSuccess("a", metadata(1, 1))
        cache.putSuccess("b", metadata(2, 2))
        cache.putSuccess("c", metadata(3, 3)) // evicts "a" (oldest)
        assertNull(cache.get("a"))
        assertEquals(metadata(2, 2), cache.get("b"))
        assertEquals(metadata(3, 3), cache.get("c"))
    }

    @Test
    fun `re-putting an existing key does not grow the insertion order (no premature eviction)`() {
        val cache = DefaultIntrinsicMediaSizeCache(maxEntries = 2)
        cache.putSuccess("a", metadata(1, 1))
        cache.putSuccess("b", metadata(2, 2))
        cache.putSuccess("a", metadata(9, 9)) // update, not a new slot
        assertEquals(metadata(9, 9), cache.get("a"))
        assertEquals(metadata(2, 2), cache.get("b"))
    }
}
