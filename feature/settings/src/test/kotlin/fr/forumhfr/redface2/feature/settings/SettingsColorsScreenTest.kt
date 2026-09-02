package fr.forumhfr.redface2.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
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
    fun `preset custom field is empty with seed placeholder and Done does not persist`() {
        mountWithReducer()

        hexField().performScrollTo().performImeAction()

        assertEquals(emptyList<ThemeColorPreferences>(), persisted)
        // M3 shows the placeholder of a labelled field only while it is focused.
        hexField().requestFocus()
        composeTestRule.onNodeWithText("#A62C2C").assertExists()
    }

    @Test
    fun `preset tap clears custom text and updates the seed placeholder`() {
        mountWithReducer(
            initial = SettingsState(
                themeColorPreferences = ThemeColorPreferences(accent = ThemeAccent.Custom(rgb = 0x12ABEF)),
                customAccentHexInput = "#12ABEF",
                customAccentHexSyncedInput = "#12ABEF",
            ),
        )

        composeTestRule.onNodeWithText("Bleu").performScrollTo().performClick()

        assertEquals(
            ThemeColorPreferences(accent = ThemeAccent.Preset(AccentPreset.BLUE)),
            persisted.last(),
        )
        composeTestRule.onNodeWithText("#12ABEF").assertDoesNotExist()
        // M3 shows the placeholder of a labelled field only while it is focused.
        hexField().requestFocus()
        composeTestRule.onNodeWithText("#1976D2").assertExists()
    }

    @Test
    fun `custom hex swatch follows the typed text`() {
        mountWithReducer()

        hexField().performScrollTo().performTextInput("12abef")

        composeTestRule.onNodeWithTag(SETTINGS_COLORS_CUSTOM_HEX_SWATCH_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SettingsColorsCustomHexSwatchRgbKey, 0x12ABEF))
        assertEquals(emptyList<ThemeColorPreferences>(), persisted)
    }

    @Test
    fun `preview post uses the reading-card container token`() {
        mountWithReducer()

        composeTestRule.onNodeWithTag(SETTINGS_COLOR_PREVIEW_POST_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SettingsColorPreviewPostContainerColorKey, Color.White))
    }

    @Test
    fun `preview reading tokens follow Rouge RF1 dark reading surfaces`() {
        val expected = mountPreviewForTokenAssertions(
            preferences = ThemeColorPreferences(
                accent = ThemeAccent.Preset(AccentPreset.ROUGE_REDFACE1),
            ),
            darkTheme = true,
        )

        assertPreviewReadingTokens(expected)
    }

    @Test
    fun `preview reading tokens follow Rouge RF1 light reading surfaces`() {
        val expected = mountPreviewForTokenAssertions(
            preferences = ThemeColorPreferences(
                accent = ThemeAccent.Preset(AccentPreset.ROUGE_REDFACE1),
            ),
            darkTheme = false,
        )

        assertPreviewReadingTokens(expected)
    }

    @Test
    fun `preview spoiler uses the AMOLED bright surface branch`() {
        val expected = mountPreviewForTokenAssertions(
            preferences = ThemeColorPreferences(
                accent = ThemeAccent.Preset(AccentPreset.ROUGE_REDFACE1),
                darkSurfaceTone = DarkSurfaceTone.AMOLED,
            ),
            darkTheme = true,
        )

        assertEquals(expected.surfaceBright, expected.spoilerContainer)
        composeTestRule.onNodeWithTag(SETTINGS_COLOR_PREVIEW_SPOILER_TAG, useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SettingsColorPreviewSpoilerContainerColorKey,
                    expected.spoilerContainer,
                ),
            )
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

    private fun mountPreviewForTokenAssertions(
        preferences: ThemeColorPreferences,
        darkTheme: Boolean,
    ): PreviewExpectedColors {
        var expected: PreviewExpectedColors? = null
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = darkTheme, themeColorPreferences = preferences) {
                expected = previewExpectedColors()
            }
            SettingsColorPreview(preferences = preferences, darkTheme = darkTheme)
        }
        composeTestRule.waitForIdle()
        return checkNotNull(expected)
    }

    private fun assertPreviewReadingTokens(expected: PreviewExpectedColors) {
        composeTestRule.onNodeWithTag(SETTINGS_COLOR_PREVIEW_HEADER_TAG, useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SettingsColorPreviewHeaderContainerColorKey,
                    expected.headerContainer,
                ),
            )
        composeTestRule.onNodeWithTag(SETTINGS_COLOR_PREVIEW_QUOTE_TAG, useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SettingsColorPreviewQuoteContainerColorKey,
                    expected.quoteContainer,
                ),
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SettingsColorPreviewQuoteAccentColorKey,
                    expected.quoteAccent,
                ),
            )
        composeTestRule.onNodeWithTag(SETTINGS_COLOR_PREVIEW_SPOILER_TAG, useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SettingsColorPreviewSpoilerContainerColorKey,
                    expected.spoilerContainer,
                ),
            )
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
        if (customAccentHexInput == customAccentHexSyncedInput) return this
        val rgb = parseThemeAccentHexOrNull(customAccentHexInput)
        return when {
            rgb == null -> copy(customAccentHexError = true)
            rgb == themeColorPreferences.accent.effectiveRgbForTest() -> {
                val syncedInput = themeColorPreferences.customAccentSyncedInputForTest()
                copy(
                    customAccentHexInput = syncedInput,
                    customAccentHexSyncedInput = syncedInput,
                    customAccentHexError = false,
                )
            }
            else -> persistForTest(
                themeColorPreferences.copy(accent = ThemeAccent.Custom(rgb = rgb)),
                onPersist,
            )
        }
    }

    private fun SettingsState.persistForTest(
        preferences: ThemeColorPreferences,
        onPersist: (ThemeColorPreferences) -> Unit,
    ): SettingsState {
        if (preferences == themeColorPreferences) return this
        onPersist(preferences)
        val accentChanged = preferences.accent != themeColorPreferences.accent
        val syncedInput = preferences.customAccentSyncedInputForTest()
        return copy(
            themeColorPreferences = preferences,
            customAccentHexInput = if (accentChanged) syncedInput else customAccentHexInput,
            customAccentHexSyncedInput = if (accentChanged) syncedInput else customAccentHexSyncedInput,
            customAccentHexError = false,
            themeColorsError = false,
        )
    }

    private fun ThemeColorPreferences.customAccentSyncedInputForTest(): String =
        when (val accent = accent) {
            is ThemeAccent.Custom -> accent.rgb.toThemeAccentHex()
            is ThemeAccent.Preset -> ""
        }

    private fun ThemeAccent.effectiveRgbForTest(): Int = when (this) {
        is ThemeAccent.Custom -> rgb
        is ThemeAccent.Preset -> preset.seedRgb
    }

    private data class PreviewExpectedColors(
        val headerContainer: Color,
        val quoteContainer: Color,
        val quoteAccent: Color,
        val spoilerContainer: Color,
        val surfaceBright: Color,
    )

    // Reads MaterialTheme in place: the Konsist rule keeps the material3 ColorScheme type in core ui.
    @Composable
    private fun previewExpectedColors(): PreviewExpectedColors {
        val scheme = MaterialTheme.colorScheme
        return PreviewExpectedColors(
            headerContainer = scheme.secondaryContainer,
            quoteContainer = scheme.surfaceContainerHighest,
            quoteAccent = scheme.primary,
            spoilerContainer = if (scheme.surface == Color.Black) {
                scheme.surfaceBright
            } else {
                scheme.surfaceContainerLow
            },
            surfaceBright = scheme.surfaceBright,
        )
    }
}
