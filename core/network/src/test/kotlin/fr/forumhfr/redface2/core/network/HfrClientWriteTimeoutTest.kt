package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorAccountChangedBeforeSendException
import fr.forumhfr.redface2.core.domain.editor.EditorDelivery
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionCallbacks
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionEnvironment
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionErrors
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.EditorWriteOwner
import fr.forumhfr.redface2.core.domain.editor.SubmissionRequest
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.network.di.NetworkModule
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.AsyncTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real guard, RealCall timeout and binding: neither the refusal witness nor the exception is fabricated. */
class HfrClientWriteTimeoutTest {
    private val server = MockWebServer()
    private val timeout = CallTimeoutGate()
    private val drafts = VersionedEditorDraftStore()
    private var selectedOwner = "tester"
    private lateinit var mutation: OkHttpClient
    private lateinit var client: HfrClient
    private lateinit var binding: EditorSubmissionBinding<Unit, String>

    @Before
    fun setUp() {
        server.start()
        val cookies = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
                Cookie.Builder().name("md_user").value(selectedOwner).domain(url.host).build(),
            )
        }
        val base = OkHttpClient.Builder()
            .eventListener(timeout)
            .addNetworkInterceptor { chain ->
                try {
                    chain.proceed(chain.request())
                } catch (refusal: EditorAccountChangedBeforeSendException) {
                    // The production guard has refused. Hold its unwinding until the real timeout fires.
                    timeout.expireCall()
                    throw refusal
                }
            }.build()
        mutation = NetworkModule.provideMutationClient(base, cookies).newBuilder()
            // Bound a broken setup; rearm to 50 ms only after the tested boundary has been reached.
            .callTimeout(5, TimeUnit.SECONDS).build()
        client = HfrClient(mutation, mutation, mutation, server.url("/"), Dispatchers.IO)
    }

    @After
    fun tearDown() {
        mutation.connectionPool.evictAll()
        mutation.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    @Test
    fun `guard refusal wrapped by a real call timeout is certain without emitting a request`() = runTest {
        selectedOwner = "other"
        server.enqueue(MockResponse().setBody("unexpected write"))

        assertEquals("session", submit())

        assertTimeout(refused = true)
        assertEquals(0, server.requestCount)
        assertEquals(EditorSubmissionState.Ready, binding.state)
        assertFalse(binding.blocksMutation)
        assertNull(drafts.entries["key"]?.attempt)
        assertEquals("body", drafts.entries["key"]?.draft?.body)
    }

    @Test
    fun `real call timeout after the server receives the post stays uncertain`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = submit {
            val received = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            assertNotNull("the entire POST must reach the server before timeout", received)
            assertEquals("POST", received?.method)
            assertEquals("content_form=body", received?.body?.readUtf8())
            timeout.expireCall()
        }

        assertEquals("uncertain", result)
        assertTimeout(refused = false)
        assertEquals(1, server.requestCount)
        assertTrue(binding.state is EditorSubmissionState.Uncertain)
        assertTrue(binding.blocksMutation)
        assertEquals(AttemptStatus.UNCERTAIN, drafts.entries["key"]?.attempt?.status)
        assertEquals("body", drafts.entries["key"]?.draft?.body)
    }

    private suspend fun TestScope.submit(afterLaunch: suspend () -> Unit = {}): String {
        val settled = CompletableDeferred<String>()
        binding = EditorSubmissionBinding<Unit, String>(
            EditorSubmissionEnvironment(this, DiagnosticsClock { 0L }, DiagnosticsLog()),
            EditorDraftSession(drafts, "key"),
            EditorSubmissionCallbacks(
                state = {}, error = { it?.let(settled::complete) }, success = { settled.complete("success") },
            ),
            EditorSubmissionErrors("uncertain", "draft", "session", "unknown", "network", "after-entry"),
        )
        binding.restore("key")
        binding.setPreference(false)
        binding.launch(binding.request(4_000, true) as SubmissionRequest.Accepted, Draft("body")) {
            client.submitReply(FormBody.Builder().add("content_form", "body").build())
            EditorDelivery(effect = Unit)
        }
        afterLaunch()
        return settled.await()
    }

    private suspend fun assertTimeout(refused: Boolean) {
        val error = timeout.failure.await()
        assertTrue("RealCall must wrap the transport failure", error is InterruptedIOException)
        assertEquals("timeout", error.message)
        val owner = checkNotNull(timeout.call.request().tag(EditorWriteOwner::class.java))
        assertEquals(refused, owner.rejectedBeforeEmission)
        assertEquals(
            refused,
            generateSequence<Throwable>(error) { it.cause }.any { it is EditorAccountChangedBeforeSendException },
        )
    }

    private class CallTimeoutGate : EventListener() {
        @Volatile lateinit var call: Call
            private set
        private val canceled = CountDownLatch(1)
        val failure = CompletableDeferred<IOException>()

        override fun callStart(call: Call) { this.call = call }
        override fun canceled(call: Call) { canceled.countDown() }
        override fun callFailed(call: Call, ioe: IOException) { failure.complete(ioe) }

        fun expireCall() {
            // RealCall exposes its AsyncTimeout through Call.timeout(). Use its actual watchdog;
            // starting here guarantees refusal/receipt precedes expiration, without sleeps or Call.cancel().
            val timer = call.timeout() as AsyncTimeout
            check(!timer.exit())
            timer.timeout(50, TimeUnit.MILLISECONDS)
            timer.enter()
            assertTrue("callTimeout must cancel the call", canceled.await(5, TimeUnit.SECONDS))
        }
    }
}
