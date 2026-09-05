package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.write.ModerationRepository
import fr.forumhfr.redface2.core.model.write.ModerationAlertOutcome
import fr.forumhfr.redface2.core.model.write.ModerationAlertState
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.write.ModerationAlertPageParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** No cache or application retry: each form/token belongs to the current account and post. */
@Singleton
class DefaultModerationRepository @Inject constructor(
    private val hfrClient: HfrClient,
    private val parser: ModerationAlertPageParser,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ModerationRepository {
    override suspend fun loadAlert(
        cat: Int,
        topicId: Int,
        numreponse: Int,
        page: Int,
    ): ModerationAlertState = withContext(ioDispatcher) {
        parser.parseState(hfrClient.fetchModerationAlertPage(cat, topicId, numreponse, page))
    }

    override suspend fun sendAlert(
        form: ModerationAlertState.Form,
        reason: String,
    ): ModerationAlertOutcome = withContext(ioDispatcher) {
        if (reason.isBlank()) {
            ModerationAlertOutcome.Rejected("")
        } else {
            parser.parseOutcome(
                hfrClient.submitModerationAlert(form.action, form.hashCheck, form.refererPage, reason),
            )
        }
    }

    override suspend fun joinAlert(prompt: ModerationAlertState.JoinPrompt): ModerationAlertOutcome =
        withContext(ioDispatcher) {
            parser.parseOutcome(
                hfrClient.joinModerationAlert(prompt.action, prompt.hashCheck, prompt.refererPage),
            )
        }
}
