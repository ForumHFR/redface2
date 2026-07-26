package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
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
 * #973 ([AMENDEMENT-v1.5-2], exigence XaTriX) — « Affichage » sub-page contract of the
 * « Agrandissement des GIF » choice group: the three S/M/L options are rendered with their
 * NUMERIC factors visible in the labels, a tap dispatches
 * [SettingsIntent.MediaDisplayProfileChanged], and the in-flight write gate disables the group —
 * the exact model of the neighbouring density group. Robolectric hosts `createComposeRule()` on
 * the JVM (same harness as [SettingsCatalogueFullWidthPostsTest]); the ViewModel is mocked so the
 * screen is exercised without Hilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsDisplayMediaProfileTest {

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
    fun `the S M L options are shown with their numeric factors visible`() {
        mount()

        composeTestRule.onNodeWithText("S (×1, net)").assertExists()
        composeTestRule.onNodeWithText("M (×1,5)").assertExists()
        composeTestRule.onNodeWithText("L (×2,5)").assertExists()
    }

    @Test
    fun `tapping an option dispatches MediaDisplayProfileChanged with its profile`() {
        mount()

        composeTestRule.onNodeWithText("L (×2,5)").performScrollTo().performClick()

        assertEquals(
            listOf<SettingsIntent>(SettingsIntent.MediaDisplayProfileChanged(MediaDisplayProfile.L)),
            received,
        )
    }

    @Test
    fun `the group is disabled while the write is in flight`() {
        mount(SettingsState(isUpdatingMediaDisplayProfile = true))

        composeTestRule.onNodeWithText("M (×1,5)").assertIsNotEnabled()
    }
}
