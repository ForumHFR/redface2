package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.post.PostCardShellFlatBottomEdge
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1046/#1050 — pure contract of the MP thread-list geometry (the `TopicListLayoutTest` pattern):
 * card mode keeps the historical 16.dp frame and 12.dp rhythm (#298), full-width removes only the
 * side gutters/rhythm, and both keep the 16/88.dp top/FAB insets. The mounted proof that the 88.dp
 * clearance actually clears the FAB lives in [ThreadFabClearanceTest].
 */
class ThreadListLayoutTest {

    @Test
    fun `thread list keeps the 16dp frame and reserves the 88dp FAB clearance`() {
        val padding = threadListContentPadding(fullWidthPosts = false)
        assertEquals(16.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(16.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(16.dp, padding.calculateTopPadding())
        // Same value as the topic's #283 bottom-cluster clearance: both reading surfaces reserve
        // the same room under the last item for the chrome floated over them.
        assertEquals(88.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `thread list keeps the historical 12dp rhythm`() {
        assertEquals(12.dp, threadListArrangement(fullWidthPosts = false).spacing)
    }

    @Test
    fun `full width drops side gutters and rhythm but keeps top and FAB insets`() {
        val padding = threadListContentPadding(fullWidthPosts = true)
        assertEquals(0.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(16.dp, padding.calculateTopPadding())
        assertEquals(88.dp, padding.calculateBottomPadding())
        assertEquals(0.dp, threadListArrangement(fullWidthPosts = true).spacing)
    }

    @Test
    fun `flat hairline exists only when another message follows`() {
        assertEquals(
            PostCardShellFlatBottomEdge.HAIRLINE,
            threadMessageFlatBottomEdge(fullWidthPosts = true, hasFollowingMessage = true),
        )
        assertEquals(
            PostCardShellFlatBottomEdge.NONE,
            threadMessageFlatBottomEdge(fullWidthPosts = true, hasFollowingMessage = false),
        )
        assertEquals(
            PostCardShellFlatBottomEdge.HAIRLINE,
            threadMessageFlatBottomEdge(fullWidthPosts = false, hasFollowingMessage = false),
        )
    }
}
