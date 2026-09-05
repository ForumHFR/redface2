package fr.forumhfr.redface2.core.parser.profile

import fr.forumhfr.redface2.core.model.profile.Sanction
import fr.forumhfr.redface2.core.model.profile.SanctionsHistory
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SanctionsHistoryParserTest {
    private val parser = SanctionsHistoryParser()

    @Test
    fun `header alone is a loaded empty history with its account pseudo`() {
        assertEquals(
            SanctionsHistory.Loaded("XaTelitte", emptyList()),
            parser.parse(fixture("sanctions_history_empty.html")),
        )
    }

    @Test
    fun `history ignores fast search and preserves all seven columns with normalized dates`() {
        assertEquals(
            SanctionsHistory.Loaded(
                "XaTriX",
                listOf(
                    Sanction(
                        pseudo = "XaTriX",
                        kind = "Teletubbies",
                        moderator = "TotalRecall",
                        category = "Intelligence Artificielle",
                        issuedAt = "13-06-2026 à 22:13",
                        liftedAt = "18-06-2026 à 22:13",
                        reason = "Promo de juin : pour deux TALC, un TT offert.",
                    ),
                ),
            ),
            parser.parse(fixture("sanctions_history_one.html")),
        )
    }

    @Test
    fun `empty or blank body requires a session`() {
        listOf("", " \n\t").forEach { html ->
            assertEquals(SanctionsHistory.SignInRequired, parser.parse(html))
        }
    }

    @Test
    fun `fast search alone is not an empty history`() {
        val document = Jsoup.parse(fixture("sanctions_history_empty.html"))
        document.select("table.main:not(.fastsearchMain)").remove()
        assertEquals(SanctionsHistory.SignInRequired, parser.parse(document.outerHtml()))
    }

    @Test
    fun `blank lifted date becomes null and blank reason stays empty`() {
        // Defensive variants derived in memory; no fabricated HTML fixture or live claim.
        listOf("", " \u00a0 \n").forEach { blank ->
            val document = Jsoup.parse(fixture("sanctions_history_one.html"))
            val cells = document.select("tr.profil td")
            cells[LIFTED_AT_COLUMN].text(blank)
            cells[REASON_COLUMN].text(blank)
            val loaded = parser.parse(document.outerHtml()) as SanctionsHistory.Loaded
            assertNull(loaded.sanctions.single().liftedAt)
            assertEquals("", loaded.sanctions.single().reason)
        }
    }

    @Test
    fun `incomplete row is ignored without dropping a valid sibling`() {
        val document = Jsoup.parse(fixture("sanctions_history_one.html"))
        val row = requireNotNull(document.selectFirst("tr.profil"))
        val incomplete = row.clone()
        incomplete.select("td").last()?.remove()
        row.after(incomplete)
        assertEquals(
            parser.parse(fixture("sanctions_history_one.html")),
            parser.parse(document.outerHtml()),
        )
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/fixtures/$name")).readText()

    private companion object {
        const val LIFTED_AT_COLUMN = 5
        const val REASON_COLUMN = 6
    }
}
