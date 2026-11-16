package fr.forumhfr.redface2.core.data.flags

import android.content.Context
import android.net.ConnectivityManager
import fr.forumhfr.redface2.core.database.dao.FlagCacheSnapshot
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshDiagnostics
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.network.diagnostics.AndroidNetworkDiagnosticsContextProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FlagRefreshSubscriptionTest {
    private val context = mockk<Context>()
    private val manager = mockk<ConnectivityManager>(relaxed = true)
    private val callback = slot<ConnectivityManager.NetworkCallback>()
    private val log = DiagnosticsLog()
    private val forum = mockk<ForumRepository>()

    init {
        every { context.getSystemService(ConnectivityManager::class.java) } returns manager
        every { manager.registerDefaultNetworkCallback(capture(callback)) } just Runs
        every { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
    }

    @Test
    fun `repository finally unregisters the network callback after caller cancellation during collection`() = runTest {
        val entered = CompletableDeferred<Unit>()
        coEvery { forum.getCategories(any()) } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        val repository = repository()
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) { repository.refresh(FlagType.CYAN) }
        entered.await()
        verify(exactly = 1) { manager.registerDefaultNetworkCallback(any()) }
        refresh.cancelAndJoin()
        verify(exactly = 1) { manager.unregisterNetworkCallback(callback.captured) }
    }

    @Test
    fun `repository finally unregisters the callback when network collection throws`() = runTest {
        val failure = IOException("SecretHost SecretCookie")
        coEvery { forum.getCategories(any()) } throws failure
        val result = repository().refresh(FlagType.CYAN)
        // The detached socle fetch rethrows a recovered copy of the failure, not the same instance.
        val cause = (result as FlagsResult.Failure).cause
        assertTrue(cause is IOException)
        assertEquals(failure.message, cause.message)
        verify(exactly = 1) { manager.unregisterNetworkCallback(callback.captured) }
        assertFalse(log.entries.value.toString().contains("Secret"))
    }

    private fun repository(): DefaultFlagRepository {
        val auth = mockk<AuthRepository>()
        every { auth.observeAuthState() } returns flowOf(AuthState.Authenticated("tester"))
        // Since the socle a failed catalogue is committed as a failure batch; an empty snapshot keeps its cause.
        val store = mockk<FlagCacheStore> {
            coEvery { merge(any(), any(), any()) } returns FlagCacheSnapshot(emptyList(), emptyList())
        }
        return DefaultFlagRepository(
            apiClient = mockk(), hfrClient = mockk(), flagAddResponseParser = mockk(),
            flagDeleteResponseParser = mockk(), forumRepository = forum, authRepository = auth,
            flagCacheStore = store, json = Json, ioDispatcher = UnconfinedTestDispatcher(),
            clock = Clock.systemUTC(), diagnostics = log,
            refreshDiagnostics = FlagRefreshDiagnostics(log, AndroidNetworkDiagnosticsContextProvider(context)),
        )
    }
}
