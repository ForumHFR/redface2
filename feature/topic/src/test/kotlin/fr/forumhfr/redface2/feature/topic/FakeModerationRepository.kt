package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.domain.write.ModerationRepository
import fr.forumhfr.redface2.core.model.write.ModerationAlertOutcome
import fr.forumhfr.redface2.core.model.write.ModerationAlertState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

internal class FakeModerationRepository : ModerationRepository {
    var alert: ModerationAlertState = ModerationAlertState.Form("modo.php?cat=13", "form-token", "referer")
    var outcome: ModerationAlertOutcome = ModerationAlertOutcome.Sent(SENT_MESSAGE)
    var loadError: Throwable? = null
    var submitError: Throwable? = null
    var loadGate: CompletableDeferred<Unit>? = null
    var submitGate: CompletableDeferred<Unit>? = null
    var loadCancelled = false
    var submissionsCompleted = 0
    val loads = mutableListOf<List<Int>>()
    val sends = mutableListOf<Pair<ModerationAlertState.Form, String>>()
    val joins = mutableListOf<ModerationAlertState.JoinPrompt>()

    override suspend fun loadAlert(cat: Int, topicId: Int, numreponse: Int, page: Int): ModerationAlertState {
        loads += listOf(cat, topicId, numreponse, page)
        try {
            loadGate?.await()
        } catch (cancellation: CancellationException) {
            loadCancelled = true
            throw cancellation
        }
        loadError?.let { throw it }
        return alert
    }

    override suspend fun sendAlert(form: ModerationAlertState.Form, reason: String): ModerationAlertOutcome {
        sends += form to reason
        submitGate?.await()
        submitError?.let { throw it }
        submissionsCompleted++
        return outcome
    }

    override suspend fun joinAlert(prompt: ModerationAlertState.JoinPrompt): ModerationAlertOutcome {
        joins += prompt
        submitGate?.await()
        submitError?.let { throw it }
        submissionsCompleted++
        return outcome
    }

    companion object {
        /** #293 — HFR's own success sentence, carried verbatim by the outcome. */
        const val SENT_MESSAGE = "Un message a été envoyé avec succès aux modérateurs"
        const val JOINED_MESSAGE = "Vous êtes désormais joint à la demande de modération."
    }
}
