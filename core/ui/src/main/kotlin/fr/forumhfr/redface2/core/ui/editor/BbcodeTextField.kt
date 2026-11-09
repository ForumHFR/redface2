package fr.forumhfr.redface2.core.ui.editor

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * Creates a field-scoped context whose [TextClassificationManager] uses [TextClassifier.NO_OP].
 * This disables automatic selection expansion and classifier-provided actions in this editor. The
 * standard Cut/Copy/Paste/Select all actions and other apps' `ACTION_PROCESS_TEXT` items remain.
 *
 * Only the classification service comes from a separate configuration context. Everything else,
 * including activity launches, resources, configuration and theme, delegates to [context], so the
 * visual context and `findActivity()` chain stay intact. The wrapper references the same context as
 * `LocalContext` and is remembered only for that context's composition lifetime, so it does not
 * extend the activity lifetime.
 */
internal fun createBbcodeTextFieldContext(context: Context): Context {
    val noOpManager = context
        .createConfigurationContext(Configuration(context.resources.configuration))
        .getSystemService(TextClassificationManager::class.java)
        ?.apply { setTextClassifier(TextClassifier.NO_OP) }
    return object : ContextWrapper(context) {
        override fun getSystemService(name: String): Any? =
            if (name == Context.TEXT_CLASSIFICATION_SERVICE) {
                noOpManager ?: super.getSystemService(name)
            } else {
                super.getSystemService(name)
            }
    }
}

/**
 * Compatibility adapter for editors whose ViewModel still owns a [TextFieldValue].
 * IME composition stays inside the field; migrated editors use the [TextFieldState] overload.
 */
@Suppress("LongParameterList") // Compose component API: optional defaulted params are idiomatic.
@Composable
fun BbcodeTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    // Multi-image upload — true makes the field non-editable while a batch is in flight so the user
    // cannot move the caret between two programmatic [img] insertions. Default false = editable.
    readOnly: Boolean = false,
    // #555 — true requests focus (and thus the IME) once on first composition. Opening an editor
    // pre-filled with a long post otherwise leaves the keyboard closed.
    autoFocus: Boolean = false,
    showLabel: Boolean = true,
) {
    BbcodeTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        placeholder = placeholder,
        readOnly = readOnly,
        autoFocus = autoFocus,
        scrollState = rememberScrollState(),
        onTextLayout = null,
        showLabel = showLabel,
    )
}

/** Internal overload exposing the BTF2 viewport to Robolectric tests without widening public API. */
@Suppress("LongParameterList")
@Composable
internal fun BbcodeTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    readOnly: Boolean = false,
    autoFocus: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
    onTextLayout: (Density.(getResult: () -> TextLayoutResult?) -> Unit)?,
    showLabel: Boolean = true,
) {
    val fieldState = rememberTextFieldState(
        initialText = value.text,
        initialSelection = value.selection,
    )
    BbcodeTextFieldValueBridge(
        fieldState = fieldState,
        value = value,
        onValueChange = onValueChange,
    )
    BbcodeTextField(
        state = fieldState,
        label = label,
        modifier = modifier,
        placeholder = placeholder,
        readOnly = readOnly,
        autoFocus = autoFocus,
        scrollState = scrollState,
        onTextLayout = onTextLayout,
        showLabel = showLabel,
    )
}

/**
 * Material 3 BBCode editor with a host-owned buffer, IME composition and selection.
 * The host must give the field a bounded height. [inputTransformation] guards user edits
 * synchronously; programmatic edits must be guarded by the owner before editing [state].
 * Keep [onTextLayout] last so existing trailing lambdas always receive the text layout.
 * [showLabel] removes both the visual label and its headroom when false, keeping [label] as the
 * accessible name, with 8 dp vertical padding for the compact viewport. The default preserves the
 * historical decoration of both public overloads.
 * [showKeyboardOnFocus] lets a scaffold restore focus without opening a previously closed IME.
 * False also prevents a programmatic focus request from starting an input session. To restore an
 * open IME, switch it to true: BTF2 starts the session even if the field already regained focus.
 * [onSemanticsClick] optionally overrides the field's accessibility click, preserving its action label.
 * The override owns focus and IME restoration; null keeps the native field action.
 * [revealCaretOnChange] follows programmatic edits outside BTF2's focused, editable caret tracking.
 * The first measured content never reveals the caret. After an edit, viewport changes keep an
 * already visible caret in view; scrolling away suspends that follow.
 * [caretRevealResetKey] reads a snapshot-backed counter the host increments in the same snapshot as
 * server hydration or draft restoration. It is observed alongside the buffer, before recomposition,
 * so these changes establish a measured baseline without revealing or competing with restored scroll.
 */
@Suppress("LongParameterList") // Same chrome and viewport hooks for both editor APIs.
@Composable
fun BbcodeTextField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    inputTransformation: InputTransformation? = null,
    placeholder: String? = null,
    readOnly: Boolean = false,
    autoFocus: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
    showLabel: Boolean = true,
    showKeyboardOnFocus: Boolean = true,
    onViewportMeasured: ((heightPx: Int) -> Unit)? = null,
    onSemanticsClick: (() -> Boolean)? = null,
    caretRevealResetKey: () -> Int = { 0 },
    revealCaretOnChange: Boolean = false,
    onTextLayout: (Density.(getResult: () -> TextLayoutResult?) -> Unit)? = null,
) {
    val fieldInteractions = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }
    var layout by remember(state) { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember(state) { mutableStateOf(false) }
    val currentReadOnly by rememberUpdatedState(readOnly)
    val currentResetKey by rememberUpdatedState(caretRevealResetKey)
    LaunchedEffect(state, scrollState, revealCaretOnChange) {
        if (revealCaretOnChange) {
            // Keep the measured baseline across edits. BTF2 owns focused, editable scrolling.
            revealEditorCaret({ layout }, state, scrollState, { currentResetKey() }, { !focused || currentReadOnly })
        }
    }
    LaunchedEffect(Unit) {
        if (autoFocus) focusRequester.requestFocus()
    }

    // #872 — reserve half the floating label's line height above the outlined box. The headroom
    // follows fontScale instead of clipping the label at the historical fixed 8 dp.
    val labelLineHeight = MaterialTheme.typography.bodySmall.lineHeight
    val labelHeadroom = if (!showLabel) {
        0.dp
    } else if (labelLineHeight.isSp) {
        with(LocalDensity.current) { (labelLineHeight / 2).toDp() }.coerceAtLeast(8.dp)
    } else {
        8.dp
    }
    val lineLimits = TextFieldLineLimits.MultiLine(
        minHeightInLines = 5,
        maxHeightInLines = Int.MAX_VALUE,
    )
    val context = LocalContext.current
    val fieldContext = remember(context) { createBbcodeTextFieldContext(context) }
    Box(modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalContext provides fieldContext) {
            BasicTextField(
                state = state,
                inputTransformation = inputTransformation,
                readOnly = readOnly,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = labelHeadroom)
                    .then(if (showLabel) Modifier else Modifier.semantics { contentDescription = label })
                    .then(if (onSemanticsClick == null) Modifier else Modifier.semantics {
                        // A null label lets the native/input-transformation label survive peer merging.
                        onClick(action = onSemanticsClick)
                    })
                    .focusRequester(focusRequester).onFocusChanged { focused = it.isFocused },
                textStyle = LocalTextStyle.current.merge(
                    TextStyle(color = MaterialTheme.colorScheme.onSurface),
                ),
                // #237 — sentence capitalization, matching RF1. A multiline editor keeps the
                // default IME action so Enter inserts a newline.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                    showKeyboardOnFocus = showKeyboardOnFocus,
                ),
                lineLimits = lineLimits,
                onTextLayout = { getResult ->
                    if (revealCaretOnChange) layout = getResult()
                    onTextLayout?.invoke(this, getResult)
                },
                interactionSource = fieldInteractions,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorator = measuredEditorDecorator(OutlinedTextFieldDefaults.decorator(
                    state = state,
                    enabled = true,
                    lineLimits = lineLimits,
                    outputTransformation = null,
                    interactionSource = fieldInteractions,
                    label = if (showLabel) ({ Text(label) }) else null,
                    placeholder = placeholder?.let { hint -> { Text(hint) } },
                    contentPadding = if (showLabel) OutlinedTextFieldDefaults.contentPaddingWithoutLabel()
                        else OutlinedTextFieldDefaults.contentPaddingWithoutLabel(top = 8.dp, bottom = 8.dp),
                ), onViewportMeasured),
                scrollState = scrollState,
            )
        }
    }
}

/** Controlled-value bridge kept separate so ordering-sensitive echo races can be tested directly. */
@Composable
internal fun BbcodeTextFieldValueBridge(
    fieldState: TextFieldState,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
) {
    val received = BbcodeTextSnapshot(text = value.text, selection = value.selection)
    var lastReceived by remember { mutableStateOf(received) }
    var lastEmitted by remember { mutableStateOf<BbcodeTextSnapshot?>(null) }
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    // Parent-owned changes (toolbar, smileys, quotes, draft restore and normalization) must update
    // the BTF2 buffer and its selection. IME composition deliberately stays inside TextFieldState.
    LaunchedEffect(received) {
        lastReceived = received
        val previouslyEmitted = lastEmitted
        lastEmitted = null
        // The parent echo can arrive one frame after a newer IME edit. It acknowledges our last
        // emission; applying it downward would overwrite the live buffer and lose that newer edit.
        if (received == previouslyEmitted) return@LaunchedEffect

        val current = BbcodeTextSnapshot(
            text = fieldState.text.toString(),
            selection = fieldState.selection,
        )
        if (current != received) {
            val textChanged = current.text != received.text
            fieldState.edit {
                // A selection-only resync must not replace text or add a text undo entry.
                if (textChanged) {
                    val change = minimalReplacement(current.text, received.text)
                    replace(change.start, change.end, change.text)
                }
                selection = received.selection
            }
        }
    }

    // snapshotFlow keeps autosave and preview fed by user edits. Comparing both the last parent
    // value and the last callback value prevents the controlled bridge from echoing either side.
    LaunchedEffect(fieldState) {
        snapshotFlow {
            BbcodeTextSnapshot(
                text = fieldState.text.toString(),
                selection = fieldState.selection,
            )
        }.collect { changed ->
            if (changed != lastReceived && changed != lastEmitted) {
                lastEmitted = changed
                latestOnValueChange(
                    latestValue.copy(
                        text = changed.text,
                        selection = changed.selection,
                        composition = null,
                    ),
                )
            }
        }
    }
}

private data class BbcodeTextSnapshot(
    val text: String,
    val selection: TextRange,
)

/** Measurement only: unlike ScrollState.viewportSize, this port is available before placement. */
private fun measuredEditorDecorator(delegate: TextFieldDecorator, onMeasured: ((Int) -> Unit)?): TextFieldDecorator =
    if (onMeasured == null) delegate else TextFieldDecorator { inner ->
        delegate.Decoration {
            Layout(content = inner) { measurables, constraints ->
                val viewport = measurables.single().measure(constraints)
                onMeasured(viewport.height)
                layout(viewport.width, viewport.height) { viewport.placeRelative(0, 0) }
            }
        }
    }

private data class BbcodeCaretSnapshot(
    val value: BbcodeTextSnapshot,
    val caret: Rect,
    val viewport: Int,
    val resetKey: Int,
) {
    fun isVisible(scroll: Int): Boolean = caret.top >= scroll && caret.bottom <= scroll + viewport
}

/** ED-14: reveal edits, then follow viewport changes only while the caret was already visible. */
private suspend fun revealEditorCaret(
    getLayout: () -> TextLayoutResult?,
    state: TextFieldState,
    scroll: ScrollState,
    resetKey: () -> Int,
    canReveal: () -> Boolean,
) {
    var previous: BbcodeCaretSnapshot? = null
    var followViewport = false
    // Observe measurement without recomposing the field on every frame of an IME/panel animation.
    snapshotFlow { measuredEditorCaret(getLayout(), state, scroll.viewportSize, resetKey()) }.collect { current ->
        if (current == null) return@collect
        val baseline = previous?.takeIf { it.resetKey == current.resetKey }
        val edited = baseline != null && baseline.value != current.value
        followViewport = baseline != null && (followViewport || edited)
        // Use the old geometry and viewport, but the current offset so manual scrolling wins.
        val shouldReveal = edited || baseline?.isVisible(scroll.value) == true
        previous = current
        if (followViewport && shouldReveal && canReveal()) scroll.revealCaret(current.caret, current.viewport)
    }
}

private fun measuredEditorCaret(
    layout: TextLayoutResult?,
    state: TextFieldState,
    viewport: Int,
    resetKey: Int,
): BbcodeCaretSnapshot? {
    if (layout == null) return null
    val value = BbcodeTextSnapshot(state.text.toString(), state.selection)
    return if (layout.layoutInput.text.text == value.text && viewport > 0) {
        val caret = layout.getCursorRect(value.selection.end.coerceIn(0, value.text.length))
        BbcodeCaretSnapshot(value, caret, viewport, resetKey)
    } else null
}

private suspend fun ScrollState.revealCaret(caret: Rect, viewport: Int) {
    val target = when {
        caret.bottom > value + viewport -> kotlin.math.ceil(caret.bottom).toInt() - viewport
        caret.top < value -> caret.top.toInt()
        else -> value
    }.coerceIn(0, maxValue)
    if (target != value) scrollTo(target)
}
