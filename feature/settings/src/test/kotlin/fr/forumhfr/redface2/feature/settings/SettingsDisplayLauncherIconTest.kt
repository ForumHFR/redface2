package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #326 — Affichage now opens the gallery and shows the currently persisted icon name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsDisplayLauncherIconTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private var galleryOpens = 0

    private fun mount(icon: AppLauncherIcon) {
        val viewModel = mockk<SettingsViewModel> {
            every { state } returns MutableStateFlow(SettingsState(appLauncherIcon = icon))
        }
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                SettingsDisplayScreen(onBack = {}, onOpenAppIcon = { galleryOpens++ }, viewModel = viewModel)
            }
        }
    }

    @Test
    fun `launcher row shows current icon and navigates to the gallery`() {
        mount(AppLauncherIcon.RF1)

        composeTestRule.onNodeWithText("Redface 1").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Icône de l'application").performClick()

        assertEquals(1, galleryOpens)
        composeTestRule.onNodeWithText("Rose").assertDoesNotExist()
        composeTestRule.onNodeWithText("Rouge").assertDoesNotExist()
    }
}
