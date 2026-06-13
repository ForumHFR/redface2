package fr.forumhfr.redface2.core.data.upload

import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import javax.inject.Provider
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
 * MockWebServer-driven tests for [ImgurProvider] (#459). The envelope shape (`{ data: { link,
 * deletehash } }`) is the imgur v3 image-upload contract documented at api.imgur.com; the fixtures
 * below are minimal hand-built snapshots — no live capture is possible in CI.
 */
class ImgurProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: ImgurProvider

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        provider = ImgurProvider(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true; explicitNulls = false },
            clientId = Provider { TEST_CLIENT_ID },
            ioDispatcher = UnconfinedTestDispatcher(),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `upload parses link and deletehash from the data envelope`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"data":{"link":"https://i.imgur.com/abc.png","deletehash":"DELHASH"},
                       "success":true,"status":200}""",
                ),
        )

        val result = provider.upload(sampleImage())

        assertEquals(UploadProviderId.IMGUR, result.provider)
        assertEquals("https://i.imgur.com/abc.png", result.imageUrl)
        assertEquals("DELHASH", result.deleteHandle)
        assertEquals(null, result.thumbnailUrl)
        assertEquals(null, result.expiresAt)
    }

    @Test
    fun `upload sends the Client-ID authorization header and a multipart image part`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"data":{"link":"https://i/x.png"}}"""),
        )

        provider.upload(sampleImage())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.endsWith("/3/image"))
        assertEquals("Client-ID $TEST_CLIENT_ID", recorded.getHeader("Authorization"))
        assertTrue(
            "must be a multipart/form-data request",
            recorded.getHeader("Content-Type")!!.startsWith("multipart/form-data"),
        )
        assertTrue("part must be named image", recorded.body.readUtf8().contains("name=\"image\""))
    }

    @Test
    fun `upload maps a 5xx response to UploadException Server`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val error = runCatching { provider.upload(sampleImage()) }.exceptionOrNull()

        assertTrue(error is UploadException.Server)
        assertEquals(500, (error as UploadException.Server).code)
        assertEquals(UploadProviderId.IMGUR, error.providerId)
    }

    @Test
    fun `upload maps broken JSON to UploadException Malformed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not json</html>"))

        val error = runCatching { provider.upload(sampleImage()) }.exceptionOrNull()

        assertTrue(error is UploadException.Malformed)
    }

    @Test
    fun `upload maps a missing data envelope to UploadException Malformed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":false,"status":400}"""))

        val error = runCatching { provider.upload(sampleImage()) }.exceptionOrNull()

        assertTrue(error is UploadException.Malformed)
    }

    @Test
    fun `upload maps a data envelope without a link to UploadException Malformed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"deletehash":"D"}}"""))

        val error = runCatching { provider.upload(sampleImage()) }.exceptionOrNull()

        assertTrue(error is UploadException.Malformed)
    }

    @Test
    fun `delete issues a DELETE on the deletehash with the Client-ID header and confirms on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":true,"success":true}"""))

        assertTrue(provider.delete("DELHASH"))

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertTrue(recorded.path!!.endsWith("/3/image/DELHASH"))
        assertEquals("Client-ID $TEST_CLIENT_ID", recorded.getHeader("Authorization"))
    }

    @Test
    fun `delete returns false when the host rejects, never throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(false, provider.delete("DELHASH"))
    }

    private fun sampleImage(): ImageUpload = ImageUpload(
        bytes = byteArrayOf(1, 2, 3, 4),
        mimeType = "image/png",
        displayName = "photo.png",
    )

    private companion object {
        const val TEST_CLIENT_ID = "TESTCLIENTID"
    }
}
