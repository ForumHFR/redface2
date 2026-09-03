package fr.forumhfr.redface2.core.domain.forum

import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Read access to the HFR forum browsing layer (categories, subcategories, topic lists).
 * Phase 1C-A is REST-first per ADR-003 — the implementation in `:core:data` calls the
 * `/webservices/rest_api.php` endpoints. The repository keeps a small in-memory cache
 * for the categories list (rare to change); topic lists are not cached in memory.
 *
 * Flow semantics mirror [fr.forumhfr.redface2.core.domain.flags.FlagRepository]:
 * - [observeCategories] / [observeTopicList] emit [ForumResult.Loading] then a single
 *   [ForumResult.Success] or [ForumResult.Failure] for the initial fetch, then any
 *   subsequent payload broadcast by an explicit [refreshCategories] / [refreshTopicList].
 * - Switching tabs / pages cancels the previous flow via the consumer's `flatMapLatest`.
 */
interface ForumRepository {

    fun observeCategories(): Flow<ForumResult<List<Category>>>

    /**
     * Cache-only category stream for adornments that need ordering/labels but must not trigger the
     * categories TTL fetch on screen start. Emits `null` when the cache is cold, then any explicit
     * [refreshCategories] emissions. Default stays cold for tests/fakes that do not model the cache.
     */
    fun observeCachedCategories(): Flow<ForumResult<List<Category>>?> = flowOf(null)

    /**
     * One-shot categories read for callers that ENUMERATE the catalogue (the flags
     * per-category fan-out, #251) rather than render it reactively. [observeCategories]'s
     * stale path emits the still-cached (stale) value FIRST so the UI can paint
     * last-known-good without flashing Loading; a caller doing `.first { it !is Loading }`
     * therefore captures the STALE list. When a category was added to HFR after the 24h
     * categories cache was warmed (e.g. cat 32 « IA »), the flags fan-out then never
     * enumerated it and its drapeaux stayed invisible (#251).
     *
     * This returns a list guaranteed fresh when [forceRefreshIfStale] is true: a fresh
     * cache is returned as-is, a stale or cold cache triggers a network fetch and returns
     * its result. With [forceRefreshIfStale] false a stale cache is returned without a
     * network round-trip (cheap path for callers that tolerate staleness). Never emits
     * [ForumResult.Loading].
     *
     * Bound: "fresh" is the categories TTL, so a category added to HFR while the cache is
     * still within its TTL is only discovered once that window elapses — acceptable because
     * the categories cache is in-memory (cold on every process restart), so a relaunch
     * re-enumerates the live catalogue. A fetch failure with a stale cache present degrades
     * to last-known-good (the caller gets the stale list, not a Failure); only a cold cache
     * surfaces the Failure.
     */
    suspend fun getCategories(forceRefreshIfStale: Boolean = false): ForumResult<List<Category>>

    suspend fun refreshCategories()

    fun observeSubcategories(cat: Int): Flow<ForumResult<List<SubCategory>>>

    /**
     * Cache-only subcategory stream for list adornments that must not fan out across every category
     * on screen start. Emits the current in-memory/DB value when known, `null` when cold, then future
     * refresh emissions. It never performs a network request by itself; callers that decide a visible
     * category needs names must call [refreshSubcategories] explicitly.
     */
    fun observeCachedSubcategories(cat: Int): Flow<ForumResult<List<SubCategory>>?>

    suspend fun refreshSubcategories(cat: Int)

    fun observeTopicList(
        cat: Int,
        subcat: Int?,
        page: Int,
    ): Flow<ForumResult<TopicListPage>>

    suspend fun refreshTopicList(cat: Int, subcat: Int?, page: Int)

    /**
     * Listing of the user's flagged topics for a (sub)category and the given [bucket] —
     * the REST equivalent of the web `owntopic` filter (#455). Authenticated by nature
     * (HFR has no flags for an anonymous session). The server returns the whole bucket in
     * a single response (no real pagination), so this is a one-shot `suspend` call rather
     * than an observable flow : the screen re-fetches on filter change / pull-to-refresh.
     *
     * Returns a [TopicListPage] — the SAME model as the regular listing — so the category
     * screen reuses its row composable and the "resume at last read page" navigation
     * contract unchanged (the flag bucket payload carries `last_post_read_id` and the
     * last-read page, mapped exactly like the normal listing).
     *
     * [subcat] `null` queries the whole category, non-null narrows to the subcategory.
     */
    suspend fun getFlagFilteredTopics(
        cat: Int,
        subcat: Int?,
        bucket: FlagFilterBucket,
    ): ForumResult<TopicListPage>

    /**
     * Anonymous prefetch — unauthenticated REST request (cf. ADR-003 §
     * Prefetch) for `(cat, subcat, page)`. The response payload is
     * **intentionally discarded** : there is no client-side cache populated
     * by this method. Persisting an anonymous response would strip per-user
     * fields (`is_read`, `last_post_read_id`) that the screen relies on, and
     * `[observeTopicList]` will re-fetch authenticated on the next visit.
     *
     * The only benefit is **edge-cache warming on HFR / its CDN** : the next
     * authenticated request for this `(cat, subcat, page)` lands warmer at
     * the origin, shaving the connect/parse cost on HFR's side. The client
     * does not see latency improvements unless HFR's CDN layer caches across
     * authenticated and unauthenticated requests for the same URL — which
     * is the documented behaviour of `forums/hardwarefr/categories/{cat}/
     * topics/last/`.
     *
     * If a real client-side prefetch cache is needed in the future, it must
     * be a **separate** persistence path that round-trips to authenticated
     * before exposing the data — never feed this anonymous payload into the
     * existing flow. Failures are swallowed (best-effort);
     * [CancellationException] propagates.
     */
    suspend fun prefetchTopicList(cat: Int, subcat: Int?, page: Int)
}

/**
 * The three HFR drapeaux buckets, as a domain type so the ViewModel never depends on the
 * `:core:network` `HfrRestFlagBucket` enum. Mirrors the web `owntopic` filter (#455):
 * PARTICIPATED = `owntopic=1` (« sujets auxquels j'ai participé »), READ = `owntopic=2`
 * (« lus uniquement »), FAVORITES = `owntopic=3` (« mes favoris »).
 */
enum class FlagFilterBucket { PARTICIPATED, READ, FAVORITES }

/**
 * Tri-state outcome of a forum browsing fetch. The domain layer stays Compose-free so
 * ViewModels translate this to a UI state on their own terms.
 */
sealed class ForumResult<out T> {
    data object Loading : ForumResult<Nothing>()
    data class Success<T>(val value: T) : ForumResult<T>()
    data class Failure(val cause: Throwable) : ForumResult<Nothing>()
}
