package fr.forumhfr.redface2.core.data.flags

import android.util.Log
import fr.forumhfr.redface2.core.data.forum.RestListEnvelope
import fr.forumhfr.redface2.core.data.forum.RestTopic
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.network.HfrApiClient
import fr.forumhfr.redface2.core.network.HfrRestFlagBucket
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
 * **Why per-category and not the global endpoint** : `protocol-hfr.md` documents that
 * the global REST drapeau endpoint (`forums/hardwarefr/topics/{bucket}/`) returns a
 * *grouped-by-category* envelope, distinct from the flat `RestListEnvelope<RestTopic>`
 * shape we have a captured fixture for. Without a captured global fixture (live
 * capture not available in the migration session), we route through the per-cat REST
 * endpoint whose contract is proven by `rest_cat23_participated.json`. Cost : one
 * REST GET per HFR public category (~19 cats), parallelised through the IO dispatcher.
 * The global endpoint can be wired in a follow-up PR once a fixture is captured.
 *
 * The first [observe] call per [FlagType] fans out across categories, concatenates the
 * results and caches the success for the current auth session ; tab switches reuse the
 * cache so the screen does not implicitly mark drapeaux as read by re-hitting the auth
 * REST endpoint. Explicit [refresh] calls always fetch and broadcast through a per-type
 * [MutableSharedFlow].
 *
 * Caching to Room is deferred to issue #26. The in-memory cache is purged on
 * [clearSessionCache] (logout, account switch).
 */
@Singleton
class DefaultFlagRepository @Inject constructor(
    private val apiClient: HfrApiClient,
    private val forumRepository: ForumRepository,
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
            emit(FlagsResult.Loading)
            emit(fetch(type))
        }
        emitAll(refreshes.getValue(type).asSharedFlow())
    }

    override suspend fun refresh(type: FlagType) {
        val refreshesForType = refreshes.getValue(type)
        refreshesForType.emit(FlagsResult.Loading)
        refreshesForType.emit(fetch(type))
    }

    override fun clearSessionCache() {
        synchronized(cachedSuccesses) { cachedSuccesses.clear() }
    }

    private suspend fun fetch(type: FlagType): FlagsResult = withContext(ioDispatcher) {
        runCatching {
            val cats = loadCategories()
            val bucket = type.toBucket()
            coroutineScope {
                cats.map { category ->
                    async { fetchAllPages(cat = category.id, bucket = bucket, defaultType = type) }
                }.awaitAll().flatten()
            }
        }.fold(
            onSuccess = { flags ->
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

    /**
     * Resolves the public category list. Reuses [ForumRepository.observeCategories]
     * which keeps an in-memory cache per session, so tabbing back to drapeaux after
     * the Forum tab has loaded is one round-trip cheaper. Surfaces [ForumResult.Failure]
     * as a thrown exception so the outer `runCatching` maps it to [FlagsResult.Failure]
     * with the original cause.
     */
    private suspend fun loadCategories(): List<Category> {
        val first = forumRepository.observeCategories().first { it !is ForumResult.Loading }
        return when (first) {
            is ForumResult.Success -> first.value
            is ForumResult.Failure -> throw first.cause
            ForumResult.Loading -> error("filtered above")
        }
    }

    /**
     * Walks every page of `categories/{cat}/topics/{bucket}/` until either the response
     * declares we have everything (`page * results_per_page >= results_count`) or the
     * server returns an empty page (defensive — shouldn't happen but prevents an
     * infinite loop on a malformed envelope).
     */
    private suspend fun fetchAllPages(
        cat: Int,
        bucket: HfrRestFlagBucket,
        defaultType: FlagType,
    ): List<Flag> {
        val accumulated = mutableListOf<Flag>()
        var page = 1
        while (true) {
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
            val list = envelope.resource
            val pageSize = list.resultsPerPage.takeIf { it > 0 } ?: DEFAULT_RESULTS_PER_PAGE
            val seen = list.page * pageSize
            if (mapped.isEmpty() || seen >= list.resultsCount) break
            page += 1
        }
        return accumulated
    }

    private fun FlagType.toBucket(): HfrRestFlagBucket = when (this) {
        FlagType.CYAN -> HfrRestFlagBucket.PARTICIPATED
        FlagType.RED -> HfrRestFlagBucket.READ
        FlagType.FAVORITE -> HfrRestFlagBucket.FAVORITES
    }

    private companion object {
        const val LOG_TAG = "FlagRepository"
        const val DEFAULT_RESULTS_PER_PAGE = 50
    }
}
