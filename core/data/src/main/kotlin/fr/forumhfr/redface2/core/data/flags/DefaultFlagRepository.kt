package fr.forumhfr.redface2.core.data.flags

import android.util.Log
import fr.forumhfr.redface2.core.data.forum.RestListEnvelope
import fr.forumhfr.redface2.core.data.forum.RestTopic
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.write.FlagDeleteResult
import fr.forumhfr.redface2.core.network.HfrApiClient
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.network.HfrRestFlagBucket
import fr.forumhfr.redface2.core.parser.write.FlagDeleteResponseParser
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Phase 1D-1 REST implementation of [FlagRepository] (cf. ADR-003, issue #110). Reads
 * the user's drapeaux from `forums/hardwarefr/categories/{cat}/topics/{participated,read,
 * favorites}/` via [HfrApiClient]. The legacy HTML scrape on `forum1f.php` has been
 * retired with this slice — `getFlagsPage` and the matching `FlagsListParser` are gone.
 *
 * **Why per-category and not the global endpoint** : the global drapeau endpoint
 * `forums/hardwarefr/topics/{bucket}/` advertises a grouped-by-category envelope
 * distinct from the flat [RestListEnvelope]<[RestTopic]> shape we have a captured
 * fixture for. Without that fixture we cannot prove the parser, so the global helper
 * is **not** exposed by `HfrApiClient` (cf. AGENTS.md § "noyau avant écosystème" —
 * pas d'API morte). We route through the per-cat REST endpoint whose contract is
 * proven by `rest_cat23_participated.json`. Cost : one REST GET per HFR public
 * category (~19 cats), parallelised through the IO dispatcher. A follow-up PR can
 * add the global helper and switch consumption once the payload is captured.
 *
 * The first [observe] call per [FlagType] fans out across categories, concatenates
 * the results, **sorts globally by `lastReplyAt` desc** so the screen shows topics
 * by activity instead of grouping by cat, and caches the success for the current
 * auth session ; tab switches reuse the cache so the screen does not implicitly
 * mark drapeaux as read by re-hitting the auth REST endpoint. Explicit [refresh]
 * calls always fetch and broadcast through a per-type [MutableSharedFlow].
 *
 * Phase 1D-3 adds a Room cache (`flag_topics`) scoped by lowercased pseudo. A
 * fresh disk cache avoids the REST fan-out ; a stale disk cache is displayed first
 * while the repository attempts a background refresh. [clearSessionCache] still
 * clears the process cache immediately on logout / account switch.
 */
// #99 added the HTML mutation collaborators (HfrClient + delflag parser) on top of the
// existing REST read deps ; all 8 are distinct Hilt-injected singletons, a parameter object
// would only hide the dependency surface from DI.
@Suppress("LongParameterList")
@Singleton
class DefaultFlagRepository @Inject constructor(
    private val apiClient: HfrApiClient,
    private val hfrClient: HfrClient,
    private val flagDeleteResponseParser: FlagDeleteResponseParser,
    private val forumRepository: ForumRepository,
    private val authRepository: AuthRepository,
    private val flagCacheStore: FlagCacheStore,
    @param:FlagsJson private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : FlagRepository {

    private val cachedSuccesses: MutableMap<FlagType, FlagsResult.Success> = EnumMap(FlagType::class.java)

    // Bumped by [clearSessionCache] on logout / account switch, read+written under the cachedSuccesses
    // lock. A fetch that began before a switch must not write its result into the (type-keyed)
    // singleton cache afterwards (#501 Codex P1) — it compares this generation at write time.
    private var sessionGeneration = 0

    /**
     * One refresh-trigger per [FlagType] so a refresh on one tab does not re-fetch the
     * other two. Replay = 0 because [observe] emits its own cached-or-initial result;
     * the shared flow only carries explicit refresh acks.
     */
    private val refreshes: Map<FlagType, MutableSharedFlow<FlagsResult>> = FlagType.entries
        .associateWith {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }

    /**
     * #501 (Codex review) — in-flight fetch coordinator, one [Deferred] per [FlagType]. observe()'s
     * initial fetch (first subscription with no fresh cache) and an explicit refresh() can fire for
     * the SAME tab at the same instant (the screen's auto-refresh lands as the list first subscribes),
     * each otherwise running the full per-category REST fan-out. Sharing one in-flight Deferred
     * collapses concurrent calls into a single fan-out; a completed fetch is cleared, so a LATER
     * refresh still triggers a fresh one. Launched in an app-scoped CoroutineScope (this is a
     * @Singleton, so the scope lives for the process) so cancelling one caller — e.g. observe() torn
     * down by a flatMapLatest tab switch — never cancels the fetch a concurrent refresh still awaits.
     */
    private val fetchScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Identifies an in-flight fetch by BOTH type and user — never share a fetch across accounts. */
    private data class FetchKey(val type: FlagType, val userId: String)

    // Keyed by (type, userId), NOT type alone: a fetch started for one account must never be awaited
    // by another after a logout / account switch, and [clearSessionCache] cancels stragglers so they
    // cannot repopulate the singleton success cache with the previous account's data (#501 Codex P1).
    private val inFlightFetches: MutableMap<FetchKey, Deferred<FlagsResult>> = mutableMapOf()

    override fun observe(type: FlagType): Flow<FlagsResult> = flow {
        val cached = synchronized(cachedSuccesses) { cachedSuccesses[type] }
        if (cached != null) {
            emit(cached)
        } else {
            val userId = currentUserId()
            if (userId == null) {
                emit(notAuthenticatedFailure())
            } else {
                val diskCached = flagCacheStore.load(type = type, userId = userId)
                if (diskCached != null) {
                    synchronized(cachedSuccesses) { cachedSuccesses[type] = diskCached.result }
                    emit(diskCached.result)
                    if (!diskCached.isFresh) {
                        when (val refreshed = fetchDeduplicated(type = type, userId = userId)) {
                            is FlagsResult.Success -> emit(refreshed)
                            is FlagsResult.Failure -> Log.w(
                                LOG_TAG,
                                "Keeping stale flags cache for $type after refresh failure",
                                refreshed.cause,
                            )
                            FlagsResult.Loading -> Unit
                        }
                    }
                } else {
                    emit(FlagsResult.Loading)
                    emit(fetchDeduplicated(type = type, userId = userId))
                }
            }
        }
        emitAll(refreshes.getValue(type).asSharedFlow())
    }

    override suspend fun refresh(type: FlagType) {
        // #862 — an EXPLICIT refresh opens a new sweep generation : the shared topics/last sweep
        // is a coalescing device for one refresh burst, never a temporal cache — a user who just
        // flagged a sticky and pulls must see it (gate Sol r2, no business TTL). The bump is
        // idempotent PER BURST (gate Sol r3) : it only fires when the current generation has
        // already materialised a sweep, so several refresh(type) calls of one global pull —
        // arriving before their first sweep exists — land in the SAME new generation and share
        // one Deferred, while a later pull (its generation's sweep exists) re-probes.
        synchronized(stickySweeps) {
            if (stickySweeps.keys.any { it.sweepGeneration == sweepGeneration }) {
                sweepGeneration++
            }
        }
        val refreshesForType = refreshes.getValue(type)
        refreshesForType.emit(FlagsResult.Loading)
        val userId = currentUserId()
        refreshesForType.emit(
            if (userId == null) {
                notAuthenticatedFailure()
            } else {
                fetchDeduplicated(type = type, userId = userId)
            },
        )
    }

    override fun clearSessionCache() {
        // Bump the generation under the same lock as the write guard: a fetch in flight across this
        // point will see a changed generation and skip its cache write (#501 Codex P1).
        synchronized(cachedSuccesses) {
            cachedSuccesses.clear()
            sessionGeneration++
        }
        // Cancel any fetch still in flight from the previous session so it stops early instead of
        // running the full fan-out for an account that just logged out / switched.
        synchronized(inFlightFetches) {
            inFlightFetches.values.forEach { it.cancel() }
            inFlightFetches.clear()
        }
        // #862 — same rule for the shared topics/last sweep : never reused across accounts.
        synchronized(stickySweeps) {
            stickySweeps.values.forEach { it.cancel() }
            stickySweeps.clear()
        }
    }

    /**
     * Removes [flag] via `delflag.php` (#99) and reconciles the caches on success only.
     *
     * Flow :
     * 1. Resolve the authenticated user ; abort with a failed [Result] if anonymous (the
     *    delflag GET would land on the login page anyway).
     * 2. GET `/user/delflag.php` through [HfrClient] (HTML mutation per ADR-003) and
     *    classify the body with [FlagDeleteResponseParser]. Network + Jsoup parse are both
     *    on [ioDispatcher] — `HfrClient` already hops, but we keep the explicit
     *    `withContext` so the CPU-bound parse never runs on the caller's dispatcher (project
     *    rule, cf. `DefaultReplyRepository`).
     * 3. **Success** → drop the row from the in-memory success cache *and* Room (logical key
     *    `cat + topicId + type`), then re-broadcast the trimmed list to active observers of
     *    [flag]'s [Flag.type] so the screen updates without a refetch.
     * 4. **Failure / unexpected page** → touch no cache, return [Result.failure].
     *
     * A [SessionExpiredException] (or any transport error) raised by [HfrClient] propagates
     * as a failed [Result] via [runCatching] — no cache is mutated, matching the read path's
     * "never trust a half-finished mutation" stance.
     */
    override suspend fun removeFlag(flag: Flag): Result<Unit> {
        val userId = currentUserId()
            ?: return Result.failure(IllegalStateException("Removing a flag requires an authenticated HFR session"))

        return runCatching {
            val result = withContext(ioDispatcher) {
                val response = hfrClient.removeFlag(
                    cat = flag.cat,
                    subcat = flag.subcat,
                    topicId = flag.topicId,
                    type = flag.type,
                    page = flag.lastReadPage,
                )
                flagDeleteResponseParser.parse(response)
            }
            when (result) {
                FlagDeleteResult.Success -> Unit
                FlagDeleteResult.Failure -> throw FlagDeleteFailedException(flag.topicId)
            }
        }.onSuccess {
            evictFlagFromCaches(userId = userId, flag = flag)
        }
    }

    /**
     * #809 — resolves the full [Flag] for a `(cat, topicId)` pair so callers outside the Drapeaux
     * view (the topic top-bar long-press) can feed [removeFlag] the complete object it keys the
     * `delflag.php` mutation on. Never fabricates a partial [Flag].
     *
     * Memory-first, **per-bucket** : scans the warm per-type success caches under the
     * [cachedSuccesses] lock. On a hit, returns immediately (no network). On a miss, only the COLD
     * buckets are fetched (each writes its success into [cachedSuccesses] via [fetch]'s
     * generation-guarded write), then a re-scan resolves. A warm bucket is authoritative **for its
     * own type only** and is never implicitly refreshed (the Drapeaux view owns refresh policy) —
     * but it says nothing about the other types : the Drapeaux screen warms one tab at a time, so
     * « CYAN warm + miss » must still check a never-loaded FAVORITE bucket (review finding : the
     * earlier any-bucket-warm short-circuit made a FAVORITE-only topic unremovable from the topic
     * screen until its tab had been visited). All three warm + miss → null with zero network. An
     * anonymous session holds no drapeaux, so it short-circuits to null before any round-trip.
     *
     * When a topic carries several drapeaux (e.g. participated AND favori), the [FlagType] iteration
     * order (CYAN, RED, FAVORITE) breaks the tie deterministically — enough for the MVP « retirer »,
     * which keys `delflag.php` on that row's own `type`.
     */
    override suspend fun findFlag(cat: Int, topicId: Int): Flag? {
        val (cachedMatch, warmTypes) = synchronized(cachedSuccesses) {
            scanCachedFlags(cat = cat, topicId = topicId) to cachedSuccesses.keys.toSet()
        }
        if (cachedMatch != null) return cachedMatch
        // Warm buckets are authoritative for their own type ; only the COLD ones need a fetch.
        val coldTypes = FlagType.entries.filterNot { it in warmTypes }
        val userId = currentUserId()
        if (coldTypes.isNotEmpty() && userId != null) {
            // Same parallel idiom as [fetch]'s per-cat fan-out. [fetchDeduplicated] collapses this
            // with any concurrent observe/refresh of the same bucket.
            coroutineScope {
                coldTypes.map { type ->
                    async { fetchDeduplicated(type = type, userId = userId) }
                }.awaitAll()
            }
        }
        return synchronized(cachedSuccesses) { scanCachedFlags(cat = cat, topicId = topicId) }
    }

    /**
     * First flag matching `(cat, topicId)` across every warm per-type bucket, or null. Must be called
     * under the [cachedSuccesses] lock (#809). EnumMap iteration is CYAN → RED → FAVORITE, so a topic
     * present in several buckets resolves deterministically to the earliest one.
     */
    private fun scanCachedFlags(cat: Int, topicId: Int): Flag? =
        cachedSuccesses.values
            .asSequence()
            .flatMap { it.flags.asSequence() }
            .firstOrNull { it.cat == cat && it.topicId == topicId }

    /**
     * Drops [flag] from the in-memory success cache and Room, then re-emits the trimmed
     * list to active observers of its tab. Called only after a confirmed `delflag.php`
     * success, so it never has to reason about a partial mutation.
     */
    private suspend fun evictFlagFromCaches(userId: String, flag: Flag) {
        val updated: FlagsResult.Success? = synchronized(cachedSuccesses) {
            val current = cachedSuccesses[flag.type] ?: return@synchronized null
            val trimmed = current.flags.filterNot {
                it.cat == flag.cat && it.topicId == flag.topicId
            }
            FlagsResult.Success(trimmed).also { cachedSuccesses[flag.type] = it }
        }

        // Room eviction is best-effort : the network deletion already succeeded, so a Room
        // hiccup must not turn a successful removal into a user-visible failure. A stale
        // disk row would be corrected on the next refresh anyway.
        runCatching {
            flagCacheStore.delete(
                userId = userId,
                type = flag.type,
                cat = flag.cat,
                topicId = flag.topicId,
            )
        }.onFailure { throwable ->
            Log.w(LOG_TAG, "Could not evict deleted flag ${flag.topicId} from Room cache", throwable)
        }

        // Re-broadcast outside the lock so observers see the trimmed list immediately
        // (mirrors the optimistic-free contract : the cache is the source of truth and the
        // network deletion already confirmed).
        if (updated != null) {
            refreshes.getValue(flag.type).emit(updated)
        }
    }

    /**
     * Runs [fetch] for [type] unless an identical fetch is already in flight, in which case the
     * concurrent caller awaits the same result instead of launching a second per-category fan-out
     * (#501, Codex review). [fetch] folds every failure into [FlagsResult.Failure] and never throws,
     * so the shared [Deferred] always completes with a result (no exception to propagate on await).
     */
    private suspend fun fetchDeduplicated(type: FlagType, userId: String): FlagsResult {
        val key = FetchKey(type = type, userId = userId)
        val deferred = synchronized(inFlightFetches) {
            inFlightFetches[key]?.takeIf { it.isActive }
                ?: fetchScope.async { fetch(type = type, userId = userId) }.also { started ->
                    inFlightFetches[key] = started
                    started.invokeOnCompletion {
                        synchronized(inFlightFetches) {
                            if (inFlightFetches[key] === started) inFlightFetches.remove(key)
                        }
                    }
                }
        }
        return deferred.await()
    }

    private suspend fun fetch(type: FlagType, userId: String): FlagsResult = withContext(ioDispatcher) {
        val startGeneration = synchronized(cachedSuccesses) { sessionGeneration }
        // #862 (gate Sol r3) — capture the SWEEP generation too : a fetch that started under an
        // older burst must never join a newer burst's sweep (it would publish a mix of old bucket
        // rows + new supplement, and holes would leak across generations).
        val startSweepGeneration = synchronized(stickySweeps) { sweepGeneration }
        val result = runCatching {
            val cats = loadCategories()
            val bucket = type.toBucket()
            // `coroutineScope` (fail-all) is intentional over `supervisorScope` (best-effort).
            // A flags screen must surface a network failure with the "Réessayer" affordance
            // — partial results would silently hide an outage and let the user think they
            // are up to date. If one cat REST call throws, all siblings are cancelled and
            // [FlagsResult.Failure] propagates with the original cause.
            coroutineScope {
                val bucketFlags = cats.map { category ->
                    async { fetchAllPages(cat = category.id, bucket = bucket, type = type) }
                }.awaitAll().flatten()
                // #251/#862 — the per-cat flag buckets DROP flagged STICKY topics in EVERY
                // category, not just the no-subcategory ones (#251's proof was cat 32 « IA » ;
                // #862 re-proved it live on cat 13 « Discussions » : a favorite flag on the sticky
                // « Rappel droits d'auteurs » is absent from `topics/favorites/` while
                // `topics/last/` at CATEGORY level carries it with `flag_owntopic=3` — including
                // stickies scoped to subcategories, fixture rest_cat13). Supplement from
                // `topics/last` for ALL cats (~19 parallel best-effort GETs) and merge,
                // deduplicated, BEFORE the global sort + cache so the sticky survives the next
                // refresh. A marginal sticky must not fail the whole screen (see
                // [fetchStickyFlagSupplement]).
                val stickySupplement = fetchStickyFlagSupplement(
                    cats = cats,
                    type = type,
                    alreadyPresent = bucketFlags,
                    userId = userId,
                    sessionGen = startGeneration,
                    sweepGen = startSweepGeneration,
                )
                (bucketFlags + stickySupplement)
                    // Per-category fan-out concatenates results in cat-iteration order — without a
                    // global sort the screen would group by cat (Discussions block, then Tech block,
                    // …) instead of showing the most recent activity first. `lastReplyAt` is the REST
                    // string `YYYY-MM-DD HH:mm`, so lexicographic descending == chronological
                    // descending. Empty strings (defensive — should not happen on REST flags) sort
                    // last, which is the right "unknown date goes to the bottom" behaviour.
                    .sortedByDescending { it.lastReplyAt }
            }
        }.fold(
            onSuccess = { flags ->
                persistFlags(userId = userId, type = type, flags = flags)
                FlagsResult.Success(flags)
            },
            onFailure = { throwable ->
                Log.w(LOG_TAG, "Flags REST fetch failed for $type", throwable)
                FlagsResult.Failure(throwable)
            },
        )
        // Cache the success ONLY if it still belongs to the active session (#501 Codex P1). observe()
        // serves this type-keyed singleton cache BEFORE resolving the current user, so a stale write
        // would hand the next account the previous account's flags. Two guards cover every ordering of
        // a logout / account switch vs. this fetch: the user must still be the one we fetched for (a
        // fetch launched for a now-stale account, racing the switch, is dropped), AND no
        // clearSessionCache must have bumped the generation since this fetch began (a switch DURING the
        // fetch). The disk cache (persistFlags) is already userId-scoped, so it keeps each account's data.
        if (result is FlagsResult.Success && currentUserId() == userId) {
            synchronized(cachedSuccesses) {
                if (sessionGeneration == startGeneration) cachedSuccesses[type] = result
            }
        }
        result
    }

    private suspend fun persistFlags(userId: String, type: FlagType, flags: List<Flag>) {
        runCatching {
            flagCacheStore.replace(userId = userId, type = type, flags = flags)
        }.onFailure { throwable ->
            Log.w(LOG_TAG, "Could not persist flags cache for $type", throwable)
        }
    }

    private suspend fun currentUserId(): String? = when (val state = authRepository.observeAuthState().first()) {
        AuthState.Anonymous -> null
        is AuthState.Authenticated -> state.pseudo.lowercase()
    }

    private fun notAuthenticatedFailure(): FlagsResult.Failure =
        FlagsResult.Failure(IllegalStateException("Flags require an authenticated HFR session"))

    /**
     * Resolves the REST-callable category list to FAN OUT over. Uses
     * [ForumRepository.getCategories] with `forceRefreshIfStale = true` rather than
     * `observeCategories().first { … }`.
     *
     * **Why not `observeCategories().first`** (#251): the observe flow's stale path emits
     * the still-cached (stale) value FIRST so the UI can paint last-known-good without a
     * Loading flash, so `first { it !is Loading }` captured the STALE catalogue. A category
     * added to HFR after the 24h categories cache was warmed (e.g. cat 32 « IA ») was then
     * never enumerated here, and its drapeaux stayed invisible. The one-shot
     * [ForumRepository.getCategories] returns a guaranteed-fresh list when stale (a fresh
     * cache costs no round-trip), so the fan-out always sees the current catalogue. It never
     * emits `Loading`.
     *
     * Filters out categories whose [Category.id] is not strictly positive (defensive guard
     * against a hypothetical `cat=0` modos space leaking through — the REST flag endpoint
     * would 403 on it). Non-numeric pseudo-cats like `cat=prive` (MPs) cannot reach this
     * method because `Category.id` is typed `Int`.
     */
    private suspend fun loadCategories(): List<Category> {
        val all = when (val result = forumRepository.getCategories(forceRefreshIfStale = true)) {
            is ForumResult.Success -> result.value
            is ForumResult.Failure -> throw result.cause
            ForumResult.Loading -> error("getCategories never emits Loading")
        }
        return all.filter { it.id > 0 }
    }

    /**
     * Walks every page of `categories/{cat}/topics/{bucket}/` until we have accumulated
     * at least `results_count` rows, the server returns an empty page (defensive —
     * shouldn't happen but prevents an infinite loop on a malformed envelope), or we
     * hit [MAX_PAGES] (anti-runaway hard cap, would mean ~5000 flagged topics in a
     * single category which is well beyond any realistic user).
     *
     * Pagination is driven by [accumulated.size] (not `page * results_per_page`) because
     * the latter is fragile: the server can normalise `results_per_page` to a value
     * different from the requested one, return a partial last page, or report a
     * `results_count` that does not match an exact multiple of `results_per_page`.
     */
    private suspend fun fetchAllPages(
        cat: Int,
        bucket: HfrRestFlagBucket,
        type: FlagType,
    ): List<Flag> {
        val accumulated = mutableListOf<Flag>()
        var page = 1
        var lastResultsCount = 0
        while (page <= MAX_PAGES) {
            val body = apiClient.getCategoryFlagTopics(
                cat = cat,
                bucket = bucket,
                page = page,
                resultsPerPage = DEFAULT_RESULTS_PER_PAGE,
                useAuth = true,
            )
            val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(body)
            val mapped = RestFlagMappers.toFlags(
                envelope = envelope,
                type = type,
                fallbackCat = cat,
            )
            accumulated += mapped
            lastResultsCount = envelope.resource.resultsCount
            if (mapped.isEmpty() || accumulated.size >= lastResultsCount) return accumulated
            page += 1
        }
        // Loop exited via the MAX_PAGES cap. Surface the silent truncation in adb logs
        // so it can be diagnosed before the user complains — defensive cap, but if it
        // ever fires the listing is incomplete and the symptom would otherwise be
        // invisible.
        if (accumulated.size < lastResultsCount) {
            Log.w(
                LOG_TAG,
                "REST flags pagination hit MAX_PAGES=$MAX_PAGES for cat=$cat bucket=$bucket " +
                    "with ${accumulated.size}/$lastResultsCount rows; list may be truncated",
            )
        }
        return accumulated
    }

    /**
     * #251/#862 — best-effort supplement for flagged STICKY topics the per-cat flag buckets drop
     * (server-side : proven on cat 32 « IA » for a no-subcat cyan, re-proven live 2026-07-13 on
     * cat 13 « Discussions » for a favorite — the sticky is absent from its bucket but present in
     * CATEGORY-level `topics/last/` with its `flag_owntopic`, subcategory-scoped stickies
     * included). For each [cats] entry, reads page 1 of `categories/{cat}/topics/last/`
     * (authenticated — `flag_owntopic` and `is_read` are per-user) and keeps the sticky rows whose
     * flag routes to [type] ([RestFlagMappers.toStickyFlags]). Rows already in [alreadyPresent]
     * (same `(cat, topicId)`) are dropped to avoid duplicates with the bucket fan-out.
     *
     * Best-effort (#251 Codex gate): although it runs in the SAME `coroutineScope` as the bucket
     * fan-out, each cat's `topics/last` call is wrapped in its own `runCatching`, so a network/JSON
     * failure logs and yields nothing rather than failing the whole refresh — recovering a marginal
     * sticky is not worth turning the screen into a "Réessayer" error. (A `CancellationException` is
     * rethrown, not swallowed, to keep structured concurrency intact.) Page 1 only:
     * stickies always head the listing, so deeper pages would be pure network noise for this fix.
     * #862 widened the scope from no-subcategory cats to ALL cats (~19 parallel GETs per refresh,
     * same order of magnitude as the bucket fan-out itself) — the drop was proven category-wide.
     */
    @Suppress("LongParameterList") // generation guards ride the fetch context — a holder would obscure them
    private suspend fun fetchStickyFlagSupplement(
        cats: List<Category>,
        type: FlagType,
        alreadyPresent: List<Flag>,
        userId: String,
        sessionGen: Int,
        sweepGen: Int,
    ): List<Flag> {
        // A stale generation (post-clearSessionCache caller, or a fetch that started under an
        // older refresh burst) or an empty catalogue all degrade to « no supplement » — never
        // create a sweep for a logged-out account, never join a newer burst's sweep.
        val sweep = if (cats.isEmpty()) null else stickySweep(cats, userId, sessionGen, sweepGen)
        if (sweep == null) return emptyList()
        val present = alreadyPresent.mapTo(mutableSetOf()) { it.cat to it.topicId }
        val bodies = try {
            sweep.await()
        } catch (cancellation: CancellationException) {
            // Distinguish « the sweep was cancelled » (session change — degrade, best-effort)
            // from « WE were cancelled » (structured concurrency — rethrow).
            currentCoroutineContext().ensureActive()
            Log.w(LOG_TAG, "Sticky sweep cancelled (session change) — empty supplement", cancellation)
            emptyMap()
        }
        return cats.flatMap { category ->
            val body = bodies[category.id] ?: return@flatMap emptyList()
            runCatching {
                val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(body)
                RestFlagMappers.toStickyFlags(envelope = envelope, type = type, fallbackCat = category.id)
            }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                Log.w(LOG_TAG, "Sticky sweep decode failed for cat=${category.id} (best-effort)", throwable)
                emptyList()
            }
        }.filter { (it.cat to it.topicId) !in present }
    }

    /**
     * #862 (gate Sol r2) — ONE `topics/last` sweep per REFRESH GENERATION, shared by the three
     * flag types : per-type sweeps would issue 3 × ~19 largely identical GETs on a full refresh
     * (57 instead of 19). This is a COALESCING device, never a temporal cache (no TTL, no wall
     * clock) : the sweep is keyed by (session generation, sweep generation, user, category-set
     * signature) — the types of one burst share exactly one [Deferred] ; an explicit [refresh]
     * bumps [sweepGeneration] so a manual pull always re-probes ; a partially failed sweep only
     * ever serves its own generation ; a stale post-[clearSessionCache] caller (its captured
     * session generation no longer matches) gets `null` and degrades instead of creating a sweep
     * for a logged-out account. Runs in [fetchScope] so the caller that LOSES a tab-switch race
     * does not cancel the sweep a sibling type still awaits ; [clearSessionCache] cancels and
     * drops everything. Per-cat failures degrade to a null body (best-effort, #251). Older
     * generations' entries are pruned WITHOUT cancel (their awaiters finish, the refs are dropped).
     */
    private fun stickySweep(
        cats: List<Category>,
        userId: String,
        sessionGen: Int,
        sweepGen: Int,
    ): Deferred<Map<Int, String?>>? = synchronized(stickySweeps) {
        val currentSessionGen = synchronized(cachedSuccesses) { sessionGeneration }
        if (sessionGen != currentSessionGen) return@synchronized null
        // Gate Sol r3 — a fetch that started under an older burst is refused (it degrades to an
        // empty supplement) exactly like a stale session : generations never mix.
        if (sweepGen != sweepGeneration) return@synchronized null
        val key = SweepKey(
            sessionGeneration = sessionGen,
            sweepGeneration = sweepGen,
            userId = userId,
            catIds = cats.map { it.id },
        )
        stickySweeps[key] ?: run {
            stickySweeps.keys.filter { it != key }.forEach(stickySweeps::remove)
            fetchScope.async {
                coroutineScope {
                    cats.map { category ->
                        async {
                            category.id to runCatching {
                                apiClient.getTopicList(
                                    cat = category.id,
                                    subcat = null,
                                    page = 1,
                                    resultsPerPage = DEFAULT_RESULTS_PER_PAGE,
                                    useAuth = true,
                                )
                            }.getOrElse { throwable ->
                                // Never swallow a coroutine cancellation as a best-effort
                                // "miss" — rethrow it so structured concurrency stays intact
                                // (#251 Codex review). Only network failures degrade to null.
                                if (throwable is CancellationException) throw throwable
                                Log.w(
                                    LOG_TAG,
                                    "Sticky sweep failed for cat=${category.id} (best-effort)",
                                    throwable,
                                )
                                null
                            }
                        }
                    }.awaitAll().toMap()
                }
            }.also { stickySweeps[key] = it }
        }
    }

    /** #862 — identity of one shared sweep : session + refresh burst + account + category set. */
    private data class SweepKey(
        val sessionGeneration: Int,
        val sweepGeneration: Int,
        val userId: String,
        val catIds: List<Int>,
    )

    private val stickySweeps: MutableMap<SweepKey, Deferred<Map<Int, String?>>> = mutableMapOf()

    // #862 — bumped by every explicit [refresh] under the stickySweeps lock : a manual pull is a
    // NEW generation, never served by the previous burst's sweep.
    private var sweepGeneration = 0

    private fun FlagType.toBucket(): HfrRestFlagBucket = when (this) {
        FlagType.CYAN -> HfrRestFlagBucket.PARTICIPATED
        FlagType.RED -> HfrRestFlagBucket.READ
        FlagType.FAVORITE -> HfrRestFlagBucket.FAVORITES
    }

    private companion object {
        const val LOG_TAG = "FlagRepository"
        const val DEFAULT_RESULTS_PER_PAGE = 50
        const val MAX_PAGES = 100
    }
}

/**
 * Raised internally by [DefaultFlagRepository.removeFlag] when HFR's `delflag.php`
 * response did not carry the « Drapeau effacé avec succès » confirmation (the drapeau
 * was already gone, the deletion was refused, or HFR served an unexpected page). It is
 * the cause wrapped in the failed [Result] returned to the caller, so the UI can surface
 * a generic "could not remove" message. No response body is carried — the page can embed
 * session metadata.
 */
class FlagDeleteFailedException(topicId: Int) :
    Exception("HFR did not confirm the drapeau removal for topic $topicId")
