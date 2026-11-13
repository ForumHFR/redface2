package fr.forumhfr.redface2.core.model.editor

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorRecipientCodecTest {
    @Test fun `offerable recipients exclude empty whitespace and unreadable envelopes`() {
        val empty = EditorRecipientCodec.encode(emptyList(), "")
        val blank = EditorRecipientCodec.encode(listOf("", "  "), " ")
        val damaged = EditorRecipientCodec.encode(listOf("Alice"), "secret").dropLast(1)
        for (stored in listOf(null, "", ", ,", empty, blank, damaged, "\u0000unknown:Alice")) {
            assertFalse(EditorRecipientCodec.hasRecipients(stored))
        }
    }

    @Test fun `chips CSV and an uncommitted recipient all hold content with the same encoding`() {
        val chips = listOf("Alice", "é🌍:12")
        val buffer = "half,:🌍 pseudo "
        assertEquals("\u0000redface-recipients-v1:0:", EditorRecipientCodec.encode(emptyList(), ""))
        assertEquals(chips + buffer, EditorRecipientCodec.decode(EditorRecipientCodec.encode(chips, buffer)))
        assertEquals(listOf("Alice", "Bob", ""), EditorRecipientCodec.decode(" Alice, Bob, "))
        for (stored in listOf("Alice", EditorRecipientCodec.encode(chips, ""),
            EditorRecipientCodec.encode(emptyList(), buffer))) {
            assertTrue(EditorRecipientCodec.hasRecipients(stored))
        }
    }

    @Test(expected = IOException::class)
    fun `decoding still rejects an unreadable envelope instead of silently clearing recipients`() {
        EditorRecipientCodec.decode("\u0000redface-recipients-v1:5:Bob")
    }
}
