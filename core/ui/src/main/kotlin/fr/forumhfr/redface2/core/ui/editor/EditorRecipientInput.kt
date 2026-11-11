package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Owner-backed input: callbacks update [EditorRecipientState] synchronously, without editing [buffer].
 * Reading the owner for each IME transaction avoids stale chips between two commitText calls.
 * Only the owner persists content; this buffer has no Bundle saver.
 */
class EditorRecipientInput(
    private val readValue: () -> EditorRecipientState,
    private val onValueChange: (EditorRecipientState) -> Unit,
) {
    internal val buffer = TextFieldState(readValue().buffer)
    internal var change by mutableStateOf<EditorRecipientChange?>(null)
        private set

    internal val transformation = InputTransformation {
        val current = readValue()
        val text = asCharSequence().toString()
        if (originalText.toString() != current.buffer) {
            // A restore/account change arrived before the field could synchronize.
            revertAllChanges()
        } else if (text != current.buffer) {
            val segments = text.split(',')
            // Merge exact duplicates after trimming edges; preserve case, inner spaces and first occurrence.
            val added = segments.dropLast(1).map(String::trim).filter(String::isNotBlank)
                .distinct().filterNot { it in current.chips }
            val next = current.copy(
                chips = current.chips + added,
                buffer = segments.last(),
            )
            onValueChange(next)
            if (readValue() == next) {
                if (added.isNotEmpty()) change = EditorRecipientChange(added, added = true)
                // Delete only the committed prefix: BTF2 maps the selection in the remaining tail.
                val prefixLength = text.lastIndexOf(',') + 1
                if (prefixLength > 0) replace(0, prefixLength, "")
            } else {
                // The owner may reject an edit during submission, even before recomposition.
                revertAllChanges()
            }
        }
    }

    internal fun synchronizeBuffer() {
        val text = readValue().buffer
        if (buffer.text.toString() != text) buffer.edit {
            replace(0, length, text)
        }
    }

    internal fun remove(index: Int, rendered: EditorRecipientState) {
        // A delayed accessibility action must not remove another chip after a list change.
        if (readValue() == rendered) {
            val next = rendered.copy(chips = rendered.chips.filterIndexed { position, _ -> position != index })
            onValueChange(next)
            if (readValue() == next) change = EditorRecipientChange(listOf(rendered.chips[index]), added = false)
        }
    }
}

internal data class EditorRecipientChange(val recipients: List<String>, val added: Boolean)
