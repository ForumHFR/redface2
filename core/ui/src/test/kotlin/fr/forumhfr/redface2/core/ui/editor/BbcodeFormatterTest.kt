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

    @Test
    fun `color action wraps the selection with HFR-real hex tags`() {
        val result = applyBbcodeAction(
            action = BbcodeAction.Color("#FF0000"),
            text = "texte",
            selectionStart = 0,
            selectionEnd = 5,
        )
        // HFR contract : closing tag echoes the same hex, not [/color].
        assertEquals("[#FF0000]texte[/#FF0000]", result.text)
        // Selection stays around the wrapped content : 9 chars of opening tag,
        // then 5 chars of "texte".
        assertEquals(9, result.selectionStart)
        assertEquals(14, result.selectionEnd)
    }

    @Test
    fun `color action with empty selection places the caret between tags`() {
        val result = applyBbcodeAction(
            action = BbcodeAction.Color("#0000FF"),
            text = "",
            selectionStart = 0,
            selectionEnd = 0,
        )
        assertEquals("[#0000FF][/#0000FF]", result.text)
        assertEquals(9, result.selectionStart)
        assertEquals(9, result.selectionEnd)
    }

    @Test
    fun `color action preserves surrounding text and selects only the wrapped portion`() {
        val result = applyBbcodeAction(
            action = BbcodeAction.Color("#008000"),
            text = "alpha beta gamma",
            selectionStart = 6,
            selectionEnd = 10,
        )
        assertEquals("alpha [#008000]beta[/#008000] gamma", result.text)
        assertEquals(15, result.selectionStart)
        assertEquals(19, result.selectionEnd)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `color action rejects a malformed hex value`() {
        BbcodeAction.Color("red")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `color action rejects a hex value without the leading hash`() {
        BbcodeAction.Color("FF0000")
    }

    @Test
    fun `color action accepts lowercase hex digits`() {
        // HFR is case-insensitive on the hex itself ; we accept lowercase so
        // a palette swatch like "#ff6600" works without forcing the caller to
        // upper-case it.
        val result = applyBbcodeAction(
            action = BbcodeAction.Color("#ff6600"),
            text = "x",
            selectionStart = 0,
            selectionEnd = 1,
        )
        assertEquals("[#ff6600]x[/#ff6600]", result.text)
    }
}
