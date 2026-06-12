package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite

/**
 * Controlled Material 3 BBCode text field used by the Phase 2B editor.
 *
 * The field is intentionally minimal — it exposes the `TextFieldValue` (text + selection)
 * directly so the toolbar above can apply formatting via [applyBbcodeAction] without
 * losing the caret position.
 *
 * ## [fillViewport] — keep the cursor visible under the IME (#275/#410)
 *
 * The editors used to give the field a bounded height (`weight(1f)`) and let the TEXT scroll
 * INSIDE it. Compose's keep-the-cursor-visible machinery works through **ancestor scrollables**
 * (`Modifier.verticalScroll`/`scrollable` re-anchor the focused area when their viewport
 * shrinks, and cursor bring-into-view requests propagate to them) — the text field's internal
 * scroller does take part in bring-into-view, but it does not re-anchor the cursor line when
 * an IME resize shrinks the field around it. Net effect on device, across keyboards (#275
 * Gboard/SwiftKey/HeliBoard): the IME resize compressed the field and the cursor line stayed
 * hidden below the fold, both while typing and on refocus after the preview (#410).
 *
 * `fillViewport = true` switches to the standard Compose text-input pattern: the field grows
 * with its content (no internal scroll) inside this composable's own scrollable column, sized
 * by the caller's bounded box (`weight(1f)` in the three full-screen editors). Typing,
 * tap-to-place-cursor and toolbar insertions all route the cursor bring-into-view through OUR
 * scrollable, and an IME resize re-anchors the focused area like in any scrollable form.
 * `heightIn(min = viewport)` keeps the v108 dogfooding contract: with little text the outlined
 * box still fills every free pixel, so tapping anywhere in the area focuses the field.
 *
 * Contract: `fillViewport = true` REQUIRES a bounded-height caller (a weighted/fixed box) —
 * enforced by a `require` at composition. Do
 * not enable it inside an outer `verticalScroll` — nested same-direction scrollables with
 * unbounded height are a Compose error; `TopicFormScreen` (outer scroll layout) keeps the
 * default and relies on its own column, which is the same machinery.
 *
 * ## Cursor follow while typing (#447)
 *
 * With the field in external-scroll mode (both [fillViewport] and the `TopicFormScreen`
 * outer-scroll layout), Compose only wires the keep-cursor-visible machinery when the text
 * field owns its internal scroll — a growing field never asks the ancestor scrollable to follow
 * the caret while typing. The implementation therefore uses the foundation [BasicTextField]
 * (M3 `OutlinedTextField` does not expose `onTextLayout`) dressed with
 * [OutlinedTextFieldDefaults.DecorationBox] for pixel parity, and issues an explicit
 * [BringIntoViewRequester.bringIntoView] on the caret rect (`TextLayoutResult.getCursorRect`)
 * whenever the selection or the text layout changes while the field is focused. The requester
 * lives on a wrapper `Box` around the inner text field; that wrapper MUST stay offset-free
 * (no padding/alignment of its own) so its coordinate space coincides with the text layout's —
 * the caret rect is expressed there, not in the decorated box's space.
 */
@Suppress("LongParameterList") // Compose component API: optional defaulted params (modifier,
// placeholder, fillViewport) are the idiomatic surface — a config holder would hurt call-sites.
@Composable
fun BbcodeTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    fillViewport: Boolean = false,
) {
    if (fillViewport) {
        BoxWithConstraints(modifier = modifier) {
            require(maxHeight.isFinite) {
                "BbcodeTextField(fillViewport = true) requires a bounded-height host " +
                    "(weighted/fixed box) — an unbounded host would nest two unbounded " +
                    "same-direction scrollables and break the heightIn(min) contract."
            }
            val viewportMinHeight = maxHeight
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag(BBCODE_FIELD_VIEWPORT_TAG),
            ) {
                BbcodeFieldImpl(
                    value = value,
                    onValueChange = onValueChange,
                    label = label,
                    placeholder = placeholder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = viewportMinHeight),
                )
            }
        }
    } else {
        BbcodeFieldImpl(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/** Test tag of the #275/#410 scrollable viewport wrapping the grown field. */
const val BBCODE_FIELD_VIEWPORT_TAG = "bbcode_field_viewport"

@Composable
private fun BbcodeFieldImpl(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    placeholder: String?,
    modifier: Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bringCursorIntoView = remember { BringIntoViewRequester() }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // #447 — follow the caret through the EXTERNAL scrollable while typing. Keyed on the
    // layout result (a new one lands after every text change) AND the selection (caret moves
    // without relayout: taps, arrow keys), never on `value.text` alone — that would fire
    // against the stale layout of the previous text.
    LaunchedEffect(isFocused, value.selection, textLayout) {
        if (!isFocused) return@LaunchedEffect
        val layout = textLayout ?: return@LaunchedEffect
        val cursorOffset = value.selection.end.coerceIn(0, layout.layoutInput.text.length)
        bringCursorIntoView.bringIntoView(layout.getCursorRect(cursorOffset))
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        // The real M3 OutlinedTextField merges the label into the field's semantics node and
        // reserves 8.dp above the box for the floating label's upper half (internal
        // `OutlinedTextFieldTopPadding`) — without it the minimized label is clipped at the top.
        modifier = modifier
            .semantics(mergeDescendants = true) {}
            .padding(top = 8.dp),
        // M3 OutlinedTextField defaults the content to LocalTextStyle coloured onSurface and
        // the caret to primary — replicated here since BasicTextField has no colour scheme.
        textStyle = LocalTextStyle.current.merge(
            TextStyle(color = MaterialTheme.colorScheme.onSurface),
        ),
        // #237 — Compose ne capitalise rien par défaut (≠ EditText/RF1 en `textCapSentences`).
        // `Sentences` rend la majuscule en début de message ET après `. ! ?`, parité RF1.
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        minLines = 5,
        onTextLayout = { textLayout = it },
        interactionSource = interactionSource,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value.text,
                innerTextField = {
                    // Offset-free wrapper by contract (see KDoc): its origin must coincide
                    // with the text layout's so the caret rect needs no translation.
                    Box(Modifier.bringIntoViewRequester(bringCursorIntoView)) {
                        innerTextField()
                    }
                },
                enabled = true,
                singleLine = false,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                label = { Text(label) },
                placeholder = placeholder?.let { hint -> { Text(hint) } },
            )
        },
    )
}
