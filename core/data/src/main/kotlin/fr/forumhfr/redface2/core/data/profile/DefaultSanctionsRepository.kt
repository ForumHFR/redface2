package fr.forumhfr.redface2.core.data.profile

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.profile.SanctionsRepository
import fr.forumhfr.redface2.core.model.profile.SanctionsHistory
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.profile.SanctionsHistoryParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
class DefaultSanctionsRepository @Inject constructor(
    private val client: HfrClient,
    private val parser: SanctionsHistoryParser,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SanctionsRepository {
    override suspend fun loadSanctions(): Result<SanctionsHistory> = withContext(ioDispatcher) {
        try {
            Result.success(parser.parse(client.fetchSanctionsHistoryPage()))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            // Same Result contract as DefaultProfileRepository: retain transport and parse failures.
            Result.failure(throwable)
        }
    }
}
