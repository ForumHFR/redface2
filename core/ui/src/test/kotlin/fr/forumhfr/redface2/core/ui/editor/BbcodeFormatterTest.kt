package fr.forumhfr.redface2.core.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class BbcodeFormatterTest {

    @Test
    fun `non-empty selection is wrapped and the new selection still wraps the content`() {
        val result = applyBbcodeAction(
            action = BbcodeAction.Bold,
            text = "hello",
            selectionStart = 0,
            selectionEnd = 5,
        )
        assertEquals("[b]hello[/b]", result.text)
        assertEquals(3, result.selectionStart)
        assertEquals(8, result.selectionEnd)
    }

    @Test
    fun `empty selection inserts both tags and places the caret between them`() {
        val result = applyBbcodeAction(
            action = BbcodeAction.Bold,
            text = "",
            selectionStart = 0,
            selectionEnd = 0,
        )
        assertEquals("[b][/b]", result.text)
        assertEquals(3, result.selectionStart)
        assertEquals(3, result.selectionEnd)
    }

    @Test
    fun `selection in the middle wraps only the selected text`() {
        val result = applyBbcodeAction(
            action = BbcodeAction.Italic,
            text = "alpha beta gamma",
            selectionStart = 6,
            selectionEnd = 10,
        )
        assertEquals("alpha [i]beta[/i] gamma", result.text)
        assertEquals(9, result.selectionStart)
        assertEquals(13, result.selectionEnd)
    }

    @Test
    fun `selection across line breaks is preserved verbatim`() {
        val result = applyBbcodeAction(
            action = BbcodeAction.Quote,
            text = "line1\nline2\nline3",
            selectionStart = 0,
            selectionEnd = 11,
        )
        assertEquals("[quote]line1\nline2[/quote]\nline3", result.text)
    }

    @Test
    fun `block-level fixed tag uses the same wrap`() {
        val result = applyBbcodeAction(
            action = BbcodeAction.Fixed,
            text = "mono",
            selectionStart = 0,
            selectionEnd = 4,
        )
        assertEquals("[fixed]mono[/fixed]", result.text)
    }

    @Test
    fun `cpp tag uses the HFR-real cpp tag, not code`() {
        val result = applyBbcodeAction(
            action = BbcodeAction.Cpp,
            text = "int x;",
            selectionStart = 0,
            selectionEnd = 6,
        )
        assertEquals("[cpp]int x;[/cpp]", result.text)
    }

    @Test
    fun `url tag wraps the selection in default form`() {
        val result = applyBbcodeAction(
            action = BbcodeAction.Url,
            text = "https://example.com",
            selectionStart = 0,
            selectionEnd = 19,
        )
        assertEquals("[url]https://example.com[/url]", result.text)
    }

    @Test
    fun `image tag wraps the selection in default form`() {
        val result = applyBbcodeAction(
            action = BbcodeAction.Image,
            text = "https://example.com/a.png",
            selectionStart = 0,
            selectionEnd = 25,
        )
        assertEquals("[img]https://example.com/a.png[/img]", result.text)
    }

    @Test
    fun `inverted selection is normalised before wrapping`() {
        val result = applyBbcodeAction(
            action = BbcodeAction.Bold,
            text = "hello",
            selectionStart = 5,
            selectionEnd = 0,
        )
        assertEquals("[b]hello[/b]", result.text)
        assertEquals(3, result.selectionStart)
        assertEquals(8, result.selectionEnd)
    }

    @Test
    fun `out-of-range selection is clamped`() {
        val result = applyBbcodeAction(
            action = BbcodeAction.Bold,
            text = "abc",
            selectionStart = -2,
            selectionEnd = 100,
        )
        assertEquals("[b]abc[/b]", result.text)
        assertEquals(3, result.selectionStart)
        assertEquals(6, result.selectionEnd)
    }
}
