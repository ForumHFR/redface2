package fr.forumhfr.redface2.feature.settings

import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.profile.SanctionsRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.profile.Sanction
import fr.forumhfr.redface2.core.model.profile.SanctionsHistory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SanctionsViewModelTest {
    private val authState = MutableStateFlow<AuthState>(AuthState.Authenticated("XaTriX"))
    private val authRepository = mockk<AuthRepository> {
        every { observeAuthState() } returns authState
    }
    private val repository = mockk<SanctionsRepository>()
    private val viewModels = mutableListOf<SanctionsViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `anonymous never loads history including on retry`() = runTest {
        authState.value = AuthState.Anonymous
        val viewModel = createViewModel()
        viewModel.submit(SanctionsIntent.Retry)
        assertEquals(SanctionsUiState.SignInRequired, viewModel.state.value)
        coVerify(exactly = 0) { repository.loadSanctions() }
    }

    @Test
    fun `authenticated load exposes loading then the history with its server pseudo`() = runTest {
        val pending = CompletableDeferred<Result<SanctionsHistory>>()
        coEvery { repository.loadSanctions() } coAnswers { pending.await() }
        val viewModel = createViewModel()
        assertEquals(SanctionsUiState.Loading, viewModel.state.value)
        val sanction = Sanction("XaTriX", "Teletubbies", "TotalRecall", "IA", "date", "lifted", "reason")
        pending.complete(Result.success(SanctionsHistory.Loaded("XaTriX", listOf(sanction))))
        advanceUntilIdle()
        assertEquals(SanctionsUiState.Loaded("XaTriX", listOf(sanction)), viewModel.state.value)
    }

    @Test
    fun `sign in loads the empty history and repeated auth state does not reload`() = runTest {
        authState.value = AuthState.Anonymous
        coEvery { repository.loadSanctions() } returns Result.success(SanctionsHistory.Loaded("XaTelitte", emptyList()))
        val viewModel = createViewModel()
        authState.value = AuthState.Authenticated("XaTelitte")
        advanceUntilIdle()
        assertEquals(SanctionsUiState.Empty("XaTelitte"), viewModel.state.value)
        authState.value = AuthState.Authenticated("XaTelitte")
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.loadSanctions() }
    }

    @Test
    fun `missing table and expired session both require sign in`() = runTest {
        coEvery { repository.loadSanctions() } returns Result.success(SanctionsHistory.SignInRequired)
        assertEquals(SanctionsUiState.SignInRequired, createViewModel().state.value)
        coEvery { repository.loadSanctions() } returns Result.failure(SessionExpiredException("history"))
        assertEquals(SanctionsUiState.SignInRequired, createViewModel().state.value)
    }

    @Test
    fun `network error retries once and recovers`() = runTest {
        coEvery { repository.loadSanctions() } returns Result.failure(IOException("offline"))
        val viewModel = createViewModel()
        assertEquals(SanctionsUiState.Error(HfrErrorKind.Network), viewModel.state.value)
        coEvery { repository.loadSanctions() } returns Result.success(SanctionsHistory.Loaded("XaTriX", emptyList()))
        viewModel.submit(SanctionsIntent.Retry)
        advanceUntilIdle()
        assertEquals(SanctionsUiState.Empty("XaTriX"), viewModel.state.value)
        coVerify(exactly = 2) { repository.loadSanctions() }
    }

    @Test
    fun `server and parser failures retain their distinct UI classification`() = runTest {
        coEvery { repository.loadSanctions() } returns Result.failure(HfrServerException(SERVER_ERROR, "history"))
        assertEquals(SanctionsUiState.Error(HfrErrorKind.ServerDown), createViewModel().state.value)
        coEvery { repository.loadSanctions() } returns Result.failure(IllegalStateException("parse"))
        assertEquals(SanctionsUiState.Error(HfrErrorKind.Other), createViewModel().state.value)
    }

    @Test
    fun `logout clears loaded account data`() = runTest {
        coEvery { repository.loadSanctions() } returns Result.success(SanctionsHistory.Loaded("XaTriX", emptyList()))
        val viewModel = createViewModel()
        authState.value = AuthState.Anonymous
        advanceUntilIdle()
        assertEquals(SanctionsUiState.SignInRequired, viewModel.state.value)
        coVerify(exactly = 1) { repository.loadSanctions() }
    }

    @Test
    fun `logout cancels the in flight read`() = runTest {
        val pending = CompletableDeferred<Result<SanctionsHistory>>()
        var cancelled = false
        coEvery { repository.loadSanctions() } coAnswers {
            try {
                pending.await()
            } finally {
                cancelled = true
            }
        }
        val viewModel = createViewModel()
        authState.value = AuthState.Anonymous
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(SanctionsUiState.SignInRequired, viewModel.state.value)
    }

    @Test
    fun `account switch drops a late previous account response`() = runTest {
        val pending = CompletableDeferred<Result<SanctionsHistory>>()
        coEvery { repository.loadSanctions() } coAnswers {
            withContext(NonCancellable) { pending.await() }
        }
        val viewModel = createViewModel()
        coEvery { repository.loadSanctions() } returns Result.success(SanctionsHistory.Loaded("XaTelitte", emptyList()))
        authState.value = AuthState.Authenticated("XaTelitte")
        advanceUntilIdle()
        assertEquals(SanctionsUiState.Empty("XaTelitte"), viewModel.state.value)
        pending.complete(Result.success(SanctionsHistory.Loaded("XaTriX", emptyList())))
        advanceUntilIdle()
        assertEquals(SanctionsUiState.Empty("XaTelitte"), viewModel.state.value)
        coVerify(exactly = 2) { repository.loadSanctions() }
    }

    @Test
    fun `retry while loading does not start a duplicate read`() = runTest {
        val pending = CompletableDeferred<Result<SanctionsHistory>>()
        coEvery { repository.loadSanctions() } coAnswers { pending.await() }
        val viewModel = createViewModel()
        viewModel.submit(SanctionsIntent.Retry)
        coVerify(exactly = 1) { repository.loadSanctions() }
        pending.complete(Result.success(SanctionsHistory.SignInRequired))
        advanceUntilIdle()
    }

    private fun createViewModel(): SanctionsViewModel =
        SanctionsViewModel(authRepository, repository).also(viewModels::add)

    private companion object {
        const val SERVER_ERROR = 503
    }
}
