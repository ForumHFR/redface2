package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #175 — coverage of [DefaultIntrinsicMediaSizeCache]: measured-size storage and FIFO eviction.
 * Failure memoization moved OUT of this cache with #960 — the TTL/retry/generation contract of
 * dead URLs is the [MediaAttemptLedger]'s and is pinned by [MediaAttemptLedgerTest].
 */
class IntrinsicMediaSizeCacheTest {

    @Test
    fun `success is stored and returned by url`() {
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccess("u", IntSize(70, 50))
        assertEquals(IntSize(70, 50), cache.get("u"))
    }

    @Test
    fun `unmeasured url returns null`() {
        assertNull(DefaultIntrinsicMediaSizeCache().get("never"))
    }

    @Test
    fun `FIFO eviction drops the oldest entry past the bound`() {
        val cache = DefaultIntrinsicMediaSizeCache(maxEntries = 2)
        cache.putSuccess("a", IntSize(1, 1))
        cache.putSuccess("b", IntSize(2, 2))
        cache.putSuccess("c", IntSize(3, 3)) // evicts "a" (oldest)
        assertNull(cache.get("a"))
        assertEquals(IntSize(2, 2), cache.get("b"))
        assertEquals(IntSize(3, 3), cache.get("c"))
    }

    @Test
    fun `re-putting an existing key does not grow the insertion order (no premature eviction)`() {
        val cache = DefaultIntrinsicMediaSizeCache(maxEntries = 2)
        cache.putSuccess("a", IntSize(1, 1))
        cache.putSuccess("b", IntSize(2, 2))
        cache.putSuccess("a", IntSize(9, 9)) // update, not a new slot
        assertEquals(IntSize(9, 9), cache.get("a"))
        assertEquals(IntSize(2, 2), cache.get("b"))
    }
}
