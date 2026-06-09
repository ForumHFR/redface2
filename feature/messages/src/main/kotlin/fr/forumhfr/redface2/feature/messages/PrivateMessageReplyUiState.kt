package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.model.PostContent

/**
 * MVI state of the private-message reply editor (#301). Mirrors the post editor's shape (draft +
 * parsed preview AST + the three HFR per-post option toggles) but is scoped to a private
 * conversation, so it carries no `cat`/`subcat`/quote fields.
 *
 * The parsed [fr.forumhfr.redface2.core.model.write.ReplyForm] (with its `hash_check` and hidden
 * fields) is held privately by the ViewModel and never exposed here, so no private metadata leaks
 * into a state snapshot.
 */
data class PrivateMessageReplyUiState(
    /** True while the initial `bddpost.php` form GET is in flight (and during a silent refetch). */
    val isLoadingForm: Boolean = true,
    /** True once a non-anonymous form has been parsed; gates submission. */
    val formAvailable: Boolean = false,
    /** True when the form GET failed (network / session / parse / anonymous) → show retry. */
    val formError: Boolean = false,
    val draft: TextFieldValue = TextFieldValue(),
    val isPreviewVisible: Boolean = false,
    val preview: PostContent = PostContent(blocks = emptyList()),
    val signatureEnabled: Boolean = false,
    val smileyDisabled: Boolean = false,
    val emailNotificationEnabled: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitError: PrivateMessageReplyError? = null,
) {
    val canSubmit: Boolean
        get() = formAvailable && !isLoadingForm && !isSubmitting && draft.text.isNotBlank()
}

/**
 * Submit-phase errors surfaced as a banner over the editor (the draft is always preserved). HFR
 * reuses the same `bddpost.php` failure sentences for private messages as for topics, so the
 * recognised reasons map 1:1 — except [Unexpected], which is the non-destructive fallback for an
 * unrecognised response: the private-message POST success sentence is not pinned by a live fixture
 * (a real send to a third party was intentionally avoided), so the UI invites the user to verify the
 * conversation rather than claiming a hard failure.
 */
sealed interface PrivateMessageReplyError {
    data object Empty : PrivateMessageReplyError
    data object InvalidHashCheck : PrivateMessageReplyError
    data object AntiFlood : PrivateMessageReplyError
    data object LoginRequired : PrivateMessageReplyError
    data object Network : PrivateMessageReplyError
    data object SessionExpired : PrivateMessageReplyError
    data object Unexpected : PrivateMessageReplyError
}
