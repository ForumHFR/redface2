package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Same synchronous owner contract as [EditorRecipientInput], including rejected/stale input.
 * Truncation keeps the first [maxLength] UTF-16 units, matching the existing String.take guard.
 */
class EditorSubjectInput(
    val maxLength: Int,
    private val readValue: () -> String,
    private val onValueChange: (String) -> Unit,
) {
    init { require(maxLength > 0) }

    internal val buffer = TextFieldState(readValue())
    internal var truncated by mutableStateOf(false)
        private set

    internal val transformation = InputTransformation {
        if (originalText.toString() != readValue()) {
            revertAllChanges()
        } else {
            val exceedsLimit = length > maxLength
            if (exceedsLimit) replace(maxLength, length, "")
            val next = asCharSequence().toString()
            onValueChange(next)
            if (readValue() == next) truncated = exceedsLimit else revertAllChanges()
        }
    }

    internal fun synchronizeBuffer() {
        val text = readValue()
        if (buffer.text.toString() != text) {
            buffer.edit { replace(0, length, text) }
            truncated = false
        }
    }
}
