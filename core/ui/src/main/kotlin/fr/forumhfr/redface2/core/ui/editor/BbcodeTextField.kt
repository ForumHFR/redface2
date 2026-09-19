package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
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
 * caret. Once the measured field size settles, a collapsed focused caret is moved by one character
 * and restored. Both positions cross `TextFieldScrollerPosition.update` with the current container
 * size. Extended selections are never touched, so handle drags remain entirely foundation-owned.
 *
 * The restoration is conditional on the probe still being current. A keystroke or a user selection
 * during the short nudge window wins instead of being overwritten.
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
    val userEditVersion = remember { AtomicInteger() }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }

    // #555 — one-shot programmatic focus. The size-settled effect below then reveals the restored
    // end-of-text caret using the internal text-field scroller.
    LaunchedEffect(Unit) {
        if (autoFocus) focusRequester.requestFocus()
    }

    KeepCaretVisibleAfterSizeChange(
        value = value,
        onValueChange = onValueChange,
        isFocused = isFocused,
        fieldSize = fieldSize,
        userEditVersion = userEditVersion,
    )

    // #872 — reserve half the floating label's line height above the outlined box. The headroom
    // follows fontScale instead of clipping the label at the historical fixed 8 dp.
    val labelLineHeight = MaterialTheme.typography.bodySmall.lineHeight
    val labelHeadroom = if (labelLineHeight.isSp) {
        with(LocalDensity.current) { (labelLineHeight / 2).toDp() }.coerceAtLeast(8.dp)
    } else {
        8.dp
    }
    BasicTextField(
        value = value,
        onValueChange = { changedValue ->
            userEditVersion.incrementAndGet()
            onValueChange(changedValue)
        },
        readOnly = readOnly,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = labelHeadroom)
            .focusRequester(focusRequester)
            .onSizeChanged { fieldSize = it },
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
                value = value.text,
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

/**
 * Nudges a collapsed caret after the bounded legacy field changes size.
 *
 * A size key covers the IME as well as non-IME shrinkage (preview, draft banner and quote cards).
 * The 150 ms settle window collapses intermediate animation measurements into one correction. The
 * restore accepts both a synchronous state round-trip (current value is the probe) and a slower
 * ViewModel round-trip (current value is still the snapshot); user edits are tracked synchronously by
 * [userEditVersion] and always cancel the restore.
 */
@Composable
private fun KeepCaretVisibleAfterSizeChange(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    isFocused: Boolean,
    fieldSize: IntSize,
    userEditVersion: AtomicInteger,
) {
    val latestValue by rememberUpdatedState(value)
    val emitValue by rememberUpdatedState(onValueChange)
    var settledFieldSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(fieldSize) {
        if (fieldSize != IntSize.Zero) {
            delay(FIELD_SIZE_SETTLE_MS)
            settledFieldSize = fieldSize
        }
    }
    LaunchedEffect(settledFieldSize, isFocused) {
        if (isFocused && settledFieldSize != IntSize.Zero) {
            val snapshot = latestValue
            val probeSelection = snapshot.caretProbeSelection()
            if (probeSelection != null) {
                val editVersion = userEditVersion.get()
                val probeValue = snapshot.copy(selection = probeSelection)
                emitValue(probeValue)
                try {
                    delay(CARET_NUDGE_MS)
                } finally {
                    // Focus loss cancels this effect; still restore unless user input took over.
                    val current = latestValue
                    val stateStillOwnedByNudge = current == snapshot || current == probeValue
                    if (userEditVersion.get() == editVersion && stateStillOwnedByNudge) {
                        emitValue(snapshot)
                    }
                }
            }
        }
    }
}

private fun TextFieldValue.caretProbeSelection(): TextRange? {
    val target = selection.end.coerceIn(0, text.length)
    val probe = if (target > 0) target - 1 else 1
    val hasCaret = selection.collapsed && text.isNotEmpty()
    val probeIsValid = probe <= text.length && probe != target
    return if (hasCaret && probeIsValid) {
        TextRange(probe)
    } else {
        null
    }
}

internal const val FIELD_SIZE_SETTLE_MS = 150L
internal const val CARET_NUDGE_MS = 64L
