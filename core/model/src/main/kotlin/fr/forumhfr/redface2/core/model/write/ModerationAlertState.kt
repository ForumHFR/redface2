package fr.forumhfr.redface2.core.model.write

/** Account-specific state of an alert on a post; numreponse is unique per category. */
sealed interface ModerationAlertState {
    /** Transient session token, read from this form only; never persisted or logged. */
    data class Form(
        val action: String,
        val hashCheck: String,
        val refererPage: String?,
    ) : ModerationAlertState

    data class JoinPrompt(
        val action: String,
        val hashCheck: String,
        val refererPage: String?,
    ) : ModerationAlertState

    /**
     * #293 — the informational states carry HFR's own sentence verbatim so the sheet keeps
     * rendering it if upstream rewords it. `message` is the normalized `div.hop` text.
     */
    data class PendingMine(val message: String) : ModerationAlertState
    data class PendingJoined(val message: String) : ModerationAlertState
    data class TreatedMine(val message: String, val treatedAt: String) : ModerationAlertState
    data class TreatedJoined(val message: String, val treatedAt: String) : ModerationAlertState
    data class Unknown(val excerpt: String) : ModerationAlertState
}
