package fr.forumhfr.redface2.feature.forum

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.TopicSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure contracts for #1131 FAB clearance and #1303 page-local sticky partition/header.
 * Mounted rendering, ordering and collapse assertions live in [ForumCategoryCollapseTest];
 * [ForumCategoryContentInsetsTest] retains the #1149 inset geometry proof.
 */
class ForumCategoryLayoutTest {

    @Test
    fun `reserves the FAB clearance at the bottom when the FAB is shown`() {
        val padding = forumListContentPadding(reserveFabSpace = true)
        assertEquals(88.dp, padding.calculateBottomPadding())
        assertEquals(0.dp, padding.calculateTopPadding())
        assertEquals(0.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
    }

    @Test
    fun `reserves nothing when the FAB is absent`() {
        val padding = forumListContentPadding(reserveFabSpace = false)
        assertEquals(0.dp, padding.calculateBottomPadding())
        assertEquals(0.dp, padding.calculateTopPadding())
        assertEquals(0.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
    }

    @Test
    fun `partitions interleaved topics into stable sticky and regular sections`() {
        val regularOne = topic(topicId = 1, isSticky = false)
        val stickyOne = topic(topicId = 2, isSticky = true)
        val lockedRegular = topic(topicId = 3, isSticky = false, isLocked = true)
        val stickyTwo = topic(topicId = 4, isSticky = true)

        val sections = listOf(regularOne, stickyOne, lockedRegular, stickyTwo).toTopicSections()

        assertEquals(listOf(stickyOne, stickyTwo), sections.sticky)
        assertEquals(listOf(regularOne, lockedRegular), sections.regular)
    }

    @Test
    fun `shows a sticky header even when the page contains only sticky topics`() {
        val sticky = topic(topicId = 1, isSticky = true)
        val regular = topic(topicId = 2, isSticky = false)

        assertTrue(
            TopicSections(sticky = listOf(sticky), regular = listOf(regular))
                .shouldShowStickyHeader(filterActive = false),
        )
        assertFalse(
            TopicSections(sticky = emptyList(), regular = listOf(regular))
                .shouldShowStickyHeader(filterActive = false),
        )
        assertTrue(
            TopicSections(sticky = listOf(sticky), regular = emptyList()).shouldShowStickyHeader(filterActive = false),
        )
        assertFalse(
            TopicSections(sticky = emptyList(), regular = emptyList()).shouldShowStickyHeader(filterActive = false),
        )
    }

    @Test
    fun `scopes the sticky header to category listings and never to flag-filter buckets`() {
        val bothSections = TopicSections(
            sticky = listOf(topic(topicId = 1, isSticky = true)),
            regular = listOf(topic(topicId = 2, isSticky = false)),
        )

        // Real category listing: the structural boundary is honoured.
        assertTrue(bothSections.shouldShowStickyHeader(filterActive = false))
        // Flag-filter bucket (Participé/Lus/Favoris): stay flat even with both sections present.
        assertFalse(bothSections.shouldShowStickyHeader(filterActive = true))
        // A structurally-absent boundary is never shown, filter mode or not.
        val onlyRegular = TopicSections(sticky = emptyList(), regular = bothSections.regular)
        assertFalse(onlyRegular.shouldShowStickyHeader(filterActive = false))
        assertFalse(onlyRegular.shouldShowStickyHeader(filterActive = true))
    }

    private fun topic(
        topicId: Int,
        isSticky: Boolean,
        isLocked: Boolean = false,
    ): TopicSummary = TopicSummary(
        cat = 23,
        subcat = null,
        topicId = topicId,
        title = "Topic $topicId",
        author = "author",
        lastReplyAuthor = "last author",
        lastReplyAt = "",
        replyCount = 0,
        totalPages = 1,
        isSticky = isSticky,
        isLocked = isLocked,
        hasUnread = null,
        lastReadPage = null,
        lastPostReadId = null,
        flagType = null,
    )
}
