package fr.forumhfr.redface2.core.ui.editor

/**
 * BBCode actions the Phase 2B toolbar can apply to the editor selection.
 *
 * Modelled as a `sealed interface` rather than an `enum class` so the colour
 * action can carry the chosen hex value (`#RRGGBB`) without forcing one
 * enum-per-shade. The fixed-tag actions stay as `data object`s so they
 * compare by identity exactly like the old enum constants.
 *
 * Tags with a real `[/...]` close (block-level `[fixed]`, `[cpp]`, `[quote]`
 * included) all share the same wrap-the-selection logic in
 * [applyBbcodeAction] — the formatter does not care whether HFR renders them
 * as inline or block-level on the receiving end. The renderer side
 * (`PostRenderer` + `BbcodePreview`) is what makes that distinction.
 *
 * HFR colour contract : `[#RRGGBB]…[/#RRGGBB]` (the closing tag echoes the
 * hex code, not `[/color]`). Verified against `BbcodeContentParser.parseColor`.
 */
sealed interface BbcodeAction {
    val openTag: String
    val closeTag: String

    data object Bold : BbcodeAction {
        override val openTag: String = "[b]"
        override val closeTag: String = "[/b]"
    }
    data object Italic : BbcodeAction {
        override val openTag: String = "[i]"
        override val closeTag: String = "[/i]"
    }
    data object Underline : BbcodeAction {
        override val openTag: String = "[u]"
        override val closeTag: String = "[/u]"
    }
    data object Strike : BbcodeAction {
        override val openTag: String = "[strike]"
        override val closeTag: String = "[/strike]"
    }
    data object Quote : BbcodeAction {
        override val openTag: String = "[quote]"
        override val closeTag: String = "[/quote]"
    }
    // HFR's web toolbar only exposes [cpp] as the code button (verified against the
    // Phase 2A fixtures — `grep "TAinsert" write_*_form_*.html` finds [cpp] and [fixed]
    // but no [code]). We mirror that here and keep the parser tolerant to [code] so
    // pasted content keeps rendering — but the editor toolbar matches HFR.
    data object Cpp : BbcodeAction {
        override val openTag: String = "[cpp]"
        override val closeTag: String = "[/cpp]"
    }
    data object Fixed : BbcodeAction {
        override val openTag: String = "[fixed]"
        override val closeTag: String = "[/fixed]"
    }
    data object Spoiler : BbcodeAction {
        override val openTag: String = "[spoiler]"
        override val closeTag: String = "[/spoiler]"
    }
    data object Url : BbcodeAction {
        override val openTag: String = "[url]"
        override val closeTag: String = "[/url]"
    }
    data object Image : BbcodeAction {
        override val openTag: String = "[img]"
        override val closeTag: String = "[/img]"
    }

    /**
     * HFR colour wrap : `[#RRGGBB]…[/#RRGGBB]`. The closing tag echoes the
     * same hex code (verified in `BbcodeContentParser.parseColor`). Hex must
     * be the canonical 7-character form starting with `#`.
     */
    data class Color(val colorHex: String) : BbcodeAction {
        init {
            require(HEX_PATTERN.matches(colorHex)) {
                "colorHex must match #RRGGBB (uppercase or lowercase), was '$colorHex'"
            }
        }
        override val openTag: String get() = "[$colorHex]"
        override val closeTag: String get() = "[/$colorHex]"

        private companion object {
            val HEX_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")
        }
    }
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

/**
 * Phase 2F-B (#11 partial) — inserts a raw BBCode [token] at the editor caret.
 *
 * Distinct from [applyBbcodeAction] because smileys do **not** wrap a selection : they are
 * point-insertions of an opaque token like `:jap:` or `[:haha jap]`. Modelling them as a
 * [BbcodeAction] would force a degenerate empty `closeTag` — instead we expose a dedicated
 * pure helper.
 *
 * Mirrors HFR's `putSmiley(tt, src)` JS, which inserts ` $token ` (with surrounding spaces)
 * — see `/compressed/message.js`. Without the surrounding spaces, two adjacent smileys would
 * fuse into an unparseable token (`:jap::cry:` is not `:jap: :cry:`). [surroundWithSpaces]
 * defaults to `true` to match the web behaviour ; tests can flip it off to assert the raw
 * insertion contract independently.
 *
 * The caret lands **after** the inserted fragment, collapsed (no selection) so the user can
 * keep typing. If a selection existed at insertion time, it is replaced — this matches
 * Compose `TextFieldValue` semantics and the behaviour of HFR's web composer.
 *
 * Defensive against the same `TextFieldValue` corner cases as [applyBbcodeAction] : out-of-
 * range or inverted selections are clamped + normalised rather than throwing.
 */
fun insertBbcodeToken(
    token: String,
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    surroundWithSpaces: Boolean = true,
): BbcodeFormatResult {
    val length = text.length
    val rawStart = selectionStart.coerceIn(0, length)
    val rawEnd = selectionEnd.coerceIn(0, length)
    val start = minOf(rawStart, rawEnd)
    val end = maxOf(rawStart, rawEnd)

    val fragment = if (surroundWithSpaces) " $token " else token
    val before = text.substring(0, start)
    val after = text.substring(end)

    val newText = buildString(length + fragment.length) {
        append(before)
        append(fragment)
        append(after)
    }
    val caret = before.length + fragment.length

    return BbcodeFormatResult(
        text = newText,
        selectionStart = caret,
        selectionEnd = caret,
    )
}
