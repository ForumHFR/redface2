package fr.forumhfr.redface2.feature.topic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1137 — pure JVM coverage of [lastReadLandingOffset], the alignment decision of a flag-tap
 * landing (last-read post + « Dernier message lu » marker). The `LazyListState` snap that applies
 * the offset and the per-frame re-decision inside `reanchorWhileMediaSettles` are not JVM-testable ;
 * the contract they rely on is pinned here : a post that fits keeps the historical top-of-post
 * landing, an overflowing post rests on its marker, and unmeasured or absurd geometry never moves
 * the list.
 */
class LastReadLandingOffsetTest {

    private val viewport = 1800
    private val marker = 72

    @Test
    fun `an item shorter than the viewport keeps the top-of-post landing`() {
        assertEquals(0, lastReadLandingOffset(itemHeight = 900, viewportHeight = viewport, markerHeight = marker))
    }

    @Test
    fun `an item exactly as tall as the viewport still fits`() {
        assertEquals(0, lastReadLandingOffset(itemHeight = viewport, viewportHeight = viewport, markerHeight = marker))
    }

    @Test
    fun `an item one pixel taller than the viewport rests on its marker`() {
        val itemHeight = viewport + 1
        assertEquals(
            itemHeight - marker,
            lastReadLandingOffset(itemHeight = itemHeight, viewportHeight = viewport, markerHeight = marker),
        )
    }

    @Test
    fun `an overflowing item is pushed up by its height minus the marker`() {
        // A three-screen post : the offset leaves exactly the marker (the item's last 72 px) on
        // screen, the first unread post right below it.
        val itemHeight = 3 * viewport
        assertEquals(
            itemHeight - marker,
            lastReadLandingOffset(itemHeight = itemHeight, viewportHeight = viewport, markerHeight = marker),
        )
    }

    @Test
    fun `the offset follows the item as it grows between two frames`() {
        // Cold cache : the post inflates by one decoded image ; the re-anchor loop re-decides and
        // the goal moves by the same amount — a frozen first-frame offset would let the marker drift.
        val before = lastReadLandingOffset(itemHeight = 2000, viewportHeight = viewport, markerHeight = marker)
        val after = lastReadLandingOffset(itemHeight = 2000 + 480, viewportHeight = viewport, markerHeight = marker)
        assertEquals(480, after - before)
    }

    @Test
    fun `an unmeasured marker degrades to the first unread post at the top`() {
        val itemHeight = 2 * viewport
        assertEquals(
            "marker height 0 → the whole item scrolls off, nothing negative or clamped",
            itemHeight,
            lastReadLandingOffset(itemHeight = itemHeight, viewportHeight = viewport, markerHeight = 0),
        )
    }

    @Test
    fun `a negative marker height is treated as unmeasured`() {
        val itemHeight = 2 * viewport
        assertEquals(
            itemHeight,
            lastReadLandingOffset(itemHeight = itemHeight, viewportHeight = viewport, markerHeight = -5),
        )
    }

    @Test
    fun `a marker at least as tall as its item never yields a negative offset`() {
        assertEquals(0, lastReadLandingOffset(itemHeight = 2000, viewportHeight = viewport, markerHeight = 2000))
        assertEquals(0, lastReadLandingOffset(itemHeight = 2000, viewportHeight = viewport, markerHeight = 5000))
    }

    @Test
    fun `an unmeasured item never moves the list`() {
        assertEquals(0, lastReadLandingOffset(itemHeight = 0, viewportHeight = viewport, markerHeight = marker))
        assertEquals(0, lastReadLandingOffset(itemHeight = -1, viewportHeight = viewport, markerHeight = marker))
    }

    @Test
    fun `an unmeasured viewport never moves the list`() {
        assertEquals(0, lastReadLandingOffset(itemHeight = 2000, viewportHeight = 0, markerHeight = marker))
        assertEquals(0, lastReadLandingOffset(itemHeight = 2000, viewportHeight = -1, markerHeight = marker))
    }
}
