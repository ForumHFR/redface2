package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState

enum class ReportReason { CHILD_SAFETY, ILLEGAL_CONTENT, HARASSMENT, APP_PROBLEM, OTHER }

data class ReportDraft(
    val reason: ReportReason? = null,
    val description: String = "",
    val link: String = "",
)

enum class ReportSession { CHECKING, ANONYMOUS, AUTHENTICATED }

enum class ReportError {
    UNCERTAIN, DRAFT_WRITE_FAILED, SESSION_EXPIRED, NETWORK, INVALID_HASH, ANTI_FLOOD,
    UNKNOWN_RECIPIENT, EMPTY_MESSAGE, REFUSED,
}

data class ReportContentUiState(
    val session: ReportSession = ReportSession.CHECKING,
    val sessionExpired: Boolean = false,
    val draft: ReportDraft = ReportDraft(),
    val draftReady: Boolean = false,
    val isLoadingForm: Boolean = false,
    val formAvailable: Boolean = false,
    val formError: Boolean = false,
    val submission: EditorSubmissionState = EditorSubmissionState.Ready,
    val error: ReportError? = null,
    val draftWriteFailed: Boolean = false,
) {
    val requiresLogin: Boolean
        get() = session == ReportSession.ANONYMOUS || sessionExpired || error == ReportError.SESSION_EXPIRED
    val isSending: Boolean
        get() = submission is EditorSubmissionState.PersistingAttempt || submission is EditorSubmissionState.Sending
    val canEdit: Boolean
        get() = session == ReportSession.AUTHENTICATED && draftReady && !submission.blocksBodyMutation
    val validFields: Boolean
        get() = draft.reason != null && draft.description.isNotBlank() && ReportMessageFormatter.isValidLink(draft.link)
    val formReady: Boolean get() = formAvailable && !isLoadingForm
    val canSubmit: Boolean get() = canEdit && formReady && validFields
    val armToken: ArmToken? get() = (submission as? EditorSubmissionState.Armed)?.token
}

sealed interface ReportContentIntent {
    data class ReasonChanged(val reason: ReportReason) : ReportContentIntent
    data class DescriptionChanged(val description: String) : ReportContentIntent
    data class LinkChanged(val link: String) : ReportContentIntent
    data object RetryForm : ReportContentIntent
    data object Submit : ReportContentIntent
    data class SubmitWithTimeout(val timeoutMillis: Long) : ReportContentIntent
    data class Confirm(val token: ArmToken?) : ReportContentIntent
    data object DismissConfirmation : ReportContentIntent
    data object Close : ReportContentIntent
    data object Login : ReportContentIntent
    data object Email : ReportContentIntent
    data object CheckMessages : ReportContentIntent
}

sealed interface ReportContentEffect {
    data object SubmitSucceeded : ReportContentEffect
    data object CloseCommitted : ReportContentEffect
    data object Login : ReportContentEffect
    data object Email : ReportContentEffect
    data object CheckMessages : ReportContentEffect
}
