package fr.forumhfr.redface2.feature.topic

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #884 — pure contract of the topic-list geometry helpers (vague 3). The Sol framing: in full-width
 * mode the ZERO gutter / ZERO gap applies ONLY between posts — the list drops its side gutters and
 * its inter-item gap, and each non-post island re-inserts its own 8/4.dp breathing room locally.
 * The card mode values are the historical ones (#398 gutters, #287 rhythm) and must never drift.
 */
class TopicListLayoutTest {

    @Test
    fun `full width drops the side gutters and keeps the vertical insets`() {
        val padding = topicListContentPadding(fullWidthPosts = true)
        assertEquals(0.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(16.dp, padding.calculateTopPadding())
        assertEquals(88.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `card mode keeps the historical 8-16-8-88 insets`() {
        val padding = topicListContentPadding(fullWidthPosts = false)
        assertEquals(8.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(8.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(16.dp, padding.calculateTopPadding())
        assertEquals(88.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `full width removes the inter-item gap, card mode keeps the 8dp rhythm`() {
        assertEquals(0.dp, topicListArrangement(fullWidthPosts = true).spacing)
        assertEquals(8.dp, topicListArrangement(fullWidthPosts = false).spacing)
    }

    @Test
    fun `island padding is identity in card mode`() {
        // In card mode the list gutter + gap already give the islands their breathing room: the
        // modifier chain must be returned UNTOUCHED (no zero-padding node inserted on every item).
        assertSame(Modifier, Modifier.islandPadding(fullWidthPosts = false))
    }

    // ----- #983 : who closes a post's bottom edge, and who owns the separator's rhythm -----

    @Test
    fun `full width draws the hairline only between two ordinary posts`() {
        // The #884 hairline was unconditional, so it stacked on top of whatever boundary the next
        // element brought (separator rules, island borders) — the reported double trait.
        assertTrue(topicPostRequestsBottomHairline(true, TopicFollowingKind.POST))
        assertFalse(topicPostRequestsBottomHairline(true, TopicFollowingKind.NON_POST))
        // A trailing post leaves no dangling rule hanging above the 88.dp bottom inset.
        assertFalse(topicPostRequestsBottomHairline(true, TopicFollowingKind.NONE))
    }

    @Test
    fun `card mode always returns the shell default, whatever follows`() {
        // Card mode must not even reach a new decision: the shell ignores the value when not flat,
        // and this keeps the historical call path provably unchanged.
        TopicFollowingKind.entries.forEach { kind ->
            assertTrue(topicPostRequestsBottomHairline(false, kind))
        }
    }

    @Test
    fun `separator padding is identity in card mode`() {
        // Same rule as islandPadding: the list's own gutter and 8.dp rhythm already place the
        // marker there, so no zero-padding node is inserted.
        assertSame(Modifier, Modifier.separatorPadding(fullWidthPosts = false))
    }

    @Test
    fun `the post item inserts no gap in full width and keeps the 8dp rhythm in card mode`() {
        // #983 — the asymmetry was the item's own spacedBy(8.dp) above the marker versus the list's
        // Arrangement.Top (0.dp) below it. In full-width NO container adds a gap; the marker owns
        // its two 4.dp half-gaps itself.
        assertEquals(0.dp, topicPostChildrenArrangement(fullWidthPosts = true).spacing)
        assertEquals(8.dp, topicPostChildrenArrangement(fullWidthPosts = false).spacing)
    }
}
