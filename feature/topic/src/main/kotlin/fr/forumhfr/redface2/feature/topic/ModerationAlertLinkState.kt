package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.domain.error.HfrErrorKind

/** Only the post context from the link; numreponse is unique per category. */
data class ModerationAlertLinkTarget(val cat: Int, val post: Int, val numreponse: Int, val page: Int)

/**
 * Activity-retained alert state: the initial read precedes the sheet, without saving account-specific messages.
 */
sealed interface ModerationAlertLinkState {
    val target: ModerationAlertLinkTarget?

    data object Idle : ModerationAlertLinkState {
        override val target: ModerationAlertLinkTarget? = null
    }

    /** Only a retry or an account change in an already open sheet keeps the modal visible. */
    data class Loading(
        override val target: ModerationAlertLinkTarget,
        val keepSheetOpen: Boolean = false,
    ) : ModerationAlertLinkState
    data class Info(
        override val target: ModerationAlertLinkTarget,
        val message: String,
        val treatedAt: String? = null,
    ) : ModerationAlertLinkState
    data class SignInRequired(override val target: ModerationAlertLinkTarget) : ModerationAlertLinkState
    data class Error(
        override val target: ModerationAlertLinkTarget,
        val kind: HfrErrorKind,
    ) : ModerationAlertLinkState
    data class NavigateToPost(
        override val target: ModerationAlertLinkTarget,
        val withAlertSheet: Boolean,
    ) : ModerationAlertLinkState
}

sealed interface ModerationAlertLinkIntent {
    data class Open(val target: ModerationAlertLinkTarget) : ModerationAlertLinkIntent
    data object Retry : ModerationAlertLinkIntent
    data object Dismiss : ModerationAlertLinkIntent
    data object ViewPost : ModerationAlertLinkIntent
}
