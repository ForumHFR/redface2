package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import fr.forumhfr.redface2.core.domain.preferences.DarkSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.ThemeColorPreferences
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.LocalEgoQuotePseudo
import fr.forumhfr.redface2.core.ui.theme.RedfaceAmoledColorScheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceLightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererColorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `moderation routes body link and variant colours and neutralises author color`() {
        val palette = moderationHighlightColors(Color.White)
        val content = PostContent(
            blocks = listOf(
                PostBlock.Paragraph(
                    inlines = listOf(
                        PostInline.Text("corps "),
                        PostInline.Link(
                            url = "https://forum.hardware.fr",
                            children = listOf(PostInline.Text("lien ")),
                        ),
                        PostInline.Color(
                            colorHex = "#000000",
                            children = listOf(PostInline.Text("couleur auteur")),
                        ),
                    ),
                ),
                PostBlock.Quote(
                    author = "Lecteur",
                    numreponse = null,
                    page = null,
                    content = paragraph("texte cité"),
                ),
            ),
        )
        setModerationContent(palette) { PostRenderer(content) }

        val paragraphNode = composeTestRule.onNodeWithText("corps", substring = true, useUnmergedTree = true)
        paragraphNode
            .assert(SemanticsMatcher.expectValue(PostRendererBodyColorKey, palette.onModeration))
            .assert(SemanticsMatcher.expectValue(PostRendererLinkColorKey, palette.linkColor))
        val annotated = paragraphNode.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        // The author `[color]` is neutralised under moderation, so no span carries it; the link is
        // the only legitimately coloured span and it follows the moderation linkColor.
        val colouredSpans = annotated.spanStyles.map { it.item.color }.filter { it != Color.Unspecified }
        assertTrue(
            "author [color] must be dropped under the moderation local, link keeps its colour",
            colouredSpans.all { it == palette.linkColor },
        )
        assertEquals(palette.onModerationVariant, textColor("Citation de Lecteur"))
        assertEquals(palette.onModeration, textColor("texte cité"))
    }

    @Test
    fun `normal post keeps author color and neutral Material reading roles`() {
        composeTestRule.setContent {
            TestTheme {
                PostRenderer(
                    content = PostContent(
                        blocks = listOf(
                            PostBlock.Paragraph(
                                inlines = listOf(
                                    PostInline.Color(
                                        colorHex = "#123456",
                                        children = listOf(PostInline.Text("couleur normale")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }

        val node = composeTestRule.onNodeWithText("couleur normale", useUnmergedTree = true)
        val annotated = node.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(annotated.spanStyles.any { it.item.color == Color(0xFF123456) })
        node
            .assert(
                SemanticsMatcher.expectValue(
                    PostRendererBodyColorKey,
                    RedfaceLightColorScheme.onSurface,
                ),
            )
            .assert(
                SemanticsMatcher.expectValue(
                    PostRendererLinkColorKey,
                    RedfaceLightColorScheme.primary,
                ),
            )
    }

    @Test
    fun `quote spoiler and code use the RF1 red sub-surface under moderation`() {
        val palette = moderationHighlightColors(Color.White)
        val content = PostContent(
            blocks = listOf(
                PostBlock.Quote(
                    author = "Modération",
                    numreponse = 42,
                    page = 1,
                    content = paragraph("citation"),
                ),
                PostBlock.Spoiler(label = "secret", content = paragraph("masqué")),
                PostBlock.CodeBlock(text = "println(42)", language = "kotlin"),
            ),
        )
        setModerationContent(palette) {
            CompositionLocalProvider(LocalEgoQuotePseudo provides "modération") {
                PostRenderer(content)
            }
        }

        listOf(
            POST_RENDERER_QUOTE_CONTAINER_TAG,
            POST_RENDERER_SPOILER_CONTAINER_TAG,
            POST_RENDERER_MONOSPACE_CONTAINER_TAG,
        ).forEach { tag ->
            composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)
                .assert(
                    SemanticsMatcher.expectValue(
                        PostRendererContainerColorKey,
                        palette.subSurfaceContainer,
                    ),
                )
        }
        assertEquals(palette.onModerationVariant, textColor("secret"))
        assertEquals(palette.onModerationVariant, textColor("kotlin"))
        assertEquals(palette.onModeration, textColor("println(42)"))
    }

    @Test
    fun `AMOLED spoiler uses surfaceBright instead of the near-black low container`() {
        val content = PostContent(
            blocks = listOf(PostBlock.Spoiler(label = "secret", content = paragraph("masqué"))),
        )

        composeTestRule.setContent {
            RedfaceTheme(
                darkTheme = true,
                themeColorPreferences = ThemeColorPreferences(darkSurfaceTone = DarkSurfaceTone.AMOLED),
            ) {
                PostRenderer(content)
            }
        }

        val spoilerContainer = RedfaceAmoledColorScheme.surfaceBright
        assertNotEquals(RedfaceAmoledColorScheme.surfaceContainerLow, spoilerContainer)
        assertTrue(rgbDistance(spoilerContainer, Color.Black) >= MIN_AMOLED_SPOILER_DISTANCE)
        composeTestRule.onNodeWithTag(POST_RENDERER_SPOILER_CONTAINER_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PostRendererContainerColorKey, spoilerContainer))
    }

    @Test
    fun `EgoQuote turns red under the moderation local`() {
        val quote = PostBlock.Quote(
            author = "Moi",
            numreponse = 42,
            page = 1,
            content = paragraph("ego quote"),
        )
        val moderation = moderationHighlightColors(Color.White)
        setModerationContent(moderation) {
            CompositionLocalProvider(LocalEgoQuotePseudo provides "moi") {
                PostRenderer(PostContent(listOf(quote)))
            }
        }
        composeTestRule.onNodeWithTag(POST_RENDERER_QUOTE_CONTAINER_TAG, useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    PostRendererContainerColorKey,
                    moderation.subSurfaceContainer,
                ),
            )
    }

    @Test
    fun `EgoQuote keeps its violet container without the moderation local`() {
        val quote = PostBlock.Quote(
            author = "Moi",
            numreponse = 42,
            page = 1,
            content = paragraph("ego quote"),
        )
        composeTestRule.setContent {
            TestTheme {
                CompositionLocalProvider(LocalEgoQuotePseudo provides "moi") {
                    PostRenderer(PostContent(listOf(quote)))
                }
            }
        }
        composeTestRule.onNodeWithTag(POST_RENDERER_QUOTE_CONTAINER_TAG, useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    PostRendererContainerColorKey,
                    Color(0xFFEDE7FF),
                ),
            )
    }

    @Test
    fun `block and inline image errors inherit moderation variant white`() {
        val palette = moderationHighlightColors(Color.White)
        setModerationContent(palette) {
            Column {
                ImageBlockError(description = "bloc")
                InlineImageErrorSlot(url = "https://invalid.example/image.png", description = "inline")
            }
        }

        composeTestRule.onNodeWithTag(POST_RENDERER_BLOCK_IMAGE_ERROR_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PostRendererContainerColorKey, palette.subSurfaceContainer))
            .assert(SemanticsMatcher.expectValue(PostRendererContentColorKey, palette.onModerationVariant))
        composeTestRule.onNodeWithTag(POST_RENDERER_INLINE_IMAGE_ERROR_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PostRendererContentColorKey, palette.onModerationVariant))
    }

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

    private fun setModerationContent(
        palette: ModerationHighlightColors,
        content: @Composable () -> Unit,
    ) {
        composeTestRule.setContent {
            TestTheme {
                val reading = ReadingContentColors(
                    onBody = palette.onModeration,
                    onBodyVariant = palette.onModerationVariant,
                    linkColor = palette.linkColor,
                )
                CompositionLocalProvider(
                    LocalModerationHighlightColors provides palette,
                    LocalReadingContentColors provides reading,
                    LocalContentColor provides reading.onBody,
                    content = content,
                )
            }
        }
    }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
            Surface(color = MaterialTheme.colorScheme.surface, content = content)
        }
    }

    private fun textColor(text: String): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = requireNotNull(
            composeTestRule.onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action,
        )
        assertTrue(action(layouts))
        return layouts.single().layoutInput.style.color
    }

    private fun paragraph(text: String): PostContent = PostContent(
        blocks = listOf(PostBlock.Paragraph(inlines = listOf(PostInline.Text(text)))),
    )

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

    private fun rgbDistance(first: Color, second: Color): Float {
        val red = first.red - second.red
        val green = first.green - second.green
        val blue = first.blue - second.blue
        return kotlin.math.sqrt(red * red + green * green + blue * blue)
    }

    private companion object {
        const val MIN_AMOLED_SPOILER_DISTANCE = 0.1f
    }
}
