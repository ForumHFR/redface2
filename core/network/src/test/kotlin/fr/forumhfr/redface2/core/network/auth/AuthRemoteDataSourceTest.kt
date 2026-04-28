package fr.forumhfr.redface2.core.network.auth

import fr.forumhfr.redface2.core.domain.auth.LoginError
import fr.forumhfr.redface2.core.model.AuthState
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthRemoteDataSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var dataSource: AuthRemoteDataSource

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder().build()
        dataSource = AuthRemoteDataSource(client = client, baseUrl = server.url("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `successful login returns Authenticated when md_user cookie is set`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "md_user=xaat; Path=/")
                .addHeader("Set-Cookie", "md_pass=deadbeef; Path=/; HttpOnly")
                .setBody("<html><body>Bienvenue xaat</body></html>"),
        )

        val result = dataSource.login("xaat", "secret")

        assertEquals(AuthState.Authenticated("xaat"), result.getOrNull())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.startsWith("/login_validation.php"))
        assertTrue(recorded.path!!.contains("config=hfr.inc"))
        val body = recorded.body.readUtf8()
        assertTrue("body should carry pseudo", body.contains("pseudo=xaat"))
        assertTrue("body should carry password", body.contains("password=secret"))
    }

    @Test
    fun `invalid credentials returns LoginError InvalidCredentials`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<html><body>Votre mot de passe ou nom d'utilisateur n'est pas valide</body></html>"),
        )

        val result = dataSource.login("xaat", "wrong")

        assertEquals(LoginError.InvalidCredentials, result.exceptionOrNull())
    }

    @Test
    fun `rate-limited response returns LoginError RateLimited`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<html><body>Afin de prévenir les tentatives de flood, veuillez patienter</body></html>"),
        )

        val result = dataSource.login("xaat", "secret")

        assertEquals(LoginError.RateLimited, result.exceptionOrNull())
    }

    @Test
    fun `success page without md_user cookie returns LoginError Unknown`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<html><body>Bienvenue</body></html>"),
        )

        val result = dataSource.login("xaat", "secret")

        val error = result.exceptionOrNull()
        assertTrue("expected Unknown but was ${error?.javaClass?.simpleName}", error is LoginError.Unknown)
        assertEquals("expected md_user cookie not set", (error as LoginError.Unknown).detail)
    }

    @Test
    fun `md_user with mismatching pseudo returns LoginError Unknown`() = runTest {
        // Defensive: HFR shouldn't set md_user with a different pseudo, but if it does we
        // refuse to claim the wrong identity.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "md_user=someone_else; Path=/")
                .setBody("<html><body>OK</body></html>"),
        )

        val result = dataSource.login("xaat", "secret")

        val error = result.exceptionOrNull()
        assertTrue("expected Unknown but was ${error?.javaClass?.simpleName}", error is LoginError.Unknown)
        assertEquals(
            "md_user cookie does not match requested pseudo",
            (error as LoginError.Unknown).detail,
        )
    }

    @Test
    fun `network failure returns LoginError Network with the IOException attached`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val result = dataSource.login("xaat", "secret")

        val error = result.exceptionOrNull()
        assertTrue("expected Network but was ${error?.javaClass?.simpleName}", error is LoginError.Network)
    }
}
