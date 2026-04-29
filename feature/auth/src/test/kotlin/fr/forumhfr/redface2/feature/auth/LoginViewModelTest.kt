package fr.forumhfr.redface2.feature.auth

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.LoginError
import fr.forumhfr.redface2.core.model.AuthState
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle with empty pseudo and password`() = runTest {
        val viewModel = LoginViewModel(authRepository = FakeAuthRepository())

        val state = viewModel.state.value
        assertEquals("", state.pseudo)
        assertEquals("", state.password)
        assertEquals(LoginUiState.Mode.Idle, state.mode)
    }

    @Test
    fun `UpdatePseudo and UpdatePassword update the state`() = runTest {
        val viewModel = LoginViewModel(authRepository = FakeAuthRepository())

        viewModel.send(LoginIntent.UpdatePseudo("xaat"))
        viewModel.send(LoginIntent.UpdatePassword("secret"))

        val state = viewModel.state.value
        assertEquals("xaat", state.pseudo)
        assertEquals("secret", state.password)
    }

    @Test
    fun `Submit with blank fields is a no-op`() = runTest {
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(authRepository = repo)

        viewModel.send(LoginIntent.Submit)
        viewModel.send(LoginIntent.UpdatePseudo("xaat"))
        viewModel.send(LoginIntent.Submit) // password still blank

        assertEquals(0, repo.loginCallCount)
        assertEquals(LoginUiState.Mode.Idle, viewModel.state.value.mode)
    }

    @Test
    fun `Submit transitions to Submitting then Authenticated on success`() = runTest {
        val repo = FakeAuthRepository(loginResult = Result.success(AuthState.Authenticated("xaat")))
        val viewModel = LoginViewModel(authRepository = repo)

        viewModel.state.test {
            assertEquals(LoginUiState.Mode.Idle, awaitItem().mode)

            viewModel.send(LoginIntent.UpdatePseudo("xaat"))
            assertEquals("xaat", awaitItem().pseudo)
            viewModel.send(LoginIntent.UpdatePassword("secret"))
            assertEquals("secret", awaitItem().password)

            viewModel.send(LoginIntent.Submit)
            assertEquals(LoginUiState.Mode.Submitting, awaitItem().mode)

            // Final emission resolves to Authenticated.
            val authenticated = awaitItem().mode
            assertTrue(authenticated is LoginUiState.Mode.Authenticated)
            assertEquals("xaat", (authenticated as LoginUiState.Mode.Authenticated).pseudo)

            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, repo.loginCallCount)
    }

    @Test
    fun `Submit maps LoginError InvalidCredentials to ErrorType InvalidCredentials`() = runTest {
        val repo = FakeAuthRepository(loginResult = Result.failure(LoginError.InvalidCredentials))
        val viewModel = LoginViewModel(authRepository = repo)

        viewModel.send(LoginIntent.UpdatePseudo("xaat"))
        viewModel.send(LoginIntent.UpdatePassword("wrong"))
        viewModel.send(LoginIntent.Submit)

        val mode = viewModel.state.value.mode
        assertTrue(mode is LoginUiState.Mode.Error)
        assertEquals(LoginUiState.ErrorType.InvalidCredentials, (mode as LoginUiState.Mode.Error).type)
    }

    @Test
    fun `Submit maps LoginError RateLimited to ErrorType RateLimited`() = runTest {
        val repo = FakeAuthRepository(loginResult = Result.failure(LoginError.RateLimited))
        val viewModel = LoginViewModel(authRepository = repo)

        viewModel.send(LoginIntent.UpdatePseudo("xaat"))
        viewModel.send(LoginIntent.UpdatePassword("secret"))
        viewModel.send(LoginIntent.Submit)

        val mode = viewModel.state.value.mode
        assertTrue(mode is LoginUiState.Mode.Error)
        assertEquals(LoginUiState.ErrorType.RateLimited, (mode as LoginUiState.Mode.Error).type)
    }

    @Test
    fun `Submit maps LoginError Network to ErrorType Network`() = runTest {
        val repo = FakeAuthRepository(loginResult = Result.failure(LoginError.Network(IOException("boom"))))
        val viewModel = LoginViewModel(authRepository = repo)

        viewModel.send(LoginIntent.UpdatePseudo("xaat"))
        viewModel.send(LoginIntent.UpdatePassword("secret"))
        viewModel.send(LoginIntent.Submit)

        val mode = viewModel.state.value.mode
        assertTrue(mode is LoginUiState.Mode.Error)
        assertEquals(LoginUiState.ErrorType.Network, (mode as LoginUiState.Mode.Error).type)
    }

    @Test
    fun `Submit maps LoginError Unknown to ErrorType Unknown and propagates the detail`() = runTest {
        val repo = FakeAuthRepository(
            loginResult = Result.failure(LoginError.Unknown("md_user mismatch (sameLength=true)")),
        )
        val viewModel = LoginViewModel(authRepository = repo)

        viewModel.send(LoginIntent.UpdatePseudo("xaat"))
        viewModel.send(LoginIntent.UpdatePassword("secret"))
        viewModel.send(LoginIntent.Submit)

        val mode = viewModel.state.value.mode as LoginUiState.Mode.Error
        assertEquals(LoginUiState.ErrorType.Unknown, mode.type)
        assertEquals("md_user mismatch (sameLength=true)", mode.detail)
    }

    @Test
    fun `DismissError clears error mode and preserves the form fields`() = runTest {
        val repo = FakeAuthRepository(loginResult = Result.failure(LoginError.InvalidCredentials))
        val viewModel = LoginViewModel(authRepository = repo)

        viewModel.send(LoginIntent.UpdatePseudo("xaat"))
        viewModel.send(LoginIntent.UpdatePassword("wrong"))
        viewModel.send(LoginIntent.Submit)

        assertTrue(viewModel.state.value.mode is LoginUiState.Mode.Error)

        viewModel.send(LoginIntent.DismissError)

        val state = viewModel.state.value
        assertEquals(LoginUiState.Mode.Idle, state.mode)
        assertEquals("xaat", state.pseudo)
        assertEquals("wrong", state.password)
    }

    @Test
    fun `editing pseudo after an error implicitly clears the error banner`() = runTest {
        val repo = FakeAuthRepository(loginResult = Result.failure(LoginError.InvalidCredentials))
        val viewModel = LoginViewModel(authRepository = repo)

        viewModel.send(LoginIntent.UpdatePseudo("xaat"))
        viewModel.send(LoginIntent.UpdatePassword("wrong"))
        viewModel.send(LoginIntent.Submit)

        assertTrue(viewModel.state.value.mode is LoginUiState.Mode.Error)

        viewModel.send(LoginIntent.UpdatePseudo("xaa"))

        assertEquals(LoginUiState.Mode.Idle, viewModel.state.value.mode)
    }

    @Test
    fun `Submit while already Submitting is debounced`() = runTest {
        val repo = FakeAuthRepository(loginResult = Result.success(AuthState.Authenticated("xaat")))
        repo.suspendUntilManuallyResolved = true
        val viewModel = LoginViewModel(authRepository = repo)

        viewModel.send(LoginIntent.UpdatePseudo("xaat"))
        viewModel.send(LoginIntent.UpdatePassword("secret"))
        viewModel.send(LoginIntent.Submit)
        viewModel.send(LoginIntent.Submit) // ignored — already Submitting

        assertEquals(1, repo.loginCallCount)
        assertFalse(viewModel.state.value.mode is LoginUiState.Mode.Authenticated)
    }
}

private class FakeAuthRepository(
    private val loginResult: Result<AuthState.Authenticated> = Result.success(AuthState.Authenticated("xaat")),
) : AuthRepository {

    var loginCallCount: Int = 0
        private set

    var suspendUntilManuallyResolved: Boolean = false

    private val authState = MutableStateFlow<AuthState>(AuthState.Anonymous)

    override fun observeAuthState(): Flow<AuthState> = authState.asStateFlow()

    override suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated> {
        loginCallCount += 1
        if (suspendUntilManuallyResolved) {
            // Simulate a long-running call so a follow-up Submit is observed mid-flight.
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { /* never resumes */ }
        }
        loginResult.onSuccess { authState.value = it }
        return loginResult
    }

    override suspend fun logout() {
        authState.value = AuthState.Anonymous
    }
}
