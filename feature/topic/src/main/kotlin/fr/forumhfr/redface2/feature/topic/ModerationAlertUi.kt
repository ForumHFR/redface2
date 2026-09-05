package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.model.write.ModerationAlertOutcome
import fr.forumhfr.redface2.core.model.write.ModerationAlertState

/** Transient sheet state; form tokens and the draft never enter saved state or the topic cache. */
sealed interface ModerationAlertUi {
    data object Loading : ModerationAlertUi
    data class Form(
        val form: ModerationAlertState.Form,
        val reasonDraft: String = "",
        val submitting: Boolean = false,
    ) : ModerationAlertUi
    data class JoinPrompt(
        val prompt: ModerationAlertState.JoinPrompt,
        val submitting: Boolean = false,
    ) : ModerationAlertUi
    /** #293 — [message] is HFR's own sentence, rendered verbatim; blank falls back to a generic string. */
    data class Info(val message: String, val treatedAt: String? = null) : ModerationAlertUi
    data class Result(val outcome: ModerationAlertOutcome) : ModerationAlertUi
}

internal fun ModerationAlertState.toModerationAlertUi(): ModerationAlertUi = when (this) {
    is ModerationAlertState.Form -> ModerationAlertUi.Form(this)
    is ModerationAlertState.JoinPrompt -> ModerationAlertUi.JoinPrompt(this)
    is ModerationAlertState.PendingMine -> ModerationAlertUi.Info(message)
    is ModerationAlertState.PendingJoined -> ModerationAlertUi.Info(message)
    is ModerationAlertState.TreatedMine -> ModerationAlertUi.Info(message, treatedAt)
    is ModerationAlertState.TreatedJoined -> ModerationAlertUi.Info(message, treatedAt)
    is ModerationAlertState.Unknown -> ModerationAlertUi.Info(excerpt)
}
