package fr.forumhfr.redface2.core.domain.editor

/**
 * Lightweight, non-blocking validation outcome for a draft BBCode body. Phase 2B-A
 * intentionally keeps this minimal — the editor never blocks the user, it just hints.
 *
 * The richer `validateBbcode` use case (mismatched closes, length warnings, anti-flood
 * pre-check) will land alongside the real submission flow in Phase 2C+ and reuse the
 * same vocabulary, plus dedicated variants. Lives in `:core:domain` so both the
 * post-level editor (`:feature:editor`) and the future topic-form ViewModel
 * (`#148` / `#149`) can share the same surface.
 */
sealed interface BbcodeValidation {
    data object Idle : BbcodeValidation
    data object EmptyDraft : BbcodeValidation
}

/**
 * Phase 2B-A's stand-in for a real `validateBbcode` use case: just flags blank drafts.
 * Returns [BbcodeValidation.Idle] for anything else — never produces a hard error so
 * the UI stays unblocked.
 */
fun validateBbcodeDraft(bbcode: String): BbcodeValidation =
    if (bbcode.isBlank()) BbcodeValidation.EmptyDraft else BbcodeValidation.Idle
