package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.domain.preferences.AccentPreset
import fr.forumhfr.redface2.core.domain.preferences.LightSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.ThemeAccent
import fr.forumhfr.redface2.core.domain.preferences.ThemeColorPreferences
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1297 — theme wiring and record-only comparison of the same post/quotes on two light tones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostTileOutlineRoborazziTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun whiteLightTilesHaveOutlines() {
        capture(LightSurfaceTone.WHITE, "post_tile_outline_white")
    }

    @Test
    fun materialTintedTilesHaveNoAutomaticOutline() {
        capture(LightSurfaceTone.MATERIAL_TINTED, "post_tile_outline_material_tinted")
    }

    @Test
    fun outlineTracksSurfaceToneDarkThemeAndAccentChanges() {
        val preferences = mutableStateOf(ThemeColorPreferences(lightSurfaceTone = LightSurfaceTone.WHITE))
        val darkTheme = mutableStateOf(false)
        var outlineColor = Color.Unspecified
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = darkTheme.value, themeColorPreferences = preferences.value) {
                outlineColor = MaterialTheme.colorScheme.outlineVariant
                SamplePost()
            }
        }
        assertOutline(1f, outlineColor)

        listOf(LightSurfaceTone.MATERIAL_TINTED, LightSurfaceTone.REDFACE1_GRAY).forEach { tone ->
            composeTestRule.runOnIdle { preferences.value = preferences.value.copy(lightSurfaceTone = tone) }
            assertNoOutline()
        }

        composeTestRule.runOnIdle {
            preferences.value = preferences.value.copy(lightSurfaceTone = LightSurfaceTone.WHITE)
        }
        composeTestRule.waitForIdle()
        assertOutline(1f, outlineColor)

        composeTestRule.runOnIdle {
            preferences.value = preferences.value.copy(accent = ThemeAccent.Preset(AccentPreset.BLUE))
        }
        composeTestRule.waitForIdle()
        assertOutline(1f, outlineColor)

        composeTestRule.runOnIdle { darkTheme.value = true }
        assertNoOutline()
        composeTestRule.runOnIdle { darkTheme.value = false }
        composeTestRule.waitForIdle()
        assertOutline(1f, outlineColor)
    }

    @Test
    fun explicitBorderTakesPrecedenceOverWhiteOutline() {
        var selectionColor = Color.Unspecified
        composeTestRule.setContent {
            RedfaceTheme(
                darkTheme = false,
                themeColorPreferences = ThemeColorPreferences(lightSurfaceTone = LightSurfaceTone.WHITE),
            ) {
                selectionColor = MaterialTheme.colorScheme.primary
                SamplePost(border = BorderStroke(2.dp, selectionColor))
            }
        }

        assertOutline(2f, selectionColor)
    }

    @Test
    fun flatWhitePostKeepsItsHairlineInsteadOfAnAutomaticOutline() {
        composeTestRule.setContent {
            RedfaceTheme(
                darkTheme = false,
                themeColorPreferences = ThemeColorPreferences(lightSurfaceTone = LightSurfaceTone.WHITE),
            ) {
                SamplePost(flat = true)
            }
        }

        assertNoOutline()
        composeTestRule.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true).assertExists()
    }

    private fun capture(tone: LightSurfaceTone, name: String) {
        var outlineColor = Color.Unspecified
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, themeColorPreferences = ThemeColorPreferences(lightSurfaceTone = tone)) {
                outlineColor = MaterialTheme.colorScheme.outlineVariant
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(modifier = Modifier.width(360.dp).padding(12.dp)) {
                        SamplePost()
                    }
                }
            }
        }
        if (tone == LightSurfaceTone.WHITE) {
            assertOutline(1f, outlineColor)
        } else {
            assertNoOutline()
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "build/outputs/roborazzi/$name.png")
    }

    private fun assertOutline(width: Float, color: Color) {
        composeTestRule.onNodeWithTag(POST_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PostCardShellBorderWidthKey, width))
            .assert(SemanticsMatcher.expectValue(PostCardShellBorderColorKey, SolidColor(color)))
    }

    private fun assertNoOutline() {
        composeTestRule.onNodeWithTag(POST_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(PostCardShellBorderWidthKey))
            .assert(SemanticsMatcher.keyNotDefined(PostCardShellBorderColorKey))
    }

    @Composable
    private fun SamplePost(flat: Boolean = false, border: BorderStroke? = null) {
        PostCardShell(
            modifier = Modifier.testTag(POST_TAG),
            flat = flat,
            border = border,
            header = {
                PostIdentityBand(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        text = "MonPseudo — 07/09/2026 10:00:00",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            },
            body = {
                PostRenderer(content = sampleContent(), modifier = Modifier.padding(12.dp))
            },
        )
    }

    private fun sampleContent() = PostContent(
        blocks = listOf(
            paragraph("Les contours délimitent le post et ses citations sur fond blanc."),
            PostBlock.Quote(
                author = "AutrePseudo",
                numreponse = null,
                page = null,
                content = PostContent(listOf(paragraph("Une citation avec sa barre d'accent."))),
            ),
            PostBlock.Quote(
                author = null,
                numreponse = null,
                page = null,
                content = PostContent(listOf(paragraph("Une citation libre avec sa barre neutre."))),
            ),
            paragraph("Le texte et les arrondis gardent leur disposition habituelle."),
        ),
    )

    private fun paragraph(text: String) = PostBlock.Paragraph(listOf(PostInline.Text(text)))

    private companion object {
        const val POST_TAG = "OutlinedPost"
    }
}
