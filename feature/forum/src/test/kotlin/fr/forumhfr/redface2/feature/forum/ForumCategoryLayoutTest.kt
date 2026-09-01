package fr.forumhfr.redface2.feature.forum

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.TopicSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure contracts for the category-list layout helpers:
 * - #1131: the 88.dp bottom clearance exists only when the create-topic FAB is rendered;
 * - #1129: sticky/regular partitioning is stable, the boundary requires both sections, and the
 *   partition/separator is scoped to real category listings (never flag-filter buckets).
 *
 * Rendering note (#1129): the only Compose/Robolectric harness in `:feature:forum` is the #1149
 * inset proof (`ForumCategoryContentInsetsTest`, mounting `ForumCategoryContent`). Downstream UI
 * validation must still cover one separator at the sticky/regular boundary, no separator for empty or
 * single-kind results (including after search), no ordinary divider immediately before the
 * separator, the tonal status badge appearing before the title for sticky and/or locked rows, and
 * a FLAT list (no partition, no separator; per-row badge still shown) whenever a flag-filter bucket
 * (Participé/Lus/Favoris) is active.
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
    fun `shows the sticky boundary only when both sections are non-empty`() {
        val sticky = topic(topicId = 1, isSticky = true)
        val regular = topic(topicId = 2, isSticky = false)

        assertTrue(TopicSections(sticky = listOf(sticky), regular = listOf(regular)).hasStickyBoundary)
        assertFalse(TopicSections(sticky = emptyList(), regular = listOf(regular)).hasStickyBoundary)
        assertFalse(TopicSections(sticky = listOf(sticky), regular = emptyList()).hasStickyBoundary)
        assertFalse(TopicSections(sticky = emptyList(), regular = emptyList()).hasStickyBoundary)
    }

    @Test
    fun `scopes the sticky boundary to category listings and never to flag-filter buckets`() {
        val bothSections = TopicSections(
            sticky = listOf(topic(topicId = 1, isSticky = true)),
            regular = listOf(topic(topicId = 2, isSticky = false)),
        )

        // Real category listing: the structural boundary is honoured.
        assertTrue(bothSections.shouldShowStickyBoundary(filterActive = false))
        // Flag-filter bucket (Participé/Lus/Favoris): stay flat even with both sections present.
        assertFalse(bothSections.shouldShowStickyBoundary(filterActive = true))
        // A structurally-absent boundary is never shown, filter mode or not.
        val onlyRegular = TopicSections(sticky = emptyList(), regular = bothSections.regular)
        assertFalse(onlyRegular.shouldShowStickyBoundary(filterActive = false))
        assertFalse(onlyRegular.shouldShowStickyBoundary(filterActive = true))
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
