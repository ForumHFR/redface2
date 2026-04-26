package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class PostRendererColorTest {

    @Test
    fun `parses six-digit hex with hash prefix`() {
        assertEquals(Color(red = 0xFF, green = 0x00, blue = 0x00), parseColor("#FF0000"))
        assertEquals(Color(red = 0x00, green = 0xFF, blue = 0x00), parseColor("#00FF00"))
        assertEquals(Color(red = 0x12, green = 0x34, blue = 0x56), parseColor("#123456"))
    }

    @Test
    fun `parses six-digit hex without hash prefix`() {
        assertEquals(Color(red = 0xFF, green = 0x00, blue = 0x00), parseColor("FF0000"))
    }

    @Test
    fun `parses eight-digit hex as RRGGBBAA`() {
        // Defensive path: HFR never emits alpha today, but the helper supports the longer form.
        // 0xFF0000_80 = opaque-ish red with 50% alpha (0x80 = 128 / 255).
        val parsed = parseColor("#FF000080")
        assertEquals(Color(red = 0xFF, green = 0x00, blue = 0x00, alpha = 0x80), parsed)
    }

    @Test
    fun `returns Unspecified for empty input`() {
        assertEquals(Color.Unspecified, parseColor(""))
    }

    @Test
    fun `returns Unspecified for non-canonical lengths`() {
        assertEquals(Color.Unspecified, parseColor("#FFF"))
        assertEquals(Color.Unspecified, parseColor("#FFFFFFFFFF"))
        assertEquals(Color.Unspecified, parseColor("garbage"))
    }
}
