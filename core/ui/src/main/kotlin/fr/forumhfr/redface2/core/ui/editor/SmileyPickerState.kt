package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.EditorSmiley

/**
 * State of the [SmileyPickerSheet], promoted from `:feature:editor` with the sheet (#387) so any
 * editor host (`:feature:editor` VMs, the MP editors, [SmileyPickerController]) shares one shape.
 */
/**
 * Phase 2F-B (#11 partial) — visibility + content of the smiley bottom-sheet picker.
 *
 *  - [Hidden] : sheet collapsed, no live work.
 *  - [Open] : sheet visible. `query` drives the wiki search ; `wiki` reflects the lifecycle
 *    of the latest search. The Standard tab does not need its own status because the
 *    [BUILTIN_HFR_SMILEYS][fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS] constant is
 *    available synchronously.
 */
sealed interface SmileyPickerState {
    data object Hidden : SmileyPickerState
    data class Open(
        val query: String = "",
        val wiki: WikiSearchState = WikiSearchState.Idle,
    ) : SmileyPickerState
}

/**
 * Lifecycle of the wiki smiley search call. `Idle` until the query crosses the
 * `query.length > 2` threshold HFR enforces ; `Loading` during the round-trip ; `Results`
 * on success ; `Error` on network or parse failure (the picker stays usable on the Standard
 * tab regardless).
 */
sealed interface WikiSearchState {
    data object Idle : WikiSearchState
    data object Loading : WikiSearchState
    data class Results(val items: List<EditorSmiley>) : WikiSearchState
    data object Error : WikiSearchState
}
