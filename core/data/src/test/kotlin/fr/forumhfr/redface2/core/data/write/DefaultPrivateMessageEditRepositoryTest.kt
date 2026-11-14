package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocation
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocationStore
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.write.PrivateMessageEditContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import fr.forumhfr.redface2.core.parser.write.ReplySubmitResponseParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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

class DefaultPrivateMessageEditRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultEditPostRepository
    private lateinit var usage: SmileyUsageRecorderFixture
    private val diagnostics = DiagnosticsLog()
    private val locations = mockk<MpStorageLocationStore>()
    private val authState = MutableStateFlow<AuthState>(AuthState.Authenticated("ALIce"))
    private val context = PrivateMessageEditContext(3000003, 1, 1980000302)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        usage = SmileyUsageRecorderFixture()
        val http = OkHttpClient.Builder().build()
        val auth = mockk<AuthRepository>()
        every { auth.observeAuthState() } returns authState
        coEvery { locations.read(any()) } returns null
        repository = DefaultEditPostRepository(
            hfrClient = HfrClient(
                authenticated = http, anonymous = http,
                mutation = http.newBuilder().retryOnConnectionFailure(false).build(),
                baseUrl = server.url("/"), ioDispatcher = Dispatchers.Unconfined,
            ),
            replyFormParser = ReplyFormParser(),
            replySubmitResponseParser = ReplySubmitResponseParser(),
            diagnostics = diagnostics,
            smileyUsageRecorder = usage.recorder,
            authRepository = auth,
            mpStorageLocationStore = locations,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        usage.close()
        server.shutdown()
    }

    @Test
    fun `GET loads private edit on the requested page`() = runTest {
        // Only page 1 was captured live: this checks request wiring, not a measured page 4 form.
        server.enqueue(MockResponse().setBody(fixture("private_message_edit_form.html")))
        val form = repository.fetchEditPostForm(context.copy(page = 4))
        assertTrue(form.initialContent.startsWith("[quotemsg="))
        val url = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("/message.php", url.encodedPath)
        assertEquals("prive", url.queryParameter("cat"))
        assertEquals("3000003", url.queryParameter("post"))
        assertEquals("1980000302", url.queryParameter("numreponse"))
        assertEquals("4", url.queryParameter("page"))
        coVerify(exactly = 1) { locations.read("ALIce") }
    }

    @Test
    fun `POST preserves exact served field order and parents with selected options`() = runTest {
        val original = form()
        val edited = original.copy(
            msgIcon = "9",
            hiddenFields = original.hiddenFields + mapOf("delete" to "1", "password" to "never-echo"),
        )
        server.enqueue(MockResponse().setBody(UNRECOGNISED_RESPONSE))
        val result = repository.submitEditPost(
            context, edited, "Updated private body",
            ReplyFormOptions(signatureEnabled = true, smileyDisabled = true, emailNotificationEnabled = true),
        )
        assertEquals(ReplySubmitResult.Failure(ReplyFailureReason.Unknown), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/bdd.php?config=hfr.inc", request.path)
        assertEquals(
            listOf(
                "hash_check" to "TESTHASH", "subcat" to "0", "parents" to "1980000301",
                "post" to "3000003", "stickold" to "", "new" to "0", "cat" to "prive",
                "numreponse" to "1980000302", "numrep" to "", "page" to "1", "verifrequet" to "1100",
                "p" to "1", "sondage" to "0", "sond" to "0", "cache" to "", "owntopic" to "0",
                "config" to "hfr.inc", "sujet" to "Sujet prive de test", "pseudo" to "TestUser",
                "password" to "", "MsgIcon" to "9", "search_smilies" to "", "ColorUsedMem" to "",
                "content_form" to "Updated private body", "wysiwyg" to "0", "submit" to "Valider votre message",
                "signature" to "1", "smiley" to "1", "emaill" to "1",
            ),
            fields(request.body.readUtf8()),
        )
        assertEquals(1, server.requestCount)
        assertTrue(usage.calls.isEmpty())
    }

    @Test
    fun `first message subject can change without inventing subcat or sending delete`() = runTest {
        val target = context.copy(numreponse = 1980000301)
        val original = form("private_message_edit_first_post_form.html", target)
        val edited = original.copy(sujet = "Updated subject", hiddenFields = original.hiddenFields + ("delete" to "1"))
        server.enqueue(MockResponse().setBody(UNRECOGNISED_RESPONSE))
        repository.submitEditPost(target, edited, "Updated body", original.options)
        val posted = fields(server.takeRequest().body.readUtf8())
        assertEquals(
            listOf(
                "hash_check", "parents", "post", "stickold", "new", "cat", "numreponse", "numrep",
                "page", "verifrequet", "p", "sondage", "sond", "cache", "owntopic", "config",
                "pseudo", "password", "sujet", "MsgIcon", "search_smilies", "ColorUsedMem", "content_form",
                "wysiwyg", "submit", "signature",
            ),
            posted.map { it.first },
        )
        assertEquals("Updated subject", posted.toMap()["sujet"])
        assertEquals("1", posted.toMap()["signature"])
    }

    @Test
    fun `hidden subject stays served and group subscription follows chosen state`() = runTest {
        val target = PrivateMessageEditContext(3000004, 1, 1980000402)
        val original = form("private_message_dt_edit_form.html", target)
        assertTrue(original.options.emailNotificationEnabled)
        server.enqueue(MockResponse().setBody(UNRECOGNISED_RESPONSE))
        repository.submitEditPost(target, original.copy(sujet = "Ignored change"), "Updated body", ReplyFormOptions())
        val posted = fields(server.takeRequest().body.readUtf8()).toMap()
        assertEquals("Sujet prive collectif fictif", posted["sujet"])
        assertEquals("", posted["parents"])
        assertFalse(posted.containsKey("signature"))
        assertFalse(posted.containsKey("smiley"))
        assertFalse(posted.containsKey("emaill"))
        assertFalse(posted.containsKey("dest"))
        assertFalse(posted.containsKey("newdest"))
        assertFalse(posted.containsKey("delete"))
    }

    @Test
    fun `unrecognised response stays uncertain without retry or usage accounting`() = runTest {
        val responses = listOf(
            UNRECOGNISED_RESPONSE,
            "<meta http-equiv=\"Refresh\" content=\"1; url=/forum2.php?cat=prive\">",
            "<p>Votre message a été édité avec succès</p>",
        )
        responses.forEach { response ->
            server.enqueue(MockResponse().setBody(response))
            val result = repository.submitEditPost(context, form(), ":o")
            // Unknown is the existing editor adapter's UNCERTAIN path, not a certain refusal.
            assertEquals(ReplySubmitResult.Failure(ReplyFailureReason.Unknown), result)
        }
        assertEquals(responses.size, server.requestCount)
        assertTrue(usage.calls.isEmpty())
    }

    @Test
    fun `known refusal markers are classified without retries`() = runTest {
        val refusals = listOf(
            "Une erreur est survenue lors de l'envoi des données" to ReplyFailureReason.InvalidHashCheck,
            "Vous devez remplir tous les champs avant de poster ce message" to ReplyFailureReason.EmptyMessage,
            "Désolé, tentatives de flood" to ReplyFailureReason.AntiFlood,
            "Désolé ce sujet a été fermé" to ReplyFailureReason.TopicLocked,
        )
        refusals.forEach { (marker, reason) ->
            // Synthetic classifier payload, not a captured MP POST response.
            server.enqueue(MockResponse().setBody("<div class=hop>$marker</div>"))
            assertEquals(ReplySubmitResult.Failure(reason), repository.submitEditPost(context, form(), ":o"))
        }
        assertEquals(refusals.size, server.requestCount)
        assertTrue(usage.calls.isEmpty())
    }

    @Test
    fun `captured MP edit success returns the edit target coordinates once`() = runTest {
        // Real HFR response to the edit POST (#1625): forum2.php refresh, no `sujet_` segment.
        val response = fixture("private_message_edit_success_response.html")
        server.enqueue(MockResponse().setBody(response))
        val result = repository.submitEditPost(context, form(), "Updated body [:fictif c:2]")
        val success = result as ReplySubmitResult.Success
        assertEquals(1, success.targetPage)
        assertEquals(context.numreponse, success.numreponse)
        assertEquals(context.threadId, success.topicId)
        assertEquals("1", fields(server.takeRequest().body.readUtf8()).toMap()["page"])
        assertEquals(1, server.requestCount)
        // Success records the smiley the edit added; failures above never record any.
        assertEquals(listOf(mapOf("[:fictif c:2]" to 1)), usage.calls.map { it.usages })
        assertTrue(diagnostics.entries.value.any { it.message == "POST MP edit Success" })
    }

    @Test
    fun `edit success takes thread page and message from the target not from the refresh`() = runTest {
        // The captured refresh says post=3000003, page=1, #t1980000302; the editor's own target wins.
        server.enqueue(MockResponse().setBody(fixture("private_message_edit_success_response.html")))
        // A target distinct from the refresh anchor: page 4 and another message of the same thread.
        val served = form(target = context.copy(page = 4))
        val form = served.copy(hiddenFields = served.hiddenFields + ("numreponse" to "1980000399"))
        val target = context.copy(page = 4, numreponse = 1980000399)
        val result = repository.submitEditPost(target, form, "Updated body")
        val refresh = (result as ReplySubmitResult.Success).refreshUrl
        assertEquals(ReplySubmitResult.Success(refresh, 4, 1980000399, context.threadId), result)
        assertEquals("4", fields(server.takeRequest().body.readUtf8()).toMap()["page"])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cached storage message is refused before GET and before POST`() = runTest {
        coEvery { locations.read("ALIce") } returns MpStorageLocation(context.threadId, context.numreponse)
        assertTrue(runCatching { repository.fetchEditPostForm(context) }.exceptionOrNull() is IllegalStateException)
        assertTrue(
            runCatching { repository.submitEditPost(context, form(), "Updated body") }.exceptionOrNull()
                is IllegalStateException,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `storage lookup follows the active account and permits other messages`() = runTest {
        coEvery { locations.read("ALIce") } returns MpStorageLocation(context.threadId, 1980000301)
        server.enqueue(MockResponse().setBody(UNRECOGNISED_RESPONSE))
        repository.submitEditPost(context, form(), "Updated body")
        authState.value = AuthState.Authenticated("Bob")
        server.enqueue(MockResponse().setBody(UNRECOGNISED_RESPONSE))
        repository.submitEditPost(context, form(), "Updated body")
        coVerify(exactly = 1) { locations.read("ALIce") }
        coVerify(exactly = 1) { locations.read("Bob") }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `uncached storage subject is refused on load and cannot be disguised before POST`() = runTest {
        val html = fixture("private_message_edit_form.html")
            .replace("Sujet prive de test", MpStorageRepository.STORAGE_SUBJECT_HASH)
        server.enqueue(MockResponse().setBody(html))
        assertTrue(runCatching { repository.fetchEditPostForm(context) }.exceptionOrNull() is IllegalStateException)
        val storage = ReplyFormParser().parseEditPost(html, context).getOrThrow().copy(sujet = "Disguised")
        assertTrue(
            runCatching { repository.submitEditPost(context, storage, "Updated body") }.exceptionOrNull()
                is IllegalStateException,
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `wrong private target public form and reply composer never reach POST`() = runTest {
        val invalidForms = listOf(
            form().copy(hiddenFields = form().hiddenFields + ("cat" to "23")),
            form().copy(hiddenFields = form().hiddenFields + ("post" to "3000004")),
            form().copy(hiddenFields = form().hiddenFields + ("numreponse" to "1980000301")),
            ReplyFormParser().parse(fixture("private_message_reply_form.html")).getOrThrow(),
        )
        invalidForms.forEach { invalid ->
            assertTrue(
                runCatching { repository.submitEditPost(context, invalid, "Updated body") }.exceptionOrNull()
                    is IllegalStateException,
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `recipient fields and incomplete field order never reach POST`() = runTest {
        val original = form()
        val invalidForms = listOf(
            original.copy(hiddenFields = original.hiddenFields + ("dest" to "Never-send")),
            original.copy(hiddenFields = original.hiddenFields + ("newdest" to "Never-send")),
            original.copy(submitFieldOrder = original.submitFieldOrder - "cat"),
        )
        invalidForms.forEach { invalid ->
            assertTrue(
                runCatching { repository.submitEditPost(context, invalid, "Updated body") }.exceptionOrNull()
                    is IllegalStateException,
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `anonymous form missing token and blank body are refused locally`() = runTest {
        val original = form()
        listOf(
            original.copy(isAnonymous = true) to ReplyFailureReason.LoginRequired,
            original.copy(hashCheck = "") to ReplyFailureReason.InvalidHashCheck,
        ).forEach { (invalid, reason) ->
            assertEquals(ReplySubmitResult.Failure(reason), repository.submitEditPost(context, invalid, "Updated body"))
        }
        assertEquals(
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage),
            repository.submitEditPost(context, original, " \n\t"),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `diagnostics never contain private content identities token or exception text`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("private_message_edit_form.html")))
        val original = repository.fetchEditPostForm(context)
        server.enqueue(MockResponse().setBody(UNRECOGNISED_RESPONSE))
        repository.submitEditPost(context, original, "Private updated body")
        server.enqueue(MockResponse().setResponseCode(503).setStatus("HTTP/1.1 503 Private failure text"))
        assertTrue(runCatching { repository.submitEditPost(context, original, "Private updated body") }.isFailure)
        listOf(
            "Sujet prive", "Message prive", "Private updated body", "Private failure text",
            "3000003", "1980000302", "TestUser", "TESTHASH", "ALIce", "quotemsg",
        ).forEach { forbidden ->
            assertFalse(diagnostics.entries.value.any { it.message.contains(forbidden) })
        }
        assertTrue(diagnostics.entries.value.any { it.message.contains("POST MP edit") })
    }

    private fun form(
        name: String = "private_message_edit_form.html",
        target: PrivateMessageEditContext = context,
    ): ReplyForm = ReplyFormParser().parseEditPost(fixture(name), target).getOrThrow()

    // Reuse parser captures from the two module/root layouts used by the neighbouring tests.
    private fun fixture(name: String): String {
        val candidates = listOf(
            File("../parser/src/test/resources/fixtures", name),
            File("core/parser/src/test/resources/fixtures", name),
        )
        return requireNotNull(candidates.firstOrNull { it.isFile }) { "Shared parser fixture not found: $name" }
            .readText()
    }

    private fun fields(body: String): List<Pair<String, String>> = body.split('&').map { pair ->
        val parts = pair.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
    }

    private companion object {
        // Neutral unit-test body, intentionally not a captured or inferred HFR response.
        private const val UNRECOGNISED_RESPONSE = "<html><body>Neutral test response</body></html>"
    }
}
