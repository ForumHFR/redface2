package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.ui.R

/** One catalogue for the five editor surfaces. Accès restreint stays reserved until #242. */
object EditorErrors {
    val empty = create(
        EditorCause.Empty, EditorErrorClass.Certain,
        R.string.editor_summary_empty, R.string.editor_error_empty, EditorErrorAction.Dismiss,
    )
    val invalidHashCheck = create(
        EditorCause.InvalidHashCheck, EditorErrorClass.Certain,
        R.string.editor_summary_hash, R.string.editor_error_invalid_hash, EditorErrorAction.Details,
    )
    val antiFlood = create(
        EditorCause.AntiFlood, EditorErrorClass.Certain,
        R.string.editor_summary_flood, R.string.editor_error_anti_flood, EditorErrorAction.Details,
    )
    val topicLocked = create(
        EditorCause.TopicLocked, EditorErrorClass.Certain,
        R.string.editor_summary_locked, R.string.editor_error_topic_locked, EditorErrorAction.Details,
    )
    val loginRequired = create(
        EditorCause.LoginRequired, EditorErrorClass.Certain,
        R.string.editor_summary_login, R.string.editor_error_login_required, EditorErrorAction.Details,
    )
    val sessionExpired = create(
        EditorCause.SessionExpired, EditorErrorClass.Certain,
        R.string.editor_summary_session, R.string.editor_error_session_expired, EditorErrorAction.Details,
    )
    val network = create(
        EditorCause.Network, EditorErrorClass.Certain,
        R.string.editor_summary_network, R.string.editor_error_network, EditorErrorAction.Details,
    )
    val quoteFetchFailed = create(
        EditorCause.QuoteFetchFailed, EditorErrorClass.Certain,
        R.string.editor_summary_quote, R.string.editor_error_quote_fetch, EditorErrorAction.Reload,
    )
    val unreadableQuoteRequest = quoteFetchFailed.copy(detail = EditorText(R.string.editor_error_quote_unreadable))
    val draftWriteFailed = create(
        EditorCause.DraftWriteFailed, EditorErrorClass.Certain,
        R.string.editor_submission_draft_write_failed, R.string.editor_detail_draft_write_failed,
                EditorErrorAction.Details,
    )
    val draftSaveFailed = create(
        EditorCause.DraftSaveFailed, EditorErrorClass.Information,
        R.string.editor_submission_draft_write_failed, R.string.editor_detail_draft_save_failed,
        EditorErrorAction.Details,
    )
    val invalidForm = create(
        EditorCause.InvalidForm, EditorErrorClass.Certain,
        R.string.editor_summary_form, R.string.editor_error_unknown, EditorErrorAction.Reload,
    )
    val formNetwork = create(
        EditorCause.FormNetwork, EditorErrorClass.Certain,
        R.string.editor_summary_network, R.string.editor_detail_form_network, EditorErrorAction.Reload,
    )
    val uncertain = create(
        EditorCause.Uncertain, EditorErrorClass.Ambiguous,
        R.string.editor_submission_uncertain, R.string.quick_reply_error_uncertain, EditorErrorAction.Details,
    )
    val missingSubcat = create(
        EditorCause.MissingSubcat, EditorErrorClass.Certain,
        R.string.editor_summary_stale_topic, R.string.editor_error_missing_subcat, EditorErrorAction.Details,
    )
    val missingTitle = create(
        EditorCause.MissingTitle, EditorErrorClass.Validation,
        R.string.editor_summary_title, null, EditorErrorAction.Complete,
    )
    val missingSubcategory = create(
        EditorCause.MissingSubcategory, EditorErrorClass.Validation,
        R.string.editor_summary_subcategory, R.string.editor_detail_subcategory, EditorErrorAction.Complete,
    )
    val missingRecipients = create(
        EditorCause.MissingRecipients, EditorErrorClass.Validation,
        R.string.editor_summary_recipients, null, EditorErrorAction.Complete,
    )
    val missingSubject = create(
        EditorCause.MissingSubject, EditorErrorClass.Validation,
        R.string.editor_summary_subject, null, EditorErrorAction.Complete,
    )
    val noImageReceived = create(
        EditorCause.NoImageReceived, EditorErrorClass.Information,
        R.string.editor_summary_no_image, R.string.editor_upload_error_no_image_received, EditorErrorAction.Dismiss,
    )
    val tooLarge = create(
        EditorCause.TooLarge, EditorErrorClass.Upload,
        R.string.editor_summary_large, R.string.editor_upload_error_too_large, EditorErrorAction.Details,
    )
    val unsupportedType = create(
        EditorCause.UnsupportedType, EditorErrorClass.Upload,
        R.string.editor_summary_format, R.string.editor_upload_error_unsupported_type, EditorErrorAction.Details,
    )
    val configuration = create(
        EditorCause.Configuration, EditorErrorClass.Upload,
        R.string.editor_summary_configuration, R.string.editor_upload_error_configuration, EditorErrorAction.Details,
    )
    val uploadNetwork = create(
        EditorCause.UploadNetwork, EditorErrorClass.Upload,
        R.string.editor_summary_network, R.string.editor_upload_error_network, EditorErrorAction.Details,
    )
    val pickerAbsent = create(
        EditorCause.PickerAbsent, EditorErrorClass.Upload,
        R.string.editor_summary_picker, R.string.editor_detail_picker_absent, EditorErrorAction.Details,
    )
    val imageLimit = create(
        EditorCause.ImageLimit, EditorErrorClass.Information,
        R.string.editor_summary_limit, R.string.editor_detail_image_limit, EditorErrorAction.Dismiss,
    )
    val unknownRecipient = create(
        EditorCause.UnknownRecipient, EditorErrorClass.Certain,
        R.string.editor_summary_recipient_unknown, R.string.messages_compose_error_unknown_recipient,
                EditorErrorAction.Complete,
    )
    val refused = create(
        EditorCause.Refused, EditorErrorClass.Certain,
        R.string.editor_summary_refused, R.string.messages_reply_error_refused, EditorErrorAction.Details,
    )

    fun quoteFetchFailed(inline: Boolean, quickReply: Boolean = false): EditorError = quoteFetchFailed.copy(
        detail = EditorText(when {
            !inline -> R.string.editor_detail_quote_cards
            quickReply -> R.string.quick_reply_error_quote_fetch
            else -> R.string.editor_error_quote_fetch
        }),
    )

    fun uncertain(surface: EditorErrorSurface): EditorError = uncertain.copy(
        detail = EditorText(when (surface) {
            EditorErrorSurface.Topic -> R.string.quick_reply_error_uncertain
            EditorErrorSurface.MessageReply -> R.string.messages_reply_error_unexpected
            EditorErrorSurface.MessageCompose -> R.string.messages_compose_error_unexpected
        }),
    )

    fun server(code: Int, providerId: UploadProviderId): EditorError = EditorError(
        EditorCause.Server(code, providerId), EditorErrorClass.Upload,
        EditorText(R.string.editor_summary_server, listOf(code)),
        EditorText(R.string.editor_upload_error_server, listOf(providerName(providerId), code)),
        EditorErrorAction.Details,
    )

    fun malformed(providerId: UploadProviderId): EditorError = EditorError(
        EditorCause.Malformed(providerId), EditorErrorClass.Upload,
        EditorText(R.string.editor_summary_malformed),
        EditorText(R.string.editor_upload_error_malformed, listOf(providerName(providerId))),
        EditorErrorAction.Details,
    )

    fun stopped(failure: EditorError, progress: UploadProgress?): EditorError = if (progress == null) {
        failure
    } else {
        EditorError(
            EditorCause.UploadStopped(progress.completed, progress.total, failure.cause), EditorErrorClass.Upload,
            EditorText(
                R.plurals.editor_summary_stopped,
                listOf(progress.completed, progress.total),
                quantity = progress.completed,
            ),
            EditorText(R.plurals.editor_detail_stopped, listOf(progress.completed, progress.total,
                failure.detail ?: failure.summary), progress.completed),
            EditorErrorAction.Details,
        )
    }

    private fun providerName(providerId: UploadProviderId): EditorText = EditorText(when (providerId) {
        UploadProviderId.DIBERIE -> R.string.editor_upload_provider_diberie
        UploadProviderId.IMGUR -> R.string.editor_upload_provider_imgur
    })

    private fun create(
        cause: EditorCause,
        errorClass: EditorErrorClass,
        summary: Int,
        detail: Int?,
        action: EditorErrorAction,
    ) = EditorError(cause, errorClass, EditorText(summary), detail?.let { EditorText(it) }, action)
}

/** A successful GET resolves only its own errors; send, draft and validation errors survive. */
fun EditorError?.afterFormLoaded(quotesResolved: Boolean = false): EditorError? = takeUnless {
    when (it?.cause) {
        EditorCause.InvalidForm, EditorCause.FormNetwork -> true
        EditorCause.QuoteFetchFailed -> quotesResolved
        else -> false
    }
}
