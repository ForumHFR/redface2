package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.post.PostCardShellFlatBottomEdge

/**
 * #1046/#1050 — geometry of the private-message thread list, extracted as pure functions on the topic's
 * `TopicListLayout` pattern so the values are unit-testable ([ThreadListLayoutTest] pins them
 * against drift at the next visual tweak; the mounted FAB-clearance proof lives in
 * `ThreadFabClearanceTest`). The MP owns its own constants rather than sharing the topic's: the
 * topic file is `internal` to `:feature:topic`, which `:feature:messages` cannot even depend on
 * (its Gradle graph is `:core:domain` + `:core:ui` only — no feature→feature edges). Deliberately
 * NOT hoisted to a shared preset either: per the `DisplayMetrics` KDoc, absolute chrome dimensions
 * (gutters, FAB clearance) are not density-preset material and stay feature-owned — the lot 1 PR 2
 * arbitration, not re-litigated here.
 *
 * Full-width keeps the #1046 top/FAB insets, but drops the side gutters and inter-message gap. The
 * trailing pager is not a message: its row keeps owning its local 8.dp vertical padding.
 */
internal fun threadListContentPadding(fullWidthPosts: Boolean): PaddingValues = if (fullWidthPosts) {
    PaddingValues(top = LIST_TOP_INSET, bottom = LIST_BOTTOM_INSET)
} else {
    PaddingValues(
        start = LIST_SIDE_GUTTER,
        top = LIST_TOP_INSET,
        end = LIST_SIDE_GUTTER,
        bottom = LIST_BOTTOM_INSET,
    )
}

/** #298/#1050 — no flat gap; card mode keeps the historical 12.dp inter-message rhythm. */
internal fun threadListArrangement(fullWidthPosts: Boolean): Arrangement.Vertical =
    if (fullWidthPosts) Arrangement.Top else Arrangement.spacedBy(LIST_ITEM_GAP)

/**
 * #509/#1050 — a hidden-message placeholder remains an inset card when ordinary messages go flat.
 * Card mode is an identity because the list already owns its 16.dp gutter and 12.dp rhythm.
 */
internal fun Modifier.threadIslandPadding(fullWidthPosts: Boolean): Modifier = if (fullWidthPosts) {
    padding(horizontal = LIST_SIDE_GUTTER, vertical = ISLAND_VERTICAL_INSET)
} else {
    this
}

/**
 * #983/#1050 — closes a flat message only when another ordinary message follows. The last message
 * never draws a dangling rule, including when the pager island follows it. Card mode returns the
 * shell default; [PostCardShellFlatBottomEdge] is ignored there.
 */
internal fun threadMessageFlatBottomEdge(
    fullWidthPosts: Boolean,
    hasFollowingMessage: Boolean,
): PostCardShellFlatBottomEdge = if (!fullWidthPosts || hasFollowingMessage) {
    PostCardShellFlatBottomEdge.HAIRLINE
} else {
    PostCardShellFlatBottomEdge.NONE
}

/** #298 — the historical 16.dp side gutter of the MP thread. Card mode only since #1050. */
private val LIST_SIDE_GUTTER: Dp = 16.dp

/** #298 — top inset above the first message, historical 16.dp, retained in both modes. */
private val LIST_TOP_INSET: Dp = 16.dp

/**
 * #1046 — clearance under the last list item for the « Répondre » ExtendedFAB (#301) the Scaffold
 * floats over the list: at 16.dp the last message and the pager row ended up UNDER the FAB at the
 * end of the list. Same value as the topic's #283 bottom-cluster clearance (its
 * `LIST_BOTTOM_INSET`), so both reading surfaces reserve the same bottom breathing room.
 */
private val LIST_BOTTOM_INSET: Dp = 88.dp

/** #298 — the 12.dp vertical rhythm of the message feed. Card mode only since #1050. */
private val LIST_ITEM_GAP: Dp = 12.dp

/** Half of the card-mode inter-message rhythm, owned locally by a full-width island. */
private val ISLAND_VERTICAL_INSET: Dp = 6.dp
