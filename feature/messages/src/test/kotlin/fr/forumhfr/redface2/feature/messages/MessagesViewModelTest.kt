package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.messages.PrivateMessageListPage
import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
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
class MessagesViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads the first inbox page on init`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery { repository.getPrivateMessageList(page = 1) } returns
            PrivateMessageListPage(page = 1, totalPages = 3, items = listOf(summary(10), summary(20)))

        val viewModel = MessagesViewModel(repository, FakeAuthRepository())

        val state = viewModel.state.value
        val mode = state.mode
        assertTrue(mode is MessagesUiState.Mode.Content)
        assertEquals(2, (mode as MessagesUiState.Mode.Content).conversations.size)
        assertEquals(1, state.page)
        assertEquals(3, state.totalPages)
        assertTrue(state.canGoNext)
    }

    @Test
    fun `anonymous state does not fetch the private inbox`() = runTest {
        val repository = mockk<MessagesRepository>()

        val viewModel = MessagesViewModel(repository, FakeAuthRepository(AuthState.Anonymous))

        assertEquals(MessagesUiState.Mode.RequiresLogin, viewModel.state.value.mode)
        coVerify(exactly = 0) {
            repository.getPrivateMessageList(page = any())
        }
    }

    @Test
    fun `surfaces a load failure as Error`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery { repository.getPrivateMessageList(page = 1) } throws IOException("offline")

        val viewModel = MessagesViewModel(repository, FakeAuthRepository())

        val mode = viewModel.state.value.mode
        assertTrue(mode is MessagesUiState.Mode.Error)
        assertEquals("offline", (mode as MessagesUiState.Mode.Error).message)
    }

    @Test
    fun `refresh reloads the current page and replaces the content on success`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery { repository.getPrivateMessageList(page = 1) } returnsMany listOf(
            PrivateMessageListPage(page = 1, totalPages = 1, items = listOf(summary(1))),
            PrivateMessageListPage(page = 1, totalPages = 1, items = listOf(summary(1), summary(2))),
        )

        val viewModel = MessagesViewModel(repository, FakeAuthRepository())
        viewModel.refresh()

        val state = viewModel.state.value
        assertEquals(2, (state.mode as MessagesUiState.Mode.Content).conversations.size)
        assertEquals(false, state.isRefreshing)
    }

    @Test
    fun `a failed refresh keeps the already-loaded content`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery { repository.getPrivateMessageList(page = 1) } returns
            PrivateMessageListPage(page = 1, totalPages = 1, items = listOf(summary(1))) andThenThrows
            IOException("offline")

        val viewModel = MessagesViewModel(repository, FakeAuthRepository())
        viewModel.refresh()

        val state = viewModel.state.value
        // A failed pull-to-refresh must not wipe the conversations already shown.
        val mode = state.mode
        assertTrue(mode is MessagesUiState.Mode.Content)
        assertEquals(1, (mode as MessagesUiState.Mode.Content).conversations.size)
        assertEquals(false, state.isRefreshing)
    }

    @Test
    fun `selectPage loads the requested page`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery { repository.getPrivateMessageList(page = 1) } returns
            PrivateMessageListPage(page = 1, totalPages = 2, items = listOf(summary(1)))
        coEvery { repository.getPrivateMessageList(page = 2) } returns
            PrivateMessageListPage(page = 2, totalPages = 2, items = listOf(summary(2), summary(3)))

        val viewModel = MessagesViewModel(repository, FakeAuthRepository())
        viewModel.selectPage(2)

        val state = viewModel.state.value
        assertEquals(2, state.page)
        assertEquals(2, (state.mode as MessagesUiState.Mode.Content).conversations.size)
        assertTrue(state.canGoPrevious)
    }

    @Test
    fun `logout clears private inbox content and login reloads it`() = runTest {
        val repository = mockk<MessagesRepository>()
        val authRepository = FakeAuthRepository()
        coEvery { repository.getPrivateMessageList(page = 1) } returns
            PrivateMessageListPage(page = 1, totalPages = 1, items = listOf(summary(1)))

        val viewModel = MessagesViewModel(repository, authRepository)
        assertTrue(viewModel.state.value.mode is MessagesUiState.Mode.Content)

        authRepository.emit(AuthState.Anonymous)
        advanceUntilIdle()
        assertEquals(MessagesUiState.Mode.RequiresLogin, viewModel.state.value.mode)

        authRepository.emit(AuthState.Authenticated("other"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.mode is MessagesUiState.Mode.Content)
    }

    private fun summary(threadId: Int, hasUnread: Boolean = false) = PrivateMessageSummary(
        threadId = threadId,
        correspondent = "Correspondant$threadId",
        subject = "Sujet $threadId",
        date = Instant.EPOCH,
        hasUnread = hasUnread,
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
