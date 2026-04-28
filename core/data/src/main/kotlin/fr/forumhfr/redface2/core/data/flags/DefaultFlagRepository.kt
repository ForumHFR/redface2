package fr.forumhfr.redface2.core.data.flags

import android.util.Log
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.flags.FlagsListParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Phase 1B.4 network-only implementation of [FlagRepository]. Each call to [observe]
 * triggers an initial fetch; subsequent [refresh] calls broadcast a fresh result through
 * a per-type [MutableSharedFlow] so concurrent observers see the same payload.
 *
 * Caching to Room is intentionally deferred: at this stage, the user's drapeaux page is
 * always small (~150 rows in the heaviest filter) and HFR latency is acceptable for the
 * "open the home tab" cold path. Cache-aside arrives in Phase 1D when persistence policy
 * for the home tab is reviewed end-to-end (cf. roadmap `1D.2`).
 */
@Singleton
class DefaultFlagRepository @Inject constructor(
    private val hfrClient: HfrClient,
    private val parser: FlagsListParser,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : FlagRepository {

    /**
     * One refresh-trigger per [FlagType] so a refresh on one tab doesn't re-fetch the
     * other two. Replay = 0 because [observe] flushes its own initial fetch via
     * [Flow.onStart] — the shared flow only carries explicit refresh acks.
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
        emit(FlagsResult.Loading)
        emit(fetch(type))
        emitAll(refreshes.getValue(type).asSharedFlow())
    }

    override suspend fun refresh(type: FlagType) {
        val result = fetch(type)
        refreshes.getValue(type).emit(result)
    }

    private suspend fun fetch(type: FlagType): FlagsResult = withContext(ioDispatcher) {
        runCatching {
            val html = hfrClient.getFlagsPage(owntopic = type.owntopic())
            parser.parse(html, defaultType = type)
        }.fold(
            onSuccess = { flags -> FlagsResult.Success(flags) },
            onFailure = { throwable ->
                Log.w(LOG_TAG, "Flags fetch failed for $type", throwable)
                FlagsResult.Failure(throwable)
            },
        )
    }

    private fun FlagType.owntopic(): Int = when (this) {
        FlagType.RED -> 1
        FlagType.CYAN -> 2
        FlagType.FAVORITE -> 3
    }

    private companion object {
        const val LOG_TAG = "FlagRepository"
    }
}
