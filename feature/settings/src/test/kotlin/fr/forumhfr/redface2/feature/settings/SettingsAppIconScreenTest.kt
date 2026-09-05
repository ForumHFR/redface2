package fr.forumhfr.redface2.feature.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h1000dp")
class SettingsAppIconScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val received = mutableListOf<SettingsIntent>()

    private fun mount(initial: SettingsState = SettingsState()) {
        val state = mutableStateOf(initial)
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                SettingsAppIconContent(
                    state = state.value,
                    callbacks = SettingsAppIconCallbacks(onBack = {}, onIntent = { intent ->
                        received += intent
                        if (intent is SettingsIntent.AppLauncherIconChanged) {
                            state.value = state.value.copy(pendingAppLauncherIcon = intent.icon)
                        }
                    }),
                    // App-module tests check the real adaptive resources; this module has no app R dependency.
                    iconResource = { android.R.drawable.sym_def_app_icon },
                )
            }
        }
    }

    @Test
    fun `gallery exposes exactly five cards and one current badge`() {
        mount()

        composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(5)
        composeTestRule.onNodeWithText("Classique").assertExists()
        composeTestRule.onNodeWithText("Redface 1").assertExists()
        composeTestRule.onNodeWithText("Monogramme RF").assertExists()
        composeTestRule.onNodeWithText("Discussion").assertExists()
        composeTestRule.onNodeWithText("Puce").assertExists()
        composeTestRule.onAllNodesWithText("Actuelle").assertCountEquals(1)
        composeTestRule.onNodeWithTag("app_icon_CLASSIC").assertIsSelected()
        composeTestRule.onNodeWithText("Appliquer").assertIsNotEnabled()
    }

    @Test
    fun `selection enables Apply and clicking it emits the explicit intent`() {
        mount()

        composeTestRule.onNodeWithTag("app_icon_RF1").performClick().assertIsSelected()
        composeTestRule.onNodeWithText("Appliquer").assertIsEnabled().performClick()

        assertEquals(
            listOf(SettingsIntent.AppLauncherIconChanged(AppLauncherIcon.RF1), SettingsIntent.ApplyAppLauncherIcon),
            received,
        )
        composeTestRule.onAllNodesWithText("Actuelle").assertCountEquals(1)
        composeTestRule.onNodeWithTag("app_icon_CLASSIC").assert(hasText("Actuelle"))
    }

    @Test
    fun `each original drawing can be selected without changing the current badge`() {
        mount()
        listOf(AppLauncherIcon.MONOGRAM, AppLauncherIcon.BUBBLES, AppLauncherIcon.CHIP).forEach { icon ->
            composeTestRule.onNodeWithTag("app_icon_${icon.name}").performClick().assertIsSelected()
            composeTestRule.onNodeWithText("Appliquer").assertIsEnabled()
            composeTestRule.onAllNodesWithText("Actuelle").assertCountEquals(1)
            composeTestRule.onNodeWithTag("app_icon_CLASSIC").assert(hasText("Actuelle"))
        }
        assertEquals(
            listOf(
                SettingsIntent.AppLauncherIconChanged(AppLauncherIcon.MONOGRAM),
                SettingsIntent.AppLauncherIconChanged(AppLauncherIcon.BUBBLES),
                SettingsIntent.AppLauncherIconChanged(AppLauncherIcon.CHIP),
            ),
            received,
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h480dp")
    fun `compact gallery scrolls to the fifth card while Apply stays accessible`() {
        mount()

        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        composeTestRule.onNodeWithTag("app_icon_CHIP").assertIsDisplayed().performClick().assertIsSelected()
        composeTestRule.onNodeWithText("Appliquer").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(
            listOf(SettingsIntent.AppLauncherIconChanged(AppLauncherIcon.CHIP), SettingsIntent.ApplyAppLauncherIcon),
            received,
        )
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        composeTestRule.onNodeWithTag("app_icon_CLASSIC").assert(hasText("Actuelle"))
    }

    @Test
    fun `selecting current icon again disables Apply`() {
        mount()
        composeTestRule.onNodeWithTag("app_icon_RF1").performClick()
        composeTestRule.onNodeWithTag("app_icon_CLASSIC").performClick()
        composeTestRule.onNodeWithText("Appliquer").assertIsNotEnabled()
    }

    @Test
    fun `application in flight disables cards and Apply`() {
        mount(SettingsState(pendingAppLauncherIcon = AppLauncherIcon.RF1, isUpdatingAppLauncherIcon = true))
        composeTestRule.onNodeWithTag("app_icon_CLASSIC").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("app_icon_RF1").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("app_icon_MONOGRAM").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("app_icon_BUBBLES").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("app_icon_CHIP").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Appliquer").assertIsNotEnabled()
    }

    @Test
    fun `persistence error is shown in the snackbar`() {
        mount(SettingsState(appLauncherIconError = true))
        composeTestRule.onNodeWithText("Impossible de mémoriser l'icône. Réessayez.").assertExists()
    }
}
