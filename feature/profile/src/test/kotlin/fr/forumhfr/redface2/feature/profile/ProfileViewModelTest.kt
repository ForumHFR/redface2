package fr.forumhfr.redface2.feature.profile

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.profile.ProfileRepository
import fr.forumhfr.redface2.core.model.UserProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
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

    @Before
    fun setUp() {
        // Use UnconfinedTestDispatcher so viewModelScope.launch {} completes
        // synchronously, matching the test pattern used in FlagsViewModelTest.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val repository = mockk<ProfileRepository>()

    private val dummyProfile = UserProfile(
        userId = 54596,
        pseudo = "XaTriX",
        avatarUrl = "https://forum-images.hardware.fr/images/perso/54596/mesdiscussions-54596.png",
        registeredAt = "12/06/2002",
        postCount = 213400,
        location = "Katowice (PL)",
        signatureHtml = null,
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
    fun `network error exposes Error state`() = runTest {
        coEvery { repository.getProfile(54596) } returns Result.failure(IOException("network error"))

        val vm = createViewModel()

        vm.state.test {
            val state = awaitItem()
            assertTrue("Mode should be Error on failure", state.mode is ProfileUiState.Mode.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Retry intent re-triggers load after error`() = runTest {
        coEvery { repository.getProfile(54596) }
            .returnsMany(
                Result.failure(IOException("first attempt")),
                Result.success(dummyProfile),
            )

        val vm = createViewModel()

        vm.state.test {
            val errorState = awaitItem()
            assertTrue("First load should fail", errorState.mode is ProfileUiState.Mode.Error)

            vm.onIntent(ProfileIntent.Retry)

            // After Retry: Loading, then Loaded
            val loadingState = awaitItem()
            assertTrue("Should return to Loading on Retry", loadingState.mode is ProfileUiState.Mode.Loading)

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
}
