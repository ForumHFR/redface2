package fr.forumhfr.redface2.core.data.upload

import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
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
 * MockWebServer-driven tests for [DiberieProvider] (#459). The diberie response contract is NOT
 * captured from a live host here (no live access in CI) — the JSON fixtures below are minimal,
 * hand-built snapshots documenting the field set the provider reads (`picID` / `picURL` / `thumbURL`);
 * the user confirms the real shape end-to-end with `dib91`.
 */
class DiberieProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: DiberieProvider

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        provider = DiberieProvider(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true; explicitNulls = false },
            ioDispatcher = UnconfinedTestDispatcher(),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `upload parses picID, picURL and thumbURL and uses picID as the delete handle`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"picID":"ABC123","picURL":"https://host/Picture/Get/f/ABC123",
                       "thumbURL":"https://host/Picture/Get/t/ABC123"}""",
                ),
        )

        val result = provider.upload(sampleImage())

        assertEquals(UploadProviderId.DIBERIE, result.provider)
        assertEquals("https://host/Picture/Get/f/ABC123", result.imageUrl)
        assertEquals("https://host/Picture/Get/t/ABC123", result.thumbnailUrl)
        assertEquals("ABC123", result.deleteHandle)
        assertEquals(null, result.expiresAt)
    }

    @Test
    fun `upload falls back to derived URLs when only picID is returned`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"picID":"XYZ"}"""))

        val result = provider.upload(sampleImage())

        assertTrue("imageUrl must be derived from picID", result.imageUrl.endsWith("/Picture/Get/f/XYZ"))
        assertTrue("thumbnailUrl must be derived from picID", result.thumbnailUrl!!.endsWith("/Picture/Get/t/XYZ"))
    }

    @Test
    fun `upload sends a multipart FORM request with an image part`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"picID":"ABC123"}"""))

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
    }

    @Test
    fun `upload maps broken JSON to UploadException Malformed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        val error = runCatching { provider.upload(sampleImage()) }.exceptionOrNull()

        assertTrue(error is UploadException.Malformed)
    }

    @Test
    fun `upload maps a 200 without picID to UploadException Malformed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"picURL":"https://host/x"}"""))

        val error = runCatching { provider.upload(sampleImage()) }.exceptionOrNull()

        assertTrue(error is UploadException.Malformed)
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

        assertTrue(provider.delete("ABC123"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.endsWith("/Host/DeletePhoto"))
    }

    @Test
    fun `delete returns false when the host rejects, never throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(false, provider.delete("ABC123"))
    }

    private fun sampleImage(): ImageUpload = ImageUpload(
        bytes = byteArrayOf(1, 2, 3, 4),
        mimeType = "image/jpeg",
        displayName = "photo.jpg",
    )
}
