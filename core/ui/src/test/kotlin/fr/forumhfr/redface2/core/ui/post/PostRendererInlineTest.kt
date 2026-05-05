package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.text.TextLinkStyles
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostRendererInlineTest {

    private val emptyLinkStyles = TextLinkStyles()

    @Test
    fun `LineBreak inside Strong preserves the newline character in the AnnotatedString`() {
        val inlines = listOf(
            PostInline.Strong(
                children = listOf(
                    PostInline.Text("hello"),
                    PostInline.LineBreak,
                    PostInline.Text("world"),
                ),
            ),
        )

        val annotated = buildInlineText(inlines, emptyLinkStyles, imageAlt = "img")

        assertEquals("hello\nworld", annotated.text)
    }

    @Test
    fun `appendInline IDs match collectInlineMedia keys for smileys with image URLs`() {
        // Builtin smiley without imageUrl emits the textual token in the AnnotatedString and
        // contributes NO entry to the inline-media map. Two smileys with imageUrl emit
        // appendInlineContent IDs (post-smiley-0, post-smiley-1) and ARE present in the map.
        // The two parallel walks must agree on which inline gets a placeholder ID.
        val inlines = listOf(
            PostInline.Smiley(kind = SmileyKind.Builtin(":jap:"), imageUrl = null),
            PostInline.Smiley(
                kind = SmileyKind.Builtin(":o"),
                imageUrl = "https://forum.hardware.fr/images/perso/o.gif",
            ),
            PostInline.Smiley(
                kind = SmileyKind.Perso("ouch"),
                imageUrl = "https://forum.hardware.fr/images/perso/ouch.gif",
            ),
        )

        val annotated = buildInlineText(inlines, emptyLinkStyles, imageAlt = "img")
        val media = collectInlineMedia(inlines)

        // The textual token of the first smiley (no imageUrl) lands in the plain-text content,
        // alongside two placeholders for the IDs the second and third smileys emit.
        assertTrue(annotated.text.contains(":jap:"))
        assertEquals(setOf("post-smiley-0", "post-smiley-1"), media.keys)
        // appendInlineContent IDs and map keys MUST match — that's the invariant the comment on
        // MediaCounter spells out.
        assertEquals(media.keys, annotated.inlineContentIds())
    }

    @Test
    fun `MediaCounter recursion is symmetric across every PostInline container`() {
        // The MediaCounter KDoc warns that appendInline and walkInlinesForMedia must advance
        // the counter under the EXACT same conditions, including when descending into the six
        // PostInline containers (Strong, Emphasis, Underline, Strike, Color, Link). If any
        // recursion branch in walkInlinesForMedia (PostRenderer.kt, `is PostInline.<C> ->
        // walkInlinesForMedia(inline.children, ...)`) is deleted, the AnnotatedString would
        // still emit a post-smiley-N placeholder but the inline-content map would not register
        // it — leading to silent divergence at Compose runtime. This test exercises every
        // container type in turn so a deletion of any branch fails loudly with a JVM unit test.
        fun smileyChild(): List<PostInline> = listOf(
            PostInline.Smiley(
                kind = SmileyKind.Builtin(":o"),
                imageUrl = "https://forum.hardware.fr/images/perso/o.gif",
            ),
        )
        val containers: List<Pair<String, PostInline>> = listOf(
            "Strong" to PostInline.Strong(smileyChild()),
            "Emphasis" to PostInline.Emphasis(smileyChild()),
            "Underline" to PostInline.Underline(smileyChild()),
            "Strike" to PostInline.Strike(smileyChild()),
            "Color" to PostInline.Color(colorHex = "#FF0000", children = smileyChild()),
            "Link" to PostInline.Link(url = "https://example.com", children = smileyChild()),
        )

        containers.forEach { (name, container) ->
            val inlines = listOf(container)
            val annotated = buildInlineText(inlines, emptyLinkStyles, imageAlt = "img")
            val media = collectInlineMedia(inlines)

            assertEquals(
                "$name: collectInlineMedia must register the nested smiley as map key",
                setOf("post-smiley-0"),
                media.keys,
            )
            assertEquals(
                "$name: AnnotatedString placeholder IDs must match the inline-content map keys",
                media.keys,
                annotated.inlineContentIds(),
            )
        }
    }

    @Test
    fun `inline image emits a post-image placeholder and a matching map entry`() {
        val inlines = listOf(
            PostInline.InlineImage(
                url = "https://forum.hardware.fr/images/foo.png",
                description = "foo",
            ),
        )

        val annotated = buildInlineText(inlines, emptyLinkStyles, imageAlt = "img")
        val media = collectInlineMedia(inlines)

        assertEquals(setOf("post-image-0"), media.keys)
        assertEquals(setOf("post-image-0"), annotated.inlineContentIds())
    }

    @Test
    fun `smiley without imageUrl renders its token as text and produces no map entry`() {
        val inlines = listOf(
            PostInline.Smiley(kind = SmileyKind.Builtin(":jap:"), imageUrl = null),
            PostInline.Smiley(kind = SmileyKind.Perso("custom"), imageUrl = null),
        )

        val annotated = buildInlineText(inlines, emptyLinkStyles, imageAlt = "img")
        val media = collectInlineMedia(inlines)

        assertEquals(":jap:[:custom]", annotated.text)
        assertTrue("no inline content expected", media.isEmpty())
        assertFalse(annotated.inlineContentIds().any { it.startsWith("post-") })
    }

    @Test
    fun `builtin smiley with imageUrl uses the small bucket placeholder`() {
        // Builtin smileys are typically 16x16 to 18x18 in HFR's icon set. The small bucket fits
        // them inline with body-medium text without a noticeable height bump on the line.
        val inlines = listOf(
            PostInline.Smiley(
                kind = SmileyKind.Builtin(":jap:"),
                imageUrl = "https://forum-images.hardware.fr/icones/smilies/jap.gif",
            ),
        )

        val media = collectInlineMedia(inlines)
        val placeholder = media["post-smiley-0"]?.placeholder

        assertNotNull("builtin smiley with imageUrl should yield an InlineTextContent", placeholder)
        assertEquals(
            "builtin bucket width should match the policy",
            PostMediaDisplayPolicy.builtinSmiley.placeholderWidth,
            placeholder!!.width,
        )
        assertEquals(
            "builtin bucket height should match the policy",
            PostMediaDisplayPolicy.builtinSmiley.placeholderHeight,
            placeholder.height,
        )
    }

    @Test
    fun `perso smiley with imageUrl uses the larger 64sp bucket not the builtin one`() {
        // Real perso (e.g. [:cosmoschtroumpf]) ship at 80x60ish. Forcing them through the 18sp
        // builtin bucket squashes them down to a fingernail. The 64sp bucket honours their
        // intrinsic size with ContentScale.Fit while keeping inline rhythm reasonable.
        val inlines = listOf(
            PostInline.Smiley(
                kind = SmileyKind.Perso("cosmoschtroumpf"),
                imageUrl = "https://forum-images.hardware.fr/images/perso/cosmoschtroumpf.gif",
            ),
        )

        val media = collectInlineMedia(inlines)
        val placeholder = media["post-smiley-0"]?.placeholder

        assertNotNull("perso smiley with imageUrl should yield an InlineTextContent", placeholder)
        assertEquals(
            "perso bucket should match the policy (64sp), not the builtin 18sp bucket",
            PostMediaDisplayPolicy.persoSmiley.placeholderWidth,
            placeholder!!.width,
        )
        assertEquals(
            PostMediaDisplayPolicy.persoSmiley.placeholderHeight,
            placeholder.height,
        )
        // Defensive: explicitly assert it's NOT the builtin bucket — guards against a future
        // refactor that accidentally collapses both code paths to one bucket.
        assertNotEquals(
            "perso must not collapse to the builtin bucket",
            PostMediaDisplayPolicy.builtinSmiley.placeholderWidth,
            placeholder.width,
        )
    }

    @Test
    fun `inline image uses the bounded 240x180 placeholder rather than fillMaxWidth`() {
        // Pre-#109 the inline image placeholder was 240x180 but the inner Modifier was
        // fillMaxWidth() — meaningless inside InlineTextContent (the placeholder dictates the
        // parent constraint), and the image stretched in unpredictable ways. The new bucket
        // pins both placeholder + Modifier to the same dimensions.
        val inlines = listOf(
            PostInline.InlineImage(
                url = "https://forum.hardware.fr/images/foo.png",
                description = "foo",
            ),
        )

        val media = collectInlineMedia(inlines)
        val placeholder = media["post-image-0"]?.placeholder

        assertNotNull("inline image should yield an InlineTextContent", placeholder)
        assertEquals(
            PostMediaDisplayPolicy.inlineImage.placeholderWidth,
            placeholder!!.width,
        )
        assertEquals(
            PostMediaDisplayPolicy.inlineImage.placeholderHeight,
            placeholder.height,
        )
    }
}

/**
 * Pulls every appendInlineContent ID out of an AnnotatedString. Compose marks the position of
 * each placeholder with the special object-replacement char (U+FFFC) and stores the ID as a
 * string annotation under the well-known tag `androidx.compose.foundation.text.inlineContent`.
 */
private fun androidx.compose.ui.text.AnnotatedString.inlineContentIds(): Set<String> =
    getStringAnnotations(
        tag = "androidx.compose.foundation.text.inlineContent",
        start = 0,
        end = length,
    ).map { it.item }.toSet()
