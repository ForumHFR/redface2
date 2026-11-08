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
    val statusWidths: List<Dp> = emptyList(),
    // ED-30/92: events reserve their measured envelope PLUS the send gap, ahead of fixed slots.
    val minimumContentWidth: Dp = 48.dp,
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
    val widths = listOf(request.availableWidth, request.submitWidth, request.minimumContentWidth) + request.statusWidths
    require(widths.all { it.value.isFinite() && it >= 0.dp })
    require(request.statusWidths.size <= 2)
    require(request.submitWidth >= 48.dp && request.minimumContentWidth >= 48.dp)
    val candidates = if (request.compactHeight) buildList {
        if (request.hasHeader) add(EditorFixedSlot.Header to 48.dp)
        add(EditorFixedSlot.Preview to 48.dp)
        request.statusWidths.forEachIndexed { index, width ->
            add((if (index == 0) EditorFixedSlot.FirstStatus else EditorFixedSlot.SecondStatus) to maxOf(48.dp, width))
        }
    } else emptyList()
    var remaining = (request.availableWidth - 32.dp - request.submitWidth).coerceAtLeast(0.dp)
    val fixed = mutableListOf<EditorFixedSlot>()
    val overflow = mutableListOf<EditorFixedSlot>()
    candidates.forEach { (slot, width) ->
        if (remaining - width >= request.minimumContentWidth) {
            fixed += slot
            remaining -= width
        } else {
            overflow += slot
        }
    }
    return EditorChromeAllocation(fixed, overflow, remaining, remaining >= request.minimumContentWidth)
}

/** ED-38: the shortcut is the third secondary action, never an ED-25 fixed slot. */
fun editorNormalActions(hasShortcut: Boolean): List<EditorSecondaryAction> = buildList {
    add(EditorSecondaryAction.Format)
    add(EditorSecondaryAction.Smileys)
    if (hasShortcut) add(EditorSecondaryAction.Shortcut)
    add(EditorSecondaryAction.Image)
    add(EditorSecondaryAction.Options)
}
