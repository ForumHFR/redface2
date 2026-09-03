package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 100% coverage of the pure [groupFlagsByCategory] grouping/sort (#179). No Android dependency:
 * the function takes raw [Flag] + [FlagCategoryOrderEntry] and returns [FlagCategorySection].
 */
class FlagCategorySectionTest {

    private val order = listOf(
        FlagCategoryOrderEntry(1, "Hardware"),
        FlagCategoryOrderEntry(10, "Programmation"),
        FlagCategoryOrderEntry(13, "Discussions"),
    )

    @Test
    fun `sections follow the canonical order, not the flags arrival order`() {
        // Flags arrive in 13, 1, 10 order; sections must come out 1, 10, 13.
        val flags = listOf(
            flag(topicId = 100, cat = 13),
            flag(topicId = 200, cat = 1),
            flag(topicId = 300, cat = 10),
        )

        val sections = groupFlagsByCategory(flags, order)

        assertEquals(listOf(1, 10, 13), sections.map { it.catId })
        assertEquals(listOf("Hardware", "Programmation", "Discussions"), sections.map { it.catName })
    }

    @Test
    fun `a category with no flags is kept as an empty section (web parity)`() {
        val flags = listOf(flag(topicId = 1, cat = 1))

        val sections = groupFlagsByCategory(flags, order)

        assertEquals(listOf(1, 10, 13), sections.map { it.catId })
        assertEquals(listOf(1), sections.first { it.catId == 1 }.topics.map { it.topicId })
        assertTrue(sections.first { it.catId == 10 }.topics.isEmpty())
        assertTrue(sections.first { it.catId == 13 }.topics.isEmpty())
    }

    @Test
    fun `a flag in an unknown category is never dropped (anti-regression #251)`() {
        val flags = listOf(
            flag(topicId = 1, cat = 1),
            flag(topicId = 2, cat = 999), // not in the order catalogue
        )

        val sections = groupFlagsByCategory(flags, order)

        // Unknown section is appended at the end, after all known categories.
        assertEquals(listOf(1, 10, 13, 999), sections.map { it.catId })
        val unknown = sections.last()
        assertEquals(999, unknown.catId)
        assertNull("unknown category carries a null name for Compose fallback", unknown.catName)
        assertEquals(listOf(2), unknown.topics.map { it.topicId })
    }

    @Test
    fun `multiple unknown categories are sorted by catId after the known ones`() {
        val flags = listOf(
            flag(topicId = 1, cat = 50),
            flag(topicId = 2, cat = 10),
            flag(topicId = 3, cat = 40),
        )

        val sections = groupFlagsByCategory(flags, order)

        // Known order first (1, 10, 13), then unknowns ascending (40, 50).
        assertEquals(listOf(1, 10, 13, 40, 50), sections.map { it.catId })
        val unknowns = sections.filter { it.catName == null }
        assertEquals(listOf(40, 50), unknowns.map { it.catId })
        assertTrue("every unknown section has a null name", unknowns.all { it.catName == null })
    }

    @Test
    fun `internal order preserves the input order of flags within a category`() {
        // The repository already sorts globally by lastReplyAt desc; grouping must be stable.
        val flags = listOf(
            flag(topicId = 30, cat = 1),
            flag(topicId = 10, cat = 1),
            flag(topicId = 20, cat = 1),
        )

        val sections = groupFlagsByCategory(flags, order)

        assertEquals(
            "grouping must preserve input order (stable), not re-sort by topicId",
            listOf(30, 10, 20),
            sections.first { it.catId == 1 }.topics.map { it.topicId },
        )
    }

    @Test
    fun `empty flags with a non-empty catalogue yields all known sections empty`() {
        val sections = groupFlagsByCategory(emptyList(), order)

        assertEquals(listOf(1, 10, 13), sections.map { it.catId })
        assertTrue("every section is empty when there are no flags", sections.all { it.topics.isEmpty() })
    }

    @Test
    fun `empty flags and empty catalogue yields an empty section list`() {
        val sections = groupFlagsByCategory(emptyList(), emptyList())

        assertTrue(sections.isEmpty())
    }

    @Test
    fun `one flag per category yields one section per category with no duplicates`() {
        val flags = listOf(
            flag(topicId = 1, cat = 1),
            flag(topicId = 2, cat = 10),
            flag(topicId = 3, cat = 13),
        )

        val sections = groupFlagsByCategory(flags, order)

        assertEquals(3, sections.size)
        assertEquals(listOf(1, 10, 13), sections.map { it.catId })
        assertTrue("no duplicated catId", sections.map { it.catId }.distinct().size == sections.size)
        sections.forEach { assertEquals(1, it.topics.size) }
    }

    @Test
    fun `groupFlagsByCategory dedups duplicate category ids in the catalogue order`() {
        // A corrupt catalogue with two entries sharing the same id must not yield two sections
        // with the same catId (duplicate LazyColumn keys → runtime crash). First occurrence wins.
        val duplicatedOrder = listOf(
            FlagCategoryOrderEntry(1, "Hardware"),
            FlagCategoryOrderEntry(1, "Hardware (doublon)"),
            FlagCategoryOrderEntry(10, "Programmation"),
        )

        val sections = groupFlagsByCategory(listOf(flag(topicId = 1, cat = 1)), duplicatedOrder)

        assertEquals(listOf(1, 10), sections.map { it.catId })
        assertEquals("first occurrence wins for the label", "Hardware", sections.first().catName)
    }

    @Test
    fun `filterCategoriesWithUnread keeps unread sections and drops empty plus fully-read ones`() {
        val sections = listOf(
            FlagCategorySection(1, "A", listOf(row(topicId = 1, cat = 1, hasUnread = true))),
            FlagCategorySection(10, "B", listOf(row(topicId = 2, cat = 10, hasUnread = false))),
            FlagCategorySection(13, "C", emptyList()),
        )

        val filtered = filterCategoriesWithUnread(sections, keepFullyRead = false)

        assertEquals("only the category with an unread survives", listOf(1), filtered.map { it.catId })
    }

    @Test
    fun `filterCategoriesWithUnread with keepFullyRead keeps read sections but still drops empty ones`() {
        // The cyan « +lus » override: a fully-read section is kept, but a truly empty section is
        // always dropped (nothing to show).
        val sections = listOf(
            FlagCategorySection(1, "A", listOf(row(topicId = 1, cat = 1, hasUnread = false))),
            FlagCategorySection(10, "B", emptyList()),
        )

        val filtered = filterCategoriesWithUnread(sections, keepFullyRead = true)

        assertEquals(listOf(1), filtered.map { it.catId })
    }

    @Test
    fun `the hard-coded fallback order has the 19 public categories in canonical sequence`() {
        // Pins FALLBACK_CATEGORY_ORDER against the documented HFR web layout (impl prompt §5).
        assertEquals(19, FALLBACK_CATEGORY_ORDER.size)
        assertEquals(
            listOf(1, 16, 15, 2, 30, 23, 25, 3, 14, 5, 4, 22, 21, 11, 10, 12, 6, 8, 13),
            FALLBACK_CATEGORY_ORDER.map { it.id },
        )
        assertEquals("Hardware", FALLBACK_CATEGORY_ORDER.first().name)
        assertEquals("Discussions", FALLBACK_CATEGORY_ORDER.last().name)
    }

    private fun flag(
        topicId: Int,
        cat: Int,
        hasUnread: Boolean = true,
    ): Flag = Flag(
        cat = cat,
        subcat = null,
        topicId = topicId,
        title = "Topic $topicId",
        totalPages = 1,
        replyCount = 0,
        type = FlagType.CYAN,
        hasUnread = hasUnread,
        lastReadPage = 1,
        lastPostReadId = null,
        firstPostAuthor = "",
        lastReplyAuthor = "",
        lastReplyAt = "",
    )

    private fun row(
        topicId: Int,
        cat: Int,
        hasUnread: Boolean = true,
    ): FlagRowUiModel = flag(topicId = topicId, cat = cat, hasUnread = hasUnread)
        .toFlagRowUiModel(MarkerStyle.STRIPE)
}
