package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings

/** A detached host: exercises the public callbacks with the real formatter, never a screen/VM. */
internal class EditorActionBarFixture {
    val bar = mutableStateOf(EditorBarState())
    val event = mutableStateOf<EditorEvent?>(null)
    val compact = mutableStateOf(false)
    val width = mutableStateOf(360.dp)
    val dark = mutableStateOf(false)
    val scale = mutableStateOf(1f)
    val uploading = mutableStateOf(false)
    val progress = mutableStateOf<UploadProgress?>(null)
    val loading = mutableStateOf(false)
    val sending = mutableStateOf(false)
    val enabled = mutableStateOf(true)
    val header = mutableStateOf(false)
    val statuses = mutableStateOf(emptyList<EditorAppBarStatus>())
    val shortcut = mutableStateOf<EditorBarShortcut?>(null)
    val imageUrl = mutableStateOf(true)
    val body = TextFieldState("Un texte choisi", TextRange(3, 8))
    val bodyFocus = FocusRequester()
    val calls = mutableListOf<String>()
    val eventActions = mutableListOf<Pair<EventKey, EditorEventAction>>()
    lateinit var inputMode: InputModeManager
    val arm get() = ArmToken(1, EditorSession("bar", "alice", "topic:23:42"),
        android.os.SystemClock.elapsedRealtimeNanos(), 60_000L)

    fun finished() = controlEvent(EditorEventContent.Finished(
        EditorText(R.plurals.editor_event_images_inserted_summary, listOf(3), quantity = 3),
        accessibleSummary = EditorText(R.plurals.editor_event_images_inserted, listOf(3), quantity = 3),
    ))

    @Composable
    fun Content(showBody: Boolean = true) {
        inputMode = LocalInputModeManager.current
        val density = LocalDensity.current
        val focusManager = LocalFocusManager.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
            RedfaceTheme(darkTheme = dark.value, dynamicColor = false,
                reading = ReadingDisplaySettings(fontScale = FontScalePreference.L)) {
                Surface(Modifier.width(width.value).testTag("sample")) {
                    Column {
                        if (showBody) BasicTextField(body, Modifier.fillMaxWidth().height(100.dp)
                            .focusRequester(bodyFocus).testTag("body").onPreviewKeyEvent {
                                if (it.key == Key.Tab && it.type == KeyEventType.KeyDown) {
                                    focusManager.moveFocus(if (it.isShiftPressed) FocusDirection.Previous
                                        else FocusDirection.Next)
                                    true
                                } else false
                            })
                        EditorActionBar(bar.value, EditorActionBarActions(
                            onGesture = { bar.value = reduceEditorBar(bar.value, it) },
                            onBbcode = { action ->
                                calls += "bbcode"
                                body.editBbcode { text, start, end -> applyBbcodeAction(action, text, start, end) }
                            },
                            onBodyFocus = { if (showBody) bodyFocus.requestFocus() },
                            onSmileys = { calls += "smileys" }, onOptions = { calls += "options" },
                            onPreview = { calls += "preview" },
                            onEvent = { key, action -> eventActions += key to action; event.value = null },
                        ), ArmedSubmitState(bar.value.arm != null, !uploading.value && !sending.value,
                            armToken = bar.value.arm),
                            ArmedSubmitActions({ calls += "send" }, { calls += "confirm" }, { calls += "disarm" }),
                            EditorBarImageActions({ calls += "upload" },
                                if (imageUrl.value) ({ calls += "url" }) else null, !imageUrl.value),
                            Modifier.testTag("bar"), event.value, compact.value, statuses.value,
                            onOpenHeader = if (header.value) ({ calls += "header" }) else null,
                            shortcut = shortcut.value, controlsEnabled = enabled.value,
                            uploading = uploading.value, uploadProgress = progress.value,
                            loadingForm = loading.value)
                    }
                }
            }
        }
    }
}

/** Reveal scrolling tools before touch injection; fixed controls have no scrolling ancestor. */
internal fun SemanticsNodeInteraction.revealEditorControl(): SemanticsNodeInteraction {
    if (hasAnyAncestor(hasScrollAction()).matches(fetchSemanticsNode())) performScrollTo()
    return assertIsDisplayed()
}
