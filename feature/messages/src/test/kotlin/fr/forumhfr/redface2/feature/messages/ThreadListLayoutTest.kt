package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1046 — pure contract of the MP thread-list geometry (the `TopicListLayoutTest` pattern): the
 * historical 16.dp frame and 12.dp rhythm (#298) must never drift, and the bottom inset is the
 * 88.dp « Répondre »-FAB clearance (#301) — NOT a symmetric 16.dp, which parked the last message
 * and the pager row under the FAB. The mounted proof that the clearance actually clears the FAB
 * lives in [ThreadFabClearanceTest]; this test pins the exact values against the next visual tweak.
 */
class ThreadListLayoutTest {

    @Test
    fun `thread list keeps the 16dp frame and reserves the 88dp FAB clearance`() {
        val padding = threadListContentPadding()
        assertEquals(16.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(16.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(16.dp, padding.calculateTopPadding())
        // Same value as the topic's #283 bottom-cluster clearance: both reading surfaces reserve
        // the same room under the last item for the chrome floated over them.
        assertEquals(88.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `thread list keeps the historical 12dp rhythm`() {
        assertEquals(12.dp, threadListArrangement().spacing)
    }
}
