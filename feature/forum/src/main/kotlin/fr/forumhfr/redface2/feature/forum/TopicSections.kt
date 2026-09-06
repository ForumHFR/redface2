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

/**
 * #1303 — header before the sticky group, even on a sticky-only page. Flag buckets keep their
 * source order and all sticky topics visible, without a header or a collapse command.
 */
internal fun TopicSections.shouldShowStickyHeader(filterActive: Boolean): Boolean =
    !filterActive && sticky.isNotEmpty()

/** #1129 — partition the filtered topics of the loaded page without assuming server ordering. */
internal fun List<TopicSummary>.toTopicSections(): TopicSections {
    val (sticky, regular) = partition(TopicSummary::isSticky)
    return TopicSections(sticky = sticky, regular = regular)
}
