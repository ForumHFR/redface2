package fr.forumhfr.redface2.core.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

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
    fun `redactUriSource hides local names and identifying authority syntax`() {
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
    }

    @Test
    fun `redactUriSource accepts Android resources without exposing their authority`() {
        assertEquals(
            "android.resource://<local>",
            DiagnosticRedactor.redactUriSource("android.resource://fr.forumhfr.redface2/123"),
        )
    }
}

@RunWith(Parameterized::class)
class DiagnosticRedactorAstraTableTest(
    private val uri: String,
    private val expected: String,
) {

    @Test
    fun `redactUriSource matches the Astra review table`() {
        assertEquals(expected, DiagnosticRedactor.redactUriSource(uri))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun astraCases(): List<Array<String>> = listOf(
            arrayOf("file://photo-alice.jpg", "file://<local>"),
            arrayOf("content://alice-photos/x", "content://<redacted-authority>"),
            arrayOf("content://media/external/images/media/12345", "content://media"),
            arrayOf(
                "content://com.google.android.apps.photos.contentprovider/0/1/" +
                    "content%3A%2F%2Fmedia%2F...",
                "content://com.google.android.apps.photos.contentprovider",
            ),
            arrayOf(
                "content://com.android.providers.media.documents/document/image%3A12345",
                "content://com.android.providers.media.documents",
            ),
            arrayOf("content://0@media/x", "content://<redacted-authority>"),
            arrayOf("content://media.example.com/x", "content://<redacted-authority>"),
            arrayOf("not-a-uri/private/photo.jpg", "<redacted>"),
            arrayOf("content:///x", "content://<redacted-authority>"),
        )
    }
}

@RunWith(Parameterized::class)
class DiagnosticRedactorAllowlistBypassTest(
    private val uri: String,
    private val expected: String,
) {

    @Test
    fun `redactUriSource rejects allowlist bypasses`() {
        assertEquals(expected, DiagnosticRedactor.redactUriSource(uri))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun bypassCases(): List<Array<String>> = listOf(
            arrayOf("content://photo-alice.jpg/x", "content://<redacted-authority>"),
            arrayOf("content://com.alice1234.photos/x", "content://<redacted-authority>"),
            arrayOf("photo-alice.jpg://media/x", "<redacted>"),
            arrayOf("content://media.example.com/x", "content://<redacted-authority>"),
        )
    }
}

@RunWith(Parameterized::class)
class DiagnosticRedactorProviderAllowlistTest(
    private val authority: String,
) {

    @Test
    fun `redactUriSource keeps an allowlisted provider authority`() {
        assertEquals(
            "content://$authority",
            DiagnosticRedactor.redactUriSource("content://$authority/private/path?token=secret#fragment"),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun providerAuthorities(): List<Array<String>> = listOf(
            arrayOf("media"),
            arrayOf("downloads"),
            arrayOf("com.android.providers.media.documents"),
            arrayOf("com.android.providers.downloads.documents"),
            arrayOf("com.android.externalstorage.documents"),
            arrayOf("com.android.providers.media"),
            arrayOf("com.google.android.apps.photos.contentprovider"),
            arrayOf("com.google.android.apps.photos.content"),
            arrayOf("com.google.android.apps.docs.storage"),
            arrayOf("com.google.android.apps.docs.storage.legacy"),
            arrayOf("com.android.providers.media.photopicker"),
        )
    }
}
