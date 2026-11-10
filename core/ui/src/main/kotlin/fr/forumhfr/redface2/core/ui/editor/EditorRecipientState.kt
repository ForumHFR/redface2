package fr.forumhfr.redface2.core.ui.editor

import java.io.IOException

/** D4: stored only in a private Draft.recipients, never sent as HFR's dest or saved in a Bundle. */
data class EditorRecipientState(val chips: List<String> = emptyList(), val buffer: String = "") {
    val csv: String get() = chips.joinToString(",")

    /** Length-delimited fields preserve spaces, Unicode and a buffer without implicitly committing it. */
    fun encode(): String = buildString {
        append(PREFIX)
        (chips + buffer).forEach { append(it.length).append(':').append(it) }
    }

    companion object {
        // NUL cannot occur in an HFR pseudo, so legacy CSV cannot be mistaken for an envelope.
        private const val PREFIX = "\u0000redface-recipients-v1:"

        /** Legacy CSV keeps its final unterminated segment in the buffer (D4). Invalid envelopes fail closed. */
        @Throws(IOException::class)
        fun decode(stored: String?): EditorRecipientState {
            val value = stored.orEmpty()
            if (!value.startsWith(PREFIX)) {
                if (value.startsWith('\u0000')) invalidCheckpoint()
                val segments = value.split(',').map(String::trim)
                return EditorRecipientState(segments.dropLast(1).filter(String::isNotBlank), segments.last())
            }
            val fields = mutableListOf<String>()
            var offset = PREFIX.length
            while (offset < value.length) {
                val separator = value.indexOf(':', offset)
                if (separator < offset) invalidCheckpoint()
                val length = value.substring(offset, separator).toIntOrNull()
                val remaining = value.length - separator - 1
                if (length == null || length < 0 || length > remaining) {
                    invalidCheckpoint()
                }
                offset = separator + 1
                fields += value.substring(offset, offset + length)
                offset += length
            }
            if (fields.isEmpty()) invalidCheckpoint()
            return EditorRecipientState(fields.dropLast(1), fields.last())
        }

        private fun invalidCheckpoint(): Nothing = throw IOException("Invalid recipient checkpoint")
    }
}
