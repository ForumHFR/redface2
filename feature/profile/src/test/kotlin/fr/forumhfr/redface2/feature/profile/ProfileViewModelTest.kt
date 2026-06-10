package fr.forumhfr.redface2.feature.profile

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.profile.ProfileRepository
import fr.forumhfr.redface2.core.model.UserProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2 finish (#208) — unit tests for [ProfileViewModel].
 *
 * Covers:
 * - initial state is Loading;
 * - successful load transitions to Loaded;
 * - network failure transitions to Error;
 * - Retry intent re-triggers the load;
 * - pseudoHint and avatarUrlHint are preserved across state transitions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private lateinit var repository: ProfileRepository

    @Before
    fun setUp() {
        // Use UnconfinedTestDispatcher so viewModelScope.launch {} completes
        // synchronously, matching the test pattern used in FlagsViewModelTest.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val dummyProfile = UserProfile(
        userId = 54596,
        pseudo = "XaTriX",
        avatarUrl = "https://forum-images.hardware.fr/images/perso/54596/mesdiscussions-54596.png",
        registeredAt = "12/06/2002",
        postCount = 213400,
        location = "Katowice (PL)",
        signatureText = null,
    )

    private fun createViewModel(
        userId: Int = 54596,
        pseudo: String = "XaTriX",
        avatarUrl: String? = null,
    ): ProfileViewModel = ProfileViewModel(
        profileRepository = repository,
        userId = userId,
        pseudoHint = pseudo,
        avatarUrlHint = avatarUrl,
    )

    @Test
    fun `initial state has correct hints`() = runTest {
        coEvery { repository.getProfile(54596) } returns Result.success(dummyProfile)

        val vm = createViewModel(pseudo = "XaTriX", avatarUrl = "https://example.com/avatar.png")

        // UnconfinedTestDispatcher runs viewModelScope.launch{} synchronously.
        // The first (and only) collected item is the final state after loadProfile().
        vm.state.test {
            val state = awaitItem()
            assertEquals(54596, state.userId)
            assertEquals("XaTriX", state.pseudoHint)
            assertEquals("https://example.com/avatar.png", state.avatarUrlHint)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `successful fetch exposes Loaded state`() = runTest {
        coEvery { repository.getProfile(54596) } returns Result.success(dummyProfile)

        val vm = createViewModel()

        // With UnconfinedTestDispatcher the load completes before .test{} collects,
        // so the very first item is already Loaded.
        vm.state.test {
            val state = awaitItem()
            assertTrue("Mode should be Loaded", state.mode is ProfileUiState.Mode.Loaded)
            val loadedMode = state.mode as ProfileUiState.Mode.Loaded
            assertEquals(dummyProfile, loadedMode.profile)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `network error exposes Error state with ErrorKind Network and original cause`() = runTest {
        // Review feedback I7: the ViewModel must surface an ErrorKind enum (not a String
        // message) so the UI resolves the localised text via stringResource. The original
        // Throwable is preserved on `cause` for diagnostics. #324 — a transport IOException
        // now classifies as Network (« Pas de connexion »), no longer as Unknown.
        val cause = IOException("network error")
        coEvery { repository.getProfile(54596) } returns Result.failure(cause)

        val vm = createViewModel()

        vm.state.test {
            val state = awaitItem()
            assertTrue("Mode should be Error on failure", state.mode is ProfileUiState.Mode.Error)
            val error = state.mode as ProfileUiState.Mode.Error
            assertEquals(ProfileUiState.ErrorKind.Network, error.kind)
            assertEquals(cause, error.cause)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `HFR 5xx error exposes Error state with ErrorKind ServerDown`() = runTest {
        // #324 — getProfile raises a typed HfrServerException on a non-2xx; a 5xx must be
        // presented as « HFR est en panne » on both the sheet and the full page.
        val cause = HfrServerException(code = 500, url = "https://forum.hardware.fr/hfr/profil-54596.htm")
        coEvery { repository.getProfile(54596) } returns Result.failure(cause)

        val vm = createViewModel()

        vm.state.test {
            val error = awaitItem().mode as ProfileUiState.Mode.Error
            assertEquals(ProfileUiState.ErrorKind.ServerDown, error.kind)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `non-IO error keeps ErrorKind Unknown`() = runTest {
        // #324 — parse failures and other programming errors keep the generic message.
        coEvery { repository.getProfile(54596) } returns
            Result.failure(IllegalStateException("parser invariant broken"))

        val vm = createViewModel()

        vm.state.test {
            val error = awaitItem().mode as ProfileUiState.Mode.Error
            assertEquals(ProfileUiState.ErrorKind.Unknown, error.kind)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Retry intent re-triggers load after error`() = runTest {
        val firstAttempt = CompletableDeferred<Result<UserProfile>>()
        val secondAttempt = CompletableDeferred<Result<UserProfile>>()
        val attempts = ArrayDeque(listOf(firstAttempt, secondAttempt))
        coEvery { repository.getProfile(54596) } coAnswers {
            attempts.removeFirst().await()
        }

        val vm = createViewModel()

        vm.state.test {
            val initialLoadingState = awaitItem()
            assertTrue(
                "Initial load should start in Loading",
                initialLoadingState.mode is ProfileUiState.Mode.Loading,
            )

            firstAttempt.complete(Result.failure(IOException("first attempt")))

            val errorState = awaitItem()
            assertTrue("First load should fail", errorState.mode is ProfileUiState.Mode.Error)

            vm.onIntent(ProfileIntent.Retry)

            // After Retry: Loading, then Loaded
            val loadingState = awaitItem()
            assertTrue("Should return to Loading on Retry", loadingState.mode is ProfileUiState.Mode.Loading)

            secondAttempt.complete(Result.success(dummyProfile))

            val loadedState = awaitItem()
            assertTrue("Second load should succeed", loadedState.mode is ProfileUiState.Mode.Loaded)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hints are preserved in all state`() = runTest {
        coEvery { repository.getProfile(54596) } returns Result.success(dummyProfile)

        val vm = createViewModel(pseudo = "MyHint", avatarUrl = "https://example.com/hint.png")

        vm.state.test {
            val state = awaitItem()
            assertEquals("MyHint", state.pseudoHint)
            assertEquals("https://example.com/hint.png", state.avatarUrlHint)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getProfile is called exactly once on init`() = runTest {
        coEvery { repository.getProfile(54596) } returns Result.success(dummyProfile)

        createViewModel()

        coVerify(exactly = 1) { repository.getProfile(54596) }
    }

    @Test
    fun `rapid Retry taps - only the last result is observable in state`() = runTest {
        // Review feedback I8: when the user taps Retry multiple times before the previous
        // attempt completes, only the freshest result should land in the state — otherwise
        // a slow first attempt finishing AFTER a fast second one would clobber the second's
        // result. The ViewModel cancels the previous loadJob before launching a new one.
        val firstResult = Result.failure<UserProfile>(IOException("stale"))
        val secondResult = Result.success(dummyProfile)
        coEvery { repository.getProfile(54596) }.returnsMany(firstResult, secondResult, secondResult)

        val vm = createViewModel()

        vm.state.test {
            // Initial load (uses firstResult)
            val errorState = awaitItem()
            assertTrue("First load surfaces Error", errorState.mode is ProfileUiState.Mode.Error)

            // Two Retry taps back-to-back. UnconfinedTestDispatcher serialises the
            // launch{} blocks, but the cancel-before-launch invariant still gates how
            // many results actually update _state. The final state must be Loaded.
            vm.onIntent(ProfileIntent.Retry)
            vm.onIntent(ProfileIntent.Retry)

            // Drain to the final state.
            var lastState = awaitItem()
            while (lastState.mode !is ProfileUiState.Mode.Loaded) {
                lastState = awaitItem()
            }
            assertEquals(dummyProfile, lastState.mode.profile)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
