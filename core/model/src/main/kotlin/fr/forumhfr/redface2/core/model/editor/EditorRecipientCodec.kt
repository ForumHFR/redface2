package fr.forumhfr.redface2.core.model.editor

import java.io.IOException

/** Private draft encoding shared by the editor and storage; the last field is the uncommitted buffer. */
object EditorRecipientCodec {
    // NUL cannot occur in an HFR pseudo, so legacy CSV cannot be mistaken for an envelope.
    private const val PREFIX = "\u0000redface-recipients-v1:"

    fun encode(chips: List<String>, buffer: String): String = buildString {
        append(PREFIX)
        (chips + buffer).forEach { append(it.length).append(':').append(it) }
    }

    /** ED-110: historical CSV has committed chips and no buffer. Unknown encodings stay unreadable. */
    @Throws(IOException::class)
    fun decode(stored: String?): List<String> {
        val value = stored.orEmpty()
        if (!value.startsWith(PREFIX)) {
            if (value.startsWith('\u0000')) invalidCheckpoint()
            return value.split(',').map(String::trim).filter(String::isNotBlank) + ""
        }
        val fields = mutableListOf<String>()
        var offset = PREFIX.length
        while (offset < value.length) {
            val separator = value.indexOf(':', offset)
            if (separator < offset) invalidCheckpoint()
            val length = value.substring(offset, separator).toIntOrNull()
            val remaining = value.length - separator - 1
            if (length == null || length < 0 || length > remaining) invalidCheckpoint()
            offset = separator + 1
            fields += value.substring(offset, offset + length)
            offset += length
        }
        if (fields.isEmpty()) invalidCheckpoint()
        return fields
    }

    /** ED-121: an empty or unreadable envelope names nobody; a nonblank buffer is user content too. */
    fun hasRecipients(stored: String?): Boolean = try {
        decode(stored).any(String::isNotBlank)
    } catch (_: IOException) {
        false
    }

    private fun invalidCheckpoint(): Nothing = throw IOException("Invalid recipient checkpoint")
}
