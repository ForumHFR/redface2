package fr.forumhfr.redface2.core.data.mpstorage

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocation
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocationStore
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageDocument
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageWriteResult
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.network.HfrConstants
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageListParser
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageThreadParser
import fr.forumhfr.redface2.core.parser.mpstorage.MpStorageParser
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
 * MPStorage read pipeline + the opt-in verify-after-write WRITE path (#6, ADR-014 §4) over a
 * [MockWebServer]. The READ tests use real parsers + fixtures; the WRITE tests script the edit-form
 * parse (mockk over [ReplyFormParser]) so the GET / RE-GET round-trip is controllable without a real
 * HFR document — the write contract is NOT OBSERVED LIVE.
 *
 * The opt-in preference defaults OFF : the very first thing the write path checks. A real POST never
 * fires unless the test flips it on AND the mutation actually changes the document.
 */
class DefaultMpStorageRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var locationStore: FakeLocationStore
    private lateinit var preferences: FakeWritePreferences
    private lateinit var repository: DefaultMpStorageRepository

    // Fixed clock shared by the repository's envelope writer and the test's body-deriving helper so the
    // `lastUpdate` stamp is identical on both sides (the verify RE-GET compares the raw mutated body).
    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(1_718_064_000_000L), ZoneOffset.UTC)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        locationStore = FakeLocationStore()
        preferences = FakeWritePreferences()
        repository = buildRepository()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildRepository(
        storageParser: MpStorageParser = MpStorageParser(),
        replyFormParser: ReplyFormParser = ReplyFormParser(),
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
            replyFormParser = replyFormParser,
            storageParser = storageParser,
            // Fixed clock so the `lastUpdate` stamp is deterministic and the test can re-derive the
            // EXACT mutated body the repository POSTs (needed for the verify-after-write RE-GET to match).
            envelopeWriter = MpStorageEnvelopeWriter(fixedClock),
            locationStore = locationStore,
            authRepository = mockk<AuthRepository> {
                every { observeAuthState() } returns MutableStateFlow(authState)
            },
            userPreferencesRepository = preferences,
            diagnostics = DiagnosticsLog(),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    // --- READ pipeline (#6, ADR-014) --------------------------------------------------------------

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

            val thread = requireNotNull(server.takeRequest().requestUrl)
            assertEquals("forum2.php", thread.pathSegments.first())
            assertEquals("prive", thread.queryParameter("cat"))
            assertEquals("9100200", thread.queryParameter("post"))

            val edit = requireNotNull(server.takeRequest().requestUrl)
            assertEquals("message.php", edit.pathSegments.first())
            assertEquals("prive", edit.queryParameter("cat"))
            assertEquals("9100200", edit.queryParameter("post"))
            assertEquals("1980664234", edit.queryParameter("numreponse"))

            assertEquals(MpStorageLocation(9100200, 1980664234), locationStore.saved[OWNER])
        }

    @Test
    fun `a located storage whose first post is unreadable maps to Unreadable, not NotFound`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("mp_storage_inbox_hit.html")))
        server.enqueue(MockResponse().setBody(fixture("mp_storage_thread_empty.html")))

        assertEquals(MpStorageResult.Unreadable, repository.fetchStorage())
        assertEquals(2, server.requestCount)
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
    fun `a cached location reads the document directly without scanning the inbox`() = runTest {
        val document = MpStorageDocument(sourceName = "DTCloud_GM", mpFlags = emptyList(), rawEnvelope = "{}")
        repository = buildRepository(
            storageParser = mockk { every { parse(any()) } returns Result.success(document) },
        )
        locationStore.saved[OWNER] = MpStorageLocation(threadId = 9100200, numreponse = 1980664234)
        server.enqueue(MockResponse().setBody(fixture("write_edit_form_test_post.html")))

        assertEquals(MpStorageResult.Found(document), repository.fetchStorage())
        assertEquals(1, server.requestCount)
    }

    // --- WRITE path (#6, ADR-014 §4) — opt-in, verify-after-write ---------------------------------

    @Test
    fun `writeBackFlag with the opt-in OFF returns DisabledByPreference and sends NO request`() = runTest {
        // The default. Even with a cached location, the path returns before touching the network.
        locationStore.saved[OWNER] = MpStorageLocation(threadId = 9100200, numreponse = 7)

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 1, page = 1, numreponse = 1, uri = null))

        assertEquals(MpStorageWriteResult.DisabledByPreference, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `writeBackFlag returns TargetNotFound when no storage MP exists and writes nothing`() = runTest {
        preferences.enabled.value = true
        server.enqueue(MockResponse().setBody(fixture("mp_storage_inbox_no_hit.html")))

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 1, page = 1, numreponse = 1, uri = null))

        assertEquals(MpStorageWriteResult.TargetNotFound, result)
        // Only the inbox scan happened — anti-doublon: NEVER create a fresh document (ADR-014 §3).
        assertEquals(1, server.requestCount)
        assertNull(locationStore.saved[OWNER])
    }

    @Test
    fun `writeBackFlag returns Unreadable when the located document is not a v01 envelope`() = runTest {
        // The edit form parses, but its content_form is NOT a v0.1 JSON envelope → Unreadable, no POST.
        repository = buildRepository(
            replyFormParser = mockk {
                every { parse(any()) } returns Result.success(storageForm(initialContent = "not json at all"))
            },
        )
        preferences.enabled.value = true
        locationStore.saved[OWNER] = STORAGE_LOCATION
        server.enqueue(MockResponse().setBody(storageEditFormHtml("not json at all")))

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 1, page = 1, numreponse = 1, uri = null))

        assertEquals(MpStorageWriteResult.Unreadable, result)
        // GET the form only — no POST: an unreadable target must never be written over.
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `writeBackFlag returns TooLarge when the mutated body exceeds the cap and sends NO POST`() = runTest {
        val filler = "x".repeat(MpStorageRepository.MAX_CONTENT_FORM_BYTES + 1024)
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [] } } ], "blob": "$filler" }"""
        repository = buildRepository(
            replyFormParser = mockk {
                every { parse(any()) } returns Result.success(storageForm(initialContent = raw))
            },
        )
        preferences.enabled.value = true
        locationStore.saved[OWNER] = STORAGE_LOCATION
        server.enqueue(MockResponse().setBody(storageEditFormHtml(raw)))

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 1, page = 1, numreponse = 1, uri = null))

        assertTrue(result is MpStorageWriteResult.TooLarge)
        // The oversize body is never POSTed (fail-closed): only the GET happened.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `writeBackFlag refuses to POST when the form subject is not the storage hash`() = runTest {
        // A wrong subject on the parsed form is the structural guard: surfaced as TargetNotFound, no POST.
        repository = buildRepository(
            replyFormParser = mockk {
                every { parse(any()) } returns Result.success(
                    storageForm(initialContent = STORAGE_RAW, subject = "Some other conversation"),
                )
            },
        )
        preferences.enabled.value = true
        locationStore.saved[OWNER] = STORAGE_LOCATION
        server.enqueue(MockResponse().setBody(storageEditFormHtml(STORAGE_RAW)))

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 7, page = 4, numreponse = 9, uri = null))

        assertEquals(MpStorageWriteResult.TargetNotFound, result)
        // Only the GET happened — the wrong-subject guard fired before any POST.
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `writeBackFlag no-op (position already current) returns Success verified without a POST`() = runTest {
        // The entry is already at this exact position → the writer reports a no-op → no POST.
        val raw = """{"data":[{"version":"0.1","mpFlags":{"list":[{"post":7,"page":4,"href":"t9","uri":null}]}}]}"""
        repository = buildRepository(
            replyFormParser = mockk {
                every { parse(any()) } returns Result.success(storageForm(initialContent = raw))
            },
        )
        preferences.enabled.value = true
        locationStore.saved[OWNER] = STORAGE_LOCATION
        server.enqueue(MockResponse().setBody(storageEditFormHtml(raw)))

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 7, page = 4, numreponse = 9, uri = null))

        assertEquals(MpStorageWriteResult.Success(verified = true), result)
        assertEquals(1, server.requestCount) // GET only — no POST on a no-op.
    }

    // --- UPDATE-ONLY auto trigger (#597) ----------------------------------------------------------

    @Test
    fun `writeBackFlagIfPresent with the opt-in OFF returns DisabledByPreference and sends NO request`() = runTest {
        // The default. The auto trigger never touches the network when the user has not opted in.
        locationStore.saved[OWNER] = MpStorageLocation(threadId = 9100200, numreponse = 7)

        val entry = MpStorageFlagEntry(threadId = 1, page = 1, numreponse = 1, uri = null)
        val result = repository.writeBackFlagIfPresent(entry)

        assertEquals(MpStorageWriteResult.DisabledByPreference, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `writeBackFlagIfPresent skips (no POST) when the threadId is absent from the document`() = runTest {
        // UPDATE-ONLY anti-pollution: a threadId not already tracked is never added from the trigger.
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [
            { "post": 555, "page": 1, "href": "t1" }
        ] } } ] }"""
        repository = buildRepository(
            replyFormParser = mockk {
                every { parse(any()) } returns Result.success(storageForm(initialContent = raw))
            },
        )
        preferences.enabled.value = true
        locationStore.saved[OWNER] = STORAGE_LOCATION
        server.enqueue(MockResponse().setBody(storageEditFormHtml(raw)))

        val entry = MpStorageFlagEntry(threadId = 42, page = 2, numreponse = 5, uri = "/u")
        val result = repository.writeBackFlagIfPresent(entry)

        assertEquals(MpStorageWriteResult.SkippedNotPresent, result)
        // GET the form only — no POST: the absent threadId is never appended (anti-doublon, ADR-014 §3).
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `writeBackFlagIfPresent updates a present threadId end-to-end (POST + verify)`() = runTest {
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [
            { "post": 42, "page": 1, "href": "t1", "uri": "/old" }
        ] } } ] }"""
        val entry = MpStorageFlagEntry(threadId = 42, page = 2, numreponse = 5, uri = "/forum2.php?post=42&page=2#t5")
        // The exact body the update-only path POSTs (matched by the verify RE-GET).
        val mutated = MpStorageEnvelopeWriter(fixedClock).upsertFlag(raw, entry, updateOnly = true)
            .let { (it as MpStorageEnvelopeWriter.Outcome.Mutated).body }
        repository = buildRepository(
            replyFormParser = mockk {
                every { parse(any()) } returnsMany listOf(
                    Result.success(storageForm(initialContent = raw)),
                    Result.success(storageForm(initialContent = mutated)),
                )
            },
        )
        preferences.enabled.value = true
        locationStore.saved[OWNER] = STORAGE_LOCATION
        server.enqueue(MockResponse().setBody(storageEditFormHtml(raw))) // GET edit form
        server.enqueue(MockResponse().setBody("<html><body>ok</body></html>")) // POST bdd.php
        server.enqueue(MockResponse().setBody(storageEditFormHtml(mutated))) // RE-GET verify

        val result = repository.writeBackFlagIfPresent(entry)

        assertEquals(MpStorageWriteResult.Success(verified = true), result)
        assertEquals(3, server.requestCount)
        server.takeRequest() // GET
        val body = formFields(server.takeRequest().body.readUtf8())
        assertEquals("prive", body["cat"])
        assertTrue(body["content_form"]!!.contains("\"page\":2"))
    }

    @Test
    fun `writeBackFlag POSTs the mutated body with cat=prive, the active pseudo and the hash subject`() = runTest {
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [] } } ] }"""
        // First parse = GET (returns the backup); second parse = RE-GET (returns the mutated body so verify passes).
        val mutated = capturedMutatedBody(raw, MpStorageFlagEntry(threadId = 42, page = 2, numreponse = 5, uri = null))
        repository = buildRepository(
            replyFormParser = mockk {
                every { parse(any()) } returnsMany listOf(
                    Result.success(storageForm(initialContent = raw)),
                    Result.success(storageForm(initialContent = mutated)),
                )
            },
        )
        preferences.enabled.value = true
        locationStore.saved[OWNER] = STORAGE_LOCATION
        server.enqueue(MockResponse().setBody(storageEditFormHtml(raw))) // GET edit form
        server.enqueue(MockResponse().setBody("<html><body>ok</body></html>")) // POST bdd.php
        server.enqueue(MockResponse().setBody(storageEditFormHtml(mutated))) // RE-GET verify

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 42, page = 2, numreponse = 5, uri = null))

        assertEquals(MpStorageWriteResult.Success(verified = true), result)
        assertEquals(3, server.requestCount)

        server.takeRequest() // skip the GET
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("bdd.php", requireNotNull(post.requestUrl).pathSegments.first())
        val body = formFields(post.body.readUtf8())
        assertEquals("prive", body["cat"])
        assertEquals("9100200", body["post"])
        assertEquals("2784595", body["numreponse"])
        assertEquals("", body["numrep"])
        assertEquals("1100", body["verifrequet"])
        // pseudo = the ACTIVE account, sujet = the CONSTANT hash (never form.sujet).
        assertEquals(OWNER, body["pseudo"])
        assertEquals(HfrConstants.MP_STORAGE_SUBJECT_HASH, body["sujet"])
        assertTrue(body["content_form"]!!.contains("\"post\":42"))
        // password / delete are hard-denied — never resent.
        assertNull("password must never be resent", body["password"])
        assertNull("the delete checkbox must never be resent", body["delete"])
    }

    @Test
    fun `writeBackFlag restores the backup when the re-read is CORRUPTED (truncated, not valid JSON)`() = runTest {
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [] } } ] }"""
        // GET = backup ; RE-GET after POST = a TRUNCATED/non-JSON body (real corruption, the HFR
        // non-UTF-8 truncation) ; RE-GET after restore = the backup. Only corruption restores — a valid
        // but different envelope is kept (see the concurrent-write test below).
        repository = buildRepository(
            replyFormParser = mockk {
                every { parse(any()) } returnsMany listOf(
                    Result.success(storageForm(initialContent = raw)), // GET (backup)
                    Result.success(storageForm(initialContent = "{ \"data\": [ { \"versi")), // truncated → corrupt
                    Result.success(storageForm(initialContent = raw)), // verify after restore (OK)
                )
            },
        )
        preferences.enabled.value = true
        locationStore.saved[OWNER] = STORAGE_LOCATION
        server.enqueue(MockResponse().setBody(storageEditFormHtml(raw))) // GET edit form
        server.enqueue(MockResponse().setBody("<html><body>ok</body></html>")) // POST mutation
        server.enqueue(MockResponse().setBody(storageEditFormHtml("{}"))) // RE-GET (mismatch)
        server.enqueue(MockResponse().setBody("<html><body>ok</body></html>")) // POST restore
        server.enqueue(MockResponse().setBody(storageEditFormHtml(raw))) // RE-GET (restored)

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 99, page = 1, numreponse = 1, uri = null))

        assertTrue(result is MpStorageWriteResult.VerificationFailedRestored)
        // GET + POST + RE-GET + restore POST + RE-GET = 5 requests.
        assertEquals(5, server.requestCount)
        server.takeRequest() // GET
        assertEquals("POST", server.takeRequest().method) // mutation POST
        server.takeRequest() // verify RE-GET
        val restorePost = server.takeRequest()
        assertEquals("POST", restorePost.method)
        // The restore re-POSTs the verbatim backup through the same guarded builder.
        val restoreBody = formFields(restorePost.body.readUtf8())
        assertEquals(raw, restoreBody["content_form"])
        assertEquals(HfrConstants.MP_STORAGE_SUBJECT_HASH, restoreBody["sujet"])
    }

    @Test
    fun `writeBackFlag reports RestoreFailed when the backup could not be restored`() = runTest {
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [] } } ] }"""
        repository = buildRepository(
            replyFormParser = mockk {
                every { parse(any()) } returnsMany listOf(
                    Result.success(storageForm(initialContent = raw)), // GET (backup)
                    Result.success(storageForm(initialContent = "{ truncated")), // verify (CORRUPT → restore)
                    Result.success(storageForm(initialContent = "{\"still\":\"wrong\"}")), // restore verify (FAIL)
                )
            },
        )
        preferences.enabled.value = true
        locationStore.saved[OWNER] = STORAGE_LOCATION
        repeat(5) { server.enqueue(MockResponse().setBody("<html><body>ok</body></html>")) }

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 99, page = 1, numreponse = 1, uri = null))

        assertTrue(result is MpStorageWriteResult.VerificationFailedRestoreFailed)
    }

    @Test
    fun `writeBackFlag keeps a valid-but-different re-read (concurrent write) without restoring`() = runTest {
        val raw = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [] } } ] }"""
        // RE-GET returns a VALID envelope that differs from what we posted (HFR re-encoded entities, or
        // a concurrent client wrote in the meantime). The doc is healthy → Success(verified=false), and
        // we must NOT restore (that would clobber a legitimate last-write-wins update). Codex review.
        val concurrent = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [ {"post":7} ] } } ] }"""
        repository = buildRepository(
            replyFormParser = mockk {
                every { parse(any()) } returnsMany listOf(
                    Result.success(storageForm(initialContent = raw)), // GET (backup)
                    Result.success(storageForm(initialContent = concurrent)), // verify: valid but different
                )
            },
        )
        preferences.enabled.value = true
        locationStore.saved[OWNER] = STORAGE_LOCATION
        server.enqueue(MockResponse().setBody(storageEditFormHtml(raw))) // GET edit form
        server.enqueue(MockResponse().setBody("<html><body>ok</body></html>")) // POST mutation
        server.enqueue(MockResponse().setBody(storageEditFormHtml(concurrent))) // RE-GET (valid, different)

        val result = repository.writeBackFlag(MpStorageFlagEntry(threadId = 99, page = 1, numreponse = 1, uri = null))

        assertEquals(MpStorageWriteResult.Success(verified = false), result)
        // GET + POST + RE-GET only — NO restore POST.
        assertEquals(3, server.requestCount)
    }

    /** The exact mutated body the real path would build, so a test can script the RE-GET to match it. */
    private fun capturedMutatedBody(raw: String, entry: MpStorageFlagEntry): String {
        val outcome = MpStorageEnvelopeWriter(fixedClock).upsertFlag(raw, entry)
        return (outcome as MpStorageEnvelopeWriter.Outcome.Mutated).body
    }

    private fun storageForm(initialContent: String, subject: String = HfrConstants.MP_STORAGE_SUBJECT_HASH): ReplyForm =
        ReplyForm(
            hashCheck = "HASH",
            sujet = subject,
            hiddenFields = mapOf("cache" to "0"),
            isAnonymous = false,
            initialContent = initialContent,
        )

    /** Minimal edit-form HTML carrying [content] in the `content_form` textarea (only used by the real parser). */
    private fun storageEditFormHtml(content: String): String =
        """<html><body><form action="bdd.php"><textarea name="content_form">$content</textarea>
           <input type="hidden" name="hash_check" value="HASH">
           <input type="hidden" name="sujet" value="${HfrConstants.MP_STORAGE_SUBJECT_HASH}"></form></body></html>"""

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

    /** Minimal [UserPreferencesRepository] fake exposing only the write opt-in (the only pref the SUT reads). */
    private class FakeWritePreferences : UserPreferencesRepository by mockk(relaxed = true) {
        val enabled = MutableStateFlow(false)
        override fun observeSyncPrivateMessagesWriteEnabled() = enabled
    }

    private companion object {
        const val OWNER = "XaTriX"
        val STORAGE_LOCATION = MpStorageLocation(threadId = 9100200, numreponse = 2784595)
        const val STORAGE_RAW = """{ "data": [ { "version": "0.1", "mpFlags": { "list": [] } } ] }"""
    }
}
