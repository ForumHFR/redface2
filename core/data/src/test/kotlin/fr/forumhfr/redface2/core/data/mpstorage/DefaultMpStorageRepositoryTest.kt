package fr.forumhfr.redface2.core.data.mpstorage

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageDocument
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageThreadParser
import fr.forumhfr.redface2.core.parser.mpstorage.MpStorageDiscoveryParser
import fr.forumhfr.redface2.core.parser.mpstorage.MpStorageParser
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * MPStorage read pipeline (#6, ADR-014) over a [MockWebServer], real fixtures only :
 * the discovery listing fixtures were captured live 2026-06-11 ; the conversation page is
 * the #298 fixture ; the edit form is the Phase 2D fixture (whose `content_form` is a real
 * BBCode post, NOT JSON — which legitimately exercises the [MpStorageResult.Unreadable]
 * arm of the pipeline). The Found arm is exercised with a stubbed storage parser over the
 * same pipeline (plus the JSON-contract coverage in `MpStorageParserTest`) ; a live
 * end-to-end Found fixture requires an account that owns a storage MP (ADR-014 lists this
 * as a remaining verification gap).
 */
class DefaultMpStorageRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultMpStorageRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        repository = buildRepository()
    }

    private fun buildRepository(storageParser: MpStorageParser = MpStorageParser()): DefaultMpStorageRepository {
        val okHttp = OkHttpClient.Builder().build()
        val client = HfrClient(
            authenticated = okHttp,
            anonymous = okHttp,
            baseUrl = server.url("/"),
            ioDispatcher = Dispatchers.Unconfined,
        )
        return DefaultMpStorageRepository(
            hfrClient = client,
            discoveryParser = MpStorageDiscoveryParser(),
            threadParser = PrivateMessageThreadParser(),
            replyFormParser = ReplyFormParser(),
            storageParser = storageParser,
            diagnostics = DiagnosticsLog(),
            clock = Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `no storage MP maps to NotFound after a single authenticated search GET`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("mp_storage_search_no_results.html")))

        val result = repository.fetchStorage()

        assertEquals(MpStorageResult.NotFound, result)
        assertEquals(1, server.requestCount)
        val url = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("forum1.php", url.pathSegments.first())
        assertEquals("prive", url.queryParameter("cat"))
        assertEquals("1", url.queryParameter("recherches"))
        assertEquals("a2bcc09b796b8c6fab77058ff8446c34", url.queryParameter("search"))
        assertEquals("1", url.queryParameter("titre"))
    }

    @Test
    fun `discovery hit drives the 3-GET pipeline and a non-JSON document maps to Unreadable`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("mp_storage_search_hit.html")))
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))
        // Real edit-form fixture : its content_form is a plain BBCode post — per ADR-014 an
        // unreadable document is surfaced, NEVER repaired (no further request, no write).
        server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html")))

        val result = repository.fetchStorage()

        assertEquals(MpStorageResult.Unreadable, result)
        assertEquals(3, server.requestCount)

        val search = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("forum1.php", search.pathSegments.first())

        // Thread page of the FIRST discovered conversation — the fixture's first listing row
        // (HFR default sort : last-message date) was renumbered 9000003 during scrubbing.
        val thread = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("forum2.php", thread.pathSegments.first())
        assertEquals("prive", thread.queryParameter("cat"))
        assertEquals("9000003", thread.queryParameter("post"))
        assertEquals("1", thread.queryParameter("page"))

        // Edit form of the conversation's FIRST post — the #298 thread fixture's first
        // anchor is t1980664234, so that exact numreponse must be forwarded.
        val edit = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("message.php", edit.pathSegments.first())
        assertEquals("prive", edit.queryParameter("cat"))
        assertEquals("9000003", edit.queryParameter("post"))
        assertEquals("1980664234", edit.queryParameter("numreponse"))
    }

    @Test
    fun `a parseable document rides the same pipeline and maps to Found`() = runTest {
        // Codex review of #406 : no real account with a storage MP exists to capture a live
        // JSON content_form (the ADR-014 verification gap), so the Found arm is exercised by
        // stubbing ONLY the storage parser over the real 3-GET pipeline and real fixtures.
        val document = MpStorageDocument(
            sourceName = "DTCloud",
            mpFlags = listOf(MpStorageFlagEntry(threadId = 12345, page = 3, numreponse = 42, uri = null)),
            rawEnvelope = """{ "data": [] }""",
        )
        repository = buildRepository(
            storageParser = mockk { every { parse(any()) } returns Result.success(document) },
        )
        server.enqueue(MockResponse().setBody(fixture("mp_storage_search_hit.html")))
        server.enqueue(MockResponse().setBody(fixture("private_message_thread.html")))
        server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html")))

        val result = repository.fetchStorage()

        assertEquals(MpStorageResult.Found(document), result)
        assertEquals(3, server.requestCount)
    }

    private fun fixture(name: String): String {
        val stream = requireNotNull(
            DefaultMpStorageRepositoryTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }
        return stream.bufferedReader().use { it.readText() }
    }
}
