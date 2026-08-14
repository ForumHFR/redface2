package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * #1046 — geometry of the private-message thread list, extracted as pure functions on the topic's
 * `TopicListLayout` pattern so the values are unit-testable ([ThreadListLayoutTest] pins them
 * against drift at the next visual tweak; the mounted FAB-clearance proof lives in
 * `ThreadFabClearanceTest`). The MP owns its own constants rather than sharing the topic's: the
 * topic file is `internal` to `:feature:topic`, which `:feature:messages` cannot even depend on
 * (its Gradle graph is `:core:domain` + `:core:ui` only — no feature→feature edges). Deliberately
 * NOT hoisted to a shared preset either: per the `DisplayMetrics` KDoc, absolute chrome dimensions
 * (gutters, FAB clearance) are not density-preset material and stay feature-owned — the lot 1 PR 2
 * arbitration, not re-litigated here.
 */
internal fun threadListContentPadding(): PaddingValues = PaddingValues(
    start = LIST_SIDE_GUTTER,
    top = LIST_TOP_INSET,
    end = LIST_SIDE_GUTTER,
    bottom = LIST_BOTTOM_INSET,
)

/** #298 — the historical 12.dp inter-message rhythm, unchanged by #1046. */
internal fun threadListArrangement(): Arrangement.Vertical = Arrangement.spacedBy(LIST_ITEM_GAP)

/** #298 — the historical 16.dp side gutter of the MP thread, unchanged by #1046. */
private val LIST_SIDE_GUTTER: Dp = 16.dp

/** #298 — top inset above the first message, historical 16.dp, unchanged by #1046. */
private val LIST_TOP_INSET: Dp = 16.dp

/**
 * #1046 — clearance under the last list item for the « Répondre » ExtendedFAB (#301) the Scaffold
 * floats over the list: at 16.dp the last message and the pager row ended up UNDER the FAB at the
 * end of the list. Same value as the topic's #283 bottom-cluster clearance (its
 * `LIST_BOTTOM_INSET`), so both reading surfaces reserve the same bottom breathing room.
 */
private val LIST_BOTTOM_INSET: Dp = 88.dp

/** #298 — the 12.dp vertical rhythm of the message feed. */
private val LIST_ITEM_GAP: Dp = 12.dp
