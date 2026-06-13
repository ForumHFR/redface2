package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.preferences.StartScreenChoice
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.Category
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class StartScreenSettingsViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val hardware = Category(id = 13, name = "Hardware", forceSubcat = false, subcategoryCount = 9)

    private fun preferencesRepository(
        persisted: StartScreenPreference = StartScreenPreference(),
    ): UserPreferencesRepository = mockk {
        every { observeStartScreen() } returns MutableStateFlow(persisted)
        coEvery { setStartScreen(any()) } just Runs
    }

    private fun forumRepository(
        result: ForumResult<List<Category>> = ForumResult.Success(listOf(hardware)),
    ): ForumRepository = mockk {
        every { observeCategories() } returns flowOf(result)
    }

    @Test
    fun `init hydrates the persisted preference and the category list`() = runTest {
        val viewModel = StartScreenSettingsViewModel(
            userPreferencesRepository = preferencesRepository(
                StartScreenPreference(StartScreenChoice.FORUM, forumCatId = 13),
            ),
            forumRepository = forumRepository(),
        )

        val state = viewModel.state.value
        assertEquals(StartScreenPreference(StartScreenChoice.FORUM, forumCatId = 13), state.preference)
        assertEquals(listOf(hardware), state.categories)
        assertFalse(state.categoriesError)
    }

    @Test
    fun `a categories fetch failure only raises the picker flag`() = runTest {
        val viewModel = StartScreenSettingsViewModel(
            userPreferencesRepository = preferencesRepository(),
            forumRepository = forumRepository(ForumResult.Failure(IOException("offline"))),
        )

        val state = viewModel.state.value
        assertTrue(state.categoriesError)
        // The segmented choice itself never depends on the network.
        assertEquals(StartScreenPreference(), state.preference)
    }

    @Test
    fun `ScreenChanged persists the choice and drops the category off FORUM`() = runTest {
        val preferences = preferencesRepository(
            StartScreenPreference(StartScreenChoice.FORUM, forumCatId = 13),
        )
        val viewModel = StartScreenSettingsViewModel(preferences, forumRepository())

        viewModel.submit(StartScreenSettingsIntent.ScreenChanged(StartScreenChoice.MESSAGES))

        assertEquals(
            StartScreenPreference(StartScreenChoice.MESSAGES),
            viewModel.state.value.preference,
        )
        coVerify { preferences.setStartScreen(StartScreenPreference(StartScreenChoice.MESSAGES)) }
    }

    @Test
    fun `ForumCategoryChanged persists the picked category`() = runTest {
        val preferences = preferencesRepository(StartScreenPreference(StartScreenChoice.FORUM))
        val viewModel = StartScreenSettingsViewModel(preferences, forumRepository())

        viewModel.submit(StartScreenSettingsIntent.ForumCategoryChanged(13))

        assertEquals(
            StartScreenPreference(StartScreenChoice.FORUM, forumCatId = 13),
            viewModel.state.value.preference,
        )
        coVerify {
            preferences.setStartScreen(StartScreenPreference(StartScreenChoice.FORUM, forumCatId = 13))
        }
    }

    @Test
    fun `a persist failure rolls the selection back and raises the error flag`() = runTest {
        val preferences = preferencesRepository()
        coEvery { preferences.setStartScreen(any()) } throws IOException("disk full")
        val viewModel = StartScreenSettingsViewModel(preferences, forumRepository())

        viewModel.submit(StartScreenSettingsIntent.ScreenChanged(StartScreenChoice.FORUM))

        val state = viewModel.state.value
        assertEquals("failed persist must revert", StartScreenPreference(), state.preference)
        assertTrue(state.persistError)
        assertFalse(state.isUpdating)
    }

    @Test
    fun `late hydration does not overwrite a selection the user already made`() = runTest {
        // The persisted read is gated so the user's local change lands FIRST — the stale
        // hydration value must then be discarded (touchedLocally guard).
        val gate = CompletableDeferred<StartScreenPreference>()
        val preferences = mockk<UserPreferencesRepository> {
            every { observeStartScreen() } returns flow { emit(gate.await()) }
            coEvery { setStartScreen(any()) } just Runs
        }
        val viewModel = StartScreenSettingsViewModel(preferences, forumRepository())

        viewModel.submit(StartScreenSettingsIntent.ScreenChanged(StartScreenChoice.MESSAGES))
        gate.complete(StartScreenPreference(StartScreenChoice.FORUM, forumCatId = 13))
        advanceUntilIdle()

        assertEquals(
            StartScreenPreference(StartScreenChoice.MESSAGES),
            viewModel.state.value.preference,
        )
    }
}
