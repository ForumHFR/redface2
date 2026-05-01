package fr.forumhfr.redface2.core.parser.forum

import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.ForumIndex
import fr.forumhfr.redface2.core.model.SubCategory
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Parses the HFR forum root page (`forum.php?config=hfr.inc`) into a [ForumIndex].
 *
 * The root page lays out one `<tr class="cat ...">` per category, holding:
 *
 * - the category title as `<a class="cCatTopic" href="/hfr/<slug>/liste_sujet-1.htm">`
 *   — the category slug is the **first** path segment after `/hfr/`. For most cats it's
 *   the only segment (e.g. `Discussions`, `Hardware`), but `Achats & Ventes` has a
 *   double-segment href (`/hfr/AchatsVentes/Hardware/liste_sujet-1.htm`) where the
 *   category slug is `AchatsVentes` and `Hardware` is its first subcategory.
 * - the subcategory list as siblings `<a class="Tableau" href="/hfr/<cat>/<sub>/liste_sujet-1.htm">`,
 *   filtered to those whose first path segment equals the category slug — anything else
 *   in this row (e.g. `<a class="Tableau" href="/message.php?...&dest=…">` MP-to-modo
 *   links sitting in the moderator column) is dropped.
 *
 * The page exposes **no numeric `cat` ID** at this level. IDs are surfaced by every
 * `forum1.php?config=hfr.inc&cat=X` page via the `<input type="hidden" name="cat">`
 * input. Keeping this model slug-only is faithful to what the fixture proves.
 */
class ForumCategoriesParser {

    fun parse(html: String): ForumIndex {
        val document = Jsoup.parse(html)
        val rows = document.select("tr.cat")
        val categories = rows.mapNotNull(::parseRow)
        return ForumIndex(categories = categories)
    }

    @Suppress("ReturnCount")
    private fun parseRow(row: Element): Category? {
        val titleAnchor = row.selectFirst("a.cCatTopic[href]") ?: return null
        val name = titleAnchor.text().trim().ifEmpty { return null }
        val categorySlug = titleAnchor.attr("href").firstHfrPathSegment() ?: return null

        // Subcategory anchors live on the same row. Filter to those whose href is a
        // genuine `liste_sujet-1.htm` link inside the parent category — that drops both
        // the moderator MP links (`/message.php?...&dest=...`) and any cross-category
        // anchor accidentally rendered in this row. LinkedHashMap preserves HFR's
        // left-to-right display order; `putIfAbsent` is a defensive no-op if the same
        // subcategory shows up twice (e.g. duplicate links).
        val subcategories = LinkedHashMap<String, SubCategory>()
        row.select("a.Tableau[href]")
            .mapNotNull { anchor ->
                val (cat, sub) = anchor.attr("href").parseSubcategoryHref() ?: return@mapNotNull null
                if (cat != categorySlug) return@mapNotNull null
                val subName = anchor.text().trim().ifEmpty { return@mapNotNull null }
                Triple(sub, subName, categorySlug)
            }
            .forEach { (sub, subName, parent) ->
                subcategories.putIfAbsent(sub, SubCategory(name = subName, slug = sub, parentCategorySlug = parent))
            }

        return Category(name = name, slug = categorySlug, subcategories = subcategories.values.toList())
    }

    /** Returns the first segment after `/hfr/` in the href, or `null` if the href is not an HFR slug URL. */
    private fun String.firstHfrPathSegment(): String? {
        val match = HFR_PREFIX.find(this) ?: return null
        return match.groupValues[1].takeIf { it.isNotEmpty() }
    }

    /**
     * Parses a `/hfr/<cat>/<sub>/liste_sujet-1.htm` href into `(cat, sub)`. Returns null
     * for any other shape (root cat link, message.php, search, etc.).
     */
    private fun String.parseSubcategoryHref(): Pair<String, String>? {
        val match = HFR_SUBCAT_LISTE.find(this) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private companion object {
        // Slug charset matches what HFR rewrites produce (alphanumeric + dash). Allowing
        // dashes is mandatory (e.g. `Materiels-problemes-divers`); allowing dots/slashes
        // is not — that would let `/message.php` slip through.
        private val HFR_PREFIX = Regex("""^/hfr/([A-Za-z0-9\-]+)""")
        private val HFR_SUBCAT_LISTE = Regex("""^/hfr/([A-Za-z0-9\-]+)/([A-Za-z0-9\-]+)/liste_sujet-1\.htm$""")
    }
}
