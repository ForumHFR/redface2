package fr.forumhfr.redface2.core.data.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [sanitizeContentForm] contract (#114). HFR silently truncates a post at the first
 * code point it cannot store (non-BMP / emoji), so the helper must drop every astral code point
 * and every lone surrogate while leaving the whole BMP — BBCode, accents, combining marks,
 * whitespace, punctuation, high-BMP selectors — untouched.
 *
 * Astral and invisible code points are written with explicit escapes / [Character.toChars] so the
 * test source stays unambiguous (no relying on the editor to preserve a raw emoji or a zero-width
 * selector verbatim).
 */
class ContentFormSanitizerTest {

    /** Renders a single code point (BMP or astral) to its String form. */
    private fun cp(codePoint: Int): String = String(Character.toChars(codePoint))

    @Test
    fun `emoji between text is removed and surrounding text preserved`() {
        // U+1F600 GRINNING FACE.
        assertEquals("beforeafter", sanitizeContentForm("before" + cp(0x1F600) + "after"))
    }

    @Test
    fun `multiple emojis and a ZWJ sequence are fully stripped of astral parts`() {
        // Family ZWJ sequence: man + ZWJ(U+200D, BMP) + woman + ZWJ + girl. The astral faces go,
        // the BMP zero-width joiners between them stay (valid BMP scalar values).
        val zwj = cp(0x200D)
        val family = cp(0x1F468) + zwj + cp(0x1F469) + zwj + cp(0x1F467)
        assertEquals("a$zwj${zwj}b", sanitizeContentForm("a${family}b"))
    }

    @Test
    fun `bbcode tags and accented latin are left untouched`() {
        val bbcode = "[b]éà œ ÿ ç[/b] [url=http://x]lien[/url]"
        assertEquals(bbcode, sanitizeContentForm(bbcode))
    }

    @Test
    fun `combining marks (BMP) survive`() {
        // 'e' + U+0301 COMBINING ACUTE ACCENT — both BMP, must be kept verbatim.
        val combining = "e" + cp(0x0301) + "galite"
        assertEquals(combining, sanitizeContentForm(combining))
    }

    @Test
    fun `lone high surrogate is removed`() {
        assertEquals("ab", sanitizeContentForm("a\uD83Db"))
    }

    @Test
    fun `lone low surrogate is removed`() {
        assertEquals("ab", sanitizeContentForm("a\uDE00b"))
    }

    @Test
    fun `whitespace tabs and newlines are preserved`() {
        val text = "line1\nline2\r\n\tindented  spaced"
        assertEquals(text, sanitizeContentForm(text))
    }

    @Test
    fun `empty string is unchanged`() {
        assertEquals("", sanitizeContentForm(""))
    }

    @Test
    fun `pure BMP content takes the fast path unchanged`() {
        val text = "Tout en BMP : ponctuation ! ? ; : , 12345 #@%"
        assertEquals(text, sanitizeContentForm(text))
    }

    @Test
    fun `high-BMP characters above the surrogate range are preserved`() {
        // Valid BMP scalar values just above the surrogate range that must survive the slow path:
        // U+E000 private-use, U+FE0F VARIATION SELECTOR-16, U+FF21 FULLWIDTH LATIN A,
        // U+20E3 COMBINING ENCLOSING KEYCAP, U+2764 HEAVY BLACK HEART.
        val highBmp = "a" + cp(0xE000) + cp(0xFE0F) + cp(0xFF21) + cp(0x20E3) + cp(0x2764) + "b"
        assertEquals(highBmp, sanitizeContentForm(highBmp))
    }

    @Test
    fun `astral base with a BMP variation selector keeps only the selector`() {
        // U+1F642 SLIGHTLY SMILING FACE (astral) + U+FE0F (BMP selector): astral dropped, selector kept.
        assertEquals(cp(0xFE0F), sanitizeContentForm(cp(0x1F642) + cp(0xFE0F)))
    }

    @Test
    fun `string made only of astral code points becomes empty`() {
        // U+1F600 + U+1F601, nothing else.
        assertEquals("", sanitizeContentForm(cp(0x1F600) + cp(0x1F601)))
    }

    // --- containsUnstorableContent : the DETECTION twin used by MPStorage's fail-closed path (C4) ----

    @Test
    fun `containsUnstorableContent is false for pure-BMP content (no truncation vector)`() {
        // Same BMP scope sanitizeContentForm preserves: BBCode, accents, high-BMP selectors → all safe.
        assertFalse(containsUnstorableContent("café [b]œuvre[/b] ! 12345"))
        val highBmp = "a" + cp(0xE000) + cp(0xFE0F) + cp(0xFF21) + cp(0x2764) + "b"
        assertFalse(containsUnstorableContent(highBmp))
        assertFalse(containsUnstorableContent(""))
    }

    @Test
    fun `containsUnstorableContent is true for an astral code point (emoji)`() {
        // U+1F600 — the exact vector HFR truncates at. MPStorage fails closed rather than strip it.
        assertTrue(containsUnstorableContent("note " + cp(0x1F600) + " end"))
        assertTrue(containsUnstorableContent(cp(0x1F642) + cp(0xFE0F))) // astral base + BMP selector
    }

    @Test
    fun `containsUnstorableContent is true for a lone unpaired surrogate`() {
        // A lone high surrogate (U+D800) is < U+10000 yet never a valid scalar value — must be detected,
        // mirroring sanitizeContentForm's explicit surrogate-range test.
        assertTrue(containsUnstorableContent("x" + cp(0xD800) + "y"))
    }
}
