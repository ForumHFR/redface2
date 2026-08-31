package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.write.DeletePostResult
import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import fr.forumhfr.redface2.core.parser.write.ReplySubmitResponseParser
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Repository-level tests for the #292 delete wire contract. Deletion reuses the edit form
 * (`message.php?…&numreponse={N}` to fetch, `bdd.php` to submit) but adds the distinguishing
 * `delete=1` field, and classifies « Message effacé avec succès ! » as success. The success
 * refresh URL decides whether a whole topic was removed (listing redirect) or just a post
 * (topic redirect).
 */
class DefaultDeletePostRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HfrClient
    private lateinit var repository: DefaultDeletePostRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val okHttp = OkHttpClient.Builder().build()
        client = HfrClient(
            authenticated = okHttp,
            anonymous = okHttp,
            mutation = okHttp.newBuilder().retryOnConnectionFailure(false).build(),
            baseUrl = server.url("/"),
            ioDispatcher = Dispatchers.Unconfined,
        )
        repository = DefaultDeletePostRepository(
            hfrClient = client,
            replyFormParser = ReplyFormParser(),
            replySubmitResponseParser = ReplySubmitResponseParser(),
            diagnostics = DiagnosticsLog(),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `delete posts to bdd_php with delete=1 and the edit fields, returns a normal-post Success`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_delete_post_form.html")))
        server.enqueue(MockResponse().setBody(fixture("write_delete_post_success_response.html")))
        val context = EditPostContext(
            cat = 23,
            subcat = 550,
            topicId = 35_395,
            page = 20,
            numreponse = 2_784_595,
        )

        val result = repository.deletePost(context)

        assertTrue("delete must classify as Success — got $result", result is DeletePostResult.Success)
        assertFalse(
            "a normal-post delete (topic refresh) is not a whole-topic delete",
            (result as DeletePostResult.Success).deletedWholeTopic,
        )

        // Drop the GET, inspect the POST.
        server.takeRequest()
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("bdd.php", recorded.requestUrl?.pathSegments?.first())

        val body = parseFormBody(recorded.body.readUtf8())
        assertEquals("the delete flag must be set", "1", body["delete"])
        assertEquals("delete must echo the post via numreponse", "2784595", body["numreponse"])
        assertEquals("", body["numrep"])
        assertEquals("23", body["cat"])
        assertEquals("550", body["subcat"])
        assertEquals("35395", body["post"])
        assertEquals("20", body["page"])
        assertFalse("password must never be transmitted", body.containsKey("password"))
    }

    @Test
    fun `whole-topic delete success flags deletedWholeTopic`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_delete_topic_form.html")))
        server.enqueue(MockResponse().setBody(fixture("write_delete_topic_success_response.html")))
        val context = EditPostContext(
            cat = 23,
            subcat = 550,
            topicId = 35_395,
            page = 1,
            numreponse = 2_784_595,
        )

        val result = repository.deletePost(context)

        assertTrue("delete must classify as Success — got $result", result is DeletePostResult.Success)
        assertTrue(
            "a whole-topic delete (listing refresh) must set deletedWholeTopic",
            (result as DeletePostResult.Success).deletedWholeTopic,
        )
    }

    private fun fixture(name: String): String {
        val stream = requireNotNull(
            DefaultDeletePostRepositoryTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun parseFormBody(body: String): Map<String, String> =
        body.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val (key, value) = pair.split('=', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }
}
