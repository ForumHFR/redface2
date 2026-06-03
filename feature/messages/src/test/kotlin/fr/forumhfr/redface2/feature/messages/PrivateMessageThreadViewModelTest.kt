package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateMessageThreadViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val request = PrivateMessageThreadRequest(
        threadId = 42,
        correspondent = "Correspondant",
        subject = "Sujet",
        page = 1,
    )

    @Test
    fun `loads the thread on init and forwards the inbox correspondent as fallback`() = runTest {
        val repository = mockk<MessagesRepository>()
        val thread = thread(page = 1, totalPages = 1)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = "Correspondant")
        } returns thread

        val viewModel = PrivateMessageThreadViewModel(request, repository, FakeAuthRepository())

        val state = viewModel.state.value
        assertTrue(state.mode is PrivateMessageThreadUiState.Mode.Content)
        assertEquals(thread, (state.mode as PrivateMessageThreadUiState.Mode.Content).thread)
        // The inbox-row correspondent must reach the repository (parser fallback contract).
        coVerify(exactly = 1) {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = "Correspondant")
        }
    }

    @Test
    fun `anonymous state does not fetch the private thread`() = runTest {
        val repository = mockk<MessagesRepository>()

        val viewModel = PrivateMessageThreadViewModel(
            request = request,
            repository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
        )

        assertEquals(PrivateMessageThreadUiState.Mode.RequiresLogin, viewModel.state.value.mode)
        coVerify(exactly = 0) {
            repository.getPrivateMessageThread(any(), any(), any())
        }
    }

    @Test
    fun `surfaces a load failure as Error`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = "Correspondant")
        } throws IOException("offline")

        val viewModel = PrivateMessageThreadViewModel(request, repository, FakeAuthRepository())

        val mode = viewModel.state.value.mode
        assertTrue(mode is PrivateMessageThreadUiState.Mode.Error)
        assertEquals("offline", (mode as PrivateMessageThreadUiState.Mode.Error).message)
    }

    @Test
    fun `selectPage loads the requested page`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = "Correspondant")
        } returns thread(page = 1, totalPages = 2)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = "Correspondant")
        } returns thread(page = 2, totalPages = 2)

        val viewModel = PrivateMessageThreadViewModel(request, repository, FakeAuthRepository())
        viewModel.selectPage(2)

        val state = viewModel.state.value
        assertEquals(2, state.page)
        assertTrue(state.canGoPrevious)
    }

    @Test
    fun `logout clears private thread content and login reloads it`() = runTest {
        val repository = mockk<MessagesRepository>()
        val authRepository = FakeAuthRepository()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = "Correspondant")
        } returns thread(page = 1, totalPages = 1)

        val viewModel = PrivateMessageThreadViewModel(request, repository, authRepository)
        assertTrue(viewModel.state.value.mode is PrivateMessageThreadUiState.Mode.Content)

        authRepository.emit(AuthState.Anonymous)
        advanceUntilIdle()
        assertEquals(PrivateMessageThreadUiState.Mode.RequiresLogin, viewModel.state.value.mode)

        authRepository.emit(AuthState.Authenticated("other"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.mode is PrivateMessageThreadUiState.Mode.Content)
    }

    private fun thread(page: Int, totalPages: Int) = PrivateMessageThread(
        threadId = 42,
        subject = "Sujet",
        correspondent = "Correspondant",
        messages = emptyList(),
        page = page,
        totalPages = totalPages,
        canReply = true,
    )

    private class FakeAuthRepository(
        initial: AuthState = AuthState.Authenticated("xaat"),
    ) : AuthRepository {
        private val state = MutableStateFlow(initial)

        override fun observeAuthState(): Flow<AuthState> = state.asStateFlow()

        override suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated> =
            Result.failure(IllegalStateException("not used"))

        override suspend fun logout() {
            state.value = AuthState.Anonymous
        }

        suspend fun emit(authState: AuthState) {
            state.emit(authState)
        }
    }
}
