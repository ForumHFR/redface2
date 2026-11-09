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

        /** Existing CSV rows become chips with an empty buffer. Invalid envelopes fail closed. */
        @Throws(IOException::class)
        fun decode(stored: String?): EditorRecipientState {
            val value = stored.orEmpty()
            if (!value.startsWith(PREFIX)) {
                if (value.startsWith('\u0000')) invalidCheckpoint()
                // C1 will settle D4's legacy CSV trailing-segment/buffer policy; preserve it for S3.
                return EditorRecipientState(value.split(',').map(String::trim).filter(String::isNotBlank))
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
