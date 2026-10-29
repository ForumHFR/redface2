package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.network.diagnostics.HfrEventListenerFactory
import fr.forumhfr.redface2.core.network.diagnostics.HfrNetworkDiagnostics
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HfrFavoriteSmileyClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HfrFavoriteSmileyClient
    private val diagnostics = DiagnosticsLog()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        // Deliberately leave retries and redirects enabled on the injected clients: the sensitive
        // path must enforce its own policy without changing other HfrClient operations.
        val base = OkHttpClient.Builder()
            .eventListenerFactory(HfrEventListenerFactory(HfrNetworkDiagnostics(diagnostics)))
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
        client = HfrFavoriteSmileyClient(
            authenticated = base.newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Client", "authenticated").build())
            }.build(),
            mutation = base.newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Client", "mutation").build())
            }.build(),
            baseUrl = server.url("/"),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `working form is the authenticated supprperso GET`() = runTest {
        server.enqueue(MockResponse().setBody("form"))
        assertEquals("form", client.getFavoriteSmileyForm())
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/user/supprperso.php?config=hfr.inc", request.path)
        assertEquals("authenticated", request.getHeader("X-Client"))
        assertEquals("no-store", request.getHeader("Cache-Control"))
    }

    @Test
    fun `add encodes the complete token and hash on the mutation client`() = runTest {
        server.enqueue(MockResponse().setBody("ok"))
        client.addFavoriteSmiley(HASH_CHECK, "[:l'été & ami:2]")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/user/addperso.php?config=hfr.inc", request.path)
        assertEquals("mutation", request.getHeader("X-Client"))
        assertTrue(requireNotNull(request.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded"))
        assertEquals(
            "hash_check=$HASH_CHECK&smilie=%5B%3Al%27%C3%A9t%C3%A9+%26+ami%3A2%5D",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `remove posts every row verbatim in order with a single checkbox`() = runTest {
        server.enqueue(MockResponse().setBody("ok"))
        client.removeFavoriteSmileys(HASH_CHECK, CODEHEX, listOf("[:un]", "[:nom espacé:2]", "aaa"), 1)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/user/supprperso_validation.php?config=hfr.inc&codehex=$CODEHEX", request.path)
        assertEquals("mutation", request.getHeader("X-Client"))
        assertEquals(
            "hash_check=$HASH_CHECK&smiley0=%5B%3Aun%5D&smiley1=%5B%3Anom+espac%C3%A9%3A2%5D" +
                "&delete1=on&smiley2=aaa",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `neither POST follows a redirect or exposes its target in an exception`() = runTest {
        listOf(false, true).forEach { removal ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/elsewhere?codehex=$CODEHEX"))
            assertSafeFailure(runCatching { mutate(removal) }.exceptionOrNull())
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `login redirect and inline login report session expiry without the sensitive URL`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login.php?codehex=$CODEHEX"))
        server.enqueue(MockResponse().setBody(LOGIN))
        repeat(2) {
            val error = runCatching { mutate(removal = true) }.exceptionOrNull()
            assertTrue(error is SessionExpiredException)
            assertSafeFailure(error)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `HTTP and body failures do not leak the URL query secrets or original cause`() = runTest {
        listOf(false, true).forEach { removal ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("codehex=$CODEHEX hash_check=$HASH_CHECK"))
            assertSafeFailure(runCatching { mutate(removal) }.exceptionOrNull())
            server.enqueue(
                MockResponse().setBody("x".repeat(4096))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
            assertSafeFailure(runCatching { mutate(removal) }.exceptionOrNull())
        }
        assertEquals(4, server.requestCount)
        assertSafeDiagnostics()
    }

    @Test
    fun `neither POST is replayed after the server receives it and disconnects`() = runTest {
        listOf(false, true).forEach { removal ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertSafeFailure(runCatching { mutate(removal) }.exceptionOrNull())
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `HTTP retry followups cannot replay either POST`() = runTest {
        listOf(false, true).forEach { removal ->
            server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
            assertSafeFailure(runCatching { mutate(removal) }.exceptionOrNull())
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `exceptions carrying credentials lose their message cause and suppressed errors before escaping`() = runTest {
        // An application interceptor terminates every call: no external network access. Use the
        // production origin so the diagnostic classifier would otherwise observe these requests.
        val base = OkHttpClient.Builder()
            .eventListenerFactory(HfrEventListenerFactory(HfrNetworkDiagnostics(diagnostics)))
            .addInterceptor { chain ->
                throw IOException("${chain.request().url} $HASH_CHECK", IllegalStateException(CODEHEX)).apply {
                    addSuppressed(IOException(CODEHEX))
                }
            }.build()
        val sensitive = HfrFavoriteSmileyClient(
            base, base, "https://forum.hardware.fr/".toHttpUrl(), Dispatchers.Unconfined,
        )
        assertSafeFailure(runCatching {
            sensitive.removeFavoriteSmileys(HASH_CHECK, CODEHEX, listOf("[:un]"), 0)
        }.exceptionOrNull())
        assertSafeFailure(runCatching { sensitive.addFavoriteSmiley(HASH_CHECK, "[:un]") }.exceptionOrNull())
        assertSafeFailure(runCatching { sensitive.getFavoriteSmileyForm() }.exceptionOrNull())
        // Only the repository publishes operation + result, no generic network diagnostic here.
        assertTrue(diagnostics.entries.value.isEmpty())
    }

    @Test
    fun `invalid codehex and deletion index are rejected locally without echoing input`() = runTest {
        val invalid = runCatching {
            client.removeFavoriteSmileys(HASH_CHECK, "not-hex-$CODEHEX", listOf("[:un]"), 0)
        }.exceptionOrNull()
        assertTrue(invalid is IllegalArgumentException)
        assertFalse(invalid.toString().contains(CODEHEX))
        assertTrue(runCatching {
            client.removeFavoriteSmileys(HASH_CHECK, CODEHEX, listOf("[:un]"), 1)
        }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    private suspend fun mutate(removal: Boolean) {
        if (removal) client.removeFavoriteSmileys(HASH_CHECK, CODEHEX, listOf("[:un]"), 0)
        else client.addFavoriteSmiley(HASH_CHECK, "[:un]")
    }

    private fun assertSafeFailure(error: Throwable?) {
        assertTrue("expected a sanitized IO failure", error is IOException)
        requireNotNull(error)
        listOf(CODEHEX, HASH_CHECK, "http://", "codehex=", "config=", "supprperso_validation.php").forEach { secret ->
            assertFalse(error.toString().contains(secret))
            assertFalse(error.message.orEmpty().contains(secret))
        }
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
        assertSafeDiagnostics()
    }

    private fun assertSafeDiagnostics() {
        val entries = diagnostics.entries.value.toString()
        assertFalse(entries.contains(CODEHEX))
        assertFalse(entries.contains(HASH_CHECK))
        assertFalse(entries.contains("codehex="))
    }

    private companion object {
        const val CODEHEX = "fedcba9876543210fedcba9876543210"
        const val HASH_CHECK = "0123456789abcdef0123456789abcdef"
        const val LOGIN = """<form action="login_validation.php"><input name="pseudo"><input name="password"></form>"""
    }
}
