package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.text.TextLinkStyles
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
