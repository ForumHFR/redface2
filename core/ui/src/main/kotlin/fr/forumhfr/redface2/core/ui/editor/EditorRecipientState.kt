package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.editor.EditorRecipientCodec
import java.io.IOException

/** D4: stored only in a private Draft.recipients, never sent as HFR's dest or saved in a Bundle. */
data class EditorRecipientState(val chips: List<String> = emptyList(), val buffer: String = "") {
    val csv: String get() = chips.joinToString(",")

    /** Length-delimited fields preserve spaces, Unicode and a buffer without implicitly committing it. */
    fun encode(): String = EditorRecipientCodec.encode(chips, buffer)

    companion object {
        /** ED-110: historical CSV contains committed chips; only envelopes carry a separate D4 buffer. */
        @Throws(IOException::class)
        fun decode(stored: String?): EditorRecipientState {
            val fields = EditorRecipientCodec.decode(stored)
            return EditorRecipientState(fields.dropLast(1), fields.last())
        }
    }
}
