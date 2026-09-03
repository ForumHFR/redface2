package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.domain.preferences.AccentPreset
import fr.forumhfr.redface2.core.domain.preferences.DarkSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.LightSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.ThemeAccent
import fr.forumhfr.redface2.core.domain.preferences.ThemeColorPreferences
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #595 — record-only Roborazzi captures of the live colour preview. PNGs land under
 * `feature/settings/build/outputs/roborazzi/` and stay unversioned.
 *
 *     ./scripts/docker-dev.sh ./gradlew :feature:settings:testDebugUnitTest \
 *         --tests '*SettingsColorPreviewRoborazziTest*' --console=plain
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class SettingsColorPreviewRoborazziTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun previewRoseLightRf1Gray() {
        capture(
            preferences = ThemeColorPreferences(accent = ThemeAccent.Preset(AccentPreset.ROSE)),
            darkTheme = false,
            name = "settings_color_preview_rose_light_rf1_gray",
        )
    }

    @Test
    fun previewRougeRedface1LightRf1Gray() {
        capture(
            preferences = ThemeColorPreferences(accent = ThemeAccent.Preset(AccentPreset.ROUGE_REDFACE1)),
            darkTheme = false,
            name = "settings_color_preview_rouge_redface1_light_rf1_gray",
        )
    }

    @Test
    fun previewBlueLightRf1Gray() {
        capture(
            preferences = ThemeColorPreferences(accent = ThemeAccent.Preset(AccentPreset.BLUE)),
            darkTheme = false,
            name = "settings_color_preview_blue_light_rf1_gray",
        )
    }

    @Test
    fun previewNeutralLightRf1Gray() {
        capture(
            preferences = ThemeColorPreferences(accent = ThemeAccent.Preset(AccentPreset.NEUTRAL)),
            darkTheme = false,
            name = "settings_color_preview_neutral_light_rf1_gray",
        )
    }

    @Test
    fun previewCustomLightRf1Gray() {
        capture(
            preferences = customPreferences,
            darkTheme = false,
            name = "settings_color_preview_custom_light_rf1_gray",
        )
    }

    @Test
    fun previewCustomLightWhite() {
        capture(
            preferences = customPreferences.copy(lightSurfaceTone = LightSurfaceTone.WHITE),
            darkTheme = false,
            name = "settings_color_preview_custom_light_white",
        )
    }

    @Test
    fun previewCustomDarkMaterialTinted() {
        capture(
            preferences = customPreferences,
            darkTheme = true,
            name = "settings_color_preview_custom_dark_material_tinted",
        )
    }

    @Test
    fun previewCustomDarkAmoled() {
        capture(
            preferences = customPreferences.copy(darkSurfaceTone = DarkSurfaceTone.AMOLED),
            darkTheme = true,
            name = "settings_color_preview_custom_dark_amoled",
        )
    }

    private fun capture(
        preferences: ThemeColorPreferences,
        darkTheme: Boolean,
        name: String,
    ) {
        composeTestRule.setContent {
            SettingsColorPreview(preferences = preferences, darkTheme = darkTheme)
        }
        composeTestRule.onNodeWithContentDescription(SETTINGS_COLOR_PREVIEW_TAG)
            .captureRoboImage(filePath = "build/outputs/roborazzi/$name.png")
    }

    private companion object {
        val customPreferences = ThemeColorPreferences(accent = ThemeAccent.Custom(rgb = 0x12ABEF))
    }
}
