package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientState
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.testing.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Actual reply and new-MP content on the common scaffold.
 * Reply: portrait/landscape × font scale 1.0/1.3 × light/dark × empty/multiline, plus restore/event/preview/
 * owner states, an armed send, both statuses at rest and under an event, and the full-screen form failure.
 * Light names are historical; dark reply names end in `_dark`. The new-MP matrix bodies stay light only.
 * Deterministic local text only. PNG references are explicitly recorded by the maintainer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h760dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class EditorBaselineRoborazziTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun portraitFontScale1_0() = captureMatrix(orientation = "portrait", fontScale = 1.0f, dark = false)

    @Test
    fun portraitFontScale1_3() = captureMatrix(orientation = "portrait", fontScale = 1.3f, dark = false)

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun landscapeFontScale1_0() = captureMatrix(orientation = "landscape", fontScale = 1.0f, dark = false)

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun landscapeFontScale1_3() = captureMatrix(orientation = "landscape", fontScale = 1.3f, dark = false)

    @Test
    fun portraitFontScale1_0Dark() = captureMatrix(orientation = "portrait", fontScale = 1.0f, dark = true)

    @Test
    fun portraitFontScale1_3Dark() = captureMatrix(orientation = "portrait", fontScale = 1.3f, dark = true)

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun landscapeFontScale1_0Dark() = captureMatrix(orientation = "landscape", fontScale = 1.0f, dark = true)

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun landscapeFontScale1_3Dark() = captureMatrix(orientation = "landscape", fontScale = 1.3f, dark = true)

    private fun captureMatrix(orientation: String, fontScale: Float, dark: Boolean) {
        val scenario = mutableStateOf(Scenario(EditorSurface.Reply, filled = false))
        lateinit var focusManager: FocusManager
        compose.setContent {
            focusManager = LocalFocusManager.current
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = fontScale),
            ) {
                RedfaceTheme(darkTheme = dark, amoledTheme = false, dynamicColor = false) {
                    val current = scenario.value
                    key(current) {
                        EditorChrome(current)
                    }
                }
            }
        }

        // The new-MP matrix bodies keep their light references only; its panels are in captureComposeStates.
        val surfaces = if (dark) listOf(EditorSurface.Reply) else EditorSurface.entries
        val cases = surfaces.flatMap { surface ->
            listOf(false, true).map { Scenario(surface, it) }
        } + ReplyChrome.entries.filter { it != ReplyChrome.Ready }.map {
            Scenario(EditorSurface.Reply, filled = true, chrome = it)
        }
        cases.forEach { current ->
            compose.runOnIdle { scenario.value = current }
            compose.waitForIdle()
            current.chrome.tap?.let { compose.onNodeWithContentDescription(it).performClick() }
            compose.runOnIdle { focusManager.clearFocus(force = true) }
            compose.mainClock.advanceTimeBy(1_000L)
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            val capture = if (current.chrome.sheet) {
                compose.onNode(isDialog())
            } else compose.onRoot()
                .assertWidthIsEqualTo(if (orientation == "portrait") 360.dp else 760.dp)
                .assertHeightIsEqualTo(if (orientation == "portrait") 760.dp else 360.dp)
            capture.captureRoboImage(filePath = "src/test/screenshots/" +
                "${current.surface.fileName}_${orientation}_fs${fontScale}_${current.state}${theme(dark)}.png")
            compose.mainClock.autoAdvance = true
        }
    }

    private fun theme(dark: Boolean) = if (dark) "_dark" else ""

    @Composable
    private fun EditorChrome(scenario: Scenario) {
        val draft = TextFieldValue(if (scenario.filled) MULTILINE_DRAFT else "")
        if (scenario.surface == EditorSurface.Reply) {
            PrivateMessageReplyTestContent(replyState(scenario.chrome, draft.text))
            return
        }
        ComposeChrome(draft.text)
    }

    private fun replyState(chrome: ReplyChrome, body: String) = PrivateMessageReplyUiState(
        isLoadingForm = false, formAvailable = !chrome.failure, formError = chrome.failure,
        bodyText = body, conversationTitle = "Conversation de démonstration",
        canManageRecipients = chrome.owner, recipients = listOf("CorrespondantTest", "Administration"),
        restorableDraft = if (chrome.draftOffer) "Brouillon privé" else null,
        submitError = if (chrome.antiFlood) EditorErrors.antiFlood else null,
        // Far-future arm, as in the post baseline: the countdown scrim stays frozen at its start.
        submission = if (chrome == ReplyChrome.Armed) {
            EditorSubmissionState.Armed(ArmToken(1, ARM_SESSION, Long.MAX_VALUE / 4, 60_000L))
        } else EditorSubmissionState.Ready,
        isPreviewVisible = chrome == ReplyChrome.Preview,
        preview = PostContent(listOf(PostBlock.Paragraph(listOf(PostInline.Text("Aperçu de la réponse."))))),
    )

    private data class Scenario(
        val surface: EditorSurface,
        val filled: Boolean,
        val chrome: ReplyChrome = ReplyChrome.Ready,
    ) {
        val state get() = chrome.scene ?: if (filled) "multiline" else "empty"
    }

    /** [scene] names the PNG; [tap] is the content description opened before capture; sheets capture their dialog. */
    private enum class ReplyChrome(val scene: String?, val tap: String? = null, val sheet: Boolean = false) {
        Ready(null),
        Restore("restore", tap = "Brouillon retrouvé, restaurer ou ignorer"),
        Owner("owner"),
        Manager("manager", tap = "2 destinataires, gérer", sheet = true),
        Options("options", tap = "Options", sheet = true),
        Error("error"),
        Details("details", tap = "Anti-flood HFR, Afficher les détails"),
        Preview("preview"),
        Armed("armed"),
        // ↺ and recipients at rest, without a tap: the app bar in portrait, the action bar in landscape.
        Statuses("statuses"),
        // Same statuses beside the anti-flood event (ED-30/41).
        EventStatuses("event_statuses"),
        Failure("failure"),
        FailureDraft("failure_draft"),
        ;

        val owner get() = this in setOf(Owner, Manager, Statuses, EventStatuses)
        val draftOffer get() = this in setOf(Restore, Statuses, EventStatuses, FailureDraft)
        val antiFlood get() = this in setOf(Error, Details, EventStatuses)
        val failure get() = this in setOf(Failure, FailureDraft)
    }

    @Test fun composePanelsPortrait() = captureComposeStates("portrait", 1f)
    @Test fun composePanelsPortraitLargeFont() = captureComposeStates("portrait", 1.3f)
    @Test @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun composePanelsLandscape() = captureComposeStates("landscape", 1f)
    @Test @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun composePanelsLandscapeLargeFont() = captureComposeStates("landscape", 1.3f)

    private fun captureComposeStates(orientation: String, scale: Float) {
        val scenario = mutableStateOf(false to ComposeCapture.Header)
        lateinit var focus: FocusManager
        compose.setContent {
            focus = LocalFocusManager.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                RedfaceTheme(darkTheme = scenario.value.first, dynamicColor = false) {
                    key(scenario.value) { ComposeChrome(MULTILINE_DRAFT, scenario.value.second) }
                }
            }
        }
        listOf(false, true).forEach { dark ->
            ComposeCapture.entries.forEach { state ->
                compose.runOnIdle { scenario.value = dark to state }
                compose.waitForIdle()
                compose.runOnIdle { focus.clearFocus(force = true) }
                compose.mainClock.advanceTimeBy(1_000L)
                compose.mainClock.autoAdvance = false
                compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/" +
                    "private_message_new_${state}_${orientation}_fs${scale}_dark$dark.png")
                compose.mainClock.autoAdvance = true
            }
        }
    }

    @Composable
    private fun ComposeChrome(body: String, capture: ComposeCapture = ComposeCapture.Normal) {
        val f = remember { PrivateMessageComposeChromeFixture().apply {
            state = state.copy(recipients = "CorrespondantTest", subject = "Conversation de démonstration",
                recipientState = EditorRecipientState(listOf("CorrespondantTest")),
                bodyText = body, isPreviewVisible = capture == ComposeCapture.Preview,
                preview = PostContent(listOf(PostBlock.Paragraph(listOf(PostInline.Text("Aperçu du message privé"))))),
                restorableDraft = if (capture == ComposeCapture.Restore) "Brouillon privé" else null)
            this.body.edit { replace(0, length, body) }
            when (capture) {
                ComposeCapture.Header -> adapter.openHeader()
                ComposeCapture.Restore -> adapter.open(PrivateMessageComposeChromeSurface.Restore)
                else -> Unit
            }
        } }
        val scope = rememberCoroutineScope()
        val smileys = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
        val height = LocalConfiguration.current.screenHeightDp.dp
        PrivateMessageComposeContent(f.state, f.body, InputTransformation {}, f.recipients, f.subject,
            f.actions, smileys, chromeEvents = f.events, chromeMemory = f.memory,
            windowMetrics = EditorWindowMetrics(height, 0.dp, 0.dp))
    }

    private enum class ComposeCapture { Normal, Header, Preview, Restore }

    private enum class EditorSurface(val fileName: String) {
        Reply("private_message_reply"),
        NewMessage("private_message_new"),
    }

    private companion object {
        val ARM_SESSION = EditorSession("baseline", "tester", "mp-reply:42")
        val MULTILINE_DRAFT = (1..24).joinToString("\n") { line ->
            "Ligne $line — Texte de démonstration pour remplir le champ et observer le retour à la ligne."
        }
    }
}
