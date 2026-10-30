package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyFailure
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
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
 * Repository operations are callbacks supplied by the hosts, without Hilt in this controller.
 * [userId] feeds HFR's wiki endpoint and falls back to 0 when the session
 * has not resolved an id (proven harmless — same fallback as `PostEditorViewModel`).
 * [SmileyPickerFavoriteSources.favoriteSmileys] reads the latest loaded form ; hosts call
 * [refreshFavorites] when that form changes. Reopening restores the wiki search but always
 * reads the current favorites.
 * [onSearchFailed] lets a host apply its own failure policy (the `:feature:editor` ViewModels
 * record a diagnostics WARN — class name only, never the query nor the userId — while the MP
 * composers keep the no-op default).
 */
class SmileyPickerController(
    private val scope: CoroutineScope,
    private val searchWiki: suspend (userId: Int, query: String) -> List<EditorSmiley>,
    private val userId: () -> Int? = { null },
    private val onSearchFailed: (Throwable) -> Unit = {},
    private val favoriteSources: SmileyPickerFavoriteSources = SmileyPickerFavoriteSources(),
) {

    private val _state = MutableStateFlow<SmileyPickerState>(SmileyPickerState.Hidden)
    val state: StateFlow<SmileyPickerState> = _state.asStateFlow()

    private var searchJob: Job? = null

    private var formGeneration = 0L
    private var confirmedFavorites: FavoriteSnapshot? = null
    private var pendingFavoriteToken: String? = null
    private var optimisticFavorites: List<EditorSmiley>? = null
    private var favoriteFeedback: SmileyFavoriteFeedback? = null
    private var favoriteFeedbackGeneration = 0L
    private var accountGeneration = 0L
    private val favoriteCommand: ((EditorSmiley) -> Unit)? =
        if (favoriteSources.hfrFavoriteMutations != null) ::onFavoriteClicked else null
    private var localSnapshot = SmileyLocalCollectionSnapshot()
    private val localCommand: (EditorSmiley) -> Unit = ::onLocalFavoriteClicked

    init {
        // Collect for the whole ViewModel lifetime, including while the sheet is hidden.
        scope.launch {
            favoriteSources.localCollections?.observe()?.collect { snapshot ->
                val accountChanged = localSnapshot.owner != snapshot.owner
                if (accountChanged) {
                    accountGeneration++
                    favoriteFeedback = null
                    favoriteFeedbackGeneration++
                }
                localSnapshot = snapshot
                publishFavorites(resetDetail = accountChanged)
            }
        }
    }

    private data class FavoriteSnapshot(
        val generation: Long,
        val items: List<EditorSmiley>,
        val removedTokens: Set<String>,
    )

    private fun currentFavoriteSnapshot() = confirmedFavorites?.takeIf { it.generation == formGeneration }

    private fun currentFavorites(): List<EditorSmiley> = optimisticFavorites
        ?: mergedFavorites()

    private fun mergedFavorites(): List<EditorSmiley> {
        val base = favoriteSources.favoriteSmileys()
        val confirmed = currentFavoriteSnapshot() ?: return base
        val byToken = confirmed.items.associateBy { it.token }
        val baseTokens = base.mapTo(mutableSetOf()) { it.token }
        // The form also contains account smileys; only a REMOVE confirmed absent can hide a base entry.
        val retained = base.filterNot { it.token in confirmed.removedTokens }.map { byToken[it.token] ?: it }
        return retained + confirmed.items.filterNot { it.token in baseTokens }
    }

    private fun accountSmileyTokens(): Set<String> {
        val confirmed = currentFavoriteSnapshot() ?: return emptySet()
        val favoriteTokens = confirmed.items.mapTo(mutableSetOf()) { it.token }
        // Removed tokens stay eligible for re-addition from the Wiki.
        return favoriteSources.favoriteSmileys().mapTo(mutableSetOf()) { it.token } -
            favoriteTokens - confirmed.removedTokens
    }

    private fun withFavorites(open: SmileyPickerState.Open): SmileyPickerState.Open {
        val local = localSnapshot.favorites.takeIf { localSnapshot.owner != null }.orEmpty()
        val top = localSnapshot.top.takeIf { localSnapshot.owner != null }.orEmpty()
        val wiki = (open.wiki as? WikiSearchState.Results)?.items.orEmpty()
        val localImages = local.mapNotNull { item ->
            item.imageUrl?.let { EditorSmiley(item.token, it, EditorSmileySource.WIKI) }
        }
        val resolver = SmileyImageResolver(wiki + localImages + currentFavorites())
        return open.copy(
            favoriteSmileys = currentFavorites(),
            pendingFavoriteToken = pendingFavoriteToken,
            favoriteFeedback = favoriteFeedback,
            favoriteFeedbackGeneration = favoriteFeedbackGeneration,
            onFavoriteChange = favoriteCommand,
            accountSmileyTokens = accountSmileyTokens(),
            localFavoriteSmileys = local.mapNotNull { resolver.resolve(it.token, it.imageUrl) },
            mostUsedSmileys = top.mapNotNull { resolver.resolve(it.token) },
            localFavoriteTokens = local.mapTo(mutableSetOf()) { it.token },
            onLocalFavoriteChange = localCommand.takeIf { localSnapshot.owner != null },
            onDetailChange = ::onDetailChanged,
            usageCounts = localSnapshot.usageCounts.takeIf { localSnapshot.owner != null }.orEmpty(),
        )
    }

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
                // Keep the live search on a redundant open(), but always start on the grid.
                withFavorites(current.copy(detailSmiley = null))
            } else {
                // #824 — restore the last dismissed search (query + results) so reopening
                // right after an insertion / accidental swipe-down never costs a retype.
                val restored = withFavorites(lastDismissed ?: SmileyPickerState.Open())
                restored.copy(initialTab = initialTab(restored))
            }
        }
    }

    /** Null returns to the grid without dismissing the sheet or changing the live search. */
    fun onDetailChanged(smiley: EditorSmiley?) {
        _state.update { current ->
            val open = current as? SmileyPickerState.Open ?: return@update current
            open.copy(detailSmiley = smiley)
        }
    }

    private fun initialTab(open: SmileyPickerState.Open): SmileyPickerTab = when {
        open.query.isNotEmpty() -> SmileyPickerTab.WIKI
        open.favoriteSmileys.isNotEmpty() -> SmileyPickerTab.FAVORITES
        localSnapshot.owner == null -> SmileyPickerTab.STANDARD
        localSnapshot.favorites.isNotEmpty() -> SmileyPickerTab.FAVORITES
        localSnapshot.top.isNotEmpty() -> SmileyPickerTab.FAVORITES
        else -> SmileyPickerTab.STANDARD
    }

    private fun onLocalFavoriteClicked(smiley: EditorSmiley) {
        val open = _state.value as? SmileyPickerState.Open ?: return
        val owner = localSnapshot.owner ?: return
        val action = if (smiley.token in open.localFavoriteTokens) {
            SmileyFavoriteAction.REMOVE
        } else {
            SmileyFavoriteAction.ADD
        }
        val collections = requireNotNull(favoriteSources.localCollections)
        favoriteFeedback = null
        val generation = ++favoriteFeedbackGeneration
        val account = accountGeneration
        publishFavorites()
        scope.launch {
            val result = runCatching {
                when (action) {
                    SmileyFavoriteAction.ADD -> collections.add(owner, smiley.token, smiley.imageUrl)
                    SmileyFavoriteAction.REMOVE -> collections.remove(owner, smiley.token)
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                LocalFavoriteSmileyMutationResult.Failure(LocalFavoriteSmileyFailure.STORAGE_FAILURE)
            }
            updateFavoriteFeedback(result.localFeedback(action), generation, account)
            publishFavorites()
        }
    }

    private fun updateFavoriteFeedback(feedback: SmileyFavoriteFeedback?, generation: Long, account: Long) {
        // Account invalidation is independent of message priority, including a switch away and back.
        if (accountGeneration != account || feedback == null) return
        val success = when (feedback) {
            SmileyFavoriteFeedback.Added, SmileyFavoriteFeedback.Removed,
            SmileyFavoriteFeedback.LocalAdded, SmileyFavoriteFeedback.LocalRemoved -> true
            else -> false
        }
        if (success && favoriteFeedbackGeneration != generation) return
        // A failure must explain the rollback even after a newer gesture; restart its overlay timeout.
        if (!success) favoriteFeedbackGeneration++
        favoriteFeedback = feedback.takeUnless { success && _state.value is SmileyPickerState.Hidden }
    }

    /** Hosts notify after a form reload, including when the sheet is already visible. */
    fun refreshFavorites() {
        formGeneration++
        publishFavorites()
    }

    private fun publishFavorites(resetDetail: Boolean = false) {
        _state.update { current ->
            val open = current as? SmileyPickerState.Open ?: return@update current
            withFavorites(if (resetDetail) open.copy(detailSmiley = null) else open)
        }
    }

    fun onFavoriteClicked(smiley: EditorSmiley) {
        val open = _state.value as? SmileyPickerState.Open ?: return
        val action = open.favoriteAction(smiley) ?: return
        val callbacks = requireNotNull(favoriteSources.hfrFavoriteMutations)
        val mutation = when (action) {
            SmileyFavoriteAction.ADD -> callbacks.addHfrFavorite
            SmileyFavoriteAction.REMOVE -> callbacks.removeHfrFavorite
        }
        val before = currentFavorites()
        // Set synchronously, before launch: a second gesture in the same frame is ignored.
        pendingFavoriteToken = smiley.token
        optimisticFavorites = when (action) {
            SmileyFavoriteAction.ADD -> before + smiley.copy(source = EditorSmileySource.HFR_FAVORITE)
            // Keep the removal target marked in progress so insertion and its spinner remain available.
            SmileyFavoriteAction.REMOVE -> before
        }
        favoriteFeedback = null
        favoriteFeedbackGeneration++
        val feedbackGeneration = favoriteFeedbackGeneration
        val account = accountGeneration
        publishFavorites()
        scope.launch {
            try {
                val result = runCatching { mutation(smiley.token) }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    HfrFavoriteSmileyMutationResult.TransportFailure
                }
                // Only confirmed lists are stamped at completion; reads merge them with the current form base.
                // Without one, dropping the optimistic layer reveals the current base or still-valid snapshot.
                confirmFavorites(result, action, smiley.token)
                updateFavoriteFeedback(result.feedback(action), feedbackGeneration, account)
            } finally {
                optimisticFavorites = null
                pendingFavoriteToken = null
                publishFavorites()
            }
        }
    }

    private fun confirmFavorites(
        result: HfrFavoriteSmileyMutationResult,
        action: SmileyFavoriteAction,
        token: String,
    ) {
        val confirmed = result.confirmedList() ?: return
        val previousRemovals = currentFavoriteSnapshot()?.removedTokens.orEmpty()
        val removed = if (
            action == SmileyFavoriteAction.REMOVE && result !is HfrFavoriteSmileyMutationResult.NotInFavorites
        ) {
            previousRemovals + token
        } else {
            previousRemovals
        }
        val confirmedTokens = confirmed.mapTo(mutableSetOf()) { it.token }
        confirmedFavorites = FavoriteSnapshot(formGeneration, confirmed, removed - confirmedTokens)
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
            // End the first opening that displayed this feedback; hidden failures wait for an opening.
            favoriteFeedback = null
            lastDismissed = current.copy(
                detailSmiley = null,
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
        // "no live wiki search", and the debounce below would otherwise fire a network call whose
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
            withFavorites(open.copy(wiki = wiki))
        }
    }

    companion object {
        /** HFR's web composer debounce (`find_smilies_timer`, /compressed/message.js). */
        const val SEARCH_DEBOUNCE_MS = 300L

        /** HFR's web composer threshold : the wiki search arms at 3 typed characters. */
        const val MIN_WIKI_QUERY_LENGTH = 3
    }
}
