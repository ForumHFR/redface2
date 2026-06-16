package fr.forumhfr.redface2.core.data.upload

import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // The Client-ID preference, read by the provider on each call via observeImgurClientId().first().
    private val clientIdFlow = MutableStateFlow(TEST_CLIENT_ID)
    private val prefs = mockk<UserPreferencesRepository> {
        every { observeImgurClientId() } returns clientIdFlow
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        provider = ImgurProvider(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true; explicitNulls = false },
            userPreferencesRepository = prefs,
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
    fun `upload maps a success-false envelope to Server with the HTTP code and the data error`() = runTest {
        // #474 — imgur signals an application-level refusal with `success:false` even on a 2xx
        // transport (HTTP 200 here). It must map to Server (carrying the host's data.error) rather
        // than stumbling into a generic Malformed on the now-absent `link`. The reported code must
        // be the envelope's application-level `status` (400), NOT the 200 transport code (Codex #474).
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"error":"File is over the size limit"},"success":false,"status":400}"""),
        )

        val error = runCatching { provider.upload(sampleImage()) }.exceptionOrNull()

        assertTrue("success:false must be Server, not Malformed", error is UploadException.Server)
        error as UploadException.Server
        assertEquals("the envelope status must win over the 200 transport code", 400, error.code)
        assertEquals(UploadProviderId.IMGUR, error.providerId)
        assertEquals("File is over the size limit", error.errorMessage)
    }

    @Test
    fun `upload maps a success-false envelope without a data error to Server with a null message`() = runTest {
        // No `data.error` provided — still Server (not Malformed), with a null message; the HTTP code
        // carries the meaning. Mirrors the old fixture that used to expect Malformed here (#474).
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":false,"status":400}"""))

        val error = runCatching { provider.upload(sampleImage()) }.exceptionOrNull()

        assertTrue(error is UploadException.Server)
        error as UploadException.Server
        assertEquals("the envelope status must win over the 200 transport code", 400, error.code)
        assertNull(error.errorMessage)
    }

    @Test
    fun `upload maps a success-false envelope with an OBJECT data error to Server`() = runTest {
        // #474 (Codex review) — imgur also returns `data.error` as a {code,message,...} OBJECT, not
        // only a string. A String? field would fail the whole envelope decode on this shape, leaving
        // envelope == null and mis-reporting the refusal as Malformed. The JsonElement model keeps the
        // envelope decodable: this must still be Server, with the object's `message` surfaced.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"data":{"error":{"code":1003,"message":"File type invalid"}},""" +
                        """"success":false,"status":400}""",
                ),
        )

        val error = runCatching { provider.upload(sampleImage()) }.exceptionOrNull()

        assertTrue("an object-form data.error must still be Server, not Malformed", error is UploadException.Server)
        error as UploadException.Server
        assertEquals(400, error.code)
        assertEquals("File type invalid", error.errorMessage)
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

    @Test
    fun `upload returns a null delete handle when the response omits the deletehash`() = runTest {
        // #474 — without a deletehash imgur cannot delete the image; surfacing an empty/absent handle
        // as null disables the delete affordance instead of falsely promising a removal.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"data":{"link":"https://i/x.png"},"success":true}"""),
        )

        val result = provider.upload(sampleImage())

        assertEquals("https://i/x.png", result.imageUrl)
        assertNull("a missing deletehash must yield a null handle (canDelete=false)", result.deleteHandle)
    }

    @Test
    fun `upload returns a null delete handle when the deletehash is blank`() = runTest {
        // A blank deletehash is as useless as an absent one — must not survive as an empty handle.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"data":{"link":"https://i/x.png","deletehash":"  "},"success":true}"""),
        )

        val result = provider.upload(sampleImage())

        assertNull(result.deleteHandle)
    }

    @Test
    fun `upload rejects an oversized image with TooLarge without hitting the network`() = runTest {
        // #474 — the per-host MAX_BYTES guard fires locally, before any POST is enqueued.
        val tooBig = ImageUpload(
            bytes = ByteArray((ImgurProvider.MAX_BYTES + 1).toInt()),
            mimeType = "image/jpeg",
            displayName = "big.jpg",
        )

        val error = runCatching { provider.upload(tooBig) }.exceptionOrNull()

        assertTrue(error is UploadException.TooLarge)
        assertEquals(ImgurProvider.MAX_BYTES, (error as UploadException.TooLarge).maxBytes)
        assertEquals("no POST may be sent for an over-limit image", 0, server.requestCount)
    }

    @Test
    fun `upload keeps a typed Server error when a non-2xx response body read fails`() = runTest {
        // #474 (Codex review) — a truncated / cut response body must NOT leak a raw IOException past
        // the UploadException contract, and a known non-2xx status must survive an unreadable body.
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("part")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val error = runCatching { provider.upload(sampleImage()) }.exceptionOrNull()

        assertTrue("a body-read failure must stay a typed UploadException", error is UploadException)
        assertTrue("a non-2xx must remain Server even if the body is unreadable", error is UploadException.Server)
        assertEquals(500, (error as UploadException.Server).code)
    }

    @Test
    fun `upload does NOT apply the still-image cap to a GIF above MAX_BYTES`() = runTest {
        // #474 (Codex review) — imgur allows animated GIFs up to 200 MB, so a GIF past the 20 MB
        // still-image cap must NOT be rejected locally; it is uploaded (the reader bounds the size).
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"data":{"link":"https://i.imgur.com/g.gif","deletehash":"DH"},""" +
                        """"success":true,"status":200}""",
                ),
        )
        val bigGif = ImageUpload(
            bytes = ByteArray((ImgurProvider.MAX_BYTES + 1).toInt()),
            mimeType = "image/gif",
            displayName = "anim.gif",
        )

        val result = runCatching { provider.upload(bigGif) }

        assertTrue("a large GIF must not be rejected by the still-image cap", result.isSuccess)
        assertEquals("https://i.imgur.com/g.gif", result.getOrThrow().imageUrl)
        assertEquals("the GIF must actually be POSTed, not locally rejected", 1, server.requestCount)
    }

    @Test
    fun `upload raises a typed Configuration error when the Client-ID is blank, before any network`() = runTest {
        // #474 — a blank Client-ID must fail with a typed config error locally, not an opaque imgur
        // 400/403 after a wasted round-trip.
        clientIdFlow.value = "   "

        val error = runCatching { provider.upload(sampleImage()) }.exceptionOrNull()

        assertTrue("blank Client-ID must be a Configuration error", error is UploadException.Configuration)
        assertEquals("no POST may be sent when the Client-ID is blank", 0, server.requestCount)
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
