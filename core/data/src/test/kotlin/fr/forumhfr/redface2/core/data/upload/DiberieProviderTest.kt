package fr.forumhfr.redface2.core.data.upload

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MockWebServer-driven tests for [DiberieProvider] (#459).
 *
 * The headline fixture [REAL_RESPONSE] is **captured live** (2026-06-13) — see
 * `core/data/src/test/resources/fixtures/diberie_upload_response.json`. It pins the one detail the
 * hand-built fixtures used to get wrong: `picID` is a JSON **number**, not a quoted string. Parsed
 * with the very same `@UploadJson` profile production uses (`ignoreUnknownKeys`, no `isLenient`), so
 * a type drift here fails the test exactly as it failed every real upload before the fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiberieProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var diagnostics: DiagnosticsLog
    private lateinit var provider: DiberieProvider

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        diagnostics = DiagnosticsLog()
        provider = DiberieProvider(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true; explicitNulls = false },
            ioDispatcher = UnconfinedTestDispatcher(),
            diagnostics = diagnostics,
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `upload parses the live-captured response (picID as a JSON number)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(readFixture(REAL_RESPONSE)))

        val result = provider.upload(sampleImage())

        assertEquals(UploadProviderId.DIBERIE, result.provider)
        assertEquals("https://rehost.diberie.com/Picture/Get/f/521196", result.imageUrl)
        assertEquals("https://rehost.diberie.com/Picture/Get/t/521196", result.thumbnailUrl)
        // picID is the integer 521196 on the wire; the delete handle is its string form.
        assertEquals("521196", result.deleteHandle)
        assertEquals(null, result.expiresAt)
        assertTrue(
            "a successful upload must leave an INFO trail in the diagnostics viewer",
            diagnostics.entries.value.any { it.level == DiagnosticsLog.Level.INFO && it.message.contains("521196") },
        )
    }

    @Test
    fun `upload parses picID, picURL and thumbURL and uses picID as the delete handle`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"picID":521196,"picURL":"https://host/Picture/Get/f/521196",
                       "thumbURL":"https://host/Picture/Get/t/521196"}""",
                ),
        )

        val result = provider.upload(sampleImage())

        assertEquals(UploadProviderId.DIBERIE, result.provider)
        assertEquals("https://host/Picture/Get/f/521196", result.imageUrl)
        assertEquals("https://host/Picture/Get/t/521196", result.thumbnailUrl)
        assertEquals("521196", result.deleteHandle)
        assertEquals(null, result.expiresAt)
    }

    @Test
    fun `upload falls back to derived URLs when only picID is returned`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"picID":777}"""))

        val result = provider.upload(sampleImage())

        assertTrue("imageUrl must be derived from picID", result.imageUrl.endsWith("/Picture/Get/f/777"))
        assertTrue("thumbnailUrl must be derived from picID", result.thumbnailUrl!!.endsWith("/Picture/Get/t/777"))
    }

    @Test
    fun `upload sends a multipart FORM request with an image part`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"picID":123}"""))

        provider.upload(sampleImage())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(
            "must be a multipart/form-data request",
            recorded.getHeader("Content-Type")!!.startsWith("multipart/form-data"),
        )
        val body = recorded.body.readUtf8()
        assertTrue("part must be named image", body.contains("name=\"image\""))
        assertTrue("upload query flags must be present", recorded.path!!.contains("SelectedExpiryType=0"))
    }

    @Test
    fun `upload maps a 5xx response to UploadException Server`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))

        val error = runCatching { provider.upload(sampleImage()) }.exceptionOrNull()

        assertTrue(error is UploadException.Server)
        assertEquals(503, (error as UploadException.Server).code)
        assertEquals(UploadProviderId.DIBERIE, error.providerId)
        assertTrue(
            "a rejected upload must leave a WARN trail",
            diagnostics.entries.value.any { it.level == DiagnosticsLog.Level.WARN && it.message.contains("503") },
        )
    }

    @Test
    fun `upload maps broken JSON to UploadException Malformed and records the body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        val error = runCatching { provider.upload(sampleImage()) }.exceptionOrNull()

        assertTrue(error is UploadException.Malformed)
        assertTrue(
            "an unparseable response must record the raw body for diagnosis",
            diagnostics.entries.value.any { it.message.contains("unparseable") && it.message.contains("not json") },
        )
    }

    @Test
    fun `upload maps a 200 without picID to UploadException Malformed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"picURL":"https://host/x"}"""))

        val error = runCatching { provider.upload(sampleImage()) }.exceptionOrNull()

        assertTrue(error is UploadException.Malformed)
        assertTrue(
            "a missing picID must record the raw body for diagnosis",
            diagnostics.entries.value.any { it.message.contains("without picID") },
        )
    }

    @Test
    fun `upload rejects an oversized image with UploadException TooLarge without hitting the network`() = runTest {
        val tooBig = ImageUpload(
            bytes = ByteArray(21 * 1024 * 1024),
            mimeType = "image/jpeg",
            displayName = "big.jpg",
        )

        val error = runCatching { provider.upload(tooBig) }.exceptionOrNull()

        assertTrue(error is UploadException.TooLarge)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `delete is best-effort - true when the host confirms`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        assertTrue(provider.delete("521196"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.endsWith("/Host/DeletePhoto"))
    }

    @Test
    fun `delete returns false when the host rejects, never throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(false, provider.delete("521196"))
    }

    private fun sampleImage(): ImageUpload = ImageUpload(
        bytes = byteArrayOf(1, 2, 3, 4),
        mimeType = "image/jpeg",
        displayName = "photo.jpg",
    )

    private fun readFixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing test fixture: fixtures/$name"
        }.bufferedReader().use { it.readText() }

    private companion object {
        const val REAL_RESPONSE = "diberie_upload_response.json"
    }
}
