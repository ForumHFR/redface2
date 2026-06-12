package fr.forumhfr.redface2.navigation

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * #313 — the badge contract: `unreadCount` is non-null ONLY when (preference on) AND (count
 * resolved) AND (count > 0). Everything else — anonymous/null, zero, disabled — renders nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MpUnreadBadgeViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        counts: Flow<Int?>,
        badgeEnabled: Flow<Boolean> = flowOf(true),
        messagesRepository: MessagesRepository = mockk(relaxed = true) {
            every { observeUnreadMpCount() } returns counts
        },
    ): MpUnreadBadgeViewModel = MpUnreadBadgeViewModel(
        messagesRepository = messagesRepository,
        userPreferencesRepository = mockk {
            every { observeMpUnreadBadge() } returns badgeEnabled
        },
    )

    // NB : StateFlow + UnconfinedTestDispatcher → l'amont est déjà résolu à la souscription et
    // l'état initial `null` est conflaté ; les tests lisent donc l'état COURANT
    // (expectMostRecentItem) au lieu de présumer la séquence null → valeur.

    @Test
    fun `a positive count flows through when the preference is on`() = runTest {
        viewModel(counts = flowOf(3)).unreadCount.test {
            assertEquals(3, expectMostRecentItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `zero and null counts hide the badge`() = runTest {
        viewModel(counts = flowOf(0)).unreadCount.test {
            assertNull(expectMostRecentItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel(counts = flowOf(null)).unreadCount.test {
            assertNull(expectMostRecentItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the preference turned off hides a positive count`() = runTest {
        viewModel(counts = flowOf(7), badgeEnabled = flowOf(false)).unreadCount.test {
            assertNull(expectMostRecentItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flipping the preference off live removes the badge`() = runTest {
        val enabled = MutableStateFlow(true)
        viewModel(counts = flowOf(4), badgeEnabled = enabled).unreadCount.test {
            assertEquals(4, expectMostRecentItem())

            enabled.value = false

            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onAppForegrounded skips the first start then ticks the repository`() = runTest {
        val repository = mockk<MessagesRepository>(relaxed = true) {
            every { observeUnreadMpCount() } returns flowOf(1)
        }
        val vm = viewModel(counts = flowOf(1), messagesRepository = repository)

        vm.onAppForegrounded() // cold start — covered by the auth-flip fetch
        verify(exactly = 0) { repository.requestUnreadRefresh() }

        vm.onAppForegrounded() // back from background — must tick
        vm.onAppForegrounded()
        verify(exactly = 2) { repository.requestUnreadRefresh() }
    }
}
