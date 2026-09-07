package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import fr.forumhfr.redface2.core.domain.preferences.PostImageCorners
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

/** #985 — contract of the three content-image corner choices in Settings > Affichage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsDisplayPostImageCornersTest {

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
    fun `the three post image corner options are visible`() {
        mount()

        composeTestRule.onNodeWithText("Coins des images").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Arrondis (8 dp, défaut)").assertExists()
        composeTestRule.onNodeWithText("Légers (4 dp)").assertExists()
        composeTestRule.onNodeWithText("Carrés (0 dp)").assertExists()
    }

    @Test
    fun `tapping square dispatches PostImageCornersChanged`() {
        mount()

        composeTestRule.onNodeWithText("Carrés (0 dp)").performScrollTo().performClick()

        assertEquals(
            listOf<SettingsIntent>(SettingsIntent.PostImageCornersChanged(PostImageCorners.SQUARE)),
            received,
        )
    }

    @Test
    fun `the group is disabled while the write is in flight`() {
        mount(SettingsState(isUpdatingPostImageCorners = true))

        composeTestRule.onNodeWithText("Arrondis (8 dp, défaut)").performScrollTo().assertIsNotEnabled()
    }
}
