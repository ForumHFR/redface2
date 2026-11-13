package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import coil3.SingletonImageLoader
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1509 / #1450 — record-only baseline of the integrated post chrome and new-topic form.
 * Uses the real content and routing fixtures from [EditorScreenLayoutTest], without a ViewModel.
 * Reply represents the shared reply/edit post chrome; transient states use the production adapter.
 * Post: portrait/compact × light/dark × scale 1/1.3 × normal/format/arm/event/restore/cards/panel/preview/options.
 * OptionsFormats uses separate methods per orientation/scale so an older reference failure cannot skip it.
 * Topic: both modes, header, poll, restore, preview, options, event, arm and app bar states (poll + restore, no tap);
 * the post and topic Restore/Options scenes open their sheet by its tap and capture its dialog window;
 * refused/missing FP context; historical names kept, each byte-identical capture recorded under one name only.
 *
 * Output: `feature/editor/build/outputs/roborazzi/<surface>_<orientation>_fs<scale>_<state>.png`.
 * This harness deliberately uses no M3 1.5 API so it can also be applied before the pin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h760dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, coil3.annotation.DelicateCoilApi::class)
class EditorBaselineRoborazziTest {

    @get:Rule
    val compose = createComposeRule()

    // Install before composing any scene, including the Options sheet's tone grid.
    @Before
    fun installImages() = installMessageIconPickerTestImages()

    @After
    fun resetImageLoader() = SingletonImageLoader.reset()

    // A ModalBottomSheet composes in its own dialog window: its density follows the system configuration,
    // as in production, never the host LocalDensity. Tests at a font scale set both, then reset the system.
    @After fun resetSystemFontScale() = RuntimeEnvironment.setFontScale(1f)

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

    @Test
    fun optionsFormatsPortraitFontScale1_0() = captureMatrix("portrait", 1f, formatsOnly = true)

    @Test
    fun optionsFormatsPortraitFontScale1_3() = captureMatrix("portrait", 1.3f, formatsOnly = true)

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun optionsFormatsLandscapeFontScale1_0() = captureMatrix("landscape", 1f, formatsOnly = true)

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun optionsFormatsLandscapeFontScale1_3() = captureMatrix("landscape", 1.3f, formatsOnly = true)

    private fun captureMatrix(orientation: String, fontScale: Float, formatsOnly: Boolean = false) {
        val scenario = mutableStateOf(Scenario(Surface.NewTopic, filled = false))
        val dark = mutableStateOf(false)
        lateinit var view: View
        var pixelsPerDp = 1f
        lateinit var focusManager: FocusManager
        lateinit var previewLabel: String
        RuntimeEnvironment.setFontScale(fontScale) // Before any sheet's dialog window is created.
        compose.setContent {
            view = LocalView.current
            focusManager = LocalFocusManager.current
            previewLabel = stringResource(UiR.string.editor_preview_label)
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

        if (!formatsOnly) {
            listOf(false, true).forEach { filled ->
                compose.runOnIdle { scenario.value = Scenario(Surface.NewTopic, filled) }
                record("new_topic_${orientation}_fs${fontScale}_${if (filled) "multiline" else "empty"}",
                    orientation, focusManager)
            }
            captureTopics(orientation, fontScale, scenario, dark, focusManager, previewLabel)
        }
        val scenes = if (formatsOnly) listOf(PostScene.OptionsFormats)
        else PostScene.entries.filter { it != PostScene.OptionsFormats }
        listOf(false, true).forEach { isDark ->
            scenes.forEach { scene ->
                compose.runOnIdle {
                    dark.value = isDark
                    scenario.value = Scenario(Surface.Post, filled = true, scene = scene)
                    val ime = if (orientation == "landscape") (190 * pixelsPerDp).toInt() else 0
                    ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                        .setVisible(WindowInsetsCompat.Type.ime(), ime > 0).build())
                }
                compose.waitForIdle()
                openPostScene(scene, previewLabel)
                record("post_${scene.name}_${orientation}_fs${fontScale}_dark$isDark", orientation, focusManager,
                    sheet = scene in setOf(PostScene.Restore, PostScene.Options, PostScene.OptionsFormats))
            }
        }
    }

    private fun openPostScene(scene: PostScene, previewLabel: String) {
        when (scene) {
            PostScene.Options, PostScene.OptionsFormats -> openPostOptions(scene)
            PostScene.Format -> compose.onNodeWithContentDescription("Mise en forme").performClick()
            PostScene.Restore -> compose.onNodeWithContentDescription(
                "Brouillon retrouvé, restaurer ou ignorer").performClick()
            PostScene.Panel -> compose.onNodeWithContentDescription(
                "3 citations, ouvrir le panneau citations").performClick()
            PostScene.Preview -> compose.onNodeWithContentDescription(previewLabel).assertIsDisplayed().performClick()
            else -> Unit
        }
        if (scene == PostScene.Preview) assertSharedPreview()
    }

    private fun openPostOptions(scene: PostScene) {
        compose.onNodeWithContentDescription("Options").performClick()
        if (scene == PostScene.OptionsFormats) {
            compose.onNodeWithTag("editor-options-tab-Formats").performClick()
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
                    TopicScene.Options -> compose.onNodeWithContentDescription("Options").performClick()
                    TopicScene.Restore -> compose.onNodeWithContentDescription(
                        "Brouillon retrouvé, restaurer ou ignorer").performClick()
                    TopicScene.Preview -> compose.onNodeWithContentDescription(previewLabel).performClick()
                    else -> Unit // Normal, EmptyTitle, Event, Armed and States are recorded without a tap.
                }
                if (scene == TopicScene.Preview) assertSharedPreview()
                val name = "${surface.fileName}_${scene.name}_${orientation}_fs${fontScale}_dark$isDark"
                record(name, orientation, focus, sheet = scene in setOf(TopicScene.Restore, TopicScene.Options))
            }
        }
    }

    /**
     * Gate r1 (R1b T3) found 40 byte-identical topic captures; each image now keeps one unchanged name.
     * Header and Poll panels match across modes; the Restore sheet is the same composable in both modes and is
     * recorded on the new topic only. Landscape hides the app bar: there the first post
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
            TopicScene.Header, TopicScene.Restore, TopicScene.Options, TopicScene.Event, TopicScene.Armed -> newTopic
            TopicScene.Poll, TopicScene.States, TopicScene.Unsupported, TopicScene.MissingRoute -> !newTopic
        }
    }

    private fun assertSharedPreview() {
        compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
        compose.onNodeWithTag("editor-preview-divider-target").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    /** The ↺ offer is a ModalBottomSheet: it renders in its own dialog window, absent from [onRoot]. */
    private fun record(name: String, orientation: String, focus: FocusManager, sheet: Boolean = false) {
        compose.waitForIdle()
        compose.runOnIdle { focus.clearFocus(force = true) }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val target = if (sheet) compose.onNode(isDialog()) else compose.onRoot()
            .assertWidthIsEqualTo(if (orientation == "portrait") 360.dp else 760.dp)
            .assertHeightIsEqualTo(if (orientation == "portrait") 760.dp else 360.dp)
        target.captureRoboImage(filePath = "src/test/screenshots/$name.png")
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
                    draftOffers = if (scenario.scene == PostScene.Restore) baselineOffers() else emptyList(),
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
            draftOffers = if (scene in setOf(TopicScene.Restore, TopicScene.States)) baselineOffers() else emptyList(),
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

    // A relative date (« il y a 5 min ») keeps the capture independent of the host time zone and day.
    private fun baselineOffers() = listOf(EditorDraftStore.DraftOffer("baseline", 1,
        EditorDraftStore.Draft("[b]Brouillon conservé[/b]", updatedAt = System.currentTimeMillis() - 301_000L)))

    private enum class PostScene {
        Normal, Format, Armed, Event, Restore, Cards, Panel, Preview, Options, OptionsFormats,
    }
    private enum class TopicScene {
        Normal, EmptyTitle, Header, Poll, Restore, Preview, Unsupported, MissingRoute, Event, Armed, States, Options,
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
