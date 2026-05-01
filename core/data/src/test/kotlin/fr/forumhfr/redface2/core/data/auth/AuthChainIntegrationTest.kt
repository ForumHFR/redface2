package fr.forumhfr.redface2.core.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.network.auth.AuthRemoteDataSource
import fr.forumhfr.redface2.core.network.cookie.PersistentCookieJar
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end integration test that exercises the full auth chain without mocking the cookie
 * persistence path:
 *
 *     MockWebServer (HFR stand-in)
 *       ↑ HTTP
 *     OkHttpClient (CookieJar = real PersistentCookieJar)
 *       ↑
 *     AuthRemoteDataSource
 *       ↑
 *     DefaultAuthRepository (observeAuthState reads cookieJar.state, not the store)
 *       ↑
 *     real DataStoreCookieStore (TemporaryFolder file, no Robolectric Keystore mock needed)
 *
 * This is the test that would have surfaced the runBlocking-at-cold-start + observe-via-DataStore
 * race that PR #91 review (Codex + superpowers) flagged. Keeping it here guards against the
 * exact regression — any future refactor that splits the runtime cache from the auth state
 * derivation will fail one of the assertions below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AuthChainIntegrationTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var cookieStore: DataStoreCookieStore
    private lateinit var cookieJar: PersistentCookieJar
    private lateinit var okHttp: OkHttpClient
    private lateinit var dataSource: AuthRemoteDataSource
    private lateinit var repository: DefaultAuthRepository

    private lateinit var baseUrl: HttpUrl
    private val cookieJars = mutableListOf<PersistentCookieJar>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        baseUrl = server.url("/")
        wireChain()
    }

    @After
    fun tearDown() {
        cookieJars.forEach { it.close() }
        cookieJars.clear()
        server.shutdown()
    }

    /** Builds the full chain with a fresh DataStore file so each test boots a clean session. */
    private fun wireChain() {
        val file = File(tempFolder.newFolder(), "cookies.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        cookieStore = DataStoreCookieStore(dataStore)
        // UnconfinedTestDispatcher drains the cookie jar's init-block collector synchronously,
        // so by the time wireChain() returns, the jar's StateFlow already mirrors the
        // persisted store contents (or null if the file is empty).
        cookieJar = newCookieJar()
        okHttp = OkHttpClient.Builder().cookieJar(cookieJar).build()
        dataSource = AuthRemoteDataSource(
            client = okHttp,
            baseUrl = baseUrl,
            diagnostics = DiagnosticsLog(),
        )
        repository = DefaultAuthRepository(
            remote = dataSource,
            cookieJar = cookieJar,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun newCookieJar(): PersistentCookieJar =
        PersistentCookieJar(cookieStore, UnconfinedTestDispatcher()).also(cookieJars::add)

    @Test
    fun `successful login propagates through the chain to AuthState Authenticated`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "md_user=xaat; Path=/")
                .addHeader("Set-Cookie", "md_pass=deadbeef; Path=/; HttpOnly")
                .setBody("<html><body>Bienvenue xaat</body></html>"),
        )

        repository.observeAuthState().test {
            // Cold start with no persisted session: first emission is Anonymous.
            assertEquals(AuthState.Anonymous, awaitItem())

            val result = repository.login("xaat", "secret")
            assertEquals(AuthState.Authenticated("xaat"), result.getOrNull())

            // The next AuthState emission should reflect the cookie that OkHttp's CookieJar
            // contract just delivered to PersistentCookieJar.saveFromResponse — synchronously
            // through cookieJar.state, NOT after a DataStore round-trip.
            assertEquals(AuthState.Authenticated("xaat"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cold start with persisted cookies emits Authenticated first, never a transient Anonymous`() = runTest {
        // Step 1 — persist cookies via the store, then tear down the chain to simulate an
        // app process death.
        cookieStore.save(
            listOf(
                makeCookie("md_user", "xaat", baseUrl.host),
            ),
        )

        // Step 2 — rebuild PersistentCookieJar + DefaultAuthRepository against the same
        // DataStore file (the "fresh boot" of a returning user).
        val coldStartJar = newCookieJar()
        val coldStartRepo = DefaultAuthRepository(
            remote = dataSource,
            cookieJar = coldStartJar,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        coldStartRepo.observeAuthState().test {
            // Critical: with the runtime-cache wiring + filterNotNull guard, the very first
            // emission is the persisted session. A flicker through Anonymous would mean the
            // user briefly saw "Se connecter à HFR" before the footer flipped — exactly the
            // UX defect the review flagged.
            assertEquals(AuthState.Authenticated("xaat"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun makeCookie(
        name: String,
        value: String,
        domain: String,
    ): okhttp3.Cookie = okhttp3.Cookie.Builder()
        .name(name)
        .value(value)
        .domain(domain)
        .path("/")
        .expiresAt(System.currentTimeMillis() + 365L * 24 * 3600 * 1000)
        .build()

    @Test
    fun `logout flips the chain back to Anonymous synchronously`() = runTest {
        // Pre-state: a logged-in session.
        cookieStore.save(
            listOf(
                makeCookie("md_user", "xaat", baseUrl.host),
            ),
        )
        val warmJar = newCookieJar()
        val warmRepo = DefaultAuthRepository(
            remote = dataSource,
            cookieJar = warmJar,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        warmRepo.observeAuthState().test {
            assertEquals(AuthState.Authenticated("xaat"), awaitItem())

            warmRepo.logout()

            // Synchronous: cookieJar.clear() updated the runtime cache before logout()
            // returned, so the next emission is Anonymous.
            assertEquals(AuthState.Anonymous, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
