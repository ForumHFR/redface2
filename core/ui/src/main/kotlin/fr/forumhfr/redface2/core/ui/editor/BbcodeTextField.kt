package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
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
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        placeholder = placeholder?.let { hint -> { Text(hint) } },
        minLines = 5,
        // #237 — Compose ne capitalise rien par défaut (≠ EditText/RF1 en `textCapSentences`).
        // `Sentences` rend la majuscule en début de message ET après `. ! ?`, parité RF1.
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
}
