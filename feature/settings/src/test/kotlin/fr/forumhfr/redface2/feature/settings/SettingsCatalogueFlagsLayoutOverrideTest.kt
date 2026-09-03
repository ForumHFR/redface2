package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCatalogueFlagsLayoutOverrideTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `per-tab override disables global group row and shows quick sheet hint`() {
        mountFlagsRow(
            id = "flags_group_by_category",
            state = SettingsState(flagsPerTabOverride = true),
        )

        composeTestRule.onNodeWithText(PER_TAB_HINT).assertExists()
        composeTestRule.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test
    fun `per-tab override disables global hide-read row and shows quick sheet hint`() {
        mountFlagsRow(
            id = "flags_hide_read",
            state = SettingsState(flagsPerTabOverride = true),
        )

        composeTestRule.onNodeWithText(PER_TAB_HINT).assertExists()
        composeTestRule.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test
    fun `per-tab override master switch stays enabled while active`() {
        mountFlagsRow(
            id = "flags_per_tab_override",
            state = SettingsState(flagsPerTabOverride = true),
        )

        composeTestRule.onNode(isToggleable()).assertIsEnabled()
    }

    @Test
    fun `override off keeps global group row editable with its regular copy`() {
        mountFlagsRow(id = "flags_group_by_category", state = SettingsState())

        composeTestRule.onNodeWithText("En-tête par catégorie ; sinon liste à plat.").assertExists()
        composeTestRule.onNode(isToggleable()).assertIsEnabled()
    }

    @Test
    fun `override off keeps global hide-read row editable when grouped with its regular copy`() {
        mountFlagsRow(id = "flags_hide_read", state = SettingsState(flagsGroupByCategory = true))

        composeTestRule.onNodeWithText("Cache les catégories sans message non lu. Sans effet en vue à plat.")
            .assertExists()
        composeTestRule.onNode(isToggleable()).assertIsEnabled()
    }

    private fun mountFlagsRow(
        id: String,
        state: SettingsState,
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                testCatalogue(state = state)
                    .first { it.id == "flags" }
                    .items
                    .first { it.searchable.id == id }
                    .render()
            }
        }
        composeTestRule.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun testCatalogue(state: SettingsState): List<SettingsCatalogueSection> = buildSettingsCatalogue(
        state = state,
        onIntent = {},
        startScreenState = StartScreenSettingsState(),
        onStartScreenIntent = {},
        onOpenProxy = {},
        onOpenMaintenance = {},
        onOpenDisplay = {},
        onOpenImages = {},
        onOpenAccountAbout = {},
        onOpenBlacklist = {},
    )
}

private const val PER_TAB_HINT =
    "L'affichage se règle par onglet depuis l'écran Drapeaux, dans la feuille de configuration rapide."
