package fr.forumhfr.redface2.core.network

import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.network.di.NetworkModule
import java.net.InetAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Connection
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * ED-160/168 — politique effective de HfrClient, sans fabriquer de page HTML HFR.
 * Les six chemins sont explicites même lorsqu'ils partagent submitReply ; le premier post
 * est une variante supplémentaire du sujet. Ceci ne remplace pas les tests des vrais repositories.
 */
@RunWith(Parameterized::class)
class HfrClientWriteReplayTest(private val path: WritePath) {
    private val server = MockWebServer()
    private val connections = CopyOnWriteArrayList<Connection>()
    private lateinit var mutation: OkHttpClient
    private lateinit var client: HfrClient
    private lateinit var baseUrl: HttpUrl

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .commonName("warm.test")
            .addSubjectAlternativeName("warm.test")
            .addSubjectAlternativeName("write.test")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        baseUrl = server.url("/").newBuilder().host("write.test").build()
        val base = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName("127.0.0.1"))
            })
            .eventListener(object : EventListener() {
                override fun connectionAcquired(call: Call, connection: Connection) {
                    connections += connection
                }
            })
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        // La même configuration que l'injection réelle, pas de verrou ajouté par le test.
        mutation = NetworkModule.provideMutationClient(base, CookieJar.NO_COOKIES)
        client = hfrClient(mutation)
    }

    @After
    fun tearDown() {
        mutation.connectionPool.evictAll()
        mutation.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    /** ED-168: even redirects that would turn POST into GET remain ambiguous responses. */
    @Test
    fun redirects301302303AreNotFollowed() = runTest {
        for (code in listOf(301, 302, 303)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", "/login.php"))
            val baseline = server.requestCount
            val error = runCatching { path.submit(client, formBody()) }.exceptionOrNull()
            assertTrue("$code remains a non-2xx write outcome, including a login Location", error is HfrServerException)
            assertEquals(code, (error as HfrServerException).code)
            assertEquals(1, server.requestCount - baseline)
            assertEquals("POST", server.takeRequest().method)
        }
    }

    /** ED-168 — rouge à e5226a65 : 307 conserve le POST et son corps rejouable. */
    @Test
    fun redirect307DoesNotReplayPost() = runTest {
        assertSingleWrite(MockResponse().setResponseCode(307).setHeader("Location", "/redirected"))
    }

    /** ED-168 — rouge à e5226a65 : 308 conserve aussi le POST. */
    @Test
    fun redirect308DoesNotReplayPost() = runTest {
        assertSingleWrite(MockResponse().setResponseCode(308).setHeader("Location", "/redirected"))
    }

    /** ED-168 — rouge à e5226a65 : retryOnConnectionFailure(false) ne suffit pas pour 503. */
    @Test
    fun retryAfterZeroDoesNotReplayPost() = runTest {
        assertSingleWrite(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
    }

    /**
     * ED-168 — rouge à e5226a65. Deux noms TLS, même IP/port, préchauffage HTTP/2, puis écriture
     * sur l'autre nom. La preuve d'identité de Connection précède le comptage du delta :
     * un 421 sur une connexion HTTP/1 ou une connexion dédiée ne ferait pas foi.
     */
    @Test
    fun coalesced421DoesNotReplayPost() = runTest {
        server.enqueue(MockResponse().setBody("warmup"))
        val warmUrl = baseUrl.newBuilder().host("warm.test").build()
        mutation.newCall(Request.Builder().url(warmUrl).build()).execute().use {
            assertEquals(Protocol.HTTP_2, it.protocol)
        }
        server.takeRequest()
        assertEquals(1, connections.size)
        server.enqueue(MockResponse().setResponseCode(421))
        enqueueFallback()
        val baseline = server.requestCount

        val result = runCatching { path.submit(client, formBody()) }

        assertTrue("le POST doit avoir acquis une connexion", connections.size >= 2)
        assertSame("le POST a réellement coalescé la connexion du premier nom", connections[0], connections[1])
        assertEquals("warm.test", connections[1].route().address.url.host)
        assertEquals("un seul POST hors préchauffage", 1, server.requestCount - baseline)
        assertTrue("421 doit remonter sans réponse de secours", result.exceptionOrNull() is HfrServerException)
    }

    /** ED-168 — vert : la mutation ne relance déjà pas une rupture après réception de la requête. */
    @Test
    fun disconnectAfterRequestDoesNotReplayPost() = runTest {
        // SocketPolicy ferme ici une requête HTTP/1 complète ; le cas HTTP/2 coalescé est distinct.
        client = hfrClient(mutation.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        enqueueFallback()
        val baseline = server.requestCount

        val result = runCatching { path.submit(client, formBody()) }

        assertEquals(1, server.requestCount - baseline)
        assertTrue("la rupture doit être signalée au repository", result.isFailure)
    }

    private suspend fun assertSingleWrite(response: MockResponse) {
        server.enqueue(response)
        enqueueFallback()
        val baseline = server.requestCount
        val body = formBody()

        val result = runCatching { path.submit(client, body) }

        assertEquals("$path : delta de requêtes reçues", 1, server.requestCount - baseline)
        assertTrue("le statut doit remonter sans suivre ni rejouer", result.exceptionOrNull() is HfrServerException)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(path.endpoint, recorded.requestUrl?.encodedPath)
        assertTrue(URLDecoder.decode(recorded.body.readUtf8(), "UTF-8").contains("content_form=contract body"))
        assertEquals("contract body", body.value(0)) // FormBody reste lisible par le comptage de smileys.
    }

    private fun enqueueFallback() {
        // Réponse disponible en cas de rejeu : le test échoue sur le delta, sans attendre un timeout.
        server.enqueue(MockResponse().setBody("unexpected replay"))
    }

    private fun hfrClient(writeClient: OkHttpClient): HfrClient = HfrClient(
        authenticated = writeClient,
        anonymous = writeClient,
        mutation = writeClient,
        baseUrl = baseUrl,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun formBody(): FormBody = FormBody.Builder()
        .add("content_form", "contract body")
        .add("hash_check", "test-token")
        .add("cat", if (path == WritePath.MP_REPLY || path == WritePath.MP_COMPOSE) "prive" else "23")
        .build()

    enum class WritePath(val endpoint: String) {
        REPLY("/bddpost.php"),
        EDIT("/bdd.php"),
        NEW_TOPIC("/bddpost.php"),
        FIRST_POST("/bdd.php"),
        MP_REPLY("/bddpost.php"),
        MP_COMPOSE("/bddpost.php"),
        QUICK_REPLY("/bddpost.php");

        suspend fun submit(client: HfrClient, body: FormBody): String = when (this) {
            EDIT, FIRST_POST -> client.submitEditPost(body)
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
