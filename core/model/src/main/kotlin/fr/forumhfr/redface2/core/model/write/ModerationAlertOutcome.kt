package fr.forumhfr.redface2.core.model.write

/** HFR's response to one alert submission. Transport/session failures propagate as exceptions. */
sealed interface ModerationAlertOutcome {
    /** #293 — each outcome carries HFR's own sentence verbatim (see [ModerationAlertState]). */
    data class Sent(val message: String) : ModerationAlertOutcome
    data class Joined(val message: String) : ModerationAlertOutcome
    data class Rejected(val message: String) : ModerationAlertOutcome
}
