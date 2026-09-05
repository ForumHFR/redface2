package fr.forumhfr.redface2.core.parser.profile

import fr.forumhfr.redface2.core.model.profile.Sanction
import fr.forumhfr.redface2.core.model.profile.SanctionsHistory
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parses the authenticated /modo/historique.php history captured for #294. */
class SanctionsHistoryParser {
    fun parse(html: String): SanctionsHistory {
        val document = Jsoup.parse(html)
        val table = document.select("table.main").firstOrNull { candidate ->
            candidate.select("tr.cBackHeader th").any { it.normalizedText() == "Nom du posteur" }
        } ?: return SanctionsHistory.SignInRequired
        return SanctionsHistory.Loaded(
            pseudo = document.selectFirst("h1#md_arbo_tree_3 b")?.normalizedText().orEmpty(),
            sanctions = table.select("tr.profil").mapNotNull(::parseRow),
        )
    }

    private fun parseRow(row: Element): Sanction? {
        val cells = row.children().filter { it.tagName() == "td" }
        if (cells.size < COLUMN_COUNT) return null
        return Sanction(
            pseudo = cells[POSTER_COLUMN].normalizedText(),
            kind = cells[KIND_COLUMN].normalizedText(),
            moderator = cells[MODERATOR_COLUMN].normalizedText(),
            category = cells[CATEGORY_COLUMN].normalizedText(),
            issuedAt = cells[ISSUED_AT_COLUMN].normalizedText(),
            liftedAt = cells[LIFTED_AT_COLUMN].normalizedText().ifBlank { null },
            reason = cells[REASON_COLUMN].normalizedText(),
        )
    }

    private fun Element.normalizedText(): String = text().replace('\u00a0', ' ').trim()

    private companion object {
        const val COLUMN_COUNT = 7
        const val POSTER_COLUMN = 0
        const val KIND_COLUMN = 1
        const val MODERATOR_COLUMN = 2
        const val CATEGORY_COLUMN = 3
        const val ISSUED_AT_COLUMN = 4
        const val LIFTED_AT_COLUMN = 5
        const val REASON_COLUMN = 6
    }
}
