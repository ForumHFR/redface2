package fr.forumhfr.redface2.feature.topic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #394 — pure resolution of the deleted-anchor fallback: which surviving post to land on when the
 * route's « dernier lu » anchor was deleted on HFR and is absent from the fetched page.
 */
class DeletedAnchorFallbackTest {

    @Test
    fun `lands on the first surviving post after the deleted anchor`() {
        // The post chronologically just after the deleted anchor is what the user reads next.
        assertEquals(1001, resolveDeletedAnchorFallback(listOf(990, 1001, 1010), target = 999))
    }

    @Test
    fun `lands on the next post even when the anchor sits between two surviving posts`() {
        assertEquals(50, resolveDeletedAnchorFallback(listOf(10, 20, 50, 80), target = 30))
    }

    @Test
    fun `falls back to the last post when the deleted anchor is newer than every surviving post`() {
        // The deleted post sat at or past the tail of what we fetched: the closest surviving
        // neighbour is the page's last post, never a top-of-page drop.
        assertEquals(200, resolveDeletedAnchorFallback(listOf(100, 200), target = 999))
    }

    @Test
    fun `lands on the only surviving post`() {
        assertEquals(42, resolveDeletedAnchorFallback(listOf(42), target = 999))
        assertEquals(42, resolveDeletedAnchorFallback(listOf(42), target = 1))
    }

    @Test
    fun `returns null for an empty page so the caller keeps the top landing`() {
        assertNull(resolveDeletedAnchorFallback(emptyList(), target = 999))
    }
}
