package fr.forumhfr.redface2.feature.topic

import kotlin.math.floor

internal const val QUICK_REPLY_FIELD_MIN_LINES = 3
internal const val QUICK_REPLY_FIELD_TARGET_MAX_LINES = 15
internal const val QUICK_REPLY_FIELD_FALLBACK_LINE_HEIGHT_DP = 24f

private const val QUICK_REPLY_FIELD_USEFUL_HEIGHT_FRACTION = 0.75f

/**
 * Computes the quick-reply text field line cap from the IME-reduced useful window height.
 *
 * The send row is pinned outside the scrollable body, so this cap only budgets the editor viewport:
 * enough room to approach RF1 on roomy displays, reduced aggressively on cramped windows.
 */
internal fun quickReplyFieldMaxLines(
    windowHeightDp: Float,
    imeHeightDp: Float,
    lineHeightDp: Float,
    minLines: Int = QUICK_REPLY_FIELD_MIN_LINES,
    targetMaxLines: Int = QUICK_REPLY_FIELD_TARGET_MAX_LINES,
): Int {
    require(minLines > 0) { "minLines must be positive" }
    require(targetMaxLines >= minLines) { "targetMaxLines must be at least minLines" }

    if (!windowHeightDp.isFinite()) return targetMaxLines

    val usefulWindowHeightDp = (windowHeightDp - imeHeightDp.coerceAtLeast(0f)).coerceAtLeast(0f)
    val safeLineHeightDp = lineHeightDp
        .takeIf { it.isFinite() && it > 0f }
        ?: QUICK_REPLY_FIELD_FALLBACK_LINE_HEIGHT_DP
    val budgetDp = usefulWindowHeightDp * QUICK_REPLY_FIELD_USEFUL_HEIGHT_FRACTION
    val budgetLines = floor(budgetDp / safeLineHeightDp).toInt()

    return budgetLines.coerceIn(minLines, targetMaxLines)
}
