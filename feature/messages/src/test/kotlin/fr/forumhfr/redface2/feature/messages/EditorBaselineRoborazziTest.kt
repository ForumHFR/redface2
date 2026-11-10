package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
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
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.testing.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Actual reply content on the common scaffold; the new-message baseline keeps its legacy assembly until C4.
 * Portrait/landscape × font scale 1.0/1.3 × empty/multiline, plus reply restore/event/preview/owner states.
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
        val scenario = mutableStateOf(Scenario(EditorSurface.Reply, filled = false))
        lateinit var focusManager: FocusManager
        compose.setContent {
            focusManager = LocalFocusManager.current
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = fontScale),
            ) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    val current = scenario.value
                    key(current) {
                        EditorChrome(current)
                    }
                }
            }
        }

        val cases = EditorSurface.entries.flatMap { surface ->
            listOf(false, true).map { Scenario(surface, it) }
        } + ReplyChrome.entries.filter { it != ReplyChrome.Ready }.map {
            Scenario(EditorSurface.Reply, filled = true, chrome = it)
        }
        cases.forEach { current ->
            val surface = current.surface
            val filled = current.filled
            compose.runOnIdle { scenario.value = current }
            compose.waitForIdle()
            val action = when (current.chrome) {
                ReplyChrome.Restore -> "Brouillon retrouvé, restaurer ou ignorer"
                ReplyChrome.Manager -> "2 destinataires, gérer"
                ReplyChrome.Options -> "Options"
                ReplyChrome.Details -> "Anti-flood HFR, Afficher les détails"
                else -> null
            }
            action?.let { compose.onNodeWithContentDescription(it).performClick() }
            compose.runOnIdle { focusManager.clearFocus(force = true) }
            compose.mainClock.advanceTimeBy(1_000L)
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            val state = if (current.chrome != ReplyChrome.Ready) current.chrome.name.lowercase()
                else if (filled) "multiline" else "empty"
            val capture = if (current.chrome in setOf(ReplyChrome.Manager, ReplyChrome.Options)) {
                compose.onNode(isDialog())
            } else compose.onRoot()
                .assertWidthIsEqualTo(if (orientation == "portrait") 360.dp else 760.dp)
                .assertHeightIsEqualTo(if (orientation == "portrait") 760.dp else 360.dp)
            capture.captureRoboImage(filePath = "src/test/screenshots/" +
                "${surface.fileName}_${orientation}_fs${fontScale}_$state.png")
            compose.mainClock.autoAdvance = true
        }
    }

    @Composable
    private fun EditorChrome(scenario: Scenario) {
        val draft = TextFieldValue(if (scenario.filled) MULTILINE_DRAFT else "")
        if (scenario.surface == EditorSurface.Reply) {
            val state = PrivateMessageReplyUiState(isLoadingForm = false, formAvailable = true,
                bodyText = draft.text, conversationTitle = "Conversation de démonstration",
                canManageRecipients = scenario.chrome in setOf(ReplyChrome.Owner, ReplyChrome.Manager),
                recipients = listOf("CorrespondantTest", "Administration"),
                restorableDraft = if (scenario.chrome == ReplyChrome.Restore) "Brouillon privé" else null,
                submitError = if (scenario.chrome in setOf(ReplyChrome.Error, ReplyChrome.Details))
                    EditorErrors.antiFlood else null,
                isPreviewVisible = scenario.chrome == ReplyChrome.Preview,
                preview = PostContent(listOf(PostBlock.Paragraph(listOf(PostInline.Text("Aperçu de la réponse."))))))
            PrivateMessageReplyTestContent(state)
            return
        }
        val newMessage = PrivateMessageComposeUiState(
            isLoadingForm = false,
            formAvailable = true,
            recipients = "CorrespondantTest",
            subject = "Conversation de démonstration",
            bodyText = draft.text,
        )
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                MessageEditorHeader(
                    title = stringResource(R.string.messages_compose_title),
                    onBack = {},
                )
                ComposeEditorBody(
                    state = newMessage,
                    onRecipientsChanged = {},
                    onSubjectChanged = {},
                    bodyFieldState = remember { TextFieldState(draft.text) },
                    bodyInputTransformation = InputTransformation {},
                    onToolbarAction = {},
                    onTogglePreview = {},
                    onErrorDismissed = {},
                    onDraftRestore = {},
                    onDraftDiscard = {},
                    onImagePickerEvent = {},
                    onUploadErrorDismissed = {},
                    modifier = Modifier.weight(1f),
                )
                MessageSubmitBar(
                    canSubmit = newMessage.canSubmit,
                    isSubmitting = false,
                    confirmArmed = false,
                    onSubmit = {},
                    onConfirmSubmit = {},
                    onDisarmConfirm = {},
                    onOpenOptions = {},
                    onOpenSmileys = {},
                )
            }
        }
    }

    private data class Scenario(
        val surface: EditorSurface,
        val filled: Boolean,
        val chrome: ReplyChrome = ReplyChrome.Ready,
    )

    private enum class ReplyChrome { Ready, Restore, Owner, Manager, Options, Error, Details, Preview }

    private enum class EditorSurface(val fileName: String) {
        Reply("private_message_reply"),
        NewMessage("private_message_new"),
    }

    private companion object {
        val MULTILINE_DRAFT = (1..24).joinToString("\n") { line ->
            "Ligne $line — Texte de démonstration pour remplir le champ et observer le retour à la ligne."
        }
    }
}
