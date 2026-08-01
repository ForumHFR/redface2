package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #175 — coverage of [DefaultIntrinsicMediaSizeCache], in particular the **failure memoization**
 * (a dead URL must not be re-fetched on every recomposition) and FIFO eviction.
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
    fun `failure is fresh within the TTL and stale after it`() {
        val cache = DefaultIntrinsicMediaSizeCache(failureTtlMillis = 1_000L)
        cache.putFailure("dead", nowMillis = 10_000L)
        assertTrue(cache.isFailureFresh("dead", nowMillis = 10_500L)) // 500ms < 1000ms TTL
        assertFalse(cache.isFailureFresh("dead", nowMillis = 11_500L)) // 1500ms > TTL → retry allowed
    }

    @Test
    fun `a recorded failure does not masquerade as a success`() {
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putFailure("dead", nowMillis = 0L)
        assertNull(cache.get("dead"))
    }

    @Test
    fun `a later success overrides an earlier failure (transient outage recovered)`() {
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putFailure("u", nowMillis = 0L)
        cache.putSuccess("u", IntSize(16, 16))
        assertEquals(IntSize(16, 16), cache.get("u"))
        assertFalse(cache.isFailureFresh("u", nowMillis = 0L))
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

    @Test
    fun `clearFailures drops failures but preserves successes (813 refresh contract)`() {
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccess("ok", IntSize(70, 50))
        cache.putFailure("ghost", nowMillis = 0L)
        cache.clearFailures()
        assertEquals(IntSize(70, 50), cache.get("ok"))
        assertFalse("a cleared failure must not stay fresh", cache.isFailureFresh("ghost", nowMillis = 0L))
    }

    @Test
    fun `clearFailures frees the failed url's insertion slot (no ghost eviction)`() {
        val cache = DefaultIntrinsicMediaSizeCache(maxEntries = 2)
        cache.putSuccess("a", IntSize(1, 1))
        cache.putFailure("ghost", nowMillis = 0L)
        cache.clearFailures()
        // Would evict "a" if "ghost" still occupied a slot in the insertion order.
        cache.putSuccess("b", IntSize(2, 2))
        assertEquals(IntSize(1, 1), cache.get("a"))
        assertEquals(IntSize(2, 2), cache.get("b"))
    }

    @Test
    fun `a failure recorded after clearFailures is fresh again (TTL restarts on the new failure)`() {
        val cache = DefaultIntrinsicMediaSizeCache(failureTtlMillis = 1_000L)
        cache.putFailure("ghost", nowMillis = 0L)
        cache.clearFailures()
        cache.putFailure("ghost", nowMillis = 5_000L)
        assertTrue(cache.isFailureFresh("ghost", nowMillis = 5_500L))
    }
}
