package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #224 (option B) — pure coverage for the image-only-paragraph promotion decision: which paragraphs are
 * "just image(s)" ([imageOnlyParagraphImages]) and when they grow past the inline caps enough to deserve
 * the full-width centred block treatment ([shouldPromoteImagesToBlocks]). Keeping it pure means the
 * heuristic is pinned without driving Compose.
 */
class InlineImagePromotionTest {

    private fun img(url: String) = PostInline.InlineImage(url = url, description = null)

    @Test
    fun `a lone image paragraph is image-only`() {
        assertEquals(listOf("a"), imageOnlyParagraphImages(listOf(img("a")))?.map { it.url })
    }

    @Test
    fun `a gallery with blank text and breaks is image-only, preserving order`() {
        val result = imageOnlyParagraphImages(
            listOf(img("a"), PostInline.Text("   "), PostInline.LineBreak, img("b"), img("c")),
        )
        assertEquals(listOf("a", "b", "c"), result?.map { it.url })
    }

    @Test
    fun `a link-wrapped image is image-only (unwrapped)`() {
        val result = imageOnlyParagraphImages(
            listOf(PostInline.Link(url = "u", children = listOf(img("a")))),
        )
        assertEquals(listOf("a"), result?.map { it.url })
    }

    @Test
    fun `an image next to real text is not image-only`() {
        assertNull(imageOnlyParagraphImages(listOf(PostInline.Text("regarde "), img("a"))))
    }

    @Test
    fun `an image next to a smiley is not image-only`() {
        assertNull(
            imageOnlyParagraphImages(
                listOf(img("a"), PostInline.Smiley(kind = SmileyKind.Builtin(":o"), imageUrl = "s")),
            ),
        )
    }

    @Test
    fun `a cc-image emoji embedded in text (Link Strong text img) is not image-only`() {
        // The real cc-image shape: <a><strong>Doublé à Most <img …></strong></a>. The surrounding text
        // makes the paragraph prose, so it keeps inline rendering — the emoji is never promoted to a block.
        val emoji = PostInline.Link(
            url = "yt",
            children = listOf(
                PostInline.Strong(
                    children = listOf(PostInline.Text("Doublé à Most "), img("emoji?hfr-cc-image=true")),
                ),
            ),
        )
        assertNull(imageOnlyParagraphImages(listOf(emoji)))
    }

    @Test
    fun `promotion needs a measured image larger than the inline caps`() {
        val images = listOf(img("a"), img("b"))
        // cold (unknown size) → stay inline until measured
        assertFalse(shouldPromoteImagesToBlocks(images, emptyMap()))
        // emoji + small reaction → stay inline
        assertFalse(
            shouldPromoteImagesToBlocks(
                images,
                mapOf("a" to IntSize(16, 16), "b" to IntSize(80, 60)),
            ),
        )
        // one real photo wider than the inline cap → promote the whole paragraph
        assertTrue(
            shouldPromoteImagesToBlocks(
                images,
                mapOf("a" to IntSize(16, 16), "b" to IntSize(1200, 800)),
            ),
        )
    }

    @Test
    fun `promotion also triggers on the height cap`() {
        assertTrue(shouldPromoteImagesToBlocks(listOf(img("tall")), mapOf("tall" to IntSize(100, 600))))
    }
}
