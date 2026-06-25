package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.model.Flag

/**
 * Pure client-side Drapeaux search (#603, PR2). HFR exposes no server-side search (cf.
 * [ADR-003]) and the flagged topics of the current tab are already loaded, so « rechercher dans les
 * drapeaux » is a title filter applied to the rendered [FlagsContent] — it never touches the
 * fetch/cache pipeline. Pure and testable without Android.
 */

/**
 * Keeps the flags whose [Flag.title] contains [query] (case-insensitive, trimmed). A blank query is
 * a no-op (returns [flags] unchanged).
 */
fun filterFlagsByQuery(flags: List<Flag>, query: String): List<Flag> {
    val q = query.trim()
    if (q.isEmpty()) return flags
    return flags.filter { it.title.contains(q, ignoreCase = true) }
}

/**
 * Applies [filterFlagsByQuery] to a whole [FlagsContent].
 *
 * - Blank query → unchanged (the grouped view keeps its web-parity empty sections).
 * - [FlagsContent.Flat] → the matching flags only.
 * - [FlagsContent.Grouped] → each section is filtered and a section that ends up empty is DROPPED,
 *   so an active search shows only categories with a hit instead of a wall of empty placeholders.
 */
fun FlagsContent.filteredBy(query: String): FlagsContent {
    if (query.isBlank()) return this
    return when (this) {
        is FlagsContent.Flat -> FlagsContent.Flat(filterFlagsByQuery(flags, query))
        is FlagsContent.Grouped -> FlagsContent.Grouped(
            sections.mapNotNull { section ->
                val kept = filterFlagsByQuery(section.topics, query)
                if (kept.isEmpty()) null else section.copy(topics = kept)
            },
        )
    }
}

/** True when this content holds no topic at all (every section empty, or a flat empty list). */
fun FlagsContent.isEmpty(): Boolean = when (this) {
    is FlagsContent.Flat -> flags.isEmpty()
    is FlagsContent.Grouped -> sections.all { it.topics.isEmpty() }
}
