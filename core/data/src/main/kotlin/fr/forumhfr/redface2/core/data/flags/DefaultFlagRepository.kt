package fr.forumhfr.redface2.core.data.flags

import android.util.Log
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagFetchTrace
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshGap
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshPhase
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrace
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrigger
import fr.forumhfr.redface2.core.domain.diagnostics.HfrRequestQueued
import fr.forumhfr.redface2.core.domain.flags.FlagCoverage
import fr.forumhfr.redface2.core.domain.flags.FlagLookup
import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshStrategy
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagFailureKind
import fr.forumhfr.redface2.core.model.FlagSource
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.write.FlagAddContext
import fr.forumhfr.redface2.core.model.write.FlagAddResult
import fr.forumhfr.redface2.core.model.write.FlagDeleteResult
import fr.forumhfr.redface2.core.network.FlagReadClock
import fr.forumhfr.redface2.core.network.FlagReadDeadlineException
import fr.forumhfr.redface2.core.network.FlagReadLimiter
import fr.forumhfr.redface2.core.network.FlagReadSession
import fr.forumhfr.redface2.core.network.HfrApiClient
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.network.HfrRestFlagBucket
import fr.forumhfr.redface2.core.parser.write.FlagAddResponseParser
import fr.forumhfr.redface2.core.parser.write.FlagDeleteResponseParser
import java.time.Clock
import java.time.Instant
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Phase 1D-1 REST implementation of [FlagRepository] (cf. ADR-003, issue #110). Reads
 * the user's drapeaux from `forums/hardwarefr/categories/{cat}/topics/{participated,read,
 * favorites}/` via [HfrApiClient]. The legacy HTML scrape on `forum1f.php` has been
 * retired with this slice — `getFlagsPage` and the matching `FlagsListParser` are gone.
 *
 * The baseline uses per-category buckets plus the shared sticky supplement. Experimental
 * policies can use the captured grouped global endpoint, whose observations never certify absence.
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
// #99/#986 added the HTML mutation collaborators (HfrClient + add/delflag parsers) on top of the
// existing REST read deps ; all 10 are distinct Hilt-injected singletons, a parameter object
// would only hide the dependency surface from DI. LargeClass: cache generations, mutation
// invalidation and their read coordinators deliberately share one singleton consistency owner.
@Suppress("LongParameterList", "LargeClass")
@Singleton
class DefaultFlagRepository @Inject constructor(
    private val apiClient: HfrApiClient,
    private val hfrClient: HfrClient,
    private val flagAddResponseParser: FlagAddResponseParser,
    private val flagDeleteResponseParser: FlagDeleteResponseParser,
    private val forumRepository: ForumRepository,
    private val authRepository: AuthRepository,
    private val flagCacheStore: FlagCacheStore,
    @param:FlagsJson private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    // #1144 — outcome trail for the two HFR mutations. Their callers are now detached
    // (`awaitDetached`), so a back press can leave the write running with nobody to raise a
    // snackbar: every add / remove records its result here (the in-app Diagnostics viewer),
    // mirroring what `DefaultDeletePostRepository` already does for a deletion.
    private val diagnostics: DiagnosticsLog,
    private val refreshDiagnostics: FlagRefreshDiagnostics,
    private val flagReadClock: FlagReadClock = FlagReadClock(),
    private val preferences: UserPreferencesRepository,
    private val readLimiter: FlagReadLimiter,
) : FlagRepository {

    private val cachedSuccesses: MutableMap<FlagType, FlagsResult.Success> = EnumMap(FlagType::class.java)
    // Same lifetime and lock as the cache; only the source trace of the returned result determines gaps.
    private val cachedFetchTraces: MutableMap<FlagType, FlagFetchTrace> = EnumMap(FlagType::class.java)
    private val cachedFetchKeys: MutableMap<FlagType, FetchKey> = EnumMap(FlagType::class.java)

    // #984 — serialize sweep barriers with commit (including Room) and result publication.
    // Lock order: per-type cacheWriteMutex, then generationMutex, then short monitor sections.
    private val generationMutex = Mutex()
    private val policyOwner = FlagRefreshPolicyOwner(preferences, readLimiter, refreshDiagnostics, ::retirePolicy)
    private val globalRefresh = GlobalFlagRefresh(apiClient, json, clock)
    private var policyGeneration = 0

    /**
     * Per-bucket generation + write lock. A successful mutation can invalidate one bucket while an
     * older REST fetch is still running; the generation rejects that stale result, and the mutex
     * serializes its Room write with the purge (#986).
     */
    private val cacheGenerations: MutableMap<FlagType, Int> =
        EnumMap<FlagType, Int>(FlagType::class.java).apply {
            FlagType.entries.forEach { put(it, 0) }
        }
    private val cacheWriteMutexes: Map<FlagType, Mutex> = FlagType.entries.associateWith { Mutex() }

    private val cacheUpdates = MutableSharedFlow<FlagType>(
        replay = 0,
        extraBufferCapacity = FlagType.entries.size,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

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
     * #501/#984 — callers of the same type, account and generations share one [Deferred].
     * An explicit refresh opens a sweep barrier before looking up the fetch, so it never joins
     * an older observation. Concurrent pulls within that barrier still share one fan-out.
     * A completed fetch is cleared, so a later refresh triggers a fresh one. Launched in an
     * app-scoped CoroutineScope (this singleton lives for the process). Cancelling one caller,
     * e.g. observe() torn down by a tab switch, never cancels a concurrent caller's shared fetch.
     * The last caller releases unfinished work; a new generation cancels obsolete fetches and
     * their sweep immediately, since none of their callers can publish those snapshots anymore.
     */
    private val fetchScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * Captured before launching the Deferred; the same key fences its sweep, commit and publication.
     */
    private data class FetchKey(
        val type: FlagType,
        val userId: String,
        val cacheGeneration: Int,
        val sessionGeneration: Int,
        val sweepGeneration: Int,
        val policyGeneration: Int,
        val resolutionCat: Int? = null,
    )

    private class ActiveRefresh(val key: FetchKey, var callers: Int = 1) {
        // Under generationMutex; retained through publication, even after the Deferred completes.
        var fetch: ObservedFetch? = null
    }
    // Accessed under cachedSuccesses; only active explicit pulls may reuse a refresh barrier.
    private val activeRefreshes = mutableMapOf<FlagType, ActiveRefresh>()

    private class ObservedFetch(
        val deferred: Deferred<FlagsResult>,
        val trace: FlagFetchTrace,
        val session: FlagReadSession,
    ) {
        // Under inFlightFetches; a caller retains its reference through acceptance/publication.
        var callers = 0
    }

    private class SupersededFetchException : CancellationException("Flags fetch generation superseded")

    private class FetchWaitDeadlineException : CancellationException("Flags replacement wait deadline reached")

    private class StickyDeadlineException : CancellationException("Shared sticky sweep deadline reached")

    private val inFlightFetches: MutableMap<FetchKey, ObservedFetch> = mutableMapOf()

    /**
     * Short topic-level memo for the menu gate (#986). A cached `true` can only cause one extra
     * confirmation if HFR changed elsewhere; the safety-sensitive `false` expires after 30 seconds.
     * Successful local mutations and account changes clear this memo explicitly.
     */
    private data class FavoriteResolutionKey(
        val userId: String,
        val cat: Int,
        val topicId: Int,
    )

    private data class FavoriteResolutionMemo(
        val isFavorite: Boolean,
        val expiresAt: Instant,
        val cacheGeneration: Int,
    )

    private data class FavoriteResolutionFetchKey(
        val resolution: FavoriteResolutionKey,
        val cacheGeneration: Int,
    )

    private val favoriteResolutionMemos: MutableMap<FavoriteResolutionKey, FavoriteResolutionMemo> = mutableMapOf()
    private val inFlightFavoriteResolutions: MutableMap<FavoriteResolutionFetchKey, Deferred<Result<Boolean>>> =
        mutableMapOf()

    override fun observe(type: FlagType): Flow<FlagsResult> = channelFlow {
        // Subscribe before any memory/Room work. A mutation that lands while this initial snapshot
        // is loading must see an active observer and publish its authoritative refresh (#986).
        val refreshJob = launch(start = CoroutineStart.UNDISPATCHED) {
            refreshes.getValue(type).asSharedFlow().collect { send(it) }
        }
        val cached = synchronized(cachedSuccesses) { cachedSuccesses[type] }
        if (cached != null) {
            // Tab subscriptions reuse even stale/partial memory; the ViewModel owns auto-refresh policy.
            send(cached)
        } else {
            val userId = currentUserId()
            if (userId == null) {
                send(notAuthenticatedFailure())
            } else {
                val (diskCached, diskSnapshotStillOwned) = cacheWriteMutexes.getValue(type).withLock {
                    val (diskSessionGeneration, diskCacheGeneration) = synchronized(cachedSuccesses) {
                        sessionGeneration to cacheGenerations.getValue(type)
                    }
                    // The read itself shares the mutation's lock. A load that starts after the
                    // generation bump cannot slip in before the matching Room purge completes.
                    val loaded = flagCacheStore.load(type = type, userId = userId)
                    val sameUser = currentUserId() == userId
                    val ownsSnapshot = sameUser && synchronized(cachedSuccesses) {
                        val ownsSnapshot = sessionGeneration == diskSessionGeneration &&
                            cacheGenerations.getValue(type) == diskCacheGeneration
                        if (ownsSnapshot && loaded != null) {
                            cachedSuccesses[type] = loaded.result
                            cachedFetchTraces.remove(type)
                            cachedFetchKeys.remove(type)
                        }
                        ownsSnapshot
                    }
                    loaded to ownsSnapshot
                }
                if (!diskSnapshotStillOwned) {
                    // A successful mutation or account switch completed while Room was loading.
                    // Discard that now-stale snapshot and resolve the current generation from REST.
                    send(FlagsResult.Loading)
                    fetchObserved(type, userId) { send(it) }
                } else if (diskCached != null) {
                    send(diskCached.result)
                    if (!diskCached.isFresh) {
                        fetchObserved(type, userId) { refreshed ->
                            when (refreshed) {
                                is FlagsResult.Success -> send(refreshed)
                                is FlagsResult.Failure -> Log.w(
                                    LOG_TAG,
                                    "Keeping stale flags cache for $type after refresh failure",
                                    refreshed.cause,
                                )
                                FlagsResult.Loading -> Unit
                            }
                        }
                    }
                } else {
                    send(FlagsResult.Loading)
                    fetchObserved(type, userId) { send(it) }
                }
            }
        }
        refreshJob.join()
    }

    override suspend fun refresh(type: FlagType): FlagsResult = diagnoseRefresh(
        type, FlagRefreshTrigger.PULL,
    ) { trace ->
        val userId = currentUserId()
        val active = beginRefresh(type, userId)
        try {
            val flow = refreshes.getValue(type)
            flow.emit(FlagsResult.Loading)
            if (userId == null) {
                val failure = notAuthenticatedFailure()
                trace.failure(failure.cause)
                flow.emit(failure)
                failure
            } else {
                fetchDeduplicated(type, userId) { result ->
                    if (result is FlagsResult.Failure) trace.failure(result.cause)
                    flow.emit(result)
                    if (result is FlagsResult.Success) trace.published()
                }
            }
        } finally {
            synchronized(cachedSuccesses) {
                if (--active.callers == 0 && activeRefreshes[type] === active) activeRefreshes.remove(type)
            }
        }
    }

    private suspend fun beginRefresh(type: FlagType, userId: String?): ActiveRefresh = generationMutex.withLock {
        val active = synchronized(cachedSuccesses) {
            val current = fetchKey(type, userId.orEmpty())
            activeRefreshes[type]?.takeIf { it.key == current }?.also { it.callers++ }
                ?: run {
                    sweepGeneration++
                    ActiveRefresh(fetchKey(type, userId.orEmpty())).also { activeRefreshes[type] = it }
                }
        }
        cancelSupersededFetches()
        active
    }

    override fun observeCacheUpdates(types: Set<FlagType>): Flow<FlagType> {
        if (types.isEmpty()) return emptyFlow()
        val observedTypes = types.toSet()
        return cacheUpdates.asSharedFlow().filter { it in observedTypes }
    }

    override suspend fun cachedCoverage(): FlagCoverage =
        synchronized(cachedSuccesses) {
            FlagCoverage(cachedSuccesses.values.flatMap { it.coverage.categories })
        }

    override fun clearSessionCache() {
        // Bump the generation under the same lock as the write guard: a fetch in flight across this
        // point will see a changed generation and skip its cache write (#501 Codex P1).
        synchronized(cachedSuccesses) {
            cachedSuccesses.clear()
            cachedFetchTraces.clear()
            cachedFetchKeys.clear()
            activeRefreshes.clear()
            sessionGeneration++
            FlagType.entries.forEach { type -> bumpCacheGeneration(type) }
            synchronized(favoriteResolutionMemos) { favoriteResolutionMemos.clear() }
        }
        // Cancel any fetch still in flight from the previous session so it stops early instead of
        // running the full fan-out for an account that just logged out / switched.
        synchronized(inFlightFetches) {
            inFlightFetches.values.forEach { it.deferred.cancel() }
            inFlightFetches.clear()
        }
        synchronized(inFlightFavoriteResolutions) {
            inFlightFavoriteResolutions.values.forEach { it.cancel() }
            inFlightFavoriteResolutions.clear()
        }
        // #862 — same rule for the shared topics/last sweep : never reused across accounts.
        synchronized(stickySweeps) {
            stickySweeps.values.forEach { it.deferred.cancel() }
            stickySweeps.clear()
        }
        FlagType.entries.forEach(::publishCacheUpdate)
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
        }.onFailure { raised ->
            // #1144 — a cancellation is NOT an outcome to report: folding it into `Result.failure`
            // told the caller « la suppression a echoue » for what was only a torn-down caller, and
            // it silently killed the CancellationException branch of
            // `TopicViewModel.confirmRemoveTopicFlag`. Re-throw it untouched; only real failures
            // become a failed Result (and get traced).
            if (raised is CancellationException) throw raised
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "delflag FAILED topic=${flag.topicId} type=${flag.type} : " +
                    (raised.message ?: raised::class.simpleName ?: "(no message)"),
            )
        }.onSuccess {
            // Traced from the repository, not from the ViewModel: since #1144 the caller may already
            // be gone (detached mutation) and its snackbar with it, so this is the only surviving
            // record of what the app did to the user's drapeaux.
            diagnostics.record(
                DiagnosticsLog.Level.INFO,
                LOG_TAG,
                "delflag OK topic=${flag.topicId} type=${flag.type}",
            )
            evictFlagFromCaches(userId = userId, flag = flag)
        }
    }

    /**
     * Adds a favourite via `addflag.php` (#986), updates cache rows whose metadata is already known,
     * and invalidates the complete FAVORITE snapshot.
     *
     * Flow :
     * 1. Resolve the authenticated user ; abort with a failed [Result] if anonymous (the
     *    addflag GET would land on the login page anyway).
     * 2. GET `/user/addflag.php` through [HfrClient] (HTML mutation per ADR-003) and
     *    classify the body with [FlagAddResponseParser]. Network + Jsoup parse are both
     *    on [ioDispatcher], matching [removeFlag]'s dispatcher boundary.
     * 3. **Success** → mark already cached CYAN/RED rows for the same `(cat, topicId)` as
     *    [Flag.isFavorite], purge the complete FAVORITE snapshot from memory + Room, and refresh
     *    an active Favorites tab. We deliberately do **not** fabricate a new FAVORITE row:
     *    `addflag.php` returns no title / counters / authors.
     * 4. **Failure / unexpected page** → touch no cache, return [Result.failure].
     *
     * A [SessionExpiredException] (or any transport error) raised by [HfrClient] propagates
     * as a failed [Result] via [runCatching] — no cache is mutated.
     */
    override suspend fun addFlag(context: FlagAddContext): Result<Unit> {
        val userId = currentUserId()
            ?: return Result.failure(IllegalStateException("Adding a favourite requires an authenticated HFR session"))

        return runCatching {
            val result = withContext(ioDispatcher) {
                val response = hfrClient.addFlag(context)
                flagAddResponseParser.parse(response)
            }
            when (result) {
                FlagAddResult.Success -> Unit
                FlagAddResult.Failure -> throw FlagAddFailedException(context.topicId)
            }
        }.onFailure { raised ->
            // #1144 — same rule as [removeFlag]: cancellation propagates, it is not a failed add.
            if (raised is CancellationException) throw raised
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "addflag FAILED topic=${context.topicId} : " +
                    (raised.message ?: raised::class.simpleName ?: "(no message)"),
            )
        }.onSuccess {
            // #1144 — the surviving trace of a favourite added by a caller that may already be gone.
            diagnostics.record(
                DiagnosticsLog.Level.INFO,
                LOG_TAG,
                "addflag OK topic=${context.topicId}",
            )
            // A session change while addflag.php was in flight must not reconcile the previous
            // account's mutation into the current account's caches. The UI independently guards its
            // own state token; this is the repository-side ownership fence.
            if (currentUserId() == userId) {
                reconcileFavoriteCaches(userId = userId, context = context)
            }
        }
    }

    /**
     * #986 — bounded-fresh topic-level favourite lookup used before offering the add/move action.
     *
     * The favourites bucket is authoritative for ordinary topics, but HFR omits sticky topics from
     * it (#251/#862). A miss therefore performs the same category-level `topics/last` supplement as
     * the list fetch, except failures are NOT best-effort here: returning a false negative would let
     * the UI move an existing favourite without confirmation. Both calls stay scoped to [cat], so
     * opening one post menu does not fan out over the whole forum. Successful answers are memoized
     * for 30 seconds and concurrent callers share one app-scoped request, so closing/reopening a menu
     * cannot cancel and restart the same HTTP lookup. Failures are never cached.
     */
    override suspend fun resolveFavorite(cat: Int, topicId: Int): Result<Boolean> {
        val userId = currentUserId()
        return if (userId == null) {
            Result.failure(IllegalStateException("Resolving a favourite requires authentication"))
        } else {
            resolveFavoriteDeduplicated(
                resolutionKey = FavoriteResolutionKey(userId = userId, cat = cat, topicId = topicId),
            )
        }
    }

    private suspend fun resolveFavoriteDeduplicated(
        resolutionKey: FavoriteResolutionKey,
    ): Result<Boolean> {
        var resolved: Result<Boolean>? = null
        while (resolved == null) {
            val cacheGeneration = synchronized(cachedSuccesses) {
                cacheGenerations.getValue(FlagType.FAVORITE)
            }
            val memoized = readFavoriteResolutionMemo(
                resolutionKey = resolutionKey,
                cacheGeneration = cacheGeneration,
            )
            if (memoized != null) {
                resolved = Result.success(memoized)
            } else {
                val result = awaitFavoriteResolution(
                    resolutionKey = resolutionKey,
                    cacheGeneration = cacheGeneration,
                )
                resolved = when {
                    currentUserId() != resolutionKey.userId -> Result.failure(
                        IllegalStateException("HFR account changed while resolving the favourite"),
                    )
                    isFavoriteResolutionGenerationCurrent(cacheGeneration) -> result
                    // A successful add invalidated FAVORITE while this lookup was in flight. Never
                    // expose its pre-mutation false; retry under the generation opened by the purge.
                    else -> null
                }
            }
        }
        return resolved
    }

    private fun readFavoriteResolutionMemo(
        resolutionKey: FavoriteResolutionKey,
        cacheGeneration: Int,
    ): Boolean? {
        val now = clock.instant()
        return synchronized(favoriteResolutionMemos) {
            favoriteResolutionMemos[resolutionKey]?.takeIf { memo ->
                memo.cacheGeneration == cacheGeneration && now.isBefore(memo.expiresAt)
            }?.isFavorite
        }
    }

    private suspend fun awaitFavoriteResolution(
        resolutionKey: FavoriteResolutionKey,
        cacheGeneration: Int,
    ): Result<Boolean> {
        val fetchKey = FavoriteResolutionFetchKey(
            resolution = resolutionKey,
            cacheGeneration = cacheGeneration,
        )
        val deferred = synchronized(inFlightFavoriteResolutions) {
            inFlightFavoriteResolutions[fetchKey]?.takeIf { it.isActive }
                ?: fetchScope.async {
                    val result = resolveFavoriteFromNetwork(
                        cat = resolutionKey.cat,
                        topicId = resolutionKey.topicId,
                        userId = resolutionKey.userId,
                    )
                    storeFavoriteResolutionIfCurrent(
                        resolutionKey = resolutionKey,
                        cacheGeneration = cacheGeneration,
                        result = result,
                    )
                    result
                }.also { started ->
                    inFlightFavoriteResolutions[fetchKey] = started
                    started.invokeOnCompletion {
                        synchronized(inFlightFavoriteResolutions) {
                            if (inFlightFavoriteResolutions[fetchKey] === started) {
                                inFlightFavoriteResolutions.remove(fetchKey)
                            }
                        }
                    }
                }
        }
        return deferred.await()
    }

    private fun isFavoriteResolutionGenerationCurrent(cacheGeneration: Int): Boolean =
        synchronized(cachedSuccesses) {
            cacheGenerations.getValue(FlagType.FAVORITE) == cacheGeneration
        }

    private fun storeFavoriteResolutionIfCurrent(
        resolutionKey: FavoriteResolutionKey,
        cacheGeneration: Int,
        result: Result<Boolean>,
    ) {
        synchronized(cachedSuccesses) {
            if (cacheGenerations.getValue(FlagType.FAVORITE) == cacheGeneration) {
                result.getOrNull()?.let { isFavorite ->
                    synchronized(favoriteResolutionMemos) {
                        favoriteResolutionMemos[resolutionKey] = FavoriteResolutionMemo(
                            isFavorite = isFavorite,
                            expiresAt = clock.instant().plusSeconds(FAVORITE_RESOLUTION_TTL_SECONDS),
                            cacheGeneration = cacheGeneration,
                        )
                    }
                }
            }
        }
    }

    private suspend fun resolveFavoriteFromNetwork(
        cat: Int,
        topicId: Int,
        userId: String,
    ): Result<Boolean> = try {
        val isFavorite = withContext(ioDispatcher) {
            val bucketMatch = fetchAllPages(
                cat = cat,
                bucket = HfrRestFlagBucket.FAVORITES,
                type = FlagType.FAVORITE,
                failOnTruncation = true,
            rejectDivergentCount = true,
            ).any { it.topicId == topicId }
            if (bucketMatch) {
                true
            } else {
                val body = apiClient.getTopicList(
                    cat = cat,
                    subcat = null,
                    page = 1,
                    resultsPerPage = DEFAULT_RESULTS_PER_PAGE,
                    useAuth = true,
                )
                val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(body)
                RestFlagMappers.toStickyFlags(
                    envelope = envelope,
                    type = FlagType.FAVORITE,
                    fallbackCat = cat,
                ).any { it.topicId == topicId }
            }
        }
        check(currentUserId() == userId) { "HFR account changed while resolving the favourite" }
        Result.success(isFavorite)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        Result.failure(error)
    }

    /**
     * #809 — resolves the full [Flag] for a `(cat, topicId)` pair so callers outside the Drapeaux
     * view (the topic top-bar long-press) can feed [removeFlag] the complete object it keys the
     * `delflag.php` mutation on. Never fabricates a partial [Flag].
     *
     * Memory-first, **per-type/category** : scans the warm per-type success caches under the
     * [cachedSuccesses] lock. On a hit, returns immediately (no network). On a miss, cold or uncovered
     * types are fetched (each writes its success into [cachedSuccesses] via [fetch]'s
     * generation-guarded write), then a re-scan resolves. Coverage is authoritative **for its
     * own type and category only**; an incomplete category must be checked again (#984) —
     * but it says nothing about the other types : the Drapeaux screen warms one tab at a time, so
     * « CYAN warm + miss » must still check a never-loaded FAVORITE bucket (review finding : the
     * earlier any-bucket-warm short-circuit made a FAVORITE-only topic unremovable from the topic
     * screen until its tab had been visited). All three sources covered + miss → Absent with zero network. An
     * anonymous session holds no drapeaux, so it short-circuits to Absent before any round-trip.
     *
     * When a topic carries several drapeaux (e.g. participated AND favori), the [FlagType] iteration
     * order (CYAN, RED, FAVORITE) breaks the tie deterministically — enough for the MVP « retirer »,
     * which keys `delflag.php` on that row's own `type`.
     */
    override suspend fun findFlag(cat: Int, topicId: Int): FlagLookup {
        val (cachedMatch, warmTypes) = synchronized(cachedSuccesses) {
            scanCachedFlags(cat, topicId) to cachedSuccesses.filterValues {
                !it.coverage.isGlobalObservation && it.coverage.covers(cat)
            }.keys.toSet()
        }
        val userId = currentUserId()
        val immediate = when {
            cachedMatch != null -> FlagLookup.Found(cachedMatch)
            userId == null -> FlagLookup.Absent
            else -> null
        }
        if (immediate != null) return immediate
        checkNotNull(userId)
        val coldTypes = FlagType.entries.filterNot { it in warmTypes }
        val resolutions = coroutineScope {
            coldTypes.map { type -> async { fetchDeduplicated(type, userId, resolutionCat = cat) } }.awaitAll()
        }
        val sameUser = currentUserId() == userId
        return synchronized(cachedSuccesses) {
            val found = scanCachedFlags(cat, topicId)
            val coveredNow = FlagType.entries.all { cachedSuccesses[it]?.coverage?.covers(cat) == true }
            when {
                !sameUser -> FlagLookup.Unavailable
                found != null -> FlagLookup.Found(found)
                coveredNow && resolutions.all { it is FlagsResult.Success && it.coverage.covers(cat) } ->
                    FlagLookup.Absent
                else -> FlagLookup.Unavailable
            }
        }
    }

    override suspend fun findCachedFlag(topicId: Int): Flag? =
        synchronized(cachedSuccesses) { scanCachedFlags(topicId = topicId) }

    override suspend fun findCachedFlag(cat: Int, topicId: Int): Flag? =
        synchronized(cachedSuccesses) { scanCachedFlags(cat = cat, topicId = topicId) }

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
     * Legacy super-favorites only stored `topicId`, so they can only be resolved from already-warm
     * caches. No cold bucket fetch here: missing `cat` makes network resolution ambiguous.
     */
    private fun scanCachedFlags(topicId: Int): Flag? =
        cachedSuccesses.values
            .asSequence()
            .flatMap { it.flags.asSequence() }
            .firstOrNull { it.topicId == topicId }

    /**
     * Drops [flag] from the in-memory success cache and Room, then re-emits the trimmed
     * list to active observers of its tab. Called only after a confirmed `delflag.php`
     * success, so it never has to reason about a partial mutation.
     */
    private suspend fun evictFlagFromCaches(userId: String, flag: Flag) {
        cacheWriteMutexes.getValue(flag.type).withLock {
            generationMutex.withLock mutation@ {
                if (currentUserId() != userId) return@mutation
                val updated = synchronized(cachedSuccesses) { evictCachedFlag(flag) }
                cancelSupersededFetches()
                // Best-effort Room eviction, serialized with fetch commit and publication.
                runCatching {
                    flagCacheStore.delete(userId, flag.type, flag.cat, flag.topicId)
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Log.w(LOG_TAG, "Could not evict deleted flag ${flag.topicId} from Room cache", throwable)
                }
                publishCacheUpdate(flag.type)
                if (updated != null) refreshes.getValue(flag.type).emit(updated)
            }
        }
    }

    /** Called under cachedSuccesses, with the mutation holding both write and generation mutexes. */
    private fun evictCachedFlag(flag: Flag): FlagsResult.Success? {
        bumpCacheGeneration(flag.type)
        cachedFetchTraces.remove(flag.type)
        // topics/last may still contain the deleted flag; never reuse that sweep.
        sweepGeneration++
        if (flag.type == FlagType.FAVORITE) {
            synchronized(favoriteResolutionMemos) { favoriteResolutionMemos.clear() }
        }
        val current = cachedSuccesses[flag.type] ?: return null
        return current.copy(flags = current.flags.filterNot {
            it.cat == flag.cat && it.topicId == flag.topicId
        }).also { cachedSuccesses[flag.type] = it }
    }

    /**
     * Reconciles every cache after a confirmed `addflag.php` success (#986).
     *
     * CYAN/RED rows already owned by the cache can safely gain `isFavorite=true`. The FAVORITE
     * bucket cannot safely gain a fabricated row because the mutation response carries none of the
     * listing metadata, so its complete memory + Room snapshot is invalidated. Per-type generations
     * prevent an older in-flight fetch from restoring the stale snapshot after this purge.
     */
    private suspend fun reconcileFavoriteCaches(userId: String, context: FlagAddContext) {
        val updated = mutableListOf<Pair<FlagType, FlagsResult.Success>>()

        listOf(FlagType.CYAN, FlagType.RED).forEach { type ->
            cacheWriteMutexes.getValue(type).withLock {
                val success = synchronized(cachedSuccesses) {
                    bumpCacheGeneration(type)
                    val current = cachedSuccesses[type] ?: return@synchronized null
                    var changed = false
                    val flags = current.flags.map { flag ->
                        if (flag.cat == context.cat && flag.topicId == context.topicId && !flag.isFavorite) {
                            changed = true
                            flag.copy(isFavorite = true)
                        } else {
                            flag
                        }
                    }
                    if (changed) current.copy(flags = flags).also { cachedSuccesses[type] = it } else null
                }
                if (success != null) {
                    runCatching {
                        flagCacheStore.markFavorite(userId, type, context.cat, context.topicId)
                    }.onFailure { throwable ->
                        if (throwable is CancellationException) throw throwable
                        Log.w(LOG_TAG, "Could not persist added favourite marker for $type", throwable)
                    }
                    updated += type to success
                }
            }
        }

        cacheWriteMutexes.getValue(FlagType.FAVORITE).withLock {
            synchronized(cachedSuccesses) {
                bumpCacheGeneration(FlagType.FAVORITE)
                cachedSuccesses.remove(FlagType.FAVORITE)
                cachedFetchTraces.remove(FlagType.FAVORITE)
                synchronized(favoriteResolutionMemos) { favoriteResolutionMemos.clear() }
            }
            // A mutation opens a new sticky-sweep generation too: a post-mutation fetch must not
            // join a topics/last sweep captured before addflag.php succeeded.
            generationMutex.withLock { synchronized(cachedSuccesses) { sweepGeneration++ } }
            runCatching {
                flagCacheStore.invalidate(userId = userId, type = FlagType.FAVORITE)
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                Log.w(LOG_TAG, "Could not invalidate the favourite Room cache", throwable)
            }
        }

        publishCacheUpdate(FlagType.FAVORITE)
        updated.forEach { (type, success) ->
            publishCacheUpdate(type)
            refreshes.getValue(type).emit(success)
        }

        // A destination kept STARTED under another navigation pane may still be collecting the old
        // list. Refresh it authoritatively; with no active collector, the purge above is sufficient
        // and the next observe() performs the fetch without paying the 30-second Room TTL.
        val favoriteRefresh = refreshes.getValue(FlagType.FAVORITE)
        if (favoriteRefresh.subscriptionCount.value > 0) {
            fetchScope.launch {
                favoriteRefresh.emit(FlagsResult.Loading)
                fetchDeduplicated(type = FlagType.FAVORITE, userId = userId) { favoriteRefresh.emit(it) }
            }
        }
        generationMutex.withLock { cancelSupersededFetches() }
    }

    /** Must be called under [cachedSuccesses]' monitor. */
    private fun bumpCacheGeneration(type: FlagType) {
        cacheGenerations[type] = cacheGenerations.getValue(type) + 1
        cachedFetchKeys.remove(type)
    }

    private fun publishCacheUpdate(type: FlagType) {
        cacheUpdates.tryEmit(type)
    }

    private suspend fun <T> diagnoseRefresh(
        type: FlagType,
        trigger: FlagRefreshTrigger,
        block: suspend (FlagRefreshTrace) -> T,
    ): T {
        policyOwner.synchronize()
        val inherited = currentCoroutineContext()[FlagRefreshTrace]
        if (inherited != null) {
            return block(inherited)
        } else {
            val trace = refreshDiagnostics.begin(trigger, listOf(type))
            try {
                return withContext(trace) { block(trace) }
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                trace.failure(error)
                throw error
            } finally {
                trace.finish()
            }
        }
    }

    private suspend fun fetchObserved(type: FlagType, userId: String, publish: suspend (FlagsResult) -> Unit) =
        diagnoseRefresh(type, FlagRefreshTrigger.INITIAL_OBSERVE) { trace ->
            fetchDeduplicated(type, userId) { result ->
                if (result is FlagsResult.Failure) trace.failure(result.cause)
                publish(result)
                if (result is FlagsResult.Success) trace.published()
            }
        }

    private suspend fun <T> measured(phase: FlagRefreshPhase, block: suspend () -> T): T {
        val trace = currentCoroutineContext()[FlagFetchTrace]
        val start = System.nanoTime()
        try {
            return block()
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            trace?.fatal(error)
            throw error
        } finally {
            trace?.phase(phase, start)
        }
    }

    /**
     * Runs [fetch] unless an identical fetch is active. Its independent measurement holder survives
     * the scope switch and retains earlier calls for callers joining halfway through the fetch.
     * Superseded results are replaced with a current committed success, or a current fetch.
     */
    private suspend fun fetchDeduplicated(
        type: FlagType,
        userId: String,
        resolutionCat: Int? = null,
        publish: (suspend (FlagsResult) -> Unit)? = null,
    ): FlagsResult {
        policyOwner.synchronize()
        val caller = currentCoroutineContext()[FlagRefreshTrace]
        // Retain the original admission budget across generation replacements as well as joins.
        var admission = FlagReadSession(flagReadClock::nowNanos, policyOwner.current)
        while (true) {
            val (key, observed) = policyOwner.withPolicy { policy ->
                admission = admission.withPolicy(policy)
                generationMutex.withLock {
                    val key = synchronized(cachedSuccesses) {
                        fetchKey(type, userId).copy(resolutionCat = resolutionCat)
                    }
                    val observed = joinOrStartFetch(key, caller, admission)
                    key to observed
                }
            }
            if (observed.session.remainingNanos() <= admission.remainingNanos()) admission = observed.session
            try {
                val result = awaitFetchResult(observed, userId, admission)
                val ownedResult = cacheWriteMutexes.getValue(type).withLock {
                    acceptFetchedResult(key, result, observed.trace, caller, publish)
                }
                if (ownedResult != null) return ownedResult
            } catch (_: FetchWaitDeadlineException) {
                // Another caller still owns the replacement: keep existing content without
                // publishing over its in-flight result, or leave a cold observer waiting for it.
                return synchronized(cachedSuccesses) { cachedSuccesses[type] } ?: FlagsResult.Loading
            } finally {
                releaseFetch(key, observed)
            }
        }
    }

    private suspend fun awaitFetchResult(
        observed: ObservedFetch,
        userId: String,
        admission: FlagReadSession,
    ): FlagsResult? = try {
        if (observed.session === admission) {
            // Its collection owns this deadline and commits completed partitions before returning.
            observed.deferred.await()
        } else {
            // A replacement already admitted by another caller may have a later deadline.
            // Stop waiting without cancelling work still owned by that caller.
            withTimeoutOrNull(admission.remainingMillis().coerceAtLeast(1)) { observed.deferred.await() }
                ?: throw FetchWaitDeadlineException()
        }
    } catch (superseded: SupersededFetchException) {
        // This repository cancelled obsolete work, not the caller's refresh. Its
        // transport remains cancelled, but only the replacement sets the outcome.
        currentCoroutineContext().ensureActive()
        if (currentUserId() != userId) throw superseded
        null
    }

    /** Called under generationMutex: a barrier cannot change between key capture and joining. */
    private fun joinOrStartFetch(
        key: FetchKey,
        caller: FlagRefreshTrace?,
        admission: FlagReadSession,
    ): ObservedFetch {
        val active = synchronized(cachedSuccesses) { activeRefreshes[key.type]?.takeIf { it.key == key } }
        var joined = false
        val observed = synchronized(inFlightFetches) {
            val shared = (active?.fetch?.takeUnless { it.deferred.isCancelled }
                ?: inFlightFetches[key]?.takeIf { it.deferred.isActive })?.also { joined = true }
                ?: run {
                    val trace = refreshDiagnostics.fetch(key.type, caller?.id).also {
                        // Register publication before a detached fetch can complete its first call.
                        caller?.attach(it, wasJoined = false)
                    }
                    trace.readLimit = admission.policy.effectiveConcurrency ?: 0
                    trace.policy = admission.policy
                    trace.readCounterOwner = admission
                    trace.readCounters = admission::counters
                    val deferred = fetchScope.async(trace + admission + HfrRequestQueued(),
                        start = CoroutineStart.LAZY) {
                        refreshDiagnostics.withRefresh { fetch(key) }
                    }
                    ObservedFetch(deferred, trace, admission)
                }.also { started ->
                    inFlightFetches[key] = started
                    started.deferred.invokeOnCompletion {
                        started.trace.markFinished()
                        synchronized(inFlightFetches) {
                            if (inFlightFetches[key] === started) inFlightFetches.remove(key)
                        }
                    }
                }
            shared.also { it.callers++ }
        }
        active?.fetch = observed
        caller?.attach(observed.trace, joined)
        observed.deferred.start()
        return observed
    }

    private fun releaseFetch(key: FetchKey, observed: ObservedFetch) = synchronized(inFlightFetches) {
        if (--observed.callers == 0 && observed.deferred.isActive) {
            if (inFlightFetches[key] === observed) inFlightFetches.remove(key)
            observed.deferred.cancel()
        }
    }

    /** Called under generationMutex. No caller of an obsolete key can publish it anymore. */
    private fun cancelSupersededFetches() {
        val (session, sweep, caches) = synchronized(cachedSuccesses) {
            Triple(sessionGeneration, sweepGeneration, cacheGenerations.toMap())
        }
        synchronized(inFlightFetches) {
            inFlightFetches.keys.filter { key ->
                key.sessionGeneration != session || key.sweepGeneration != sweep ||
                    key.cacheGeneration != caches.getValue(key.type) || key.policyGeneration != policyGeneration
            }.forEach { key ->
                inFlightFetches.remove(key)?.deferred?.cancel(SupersededFetchException())
            }
        }
        // A sweep is shared across types. Cancel it only when the entire sweep generation
        // loses publication ownership, never when just one of its callers goes away.
        synchronized(stickySweeps) {
            stickySweeps.keys.filter { it.sessionGeneration != session || it.sweepGeneration != sweep }
                .forEach { key -> stickySweeps.remove(key)?.deferred?.cancel(SupersededFetchException()) }
        }
    }

    private suspend fun acceptFetchedResult(
        key: FetchKey,
        result: FlagsResult?,
        trace: FlagFetchTrace,
        caller: FlagRefreshTrace?,
        publish: (suspend (FlagsResult) -> Unit)?,
    ): FlagsResult? = generationMutex.withLock {
        val type = key.type
        val userId = key.userId
        if (currentUserId() != userId) {
            val failure = FlagsResult.Failure(
                IllegalStateException("HFR account changed while fetching flags"),
            )
            caller?.failure(failure.cause)
            return@withLock failure
        }
        var resultTrace: FlagFetchTrace? = trace
        val owned = synchronized(cachedSuccesses) {
            val current = fetchKey(type, userId).copy(resolutionCat = key.resolutionCat)
            when {
                key == current -> result
                cachedFetchKeys[type] == current -> {
                    resultTrace = cachedFetchTraces[type]
                    cachedSuccesses[type]
                }
                else -> null
            }
        } ?: return@withLock null
        caller?.result(type, resultTrace)
        // Both the per-type mutation lock and sweep barrier cover acceptance + publication.
        publish?.invoke(owned)
        owned
    }

    /** Must be called under [cachedSuccesses]' monitor. */
    private fun fetchKey(type: FlagType, userId: String): FetchKey = FetchKey(
        type = type,
        userId = userId,
        cacheGeneration = cacheGenerations.getValue(type),
        sessionGeneration = sessionGeneration,
        sweepGeneration = sweepGeneration,
        policyGeneration = policyGeneration,
    )

    private suspend fun fetch(key: FetchKey): FlagsResult = withContext(ioDispatcher) {
        val batch = try {
            val strategy = currentCoroutineContext()[FlagReadSession]?.policy?.strategy
            if (key.resolutionCat == null && strategy in GLOBAL_STRATEGIES) {
                val global = globalRefresh.collect(key.type)
                if (strategy == FlagsRefreshStrategy.GLOBAL_ONLY) {
                    return@withContext commitFetchedSuccess(key, global) ?: FlagsResult.Loading
                }
                val positive = commitFetchedSuccess(key, global)
                if (positive != null) publishIntermediate(key, positive)
            }
            collectRefresh(key)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(LOG_TAG, "Flags REST fetch failed for ${key.type}", error)
            FlagRefreshBatch(null, emptyList(), clock.instant(), error)
        }
        commitFetchedSuccess(key, batch) ?: FlagsResult.Loading
    }

    private suspend fun publishIntermediate(key: FetchKey, result: FlagsResult) {
        cacheWriteMutexes.getValue(key.type).withLock {
            generationMutex.withLock {
                val owns = currentUserId() == key.userId && synchronized(cachedSuccesses) {
                    fetchKey(key.type, key.userId) == key
                }
                if (owns) refreshes.getValue(key.type).emit(result)
            }
        }
    }

    private suspend fun collectRefresh(key: FetchKey): FlagRefreshBatch {
        val session = checkNotNull(currentCoroutineContext()[FlagReadSession])
        val collection = FlagRefreshCollection(clock)
        val completed = try {
            withTimeoutOrNull(session.remainingMillis().coerceAtLeast(1)) {
                session.checkBudget()
                val cats = measured(FlagRefreshPhase.CATALOGUE) {
                    loadCategories().let { cats ->
                        key.resolutionCat?.let { cat -> cats.filter { it.id == cat }.ifEmpty {
                            throw SerializationException("Missing lookup category")
                        } } ?: cats
                    }
                }
                collection.categories = cats
                currentCoroutineContext()[FlagFetchTrace]?.categoryCount = cats.size
                val expired = AtomicBoolean(false)
                val bucketCats = resolutionBuckets(key, cats)
                (cats - bucketCats.toSet()).forEach { collection.retain(it.id, FlagSource.BUCKET) }
                val buckets = measured(FlagRefreshPhase.BUCKETS) {
                    collectBuckets(bucketCats, key.type, expired, collection)
                }
                if (shouldCollectStickies(expired, buckets, bucketCats.size < cats.size)) {
                    measured(FlagRefreshPhase.STICKIES) {
                        fetchStickyFlagSupplement(cats, key.type, buckets.flatMap { it.flags }, key,
                            collection::record)
                    }
                }
                true
            } ?: false
        } catch (_: FlagReadDeadlineException) {
            false
        }
        val batch = collection.finish(deadline = !completed)
        if (!completed || batch.outcomes.any { it.failure == FlagFailureKind.DEADLINE }) {
            currentCoroutineContext()[FlagFetchTrace]?.gap(FlagRefreshGap.COLLECTION_DEADLINE)
        }
        session.expiredCategories(batch.outcomes.filter { it.failure == FlagFailureKind.DEADLINE }
            .map { it.cat }.distinct().size)
        return if (key.resolutionCat == null) batch else batch.copy(categoryIds = null)
    }

    private fun resolutionBuckets(key: FetchKey, cats: List<Category>): List<Category> {
        if (key.resolutionCat == null) return cats
        val coverage = synchronized(cachedSuccesses) { cachedSuccesses[key.type]?.coverage }
        return cats.filterNot { coverage?.isGlobalObservation == false && coverage.covers(it.id, FlagSource.BUCKET) }
    }

    private fun shouldCollectStickies(
        expired: AtomicBoolean,
        buckets: List<CategoryRefreshOutcome>,
        retainedBucket: Boolean,
    ): Boolean = !expired.get() && (retainedBucket || buckets.any { it.failure == null })

    private suspend fun collectBuckets(
        cats: List<Category>,
        type: FlagType,
        expired: AtomicBoolean,
        collection: FlagRefreshCollection,
    ): List<CategoryRefreshOutcome> = coroutineScope {
        cats.map { category ->
            async(HfrRequestQueued()) {
                val outcome = try {
                    val flags = fetchAllPages(category.id, type.toBucket(), type,
                        failOnTruncation = true, sessionExpired = expired)
                    currentCoroutineContext()[FlagReadSession]?.checkBudget()
                    CategoryRefreshOutcome(category.id, FlagSource.BUCKET, flags, clock.instant())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                    val failure = error.toFlagFailureKind()
                    if (failure == FlagFailureKind.SESSION) expired.set(true)
                    collection.failed(error)
                    CategoryRefreshOutcome(category.id, FlagSource.BUCKET, receivedAt = clock.instant(),
                        failure = failure)
                }
                outcome.also(collection::record)
            }
        }.awaitAll()
    }

    /** The mutation lock and generation barrier cover the read/merge, ONE Room write and publication. */
    private suspend fun commitFetchedSuccess(key: FetchKey, batch: FlagRefreshBatch): FlagsResult? {
        val type = key.type
        val trace = currentCoroutineContext()[FlagFetchTrace]
        val mutexStart = System.nanoTime()
        return cacheWriteMutexes.getValue(type).withLock {
            generationMutex.withLock commit@{
                trace?.phase(FlagRefreshPhase.MUTEX, mutexStart)
                val ownsWrite = currentUserId() == key.userId && synchronized(cachedSuccesses) {
                    fetchKey(type, key.userId).copy(resolutionCat = key.resolutionCat) == key
                }
                if (!ownsWrite) {
                    trace?.gap(FlagRefreshGap.CACHE_GENERATION)
                    return@commit null
                }
                val snapshot = flagCacheStore.merge(key.userId, type, batch)
                currentCoroutineContext()[FlagReadSession]?.expiredCategories(
                    snapshot.coverage.filter { it.failure == FlagFailureKind.DEADLINE }.map { it.cat }.distinct().size,
                )
                val hasContent = snapshot.rows.isNotEmpty() || snapshot.coverage.any { it.lastSuccessAt != null }
                if (!hasContent) return@commit FlagsResult.Failure(
                    batch.cause ?: IllegalStateException("Flags refresh produced no available snapshot"),
                )
                val result = snapshot.toResult().let { result ->
                    val issue = result.issue
                    result.copy(issue = issue?.copy(stickyCategories = issue.stickyCategories.map {
                        it.copy(name = batch.categoryNames[it.id])
                    }))
                }
                measured(FlagRefreshPhase.ROOM) { persistFlags(key.userId, type, snapshot) }
                val committed = synchronized(cachedSuccesses) {
                    if (fetchKey(type, key.userId).copy(resolutionCat = key.resolutionCat) == key) {
                        cachedSuccesses[type] = result
                        cachedFetchKeys[type] = key.takeIf { it.resolutionCat == null } ?: fetchKey(type, key.userId)
                        if (trace != null) cachedFetchTraces[type] = trace else cachedFetchTraces.remove(type)
                        true
                    } else {
                        false
                    }
                }
                if (committed) publishCacheUpdate(type)
                result
            }
        }
    }

    private suspend fun persistFlags(
        userId: String,
        type: FlagType,
        snapshot: fr.forumhfr.redface2.core.database.dao.FlagCacheSnapshot,
    ) {
        val trace = currentCoroutineContext()[FlagFetchTrace]
        runCatching { flagCacheStore.save(userId, type, snapshot) }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            trace?.gap(FlagRefreshGap.ROOM_WRITE)
            trace?.failed(FlagRefreshPhase.ROOM, throwable, fatal = false)
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
            is ForumResult.Failure -> {
                currentCoroutineContext()[FlagFetchTrace]?.failed(
                    FlagRefreshPhase.CATALOGUE,
                    result.cause,
                    fatal = true,
                )
                throw result.cause
            }
            ForumResult.Loading -> error("getCategories never emits Loading")
        }
        return all.filter { it.id > 0 }.ifEmpty {
            throw SerializationException("Missing public flags catalogue")
        }
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
        failOnTruncation: Boolean = false,
        sessionExpired: AtomicBoolean? = null,
        rejectDivergentCount: Boolean = false,
    ): List<Flag> {
        val accumulated = mutableListOf<Flag>()
        val trace = currentCoroutineContext()[FlagFetchTrace]
        var page = 1
        var lastResultsCount = 0
        while (page <= MAX_PAGES) {
            trace?.page(sticky = false)
            val envelope = try {
                checkFlagSession(sessionExpired)
                val body = apiClient.getCategoryFlagTopics(
                    cat = cat,
                    bucket = bucket,
                    page = page,
                    resultsPerPage = DEFAULT_RESULTS_PER_PAGE,
                    useAuth = true,
                )
                decodeFlagPage(json, body)
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                trace?.failed(FlagRefreshPhase.BUCKETS, error, fatal = true)
                throw error
            }
            val mapped = RestFlagMappers.toFlags(
                envelope = envelope,
                type = type,
                fallbackCat = cat,
            )
            accumulated += mapped
            lastResultsCount = envelope.resource.resultsCount
            if (mapped.isEmpty() || accumulated.size >= lastResultsCount) {
                if (accumulated.size < lastResultsCount) trace?.gap(FlagRefreshGap.EMPTY_BUCKET_PAGE)
                // The destructive favourite-position gate remains stricter than list replacement.
                check(!rejectDivergentCount || accumulated.size >= lastResultsCount) {
                    "Incomplete favourite position lookup"
                }
                return accumulated
            }
            page += 1
        }
        // Unlike a divergent counter on an empty page, a non-empty walk stopped by our cap
        // has unvisited pages. List refreshes discard this source and retain its previous rows.
        if (accumulated.size < lastResultsCount) {
            trace?.gap(FlagRefreshGap.PAGE_CAP)
            Log.w(
                LOG_TAG,
                "REST flags pagination hit MAX_PAGES=$MAX_PAGES for cat=$cat bucket=$bucket " +
                "with ${accumulated.size}/$lastResultsCount rows; list may be truncated",
            )
            if (failOnTruncation) {
                throw FlagPageCapException()
            }
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
     * failure retains the previous source rather than failing the whole refresh. #984 persists
     * that category's failure independently of the existing diagnostic gap.
     * (A `CancellationException` is
     * rethrown, not swallowed, to keep structured concurrency intact.) Page 1 only:
     * stickies always head the listing, so deeper pages would be pure network noise for this fix.
     * #862 widened the scope from no-subcategory cats to ALL cats (~19 parallel GETs per refresh,
     * same order of magnitude as the bucket fan-out itself) — the drop was proven category-wide.
     */
    private suspend fun fetchStickyFlagSupplement(
        cats: List<Category>,
        type: FlagType,
        alreadyPresent: List<Flag>,
        key: FetchKey,
        record: (CategoryRefreshOutcome) -> Unit,
    ) {
        val userId = key.userId
        val sessionGen = key.sessionGeneration
        val sweepGen = key.sweepGeneration
        // A stale generation (post-clearSessionCache caller, or a fetch that started under an
        // older refresh burst) or an empty catalogue all degrade to « no supplement » — never
        // create a sweep for a logged-out account, never join a newer burst's sweep.
        val trace = currentCoroutineContext()[FlagFetchTrace]
        val sweep = if (cats.isEmpty()) null else stickySweep(cats, userId, sessionGen, sweepGen, trace)
        if (sweep == null) {
            if (cats.isNotEmpty()) trace?.gap(FlagRefreshGap.STICKY_GENERATION)
            cats.forEach { record(CategoryRefreshOutcome(it.id, FlagSource.STICKY,
                receivedAt = clock.instant(), failure = FlagFailureKind.UNKNOWN)) }
            return
        }
        val present = alreadyPresent.mapTo(mutableSetOf()) { it.cat to it.topicId }
        var deadline = false
        try {
            sweep.deferred.await()
        } catch (_: StickyDeadlineException) {
            // Another consumer exhausted the shared sweep's budget, not this refresh's Job.
            // stickyOutcomes keeps finished partitions and marks missing bodies DEADLINE.
            currentCoroutineContext().ensureActive()
        } catch (timeout: TimeoutCancellationException) {
            deadline = true
            throw timeout
        } catch (cancellation: CancellationException) {
            currentCoroutineContext().ensureActive()
            val sameSession = synchronized(cachedSuccesses) { sessionGeneration == sessionGen }
            if (sameSession && currentUserId() == userId) throw cancellation
            trace?.gap(FlagRefreshGap.STICKY_CANCELLED)
        } finally {
            // Record completed categories even when the collection deadline interrupts await().
            try {
                stickyOutcomes(cats, type, present, sweep, trace).forEach(record)
                sweep.trace?.let { trace?.absorbSticky(it) }
            } finally {
                releaseSweep(sweep, deadline)
            }
        }
    }

    private fun stickyOutcomes(
        cats: List<Category>,
        type: FlagType,
        present: Set<Pair<Int, Int>>,
        sweep: ObservedSweep,
        trace: FlagFetchTrace?,
    ): List<CategoryRefreshOutcome> {
        val bodies = synchronized(sweep.bodies) { sweep.bodies.toMap() }
        return cats.mapNotNull { category ->
            val body = bodies[category.id] ?: if (sweep.deferred.isCompleted) {
                Result.failure(FlagReadDeadlineException())
            } else {
                return@mapNotNull null
            }
            try {
                val envelope = decodeFlagPage(json, body.getOrThrow())
                val flags = RestFlagMappers.toStickyFlags(envelope, type, category.id)
                    .filter { (it.cat to it.topicId) !in present }
                CategoryRefreshOutcome(category.id, FlagSource.STICKY, flags, clock.instant())
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                if (body.isSuccess) {
                    trace?.gap(FlagRefreshGap.STICKY_DECODE, stickyCategory = category.id)
                    trace?.failed(FlagRefreshPhase.STICKIES, error, fatal = false)
                }
                CategoryRefreshOutcome(category.id, FlagSource.STICKY, receivedAt = clock.instant(),
                    failure = error.toFlagFailureKind())
            }
        }
    }

    private suspend fun releaseSweep(sweep: ObservedSweep, deadline: Boolean) {
        val drain = synchronized(stickySweeps) {
            sweep.callers--
            val stop = (sweep.callers == 0 || deadline) && !sweep.deferred.isCompleted
            if (stop) {
                stickySweeps.entries.removeAll { it.value === sweep }
                sweep.deferred.cancel(if (deadline) StickyDeadlineException() else null)
            }
            stop
        }
        // Await real Call cancellation and response closure before publishing the partial snapshot.
        if (drain) withContext(NonCancellable) { sweep.deferred.join() }
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
     * drops everything. Per-cat failures carry a failed Result (best-effort, #251). Older
     * generations are cancelled at their barrier: none of their callers can publish those results.
     */
    private suspend fun stickySweep(
        cats: List<Category>,
        userId: String,
        sessionGen: Int,
        sweepGen: Int,
        parentTrace: FlagFetchTrace?,
    ): ObservedSweep? {
        val session = checkNotNull(currentCoroutineContext()[FlagReadSession])
        return synchronized(stickySweeps) {
            val ownsSweep = synchronized(cachedSuccesses) {
                sessionGen == sessionGeneration && sweepGen == sweepGeneration
            }
            if (!ownsSweep) return@synchronized null
            val key = SweepKey(
                sessionGeneration = sessionGen,
                sweepGeneration = sweepGen,
                userId = userId,
                catIds = cats.map { it.id },
            )
            val existing = stickySweeps[key]
            if (existing != null && !existing.deferred.isCancelled) {
                existing.callers++
                parentTrace?.joinedStickySweep()
                existing.trace?.let { parentTrace?.sticky(it) }
                existing
            } else {
                startStickySweep(cats, parentTrace, session).also { stickySweeps[key] = it }
            }
        }
    }

    private fun startStickySweep(
        cats: List<Category>,
        parentTrace: FlagFetchTrace?,
        session: FlagReadSession,
    ): ObservedSweep {
        val trace = parentTrace?.let { parent ->
            FlagFetchTrace(parent.refreshId, parent.type).also { parent.sticky(it) }
        }
        val bodies = mutableMapOf<Int, Result<String>>()
        val deferred = fetchScope.async((trace ?: kotlin.coroutines.EmptyCoroutineContext) + session) {
            refreshDiagnostics.withRefresh {
                withTimeoutOrNull(session.remainingMillis().coerceAtLeast(1)) {
                    coroutineScope {
                        val expired = AtomicBoolean(false)
                        cats.map { category ->
                            async(HfrRequestQueued()) {
                                val body = fetchStickyBody(category.id, expired, trace)
                                synchronized(bodies) { bodies[category.id] = body }
                            }
                        }.awaitAll()
                    }
                }
            }
            synchronized(bodies) { bodies.toMap() }
        }
        return ObservedSweep(deferred, trace, bodies)
    }

    private suspend fun fetchStickyBody(
        cat: Int,
        expired: AtomicBoolean,
        trace: FlagFetchTrace?,
    ): Result<String> = runCatching {
        checkFlagSession(expired)
        trace?.page(sticky = true)
        apiClient.getTopicList(cat = cat, subcat = null, page = 1,
            resultsPerPage = DEFAULT_RESULTS_PER_PAGE, useAuth = true)
    }.onFailure { throwable ->
        if (throwable is SessionExpiredException) expired.set(true)
        if (throwable is CancellationException) throw throwable
        trace?.gap(FlagRefreshGap.STICKY_REQUEST, stickyCategory = cat)
        trace?.failed(FlagRefreshPhase.STICKIES, throwable, fatal = false)
        Log.w(LOG_TAG, "Sticky sweep failed for cat=$cat (best-effort)", throwable)
    }

    /** #862 — identity of one shared sweep : session + refresh burst + account + category set. */
    private data class SweepKey(
        val sessionGeneration: Int,
        val sweepGeneration: Int,
        val userId: String,
        val catIds: List<Int>,
    )

    private class ObservedSweep(
        val deferred: Deferred<Map<Int, Result<String>>>,
        val trace: FlagFetchTrace?,
        val bodies: MutableMap<Int, Result<String>>,
    ) {
        var callers = 1 // Under stickySweeps; released even when await is cancelled.
    }

    private val stickySweeps: MutableMap<SweepKey, ObservedSweep> = mutableMapOf()

    /** Fence publication before closing admission and draining all owners in the policy controller. */
    private suspend fun retirePolicy(): List<kotlinx.coroutines.Job> = generationMutex.withLock {
        synchronized(cachedSuccesses) {
            policyGeneration++
            sweepGeneration++
            activeRefreshes.clear()
            cachedFetchKeys.clear()
        }
        val jobs = synchronized(inFlightFetches) { inFlightFetches.values.map { it.deferred } } +
            synchronized(stickySweeps) { stickySweeps.values.map { it.deferred } }
        cancelSupersededFetches()
        jobs
    }

    // #862/#984 — accessed under cachedSuccesses. Bumps also hold generationMutex so a manual
    // pull fences commit and publication; overlapping pulls of one type may share the barrier.
    private var sweepGeneration = 0

    private fun FlagType.toBucket(): HfrRestFlagBucket = when (this) {
        FlagType.CYAN -> HfrRestFlagBucket.PARTICIPATED
        FlagType.RED -> HfrRestFlagBucket.READ
        FlagType.FAVORITE -> HfrRestFlagBucket.FAVORITES
    }

    init {
        fetchScope.launch { policyOwner.observe() }
    }

    private companion object {
        const val LOG_TAG = "FlagRepository"
        const val DEFAULT_RESULTS_PER_PAGE = 50
        const val MAX_PAGES = 100
        const val FAVORITE_RESOLUTION_TTL_SECONDS = 30L
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

/**
 * Raised internally by [DefaultFlagRepository.addFlag] when HFR's `addflag.php` response
 * did not carry the « Favori positionné » confirmation. It is the cause wrapped in the
 * failed [Result] returned to the caller, so the UI can surface a generic "could not add"
 * message. No response body is carried — the page can embed session metadata.
 */
class FlagAddFailedException(topicId: Int) :
    Exception("HFR did not confirm the favourite add for topic $topicId")

private val GLOBAL_STRATEGIES = setOf(FlagsRefreshStrategy.GLOBAL_ONLY, FlagsRefreshStrategy.GLOBAL_FIRST)
