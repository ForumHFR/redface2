package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import fr.forumhfr.redface2.core.domain.preferences.PostImageMaxWidth
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

/**
 * #991 — « Affichage » sub-page contract of the « Largeur maximale des images » choice group:
 * the four fImage presets are visible, a tap dispatches [SettingsIntent.PostImageMaxWidthChanged],
 * and the in-flight write gate disables the group.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsDisplayPostImageMaxWidthTest {

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
    fun `the four post image max width options are visible`() {
        mount()

        composeTestRule.onNodeWithText("Largeur maximale des images").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("90\u00A0%").assertExists()
        composeTestRule.onNodeWithText("95\u00A0% (défaut)").assertExists()
        composeTestRule.onNodeWithText("99\u00A0%").assertExists()
        composeTestRule.onNodeWithText("100\u00A0%").assertExists()
    }

    @Test
    fun `tapping an option dispatches PostImageMaxWidthChanged with its width`() {
        mount()

        composeTestRule.onNodeWithText("100\u00A0%").performScrollTo().performClick()

        assertEquals(
            listOf<SettingsIntent>(SettingsIntent.PostImageMaxWidthChanged(PostImageMaxWidth.P100)),
            received,
        )
    }

    @Test
    fun `the group is disabled while the write is in flight`() {
        mount(SettingsState(isUpdatingPostImageMaxWidth = true))

        composeTestRule.onNodeWithText("95\u00A0% (défaut)").performScrollTo().assertIsNotEnabled()
    }
}
