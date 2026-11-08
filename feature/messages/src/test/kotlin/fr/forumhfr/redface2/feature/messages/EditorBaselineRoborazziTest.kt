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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.testing.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1509 — sixteen versioned baselines of the old MP reply/new-conversation editors.
 * [PrivateMessageEditorLayoutTest]'s ready-form fixture is reused for the reply body.
 * The complete content wrappers are private: this test assembles their REAL header, body and
 * submit bar in the same Surface/Column/status-insets/weight arrangement, without a ViewModel.
 * Only closed-sheet, loaded, non-submitting chrome is represented; no network or private data.
 * Portrait/landscape × font scale 1.0/1.3 × empty/multiline body uses the fixed light app theme.
 *
 * Output: `feature/messages/src/test/screenshots/<surface>_<orientation>_fs<scale>_<state>.png`.
 * No M3 1.5 API is used: run this same harness on the base and after the pin.
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

        EditorSurface.entries.forEach { surface ->
            listOf(false, true).forEach { filled ->
                compose.runOnIdle { scenario.value = Scenario(surface, filled) }
                compose.waitForIdle()
                compose.runOnIdle { focusManager.clearFocus(force = true) }
                compose.mainClock.advanceTimeBy(1_000L)
                compose.waitForIdle()
                compose.mainClock.autoAdvance = false
                val state = if (filled) "multiline" else "empty"
                compose.onRoot()
                    .assertWidthIsEqualTo(if (orientation == "portrait") 360.dp else 760.dp)
                    .assertHeightIsEqualTo(if (orientation == "portrait") 760.dp else 360.dp)
                    .captureRoboImage(
                        filePath = "src/test/screenshots/" +
                            "${surface.fileName}_${orientation}_fs${fontScale}_$state.png",
                    )
                compose.mainClock.autoAdvance = true
            }
        }
    }

    @Composable
    private fun EditorChrome(scenario: Scenario) {
        val draft = TextFieldValue(if (scenario.filled) MULTILINE_DRAFT else "")
        val reply = PrivateMessageReplyUiState(
            isLoadingForm = false,
            formAvailable = true,
            bodyText = draft.text,
        )
        val newMessage = PrivateMessageComposeUiState(
            isLoadingForm = false,
            formAvailable = true,
            recipients = "CorrespondantTest",
            subject = "Conversation de démonstration",
            bodyText = draft.text,
        )
        val isReply = scenario.surface == EditorSurface.Reply
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                MessageEditorHeader(
                    title = stringResource(
                        if (isReply) R.string.messages_reply_title else R.string.messages_compose_title,
                    ),
                    onBack = {},
                )
                if (isReply) {
                    ReplyEditorBody(
                        state = reply,
                        bodyFieldState = remember(scenario) {
                            TextFieldState(initialText = draft.text, initialSelection = draft.selection)
                        },
                        bodyInputTransformation = InputTransformation {},
                        onToolbarAction = {},
                        onTogglePreview = {},
                        onErrorDismissed = {},
                        onDraftRestore = {},
                        onDraftDiscard = {},
                        onImagePickerEvent = {},
                        onUploadErrorDismissed = {},
                        onManageRecipients = {},
                        modifier = Modifier.weight(1f),
                    )
                } else {
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
                }
                MessageSubmitBar(
                    canSubmit = if (isReply) reply.canSubmit else newMessage.canSubmit,
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

    private data class Scenario(val surface: EditorSurface, val filled: Boolean)

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
