package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HfrApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HfrApiClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val okHttp = OkHttpClient.Builder().build()
        client = HfrApiClient(
            authenticated = okHttp,
            anonymous = okHttp,
            baseUrl = server.url("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getCategories hits rest_api with uri=forums hardwarefr categories`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.getCategories(useAuth = false)

        val recorded = server.takeRequest()
        val url = requireNotNull(recorded.requestUrl)
        assertEquals("/webservices/rest_api.php", url.encodedPath)
        assertEquals("forums/hardwarefr/categories/", url.queryParameter("uri"))
    }

    @Test
    fun `getSubcategories embeds the cat id in the uri path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.getSubcategories(cat = 13)

        val recorded = server.takeRequest()
        val url = requireNotNull(recorded.requestUrl)
        assertEquals("forums/hardwarefr/categories/13/subcategories/", url.queryParameter("uri"))
    }

    @Test
    fun `getTopicList with subcat sends top-level page and results_per_page`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.getTopicList(cat = 23, subcat = 550, page = 1, resultsPerPage = 50)

        val recorded = server.takeRequest()
        val url = requireNotNull(recorded.requestUrl)
        assertEquals(
            "forums/hardwarefr/categories/23/subcategories/550/topics/last/",
            url.queryParameter("uri"),
        )
        assertEquals("1", url.queryParameter("page"))
        assertEquals("50", url.queryParameter("results_per_page"))
    }

    @Test
    fun `getTopicList without subcat hits the cat-level last topics endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.getTopicList(cat = 13, subcat = null, page = 2, resultsPerPage = 50)

        val recorded = server.takeRequest()
        val url = requireNotNull(recorded.requestUrl)
        assertEquals("forums/hardwarefr/categories/13/topics/last/", url.queryParameter("uri"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("50", url.queryParameter("results_per_page"))
    }

    @Test
    fun `getTopicMetadata hits the topic-level URI without page params`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.getTopicMetadata(cat = 23, topicId = 35395)

        val recorded = server.takeRequest()
        val url = requireNotNull(recorded.requestUrl)
        assertEquals("forums/hardwarefr/categories/23/topics/35395/", url.queryParameter("uri"))
        assertNull(url.queryParameter("page"))
        assertNull(url.queryParameter("results_per_page"))
    }

    @Test
    fun `HTTP 500 surfaces an IOException carrying the URL and a body excerpt`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal explode"))

        val error = runCatching { client.getCategories() }.exceptionOrNull()

        assertTrue("expected IOException, got $error", error is java.io.IOException)
        val message = requireNotNull(error).message.orEmpty()
        assertTrue("missing status: $message", "500" in message)
        assertTrue("missing body excerpt: $message", "internal explode" in message)
    }

    @Test
    fun `authenticated call detects a session expired login redirect`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login.php"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("login form"))

        val error = runCatching {
            client.getTopicList(cat = 13, subcat = null, page = 1, useAuth = true)
        }.exceptionOrNull()

        assertTrue("expected SessionExpiredException, got $error", error is SessionExpiredException)
    }

    @Test
    fun `rewriteHateoasHref converts a HATEOAS href into a callable URL with carried over query params`() {
        val href = (
            "https://forum.hardware.fr/api/forums/hardwarefr/categories/" +
                "23/topics/35395/posts/?page=12&results_per_page=40"
            ).toHttpUrl()

        val rewritten = client.rewriteHateoasHref(href)

        assertEquals("https", rewritten.scheme)
        assertEquals("forum.hardware.fr", rewritten.host)
        assertEquals("/webservices/rest_api.php", rewritten.encodedPath)
        assertEquals(
            "forums/hardwarefr/categories/23/topics/35395/posts/",
            rewritten.queryParameter("uri"),
        )
        assertEquals("12", rewritten.queryParameter("page"))
        assertEquals("40", rewritten.queryParameter("results_per_page"))
    }

    @Test
    fun `rewriteHateoasHref keeps a uri-less link clean`() {
        val href = "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/".toHttpUrl()

        val rewritten = client.rewriteHateoasHref(href)

        assertEquals("forums/hardwarefr/categories/13/", rewritten.queryParameter("uri"))
        assertNull(rewritten.queryParameter("page"))
    }

    @Test
    fun `rewriteHateoasHref rejects a wrong host`() {
        val bad: HttpUrl = "https://example.com/api/forums/hardwarefr/categories/".toHttpUrl()

        val error = runCatching { client.rewriteHateoasHref(bad) }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $error", error is IllegalArgumentException)
    }

    @Test
    fun `rewriteHateoasHref rejects a non-api path`() {
        val bad: HttpUrl = "https://forum.hardware.fr/forum2.php?cat=23".toHttpUrl()

        val error = runCatching { client.rewriteHateoasHref(bad) }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $error", error is IllegalArgumentException)
    }

    @Test
    fun `getTopicList rejects an out-of-range resultsPerPage`() = runTest {
        val error = runCatching {
            client.getTopicList(cat = 13, subcat = null, page = 1, resultsPerPage = 0)
        }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $error", error is IllegalArgumentException)
    }
}
