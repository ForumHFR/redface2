package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageReadPositionSeeder
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageSeedOutcome
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.messages.PrivateMessageListPage
import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
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

        val viewModel = viewModel(repository, FakeAuthRepository())

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

        val viewModel = viewModel(repository, FakeAuthRepository(AuthState.Anonymous))

        assertEquals(MessagesUiState.Mode.RequiresLogin, viewModel.state.value.mode)
        coVerify(exactly = 0) {
            repository.getPrivateMessageList(page = any())
        }
    }

    @Test
    fun `surfaces a load failure as Error`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery { repository.getPrivateMessageList(page = 1) } throws IOException("offline")

        val viewModel = viewModel(repository, FakeAuthRepository())

        // #316: the Error mode carries NO raw throwable message (privacy — it can embed the private
        // conversation URL). The only detail is the #324 type-derived kind (safe closed enum).
        val mode = viewModel.state.value.mode
        assertTrue(mode is MessagesUiState.Mode.Error)
        assertEquals(HfrErrorKind.Network, (mode as MessagesUiState.Mode.Error).kind)
    }

    @Test
    fun `surfaces an HFR 5xx load failure with the ServerDown kind`() = runTest {
        // #324 — an HFR outage must be distinguishable from a network cut on the inbox,
        // still without any raw message (the kind is derived from the exception TYPE only).
        val repository = mockk<MessagesRepository>()
        coEvery { repository.getPrivateMessageList(page = 1) } throws
            HfrServerException(code = 503, url = "https://forum.hardware.fr/forum1.php")

        val viewModel = viewModel(repository, FakeAuthRepository())

        val mode = viewModel.state.value.mode
        assertTrue(mode is MessagesUiState.Mode.Error)
        assertEquals(HfrErrorKind.ServerDown, (mode as MessagesUiState.Mode.Error).kind)
    }

    @Test
    fun `refresh reloads the current page and replaces the content on success`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery { repository.getPrivateMessageList(page = 1) } returnsMany listOf(
            PrivateMessageListPage(page = 1, totalPages = 1, items = listOf(summary(1))),
            PrivateMessageListPage(page = 1, totalPages = 1, items = listOf(summary(1), summary(2))),
        )

        val viewModel = viewModel(repository, FakeAuthRepository())
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

        val viewModel = viewModel(repository, FakeAuthRepository())
        viewModel.refresh()

        val state = viewModel.state.value
        // A failed pull-to-refresh must not wipe the conversations already shown.
        val mode = state.mode
        assertTrue(mode is MessagesUiState.Mode.Content)
        assertEquals(1, (mode as MessagesUiState.Mode.Content).conversations.size)
        assertEquals(false, state.isRefreshing)
    }

    @Test
    fun `networkLoadGeneration bumps on every successful network load`() = runTest {
        // #531 — the inbox has no cache layer, so every successful Content is a fresh network result.
        // The generation counter (which the screen keys its read-mark reconciliation on) must advance
        // once per fetch: init (1), then refresh (2). It never advances on a failed load.
        val repository = mockk<MessagesRepository>()
        coEvery { repository.getPrivateMessageList(page = 1) } returns
            PrivateMessageListPage(page = 1, totalPages = 1, items = listOf(summary(1)))

        val viewModel = viewModel(repository, FakeAuthRepository())
        assertEquals(1, viewModel.state.value.networkLoadGeneration)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.networkLoadGeneration)
    }

    @Test
    fun `networkLoadGeneration does not advance on a failed refresh`() = runTest {
        // #531 — a failed pull-to-refresh keeps the existing content and must NOT bump the generation
        // (no fresh server data to reconcile against — reconciling on stale content could wrongly drop
        // marks). The successful init load is generation 1; the failed refresh leaves it at 1.
        val repository = mockk<MessagesRepository>()
        coEvery { repository.getPrivateMessageList(page = 1) } returns
            PrivateMessageListPage(page = 1, totalPages = 1, items = listOf(summary(1))) andThenThrows
            IOException("offline")

        val viewModel = viewModel(repository, FakeAuthRepository())
        assertEquals(1, viewModel.state.value.networkLoadGeneration)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.networkLoadGeneration)
    }

    @Test
    fun `selectPage loads the requested page`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery { repository.getPrivateMessageList(page = 1) } returns
            PrivateMessageListPage(page = 1, totalPages = 2, items = listOf(summary(1)))
        coEvery { repository.getPrivateMessageList(page = 2) } returns
            PrivateMessageListPage(page = 2, totalPages = 2, items = listOf(summary(2), summary(3)))

        val viewModel = viewModel(repository, FakeAuthRepository())
        viewModel.selectPage(2)

        val state = viewModel.state.value
        assertEquals(2, state.page)
        assertEquals(2, (state.mode as MessagesUiState.Mode.Content).conversations.size)
        assertTrue(state.canGoPrevious)
    }

    @Test
    fun `inbox never prefetches conversations during loads or authentication transitions`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery { repository.getPrivateMessageList(page = 1) } returns
            PrivateMessageListPage(page = 1, totalPages = 2, items = listOf(summary(1)))
        coEvery { repository.getPrivateMessageList(page = 2) } returns
            PrivateMessageListPage(page = 2, totalPages = 2, items = listOf(summary(2)))
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        val viewModel = viewModel(repository, authRepository)

        viewModel.refresh()
        advanceUntilIdle()
        viewModel.selectPage(2)
        advanceUntilIdle()
        authRepository.emit(AuthState.Anonymous)
        advanceUntilIdle()
        authRepository.emit(AuthState.Authenticated("bob"))
        advanceUntilIdle()

        coVerify(exactly = 0) {
            repository.prefetchPrivateMessageThread(threadId = any(), page = any())
        }
    }

    @Test
    fun `logout clears private inbox content and login reloads it`() = runTest {
        val repository = mockk<MessagesRepository>()
        val authRepository = FakeAuthRepository()
        coEvery { repository.getPrivateMessageList(page = 1) } returns
            PrivateMessageListPage(page = 1, totalPages = 1, items = listOf(summary(1)))

        val viewModel = viewModel(repository, authRepository)
        assertTrue(viewModel.state.value.mode is MessagesUiState.Mode.Content)

        authRepository.emit(AuthState.Anonymous)
        advanceUntilIdle()
        assertEquals(MessagesUiState.Mode.RequiresLogin, viewModel.state.value.mode)

        authRepository.emit(AuthState.Authenticated("other"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.mode is MessagesUiState.Mode.Content)
    }

    @Test
    fun `seeds DT reading positions once when the section is enabled`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery { repository.getPrivateMessageList(page = 1) } returns
            PrivateMessageListPage(page = 1, totalPages = 1, items = listOf(summary(1)))
        val seeder = fakeSeeder()

        viewModel(repository, preferences = fakePreferences(showDt = true), seeder = seeder)
        advanceUntilIdle()

        coVerify(exactly = 1) { seeder.seed() }
    }

    @Test
    fun `does not seed DT reading positions when the section is disabled`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery { repository.getPrivateMessageList(page = 1) } returns
            PrivateMessageListPage(page = 1, totalPages = 1, items = listOf(summary(1)))
        val seeder = fakeSeeder()

        viewModel(repository, preferences = fakePreferences(showDt = false), seeder = seeder)
        advanceUntilIdle()

        coVerify(exactly = 0) { seeder.seed() }
    }

    private fun viewModel(
        repository: MessagesRepository,
        authRepository: AuthRepository = FakeAuthRepository(),
        preferences: UserPreferencesRepository = fakePreferences(showDt = false),
        seeder: MpStorageReadPositionSeeder = fakeSeeder(),
    ) = MessagesViewModel(
        repository = repository,
        authRepository = authRepository,
        userPreferencesRepository = preferences,
        mpStorageReadPositionSeeder = seeder,
    )

    private fun fakePreferences(showDt: Boolean) = mockk<UserPreferencesRepository> {
        every { observeShowDtSection() } returns flowOf(showDt)
    }

    private fun fakeSeeder() = mockk<MpStorageReadPositionSeeder> {
        coEvery { seed() } returns MpStorageSeedOutcome.NoStorage
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
