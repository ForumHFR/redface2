package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #958 (Lot 2, §5/G3) — the LinkAnnotation SPLIT: inside a `[url=…]` subtree, the placeholder of a
 * CONTENT image is emitted OUTSIDE any [LinkAnnotation] range (its interaction belongs to the image
 * node — tap opens the link, long-press opens the menu), while text, smileys and cc-images (#256)
 * REMAIN under the link. Structural invariants pinned here, on [buildInlineText] output:
 *  - no link range ever overlaps a content-image placeholder;
 *  - the surrounding text keeps link ranges carrying the SAME URL;
 *  - no EMPTY link range is emitted (adjacent images, image at the edges);
 *  - a link WITHOUT content image keeps today's single range (structural fast-path);
 *  - the MediaCounter ID symmetry with [collectInlineMedia] survives the split.
 */
class PostRendererLinkSplitTest {

    private val emptyLinkStyles = TextLinkStyles()
    private val contentImageUrl = "https://rehost.diberie.com/Picture/Get/f/photo.png"
    private val ccImageUrl = "https://example.org/emojis-micro/1f600.png?hfr-cc-image=true"
    private val linkUrl = "https://example.org/full"

    private fun contentImage(description: String = "photo") =
        PostInline.InlineImage(url = contentImageUrl, description = description)

    private fun ccImage(description: String = "emoji") =
        PostInline.InlineImage(url = ccImageUrl, description = description)

    private fun imageSmiley() = PostInline.Smiley(
        kind = SmileyKind.Builtin(":o"),
        imageUrl = "https://forum.hardware.fr/images/perso/o.gif",
    )

    private fun link(vararg children: PostInline) =
        PostInline.Link(url = linkUrl, children = children.toList())

    private fun build(vararg inlines: PostInline): AnnotatedString =
        buildInlineText(inlines.toList(), emptyLinkStyles, imageAlt = "img")

    @Test
    fun `a content image placeholder inside a link is emitted outside every LinkAnnotation range`() {
        val annotated = build(link(PostInline.Text("avant "), contentImage(), PostInline.Text(" après")))

        val placeholder = annotated.singlePlaceholderRange()
        val overlapping = annotated.linkRanges().filter { it.overlaps(placeholder) }

        assertEquals("no link range may cover the content-image placeholder", emptyList<Any>(), overlapping)
    }

    @Test
    fun `the text around a split content image keeps link ranges carrying the link URL`() {
        val annotated = build(link(PostInline.Text("avant "), contentImage(), PostInline.Text(" après")))

        val ranges = annotated.linkRanges()
        assertEquals("one range per text run around the image", 2, ranges.size)
        ranges.forEach { range ->
            assertEquals(linkUrl, (range.item as LinkAnnotation.Url).url)
            assertTrue("no empty link range", range.start < range.end)
        }
        // The two runs cover exactly the text on each side of the placeholder.
        val placeholder = annotated.singlePlaceholderRange()
        assertEquals("avant ", annotated.text.substring(ranges[0].start, ranges[0].end))
        assertEquals(" après", annotated.text.substring(ranges[1].start, ranges[1].end))
        assertTrue(ranges[0].end <= placeholder.start && placeholder.end <= ranges[1].start)
    }

    @Test
    fun `a cc-image placeholder stays under the LinkAnnotation (fast-path #256)`() {
        val annotated = build(link(PostInline.Text("regarde "), ccImage(), PostInline.Text(" !")))

        val ranges = annotated.linkRanges()
        assertEquals("the whole link content keeps ONE single range", 1, ranges.size)
        assertEquals(0, ranges[0].start)
        assertEquals(annotated.length, ranges[0].end)
    }

    @Test
    fun `an image smiley placeholder stays under the LinkAnnotation`() {
        val annotated = build(link(PostInline.Text("gg "), imageSmiley()))

        val ranges = annotated.linkRanges()
        assertEquals(1, ranges.size)
        assertEquals(0, ranges[0].start)
        assertEquals(annotated.length, ranges[0].end)
    }

    @Test
    fun `a link without content image keeps a single range even with nested styles`() {
        val annotated = build(
            link(
                PostInline.Strong(children = listOf(PostInline.Text("gras"))),
                PostInline.Text(" et "),
                ccImage(),
                imageSmiley(),
            ),
        )

        val ranges = annotated.linkRanges()
        assertEquals("structural fast-path: one range over the whole link", 1, ranges.size)
        assertEquals(0, ranges[0].start)
        assertEquals(annotated.length, ranges[0].end)
    }

    @Test
    fun `a content image nested in a styled container splits the link and keeps the style on the text`() {
        val annotated = build(
            link(
                PostInline.Strong(
                    children = listOf(
                        PostInline.Text("b1 "),
                        contentImage(),
                        PostInline.Text(" b2"),
                    ),
                ),
            ),
        )

        val placeholder = annotated.singlePlaceholderRange()
        assertTrue(
            "placeholder outside every link range",
            annotated.linkRanges().none { it.overlaps(placeholder) },
        )
        assertEquals("one link range per text run", 2, annotated.linkRanges().size)
        // The bold styling survives the split on both text runs.
        val b1 = annotated.text.indexOf("b1")
        val b2 = annotated.text.indexOf("b2")
        listOf(b1, b2).forEach { index ->
            val bold = annotated.spanStyles.any { span ->
                span.start <= index && index < span.end && span.item.fontWeight == FontWeight.Bold
            }
            assertTrue("bold span must cover the text at index $index", bold)
        }
    }

    @Test
    fun `adjacent content images inside a link emit no empty link range`() {
        val imagesOnly = build(link(contentImage("a"), contentImage("b")))
        assertEquals(
            "a link reduced to its content images emits NO link range at all",
            emptyList<Any>(),
            imagesOnly.linkRanges(),
        )

        val withText = build(link(PostInline.Text("t"), contentImage("a"), contentImage("b")))
        val ranges = withText.linkRanges()
        assertEquals("one single range over the leading text", 1, ranges.size)
        assertEquals("t", withText.text.substring(ranges[0].start, ranges[0].end))
    }

    @Test
    fun `a content image at the link edges emits no empty link range`() {
        val leading = build(link(contentImage(), PostInline.Text("fin")))
        assertEquals(1, leading.linkRanges().size)
        assertEquals("fin", leading.text.substring(leading.linkRanges()[0].start, leading.linkRanges()[0].end))

        val trailing = build(link(PostInline.Text("début"), contentImage()))
        assertEquals(1, trailing.linkRanges().size)
        assertEquals(
            "début",
            trailing.text.substring(trailing.linkRanges()[0].start, trailing.linkRanges()[0].end),
        )
    }

    @Test
    fun `MediaCounter symmetry survives the link split`() {
        // Smiley, content image and cc-image interleaved inside the link, plus a trailing content
        // image outside: both walks must advance the counter under the exact same conditions
        // (MediaCounter KDoc), placeholder IDs == inline-content map keys.
        val inlines = listOf(
            link(imageSmiley(), contentImage("dans-lien"), ccImage(), PostInline.Text(" fin")),
            contentImage("hors-lien"),
        )

        val annotated = buildInlineText(inlines, emptyLinkStyles, imageAlt = "img")
        val media = collectInlineMedia(inlines)

        assertEquals(
            setOf("post-smiley-0", "post-image-0", "post-image-1", "post-image-2"),
            media.keys,
        )
        assertEquals(media.keys, annotated.inlineContentIds())
    }
}

private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

private fun AnnotatedString.linkRanges(): List<AnnotatedString.Range<LinkAnnotation>> =
    getLinkAnnotations(0, length)

private fun AnnotatedString.placeholderRanges(): List<AnnotatedString.Range<String>> =
    getStringAnnotations(INLINE_CONTENT_TAG, 0, length)

private fun AnnotatedString.singlePlaceholderRange(): AnnotatedString.Range<String> {
    val ranges = placeholderRanges()
    check(ranges.size == 1) { "expected exactly one placeholder, got ${ranges.size}" }
    return ranges.single()
}

private fun AnnotatedString.Range<LinkAnnotation>.overlaps(other: AnnotatedString.Range<String>): Boolean =
    start < other.end && other.start < end

private fun AnnotatedString.inlineContentIds(): Set<String> =
    placeholderRanges().map { it.item }.toSet()
