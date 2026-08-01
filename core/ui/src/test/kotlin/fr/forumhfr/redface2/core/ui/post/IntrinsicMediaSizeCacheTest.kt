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
 * #973 ([AMENDEMENT-v1.5-2]): the entry is the ATOMIC [IntrinsicMediaMetadata] — size + probe
 * MIME deposited in one write. The first-deposit authority now also pins the MIME: no late
 * reclassification, in either direction, once an entry landed.
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
    fun `putSuccessIfAbsent never overwrites the first valid pair`() {
        // §3/§6 (G2): the FIRST valid oriented pair fixes the box — no second correction when
        // the other source (probe vs painter) later disagrees.
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccess("u", metadata(800, 600))
        assertFalse(cache.putSuccessIfAbsent("u", metadata(320, 240)))
        assertEquals(metadata(800, 600), cache.get("u"))
    }

    @Test
    fun `a mime-less late deposit never erases the probe's mime (no late reclassification)`() {
        // #973: the probe landed first with `image/gif`; a later painter G2 deposit (which never
        // carries a MIME) must not strip it — the FIRST valid metadata is authoritative as a
        // WHOLE, there is no field-level patching.
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccessIfAbsent("u", metadata(320, 240, "image/gif"))
        assertFalse(cache.putSuccessIfAbsent("u", metadata(320, 240, mimeType = null)))
        assertEquals(metadata(320, 240, "image/gif"), cache.get("u"))
    }

    @Test
    fun `a late deposit carrying a mime never reclassifies a mime-less entry`() {
        // #973: the painter fixed the entry first (no MIME); a probe landing later must not
        // promote the entry to `image/gif` after the box is fixed — « AUCUN reclassement tardif ».
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccessIfAbsent("u", metadata(320, 240, mimeType = null))
        assertFalse(cache.putSuccessIfAbsent("u", metadata(320, 240, "image/gif")))
        assertEquals(metadata(320, 240, mimeType = null), cache.get("u"))
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
