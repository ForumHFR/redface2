package fr.forumhfr.redface2.feature.forum

import fr.forumhfr.redface2.core.model.TopicSummary

/**
 * #1129 — local sections of the currently displayed category page. Sticky topics are grouped
 * ahead of regular topics even when the REST payload interleaves them; [List.partition] preserves
 * the source order inside each section.
 */
internal data class TopicSections(
    val sticky: List<TopicSummary>,
    val regular: List<TopicSummary>,
)

/** True only when the list needs a visible boundary between sticky and regular topics. */
internal val TopicSections.hasStickyBoundary: Boolean
    get() = sticky.isNotEmpty() && regular.isNotEmpty()

/**
 * #1129 — whether to actually draw the sticky/regular boundary (and, upstream, the sticky
 * partition) for the current listing. Scoped to real category listings only: flag-filter buckets
 * (Participé/Lus/Favoris) are recency-sorted cross-category views, so a pinned topic must not be
 * promoted there and no separator is shown. Kept out of the composable so the scoping invariant is
 * unit-testable without a Compose harness.
 */
internal fun TopicSections.shouldShowStickyBoundary(filterActive: Boolean): Boolean =
    !filterActive && hasStickyBoundary

/** #1129 — partition the filtered topics of the loaded page without assuming server ordering. */
internal fun List<TopicSummary>.toTopicSections(): TopicSections {
    val (sticky, regular) = partition(TopicSummary::isSticky)
    return TopicSections(sticky = sticky, regular = regular)
}
