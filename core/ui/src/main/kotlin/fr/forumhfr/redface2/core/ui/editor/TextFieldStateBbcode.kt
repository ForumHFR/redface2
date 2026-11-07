package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/** Apply formatting to the live buffer and selection in a single undoable transaction. */
fun TextFieldState.editBbcode(format: (String, Int, Int) -> BbcodeFormatResult) {
    edit {
        val before = asCharSequence().toString()
        val result = format(before, selection.start, selection.end)
        if (before != result.text) {
            val change = minimalReplacement(before, result.text)
            replace(change.start, change.end, change.text)
        }
        selection = TextRange(
            result.selectionStart.coerceIn(0, length),
            result.selectionEnd.coerceIn(0, length),
        )
    }
}
