package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Controlled Material 3 BBCode text field used by the four full-screen editors.
 *
 * The field exposes [TextFieldValue] directly so the toolbar can preserve and edit the selection.
 * Its host must give it a bounded height: the legacy [BasicTextField] then owns the vertical scroll,
 * and its selection manager compensates that INTERNAL scroll while a handle is dragged
 * (#447/#1406).
 *
 * Foundation 1.11.2 only coerces the internal scroll offset when the cursor rectangle changes;
 * resizing the viewport alone (IME, preview, banner or quote cards) does not re-anchor an unchanged
 * caret. Once the measured field size settles, the value DISPLAYED by the field moves a collapsed
 * focused caret by one code point for two frames, then returns to the controlled value. Both
 * positions cross `TextFieldScrollerPosition.update` with the current container size. Neither
 * position is emitted to [onValueChange], so the ViewModel never mistakes this rendering probe for
 * user input (in particular, it cannot trigger draft autosave). Extended selections and active IME
 * compositions are never touched, so handle drags and composed words remain foundation-owned.
 *
 * A keystroke during the two-frame probe is rebased onto the real caret before it is emitted. A
 * parent value change or user selection wins instead of being overwritten by the local restore.
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
    val fieldInteractions = remember { MutableInteractionSource() }
    val isFocused by fieldInteractions.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    var caretProbe by remember { mutableStateOf<CaretProbe?>(null) }
    val activeProbe = caretProbe?.takeIf { it.source == value }
    val displayedValue = activeProbe?.displayed ?: value

    // A toolbar/smiley insertion or any other parent-owned update supersedes the local probe. This
    // is deliberately separate from BasicTextField.onValueChange: those programmatic changes do not
    // pass through the field callback, but still must become visible immediately.
    LaunchedEffect(value) {
        if (caretProbe?.source != value) caretProbe = null
    }

    // #555 — one-shot programmatic focus. The size-settled effect below then reveals the restored
    // end-of-text caret using the internal text-field scroller.
    LaunchedEffect(Unit) {
        if (autoFocus) focusRequester.requestFocus()
    }

    KeepCaretVisibleAfterSizeChange(
        value = value,
        isFocused = isFocused,
        fieldSize = fieldSize,
        onProbeStarted = { caretProbe = it },
        onProbeFinished = { finishedProbe ->
            if (caretProbe === finishedProbe) caretProbe = null
        },
    )

    // #872 — reserve half the floating label's line height above the outlined box. The headroom
    // follows fontScale instead of clipping the label at the historical fixed 8 dp.
    val labelLineHeight = MaterialTheme.typography.bodySmall.lineHeight
    val labelHeadroom = if (labelLineHeight.isSp) {
        with(LocalDensity.current) { (labelLineHeight / 2).toDp() }.coerceAtLeast(8.dp)
    } else {
        8.dp
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { fieldSize = it },
    ) {
        BasicTextField(
            value = displayedValue,
            onValueChange = { changedValue ->
                val callbackProbe = caretProbe?.takeIf { it.source == value }
                caretProbe = null
                onValueChange(callbackProbe?.let { changedValue.rebasedFrom(it) } ?: changedValue)
            },
            readOnly = readOnly,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = labelHeadroom)
                .focusRequester(focusRequester)
                .semantics { bbcodeDisplayedSelection = displayedValue.selection },
            textStyle = LocalTextStyle.current.merge(
                TextStyle(color = MaterialTheme.colorScheme.onSurface),
            ),
            // #237 — Compose ne capitalise rien par défaut (≠ EditText/RF1 en `textCapSentences`).
            // `Sentences` rend la majuscule en début de message ET après `. ! ?`, parité RF1.
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            minLines = 5,
            interactionSource = fieldInteractions,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = displayedValue.text,
                    innerTextField = innerTextField,
                    enabled = true,
                    singleLine = false,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = fieldInteractions,
                    label = { Text(label) },
                    placeholder = placeholder?.let { hint -> { Text(hint) } },
                )
            },
        )
    }
}

/**
 * Nudges a collapsed caret after the bounded legacy field changes size.
 *
 * A size key covers the IME as well as non-IME shrinkage (preview, draft banner and quote cards).
 * The 150 ms settle window collapses intermediate animation measurements into one correction. Focus
 * is read only as a guard: regaining focus without a size change must not create another probe.
 */
@Composable
private fun KeepCaretVisibleAfterSizeChange(
    value: TextFieldValue,
    isFocused: Boolean,
    fieldSize: IntSize,
    onProbeStarted: (CaretProbe) -> Unit,
    onProbeFinished: (CaretProbe) -> Unit,
) {
    val latestValue by rememberUpdatedState(value)
    val latestIsFocused by rememberUpdatedState(isFocused)
    val startProbe by rememberUpdatedState(onProbeStarted)
    val finishProbe by rememberUpdatedState(onProbeFinished)
    var settledFieldSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(fieldSize) {
        if (fieldSize != IntSize.Zero) {
            delay(FIELD_SIZE_SETTLE_MS)
            settledFieldSize = fieldSize
        }
    }
    LaunchedEffect(settledFieldSize) {
        if (settledFieldSize == IntSize.Zero) return@LaunchedEffect
        if (!latestIsFocused) return@LaunchedEffect
        val snapshot = latestValue
        val probeSelection = snapshot.caretProbeSelection() ?: return@LaunchedEffect
        val probe = CaretProbe(
            source = snapshot,
            displayed = snapshot.copy(selection = probeSelection),
        )
        startProbe(probe)
        try {
            // Two rendered values are sufficient for CoreTextField to update its cursor rectangle
            // with the new viewport, without keeping a 64 ms input hazard open.
            withFrameNanos { }
            withFrameNanos { }
        } finally {
            finishProbe(probe)
        }
    }
}

private fun TextFieldValue.caretProbeSelection(): TextRange? {
    if (composition != null || !selection.collapsed || text.isEmpty()) return null
    val target = selection.end.coerceIn(0, text.length)
    val probe = if (target > 0) {
        text.offsetByCodePoints(target, -1)
    } else {
        text.offsetByCodePoints(target, 1)
    }
    val probeIsValid = probe <= text.length && probe != target
    return if (probeIsValid) TextRange(probe) else null
}

/**
 * Replays a text edit received from the transient [CaretProbe.displayed] selection at the real
 * [CaretProbe.source] caret. Selection-only changes (tap/handle/key navigation) already describe an
 * absolute user choice and are therefore forwarded untouched.
 */
private fun TextFieldValue.rebasedFrom(probe: CaretProbe): TextFieldValue {
    val sourceText = probe.source.text
    if (text == sourceText) return this

    // The edit was produced from the displayed collapsed caret. Capping the common prefix there
    // removes diff ambiguity when the inserted text equals the following source character.
    val commonPrefix = minOf(
        sourceText.commonPrefixWith(text).length,
        probe.displayed.selection.end,
    )
    val maxSuffix = minOf(sourceText.length - commonPrefix, text.length - commonPrefix)
    var commonSuffix = 0
    while (
        commonSuffix < maxSuffix &&
        sourceText[sourceText.lastIndex - commonSuffix] == text[text.lastIndex - commonSuffix]
    ) {
        commonSuffix++
    }

    val removedEnd = sourceText.length - commonSuffix
    val insertedEnd = text.length - commonSuffix
    val caretDelta = probe.source.selection.end - probe.displayed.selection.end
    val targetStart = (commonPrefix + caretDelta).coerceIn(0, sourceText.length)
    val targetEnd = (removedEnd + caretDelta).coerceIn(targetStart, sourceText.length)
    val correctedText = sourceText.replaceRange(
        startIndex = targetStart,
        endIndex = targetEnd,
        replacement = text.substring(commonPrefix, insertedEnd),
    )
    return copy(
        text = correctedText,
        selection = selection.shifted(caretDelta, correctedText.length),
        composition = composition?.shifted(caretDelta, correctedText.length),
    )
}

private fun TextRange.shifted(delta: Int, textLength: Int): TextRange = TextRange(
    start = (start + delta).coerceIn(0, textLength),
    end = (end + delta).coerceIn(0, textLength),
)

private data class CaretProbe(
    val source: TextFieldValue,
    val displayed: TextFieldValue,
)

/** Test diagnostic for the selection currently rendered by the controlled legacy field. */
internal val BbcodeDisplayedSelectionKey =
    SemanticsPropertyKey<TextRange>("BbcodeDisplayedSelection")

private var SemanticsPropertyReceiver.bbcodeDisplayedSelection by BbcodeDisplayedSelectionKey

internal const val FIELD_SIZE_SETTLE_MS = 150L
