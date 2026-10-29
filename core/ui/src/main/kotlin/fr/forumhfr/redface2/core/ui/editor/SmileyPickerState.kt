package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource

/**
 * State of the [SmileyPickerSheet], promoted from `:feature:editor` with the sheet (#387) so any
 * editor host (`:feature:editor` VMs, the MP editors, [SmileyPickerController]) shares one shape.
 */
/**
 * Phase 2F-B (#11 partial) — visibility + content of the smiley bottom-sheet picker.
 *
 *  - [Hidden] : sheet collapsed; a favorite mutation can still finish in the controller.
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
        /** Standard tab's optional first section: server order, with a pending addition appended. */
        val favoriteSmileys: List<EditorSmiley> = emptyList(),
        val pendingFavoriteToken: String? = null,
        val favoriteFeedback: SmileyFavoriteFeedback? = null,
        /** Stable command supplied by the controller so all four sheets share the same gestures. */
        val onFavoriteChange: ((EditorSmiley) -> Unit)? = null,
        /** Distinguishes repeated feedback even when Compose skips the intermediate null state. */
        val favoriteFeedbackGeneration: Long = 0L,
    ) : SmileyPickerState {
        fun favoriteAction(smiley: EditorSmiley): SmileyFavoriteAction? = when {
            onFavoriteChange == null || pendingFavoriteToken != null -> null
            smiley.source == EditorSmileySource.BUILTIN -> null
            smiley.source == EditorSmileySource.HFR_FAVORITE -> SmileyFavoriteAction.REMOVE
            favoriteSmileys.any { it.token == smiley.token } -> SmileyFavoriteAction.REMOVE
            else -> SmileyFavoriteAction.ADD
        }
    }
}

enum class SmileyFavoriteAction { ADD, REMOVE }

/** Cleared after its first visible opening; hidden successes are silent, hidden failures wait for reopening. */
sealed interface SmileyFavoriteFeedback {
    data object Added : SmileyFavoriteFeedback
    data object Removed : SmileyFavoriteFeedback
    data object AlreadyPresent : SmileyFavoriteFeedback
    data class Full(val limit: Int) : SmileyFavoriteFeedback
    data object NotFound : SmileyFavoriteFeedback
    data object ListChanged : SmileyFavoriteFeedback
    data object NotAuthenticated : SmileyFavoriteFeedback
    data object TransportFailure : SmileyFavoriteFeedback
    data object UnknownResponse : SmileyFavoriteFeedback
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
