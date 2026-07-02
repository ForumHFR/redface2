package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.ui.editor.UploadError
import fr.forumhfr.redface2.core.ui.editor.UploadProgress

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
    /**
     * True once the option toggles have been hydrated from a parsed form. Mirrors the post editor's
     * `optionsHydratedFromForm`: a silent refetch after an expired `hash_check` must not re-hydrate
     * (and thus clobber) a toggle the user changed in between.
     */
    val optionsHydratedFromForm: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitError: PrivateMessageReplyError? = null,
    /**
     * #312 — `true` while the « Confirmation avant publication » dialog is shown. Only raised when
     * the persisted preference is on AND the submit already passed every validation gate (mirrors
     * the post editor's `showSubmitConfirmation`). Confirm / dismiss go through
     * [PrivateMessageReplyViewModel.onSubmitConfirmed] / [PrivateMessageReplyViewModel.onSubmitConfirmationDismissed].
     */
    val showSubmitConfirmation: Boolean = false,
    /**
     * #405 — body of a previously-cached private-reply draft for this thread, surfaced on init when
     * a non-empty draft was found. Non-null means « propose a restore » : « Restaurer » pre-fills
     * the editor, « Ignorer » deletes the cached row. Never silently applied nor lost.
     */
    val restorableDraft: String? = null,
    /**
     * #606 — true only when the loaded form is the OWNER's DT/MultiMP (HFR served the `newdest`
     * field). Gates the member editor in the UI ; a simple participant / one-to-one MP keeps it
     * false and never sees the editor.
     */
    val canManageRecipients: Boolean = false,
    /**
     * #606 — current working list of DT/MultiMP members (owner view), parsed from HFR's `newdest`
     * CSV on form load and mutated by add / remove. Order, case, accents, `+` and internal spaces
     * are preserved verbatim (« Bébé Yoda », « stitch+ ») ; only the leading / trailing whitespace
     * of each CSV element is trimmed. Empty when [canManageRecipients] is false.
     */
    val recipients: List<String> = emptyList(),
    /**
     * #606 — true once the owner has actually added / removed a member. Until then the submit sends
     * `recipientsOverride = null` so the repository forwards HFR's original `newdest` **verbatim**
     * (a normal owner reply must never round-trip the member list through parse → join, which would
     * normalise whitespace / drop entries and risk losing members). Only an explicit edit arms it.
     */
    val recipientsDirty: Boolean = false,
    /**
     * #459 — `true` while an image upload (single or batch) is in flight: toolbar spinner + body
     * field locked (`readOnly`) so the caret cannot move between two programmatic `[img]` insertions.
     */
    val isUploading: Boolean = false,
    /** #459 — typed upload failure surfaced as a dismissible banner (shared `:core:ui` taxonomy). */
    val uploadError: UploadError? = null,
    /** #459 — « n/N » batch counter (null for a single image). */
    val uploadProgress: UploadProgress? = null,
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
