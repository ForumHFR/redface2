package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
    // Multi-image upload — true makes the field non-editable while a batch is in flight so the user
    // cannot move the caret between two programmatic [img] insertions. Default false = editable.
    readOnly: Boolean = false,
    // #555 — true requests focus (and thus the IME) once on first composition. Opening an editor
    // pre-filled with a LONG post never focused the field by itself: the #447 caret-follow effect
    // is gated on `isFocused`, and nothing set the focus — keyboard closed, follow inert.
    autoFocus: Boolean = false,
) {
    if (fillViewport) {
        // #872 — the label is PINNED above the scrollable viewport instead of floating on the
        // field's top border. The floating label lives inside the #275/#410 viewport, so any
        // scroll (typically #447/#880's open-time bring-into-view of an end-of-text caret in a
        // compressed editor) could park it half-clipped at the viewport's top edge — thibw's
        // « Contenu BBCode » truncated at fontScale 1 whenever the draft banner compressed the
        // field. A pinned line is immune to the viewport's scroll at any fontScale (a very long
        // label on a narrow display ellipsizes horizontally instead of clipping glyphs); the
        // field keeps its placeholder, an ACCESSIBLE name (gate Sol : the DecorationBox no
        // longer carries the label, so the impl merges it as contentDescription), and the focus
        // colour still flows into the pinned line through the hoisted interactionSource.
        val interactionSource = remember { MutableInteractionSource() }
        val isFocused by interactionSource.collectIsFocusedAsState()
        // The bounded-height contract is checked on the CALLER's constraints (gate Sol : an inner
        // weighted box would see post-measure constraints and could let an unbounded host through).
        BoxWithConstraints(modifier = modifier) {
            require(maxHeight.isFinite) {
                "BbcodeTextField(fillViewport = true) requires a bounded-height host " +
                    "(weighted/fixed box) — an unbounded host would nest two unbounded " +
                    "same-direction scrollables and break the heightIn(min) contract."
            }
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isFocused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .testTag(BBCODE_FIELD_PINNED_LABEL_TAG),
                )
                BoxWithConstraints(modifier = Modifier.weight(1f)) {
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
                            floatingLabel = false,
                            placeholder = placeholder,
                            readOnly = readOnly,
                            autoFocus = autoFocus,
                            interactionSource = interactionSource,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = viewportMinHeight),
                        )
                    }
                }
            }
        }
    } else {
        BbcodeFieldImpl(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            readOnly = readOnly,
            autoFocus = autoFocus,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/** Test tag of the #275/#410 scrollable viewport wrapping the grown field. */
const val BBCODE_FIELD_VIEWPORT_TAG = "bbcode_field_viewport"

/** Test tag of the #872 pinned label rendered ABOVE the viewport in `fillViewport` mode. */
const val BBCODE_FIELD_PINNED_LABEL_TAG = "bbcode_field_pinned_label"

@Suppress("LongParameterList") // Compose component impl: mirrors BbcodeTextField's idiomatic surface
// (value/onValueChange/label/placeholder/modifier + readOnly) — a config holder would hurt clarity.
@Composable
private fun BbcodeFieldImpl(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    placeholder: String?,
    modifier: Modifier,
    readOnly: Boolean = false,
    autoFocus: Boolean = false,
    // #872 — false in fillViewport mode: the label is pinned OUTSIDE the scrollable by the
    // caller, so the decoration renders no floating label (and needs no headroom reservation);
    // the label then reaches assistive tech as the field's contentDescription instead of the
    // DecorationBox's merged label node.
    floatingLabel: Boolean = true,
    // #872 — hoisted by the fillViewport wrapper so the pinned label can mirror the focus colour.
    interactionSource: MutableInteractionSource? = null,
) {
    val fieldInteractions = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by fieldInteractions.collectIsFocusedAsState()
    val bringCursorIntoView = remember { BringIntoViewRequester() }
    val focusRequester = remember { FocusRequester() }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // #555 — programmatic focus on entry (one-shot). Without it an editor hydrated with existing
    // content never opens the IME: the caret sits at the end of the text but the field waits for a
    // tap, and the #447 caret-follow below stays inert (`isFocused` gate). Firing after the first
    // composition also lets the follow effect scroll straight to that end-of-text caret.
    LaunchedEffect(Unit) {
        if (autoFocus) focusRequester.requestFocus()
    }

    // #447 — follow the caret through the EXTERNAL scrollable while typing. Keyed on the
    // layout result (a new one lands after every text change) AND the selection (caret moves
    // without relayout: taps, arrow keys), never on `value.text` alone — that would fire
    // against the stale layout of the previous text.
    //
    // #880 — ALSO keyed on the IME bottom inset. At the sheet → full-screen escalation the
    // resumed draft (and its end-of-text caret) lands ASYNC, after autoFocus already fired and
    // while the keyboard is still animating in under `adjustNothing` : the follow then runs
    // against a viewport that has not shrunk yet, judges the caret « already visible », and
    // nothing re-triggers it once the IME finally covers the field. Re-keying on the inset
    // re-issues the bring-into-view when the viewport settles ; each key change cancels the
    // previous effect (and its in-flight bringIntoView), so requests never pile up.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(isFocused, value.selection, textLayout, imeBottom) {
        if (!isFocused) return@LaunchedEffect
        val layout = textLayout ?: return@LaunchedEffect
        // #880 — a layout for ANOTHER text (the async restore replaced the value, its fresh
        // layout not delivered yet) would scroll to a meaningless rect : skip, the matching
        // `onTextLayout` re-keys this effect immediately after.
        if (layout.layoutInput.text.text != value.text) return@LaunchedEffect
        val cursorOffset = value.selection.end.coerceIn(0, layout.layoutInput.text.length)
        bringCursorIntoView.bringIntoView(layout.getCursorRect(cursorOffset))
    }

    // #872 — the floating label's headroom must follow the FONT scale, not a fixed dp : the
    // minimized label renders at bodySmall (sp), so the historical 8.dp reservation (M3's own
    // `OutlinedTextFieldTopPadding`) clips its top at fontScale > 1 — thibw's truncated
    // « Contenu BBCode ». Half the label's line height equals exactly 8.dp at fontScale 1
    // (16.sp / 2) and stretches with the user's setting beyond it. No label (fillViewport mode :
    // the caller pins it outside the scrollable) → nothing floats, no headroom.
    val labelLineHeight = MaterialTheme.typography.bodySmall.lineHeight
    val labelHeadroom = when {
        !floatingLabel -> 0.dp
        labelLineHeight.isSp ->
            with(LocalDensity.current) { (labelLineHeight / 2).toDp() }.coerceAtLeast(8.dp)
        else -> 8.dp
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        // The real M3 OutlinedTextField merges the label into the field's semantics node and
        // reserves headroom above the box for the floating label's upper half — without it the
        // minimized label is clipped at the top (cf. labelHeadroom above).
        modifier = modifier
            .semantics(mergeDescendants = true) {
                // #872 — pinned-label mode : the DecorationBox no longer carries the label, so
                // the field itself must expose its accessible name (gate Sol). TalkBack then
                // announces « <label>, <valeur>, zone d'édition » ; the editable value is NOT
                // masked (contentDescription complements EditableText on text fields).
                if (!floatingLabel) contentDescription = label
            }
            .padding(top = labelHeadroom)
            .focusRequester(focusRequester),
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
        interactionSource = fieldInteractions,
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
                interactionSource = fieldInteractions,
                label = if (floatingLabel) ({ Text(label) }) else null,
                placeholder = placeholder?.let { hint -> { Text(hint) } },
            )
        },
    )
}
