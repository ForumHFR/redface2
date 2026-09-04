package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

/** #326 — Compose contract for the launcher-icon choice group in Settings > Affichage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsDisplayLauncherIconTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val received = mutableListOf<SettingsIntent>()

    private fun mount(state: SettingsState = SettingsState()) {
        val viewModel = mockk<SettingsViewModel> {
            every { this@mockk.state } returns MutableStateFlow(state)
            every { submit(any()) } answers { received += firstArg<SettingsIntent>() }
        }
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                SettingsDisplayScreen(onBack = {}, viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the four icons and launcher refresh help are shown`() {
        mount()

        composeTestRule.onNodeWithText("Classique").performScrollTo().assertExists()
        composeTestRule.onAllNodesWithText("Sombre").assertCountEquals(2)
        composeTestRule.onNodeWithText("Rose").assertExists()
        composeTestRule.onNodeWithText("Rouge").assertExists()
        composeTestRule
            .onNodeWithText("Le lanceur peut mettre quelques secondes à afficher la nouvelle icône.")
            .assertExists()
    }

    @Test
    fun `tapping an icon dispatches AppLauncherIconChanged`() {
        mount()

        composeTestRule.onNodeWithText("Rose").performScrollTo().performClick()

        assertEquals(
            listOf<SettingsIntent>(SettingsIntent.AppLauncherIconChanged(AppLauncherIcon.ROSE)),
            received,
        )
    }

    @Test
    fun `the choices are disabled while persistence is in flight`() {
        mount(SettingsState(isUpdatingAppLauncherIcon = true))

        composeTestRule.onNodeWithText("Classique").performScrollTo().assertIsNotEnabled()
    }
}
