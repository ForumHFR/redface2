package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.ModerationAlertOutcome
import fr.forumhfr.redface2.core.model.write.ModerationAlertState

/** Explicit authenticated moderation actions. Network/session exceptions propagate to the caller. */
interface ModerationRepository {
    suspend fun loadAlert(cat: Int, topicId: Int, numreponse: Int, page: Int): ModerationAlertState
    suspend fun sendAlert(form: ModerationAlertState.Form, reason: String): ModerationAlertOutcome
    suspend fun joinAlert(prompt: ModerationAlertState.JoinPrompt): ModerationAlertOutcome
}
