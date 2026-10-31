package fr.forumhfr.redface2.core.parser.smiley

import org.jsoup.Jsoup

/** #1154 — reads only the requested smiley's editable keywords, never the modification history. */
class SmileyWikiKeywordsParser {
    fun parse(html: String, token: String): List<String> {
        val document = Jsoup.parse(html)
        val form = requireNotNull(document.select("form").firstOrNull {
            it.attr("action").substringBefore('?').substringAfterLast('/') == "wikismilies.php" &&
                it.selectFirst("input[type=hidden][name=detail]") != null
        }) { "Missing Wiki detail form" }
        require(form.selectFirst("input[type=hidden][name=detail]")?.attr("value") == token) {
            "Mismatched Wiki detail token"
        }
        val rows = form.select("tr.s2Topic")
        require(rows.isNotEmpty()) { "Missing Wiki detail rows" }
        val row = rows.firstOrNull {
            it.selectFirst("input[type=hidden][name=smiley0][value]")?.attr("value") == token
        }
        // Without the target row, only HFR's explicit empty-result marker is a valid empty result.
        // A different token or a broken field must fail instead of entering the cache as empty.
        require(row != null || rows.any {
            it.text().contains("Désolé, aucune réponse n'a été trouvée")
        }) { "Unrecognized Wiki detail result" }
        val keywords = row?.let {
            requireNotNull(it.selectFirst("input[type=text][name=keywords0][value]")) {
                "Missing Wiki keywords field"
            }.attr("value")
        }.orEmpty()
        // Jsoup decodes attribute entities. A hyphen is part of a keyword, not a separator.
        return keywords.split(WHITESPACE).filter { it.isNotBlank() }.distinct()
    }

    private companion object {
        private val WHITESPACE = Regex("""[\s\p{Zs}]+""")
    }
}
