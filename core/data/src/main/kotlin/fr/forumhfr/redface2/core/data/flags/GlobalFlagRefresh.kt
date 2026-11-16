package fr.forumhfr.redface2.core.data.flags

import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.network.FlagReadDeadlineException
import fr.forumhfr.redface2.core.network.FlagReadSession
import fr.forumhfr.redface2.core.network.HfrApiClient
import fr.forumhfr.redface2.core.network.HfrRestFlagBucket
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** Global-first and its category continuation share the original admission budget. */
internal class GlobalFlagRefresh(private val api: HfrApiClient, private val json: Json, private val clock: Clock) {
    suspend fun collect(type: FlagType): FlagRefreshBatch = try {
        val session = checkNotNull(currentCoroutineContext()[FlagReadSession])
        val flags = withTimeoutOrNull(session.remainingMillis().coerceAtLeast(1)) {
            session.checkBudget()
            val bucket = when (type) {
                FlagType.CYAN -> HfrRestFlagBucket.PARTICIPATED
                FlagType.RED -> HfrRestFlagBucket.READ
                FlagType.FAVORITE -> HfrRestFlagBucket.FAVORITES
            }
            decodeGlobalFlags(json, api.getGlobalFlagTopics(bucket), type).also { session.checkBudget() }
        } ?: throw FlagReadDeadlineException()
        FlagRefreshBatch(null, emptyList(), clock.instant(), globalOnly = true, observations = flags)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        FlagRefreshBatch(null, emptyList(), clock.instant(), cause = error, globalOnly = true)
    }
}
