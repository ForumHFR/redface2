package fr.forumhfr.redface2.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #831 — pins the pure identity decisions of the image saver: magic-byte sniffing beats the URL
 * extension, the fallback is stable, and the display name's extension always agrees with the
 * recorded MIME (« extension cohérente avec le MIME sniffé », Codex framing arbitrage #4).
 */
class PostImageMediaTypeTest {

    // ---- magic-byte sniffing -------------------------------------------------------------

    private fun bytes(vararg header: Int): ByteArray =
        ByteArray(16) { index -> if (index < header.size) header[index].toByte() else 0 }

    @Test
    fun `sniffs the common raster signatures`() {
        assertEquals("image/jpeg", sniffImageMediaType(bytes(0xFF, 0xD8, 0xFF, 0xE0))?.mimeType)
        assertEquals("image/png", sniffImageMediaType(bytes(0x89, 'P'.code, 'N'.code, 'G'.code))?.mimeType)
        assertEquals(
            "image/gif",
            sniffImageMediaType(bytes('G'.code, 'I'.code, 'F'.code, '8'.code, '9'.code, 'a'.code))?.mimeType,
        )
        assertEquals("image/bmp", sniffImageMediaType(bytes('B'.code, 'M'.code))?.mimeType)
    }

    @Test
    fun `sniffs WebP through its RIFF container`() {
        val webp = ByteArray(16)
        "RIFF".forEachIndexed { i, c -> webp[i] = c.code.toByte() }
        "WEBP".forEachIndexed { i, c -> webp[8 + i] = c.code.toByte() }
        assertEquals("image/webp", sniffImageMediaType(webp)?.mimeType)
    }

    @Test
    fun `sniffs AVIF through its ftyp brand at offset 4`() {
        val avif = ByteArray(16)
        "ftypavif".forEachIndexed { i, c -> avif[4 + i] = c.code.toByte() }
        assertEquals("image/avif", sniffImageMediaType(avif)?.mimeType)
    }

    @Test
    fun `unknown signatures and short payloads sniff to null`() {
        assertNull(sniffImageMediaType(bytes('<'.code, 'h'.code, 't'.code, 'm'.code, 'l'.code)))
        assertNull(sniffImageMediaType(ByteArray(4))) // shorter than any reliable signature window
    }

    // ---- resolution priority -------------------------------------------------------------

    @Test
    fun `sniffed bytes beat a lying URL extension`() {
        // A PNG served behind a `.jpg` URL is saved as a PNG (the bytes are the truth).
        val resolved = resolveImageMediaType(bytes(0x89, 'P'.code, 'N'.code, 'G'.code), "https://x.com/photo.jpg")
        assertEquals("image/png", resolved.mimeType)
        assertEquals("png", resolved.extension)
    }

    @Test
    fun `URL extension is the fallback when sniffing fails`() {
        val resolved = resolveImageMediaType(ByteArray(16), "https://x.com/anim.GIF?v=2#frag")
        assertEquals("image/gif", resolved.mimeType)
    }

    @Test
    fun `stable JPEG fallback when neither bytes nor URL identify the type`() {
        val resolved = resolveImageMediaType(ByteArray(16), "https://x.com/picture")
        assertEquals("image/jpeg", resolved.mimeType)
        assertEquals("jpg", resolved.extension)
    }

    // ---- display name --------------------------------------------------------------------

    private val png = resolveImageMediaType(bytes(0x89, 'P'.code, 'N'.code, 'G'.code), "")

    @Test
    fun `display name derives from the URL last segment with the resolved extension`() {
        assertEquals(
            "screenshot-2026.png",
            imageDisplayName("https://x.com/a/b/screenshot-2026.jpg?token=abc", png),
        )
    }

    @Test
    fun `special characters are sanitized and the base is capped`() {
        // '%', ' ', '(', ')', '!' all collapse to '_' ; the trailing runs of '_' are trimmed.
        assertEquals(
            "mon_20image__1.png",
            imageDisplayName("https://x.com/mon%20image (1)!.png", png),
        )

        val longBase = "a".repeat(200)
        val capped = imageDisplayName("https://x.com/$longBase.png", png)
        assertEquals("a".repeat(64) + ".png", capped)
    }

    @Test
    fun `unusable bases fall back to the stable default`() {
        assertEquals("image.png", imageDisplayName("https://x.com/", png))
        assertEquals("image.png", imageDisplayName("https://x.com/....", png))
    }
}
