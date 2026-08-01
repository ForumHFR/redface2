package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * #884 — geometry of the topic post list, switched by the « posts en pleine largeur » preference
 * (vague 3). Extracted as pure functions (nothing mounts TopicLoadedContent in tests) so the
 * values are unit-testable; the Sol contract they encode:
 *
 *  - **full-width**: ZERO side gutter and ZERO inter-item gap — but ONLY between posts. The flat
 *    cards touch the screen edges and each other (the shell hairline is the only separation);
 *  - **islands** (every non-post item: poll, page-boundary, end-of-topic, search footers, hidden
 *    post placeholder) re-insert their own local breathing room via [islandPadding] — 8.dp
 *    horizontal (the lost gutter) and 4.dp vertical (half the lost 8.dp rhythm on each side);
 *  - **card mode** keeps the historical values byte-identical: the 8.dp side gutter owned locally
 *    since #398, the 16/88.dp top/bottom insets (#283 bottom-cluster clearance) and the 8.dp
 *    spacedBy rhythm (#287 density feedback) — and [islandPadding] is a strict identity (no
 *    zero-padding node inserted on every item).
 */
internal fun topicListContentPadding(fullWidthPosts: Boolean): PaddingValues = if (fullWidthPosts) {
    PaddingValues(top = LIST_TOP_INSET, bottom = LIST_BOTTOM_INSET)
} else {
    PaddingValues(
        start = LIST_SIDE_GUTTER,
        top = LIST_TOP_INSET,
        end = LIST_SIDE_GUTTER,
        bottom = LIST_BOTTOM_INSET,
    )
}

/** #884 — inter-item rhythm: none in full-width (posts touch), the #287 8.dp otherwise. */
internal fun topicListArrangement(fullWidthPosts: Boolean): Arrangement.Vertical =
    if (fullWidthPosts) Arrangement.Top else Arrangement.spacedBy(LIST_ITEM_GAP)

/**
 * #884 — local inset of a NON-POST list item (cf. the file KDoc above). Identity in card mode:
 * the list gutter + gap already give the islands their breathing room there.
 */
internal fun Modifier.islandPadding(fullWidthPosts: Boolean): Modifier = if (fullWidthPosts) {
    padding(horizontal = ISLAND_HORIZONTAL_INSET, vertical = ISLAND_VERTICAL_INSET)
} else {
    this
}

/** #398 — the reader's own 8.dp side gutter (the nav host no longer pads screens). Card mode only. */
private val LIST_SIDE_GUTTER: Dp = 8.dp

/** Top inset above the first item, both modes. */
private val LIST_TOP_INSET: Dp = 16.dp

/** #283 — clearance under the last post for the floating bottom-action cluster, both modes. */
private val LIST_BOTTOM_INSET: Dp = 88.dp

/** #287 — the 8.dp vertical rhythm of the card feed. Card mode only. */
private val LIST_ITEM_GAP: Dp = 8.dp

/** #884 — island side inset, compensating the lost [LIST_SIDE_GUTTER]. */
private val ISLAND_HORIZONTAL_INSET: Dp = 8.dp

/** #884 — island vertical inset, compensating the lost [LIST_ITEM_GAP] (half on each side). */
private val ISLAND_VERTICAL_INSET: Dp = 4.dp
