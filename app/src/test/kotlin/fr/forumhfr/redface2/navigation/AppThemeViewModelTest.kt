package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.core.domain.preferences.AccentColor
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
import fr.forumhfr.redface2.core.domain.preferences.SmileyPickerDecoration
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrap
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
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

    @Test
    fun `before the first DataStore emission the mirror seeds the state`() = runTest {
        val repository = mockk<UserPreferencesRepository> {
            // A SharedFlow that never emits = DataStore still hydrating on a cold start.
            every { observeThemeMode() } returns MutableSharedFlow()
            every { observeAmoledEnabled() } returns MutableSharedFlow()
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
            every { observeAccentColor() } returns MutableStateFlow(AccentColor.ROSE)
            // #973 — eagerly collected by the VM constructor; default M is enough here.
            every { observeMediaDisplayProfile() } returns MutableStateFlow(MediaDisplayProfile.M)
            // #989 — nouveau flow de l'interface : à stubber sinon MockK échoue au premier collect.
            every { observeSmileyPickerDecoration() } returns MutableStateFlow(SmileyPickerDecoration.NONE)
        }

        val vm = AppThemeViewModel(
            userPreferencesRepository = repository,
            themeBootstrapStore = bootstrapStore(ThemeBootstrap(ThemeMode.DARK, amoledEnabled = true)),
        )

        assertEquals(ThemeMode.DARK, vm.themeMode.value)
        assertTrue(vm.amoledEnabled.value)
    }

    @Test
    fun `the hydrated DataStore value wins over a divergent mirror`() = runTest {
        // Partial restore / cleared mirror : DataStore stays the source of truth.
        val repository = mockk<UserPreferencesRepository> {
            every { observeThemeMode() } returns MutableStateFlow(ThemeMode.LIGHT)
            every { observeAmoledEnabled() } returns MutableStateFlow(false)
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
            every { observeAccentColor() } returns MutableStateFlow(AccentColor.ROSE)
            // #973 — eagerly collected by the VM constructor; default M is enough here.
            every { observeMediaDisplayProfile() } returns MutableStateFlow(MediaDisplayProfile.M)
            // #989 — nouveau flow de l'interface : à stubber sinon MockK échoue au premier collect.
            every { observeSmileyPickerDecoration() } returns MutableStateFlow(SmileyPickerDecoration.NONE)
        }

        val vm = AppThemeViewModel(
            userPreferencesRepository = repository,
            themeBootstrapStore = bootstrapStore(ThemeBootstrap(ThemeMode.DARK, amoledEnabled = true)),
        )

        assertEquals(ThemeMode.LIGHT, vm.themeMode.value)
        assertEquals(false, vm.amoledEnabled.value)
    }

    @Test
    fun `the media display profile hydrates from the repository`() = runTest {
        // #973 — the profile is a reading preference like foldLongQuotes (#332): eagerly
        // collected, seed M (no bootstrap mirror — it does not paint the pre-first-frame
        // window), the DataStore value wins once hydrated.
        val repository = mockk<UserPreferencesRepository> {
            every { observeThemeMode() } returns MutableStateFlow(ThemeMode.LIGHT)
            every { observeAmoledEnabled() } returns MutableStateFlow(false)
            every { observeDisplayDensity() } returns MutableStateFlow(DisplayDensity.COMFORT)
            every { observeFontScale() } returns MutableStateFlow(FontScalePreference.M)
            every { observeDebugBoundsOverlay() } returns MutableStateFlow(false)
            every { observeFoldLongQuotes() } returns MutableStateFlow(true)
            every { observeShowScrollbar() } returns MutableStateFlow(true)
            every { observeNavBarLabels() } returns MutableStateFlow(true)
            every { observeHideSystemNavBar() } returns MutableStateFlow(false)
            every { observeImmersiveBackButton() } returns MutableStateFlow(true)
            every { observeImmersiveNavBarReveal() } returns MutableStateFlow(ImmersiveNavBarReveal.MANUAL)
            every { observeAccentColor() } returns MutableStateFlow(AccentColor.ROSE)
            every { observeMediaDisplayProfile() } returns MutableStateFlow(MediaDisplayProfile.L)
            // #989 — nouveau flow de l'interface : à stubber sinon MockK échoue au premier collect.
            every { observeSmileyPickerDecoration() } returns MutableStateFlow(SmileyPickerDecoration.NONE)
        }

        val vm = AppThemeViewModel(
            userPreferencesRepository = repository,
            themeBootstrapStore = bootstrapStore(ThemeBootstrap(ThemeMode.DARK, amoledEnabled = true)),
        )

        assertEquals(MediaDisplayProfile.L, vm.mediaDisplayProfile.value)
    }
}
