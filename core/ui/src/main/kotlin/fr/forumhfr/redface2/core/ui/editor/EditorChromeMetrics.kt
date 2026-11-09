package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** ED-02/05: application envelopes, independent of bar mode, events and keyboard visibility. */
data class EditorChromeMetrics(val compactHeight: Boolean) {
    val appBarHeight: Dp get() = if (compactHeight) 0.dp else minimumTargetSize
    val actionBarHeight: Dp get() = if (compactHeight) minimumTargetSize else 56.dp
    val columnSpacing: Dp get() = if (compactHeight) 0.dp else 12.dp
    val draftHeightFloor: Dp get() = if (compactHeight) 0.dp else EDITOR_DRAFT_MIN_HEIGHT
    val showFieldLabel: Boolean get() = !compactHeight

    /** [availableHeight] is the content height AFTER the single bottom inset and top inset. */
    fun draftHeight(availableHeight: Dp): Dp =
        (availableHeight - appBarHeight - columnSpacing - actionBarHeight).coerceAtLeast(0.dp)

    /** J: pass the REAL field's measured line/decoration heights and the required 3 or 2 lines. */
    fun draftAllocation(
        availableHeight: Dp,
        lineHeight: Dp,
        decorationHeight: Dp,
        minimumLines: Int,
    ): EditorDraftAllocation {
        require(lineHeight.value.isFinite() && lineHeight > 0.dp)
        require(decorationHeight.value.isFinite() && decorationHeight >= 0.dp)
        require(minimumLines > 0)
        val height = draftHeight(availableHeight)
        val lines = ((height - decorationHeight).coerceAtLeast(0.dp) / lineHeight).toInt()
        return EditorDraftAllocation(height, lines, height >= draftHeightFloor, lines >= minimumLines)
    }

    internal companion object {
        val minimumTargetSize = 48.dp
        val horizontalGutter = 16.dp
        val horizontalGutters = horizontalGutter * 2
        val formatMinimumWidth = minimumTargetSize * 2
        val eventMinimumWidth = 185.dp
        val eventSubmitGap = 8.dp
        val eventGutterExtension = 4.dp
        val eventMinimumContentWidth = eventMinimumWidth + eventSubmitGap
    }
}

/** Insufficient windows stay explicit; the policy never invents height or lowers the J minima. */
data class EditorDraftAllocation(
    val height: Dp,
    val completeLines: Int,
    val meetsHeightFloor: Boolean,
    val meetsLineMinimum: Boolean,
)
