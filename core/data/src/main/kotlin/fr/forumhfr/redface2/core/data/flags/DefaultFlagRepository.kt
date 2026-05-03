package fr.forumhfr.redface2.core.data.flags

import android.util.Log
import fr.forumhfr.redface2.core.data.cache.CachePolicy
import fr.forumhfr.redface2.core.data.forum.RestListEnvelope
import fr.forumhfr.redface2.core.data.forum.RestTopic
import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.database.entities.FlagTopicEntity
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
import fr.forumhfr.redface2.core.network.HfrApiClient
import fr.forumhfr.redface2.core.network.HfrRestFlagBucket
import java.time.Clock
import java.time.Instant
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
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
@Singleton
class DefaultFlagRepository @Inject constructor(
    private val apiClient: HfrApiClient,
    private val forumRepository: ForumRepository,
    private val authRepository: AuthRepository,
    private val flagDao: FlagDao,
    private val clock: Clock,
    @param:FlagsJson private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : FlagRepository {

    private val cachedSuccesses: MutableMap<FlagType, FlagsResult.Success> = EnumMap(FlagType::class.java)

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

    override fun observe(type: FlagType): Flow<FlagsResult> = flow {
        val cached = synchronized(cachedSuccesses) { cachedSuccesses[type] }
        if (cached != null) {
            emit(cached)
        } else {
            val userId = currentUserId()
            if (userId == null) {
                emit(notAuthenticatedFailure())
            } else {
                val diskCached = loadCached(type = type, userId = userId)
                if (diskCached != null) {
                    synchronized(cachedSuccesses) { cachedSuccesses[type] = diskCached.result }
                    emit(diskCached.result)
                    if (!diskCached.isFresh) {
                        when (val refreshed = fetch(type = type, userId = userId)) {
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
                    emit(fetch(type = type, userId = userId))
                }
            }
        }
        emitAll(refreshes.getValue(type).asSharedFlow())
    }

    override suspend fun refresh(type: FlagType) {
        val refreshesForType = refreshes.getValue(type)
        refreshesForType.emit(FlagsResult.Loading)
        val userId = currentUserId()
        refreshesForType.emit(
            if (userId == null) {
                notAuthenticatedFailure()
            } else {
                fetch(type = type, userId = userId)
            },
        )
    }

    override fun clearSessionCache() {
        synchronized(cachedSuccesses) { cachedSuccesses.clear() }
    }

    private suspend fun fetch(type: FlagType, userId: String): FlagsResult = withContext(ioDispatcher) {
        runCatching {
            val cats = loadCategories()
            val bucket = type.toBucket()
            // `coroutineScope` (fail-all) is intentional over `supervisorScope` (best-effort).
            // A flags screen must surface a network failure with the "Réessayer" affordance
            // — partial results would silently hide an outage and let the user think they
            // are up to date. If one cat REST call throws, all siblings are cancelled and
            // [FlagsResult.Failure] propagates with the original cause.
            coroutineScope {
                cats.map { category ->
                    async { fetchAllPages(cat = category.id, bucket = bucket, defaultType = type) }
                }.awaitAll().flatten()
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
                FlagsResult.Success(flags).also { result ->
                    synchronized(cachedSuccesses) { cachedSuccesses[type] = result }
                }
            },
            onFailure = { throwable ->
                Log.w(LOG_TAG, "Flags REST fetch failed for $type", throwable)
                FlagsResult.Failure(throwable)
            },
        )
    }

    private suspend fun loadCached(type: FlagType, userId: String): CachedFlags? = withContext(ioDispatcher) {
        val rows = flagDao.getFlags(userId = userId, type = type)
        if (rows.isEmpty()) return@withContext null

        val fetchedAt = flagDao.getLastFetchedAt(userId = userId, type = type)
            ?: return@withContext null
        CachedFlags(
            result = FlagsResult.Success(rows.map { it.toFlag() }),
            isFresh = CachePolicy.isFresh(fetchedAt, CachePolicy.flags, clock),
        )
    }

    private suspend fun persistFlags(userId: String, type: FlagType, flags: List<Flag>) {
        val fetchedAt = clock.instant()
        runCatching {
            flagDao.replaceForType(
                userId = userId,
                type = type,
                rows = flags.map { it.toEntity(userId = userId, fetchedAt = fetchedAt) },
            )
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
     * Resolves the REST-callable category list. Reuses [ForumRepository.observeCategories]
     * which keeps an in-memory cache per session, so tabbing back to drapeaux after
     * the Forum tab has loaded is one round-trip cheaper.
     *
     * Filters out categories whose [Category.id] is not strictly positive (defensive
     * guard against a hypothetical `cat=0` modos space leaking through — the REST flag
     * endpoint would 403 on it). Non-numeric pseudo-cats like `cat=prive` (MPs) cannot
     * reach this method because `Category.id` is typed `Int`.
     *
     * The `first { !Loading }` waits for the first concrete result. A stalled `Loading`
     * (network outage before the first fetch ever completes) is bounded by OkHttp's
     * connect/read timeouts inside the underlying `getCategories` REST call — when that
     * fails, `ForumRepository.observeCategories()` surfaces a `Failure` and `first` returns.
     * We do not impose a separate `withTimeout` here because it would compete with
     * coroutine test schedulers that don't share state with `Dispatchers.IO`.
     */
    private suspend fun loadCategories(): List<Category> {
        val first = forumRepository.observeCategories().first { it !is ForumResult.Loading }
        val all = when (first) {
            is ForumResult.Success -> first.value
            is ForumResult.Failure -> throw first.cause
            ForumResult.Loading -> error("filtered above")
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
        defaultType: FlagType,
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
                defaultType = defaultType,
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

    private fun FlagType.toBucket(): HfrRestFlagBucket = when (this) {
        FlagType.CYAN -> HfrRestFlagBucket.PARTICIPATED
        FlagType.RED -> HfrRestFlagBucket.READ
        FlagType.FAVORITE -> HfrRestFlagBucket.FAVORITES
    }

    private fun FlagTopicEntity.toFlag(): Flag = Flag(
        cat = cat,
        subcat = subcat,
        topicId = topicId,
        title = title,
        totalPages = totalPages,
        replyCount = replyCount,
        type = type,
        hasUnread = hasUnread,
        lastReadPage = lastReadPage,
        lastPostReadId = lastPostReadId,
        firstPostAuthor = firstPostAuthor,
        lastReplyAuthor = lastReplyAuthor,
        lastReplyAt = lastReplyAt,
    )

    private fun Flag.toEntity(userId: String, fetchedAt: Instant): FlagTopicEntity = FlagTopicEntity(
        userId = userId,
        type = type,
        cat = cat,
        subcat = subcat,
        topicId = topicId,
        title = title,
        totalPages = totalPages,
        replyCount = replyCount,
        hasUnread = hasUnread,
        lastReadPage = lastReadPage,
        lastPostReadId = lastPostReadId,
        firstPostAuthor = firstPostAuthor,
        lastReplyAuthor = lastReplyAuthor,
        lastReplyAt = lastReplyAt,
        fetchedAt = fetchedAt,
        authMode = FetchMode.AUTHENTICATED,
    )

    private data class CachedFlags(
        val result: FlagsResult.Success,
        val isFresh: Boolean,
    )

    private companion object {
        const val LOG_TAG = "FlagRepository"
        const val DEFAULT_RESULTS_PER_PAGE = 50
        const val MAX_PAGES = 100
    }
}
