package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.core.domain.preferences.DarkSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
import fr.forumhfr.redface2.core.domain.preferences.NavBarLabelsBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.PostImageMaxWidth
import fr.forumhfr.redface2.core.domain.preferences.SmileyPickerDecoration
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrap
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.ThemeColorPreferences
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #386 — the cold-start contract : until DataStore's first emission lands, the exposed theme
 * is the synchronous [ThemeBootstrapStore] mirror, NOT a hard-coded SYSTEM default. A user who
 * forced DARK under a light OS must get DARK on the very first frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppThemeViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun bootstrapStore(bootstrap: ThemeBootstrap): ThemeBootstrapStore =
        mockk { every { read() } returns bootstrap }

    // #1138 — synchronous nav-bar-labels mirror; `read()` supplies the StateFlow seed.
    private fun navBarLabelsStore(seed: Boolean): NavBarLabelsBootstrapStore =
        mockk { every { read() } returns seed }

    @Test
    fun `before the first DataStore emission the mirror seeds the state`() = runTest {
        val repository = mockk<UserPreferencesRepository> {
            // A SharedFlow that never emits = DataStore still hydrating on a cold start.
            every { observeThemeMode() } returns MutableSharedFlow()
            every { observeThemeColorPreferences() } returns MutableSharedFlow()
            // #287 — eagerly collected by the VM; defaults are enough for this theme-focused test.
            every { observeDisplayDensity() } returns MutableStateFlow(DisplayDensity.COMFORT)
            every { observeFontScale() } returns MutableStateFlow(FontScalePreference.M)
            // #445 — eagerly collected by the VM constructor; default off is enough here.
            every { observeDebugBoundsOverlay() } returns MutableStateFlow(false)
            // #332 — eagerly collected by the VM constructor; default on is enough here.
            every { observeFoldLongQuotes() } returns MutableStateFlow(true)
            // #105 — eagerly collected by the VM constructor; default on is enough here.
            every { observeShowScrollbar() } returns MutableStateFlow(true)
            // #666 — eagerly collected by the VM constructor; default on is enough here.
            every { observeNavBarLabels() } returns MutableStateFlow(true)
            // #518 — eagerly collected by the VM constructor; default off is enough here.
            every { observeHideSystemNavBar() } returns MutableStateFlow(false)
            // #518 follow-up — eagerly collected by the VM constructor; default on is enough here.
            every { observeImmersiveBackButton() } returns MutableStateFlow(true)
            every { observeImmersiveNavBarReveal() } returns MutableStateFlow(ImmersiveNavBarReveal.MANUAL)
            every { observeAlwaysAskLinkApp() } returns MutableStateFlow(false)
            // #973 — eagerly collected by the VM constructor; default M is enough here.
            every { observeMediaDisplayProfile() } returns MutableStateFlow(MediaDisplayProfile.M)
            // #991 — eagerly collected by the VM constructor; default P95 is enough here.
            every { observePostImageMaxWidth() } returns MutableStateFlow(PostImageMaxWidth.DEFAULT)
            // #989 — nouveau flow de l'interface : à stubber sinon MockK échoue au premier collect.
            every { observeSmileyPickerDecoration() } returns MutableStateFlow(SmileyPickerDecoration.NONE)
            // #1170 — explicit interface defaults keep this wide repository mock future-proof.
            every { observeTopicUnansweredPollsExpanded() } returns MutableStateFlow(false)
            coEvery { setTopicUnansweredPollsExpanded(any()) } returns Unit
        }

        val bootstrap = ThemeBootstrap(ThemeMode.DARK, amoledEnabled = true)
        val vm = AppThemeViewModel(
            userPreferencesRepository = repository,
            themeBootstrapStore = bootstrapStore(bootstrap),
            navBarLabelsBootstrapStore = navBarLabelsStore(true),
        )

        assertEquals(ThemeMode.DARK, vm.themeMode.value)
        assertEquals(bootstrap.colorPreferences, vm.themeColorPreferences.value)
        assertTrue(vm.amoledEnabled.value)
    }

    @Test
    fun `before the first DataStore emission the mirror seeds the nav bar labels`() = runTest {
        // #1138 — the cold-start contract for the nav-bar labels: until DataStore hydrates, the
        // exposed value is the synchronous mirror, NOT the hard-coded `true`. A user who hid the
        // labels (mirror = false) must get an icon-only bar on the very first frame, no flash.
        val repository = mockk<UserPreferencesRepository> {
            // A SharedFlow that never emits = DataStore still hydrating on a cold start.
            every { observeNavBarLabels() } returns MutableSharedFlow()
            every { observeThemeMode() } returns MutableStateFlow(ThemeMode.SYSTEM)
            every { observeThemeColorPreferences() } returns MutableStateFlow(ThemeColorPreferences())
            every { observeDisplayDensity() } returns MutableStateFlow(DisplayDensity.COMFORT)
            every { observeFontScale() } returns MutableStateFlow(FontScalePreference.M)
            every { observeDebugBoundsOverlay() } returns MutableStateFlow(false)
            every { observeFoldLongQuotes() } returns MutableStateFlow(true)
            every { observeShowScrollbar() } returns MutableStateFlow(true)
            every { observeHideSystemNavBar() } returns MutableStateFlow(false)
            every { observeImmersiveBackButton() } returns MutableStateFlow(true)
            every { observeImmersiveNavBarReveal() } returns MutableStateFlow(ImmersiveNavBarReveal.MANUAL)
            every { observeAlwaysAskLinkApp() } returns MutableStateFlow(false)
            every { observeMediaDisplayProfile() } returns MutableStateFlow(MediaDisplayProfile.M)
            every { observePostImageMaxWidth() } returns MutableStateFlow(PostImageMaxWidth.DEFAULT)
            every { observeSmileyPickerDecoration() } returns MutableStateFlow(SmileyPickerDecoration.NONE)
            every { observeTopicUnansweredPollsExpanded() } returns MutableStateFlow(false)
            coEvery { setTopicUnansweredPollsExpanded(any()) } returns Unit
        }

        val vm = AppThemeViewModel(
            userPreferencesRepository = repository,
            themeBootstrapStore = bootstrapStore(ThemeBootstrap()),
            navBarLabelsBootstrapStore = navBarLabelsStore(false),
        )

        // The seed is the mirror's `false`, proving it does NOT come from the hard-coded `true`.
        assertFalse(vm.navBarLabels.value)
    }

    @Test
    fun `the hydrated DataStore value wins over a divergent mirror`() = runTest {
        // Partial restore / cleared mirror : DataStore stays the source of truth.
        val repository = mockk<UserPreferencesRepository> {
            every { observeThemeMode() } returns MutableStateFlow(ThemeMode.LIGHT)
            every { observeThemeColorPreferences() } returns MutableStateFlow(
                ThemeColorPreferences(darkSurfaceTone = DarkSurfaceTone.MATERIAL_TINTED),
            )
            every { observeDisplayDensity() } returns MutableStateFlow(DisplayDensity.COMFORT)
            every { observeFontScale() } returns MutableStateFlow(FontScalePreference.M)
            // #445 — eagerly collected by the VM constructor; default off is enough here.
            every { observeDebugBoundsOverlay() } returns MutableStateFlow(false)
            // #332 — eagerly collected by the VM constructor; default on is enough here.
            every { observeFoldLongQuotes() } returns MutableStateFlow(true)
            // #105 — eagerly collected by the VM constructor; default on is enough here.
            every { observeShowScrollbar() } returns MutableStateFlow(true)
            // #666 — eagerly collected by the VM constructor; default on is enough here.
            every { observeNavBarLabels() } returns MutableStateFlow(true)
            // #518 — eagerly collected by the VM constructor; default off is enough here.
            every { observeHideSystemNavBar() } returns MutableStateFlow(false)
            // #518 follow-up — eagerly collected by the VM constructor; default on is enough here.
            every { observeImmersiveBackButton() } returns MutableStateFlow(true)
            every { observeImmersiveNavBarReveal() } returns MutableStateFlow(ImmersiveNavBarReveal.MANUAL)
            every { observeAlwaysAskLinkApp() } returns MutableStateFlow(true)
            // #973 — eagerly collected by the VM constructor; default M is enough here.
            every { observeMediaDisplayProfile() } returns MutableStateFlow(MediaDisplayProfile.M)
            // #991 — eagerly collected by the VM constructor; default P95 is enough here.
            every { observePostImageMaxWidth() } returns MutableStateFlow(PostImageMaxWidth.DEFAULT)
            // #989 — nouveau flow de l'interface : à stubber sinon MockK échoue au premier collect.
            every { observeSmileyPickerDecoration() } returns MutableStateFlow(SmileyPickerDecoration.NONE)
            every { observeTopicUnansweredPollsExpanded() } returns MutableStateFlow(false)
            coEvery { setTopicUnansweredPollsExpanded(any()) } returns Unit
        }

        val vm = AppThemeViewModel(
            userPreferencesRepository = repository,
            themeBootstrapStore = bootstrapStore(ThemeBootstrap(ThemeMode.DARK, amoledEnabled = true)),
            navBarLabelsBootstrapStore = navBarLabelsStore(true),
        )

        assertEquals(ThemeMode.LIGHT, vm.themeMode.value)
        assertEquals(ThemeColorPreferences(), vm.themeColorPreferences.value)
        assertEquals(false, vm.amoledEnabled.value)
        assertTrue(vm.alwaysAskLinkApp.value)
    }

    @Test
    fun `the media display profile hydrates from the repository`() = runTest {
        // #973 — the profile is a reading preference like foldLongQuotes (#332): eagerly
        // collected, seed M (no bootstrap mirror — it does not paint the pre-first-frame
        // window), the DataStore value wins once hydrated.
        val repository = mockk<UserPreferencesRepository> {
            every { observeThemeMode() } returns MutableStateFlow(ThemeMode.LIGHT)
            every { observeThemeColorPreferences() } returns MutableStateFlow(ThemeColorPreferences())
            every { observeDisplayDensity() } returns MutableStateFlow(DisplayDensity.COMFORT)
            every { observeFontScale() } returns MutableStateFlow(FontScalePreference.M)
            every { observeDebugBoundsOverlay() } returns MutableStateFlow(false)
            every { observeFoldLongQuotes() } returns MutableStateFlow(true)
            every { observeShowScrollbar() } returns MutableStateFlow(true)
            every { observeNavBarLabels() } returns MutableStateFlow(true)
            every { observeHideSystemNavBar() } returns MutableStateFlow(false)
            every { observeImmersiveBackButton() } returns MutableStateFlow(true)
            every { observeImmersiveNavBarReveal() } returns MutableStateFlow(ImmersiveNavBarReveal.MANUAL)
            every { observeAlwaysAskLinkApp() } returns MutableStateFlow(false)
            every { observeMediaDisplayProfile() } returns MutableStateFlow(MediaDisplayProfile.L)
            every { observePostImageMaxWidth() } returns MutableStateFlow(PostImageMaxWidth.DEFAULT)
            // #989 — nouveau flow de l'interface : à stubber sinon MockK échoue au premier collect.
            every { observeSmileyPickerDecoration() } returns MutableStateFlow(SmileyPickerDecoration.NONE)
            every { observeTopicUnansweredPollsExpanded() } returns MutableStateFlow(false)
            coEvery { setTopicUnansweredPollsExpanded(any()) } returns Unit
        }

        val vm = AppThemeViewModel(
            userPreferencesRepository = repository,
            themeBootstrapStore = bootstrapStore(ThemeBootstrap(ThemeMode.DARK, amoledEnabled = true)),
            navBarLabelsBootstrapStore = navBarLabelsStore(true),
        )

        assertEquals(MediaDisplayProfile.L, vm.mediaDisplayProfile.value)
    }

    @Test
    fun `the post image max width hydrates from the repository`() = runTest {
        val repository = mockk<UserPreferencesRepository> {
            every { observeThemeMode() } returns MutableStateFlow(ThemeMode.LIGHT)
            every { observeThemeColorPreferences() } returns MutableStateFlow(ThemeColorPreferences())
            every { observeDisplayDensity() } returns MutableStateFlow(DisplayDensity.COMFORT)
            every { observeFontScale() } returns MutableStateFlow(FontScalePreference.M)
            every { observeDebugBoundsOverlay() } returns MutableStateFlow(false)
            every { observeFoldLongQuotes() } returns MutableStateFlow(true)
            every { observeShowScrollbar() } returns MutableStateFlow(true)
            every { observeNavBarLabels() } returns MutableStateFlow(true)
            every { observeHideSystemNavBar() } returns MutableStateFlow(false)
            every { observeImmersiveBackButton() } returns MutableStateFlow(true)
            every { observeImmersiveNavBarReveal() } returns MutableStateFlow(ImmersiveNavBarReveal.MANUAL)
            every { observeAlwaysAskLinkApp() } returns MutableStateFlow(false)
            every { observeMediaDisplayProfile() } returns MutableStateFlow(MediaDisplayProfile.M)
            every { observePostImageMaxWidth() } returns MutableStateFlow(PostImageMaxWidth.P90)
            every { observeSmileyPickerDecoration() } returns MutableStateFlow(SmileyPickerDecoration.NONE)
            every { observeTopicUnansweredPollsExpanded() } returns MutableStateFlow(false)
            coEvery { setTopicUnansweredPollsExpanded(any()) } returns Unit
        }

        val vm = AppThemeViewModel(
            userPreferencesRepository = repository,
            themeBootstrapStore = bootstrapStore(ThemeBootstrap()),
            navBarLabelsBootstrapStore = navBarLabelsStore(true),
        )

        assertEquals(PostImageMaxWidth.P90, vm.postImageMaxWidth.value)
    }
}
