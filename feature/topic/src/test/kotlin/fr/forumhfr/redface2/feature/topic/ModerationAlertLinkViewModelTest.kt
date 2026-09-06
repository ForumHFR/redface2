package fr.forumhfr.redface2.feature.topic

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.write.ModerationRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.write.ModerationAlertState
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModerationAlertLinkViewModelTest {
    private val target = ModerationAlertLinkTarget(cat = 23, post = 35421, numreponse = 2_800_456, page = 76)
    private val moderation = FakeModerationRepository()
    private val auth = FakeAuthRepository(AuthState.Authenticated("xaat"))
    private val viewModels = mutableListOf<ModerationAlertLinkViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.onIntent(ModerationAlertLinkIntent.Dismiss) }
        Dispatchers.resetMain()
    }

    @Test
    fun `authenticated open emits loading then HFR information without submitting`() = runTest {
        moderation.alert = ModerationAlertState.PendingMine(HFR_MESSAGE)
        moderation.loadGate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.state.test {
            assertEquals(ModerationAlertLinkState.Idle, awaitItem())
            viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
            assertEquals(ModerationAlertLinkState.Loading(target), awaitItem())
            runCurrent()
            assertEquals(listOf(listOf(23, 35421, 2_800_456, 76)), moderation.loads)
            moderation.loadGate?.complete(Unit)
            assertEquals(ModerationAlertLinkState.Info(target, HFR_MESSAGE), awaitItem())
        }
        assertTrue(moderation.sends.isEmpty())
        assertTrue(moderation.joins.isEmpty())
    }

    @Test
    fun `every informational alert state preserves the HFR message and treatment date`() = runTest {
        val states = listOf(
            ModerationAlertState.PendingMine(HFR_MESSAGE) to null,
            ModerationAlertState.PendingJoined(HFR_MESSAGE) to null,
            ModerationAlertState.TreatedMine(HFR_MESSAGE, TREATED_AT) to TREATED_AT,
            ModerationAlertState.TreatedJoined(HFR_MESSAGE, TREATED_AT) to TREATED_AT,
            ModerationAlertState.Unknown(HFR_MESSAGE) to null,
        )
        val viewModel = viewModel()
        for ((alert, treatedAt) in states) {
            moderation.alert = alert
            viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
            advanceUntilIdle()
            assertEquals(ModerationAlertLinkState.Info(target, HFR_MESSAGE, treatedAt), viewModel.state.value)
        }
    }

    @Test
    fun `form navigates to the post before opening the alert sheet`() = runTest {
        moderation.alert = ModerationAlertState.Form("modo.php", "token", null)
        val viewModel = viewModel()
        viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
        advanceUntilIdle()
        assertEquals(ModerationAlertLinkState.NavigateToPost(target, withAlertSheet = true), viewModel.state.value)
        assertTrue(moderation.sends.isEmpty())
    }

    @Test
    fun `join prompt navigates to the post before opening the alert sheet`() = runTest {
        moderation.alert = ModerationAlertState.JoinPrompt("modo.php", "token", null)
        val viewModel = viewModel()
        viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
        advanceUntilIdle()
        assertEquals(ModerationAlertLinkState.NavigateToPost(target, withAlertSheet = true), viewModel.state.value)
        assertTrue(moderation.joins.isEmpty())
    }

    @Test
    fun `anonymous open requires sign in without a moderation request`() = runTest {
        auth.emit(AuthState.Anonymous)
        val viewModel = viewModel()
        viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
        advanceUntilIdle()
        assertEquals(ModerationAlertLinkState.SignInRequired(target), viewModel.state.value)
        assertTrue(moderation.loads.isEmpty())
    }

    @Test
    fun `network failure can be retried with the same target`() = runTest {
        moderation.loadError = IOException("offline")
        val viewModel = viewModel()
        viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
        advanceUntilIdle()
        assertEquals(ModerationAlertLinkState.Error(target, HfrErrorKind.Network), viewModel.state.value)
        moderation.loadError = null
        moderation.alert = ModerationAlertState.PendingMine(HFR_MESSAGE)
        viewModel.onIntent(ModerationAlertLinkIntent.Retry)
        assertEquals(ModerationAlertLinkState.Loading(target), viewModel.state.value)
        advanceUntilIdle()
        assertEquals(ModerationAlertLinkState.Info(target, HFR_MESSAGE), viewModel.state.value)
        assertEquals(List(2) { listOf(23, 35421, 2_800_456, 76) }, moderation.loads)
    }

    @Test
    fun `view post navigates without an alert sheet from info sign in and error`() = runTest {
        val viewModel = viewModel()
        moderation.alert = ModerationAlertState.PendingMine(HFR_MESSAGE)
        viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
        advanceUntilIdle()
        viewModel.onIntent(ModerationAlertLinkIntent.ViewPost)
        assertEquals(ModerationAlertLinkState.NavigateToPost(target, withAlertSheet = false), viewModel.state.value)

        auth.emit(AuthState.Anonymous)
        viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
        advanceUntilIdle()
        viewModel.onIntent(ModerationAlertLinkIntent.ViewPost)
        assertEquals(ModerationAlertLinkState.NavigateToPost(target, withAlertSheet = false), viewModel.state.value)

        auth.emit(AuthState.Authenticated("xaat"))
        moderation.loadError = IOException("offline")
        viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
        advanceUntilIdle()
        viewModel.onIntent(ModerationAlertLinkIntent.ViewPost)
        assertEquals(ModerationAlertLinkState.NavigateToPost(target, withAlertSheet = false), viewModel.state.value)
    }

    @Test
    fun `dismiss returns to idle and cancels loading`() = runTest {
        moderation.loadGate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
        runCurrent()
        viewModel.onIntent(ModerationAlertLinkIntent.Dismiss)
        advanceUntilIdle()
        assertEquals(ModerationAlertLinkState.Idle, viewModel.state.value)
        assertTrue(moderation.loadCancelled)
    }

    @Test
    fun `dismissed information stays idle after a later account change`() = runTest {
        moderation.alert = ModerationAlertState.PendingMine(HFR_MESSAGE)
        val viewModel = viewModel()
        viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
        advanceUntilIdle()
        viewModel.onIntent(ModerationAlertLinkIntent.Dismiss)
        auth.emit(AuthState.Authenticated("another-user"))
        advanceUntilIdle()
        assertEquals(ModerationAlertLinkState.Idle, viewModel.state.value)
        assertEquals(1, moderation.loads.size)
    }

    @Test
    fun `a late noncancellable response cannot reopen a dismissed sheet`() = runTest {
        moderation.loadGate = CompletableDeferred()
        moderation.alert = ModerationAlertState.PendingMine(HFR_MESSAGE)
        val viewModel = viewModel(noncancellableRepository())
        viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
        runCurrent()
        viewModel.onIntent(ModerationAlertLinkIntent.Dismiss)
        moderation.loadGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(ModerationAlertLinkState.Idle, viewModel.state.value)
    }

    @Test
    fun `view post during loading ignores the late response`() = runTest {
        moderation.loadGate = CompletableDeferred()
        val viewModel = viewModel(noncancellableRepository())
        viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
        runCurrent()
        viewModel.onIntent(ModerationAlertLinkIntent.ViewPost)
        moderation.loadGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(ModerationAlertLinkState.NavigateToPost(target, withAlertSheet = false), viewModel.state.value)
    }

    @Test
    fun `a newer open keeps its target after the previous load returns late`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        moderation.loadGate = firstGate
        val viewModel = viewModel(noncancellableRepository())
        viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
        runCurrent()
        moderation.loadGate = null
        moderation.alert = ModerationAlertState.PendingMine(HFR_MESSAGE)
        val newerTarget = target.copy(numreponse = target.numreponse + 1)
        viewModel.onIntent(ModerationAlertLinkIntent.Open(newerTarget))
        runCurrent()
        firstGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ModerationAlertLinkState.Info(newerTarget, HFR_MESSAGE), viewModel.state.value)
    }

    @Test
    fun `logout clears account information and login reloads it`() = runTest {
        moderation.alert = ModerationAlertState.PendingMine(HFR_MESSAGE)
        val viewModel = viewModel()
        viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
        advanceUntilIdle()
        auth.emit(AuthState.Anonymous)
        advanceUntilIdle()
        assertEquals(ModerationAlertLinkState.SignInRequired(target), viewModel.state.value)
        assertEquals(1, moderation.loads.size)
        moderation.alert = ModerationAlertState.Unknown("Autre compte")
        auth.emit(AuthState.Authenticated("another-user"))
        advanceUntilIdle()
        assertEquals(ModerationAlertLinkState.Info(target, "Autre compte"), viewModel.state.value)
        assertEquals(2, moderation.loads.size)
    }

    @Test
    fun `session expiry requires sign in instead of reporting a network outage`() = runTest {
        moderation.loadError = SessionExpiredException("https://forum.hardware.fr/")
        val viewModel = viewModel()
        viewModel.onIntent(ModerationAlertLinkIntent.Open(target))
        advanceUntilIdle()
        assertEquals(ModerationAlertLinkState.SignInRequired(target), viewModel.state.value)
    }

    private fun viewModel(repository: ModerationRepository = moderation): ModerationAlertLinkViewModel =
        ModerationAlertLinkViewModel(repository, auth).also { viewModels += it }

    private fun noncancellableRepository(): ModerationRepository = object : ModerationRepository by moderation {
        override suspend fun loadAlert(cat: Int, topicId: Int, numreponse: Int, page: Int): ModerationAlertState =
            withContext(NonCancellable) { moderation.loadAlert(cat, topicId, numreponse, page) }
    }

    private class FakeAuthRepository(initial: AuthState) : AuthRepository {
        private val state = MutableStateFlow(initial)
        fun emit(value: AuthState) { state.value = value }
        override fun observeAuthState() = state.asStateFlow()
        override suspend fun login(pseudo: String, password: String) = error("login is not exercised here")
        override suspend fun logout() = error("logout is not exercised here")
    }

    private companion object {
        const val HFR_MESSAGE = "Votre demande de modération sur ce message n'est pas encore traitée."
        const val TREATED_AT = "2026-09-05 17:27:28"
    }
}
