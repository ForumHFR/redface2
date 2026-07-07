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
 * since #440, the `:feature:editor` ViewModels since #441) gets the picker without
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
 * #824 — restore-on-reopen contract : [dismiss] snapshots the visible search (query + wiki
 * results) instead of dropping it, and the next [open] restores it, so an insertion (which
 * dismisses the sheet on all surfaces) or an accidental swipe-down never costs the user a
 * retype. `Results` are restored verbatim (no refetch) ; the transient `Loading` / `Error`
 * branches are normalised to `Idle` at snapshot time (a restored Loading would spin forever
 * — its job was cancelled — and a restored Error would resurface a stale failure). There is
 * deliberately no public reset : the controller lives and dies with its host ViewModel, whose
 * editorial context is frozen at construction, so « editor closed = search forgotten » is the
 * ViewModel lifecycle itself.
 *
 * [searchWiki] is a lambda (not `SmileyRepository`) so `:core:ui` gains no dependency on
 * `:core:domain` ; [userId] feeds HFR's wiki endpoint and falls back to 0 when the session
 * has not resolved an id (proven harmless — same fallback as `PostEditorViewModel`).
 * [onSearchFailed] lets a host apply its own failure policy (the `:feature:editor` ViewModels
 * record a diagnostics WARN — class name only, never the query nor the userId — while the MP
 * composers keep the no-op default).
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

    /**
     * #824 — snapshot of the last dismissed Open state, already normalised by [dismiss]
     * (`wiki` is either `Results` or `Idle`, never a transient branch). Null until the
     * first dismissal ; never cleared — the whole controller is dropped with its host
     * ViewModel, which is the intended invalidation boundary.
     */
    private var lastDismissed: SmileyPickerState.Open? = null

    fun open() {
        _state.update { current ->
            if (current is SmileyPickerState.Open) {
                // Idempotent while visible : a redundant open() must NOT clobber the live
                // query/results with the stale dismissal snapshot below.
                current
            } else {
                // #824 — restore the last dismissed search (query + results) so reopening
                // right after an insertion / accidental swipe-down never costs a retype.
                lastDismissed ?: SmileyPickerState.Open()
            }
        }
    }

    fun dismiss() {
        searchJob?.cancel()
        searchJob = null
        // #824 — keep the visible search for the next open(), normalising the transient
        // branches : a restored Loading would spin forever (its job was just cancelled
        // above) and a restored Error would resurface a stale failure, so both collapse
        // to Idle. Results are restored verbatim — no refetch on reopen ; staleness within
        // one editing session is acceptable (issue #824, immediate-reopen use case).
        val current = _state.value
        if (current is SmileyPickerState.Open) {
            lastDismissed = current.copy(
                wiki = when (val wiki = current.wiki) {
                    is WikiSearchState.Results -> wiki
                    WikiSearchState.Idle, WikiSearchState.Loading, WikiSearchState.Error ->
                        WikiSearchState.Idle
                },
            )
        }
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
