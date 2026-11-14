package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorDelivery
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionCallbacks
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionEnvironment
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionErrors
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionRequest
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorWriteOwner
import fr.forumhfr.redface2.core.network.cookie.CookieStore
import fr.forumhfr.redface2.core.network.cookie.PersistentCookieJar
import fr.forumhfr.redface2.core.network.di.NetworkModule
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Real cookie selection and mutation client; exercises both delayed and cancelling auth observers. */
@RunWith(Parameterized::class)
class HfrClientWriteOwnerTest(private val path: WritePath) {
    private val server = MockWebServer()
    private val resolved = CompletableDeferred<StoreWriteResult>()
    private val drafts = object : VersionedEditorDraftStore() {
        var ownerReads = 0
        override suspend fun currentOwner(): String? = super.currentOwner().also { ownerReads++ }
        override suspend fun finishAttempt(token: AttemptToken, outcome: AttemptOutcome): StoreWriteResult =
            super.finishAttempt(token, outcome).also {
                if (outcome == AttemptOutcome.CERTAIN_FAILURE) resolved.complete(it)
            }
    }
    private lateinit var cookies: PersistentCookieJar
    private lateinit var mutation: OkHttpClient
    private lateinit var client: HfrClient
    private var beforeCookieSelection: () -> Unit = {}
    private var afterCookieSelection: () -> Unit = {}
    private var witness: EditorWriteOwner? = null

    @Before
    fun setUp() {
        server.start()
        drafts.activeOwner = "alice"
        val store = object : CookieStore {
            val value = MutableStateFlow(listOf(cookie("Alice")))
            override fun observe() = value
            override suspend fun save(cookies: List<Cookie>) { value.value = cookies }
            override suspend fun clear() { value.value = emptyList() }
        }
        cookies = PersistentCookieJar(store, Dispatchers.Unconfined)
        val base = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                beforeCookieSelection()
                chain.proceed(chain.request())
            }
            .addNetworkInterceptor { chain ->
                afterCookieSelection()
                chain.proceed(chain.request())
            }
            .build()
        mutation = NetworkModule.provideMutationClient(base, cookies)
        client = HfrClient(mutation, mutation, mutation, server.url("/"), Dispatchers.IO)
    }

    @After
    fun tearDown() {
        cookies.close()
        mutation.connectionPool.evictAll()
        mutation.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    /** Red on e49718e4: the owner check succeeds for A, but the POST uses B's cookies. */
    @Test
    fun accountSwitchAfterOwnerCheckRejectsBeforeHttpEmissionAndResolvesAttempt() = runTest {
        assertRejectedSwitch(switchBeforeClient = false)
    }

    /** The transport must use the durable owner even when the IO pipeline already sees B. */
    @Test
    fun accountSwitchInLocalPipelineCannotReplaceTheDurableOwner() = runTest {
        assertRejectedSwitch(switchBeforeClient = true)
    }

    private suspend fun TestScope.assertRejectedSwitch(switchBeforeClient: Boolean) {
        val settled = CompletableDeferred<String>()
        val binding = EditorSubmissionBinding<Unit, String>(
            EditorSubmissionEnvironment(this, DiagnosticsClock { 0L }, DiagnosticsLog()),
            EditorDraftSession(drafts, "key"),
            EditorSubmissionCallbacks(
                state = {}, error = { it?.let(settled::complete) }, success = { settled.complete("success") },
            ),
            EditorSubmissionErrors("uncertain", "draft", "session", "unknown"),
        )
        binding.restore("key")
        binding.setPreference(false)
        beforeCookieSelection = {
            assertEquals("restore and ownsCurrentAccount have both completed", 2, drafts.ownerReads)
            if (!switchBeforeClient) replaceAccount("bob")
        }
        server.enqueue(MockResponse().setBody("unexpected write"))

        binding.launch(binding.request(4_000, true) as SubmissionRequest.Accepted, Draft("body")) {
            if (switchBeforeClient) replaceAccount("bob")
            witness = currentCoroutineContext()[EditorWriteOwner]
            path.submit(client, body())
            EditorDelivery(effect = Unit)
        }

        assertEquals("session", settled.await())
        assertTrue(checkNotNull(witness).provesNoEmission)
        assertEquals("no HTTP request headers or body may reach HFR", 0, server.requestCount)
        assertEquals(EditorSubmissionState.Ready, binding.state)
        assertFalse(binding.blocksMutation)
        drafts.activeOwner = "alice"
        assertNull(drafts.entries["key"]?.attempt)
        assertEquals("body", drafts.entries["key"]?.draft?.body)
    }

    /** Changing the jar after BridgeInterceptor must not change the already selected identity. */
    @Test
    fun stableSelectedCookiesAreSentEvenIfJarChangesLater() = runTest {
        afterCookieSelection = { replaceAccount("bob") }
        server.enqueue(MockResponse().setBody("accepted"))
        val original = body()

        assertEquals("accepted", path.submit(client, original))

        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().getHeader("Cookie").orEmpty().contains("md_user=Alice"))
        assertEquals("body", original.value(0)) // Still readable by #1154.
    }

    @Test
    fun identicalCookiesFromParentAndHostDomainsAllowEmission() = runTest {
        val url = server.url("/").newBuilder().host("forum.hardware.test").build()
        cookies.saveFromResponse(
            url,
            listOf("hardware.test", "forum.hardware.test").map { domain ->
                Cookie.Builder().name("md_user").value("Alice").domain(domain).build()
            },
        )
        val domainTransport = mutation.newBuilder()
            .dns { listOf(InetAddress.getByName("127.0.0.1")) }.build()
        val domainClient = HfrClient(domainTransport, domainTransport, domainTransport, url, Dispatchers.IO)
        server.enqueue(MockResponse().setBody("accepted"))

        assertEquals("accepted", path.submit(domainClient, body()))

        assertEquals(1, server.requestCount)
        val selected = server.takeRequest().getHeader("Cookie").orEmpty().split(';').map(String::trim)
        assertEquals(listOf("md_user=Alice", "md_user=Alice"), selected)
    }

    @Test
    fun accountObserverCancellationCannotKeepARejectedAttemptUncertain() = runTest {
        val binding = EditorSubmissionBinding<Unit, String>(
            EditorSubmissionEnvironment(this, DiagnosticsClock { 0L }, DiagnosticsLog()),
            EditorDraftSession(drafts, "key"),
            EditorSubmissionCallbacks(state = {}, error = {}, success = {}),
            EditorSubmissionErrors("uncertain", "draft", "session", "unknown"),
        )
        binding.restore("key")
        binding.setPreference(false)
        beforeCookieSelection = { replaceAccount("bob") }
        afterCookieSelection = {
            val invalidated = CompletableDeferred<Unit>()
            launch {
                binding.accountChanged()
                invalidated.complete(Unit)
            }
            // Force the UI observer to cancel the send before the network guard returns its refusal.
            runBlocking { withTimeout(5_000) { invalidated.await() } }
        }
        server.enqueue(MockResponse().setBody("unexpected write"))

        binding.launch(binding.request(4_000, true) as SubmissionRequest.Accepted, Draft("body")) {
            witness = currentCoroutineContext()[EditorWriteOwner]
            path.submit(client, body())
            EditorDelivery(effect = Unit)
        }

        val result = withContext(Dispatchers.Default) { withTimeout(5_000) { resolved.await() } }
        assertTrue(checkNotNull(witness).provesNoEmission)
        assertEquals(EditorSubmissionState.Ready, binding.state)
        assertFalse(binding.isInitialized)
        assertEquals(StoreWriteResult.Written(1), result)
        assertEquals(0, server.requestCount)
        drafts.activeOwner = "alice"
        assertNull(drafts.entries["key"]?.attempt)
        assertEquals("body", drafts.entries["key"]?.draft?.body)
    }

    @Test
    fun logoutBetweenSnapshotAndEmissionIsRejected() = runTest {
        beforeCookieSelection = { cookies.clear() }
        server.enqueue(MockResponse().setBody("unexpected write"))

        assertTrue(runCatching { path.submit(client, body()) }.isFailure)

        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancelledAttemptCannotCrossALateNetworkBoundary() = runTest {
        val owner = EditorWriteOwner("alice")
        assertTrue(owner.cancelBeforeEmission())
        server.enqueue(MockResponse().setBody("unexpected write"))

        assertTrue(runCatching { withContext(owner) { path.submit(client, body()) } }.isFailure)

        assertTrue(owner.provesNoEmission)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun preparatoryGetNeverAdvancesThePostWitness() = runTest {
        val owner = EditorWriteOwner("alice")
        server.enqueue(MockResponse().setBody("unparsed preparation response"))

        withContext(owner) { client.getReplyForm(cat = 23, subcat = 401, post = 35421, page = 1) }

        assertTrue(owner.provesNoEmission)
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    private fun replaceAccount(owner: String) {
        cookies.saveFromResponse(server.url("/"), listOf(cookie(owner)))
        drafts.activeOwner = owner
    }

    private fun cookie(owner: String): Cookie = Cookie.Builder()
        .name("md_user").value(owner).domain(server.url("/").host).build()

    private fun body(): FormBody = FormBody.Builder().add("content_form", "body")
        .apply { if (path == WritePath.DELETE) add("delete", "1") }.build()

    enum class WritePath {
        REPLY, EDIT, FIRST_POST, NEW_TOPIC, MP_REPLY, MP_COMPOSE, QUICK_REPLY, DELETE;

        suspend fun submit(client: HfrClient, body: FormBody): String = when (this) {
            EDIT, FIRST_POST, DELETE -> client.submitEditPost(body)
            NEW_TOPIC -> client.submitNewTopic(body)
            REPLY, MP_REPLY, MP_COMPOSE, QUICK_REPLY -> client.submitReply(body)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun paths(): List<Array<WritePath>> = WritePath.entries.map { arrayOf(it) }
    }
}
