package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.Flag

/**
 * One section of the category-grouped Drapeaux screen (#179). On HFR web, the « Vos sujets »
 * view is grouped by forum category in canonical order, with one separator band per category —
 * empty categories included. Redface 2 reproduces that grouping inside each existing tab.
 *
 * [topics] empty ⇒ the section renders an empty placeholder (the wording is chosen per tab in
 * Compose, cf. `flags_category_empty*` strings ; no fake domain item is emitted for emptiness).
 * [catName] is the canonical label of a known category ; **`null` ⇒ unknown category** (absent
 * from the REST catalogue AND the hard-coded fallback order), whose label is resolved CÔTÉ
 * COMPOSE via `stringResource(R.string.flags_category_fallback, catId)`. The pure grouping
 * function never fabricates an Android string.
 */
data class FlagCategorySection(
    val catId: Int,
    val catName: String?,
    val topics: List<FlagRowUiModel>,
)

/**
 * Canonical-order entry for a forum category (id + name). Local model to `:feature:flags`,
 * deliberately narrower than [fr.forumhfr.redface2.core.model.Category]: grouping/sorting/
 * labelling only needs the id and the name, not `forceSubcat`/`subcategoryCount`. Keeping it
 * narrow also lets the hard-coded fallback order ([FALLBACK_CATEGORY_ORDER]) avoid fabricating
 * those extra fields.
 */
data class FlagCategoryOrderEntry(val id: Int, val name: String)

/**
 * Hard-coded canonical category order used ONLY for display/sort when
 * [fr.forumhfr.redface2.core.domain.forum.ForumRepository.observeCategories] has not yet emitted
 * a `Success` (cold start, `Loading`/`Failure`). Never used to filter flags or decide a fetch.
 *
 * 19 public categories captured from `core/data/src/test/resources/fixtures/rest_categories.json`
 * (REST `forums/hardwarefr/categories/`), `&amp;` entities decoded to `&`. Order matches the HFR
 * web layout: id sequence 1, 16, 15, 2, 30, 23, 25, 3, 14, 5, 4, 22, 21, 11, 10, 12, 6, 8, 13.
 */
internal val FALLBACK_CATEGORY_ORDER: List<FlagCategoryOrderEntry> = listOf(
    FlagCategoryOrderEntry(1, "Hardware"),
    FlagCategoryOrderEntry(16, "Hardware - Périphériques"),
    FlagCategoryOrderEntry(15, "Ordinateurs portables"),
    FlagCategoryOrderEntry(2, "Overclocking, Cooling & Modding"),
    FlagCategoryOrderEntry(30, "Electronique, domotique, DIY"),
    FlagCategoryOrderEntry(23, "Technologies Mobiles"),
    FlagCategoryOrderEntry(25, "Apple"),
    FlagCategoryOrderEntry(3, "Video & Son"),
    FlagCategoryOrderEntry(14, "Photo numérique"),
    FlagCategoryOrderEntry(5, "Jeux Video"),
    FlagCategoryOrderEntry(4, "Windows & Software"),
    FlagCategoryOrderEntry(22, "Réseaux grand public / SoHo"),
    FlagCategoryOrderEntry(21, "Systèmes & Réseaux Pro"),
    FlagCategoryOrderEntry(11, "Linux et OS Alternatifs"),
    FlagCategoryOrderEntry(10, "Programmation"),
    FlagCategoryOrderEntry(12, "Graphisme"),
    FlagCategoryOrderEntry(6, "Achats & Ventes"),
    FlagCategoryOrderEntry(8, "Emploi & Etudes"),
    FlagCategoryOrderEntry(13, "Discussions"),
)

/**
 * Groups [flags] (already unread-filtered per type when that tab's « non-lus uniquement » is on,
 * cf. #154/#317) by category and orders the sections by the
 * canonical [orderedCategories]. Returns FIRST every known category (empty sections included,
 * for web parity), THEN at the end the categories present in the flags but absent from the
 * catalogue, as « unknown » sections (`catName == null`) sorted by `catId` ascending.
 *
 * Pure, testable without Android, NO Android dependency (no label lambda — the fallback label
 * is resolved in Compose via `stringResource`).
 *
 * Per-section internal order: PRESERVES the input order of the flags (the repository already
 * sorts globally by `lastReplyAt` descending). Grouping is STABLE (tie-break on the original
 * index). Pinned by test.
 *
 * Invariant #251 (passive): no flag is ever dropped. A category outside the catalogue is
 * rendered as an « unknown » section (`catName == null`) at the end, never filtered out.
 */
fun groupFlagsByCategory(
    flags: List<Flag>,
    orderedCategories: List<FlagCategoryOrderEntry>,
): List<FlagCategorySection> =
    groupFlagRowsByCategory(
        rows = flags.map { it.toFlagRowUiModel(MarkerStyle.STRIPE) },
        orderedCategories = orderedCategories,
    )

fun groupFlagRowsByCategory(
    rows: List<FlagRowUiModel>,
    orderedCategories: List<FlagCategoryOrderEntry>,
): List<FlagCategorySection> {
    // Stable group-by: LinkedHashMap preserves first-seen order, and a list per bucket
    // preserves the input order of flags within a category (tie-break = original index).
    val byCat: Map<Int, List<FlagRowUiModel>> = rows.groupByTo(LinkedHashMap()) { it.cat }

    // Defensive dedup: a corrupt catalogue with two entries sharing the same id would otherwise
    // emit two sections with the same catId → duplicate LazyColumn keys (`cat-$catId-header`),
    // which throws at runtime. The REST contract returns 19 distinct ids, so this is a guard,
    // not an expected path; keep the FIRST occurrence (canonical order/label).
    val knownSections = orderedCategories.distinctBy { it.id }.map { entry ->
        FlagCategorySection(
            catId = entry.id,
            catName = entry.name,
            topics = byCat[entry.id].orEmpty(),
        )
    }

    val knownIds = orderedCategories.mapTo(HashSet()) { it.id }
    val unknownSections = byCat.keys
        .filterNot { it in knownIds }
        .sorted()
        .map { catId ->
            FlagCategorySection(
                catId = catId,
                catName = null,
                topics = byCat.getValue(catId),
            )
        }

    return knownSections + unknownSections
}

/**
 * Filters [sections] for the « masquer les catégories sans message non lu » preference (#179
 * follow-up). A section is KEPT when it has at least one unread flag; empty sections are always
 * dropped.
 *
 * [keepFullyRead] is the « +lus » override (#317, generalised to every real type by #825): when
 * the user explicitly opted to show already-read topics (unreadOnly off — an explicit choice on
 * CYAN/RED/FAVORITE alike since the #751 re-tap shortcut), a section that holds only read flags
 * is still kept (only truly empty sections are dropped) — otherwise the read topics the user
 * just asked to see would be hidden by this very filter. Callers pass `false` only for
 * unread-only views, where the read flags were already filtered out upstream.
 *
 * Pure, testable without Android.
 */
fun filterCategoriesWithUnread(
    sections: List<FlagCategorySection>,
    keepFullyRead: Boolean,
): List<FlagCategorySection> = sections.filter { section ->
    when {
        section.topics.isEmpty() -> false
        keepFullyRead -> true
        else -> section.topics.any { it.hasUnread }
    }
}
