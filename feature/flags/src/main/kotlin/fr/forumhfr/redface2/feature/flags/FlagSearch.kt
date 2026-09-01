package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.search.containsFolded
import fr.forumhfr.redface2.core.model.Flag

/**
 * Pure client-side Drapeaux search (#603, PR2). HFR exposes no server-side search (cf.
 * [ADR-003]) and the flagged topics of the current tab are already loaded, so « rechercher dans les
 * drapeaux » is a title filter applied to the rendered [FlagsContent] — it never touches the
 * fetch/cache pipeline. Pure and testable without Android.
 *
 * Matching is case- AND accent-insensitive (#739): both the query and the title go through the
 * shared [containsFolded] folding (NFD + combining-marks removal + lowercase + `œ`/`æ` spelled
 * out), so « cafe » finds « café » and « café » finds « cafe » — the same folding as the Forum and
 * Settings searches.
 */

/**
 * Keeps the flags whose [Flag.title] contains [query] (case- and accent-insensitive, trimmed). A
 * blank query is a no-op (returns [flags] unchanged).
 */
fun filterFlagsByQuery(flags: List<Flag>, query: String): List<Flag> {
    val q = query.trim()
    if (q.isEmpty()) return flags
    return flags.filter { it.title.containsFolded(q) }
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

/**
 * Pure client-side DT search (#603 harmonisation — the same « rechercher dans les drapeaux » loupe is
 * now offered on the DT tab, applied to its conversation list). Keeps the [DtListItem.InboxBacked] rows
 * whose conversation subject contains [query] (case- and accent-insensitive, trimmed — #739).
 * [DtListItem.StorageOnly] orphans carry no subject (only a threadId), so an active query drops them —
 * they have nothing to match. A blank query is a no-op (returns [items] unchanged).
 */
fun filterDtItemsByQuery(items: List<DtListItem>, query: String): List<DtListItem> {
    val q = query.trim()
    if (q.isEmpty()) return items
    return items.filter { item ->
        when (item) {
            is DtListItem.InboxBacked -> item.conversation.subject.containsFolded(q)
            is DtListItem.StorageOnly -> false
        }
    }
}
