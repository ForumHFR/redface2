package fr.forumhfr.redface2.core.data.mpstorage

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocation
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocationStore
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageDocument
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
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
