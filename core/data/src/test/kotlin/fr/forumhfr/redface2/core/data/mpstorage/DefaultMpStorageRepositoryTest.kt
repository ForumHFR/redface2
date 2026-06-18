package fr.forumhfr.redface2.core.data.mpstorage

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocation
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocationStore
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageDocument
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageWriteResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageListParser
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageThreadParser
import fr.forumhfr.redface2.core.parser.mpstorage.MpStorageParser
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MPStorage read pipeline (#6, ADR-014) over a [MockWebServer], real parsers + fixtures.
 *
 * Discovery walks the MP INBOX (`forum1.php?cat=prive`) matching a conversation whose subject is
 * the fixed storage hash — the de-facto userscript contract, NOT a title search (HFR's subject
 * index never returns the hash, which is why the original #406 search-based discovery reported
 * NotFound on every real account). The inbox fixtures are crafted to the real
 * `PrivateMessageListParser` selectors; the conversation page is the #298 fixture; the edit form is
 * the Phase 2D fixture (a real BBCode post, NOT JSON — legitimately the [MpStorageResult.Unreadable]
 * arm). The Found arm stubs only the storage parser over the real pipeline (plus the JSON-contract
 * coverage in `MpStorageParserTest`).
 */
class DefaultMpStorageRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var locationStore: FakeLocationStore
    private lateinit var repository: DefaultMpStorageRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        locationStore = FakeLocationStore()
        repository = buildRepository()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildRepository(
        storageParser: MpStorageParser = MpStorageParser(),
        authState: AuthState = AuthState.Authenticated(OWNER),
    ): DefaultMpStorageRepository {
        val okHttp = OkHttpClient.Builder().build()
        val client = HfrClient(
            authenticated = okHttp,
            anonymous = okHttp,
            baseUrl = server.url("/"),
            ioDispatcher = Dispatchers.Unconfined,
        )
        return DefaultMpStorageRepository(
            hfrClient = client,
            listParser = PrivateMessageListParser(),
            threadParser = PrivateMessageThreadParser(),
            replyFormParser = ReplyFormParser(),
            storageParser = storageParser,
            envelopeWriter = MpStorageEnvelopeWriter(),
            locationStore = locationStore,
            authRepository = mockk<AuthRepository> {
                every { observeAuthState() } returns MutableStateFlow(authState)
            },
            diagnostics = DiagnosticsLog(),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `an anonymous session yields NotFound without any request`() = runTest {
        repository = buildRepository(authState = AuthState.Anonymous)

        assertEquals(MpStorageResult.NotFound, repository.fetchStorage())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `no storage subject in the inbox maps to NotFound after scanning the single page`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("mp_storage_inbox_no_hit.html")))

        assertEquals(MpStorageResult.NotFound, repository.fetchStorage())
        assertEquals(1, server.requestCount)

        val scan = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("forum1.php", scan.pathSegments.first())
        assertEquals("prive", scan.queryParameter("cat"))
        assertEquals("1", scan.queryParameter("page"))
        assertNull(locationStore.saved[OWNER])
    }

    @Test
    fun `inbox scan drives the 3-GET pipeline, caches the location, and a non-JSON document maps to Unreadable`() =
        runTest {
            server.enqueue(MockResponse().setBody(fixture("mp_storage_inbox_hit.html")))
            server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))
            // Real edit-form fixture: its content_form is a plain BBCode post — per ADR-014 an
            // unreadable document is surfaced, NEVER repaired (no write).
            server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html")))

            assertEquals(MpStorageResult.Unreadable, repository.fetchStorage())
            assertEquals(3, server.requestCount)

            val scan = requireNotNull(server.takeRequest().requestUrl)
            assertEquals("forum1.php", scan.pathSegments.first())

            // Conversation page of the hash-subject row (threadId 9100200 in the inbox fixture).
            val thread = requireNotNull(server.takeRequest().requestUrl)
            assertEquals("forum2.php", thread.pathSegments.first())
            assertEquals("prive", thread.queryParameter("cat"))
            assertEquals("9100200", thread.queryParameter("post"))

            // Edit form of the conversation's FIRST post — the #298 thread fixture's first anchor
            // is t1980664234, so that exact numreponse must be forwarded.
            val edit = requireNotNull(server.takeRequest().requestUrl)
            assertEquals("message.php", edit.pathSegments.first())
            assertEquals("prive", edit.queryParameter("cat"))
            assertEquals("9100200", edit.queryParameter("post"))
            assertEquals("1980664234", edit.queryParameter("numreponse"))

            // The discovered location is cached so the next fetch skips the inbox scan.
            assertEquals(MpStorageLocation(9100200, 1980664234), locationStore.saved[OWNER])
        }

    @Test
    fun `a located storage whose first post is unreadable maps to Unreadable, not NotFound`() = runTest {
        // The subject matches in the inbox but the conversation page has no first post (empty thread
        // / DOM drift) : the storage MP EXISTS, so it must surface Unreadable — never NotFound (which
        // would silently skip seeding as if the account had no storage). Codex review of this PR.
        server.enqueue(MockResponse().setBody(fixture("mp_storage_inbox_hit.html")))
        server.enqueue(MockResponse().setBody(fixture("mp_storage_thread_empty.html")))

        assertEquals(MpStorageResult.Unreadable, repository.fetchStorage())
        // Inbox scan + conversation page only — no edit-form GET (no first post to read) ...
        assertEquals(2, server.requestCount)
        // ... and nothing is cached: we never resolved a first-post numreponse to remember.
        assertNull(locationStore.saved[OWNER])
    }

    @Test
    fun `a parseable document rides the same pipeline and maps to Found`() = runTest {
        val document = MpStorageDocument(
            sourceName = "DTCloud_GM",
            mpFlags = listOf(MpStorageFlagEntry(threadId = 12345, page = 3, numreponse = 42, uri = null)),
            rawEnvelope = """{ "data": [] }""",
        )
        repository = buildRepository(
            storageParser = mockk { every { parse(any()) } returns Result.success(document) },
        )
        server.enqueue(MockResponse().setBody(fixture("mp_storage_inbox_hit.html")))
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))
        server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html")))

        assertEquals(MpStorageResult.Found(document), repository.fetchStorage())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `discovery walks to the next inbox page when the first has no match`() = runTest {
        val document = MpStorageDocument(sourceName = "DTCloud_GM", mpFlags = emptyList(), rawEnvelope = "{}")
        repository = buildRepository(
            storageParser = mockk { every { parse(any()) } returns Result.success(document) },
        )
        server.enqueue(MockResponse().setBody(fixture("mp_storage_inbox_p1_nohit.html")))
        server.enqueue(MockResponse().setBody(fixture("mp_storage_inbox_p2_hit.html")))
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))
        server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html")))

        assertEquals(MpStorageResult.Found(document), repository.fetchStorage())
        assertEquals(4, server.requestCount)

        assertEquals("1", requireNotNull(server.takeRequest().requestUrl).queryParameter("page"))
        assertEquals("2", requireNotNull(server.takeRequest().requestUrl).queryParameter("page"))
    }

    @Test
    fun `a cached location reads the document directly without scanning the inbox`() = runTest {
        val document = MpStorageDocument(sourceName = "DTCloud_GM", mpFlags = emptyList(), rawEnvelope = "{}")
        repository = buildRepository(
            storageParser = mockk { every { parse(any()) } returns Result.success(document) },
        )
        locationStore.saved[OWNER] = MpStorageLocation(threadId = 9100200, numreponse = 1980664234)
        server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html")))

        assertEquals(MpStorageResult.Found(document), repository.fetchStorage())
        assertEquals(1, server.requestCount)

        val edit = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("message.php", edit.pathSegments.first())
        assertEquals("9100200", edit.queryParameter("post"))
        assertEquals("1980664234", edit.queryParameter("numreponse"))
    }

    // --- WRITE path (#6, ADR-014 §4) — GUARDED, NOT OBSERVED LIVE ---------------------------------

    @Test
    fun `writeBackFlag dryRun prepares the mutated body and sends NO POST`() = runTest {
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [] } } ], "sourceName": "DTCloud" }"""
        repository = buildRepository(
            storageParser = mockk {
                every { parse(any()) } returns Result.success(
                    MpStorageDocument(sourceName = "DTCloud", mpFlags = emptyList(), rawEnvelope = raw),
                )
            },
        )
        locationStore.saved[OWNER] = MpStorageLocation(threadId = 9100200, numreponse = 1980664234)
        // Only the edit-form GET is enqueued — a dry run must not POST anything.
        server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html")))

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 777, page = 4, numreponse = 9, uri = "/u"))

        val prepared = result as MpStorageWriteResult.Prepared
        assertEquals(false, prepared.posted)
        assertTrue("the new flag must be in the prepared body", prepared.body.contains("\"post\":777"))
        // Exactly one request: the GET of the edit form. No POST hit the wire.
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `writeBackFlag with dryRun=false POSTs bdd_php with cat=prive and the preserved hidden fields`() = runTest {
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [] } } ] }"""
        repository = buildRepository(
            storageParser = mockk {
                every { parse(any()) } returns Result.success(
                    MpStorageDocument(sourceName = null, mpFlags = emptyList(), rawEnvelope = raw),
                )
            },
        )
        locationStore.saved[OWNER] = MpStorageLocation(threadId = 9100200, numreponse = 2784595)
        server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html"))) // GET edit form
        server.enqueue(MockResponse().setBody("<html><body>ok</body></html>")) // POST bdd.php

        // dryRun=false is the GUARDED branch — exercised only by this test, never by app code.
        val result = repository.writeBackFlag(
            MpStorageFlagEntry(threadId = 42, page = 2, numreponse = 5, uri = null),
            dryRun = false,
        )

        val prepared = result as MpStorageWriteResult.Prepared
        assertTrue("the live POST branch reports posted = true", prepared.posted)
        assertEquals(2, server.requestCount)

        server.takeRequest() // skip the GET
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("bdd.php", requireNotNull(post.requestUrl).pathSegments.first())
        val body = formFields(post.body.readUtf8())
        // The whole point of submitPrivateMessageEdit : cat is the String "prive", not an Int.
        assertEquals("prive", body["cat"])
        assertEquals("9100200", body["post"])
        assertEquals("2784595", body["numreponse"])
        assertEquals("", body["numrep"])
        assertEquals("1100", body["verifrequet"])
        // content_form carries the mutated JSON, not BBCode.
        assertTrue(body["content_form"]!!.contains("\"post\":42"))
        // hash_check + sujet come from the parsed edit form; hidden fields preserved.
        assertEquals("REDACTED_HASH_CHECK", body["hash_check"])
        assertEquals("Redface 2 — PHASE 2 @ ALPHA", body["sujet"])
        // password / delete are hard-denied — never resent.
        assertNull("password must never be resent", body["password"])
        assertNull("the delete checkbox must never be resent", body["delete"])
    }

    @Test
    fun `writeBackFlag returns TargetNotFound when no storage MP exists and writes nothing`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("mp_storage_inbox_no_hit.html")))

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 1, page = 1, numreponse = 1, uri = null))

        assertEquals(MpStorageWriteResult.TargetNotFound, result)
        // Only the inbox scan happened — anti-doublon: NEVER create a fresh document (ADR-014 §3).
        assertEquals(1, server.requestCount)
        assertNull(locationStore.saved[OWNER])
    }

    @Test
    fun `writeBackFlag returns TargetNotFound on an anonymous session without any request`() = runTest {
        repository = buildRepository(authState = AuthState.Anonymous)

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 1, page = 1, numreponse = 1, uri = null))

        assertEquals(MpStorageWriteResult.TargetNotFound, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `writeBackFlag returns TargetUnreadable when the located document is not a v01 envelope`() = runTest {
        // The real edit-form fixture's content_form is plain BBCode — not a readable v0.1 envelope.
        // Per ADR-014 §3 this is surfaced, NEVER repaired / overwritten with a default.
        locationStore.saved[OWNER] = MpStorageLocation(threadId = 9100200, numreponse = 2784595)
        server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html")))

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 1, page = 1, numreponse = 1, uri = null))

        assertEquals(MpStorageWriteResult.TargetUnreadable, result)
        // GET the form only — no POST: an unreadable target must never be written over.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `writeBackFlag returns TooLarge when the mutated body exceeds the 256 KiB cap`() = runTest {
        val filler = "x".repeat(260 * 1024)
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [] } } ], "blob": "$filler" }"""
        repository = buildRepository(
            storageParser = mockk {
                every { parse(any()) } returns Result.success(
                    MpStorageDocument(sourceName = null, mpFlags = emptyList(), rawEnvelope = raw),
                )
            },
        )
        locationStore.saved[OWNER] = MpStorageLocation(threadId = 9100200, numreponse = 2784595)
        server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html")))

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 1, page = 1, numreponse = 1, uri = null))

        assertTrue(result is MpStorageWriteResult.TooLarge)
        // The oversize body is never POSTed (fail-closed): only the GET happened.
        assertEquals(1, server.requestCount)
    }

    /** Decodes an `application/x-www-form-urlencoded` POST body into a field map (absent field ⇒ null). */
    private fun formFields(body: String): Map<String, String> =
        body.split("&")
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val name = java.net.URLDecoder.decode(pair.substringBefore("="), "UTF-8")
                val value = java.net.URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8")
                name to value
            }

    private fun fixture(name: String): String {
        val stream = requireNotNull(
            DefaultMpStorageRepositoryTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }
        return stream.bufferedReader().use { it.readText() }
    }

    /** In-memory [MpStorageLocationStore] keyed by the owner string (the SUT always passes [OWNER]). */
    private class FakeLocationStore : MpStorageLocationStore {
        val saved = mutableMapOf<String, MpStorageLocation>()

        override suspend fun read(owner: String?): MpStorageLocation? = owner?.let { saved[it] }

        override suspend fun save(owner: String?, threadId: Int, numreponse: Int) {
            if (owner != null) saved[owner] = MpStorageLocation(threadId, numreponse)
        }

        override suspend fun clear(owner: String?) {
            if (owner != null) saved.remove(owner)
        }
    }

    private companion object {
        const val OWNER = "XaTriX"
    }
}
