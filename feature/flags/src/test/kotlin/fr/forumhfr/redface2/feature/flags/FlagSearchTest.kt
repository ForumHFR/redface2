package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 100% coverage of the pure client-side Drapeaux search (#603, PR2). The flagged topics are already
 * loaded (HFR has no server search, cf. ADR-003), so « rechercher dans les drapeaux » is a pure
 * title filter applied to the rendered [FlagsContent] — no ViewModel pipeline change. Blank query is
 * a no-op (the grouped view keeps its web-parity empty sections); a real query drops non-matching
 * sections so the result is not a wall of empty placeholders.
 */
class FlagSearchTest {

    // --- filterFlagsByQuery ------------------------------------------------------------------

    @Test
    fun `a blank query returns the list unchanged`() {
        val flags = listOf(flag("Kotlin"), flag("Compose"))
        assertEquals(flags, filterFlagsByQuery(flags, "   "))
    }

    @Test
    fun `the query matches the title case-insensitively`() {
        val flags = listOf(flag("Kotlin Multiplatform"), flag("Jetpack Compose"), flag("Rust"))
        assertEquals(
            listOf("Kotlin Multiplatform"),
            filterFlagsByQuery(flags, "kotlin").map { it.title },
        )
    }

    @Test
    fun `the query is trimmed before matching`() {
        val flags = listOf(flag("Compose"), flag("Rust"))
        assertEquals(listOf("Compose"), filterFlagsByQuery(flags, "  compose  ").map { it.title })
    }

    @Test
    fun `a substring anywhere in the title matches`() {
        val flags = listOf(flag("Le topic des montres"), flag("Le topic des PC"))
        assertEquals(listOf("Le topic des montres"), filterFlagsByQuery(flags, "montre").map { it.title })
    }

    @Test
    fun `no match yields an empty list`() {
        val flags = listOf(flag("A"), flag("B"))
        assertEquals(emptyList<Flag>(), filterFlagsByQuery(flags, "zzz"))
    }

    // --- FlagsContent.filteredBy (flat) ------------------------------------------------------

    @Test
    fun `flat content with a blank query is unchanged`() {
        val content = FlagsContent.Flat(listOf(flag("A"), flag("B")))
        assertEquals(content, content.filteredBy(""))
    }

    @Test
    fun `flat content keeps only the matching flags`() {
        val content = FlagsContent.Flat(listOf(flag("Kotlin"), flag("Rust")))
        val filtered = content.filteredBy("kotlin") as FlagsContent.Flat
        assertEquals(listOf("Kotlin"), filtered.flags.map { it.title })
    }

    // --- FlagsContent.filteredBy (grouped) ---------------------------------------------------

    @Test
    fun `grouped content with a blank query keeps its sections (web-parity empties)`() {
        val content = FlagsContent.Grouped(
            listOf(
                FlagCategorySection(1, "Hardware", listOf(flag("CPU"))),
                FlagCategorySection(10, "Programmation", emptyList()),
            ),
        )
        assertEquals(content, content.filteredBy("  "))
    }

    @Test
    fun `grouped content filters within sections and drops the empty ones`() {
        val content = FlagsContent.Grouped(
            listOf(
                FlagCategorySection(1, "Hardware", listOf(flag("Carte mère"), flag("CPU"))),
                FlagCategorySection(10, "Programmation", listOf(flag("Kotlin"))),
                FlagCategorySection(13, "Discussions", emptyList()),
            ),
        )

        val filtered = content.filteredBy("c") as FlagsContent.Grouped

        // « Carte mère » + « CPU » match in Hardware ; nothing in Programmation/Discussions → dropped.
        assertEquals(listOf(1), filtered.sections.map { it.catId })
        assertEquals(listOf("Carte mère", "CPU"), filtered.sections.single().topics.map { it.title })
    }

    private fun flag(title: String): Flag = Flag(
        cat = 1,
        subcat = null,
        topicId = title.hashCode(),
        title = title,
        totalPages = 1,
        replyCount = 0,
        type = FlagType.CYAN,
        isFavorite = false,
        hasUnread = true,
        lastReadPage = 1,
        lastPostReadId = null,
        firstPostAuthor = "op",
        lastReplyAuthor = "last",
        lastReplyAt = "2026-06-24 12:00",
    )
}
