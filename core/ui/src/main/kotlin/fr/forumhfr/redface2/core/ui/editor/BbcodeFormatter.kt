package fr.forumhfr.redface2.core.ui.editor

/**
 * BBCode actions the Phase 2B toolbar can apply to the editor selection. Listed in
 * declaration order so the toolbar UI keeps a stable, deterministic button order.
 *
 * Tags with a real `[/...]` close (Block-level fixed/code/cpp included) all share the
 * same wrap-the-selection logic — the formatter does not care whether HFR renders
 * them as inline or block-level on the receiving end. The renderer side
 * (`PostRenderer` + `BbcodePreview`) is what makes that distinction.
 */
enum class BbcodeAction(val openTag: String, val closeTag: String) {
    Bold(openTag = "[b]", closeTag = "[/b]"),
    Italic(openTag = "[i]", closeTag = "[/i]"),
    Underline(openTag = "[u]", closeTag = "[/u]"),
    Strike(openTag = "[strike]", closeTag = "[/strike]"),
    Quote(openTag = "[quote]", closeTag = "[/quote]"),
    // HFR's web toolbar only exposes [cpp] as the code button (verified against the
    // Phase 2A fixtures — `grep "TAinsert" write_*_form_*.html` finds [cpp] and [fixed]
    // but no [code]). We mirror that here and keep the parser tolerant to [code] so
    // pasted content keeps rendering — but the editor toolbar matches HFR.
    Cpp(openTag = "[cpp]", closeTag = "[/cpp]"),
    Fixed(openTag = "[fixed]", closeTag = "[/fixed]"),
    Spoiler(openTag = "[spoiler]", closeTag = "[/spoiler]"),
    Url(openTag = "[url]", closeTag = "[/url]"),
    Image(openTag = "[img]", closeTag = "[/img]"),
}

/**
 * Result of applying a [BbcodeAction] on an existing text + selection range.
 *
 * - [text] is the new full text after insertion.
 * - [selectionStart] and [selectionEnd] mark the new selection. The contract is
 *   convenient for callers wiring this up to Compose's `TextFieldValue`:
 *
 *   * if the original selection was non-empty, the new selection wraps the inserted
 *     content (i.e. spans the formatted text between the open and close tags);
 *   * if the original selection was empty, the new selection is collapsed and placed
 *     between the open and close tags so the caret sits where the user is about to
 *     type.
 */
data class BbcodeFormatResult(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/**
 * Pure helper that applies a BBCode wrap on a `(text, selectionStart, selectionEnd)`
 * triple. Stays Compose-free so it can be unit-tested on the JVM.
 *
 * Inputs are clamped defensively — out-of-range or inverted selections are normalised
 * rather than throwing, because Compose's `TextFieldValue` can produce them under
 * race conditions (IME edits, paste, undo) and the editor must never crash.
 */
fun applyBbcodeAction(
    action: BbcodeAction,
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
): BbcodeFormatResult {
    val length = text.length
    val rawStart = selectionStart.coerceIn(0, length)
    val rawEnd = selectionEnd.coerceIn(0, length)
    val start = minOf(rawStart, rawEnd)
    val end = maxOf(rawStart, rawEnd)

    val before = text.substring(0, start)
    val selected = text.substring(start, end)
    val after = text.substring(end)

    val open = action.openTag
    val close = action.closeTag

    val newText = buildString(length + open.length + close.length + selected.length) {
        append(before)
        append(open)
        append(selected)
        append(close)
        append(after)
    }

    val newSelectionStart = before.length + open.length
    val newSelectionEnd = newSelectionStart + selected.length

    return BbcodeFormatResult(
        text = newText,
        selectionStart = newSelectionStart,
        selectionEnd = newSelectionEnd,
    )
}
