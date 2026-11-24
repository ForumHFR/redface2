package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class EditorFixedSlot { Header, Preview, FirstStatus, SecondStatus }
enum class EditorSecondaryAction { Format, Smileys, Shortcut, Image, Options }

/** Width AFTER ED-04 safeDrawing horizontal insets; gutters are subtracted here exactly once. */
data class EditorChromeLayoutRequest(
    val availableWidth: Dp,
    val submitWidth: Dp,
    val compactHeight: Boolean,
    val hasHeader: Boolean = false,
    // Independent identities: null means absent; a present target reserves at least 48 dp.
    val firstStatusWidth: Dp? = null,
    val secondStatusWidth: Dp? = null,
    // ED-25/30: scrolling target at rest; full event envelope plus Send gap during an event.
    val minimumContentWidth: Dp = EditorChromeMetrics.minimumContentWidth,
)

data class EditorChromeAllocation(
    val fixedSlots: List<EditorFixedSlot>,
    // In tools modes these lead the scrolling row; during events the host hides them (ED-30).
    val overflowSlots: List<EditorFixedSlot>,
    val scrollingWidth: Dp,
    val hasMinimumContentWidth: Boolean,
)

/** ED-25: Send > Header > Preview > states. No orientation or guessed send width. */
fun allocateEditorChrome(request: EditorChromeLayoutRequest): EditorChromeAllocation {
    val widths = listOfNotNull(request.availableWidth, request.submitWidth, request.minimumContentWidth,
        request.firstStatusWidth, request.secondStatusWidth)
    if (widths.any { !it.value.isFinite() || it < 0.dp }) {
        return EditorChromeAllocation(emptyList(), emptyList(), 0.dp, hasMinimumContentWidth = false)
    }
    val target = EditorChromeMetrics.minimumTargetSize
    val minimum = request.minimumContentWidth.coerceAtLeast(EditorChromeMetrics.minimumContentWidth)
    val candidates = if (request.compactHeight) buildList {
        if (request.hasHeader) add(EditorFixedSlot.Header to target)
        add(EditorFixedSlot.Preview to target)
        request.firstStatusWidth?.let { add(EditorFixedSlot.FirstStatus to maxOf(target, it)) }
        request.secondStatusWidth?.let { add(EditorFixedSlot.SecondStatus to maxOf(target, it)) }
    } else emptyList()
    // A 48 dp touch target can measure slightly less after rounding to whole pixels.
    var remaining = (request.availableWidth - EditorChromeMetrics.horizontalGutters - request.submitWidth)
        .coerceAtLeast(0.dp)
    val fixed = mutableListOf<EditorFixedSlot>()
    val overflow = mutableListOf<EditorFixedSlot>()
    candidates.forEach { (slot, width) ->
        // Keep a priority prefix so masking a wide status never shifts a lower-priority status left.
        if (overflow.isEmpty() && remaining - width >= minimum) {
            fixed += slot
            remaining -= width
        } else {
            overflow += slot
        }
    }
    return EditorChromeAllocation(fixed, overflow, remaining, remaining >= minimum)
}

/** ED-38: the shortcut is the third secondary action, never an ED-25 fixed slot. */
fun editorNormalActions(hasShortcut: Boolean): List<EditorSecondaryAction> = buildList {
    add(EditorSecondaryAction.Format)
    add(EditorSecondaryAction.Smileys)
    if (hasShortcut) add(EditorSecondaryAction.Shortcut)
    add(EditorSecondaryAction.Image)
    add(EditorSecondaryAction.Options)
}
