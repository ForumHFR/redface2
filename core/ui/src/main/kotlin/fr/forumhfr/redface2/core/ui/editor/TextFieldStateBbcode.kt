package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/** Apply formatting to the live buffer and selection in a single undoable transaction. */
fun TextFieldState.editBbcode(format: (String, Int, Int) -> BbcodeFormatResult) {
    edit {
        val before = asCharSequence().toString()
        val result = format(before, selection.start, selection.end)
        if (before != result.text) {
            val change = bbcodeChangedRange(before, result.text)
            replace(change.start, change.oldEnd, result.text.substring(change.start, change.newEnd))
        }
        selection = TextRange(
            result.selectionStart.coerceIn(0, length),
            result.selectionEnd.coerceIn(0, length),
        )
    }
}

/** Exclusive ends, with a common suffix bounded by the prefix so neither side overlaps. */
internal data class BbcodeChangedRange(val start: Int, val oldEnd: Int, val newEnd: Int)

internal fun bbcodeChangedRange(before: String, after: String): BbcodeChangedRange {
    val shortest = minOf(before.length, after.length)
    var prefix = 0
    while (prefix < shortest && before[prefix] == after[prefix]) prefix++
    var suffix = 0
    while (suffix < shortest - prefix && before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) {
        suffix++
    }
    return BbcodeChangedRange(prefix, before.length - suffix, after.length - suffix)
}
