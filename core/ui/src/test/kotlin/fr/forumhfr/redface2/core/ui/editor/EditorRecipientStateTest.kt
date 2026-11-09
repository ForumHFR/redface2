package fr.forumhfr.redface2.core.ui.editor

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EditorRecipientStateTest {
    @Test fun `chips and uncommitted buffer round trip independently including Unicode and separators`() {
        val state = EditorRecipientState(listOf("pseudo with spaces", "é🌍:12"), "half,:🌍 pseudo ")
        assertEquals(state, EditorRecipientState.decode(state.encode()))
        assertEquals("pseudo with spaces,é🌍:12", state.csv)
        assertFalse(state.csv.contains(state.buffer))
        assertNotEquals(state.encode(), state.copy(buffer = "").encode())
        assertNotEquals(state.encode(), EditorRecipientState(state.chips + state.buffer).encode())
        assertEquals(EditorRecipientState(), EditorRecipientState.decode(EditorRecipientState().encode()))
        assertEquals(EditorRecipientState(buffer = "not yet committed"),
            EditorRecipientState.decode(EditorRecipientState(buffer = "not yet committed").encode()))
    }

    @Test fun `legacy CSV restores chips and an empty buffer without changing internal spaces`() {
        assertEquals(EditorRecipientState(listOf("Alice", "Bob with spaces")),
            EditorRecipientState.decode(" Alice, Bob with spaces, "))
        assertEquals(EditorRecipientState(), EditorRecipientState.decode(null))
        assertEquals(EditorRecipientState(), EditorRecipientState.decode(""))
    }

    @Test(expected = IOException::class)
    fun `damaged envelope is never silently interpreted as a recipient`() {
        EditorRecipientState.decode(EditorRecipientState(buffer = "secret").encode().dropLast(1))
    }

    @Test(expected = IOException::class)
    fun `unknown envelope version cannot become a CSV recipient`() {
        EditorRecipientState.decode(EditorRecipientState().encode().replace("v1:", "v2:"))
    }
}
