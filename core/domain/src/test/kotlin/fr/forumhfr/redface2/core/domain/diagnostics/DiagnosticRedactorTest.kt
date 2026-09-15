package fr.forumhfr.redface2.core.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {

    @Test
    fun `redact replaces HTTP and HTTPS URLs`() {
        val redacted = DiagnosticRedactor.redact("http://example.test/a https://example.test/b")

        assertEquals("<url> <url>", redacted)
    }

    @Test
    fun `redact replaces an encoded photo picker content URI`() {
        val uri = "content://com.google.android.apps.photos.contentprovider/0/1/" +
            "content%3A%2F%2Fmedia%2Fexternal%2Fimages%2Fmedia%2F12345/ORIGINAL/NONE/image%2Fjpeg/123"

        assertEquals("<url>", DiagnosticRedactor.redact(uri))
    }

    @Test
    fun `redact replaces an absolute storage path`() {
        val redacted = DiagnosticRedactor.redact("open failed: /storage/emulated/0/DCIM/IMG_2865.jpg")

        assertEquals("open failed: <path>", redacted)
    }

    @Test
    fun `redact replaces an identifying numeric JSON field`() {
        val redacted = DiagnosticRedactor.redact("""{"picID":543526}""")

        assertEquals("{<redacted>}", redacted)
        assertFalse(redacted.contains("picID"))
        assertFalse(redacted.contains("543526"))
    }

    @Test
    fun `redact replaces an identifying URL JSON field`() {
        val redacted = DiagnosticRedactor.redact("""{"picURL":"https://host/image/543526"}""")

        assertEquals("{<redacted>}", redacted)
        assertFalse(redacted.contains("picURL"))
        assertFalse(redacted.contains("://"))
    }

    @Test
    fun `redact leaves safe text unchanged`() {
        assertEquals("HTTP 422: upload refused", DiagnosticRedactor.redact("HTTP 422: upload refused"))
    }

    @Test
    fun `redact fails closed when an unrecognised scheme remains`() {
        val redacted = DiagnosticRedactor.redact("unexpected HTTPS://host/private/543526")

        assertEquals("<redacted>", redacted)
    }

    @Test
    fun `redact applies the requested maximum length after replacements`() {
        val redacted = DiagnosticRedactor.redact("https://host/private ${"x".repeat(20)}", maxLength = 10)

        assertEquals("<url> xxxx", redacted)
        assertTrue(redacted.length <= 10)
    }

    @Test
    fun `redactUriSource keeps only a safe scheme and authority`() {
        val redacted = DiagnosticRedactor.redactUriSource(
            "content://com.android.providers.media.documents/document/image%3A12345?token=secret",
        )

        assertEquals("content://com.android.providers.media.documents", redacted)
        assertFalse(redacted.contains("document/"))
        assertFalse(redacted.contains("12345"))
        assertFalse(redacted.contains("secret"))
    }

    @Test
    fun `redactUriSource keeps only recognized provider-shaped authorities`() {
        assertEquals("content://media", DiagnosticRedactor.redactUriSource("content://media/external/images/1"))
        assertEquals(
            "content://com.google.android.apps.photos.contentprovider",
            DiagnosticRedactor.redactUriSource(
                "content://com.google.android.apps.photos.contentprovider/secret-photo.jpg",
            ),
        )
        assertEquals(
            "content://<redacted-authority>",
            DiagnosticRedactor.redactUriSource("content://alice-photos/private/photo.jpg"),
        )
    }

    @Test
    fun `redactUriSource hides local names and identifying authority syntax`() {
        assertEquals("file://<local>", DiagnosticRedactor.redactUriSource("file://photo-alice.jpg"))
        assertEquals(
            "content://<redacted-authority>",
            DiagnosticRedactor.redactUriSource("content://com.example.provider123456/private"),
        )
        assertEquals(
            "content://<redacted-authority>",
            DiagnosticRedactor.redactUriSource("content://user@example.test/private"),
        )
        assertEquals(
            "content://<redacted-authority>",
            DiagnosticRedactor.redactUriSource("content://alice%2Dphotos/private"),
        )
        assertEquals(
            "content://<redacted-authority>",
            DiagnosticRedactor.redactUriSource("content://com.${"a".repeat(121)}/private"),
        )
        assertEquals("<redacted>", DiagnosticRedactor.redactUriSource("not-a-uri/private/photo.jpg"))
    }
}
