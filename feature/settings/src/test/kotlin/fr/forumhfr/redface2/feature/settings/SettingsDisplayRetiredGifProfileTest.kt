package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsDisplayRetiredGifProfileTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `display settings no longer expose GIF enlargement or S M L factors`() {
        val viewModel = mockk<SettingsViewModel> {
            every { state } returns MutableStateFlow(SettingsState())
        }
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                SettingsDisplayScreen(onBack = {}, viewModel = viewModel)
            }
        }
        listOf("Agrandissement des GIF", "S (×1, net)", "M (×1,5)", "L (×2,5)").forEach { label ->
            composeTestRule.onNodeWithText(label).assertDoesNotExist()
        }
    }
}
