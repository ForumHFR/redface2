package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import fr.forumhfr.redface2.core.model.PostInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ──────────────────────────────────────────────────────────────────────
    // ensureReadableColor — author [color] legibility clamp (state-hygiene audit 2026-07-05).
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `navy is lifted to the dark floor in dark theme`() {
        val navy = parseColor("#000080")
        val adjusted = ensureReadableColor(navy, isDark = true)

        assertTrue("navy (#000080) must be lightened on a dark surface", adjusted.luminance() > navy.luminance())
        assertTrue(
            "the adjusted colour must reach the dark floor",
            adjusted.luminance() >= MIN_DARK_LUMINANCE,
        )
    }

    @Test
    fun `pure yellow is darkened to the light ceiling in light theme`() {
        val yellow = parseColor("#FFFF00")
        val adjusted = ensureReadableColor(yellow, isDark = false)

        assertTrue("yellow (#FFFF00) must be darkened on a light surface", adjusted.luminance() < yellow.luminance())
        assertTrue(
            "the adjusted colour must come down to the light ceiling",
            adjusted.luminance() <= MAX_LIGHT_LUMINANCE,
        )
    }

    @Test
    fun `an already-readable red passes through untouched in both themes`() {
        // #CC0000 sits between the two thresholds (luminance ≈ 0.13): a clamp, not a remap.
        val red = parseColor("#CC0000")
        assertEquals(red, ensureReadableColor(red, isDark = true))
        assertEquals(red, ensureReadableColor(red, isDark = false))
    }

    @Test
    fun `dark colours are untouched in light theme and light colours in dark theme`() {
        // Each threshold only applies to its own theme.
        val navy = parseColor("#000080")
        assertEquals(navy, ensureReadableColor(navy, isDark = false))
        val yellow = parseColor("#FFFF00")
        assertEquals(yellow, ensureReadableColor(yellow, isDark = true))
    }

    @Test
    fun `Unspecified passes through the clamp`() {
        // parseColor returns Unspecified for malformed hex; SpanStyle treats it as "inherit".
        assertEquals(Color.Unspecified, ensureReadableColor(Color.Unspecified, isDark = true))
        assertEquals(Color.Unspecified, ensureReadableColor(Color.Unspecified, isDark = false))
    }

    @Test
    fun `clamping preserves the hue family`() {
        // The lerp runs in Oklab (perceptual hue), so the HSV hue can drift a little — lifted
        // navy measures ≈ 221° vs 240° (Oklab's Abney correction for lightened deep blues) — but
        // the colour must stay in its hue FAMILY: a flip to grey/purple/green would land far
        // outside these bands.
        val liftedNavy = ensureReadableColor(parseColor("#000080"), isDark = true)
        val navyHue = hueOf(liftedNavy)
        assertTrue("lifted navy must still read as a blue (hue was $navyHue)", navyHue in 200.0..260.0)

        val loweredYellow = ensureReadableColor(parseColor("#FFFF00"), isDark = false)
        val yellowHue = hueOf(loweredYellow)
        assertTrue("darkened yellow must still read as a yellow (hue was $yellowHue)", yellowHue in 45.0..75.0)
    }

    @Test
    fun `buildInlineText clamps the colour span when isDark is true`() {
        val inlines = listOf(
            PostInline.Color(colorHex = "#000080", children = listOf(PostInline.Text("navy text"))),
        )

        val annotated = buildInlineText(inlines, TextLinkStyles(), imageAlt = "img", isDark = true)

        val span = annotated.spanStyles.single().item as SpanStyle
        assertEquals(ensureReadableColor(parseColor("#000080"), isDark = true), span.color)
        assertTrue("the applied span must sit at/above the dark floor", span.color.luminance() >= MIN_DARK_LUMINANCE)
    }

    @Test
    fun `buildInlineText keeps the raw author colour when isDark is false`() {
        val inlines = listOf(
            PostInline.Color(colorHex = "#000080", children = listOf(PostInline.Text("navy text"))),
        )

        val annotated = buildInlineText(inlines, TextLinkStyles(), imageAlt = "img", isDark = false)

        val span = annotated.spanStyles.single().item as SpanStyle
        assertEquals("a dark hue is fine on a light surface", parseColor("#000080"), span.color)
    }

    /** Plain HSV hue in degrees — enough to pin "still a blue / still a yellow" after the clamp. */
    private fun hueOf(color: Color): Double {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta == 0f) return 0.0
        val h = when (max) {
            r -> ((g - b) / delta) % 6f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
        return ((h * 60f) + 360f).toDouble() % 360.0
    }
}
