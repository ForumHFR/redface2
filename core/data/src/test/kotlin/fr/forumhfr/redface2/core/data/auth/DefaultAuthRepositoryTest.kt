package fr.forumhfr.redface2.core.data.auth

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.LoginError
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.network.auth.AuthRemoteDataSource
import fr.forumhfr.redface2.core.network.cookie.CookieStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAuthRepositoryTest {

    @Test
    fun `observeAuthState emits Anonymous when no cookies are persisted`() = runTest {
        val store = FakeCookieStore(initial = emptyList())
        val repo = DefaultAuthRepository(
            remote = mockk(),
            cookieStore = store,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        repo.observeAuthState().test {
            assertEquals(AuthState.Anonymous, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeAuthState emits Authenticated when md_user cookie is present`() = runTest {
        val store = FakeCookieStore(initial = listOf(cookie("md_user", "xaat")))
        val repo = DefaultAuthRepository(
            remote = mockk(),
            cookieStore = store,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        repo.observeAuthState().test {
            assertEquals(AuthState.Authenticated("xaat"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeAuthState emits Anonymous when md_user value is blank`() = runTest {
        // Defensive: an md_user cookie with empty value can show up during a deletion-marker
        // Set-Cookie before the jar's merge runs. We don't want to flap to a phantom session.
        val store = FakeCookieStore(initial = listOf(cookie("md_user", "")))
        val repo = DefaultAuthRepository(
            remote = mockk(),
            cookieStore = store,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        repo.observeAuthState().test {
            assertEquals(AuthState.Anonymous, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeAuthState transitions Anonymous to Authenticated when cookies are saved`() = runTest {
        val store = FakeCookieStore(initial = emptyList())
        val repo = DefaultAuthRepository(
            remote = mockk(),
            cookieStore = store,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        repo.observeAuthState().test {
            assertEquals(AuthState.Anonymous, awaitItem())

            store.save(listOf(cookie("md_user", "xaat")))
            assertEquals(AuthState.Authenticated("xaat"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `login delegates to remote and returns its Result`() = runTest {
        val remote = mockk<AuthRemoteDataSource>()
        coEvery { remote.login("xaat", "secret") } returns Result.success(AuthState.Authenticated("xaat"))
        val repo = DefaultAuthRepository(
            remote = remote,
            cookieStore = FakeCookieStore(initial = emptyList()),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val result = repo.login("xaat", "secret")

        assertEquals(AuthState.Authenticated("xaat"), result.getOrNull())
        coVerify(exactly = 1) { remote.login("xaat", "secret") }
    }

    @Test
    fun `login propagates LoginError from remote`() = runTest {
        val remote = mockk<AuthRemoteDataSource>()
        coEvery { remote.login(any(), any()) } returns Result.failure(LoginError.InvalidCredentials)
        val repo = DefaultAuthRepository(
            remote = remote,
            cookieStore = FakeCookieStore(initial = emptyList()),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val result = repo.login("xaat", "wrong")

        assertTrue(result.exceptionOrNull() is LoginError.InvalidCredentials)
    }

    @Test
    fun `logout clears the cookie store`() = runTest {
        val store = FakeCookieStore(initial = listOf(cookie("md_user", "xaat")))
        val repo = DefaultAuthRepository(
            remote = mockk(),
            cookieStore = store,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        repo.logout()

        store.observe().test {
            assertEquals(emptyList<Cookie>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun cookie(
        name: String,
        value: String,
        domain: String = "forum.hardware.fr",
        path: String = "/",
        expiresAt: Long = System.currentTimeMillis() + 365L * 24 * 3600 * 1000,
    ): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain(domain)
        .path(path)
        .expiresAt(expiresAt)
        .secure()
        .build()

    private class FakeCookieStore(initial: List<Cookie>) : CookieStore {
        private val state = MutableStateFlow(initial)

        override fun observe(): Flow<List<Cookie>> = state.asStateFlow()

        override suspend fun save(cookies: List<Cookie>) {
            state.value = cookies
        }

        override suspend fun clear() {
            state.value = emptyList()
        }
    }
}
