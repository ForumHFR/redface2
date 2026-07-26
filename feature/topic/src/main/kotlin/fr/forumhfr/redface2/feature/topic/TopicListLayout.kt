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
 * #983 — rhythm between the children of ONE logical post item (a post and, below it, the
 * « Dernier message lu » separator — cf. [Modifier.separatorPadding]).
 *
 * Same rule as [topicListArrangement], stated separately because it is a different seam: in
 * full-width mode NO container inserts a gap (each non-post owns its own half-rhythm, so the
 * separator is symmetric — the #983 bug was the 8.dp above / 0.dp below asymmetry this produced),
 * while card mode keeps the historical #287 8.dp between the card and the separator.
 */
internal fun topicPostChildrenArrangement(fullWidthPosts: Boolean): Arrangement.Vertical =
    topicListArrangement(fullWidthPosts)

/**
 * #884 — local inset of a NON-POST list item (cf. the file KDoc above). Identity in card mode:
 * the list gutter + gap already give the islands their breathing room there.
 */
internal fun Modifier.islandPadding(fullWidthPosts: Boolean): Modifier = if (fullWidthPosts) {
    padding(horizontal = ISLAND_HORIZONTAL_INSET, vertical = ISLAND_VERTICAL_INSET)
} else {
    this
}

/**
 * #983 — local inset of a full-width SEPARATOR RULE (the « Dernier message lu » marker), as
 * opposed to an [islandPadding] island.
 *
 * A rule and an island are both non-posts, but they are NOT the same kind of thing: an island is a
 * floating card (it re-inserts the lost side gutter so it reads as a card), whereas the marker is a
 * rule that CUTS THROUGH the post sequence — it follows the posts, so it stays edge to edge when
 * they do. Hence the vertical half-rhythm only, and no horizontal inset.
 *
 * Identity in card mode, like [islandPadding]: there the list's own gutter and 8.dp rhythm already
 * place the marker (inset inside the gutter, exactly as the cards it separates).
 */
internal fun Modifier.separatorPadding(fullWidthPosts: Boolean): Modifier = if (fullWidthPosts) {
    padding(vertical = ISLAND_VERTICAL_INSET)
} else {
    this
}

/**
 * #983 — what follows a post in the POST SEQUENCE, from that post's point of view. Drives
 * [topicPostRequestsBottomHairline].
 *
 * The successor is not always the next list item: the last-read separator is rendered INSIDE the
 * post's own item, so it is a successor without being an item. Symmetrically, the closing island
 * (poll, page boundary, end-of-topic, search footers) is a further list item that the last post
 * never sees — it reports [NONE], not [NON_POST], and the effect is the same (no hairline): the
 * island brings its own border.
 */
internal enum class TopicFollowingKind {
    /** Another ordinary (non-hidden) post card. */
    POST,

    /**
     * A non-post that carries its own visual boundary and is reachable from this post: the last-read
     * separator nested in this item, or the next post rendered as a hidden-post placeholder card.
     */
    NON_POST,

    /**
     * No post follows: this is the last post of the rendered page. Either a closing island takes
     * over (it draws its own border) or nothing does — in both cases a hairline here would be a rule
     * hanging in the bottom inset.
     */
    NONE,
}

/**
 * #983 — whether the topic sequence asks the flat shell for its closing hairline.
 *
 * The full-width contract (#884) made the hairline the ONLY separation between posts, and drew it
 * unconditionally. That doubled the trait wherever the next element brings its own boundary: the
 * separator's own 2.dp rules, an `OutlinedCard` island's border, a filled island's edge — the #983
 * report (irregular spacing + parasitic horizontal lines). So in full-width the hairline exists at
 * an ordinary post → ordinary post boundary and nowhere else — including the end of the page, where
 * a trailing post would otherwise leave a rule dangling above the bottom inset.
 *
 * Card mode returns the shell's historical default (the shell ignores it when not flat), so its
 * call path is unchanged.
 */
internal fun topicPostRequestsBottomHairline(
    fullWidthPosts: Boolean,
    followingKind: TopicFollowingKind,
): Boolean = !fullWidthPosts || followingKind == TopicFollowingKind.POST

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
