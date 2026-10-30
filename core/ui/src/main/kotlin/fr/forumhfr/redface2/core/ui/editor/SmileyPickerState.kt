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
        /** HFR section: form order, with new confirmed or pending favorites appended. */
        val favoriteSmileys: List<EditorSmiley> = emptyList(),
        val pendingFavoriteToken: String? = null,
        val favoriteFeedback: SmileyFavoriteFeedback? = null,
        /** Stable command supplied by the controller so all four sheets share the same gestures. */
        val onFavoriteChange: ((EditorSmiley) -> Unit)? = null,
        /** Distinguishes repeated feedback even when Compose skips the intermediate null state. */
        val favoriteFeedbackGeneration: Long = 0L,
        /** Form entries absent from confirmed removable favorites; no HFR action, local actions unaffected. */
        val accountSmileyTokens: Set<String> = emptySet(),
        /** Decided by open(), never recomputed by composition when collections arrive late. */
        val initialTab: SmileyPickerTab = if (query.isNotEmpty()) SmileyPickerTab.WIKI else SmileyPickerTab.STANDARD,
        val localFavoriteSmileys: List<EditorSmiley> = emptyList(),
        val mostUsedSmileys: List<EditorSmiley> = emptyList(),
        /** Exact membership includes local entries whose image cannot be resolved. */
        val localFavoriteTokens: Set<String> = emptySet(),
        val onLocalFavoriteChange: ((EditorSmiley) -> Unit)? = null,
        /** Kept by the host ViewModel's controller, even if its original lazy cell disappears. */
        val detailSmiley: EditorSmiley? = null,
        val onDetailChange: ((EditorSmiley?) -> Unit)? = null,
        val usageCounts: Map<String, Long> = emptyMap(),
        val wikiKeywords: SmileyWikiKeywordsState = SmileyWikiKeywordsState.Hidden,
        val onWikiKeywordsRetry: (() -> Unit)? = null,
        /** Mutation target and completion order, independent of the overlay's gesture generation. */
        val favoriteFeedbackToken: String? = null,
        val favoriteFeedbackEmission: Long = 0L,
        /** Captured at the gesture, before composition can receive an already-completed mutation. */
        val detailFeedbackEmissionAtEntry: Long = 0L,
    ) : SmileyPickerState {
        /** The detail can show an eligible action's pending state while mutations remain blocked. */
        fun favoriteAction(smiley: EditorSmiley, includePending: Boolean = false): SmileyFavoriteAction? = when {
            onFavoriteChange == null -> null
            pendingFavoriteToken != null && !includePending -> null
            smiley.source == EditorSmileySource.BUILTIN -> null
            smiley.token in accountSmileyTokens -> null
            favoriteSmileys.any { it.token == smiley.token } -> SmileyFavoriteAction.REMOVE
            else -> SmileyFavoriteAction.ADD
        }

        fun localFavoriteAction(smiley: EditorSmiley): SmileyFavoriteAction? = when {
            onLocalFavoriteChange == null -> null
            smiley.token in localFavoriteTokens -> SmileyFavoriteAction.REMOVE
            else -> SmileyFavoriteAction.ADD
        }
    }
}

enum class SmileyFavoriteAction { ADD, REMOVE }

enum class SmileyPickerTab { FAVORITES, STANDARD, WIKI }

sealed interface SmileyWikiKeywordsState {
    data object Hidden : SmileyWikiKeywordsState
    data object Loading : SmileyWikiKeywordsState
    data class Loaded(val keywords: List<String>) : SmileyWikiKeywordsState
    data object Failed : SmileyWikiKeywordsState
}

/** Cleared after its first visible opening; hidden successes are silent, hidden failures wait for reopening. */
sealed interface SmileyFavoriteFeedback {
    data object Added : SmileyFavoriteFeedback
    data object Removed : SmileyFavoriteFeedback
    data object AlreadyPresent : SmileyFavoriteFeedback
    data object NotInFavorites : SmileyFavoriteFeedback
    data class Full(val limit: Int) : SmileyFavoriteFeedback
    data object NotFound : SmileyFavoriteFeedback
    data object ListChanged : SmileyFavoriteFeedback
    data object NotAuthenticated : SmileyFavoriteFeedback
    data object TransportFailure : SmileyFavoriteFeedback
    data object UnknownResponse : SmileyFavoriteFeedback
    data object LocalAdded : SmileyFavoriteFeedback
    data object LocalRemoved : SmileyFavoriteFeedback
    data object LocalLimitReached : SmileyFavoriteFeedback
    data object LocalStorageFailure : SmileyFavoriteFeedback
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
