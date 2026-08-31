package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.model.write.EditFirstPostContext
import fr.forumhfr.redface2.core.model.write.NewTopicContext
import fr.forumhfr.redface2.core.model.write.NewTopicSubmitResult
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.write.ReplySubmitResponseParser
import fr.forumhfr.redface2.core.parser.write.TopicFormParser
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
 * Repository-level tests for Phase 2D #148 (edit first post). Pins the wire
 * contract :
 *
 *  - GET targets `message.php?…&numreponse={firstPost}`.
 *  - POST targets `bdd.php?config=hfr.inc`.
 *  - POST body carries the modified subject + content, the selected subcat,
 *    `numreponse` of the FP, and `numrep=""`.
 *  - `password` and `delete` never reach the wire (the FP form ships a
 *    « Effacer l'intégralité du sujet » checkbox).
 *  - Diagnostics never leak `hash_check`, the BBCode content, or the
 *    `numreponse` via the refresh URL.
 */
class DefaultTopicFormRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HfrClient
    private lateinit var diagnostics: DiagnosticsLog
    private lateinit var repository: DefaultTopicFormRepository

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
        diagnostics = DiagnosticsLog()
        repository = DefaultTopicFormRepository(
            hfrClient = client,
            topicFormParser = TopicFormParser(),
            replySubmitResponseParser = ReplySubmitResponseParser(),
            diagnostics = diagnostics,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `GET FP form hits message_php with numreponse and full id tuple`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_edit_first_post_form.html")))
        val context = EditFirstPostContext(
            cat = 10,
            subcat = 388,
            topicId = 148_749,
            page = 1,
            numreponse = 2_523_829,
        )
        val form = repository.fetchEditFirstPostForm(context)
        assertEquals("[Redface2] Topic temporaire de test écriture", form.subject)
        assertEquals(388, form.selectedSubcat)

        val recorded = server.takeRequest()
        val url = recorded.requestUrl!!
        assertEquals("message.php", url.pathSegments.first())
        assertEquals("10", url.queryParameter("cat"))
        assertEquals("148749", url.queryParameter("post"))
        assertEquals("388", url.queryParameter("subcat"))
        assertEquals("1", url.queryParameter("page"))
        assertEquals("2523829", url.queryParameter("numreponse"))
    }

    @Test
    fun `POST FP edit hits bdd_php with sujet, subcat, numreponse rempli and numrep empty`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_edit_first_post_form.html")))
        server.enqueue(MockResponse().setBody(fixture("write_edit_first_post_success_response.html")))
        val context = EditFirstPostContext(
            cat = 10,
            subcat = 388,
            topicId = 148_749,
            page = 1,
            numreponse = 2_523_829,
        )
        val form = repository.fetchEditFirstPostForm(context)
        val result = repository.submitEditFirstPost(
            context = context,
            form = form,
            subject = "[Redface2] Sujet renommé",
            bbcodeContent = "Edited FP body",
            selectedSubcat = 388,
            options = ReplyFormOptions(signatureEnabled = true),
        )
        assertTrue("FP edit must classify as Success — got $result", result is ReplySubmitResult.Success)

        server.takeRequest() // drop GET
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("bdd.php", recorded.requestUrl!!.pathSegments.first())

        val body = parseFormBody(recorded.body.readUtf8())
        assertEquals("[Redface2] Sujet renommé", body["sujet"])
        assertEquals("Edited FP body", body["content_form"])
        assertEquals("388", body["subcat"])
        assertEquals("2523829", body["numreponse"])
        assertEquals("numrep stays empty for an FP edit", "", body["numrep"])
        assertEquals("10", body["cat"])
        assertEquals("148749", body["post"])
        assertEquals("1", body["page"])
        assertEquals("1", body["signature"])
        assertFalse("password must never reach HFR", body.containsKey("password"))
        assertFalse(
            "delete must never reach HFR on an FP edit — destructive flow is out of scope",
            body.containsKey("delete"),
        )
        // Poll fields : the fixture has no active sondage, so nothing poll-
        // related should be on the wire. Before the poll-source-of-truth fix,
        // empty `textreponse0..10` and date inputs were leaking through
        // `hiddenFields` even though no sondage was active.
        listOf(
            "have_sondage", "textreponse0", "textreponse1", "textreponse5", "textreponse10",
            "allowvisitor", "max_votes", "jour", "mois", "annee", "heure", "minute",
        ).forEach { name ->
            assertFalse(
                "$name must not be POSTed when no sondage is active — got body=$body",
                body.containsKey(name),
            )
        }
    }

    @Test
    fun `synthetic poll fields are forwarded exactly once through TopicPollForm`() = runTest {
        // Build a tiny synthetic form whose only purpose is to exercise the
        // poll-passthrough branch (the real fixture has have_sondage unchecked).
        // The point of this test is to prove that when TopicPollForm.fields
        // carries poll keys, the wire body contains them once — not twice and
        // not zero times.
        val syntheticForm = """<html><body><form action="bdd.php?config=hfr.inc">
            <input type="hidden" name="hash_check" value="HASH" />
            <input type="hidden" name="pseudo" value="me" />
            <input type="text" name="sujet" value="Topic with poll" />
            <textarea name="content_form">Body</textarea>
            <select name="subcat"><option value="388" selected="selected">Divers</option></select>
            <input type="checkbox" name="have_sondage" value="1" checked="checked" />
            <input name="textreponse0" value="Yes" />
            <input name="textreponse1" value="No" />
            <select name="max_votes"><option value="2" selected="selected">2</option></select>
            <input type="text" name="jour" value="31" />
            <input type="text" name="mois" value="12" />
            <input type="text" name="annee" value="2026" />
        </form></body></html>"""

        server.enqueue(MockResponse().setBody(syntheticForm))
        server.enqueue(MockResponse().setBody(fixture("write_edit_first_post_success_response.html")))

        val context = EditFirstPostContext(
            cat = 10, subcat = 388, topicId = 148_749, page = 1, numreponse = 2_523_829,
        )
        val form = repository.fetchEditFirstPostForm(context)
        assertTrue("Synthetic form must surface an active poll", form.poll.present)
        repository.submitEditFirstPost(
            context = context,
            form = form,
            subject = "Topic with poll",
            bbcodeContent = "Body",
            selectedSubcat = 388,
        )

        server.takeRequest() // GET
        val recorded = server.takeRequest()
        val rawBody = recorded.body.readUtf8()
        val pairs = rawBody.split('&')

        // Each poll key must appear exactly once on the wire.
        listOf("have_sondage=1", "textreponse0=Yes", "textreponse1=No", "max_votes=2",
            "jour=31", "mois=12", "annee=2026").forEach { pair ->
            assertEquals(
                "Poll pair '$pair' must appear exactly once — body=$rawBody",
                1,
                pairs.count { it == pair },
            )
        }
    }

    @Test
    fun `success diagnostics do not leak numreponse via refreshUrl`() = runTest {
        // FP refresh URL anchors `#t{numreponse}` (see
        // write_edit_first_post_success_response.html). Same regression guard
        // as `DefaultEditPostRepositoryTest` — the diagnostics buffer is
        // user-visible in the alpha panel.
        server.enqueue(MockResponse().setBody(fixture("write_edit_first_post_form.html")))
        server.enqueue(MockResponse().setBody(fixture("write_edit_first_post_success_response.html")))
        val context = EditFirstPostContext(
            cat = 10,
            subcat = 388,
            topicId = 148_749,
            page = 1,
            numreponse = 2_523_829,
        )
        val form = repository.fetchEditFirstPostForm(context)
        repository.submitEditFirstPost(
            context = context,
            form = form,
            subject = "[Redface2] Sujet renommé",
            bbcodeContent = "Edited FP body",
            selectedSubcat = 388,
        )

        val records = diagnostics.entries.value
        listOf("2523829", "#t2523829", "refreshUrl=/hfr/").forEach { forbidden ->
            assertFalse(
                "Diagnostics buffer must not contain '$forbidden' — records: ${records.map { it.message }}",
                records.any { it.message.contains(forbidden) },
            )
        }
        assertTrue(
            "At least one record must surface FP submit success",
            records.any { it.message.contains("POST FP edit Success") },
        )
    }

    // ---- Phase 2E (#149) — create-topic --------------------------------------

    @Test
    fun `GET new-topic hits message_php with cat, subcat, sondage, owntopic, new params`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_create_topic_form_android_cat.html")))
        repository.fetchNewTopicForm(NewTopicContext(cat = 23, entrySubcat = 550))

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        val url = recorded.requestUrl!!
        assertEquals("message.php", url.pathSegments.first())
        assertEquals("hfr.inc", url.queryParameter("config"))
        assertEquals("23", url.queryParameter("cat"))
        assertEquals("550", url.queryParameter("subcat"))
        assertEquals("0", url.queryParameter("sondage"))
        assertEquals("0", url.queryParameter("owntopic"))
        assertEquals("0", url.queryParameter("new"))
    }

    @Test
    fun `GET new-topic with null entrySubcat passes subcat=0`() = runTest {
        // « Toutes les sous-catégories » view : HFR is fine with subcat=0 and
        // serves the same composer (sub-category dropdown left fully empty).
        server.enqueue(MockResponse().setBody(fixture("write_create_topic_form_android_cat.html")))
        repository.fetchNewTopicForm(NewTopicContext(cat = 23, entrySubcat = null))

        val recorded = server.takeRequest()
        assertEquals("0", recorded.requestUrl!!.queryParameter("subcat"))
    }

    @Test
    fun `POST new-topic hits bddpost_php with sujet subcat content from_subcat empty post numreponse`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_create_topic_form_android_cat.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val context = NewTopicContext(cat = 23, entrySubcat = 550)
        val form = repository.fetchNewTopicForm(context)
        val result = repository.submitNewTopic(
            context = context,
            form = form,
            subject = "Topic test Redface 2 v49",
            bbcodeContent = "Contenu du nouveau topic.",
            selectedSubcat = 562, // user picked « Téléphone » instead of the entry chip
            options = ReplyFormOptions(signatureEnabled = true),
        )
        assertTrue("Create-topic must classify Success — got $result", result is NewTopicSubmitResult.Success)

        server.takeRequest() // drop GET
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("bddpost.php", recorded.requestUrl!!.pathSegments.first())

        val body = parseFormBody(recorded.body.readUtf8())
        assertEquals("Topic test Redface 2 v49", body["sujet"])
        assertEquals("Contenu du nouveau topic.", body["content_form"])
        assertEquals("23", body["cat"])
        // Dropdown choice — distinct from the entry chip below.
        assertEquals("562", body["subcat"])
        // d'arrivée chip — sourced from `form.hiddenFields["from_subcat"]`,
        // NOT from `selectedSubcat`. The two are deliberately decoupled.
        assertEquals("550", body["from_subcat"])
        // Brand-new topic : these three identifiers are always blank.
        assertEquals("", body["post"])
        assertEquals("", body["numreponse"])
        assertEquals("", body["numrep"])
        assertEquals("1", body["page"])
        assertEquals("1100", body["verifrequet"])
        assertTrue("hash_check must reach the wire", body["hash_check"].orEmpty().isNotEmpty())
        assertEquals("1", body["signature"])
        // Deny rules — wire never sees these.
        assertFalse("password must never reach HFR", body.containsKey("password"))
        assertFalse("delete must never reach HFR on create-topic", body.containsKey("delete"))
        // Poll fields must not leak on a no-poll fixture.
        listOf(
            "have_sondage", "textreponse0", "textreponse5", "textreponse10",
            "allowvisitor", "max_votes", "jour", "mois", "annee", "heure", "minute",
        ).forEach { name ->
            assertFalse(
                "$name must not be POSTed when no sondage is active — body=$body",
                body.containsKey(name),
            )
        }
    }

    @Test
    fun `POST new-topic in a cat without sub-category posts subcat=0 (cat IA)`() = runTest {
        // #213 — the « Intelligence artificielle » category (cat=32) ships a create
        // form with NO <select name=subcat> (real fixture `write_ia_create_form.html`).
        // The parser surfaces `hasSubcategorySelect = false` / `selectedSubcat = null`,
        // and the ViewModel posts `subcat=0`. End-to-end, the repository must accept
        // `selectedSubcat = 0` for such a form and put `subcat=0` on the wire.
        server.enqueue(MockResponse().setBody(fixture("write_ia_create_form.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val context = NewTopicContext(cat = 32, entrySubcat = null)
        val form = repository.fetchNewTopicForm(context)
        assertFalse("IA cat has no <select name=subcat>", form.hasSubcategorySelect)

        val result = repository.submitNewTopic(
            context = context,
            form = form,
            subject = "Sujet IA",
            bbcodeContent = "Corps du sujet IA.",
            selectedSubcat = 0,
            options = ReplyFormOptions(),
        )
        assertTrue("cat-0-subcat create must classify Success — got $result", result is NewTopicSubmitResult.Success)

        server.takeRequest() // drop GET
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("bddpost.php", recorded.requestUrl!!.pathSegments.first())
        val body = parseFormBody(recorded.body.readUtf8())
        assertEquals("Sujet IA", body["sujet"])
        assertEquals("32", body["cat"])
        assertEquals("subcat=0 must reach the wire for a sub-category-less cat", "0", body["subcat"])
    }

    @Test
    fun `POST new-topic rejects subcat=0 for a cat WITH sub-categories`() = runTest {
        // Symmetric guard : posting `subcat=0` against a cat that DOES expose a
        // <select name=subcat> would silently drop the topic into « no sub-category ».
        // The repository must short-circuit that as a failure.
        server.enqueue(MockResponse().setBody(fixture("write_create_topic_form_android_cat.html")))

        val context = NewTopicContext(cat = 23, entrySubcat = 550)
        val form = repository.fetchNewTopicForm(context)
        assertTrue("Android cat exposes a <select name=subcat>", form.hasSubcategorySelect)

        val result = repository.submitNewTopic(
            context = context,
            form = form,
            subject = "Topic",
            bbcodeContent = "Body",
            selectedSubcat = 0,
            options = ReplyFormOptions(),
        )
        assertTrue("subcat=0 on a cat with sub-categories must fail", result is NewTopicSubmitResult.Failure)
        // No POST went out — only the GET form fetch was consumed.
        server.takeRequest()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `POST new-topic classifies the real create success and exposes no topic id`() = runTest {
        // #214 — replays the REAL create-topic success page captured live
        // (`write_create_topic_success_response.html`, « Votre message a été posté avec
        // succès ! », refresh to `…/liste_sujet-1.htm`). HFR redirects to the category
        // LISTING and returns NO topic id, so the repository classifies Success but with
        // `(newTopicId, newNumreponse) = (null, null)` — the navigation host then lands on
        // the category listing (direct navigation to the created topic is impossible, #206).
        server.enqueue(MockResponse().setBody(fixture("write_create_topic_form_android_cat.html")))
        server.enqueue(MockResponse().setBody(fixture("write_create_topic_success_response.html")))

        val context = NewTopicContext(cat = 23, entrySubcat = 550)
        val form = repository.fetchNewTopicForm(context)
        val result = repository.submitNewTopic(
            context = context,
            form = form,
            subject = "Topic test Redface 2 #214",
            bbcodeContent = "Contenu du nouveau topic.",
            selectedSubcat = 562,
            options = ReplyFormOptions(signatureEnabled = true),
        )

        assertTrue("Create-topic must classify Success — got $result", result is NewTopicSubmitResult.Success)
        val success = result as NewTopicSubmitResult.Success
        assertEquals(null, success.newTopicId)
        assertEquals(null, success.newNumreponse)
        assertEquals(23, success.targetCat)
        assertEquals(562, success.targetSubcat)
    }

    @Test
    fun `POST new-topic with signature off omits the wire key`() = runTest {
        // Browser-style submit : an unchecked option is absent from the POST,
        // not present-and-false. We pin this contract for the three toggles
        // shared with Edit FP / Reply.
        server.enqueue(MockResponse().setBody(fixture("write_create_topic_form_android_cat.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val context = NewTopicContext(cat = 23, entrySubcat = 550)
        val form = repository.fetchNewTopicForm(context)
        repository.submitNewTopic(
            context = context,
            form = form,
            subject = "Topic",
            bbcodeContent = "Body",
            selectedSubcat = 550,
            options = ReplyFormOptions(
                signatureEnabled = false,
                smileyDisabled = false,
                emailNotificationEnabled = false,
            ),
        )

        server.takeRequest()
        val recorded = server.takeRequest()
        val body = parseFormBody(recorded.body.readUtf8())
        assertFalse("signature must be absent when toggle off", body.containsKey("signature"))
        assertFalse("smiley must be absent when toggle off", body.containsKey("smiley"))
        assertFalse("emaill must be absent when toggle off", body.containsKey("emaill"))
    }

    @Test
    fun `POST new-topic on an anonymous form short-circuits as LoginRequired`() = runTest {
        // The anonymous fixture has `pseudo` empty + `password` visible. The
        // wire would refuse the POST anyway ; we refuse it locally so the
        // diagnostics buffer never carries the attempt details.
        server.enqueue(MockResponse().setBody(fixture("write_create_topic_anonymous_form.html")))

        val context = NewTopicContext(cat = 23, entrySubcat = 550)
        val form = repository.fetchNewTopicForm(context)
        val result = repository.submitNewTopic(
            context = context,
            form = form,
            subject = "Topic",
            bbcodeContent = "Body",
            selectedSubcat = 550,
        )
        assertTrue(result is NewTopicSubmitResult.Failure)
        assertEquals(
            ReplyFailureReason.LoginRequired,
            (result as NewTopicSubmitResult.Failure).reason,
        )
        // Only the GET went on the wire ; no POST attempt.
        server.takeRequest()
        assertEquals(0, server.requestCount - 1)
    }

    @Test
    fun `new-topic diagnostics do not leak hash_check, subject, content or refreshUrl`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("write_create_topic_form_android_cat.html")))
        server.enqueue(MockResponse().setBody(fixture("write_reply_success_response.html")))

        val context = NewTopicContext(cat = 23, entrySubcat = 550)
        val form = repository.fetchNewTopicForm(context)
        val secretSubject = "TRES-CONFIDENTIEL-SUJET"
        val secretContent = "TRES-CONFIDENTIEL-CONTENU"
        repository.submitNewTopic(
            context = context,
            form = form,
            subject = secretSubject,
            bbcodeContent = secretContent,
            selectedSubcat = 550,
        )

        val records = diagnostics.entries.value
        // Token must never appear in any log line.
        assertTrue(records.none { it.message.contains("REDACTED_HASH_CHECK") })
        // Subject + content are not surfaced in the log either.
        assertTrue(records.none { it.message.contains(secretSubject) })
        assertTrue(records.none { it.message.contains(secretContent) })
        // Refresh URL parsed from the reply success fixture must be collapsed.
        assertTrue(records.none { it.message.contains("refreshUrl=/hfr/") })
        // A success log line exists.
        assertTrue(
            "Expected at least one Success record",
            records.any { it.message.contains("POST new-topic Success") },
        )
    }

    private fun fixture(name: String): String =
        requireNotNull(
            DefaultTopicFormRepositoryTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }.bufferedReader().use { it.readText() }

    private fun parseFormBody(body: String): Map<String, String> =
        body.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val (k, v) = pair.split('=', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
}
