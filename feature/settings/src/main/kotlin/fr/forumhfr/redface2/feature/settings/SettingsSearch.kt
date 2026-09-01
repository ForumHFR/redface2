package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.search.foldForSearch

/**
 * #494 — pure, testable model of the settings catalogue for the activable search.
 *
 * This file is 100% Kotlin/JVM: it has NO `androidx`/`android` dependency (no `R`, no Compose, no
 * `SettingsState`) so [filterSettingsSections] can be unit-tested without Robolectric. The mapping
 * from `SettingsState` + resolved strings to a `List<SettingsSearchableSection>` is built at the
 * `SettingsScreen` call site, not here.
 */

/** A catalogue section (e.g. « Affichage ») holding its searchable items. */
internal data class SettingsSearchableSection(
    val id: String,
    val title: String,
    val items: List<SettingsSearchableItem>,
)

/**
 * A single searchable settings entry.
 *
 * - [keywords] are extra match terms not shown verbatim (synonyms, related issue topics).
 * - [enabled] mirrors the row's interactivity. Disabled (future) rows STAY searchable.
 * - [visible] gates the row entirely: an item with `visible = false` (e.g. a DT-only entry while the
 *   DT section is off) is excluded from results even when it would match the query.
 */
internal data class SettingsSearchableItem(
    val id: String,
    val title: String,
    val description: String? = null,
    val keywords: List<String> = emptyList(),
    val enabled: Boolean = true,
    val visible: Boolean = true,
)

/**
 * Filters [sections] against [query].
 *
 * - Items with `visible = false` are dropped first, always.
 * - A blank [query] returns every section with its visible items (sections that have no visible item
 *   are omitted).
 * - Otherwise an item matches when the case- and accent-folded [query] is contained in the folded
 *   title, description, or any keyword. `enabled = false` items remain matchable.
 * - Sections left with no matching item are omitted.
 *
 * Folding is the shared `foldForSearch` of `:core:domain` (NFD + combining-marks removal + lowercase +
 * `œ`/`æ` spelled out, #739) — the same one as the Forum and Drapeaux searches.
 */
internal fun filterSettingsSections(
    sections: List<SettingsSearchableSection>,
    query: String,
): List<SettingsSearchableSection> {
    val visibleSections = sections.map { section ->
        section.copy(items = section.items.filter { it.visible })
    }
    if (query.isBlank()) {
        return visibleSections.filter { it.items.isNotEmpty() }
    }
    val needle = query.foldForSearch()
    return visibleSections.mapNotNull { section ->
        val matches = section.items.filter { item -> item.matches(needle) }
        if (matches.isEmpty()) null else section.copy(items = matches)
    }
}

private fun SettingsSearchableItem.matches(needle: String): Boolean =
    title.foldForSearch().contains(needle) ||
        description?.foldForSearch()?.contains(needle) == true ||
        keywords.any { it.foldForSearch().contains(needle) }
