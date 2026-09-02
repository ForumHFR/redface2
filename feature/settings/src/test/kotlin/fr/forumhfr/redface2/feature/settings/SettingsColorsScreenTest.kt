package fr.forumhfr.redface2.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import fr.forumhfr.redface2.core.domain.preferences.AccentPreset
import fr.forumhfr.redface2.core.domain.preferences.DarkSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.LightSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.ThemeAccent
import fr.forumhfr.redface2.core.domain.preferences.ThemeColorPreferences
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

/** #595 — interactive contract of Settings > Affichage > Couleurs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-xxhdpi")
class SettingsColorsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val persisted = mutableListOf<ThemeColorPreferences>()

    @Test
    fun `display sub-page opens the colours sub-page`() {
        var opened = 0
        val viewModel = mockk<SettingsViewModel> {
            every { state } returns MutableStateFlow(SettingsState())
            every { submit(any()) } returns Unit
        }

        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                SettingsDisplayScreen(onBack = {}, onOpenColors = { opened++ }, viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithText("Couleurs").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `preset tap dispatches and applies the selected accent`() {
        mountWithReducer()

        composeTestRule.onNodeWithText("Bleu").performScrollTo().performClick()

        assertEquals(
            ThemeColorPreferences(accent = ThemeAccent.Preset(AccentPreset.BLUE)),
            persisted.last(),
        )
    }

    @Test
    fun `invalid custom hex shows the French error without persisting`() {
        mountWithReducer()

        val field = hexField()
        field.performScrollTo()
        field.performTextClearance()
        field.performTextInput("#12GG00")
        field.performImeAction()

        composeTestRule.onNodeWithText("Saisissez une couleur hexa sur 6 caractères, par exemple #A62C2C.")
            .assertExists()
        assertEquals(emptyList<ThemeColorPreferences>(), persisted)
    }

    @Test
    fun `valid custom hex normalizes and persists the custom accent`() {
        mountWithReducer()

        val field = hexField()
        field.performScrollTo()
        field.performTextClearance()
        field.performTextInput("12abef")
        field.performImeAction()

        assertEquals(
            ThemeColorPreferences(accent = ThemeAccent.Custom(rgb = 0x12ABEF)),
            persisted.last(),
        )
        composeTestRule.onNodeWithText("#12ABEF").assertExists()
    }

    @Test
    fun `system colours are visible on Android 12 and keep accent controls disabled when active`() {
        mountScreen(
            SettingsState(
                themeColorPreferences = ThemeColorPreferences(dynamicColorEnabled = true),
            ),
        )

        composeTestRule.onNodeWithText("Couleurs du système").assertExists()
        composeTestRule.onNodeWithText("Bleu").performScrollTo().assertIsNotEnabled()
    }

    @Test
    @Config(sdk = [30], qualifiers = "w360dp-h900dp-xxhdpi")
    fun `system colours are hidden before Android 12`() {
        mountScreen()

        composeTestRule.onNodeWithText("Couleurs du système").assertDoesNotExist()
    }

    @Test
    fun `surface tone groups persist light and dark choices separately`() {
        mountWithReducer()

        composeTestRule.onNodeWithText("Blanc").performScrollTo().performClick()
        composeTestRule.onNodeWithText("AMOLED").performScrollTo().performClick()

        assertEquals(
            ThemeColorPreferences(
                lightSurfaceTone = LightSurfaceTone.WHITE,
                darkSurfaceTone = DarkSurfaceTone.AMOLED,
            ),
            persisted.last(),
        )
    }

    private fun mountScreen(state: SettingsState = SettingsState()) {
        val viewModel = mockk<SettingsViewModel> {
            every { this@mockk.state } returns MutableStateFlow(state)
            every { submit(any()) } returns Unit
        }
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                SettingsColorsScreen(onBack = {}, viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun mountWithReducer(
        initial: SettingsState = SettingsState(),
        environment: SettingsColorsEnvironment = SettingsColorsEnvironment(
            effectiveDark = false,
            systemColorsAvailable = true,
        ),
    ) {
        persisted.clear()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                StatefulColorsContent(initial = initial, environment = environment)
            }
        }
        composeTestRule.waitForIdle()
    }

    @Composable
    private fun StatefulColorsContent(
        initial: SettingsState,
        environment: SettingsColorsEnvironment,
    ) {
        var state by remember { mutableStateOf(initial) }
        SettingsColorsContent(
            state = state,
            callbacks = SettingsColorsCallbacks(
                onBack = {},
                onIntent = { intent ->
                    state = state.reduceForTest(intent) { persisted += it }
                },
            ),
            environment = environment,
        )
    }

    private fun hexField() =
        composeTestRule.onNodeWithContentDescription(SETTINGS_COLORS_CUSTOM_HEX_FIELD_TAG)

    private fun SettingsState.reduceForTest(
        intent: SettingsIntent,
        onPersist: (ThemeColorPreferences) -> Unit,
    ): SettingsState = when (intent) {
        is SettingsIntent.ThemeAccentPresetChanged -> persistForTest(
            themeColorPreferences.copy(accent = ThemeAccent.Preset(intent.preset)),
            onPersist,
        )
        is SettingsIntent.CustomAccentHexChanged -> copy(
            customAccentHexInput = intent.text,
            customAccentHexError = false,
        )
        SettingsIntent.CustomAccentHexCommitted -> commitCustomHexForTest(onPersist)
        is SettingsIntent.LightSurfaceToneChanged -> persistForTest(
            themeColorPreferences.copy(lightSurfaceTone = intent.tone),
            onPersist,
        )
        is SettingsIntent.DarkSurfaceToneChanged -> persistForTest(
            themeColorPreferences.copy(darkSurfaceTone = intent.tone),
            onPersist,
        )
        is SettingsIntent.DynamicColorEnabledChanged -> persistForTest(
            themeColorPreferences.copy(dynamicColorEnabled = intent.enabled),
            onPersist,
        )
        else -> this
    }

    private fun SettingsState.commitCustomHexForTest(
        onPersist: (ThemeColorPreferences) -> Unit,
    ): SettingsState {
        val rgb = parseThemeAccentHexOrNull(customAccentHexInput)
            ?: return copy(customAccentHexError = true)
        return persistForTest(
            themeColorPreferences.copy(accent = ThemeAccent.Custom(rgb = rgb)),
            onPersist,
        ).copy(customAccentHexInput = rgb.toThemeAccentHex(), customAccentHexError = false)
    }

    private fun SettingsState.persistForTest(
        preferences: ThemeColorPreferences,
        onPersist: (ThemeColorPreferences) -> Unit,
    ): SettingsState {
        onPersist(preferences)
        return copy(themeColorPreferences = preferences, themeColorsError = false)
    }
}
