package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.EditorSmiley
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * #387 — self-contained state machine for the [SmileyPickerSheet], extracted from
 * `PostEditorViewModel`'s embedded logic so ANY editor host (the MP reply/compose ViewModels
 * today, the `:feature:editor` ViewModels as a follow-up migration) gets the picker without
 * re-implementing the debounce and its race guards.
 *
 * The host owns insertion : a tap on a smiley hands the BBCode token back to the host
 * (cf. `SmileyPickerSheet.onSmileyClicked`), which wraps it into its own draft via
 * `insertBbcodeToken` and then calls [dismiss].
 *
 * Wiki search contract (mirrors HFR's web composer, `/compressed/message.js`) :
 *  - queries of length ≤ 2 reset the wiki branch to Idle (the Standard tab stays usable) ;
 *  - a 300 ms debounce coalesces a typing burst ; `Loading` flips only AFTER the debounce so
 *    a one-burst « jap » never flashes a spinner ;
 *  - job-identity + query-equality guards drop stale responses (cancelled jobs, retyped
 *    queries) so an older network result can never overwrite a newer one.
 *
 * [searchWiki] is a lambda (not `SmileyRepository`) so `:core:ui` gains no dependency on
 * `:core:domain` ; [userId] feeds HFR's wiki endpoint and falls back to 0 when the session
 * has not resolved an id (proven harmless — same fallback as `PostEditorViewModel`).
 */
class SmileyPickerController(
    private val scope: CoroutineScope,
    private val searchWiki: suspend (userId: Int, query: String) -> List<EditorSmiley>,
    private val userId: () -> Int? = { null },
    private val onSearchFailed: (Throwable) -> Unit = {},
) {

    private val _state = MutableStateFlow<SmileyPickerState>(SmileyPickerState.Hidden)
    val state: StateFlow<SmileyPickerState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun open() {
        _state.update { current ->
            if (current is SmileyPickerState.Open) current else SmileyPickerState.Open()
        }
    }

    fun dismiss() {
        searchJob?.cancel()
        searchJob = null
        _state.value = SmileyPickerState.Hidden
    }

    fun onQueryChanged(query: String) {
        // A late callback landing after dismiss() must not arm a search : Hidden means
        // "no live work", and the debounce below would otherwise fire a network call whose
        // result is only THEN dropped by the Open guards (Codex review, PR #440).
        if (_state.value !is SmileyPickerState.Open) return
        _state.update { current ->
            val open = current as? SmileyPickerState.Open ?: return@update current
            open.copy(query = query)
        }
        // Cancel in-flight searches so an older response can't overwrite a newer query.
        searchJob?.cancel()
        if (query.length < MIN_WIKI_QUERY_LENGTH) {
            _state.update { current ->
                val open = current as? SmileyPickerState.Open ?: return@update current
                open.copy(wiki = WikiSearchState.Idle)
            }
            return
        }
        searchJob = scope.launch {
            // A superseding query cancels this job AT this suspension point (cancel() above),
            // so no explicit job-identity guard is needed : a job that survives the delay is
            // the latest one, and the query-equality guards below drop any residual stale
            // write (the only theoretical leak — the same query retyped inside the window —
            // converges to the same result anyway).
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { current ->
                val open = current as? SmileyPickerState.Open ?: return@update current
                if (open.query != query) return@update current
                open.copy(wiki = WikiSearchState.Loading)
            }
            val outcome = runCatching { searchWiki(userId() ?: 0, query) }
            outcome.fold(
                onSuccess = { items ->
                    updateWikiIfCurrent(query, WikiSearchState.Results(items))
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    onSearchFailed(error)
                    updateWikiIfCurrent(query, WikiSearchState.Error)
                },
            )
        }
    }

    /** Drops the result if the user closed the picker or typed a different query meanwhile. */
    private fun updateWikiIfCurrent(query: String, wiki: WikiSearchState) {
        _state.update { current ->
            val open = current as? SmileyPickerState.Open ?: return@update current
            if (open.query != query) return@update current
            open.copy(wiki = wiki)
        }
    }

    companion object {
        /** HFR's web composer debounce (`find_smilies_timer`, /compressed/message.js). */
        const val SEARCH_DEBOUNCE_MS = 300L

        /** HFR's web composer threshold : the wiki search arms at 3 typed characters. */
        const val MIN_WIKI_QUERY_LENGTH = 3
    }
}
