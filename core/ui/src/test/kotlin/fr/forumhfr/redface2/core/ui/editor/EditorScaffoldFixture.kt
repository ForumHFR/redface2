package fr.forumhfr.redface2.core.ui.editor

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings

/** Component bench only: no screen, ViewModel, upload launcher or network submission. */
internal class EditorScaffoldFixture {
    val chrome = mutableStateOf(EditorScaffoldChrome())
    val metrics = mutableStateOf(EditorWindowMetrics(740.dp, 24.dp, 30.dp))
    val width = mutableStateOf(360.dp)
    val dark = mutableStateOf(false)
    val scale = mutableStateOf(1f)
    val fontPreset = mutableStateOf(FontScalePreference.L)
    val autoFocus = mutableStateOf(false)
    var inputTransformation: InputTransformation? = null
    val panel = mutableStateOf(false)
    val headerLabel = mutableStateOf("Titre et sous-catégorie")
    val detail = mutableStateOf<EditorEvent?>(null)
    val body = TextFieldState((1..40).joinToString("\n") { "Ligne $it du message" }, TextRange(8, 12))
    val focus = FocusRequester()
    val scroll = ScrollState(0)
    val calls = mutableListOf<String>()
    var inputConnection: InputConnection? = null
        private set
    var inputSessions = 0
        private set
    // Observe BTF2's actual startInputMethod boundary, before Compose's own test input handler.
    // A controller show() without a live session cannot reach this interceptor.
    private val textInput = PlatformTextInputInterceptor { request, next ->
        val connection = request.createInputConnection(EditorInfo())
        inputConnection = connection
        inputSessions++
        try {
            next.startInputMethod(request)
        } finally {
            inputConnection = null
            connection.closeConnection()
        }
    }
    var disposals = 0
    lateinit var view: View
    lateinit var density: Density
    lateinit var back: OnBackPressedDispatcher
    lateinit var inputMode: InputModeManager
    val arm = ArmToken(1, EditorSession("scaffold", "alice", "topic:23:42"),
        android.os.SystemClock.elapsedRealtimeNanos(), 60_000L)

    /** Captures only: armed far in the future, the decorative countdown stays at 0 whatever the scheduling. */
    val frozenArm = arm.copy(armedAtNanos = Long.MAX_VALUE / 4)
    val statuses = listOf(
        EditorAppBarStatus(R.drawable.ic_editor_quote, "12 citations", { calls += "quotes" }, 12),
        EditorAppBarStatus(R.drawable.ic_editor_restore, "Brouillon retrouvé", { calls += "restore" }),
    )

    @Suppress("LongParameterList") // Independent inset edges and visibility, including a floating IME.
    fun insets(top: Int = 24, left: Int = 0, right: Int = 0, nav: Int = 30, ime: Int = 0,
        imeVisible: Boolean = ime > 0) {
        val dispatched = with(density) {
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top.dp.roundToPx(), 0, 0))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(left.dp.roundToPx(), 0, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, right.dp.roundToPx(), nav.dp.roundToPx()))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime.dp.roundToPx()))
                .setVisible(WindowInsetsCompat.Type.statusBars(), top > 0)
                .setVisible(WindowInsetsCompat.Type.navigationBars(), nav > 0 || right > 0)
                .setVisible(WindowInsetsCompat.Type.ime(), imeVisible).build()
        }
        ViewCompat.dispatchApplyWindowInsets(view, dispatched)
    }

    private fun gesture(gesture: EditorBarGesture) {
        chrome.value = chrome.value.copy(bar = reduceEditorBar(chrome.value.bar, gesture))
    }

    @Composable
    fun Content() {
        view = LocalView.current
        density = LocalDensity.current
        back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        inputMode = LocalInputModeManager.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
            InterceptPlatformTextInput(textInput) { Bench() }
        }
    }

    @Composable
    private fun Bench() {
        RedfaceTheme(darkTheme = dark.value, dynamicColor = false,
            reading = ReadingDisplaySettings(fontScale = fontPreset.value)) {
            EditorScaffold(body, metrics.value, "Répondre", "Sujet de démonstration", chrome.value,
                EditorActionBarActions(::gesture, { action ->
                    body.editBbcode { text, start, end -> applyBbcodeAction(action, text, start, end) }
                }, {}, { calls += "smileys" }, { calls += "options" }, { calls += "preview" },
                    { key, action ->
                        calls += "event:$action"
                        if (chrome.value.event?.key == key) when (action) {
                            EditorEventAction.Details -> detail.value = chrome.value.event
                            EditorEventAction.Complete -> {
                                panel.value = true
                                chrome.value = chrome.value.copy(event = null)
                            }
                            EditorEventAction.Dismiss -> chrome.value = chrome.value.copy(event = null)
                        }
                    }),
                ArmedSubmitState(chrome.value.bar.arm != null,
                    !chrome.value.uploading && !chrome.value.loadingForm && !chrome.value.bodyReadOnly,
                    armToken = chrome.value.bar.arm),
                ArmedSubmitActions({ calls += "send" }, { calls += "confirm" }, {
                    calls += "disarm"; chrome.value = chrome.value.copy(bar = chrome.value.bar.copy(arm = null))
                }), EditorBarImageActions({ calls += "upload" }, { calls += "url" }),
                { calls += "close" },
                modifier = Modifier.size(width.value, metrics.value.windowHeight).testTag("scaffold-bench"),
                onOpenHeader = { panel.value = true }, bodyFocusRequester = focus, bodyScrollState = scroll,
                headerLabel = headerLabel.value,
                bodyFocusRestoreAllowed = !panel.value && detail.value == null && !chrome.value.previewVisible,
                autoFocus = autoFocus.value,
                inputTransformation = inputTransformation,
                overlay = { onReturnToBody, bodyContent ->
                    EditorHeaderPanel(panel.value, "Titre et sous-catégorie", body, { panel.value = false },
                        onReturnToBody, Modifier.fillMaxSize(), body = {
                            EditorEventDetails(detail.value, body, { key ->
                                calls += "detail-close"
                                if (chrome.value.event?.key == key) chrome.value = chrome.value.copy(event = null)
                                detail.value = null
                            }, { calls += "reload" }, onReturnToBody, Modifier.fillMaxSize(), body = {
                                DisposableEffect(Unit) { onDispose { disposals++ } }
                                bodyContent()
                            })
                        }) { Text("Ajoutez un titre au sujet.") }
                })
        }
    }
}
