package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * Controlled Material 3 BBCode text field used by the four full-screen editors.
 *
 * The public API remains [TextFieldValue]-based so the existing ViewModels and toolbar actions own
 * the draft and its selection. Internally, the state-based [BasicTextField] owns IME composition,
 * caret following and selection-handle scrolling. The host must give the field a bounded height.
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

    val fieldInteractions = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (autoFocus) focusRequester.requestFocus()
    }

    // #872 — reserve half the floating label's line height above the outlined box. The headroom
    // follows fontScale instead of clipping the label at the historical fixed 8 dp.
    val labelLineHeight = MaterialTheme.typography.bodySmall.lineHeight
    val labelHeadroom = if (labelLineHeight.isSp) {
        with(LocalDensity.current) { (labelLineHeight / 2).toDp() }.coerceAtLeast(8.dp)
    } else {
        8.dp
    }
    val lineLimits = TextFieldLineLimits.MultiLine(
        minHeightInLines = 5,
        maxHeightInLines = Int.MAX_VALUE,
    )
    Box(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            state = fieldState,
            readOnly = readOnly,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = labelHeadroom)
                .focusRequester(focusRequester),
            textStyle = LocalTextStyle.current.merge(
                TextStyle(color = MaterialTheme.colorScheme.onSurface),
            ),
            // #237 — sentence capitalization, matching RF1. A multiline editor keeps the default
            // IME action so Enter inserts a newline.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default,
            ),
            lineLimits = lineLimits,
            onTextLayout = onTextLayout,
            interactionSource = fieldInteractions,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorator = OutlinedTextFieldDefaults.decorator(
                state = fieldState,
                enabled = true,
                lineLimits = lineLimits,
                outputTransformation = null,
                interactionSource = fieldInteractions,
                label = { Text(label) },
                placeholder = placeholder?.let { hint -> { Text(hint) } },
            ),
            scrollState = scrollState,
        )
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
                // A selection-only resync must preserve IME composition and the text undo stack.
                if (textChanged) replace(0, length, received.text)
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
