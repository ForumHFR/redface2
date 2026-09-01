package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 100% coverage of the pure client-side Drapeaux search (#603, PR2). The flagged topics are already
 * loaded (HFR has no server search, cf. ADR-003), so « rechercher dans les drapeaux » is a pure
 * title filter applied to the rendered [FlagsContent] — no ViewModel pipeline change. Blank query is
 * a no-op (the grouped view keeps its web-parity empty sections); a real query drops non-matching
 * sections so the result is not a wall of empty placeholders. Matching is case- and
 * accent-insensitive (#739) through the shared `foldForSearch` of `:core:domain`.
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
    fun `an unaccented query finds an accented title (cafe finds café)`() {
        val flags = listOf(flag("Le topic du café"), flag("Le topic du thé"))
        assertEquals(listOf("Le topic du café"), filterFlagsByQuery(flags, "cafe").map { it.title })
    }

    @Test
    fun `an accented query finds an unaccented title (café finds cafe)`() {
        val flags = listOf(flag("Le topic du cafe"), flag("Le topic du the"))
        assertEquals(listOf("Le topic du cafe"), filterFlagsByQuery(flags, "café").map { it.title })
    }

    @Test
    fun `accent folding keeps the query case-insensitive`() {
        val flags = listOf(flag("Réflexion sur la batterie"), flag("Rust"))
        assertEquals(listOf("Réflexion sur la batterie"), filterFlagsByQuery(flags, "REFLEXION").map { it.title })
    }

    @Test
    fun `a ligature title is found by its two-letter spelling`() {
        val flags = listOf(flag("Le cœur du problème"), flag("Rust"))
        assertEquals(listOf("Le cœur du problème"), filterFlagsByQuery(flags, "coeur").map { it.title })
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

    @Test
    fun `grouped content search is accent-insensitive (the FlagsRoute path)`() {
        val content = FlagsContent.Grouped(
            listOf(
                FlagCategorySection(1, "Hardware", listOf(flag("Carte mère"), flag("CPU"))),
                FlagCategorySection(13, "Discussions", listOf(flag("Le topic du café"))),
            ),
        )

        val filtered = content.filteredBy("mere") as FlagsContent.Grouped

        assertEquals(listOf(1), filtered.sections.map { it.catId })
        assertEquals(listOf("Carte mère"), filtered.sections.single().topics.map { it.title })
    }

    // --- FlagsContent.isEmpty ----------------------------------------------------------------

    @Test
    fun `isEmpty is true for a flat content with no flags`() {
        assertTrue(FlagsContent.Flat(emptyList()).isEmpty())
    }

    @Test
    fun `isEmpty is false for a flat content with flags`() {
        assertFalse(FlagsContent.Flat(listOf(flag("A"))).isEmpty())
    }

    @Test
    fun `isEmpty is true for a grouped content whose sections are all empty`() {
        val content = FlagsContent.Grouped(
            listOf(
                FlagCategorySection(1, "Hardware", emptyList()),
                FlagCategorySection(10, "Programmation", emptyList()),
            ),
        )
        assertTrue(content.isEmpty())
    }

    @Test
    fun `isEmpty is false for a grouped content with at least one topic`() {
        val content = FlagsContent.Grouped(
            listOf(
                FlagCategorySection(1, "Hardware", emptyList()),
                FlagCategorySection(10, "Programmation", listOf(flag("Kotlin"))),
            ),
        )
        assertFalse(content.isEmpty())
    }

    @Test
    fun `a grouped query matching nothing drops every section and is empty`() {
        val content = FlagsContent.Grouped(
            listOf(
                FlagCategorySection(1, "Hardware", listOf(flag("CPU"))),
                FlagCategorySection(10, "Programmation", listOf(flag("Kotlin"))),
            ),
        )

        val filtered = content.filteredBy("zzz")

        assertEquals(emptyList<FlagCategorySection>(), (filtered as FlagsContent.Grouped).sections)
        assertTrue("all sections dropped → empty (the NoFlagsSearchResults path)", filtered.isEmpty())
    }

    // --- filterDtItemsByQuery (#603 harmonisation — the loupe filters the DT list too) -------

    @Test
    fun `a blank DT query returns the list unchanged (orphans kept)`() {
        val items = listOf(dtInbox(1, "RDNA4"), dtOrphan(9))
        assertEquals(items, filterDtItemsByQuery(items, "  "))
    }

    @Test
    fun `the DT query matches the conversation subject case-insensitively`() {
        val items = listOf(dtInbox(1, "RDNA4 — AMD"), dtInbox(2, "Claviers mécaniques"))
        assertEquals(
            listOf(1),
            filterDtItemsByQuery(items, "rdna").map { it.threadId },
        )
    }

    @Test
    fun `the DT query is trimmed before matching`() {
        val items = listOf(dtInbox(1, "Claviers mécaniques"), dtInbox(2, "RDNA4"))
        assertEquals(listOf(1), filterDtItemsByQuery(items, "  claviers  ").map { it.threadId })
    }

    @Test
    fun `the DT query is accent-insensitive in both directions`() {
        val items = listOf(dtInbox(1, "Claviers mécaniques"), dtInbox(2, "Cafe du commerce"), dtInbox(3, "RDNA4"))
        assertEquals(listOf(1), filterDtItemsByQuery(items, "mecaniques").map { it.threadId })
        assertEquals(listOf(2), filterDtItemsByQuery(items, "café").map { it.threadId })
    }

    @Test
    fun `an active DT query drops storage-only orphans (no subject to match)`() {
        val items = listOf(dtInbox(1, "RDNA4"), dtOrphan(9))
        // The orphan has no subject; an active query keeps only the matching inbox row.
        assertEquals(listOf(1), filterDtItemsByQuery(items, "rdna").map { it.threadId })
    }

    @Test
    fun `a DT query matching nothing yields an empty list`() {
        val items = listOf(dtInbox(1, "RDNA4"), dtInbox(2, "Claviers"))
        assertEquals(emptyList<DtListItem>(), filterDtItemsByQuery(items, "zzz"))
    }

    @Test
    fun `an active DT query over storage-only orphans only yields an empty list`() {
        // Orphans carry no subject, so any active query empties the list (the NoFlagsSearchResults path).
        val items = listOf(dtOrphan(7), dtOrphan(9))
        assertEquals(emptyList<DtListItem>(), filterDtItemsByQuery(items, "anything"))
    }

    private fun dtInbox(threadId: Int, subject: String): DtListItem.InboxBacked =
        DtListItem.InboxBacked(
            conversation = PrivateMessageSummary(
                threadId = threadId,
                correspondent = "",
                subject = subject,
                date = Instant.parse("2026-06-28T12:00:00Z"),
                hasUnread = true,
                isMultiRecipient = true,
                lastPage = 1,
            ),
            resumePage = null,
        )

    private fun dtOrphan(threadId: Int): DtListItem.StorageOnly =
        DtListItem.StorageOnly(threadId = threadId, resumePage = 1, numreponse = null)

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
