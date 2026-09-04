package fr.forumhfr.redface2.core.ui.post

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostImageViewerPolicyTest {

    @Test
    fun `unlinked block uses its rendered URL for source preview and external target`() {
        val request = viewerRequestFor(
            target = PostImageTarget(
                url = "https://cdn.example.org/rendered/photo.png",
                description = "vacances",
                linkUrl = null,
            ),
            diskCache = true,
        )

        assertEquals("https://cdn.example.org/rendered/photo.png", request?.sourceUrl)
        assertEquals("https://cdn.example.org/rendered/photo.png", request?.previewUrl)
        assertEquals("https://cdn.example.org/rendered/photo.png", request?.externalUrl)
        assertEquals("vacances", request?.description)
        assertEquals(true, request?.diskCache)
    }

    @Test
    fun `linked image-like block loads the link and keeps rendered image as preview`() {
        val request = viewerRequestFor(
            target = PostImageTarget(
                url = "https://cdn.example.org/thumb/42.jpg",
                description = null,
                linkUrl = "https://cdn.example.org/original/42.AVIF?download=1#full",
            ),
            diskCache = false,
        )

        assertEquals("https://cdn.example.org/original/42.AVIF?download=1#full", request?.sourceUrl)
        assertEquals("https://cdn.example.org/thumb/42.jpg", request?.previewUrl)
        assertEquals("https://cdn.example.org/original/42.AVIF?download=1#full", request?.externalUrl)
        assertEquals(false, request?.diskCache)
    }

    @Test
    fun `linked non-image block stays outside the viewer`() {
        assertNull(
            viewerRequestFor(
                target = PostImageTarget(
                    url = "https://cdn.example.org/thumb/42.jpg",
                    description = null,
                    linkUrl = "https://example.org/gallery/42",
                ),
                diskCache = true,
            ),
        )
    }

    @Test
    fun `ineligible rendered payload stays outside the viewer`() {
        assertNull(
            viewerRequestFor(
                target = PostImageTarget(
                    url = "data:image/png;base64,AAAA",
                    description = null,
                    linkUrl = null,
                ),
                diskCache = true,
            ),
        )
    }

    @Test
    fun `supported extensions ignore case query and fragment`() {
        listOf("jpg", "jpeg", "png", "gif", "webp", "avif").forEach { extension ->
            assertTrue(
                extension,
                isImageLikeUrl("https://images.example.org/photo.${extension.uppercase()}?size=full#view"),
            )
        }
    }

    @Test
    fun `versioned hosts are image-like without an extension`() {
        listOf("reho.st", "rehost.diberie.com", "i.imgur.com").forEach { host ->
            assertTrue(host, isImageLikeUrl("https://${host.uppercase()}/Picture/Get/f/12345"))
        }
    }

    @Test
    fun `unknown host without supported extension is doubtful`() {
        assertFalse(isImageLikeUrl("https://images.example.org/full/42?format=png"))
        assertFalse(isImageLikeUrl("https://imgur.com/gallery/42"))
        assertFalse(isImageLikeUrl("not a URL.jpg"))
    }
}
