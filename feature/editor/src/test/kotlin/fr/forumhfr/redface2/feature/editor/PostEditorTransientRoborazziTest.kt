package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.testing.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Integrated post screen for quote panels; shared preview rendered separately with the real source field. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h760dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostEditorTransientRoborazziTest {
    @get:Rule val compose = createComposeRule()

    @Test fun portraitScale1() = captureMatrix(false, 1f)
    @Test fun portraitScale13() = captureMatrix(false, 1.3f)
    @Test @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun compactScale1() = captureMatrix(true, 1f)
    @Test @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun compactScale13() = captureMatrix(true, 1.3f)

    private fun captureMatrix(compact: Boolean, fontScale: Float) {
        val scenario = mutableStateOf(false to false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                val (dark, preview) = scenario.value
                RedfaceTheme(darkTheme = dark, amoledTheme = false, dynamicColor = false) {
                    key(scenario.value) {
                        val host = remember { transientHost() }
                        val focus = remember { FocusRequester() }
                        if (preview) PostEditorPreview(true, PostContent(listOf(
                            PostBlock.Paragraph(listOf(PostInline.Text("Aperçu du message avec une citation."))),
                        )), host.body, focus) {
                            BbcodeTextField(host.body, "Message", Modifier.fillMaxSize().focusRequester(focus),
                                showKeyboardOnFocus = false)
                        } else PostEditorTestContent(host.state, host::dispatch, host.body, host.events, host.memory)
                    }
                }
            }
        }
        listOf(false, true).filter { !it || fontScale == 1f }.forEach { dark ->
            listOf(false, true).forEach { preview ->
                compose.runOnIdle { scenario.value = dark to preview }
                compose.waitForIdle()
                if (preview) compose.onNodeWithTag("editor-preview-divider-target").assertIsDisplayed()
                val direction = if (compact) "compact" else "portrait"
                val suffix = "${direction}_fs${fontScale}_dark$dark"
                if (preview) capture("post_preview_$suffix") else {
                    capture("post_cards_$suffix")
                    compose.onNodeWithContentDescription("3 citations, ouvrir le panneau citations").performClick()
                    capture("post_quotes_$suffix")
                    compose.onNodeWithText("Tout vider").performClick()
                    capture("post_quotes_empty_$suffix")
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/$name.png")
        compose.mainClock.autoAdvance = true
    }
}
