package fr.forumhfr.redface2.feature.editor

import android.view.View
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings
import fr.forumhfr.redface2.testing.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1509 / #1450 — record-only baseline of the integrated post chrome and new-topic form.
 * Uses the real content and routing fixtures from [EditorScreenLayoutTest], without a ViewModel.
 * Reply represents the shared reply/edit post chrome; transient states use the production adapter.
 * Post: portrait/compact × light/dark × scale 1/1.3 × normal/format/arm/event/restore/cards/panel/preview.
 * Topic: both modes, header, poll, restore, preview, event, arm and app bar states (poll + restore, no tap);
 * refused/missing FP context; historical names kept, each byte-identical capture recorded under one name only.
 *
 * Output: `feature/editor/build/outputs/roborazzi/<surface>_<orientation>_fs<scale>_<state>.png`.
 * This harness deliberately uses no M3 1.5 API so it can also be applied before the pin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h760dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class EditorBaselineRoborazziTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun portraitFontScale1_0() = captureMatrix(orientation = "portrait", fontScale = 1.0f)

    @Test
    fun portraitFontScale1_3() = captureMatrix(orientation = "portrait", fontScale = 1.3f)

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun landscapeFontScale1_0() = captureMatrix(orientation = "landscape", fontScale = 1.0f)

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun landscapeFontScale1_3() = captureMatrix(orientation = "landscape", fontScale = 1.3f)

    private fun captureMatrix(orientation: String, fontScale: Float) {
        val scenario = mutableStateOf(Scenario(Surface.NewTopic, filled = false))
        val dark = mutableStateOf(false)
        lateinit var view: View
        var pixelsPerDp = 1f
        lateinit var focusManager: FocusManager
        lateinit var previewLabel: String
        compose.setContent {
            view = LocalView.current
            focusManager = LocalFocusManager.current
            previewLabel = stringResource(UiR.string.editor_preview_show)
            pixelsPerDp = LocalDensity.current.density
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = fontScale),
            ) {
                RedfaceTheme(darkTheme = dark.value, amoledTheme = false, dynamicColor = false,
                    reading = ReadingDisplaySettings(fontScale = FontScalePreference.L)) {
                    val current = scenario.value
                    // Recreate field/scroll/picker state for every capture, never inherit a caret
                    // position or a controls offset from the preceding surface or draft. The theme is
                    // part of the key: topic scenes capture one scenario in light then dark, and the
                    // panel opened by the light pass would otherwise stay open over the dark pass.
                    key(current, dark.value) {
                        EditorChrome(current)
                    }
                }
            }
        }

        listOf(false, true).forEach { filled ->
            compose.runOnIdle { scenario.value = Scenario(Surface.NewTopic, filled) }
            record("new_topic_${orientation}_fs${fontScale}_${if (filled) "multiline" else "empty"}",
                orientation, focusManager)
        }
        captureTopics(orientation, fontScale, scenario, dark, focusManager, previewLabel)
        listOf(false, true).forEach { isDark ->
            PostScene.entries.filter { orientation != "portrait" || it != PostScene.Panel }.forEach { scene ->
                compose.runOnIdle {
                    dark.value = isDark
                    scenario.value = Scenario(Surface.Post, filled = true, scene = scene)
                    val ime = if (orientation == "landscape") (190 * pixelsPerDp).toInt() else 0
                    ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                        .setVisible(WindowInsetsCompat.Type.ime(), ime > 0).build())
                }
                compose.waitForIdle()
                when (scene) {
                    PostScene.Format -> compose.onNodeWithContentDescription("Mise en forme").performClick()
                    PostScene.Restore -> compose.onNodeWithContentDescription(
                        "Brouillon retrouvé, restaurer ou ignorer").performClick()
                    PostScene.Panel -> compose.onNodeWithContentDescription(
                        "3 citations, ouvrir le panneau citations").performClick()
                    PostScene.Preview -> compose.onNodeWithContentDescription(previewLabel)
                        .assertIsDisplayed().performClick()
                    else -> Unit
                }
                record("post_${scene.name}_${orientation}_fs${fontScale}_dark$isDark", orientation, focusManager)
            }
        }
    }

    @Suppress("LongParameterList") // Capture environment and independently varied topic scenarios.
    private fun captureTopics(
        orientation: String,
        fontScale: Float,
        scenario: androidx.compose.runtime.MutableState<Scenario>,
        dark: androidx.compose.runtime.MutableState<Boolean>,
        focus: FocusManager,
        previewLabel: String,
    ) {
        for (surface in listOf(Surface.NewTopic, Surface.FirstPost)) {
            captureTopicSurface(surface, orientation, fontScale, scenario, dark, focus, previewLabel)
        }
    }

    @Suppress("LongParameterList") // One surface plus the same capture environment as captureTopics.
    private fun captureTopicSurface(
        surface: Surface,
        orientation: String,
        fontScale: Float,
        scenario: androidx.compose.runtime.MutableState<Scenario>,
        dark: androidx.compose.runtime.MutableState<Boolean>,
        focus: FocusManager,
        previewLabel: String,
    ) {
        for (scene in TopicScene.entries) {
            for (isDark in listOf(false, true).filter { scene.recorded(surface, orientation, it) }) {
                compose.runOnIdle {
                    dark.value = isDark
                    scenario.value = Scenario(surface, filled = true, topicScene = scene)
                }
                compose.waitForIdle()
                when (scene) {
                    TopicScene.Header, TopicScene.Unsupported, TopicScene.MissingRoute ->
                        compose.onNodeWithContentDescription("Modifier le titre", substring = true).performClick()
                    TopicScene.Poll -> compose.onNodeWithContentDescription("Sondage présent, consulter")
                        .performClick()
                    TopicScene.Restore -> compose.onNodeWithContentDescription(
                        "Brouillon retrouvé, restaurer ou ignorer").performClick()
                    TopicScene.Preview -> compose.onNodeWithContentDescription(previewLabel).performClick()
                    else -> Unit // Normal, EmptyTitle, Event, Armed and States are recorded without a tap.
                }
                val name = "${surface.fileName}_${scene.name}_${orientation}_fs${fontScale}_dark$isDark"
                record(name, orientation, focus)
            }
        }
    }

    /**
     * Gate r1 (R1b T3) found 40 byte-identical topic captures; each image now keeps one unchanged name.
     * Header, Poll and Restore panels match across modes. Landscape hides the app bar: there the first post
     * Normal and EmptyTitle match the new topic Normal (only creation auto-opens the header on an empty title)
     * and Preview matches across modes. The light new topic Normal is the historical
     * `new_topic_<orientation>_fs<scale>_multiline`. Event and Armed only change the action bar, as on the
     * post's single surface; an existing poll (Poll, States) only occurs when editing a first post.
     */
    private fun TopicScene.recorded(surface: Surface, orientation: String, isDark: Boolean): Boolean {
        val newTopic = surface == Surface.NewTopic
        return when (this) {
            TopicScene.Normal -> if (newTopic) isDark else orientation == "portrait"
            TopicScene.EmptyTitle, TopicScene.Preview -> newTopic || orientation == "portrait"
            TopicScene.Header, TopicScene.Restore, TopicScene.Event, TopicScene.Armed -> newTopic
            TopicScene.Poll, TopicScene.States, TopicScene.Unsupported, TopicScene.MissingRoute -> !newTopic
        }
    }

    private fun record(name: String, orientation: String, focus: FocusManager) {
        compose.waitForIdle()
        compose.runOnIdle { focus.clearFocus(force = true) }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onRoot().assertWidthIsEqualTo(if (orientation == "portrait") 360.dp else 760.dp)
            .assertHeightIsEqualTo(if (orientation == "portrait") 760.dp else 360.dp)
            .captureRoboImage(filePath = "src/test/screenshots/$name.png")
        compose.mainClock.autoAdvance = true
    }

    @Composable
    private fun EditorChrome(scenario: Scenario) {
        val draft = TextFieldValue(if (scenario.filled) MULTILINE_DRAFT else "")
        when (scenario.surface) {
            Surface.Post -> {
                val host = remember { PostEditorChromeFixture() }
                val quotes = (1..3).map { QuoteSelection(QuoteLocator(1, it, null), "Auteur $it", "Citation $it") }
                // Returns the host: a Unit-returning remember is rejected by lint (RememberReturnType).
                remember(scenario) { host.apply { state = state.copy(bodyText = draft.text,
                    preview = PostContent(listOf(PostBlock.Paragraph(listOf(PostInline.Text("Aperçu du message."))))),
                    restorableDraft = if (scenario.scene == PostScene.Restore) "Brouillon conservé" else null,
                    quotes = if (scenario.scene in setOf(PostScene.Cards, PostScene.Panel)) quotes else emptyList(),
                    submission = if (scenario.scene == PostScene.Armed) EditorSubmissionState.Armed(
                        ArmToken(1, host.session, Long.MAX_VALUE / 4, 60_000L)) // Far-future arm: frozen countdown.
                    else EditorSubmissionState.Ready,
                    submitError = if (scenario.scene == PostScene.Event) EditorErrors.formNetwork else null) } }
                val body = remember { TextFieldState(draft.text, draft.selection) }
                PostEditorTestContent(host.state, host::dispatch, body, host.events)
            }
            Surface.NewTopic, Surface.FirstPost -> {
                val host = remember { TopicFormChromeFixture(if (scenario.surface == Surface.NewTopic)
                    TopicFormMode.New else TopicFormMode.EditFirstPost) }
                remember(scenario) { stageTopic(host, scenario.topicScene, draft.text) }
                val body = remember { TextFieldState(draft.text, draft.selection) }
                TopicFormTestContent(host.state, host::dispatch, body, host.events, host.memory)
            }
        }
    }

    /**
     * Stages one topic scene and returns the host (RememberReturnType). The content renders the host's event
     * store without projecting state, so the Event scene delivers its error through that store, like the VM.
     */
    private fun stageTopic(host: TopicFormChromeFixture, scene: TopicScene, draft: String): TopicFormChromeFixture {
        host.state = host.state.copy(
            subject = TextFieldValue(if (scene == TopicScene.EmptyTitle) "" else "Sujet de démonstration"),
            bodyText = draft,
            preview = PostContent(listOf(PostBlock.Paragraph(listOf(PostInline.Text("Aperçu du sujet."))))),
            restorableDraft = "Brouillon conservé".takeIf { scene in setOf(TopicScene.Restore, TopicScene.States) },
            pollPresent = scene in setOf(TopicScene.Poll, TopicScene.States),
            firstPostSubcategoryRefused = scene == TopicScene.Unsupported,
            subcat = if (scene == TopicScene.MissingRoute) null else 1,
            hasSubcategorySelect = scene != TopicScene.MissingRoute,
            submission = if (scene == TopicScene.Armed) EditorSubmissionState.Armed(
                ArmToken(1, host.session, Long.MAX_VALUE / 4, 60_000L)) // Far-future arm: frozen countdown.
            else EditorSubmissionState.Ready,
        )
        if (scene == TopicScene.Event) host.error(EditorErrors.formNetwork)
        return host
    }

    private enum class PostScene { Normal, Format, Armed, Event, Restore, Cards, Panel, Preview }
    private enum class TopicScene {
        Normal, EmptyTitle, Header, Poll, Restore, Preview, Unsupported, MissingRoute, Event, Armed, States,
    }
    private data class Scenario(
        val surface: Surface, val filled: Boolean, val scene: PostScene = PostScene.Normal,
        val topicScene: TopicScene = TopicScene.Normal,
    )

    private enum class Surface(val fileName: String) {
        Post("post_reply"),
        NewTopic("new_topic"),
        FirstPost("edit_first_post"),
    }

    private companion object {
        // Longer than the tallest draft viewport, with no dates, images or remote content.
        val MULTILINE_DRAFT = (1..24).joinToString("\n") { line ->
            "Ligne $line — Texte de démonstration pour remplir le champ et observer le retour à la ligne."
        }
    }
}
