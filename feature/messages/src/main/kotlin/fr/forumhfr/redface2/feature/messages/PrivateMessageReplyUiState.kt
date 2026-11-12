package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftOffer
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.UploadProgress

/**
 * MVI state of the private-message reply editor (#301). Mirrors the post editor's shape (body +
 * parsed preview AST + the three HFR per-post option toggles) but is scoped to a private
 * conversation, so it carries no `cat`/`subcat`/quote fields.
 *
 * The parsed [fr.forumhfr.redface2.core.model.write.ReplyForm] (with its `hash_check` and hidden
 * fields) is held privately by the ViewModel and never exposed here, so no private metadata leaks
 * into a state snapshot.
 */
data class PrivateMessageReplyUiState(
    /** Owner-bound draft read finishes before a recreated field can accept input. */
    val isInitializing: Boolean = false,
    /** True while the initial `bddpost.php` form GET is in flight (and during a silent refetch). */
    val isLoadingForm: Boolean = true,
    /** True once a non-anonymous form has been parsed; gates submission. */
    val formAvailable: Boolean = false,
    /** Private app-bar context from ReplyForm.sujet only; never saved in navigation or a Bundle. */
    val conversationTitle: String = "",
    /** True when the form GET failed (network / session / parse / anonymous) → show retry. */
    val formError: Boolean = false,
    val quotePending: Boolean = false,
    val quoteCommitPending: Boolean = false,
    val unreadableQuoteRequest: Boolean = false,
    val quoteFetchFailures: Int = 0,
    val quoteStorageUnavailable: Boolean = false,
    /** Text-only mirror; selection and undo history stay in the ViewModel. */
    val bodyText: String = "",
    /**
     * The body is known from a session/attempt or the first successful form load. A resumed body,
     * including a blank edit, prevents a GET from duplicating the server-provided inline quote.
     */
    val draftHydratedFromForm: Boolean = false,
    /** Ordinary form hydration only; a delivered Q is never a replaceable restore seed. */
    val draftHydratedContent: String = "",
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
    val submission: EditorSubmissionState = EditorSubmissionState.Ready,
    val isSubmitting: Boolean = false,
    val submitError: EditorError? = null,
    /**
     * #312 — `true` while the « Confirmation avant publication » dialog is shown. Only raised when
     * the preference is active/unknown AND the submit already passed every validation gate (mirrors
     * the post editor's `showSubmitConfirmation`). Confirm / dismiss go through
     * [PrivateMessageReplyViewModel.onSubmitConfirmed] / [PrivateMessageReplyViewModel.onSubmitConfirmationDismissed].
     */
    val showSubmitConfirmation: Boolean = false,
    /**
     * #405 — body of a previously-cached private-reply draft for this thread, surfaced on init when
     * a non-empty draft was found outside a matching session. « Restaurer » pre-fills the editor,
     * « Ignorer » clears the cached content. A matching ED-81 checkpoint resumes silently instead.
     */
    val restorableDraft: String? = null,
    val draftOffers: List<DraftOffer> = emptyList(),
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
     * field locked (`readOnly`) against user body mutations; programmatic `[img]` insertion remains allowed.
     */
    val isUploading: Boolean = false,
    /** #459 — typed upload failure surfaced as a dismissible banner (shared `:core:ui` taxonomy). */
    val uploadError: EditorError? = null,
    /** #459 — « n/N » batch counter (null for a single image). */
    val uploadProgress: UploadProgress? = null,
    /** #1128 — observed continuously so the next image pick uses the current preference. */
    val imagePickerMode: ImagePickerMode = ImagePickerMode.DEFAULT,
    val editorShortcut: EditorShortcut = EditorShortcut.NONE,
) {
    val hasDormantQuote: Boolean get() = quotePending && submission is EditorSubmissionState.Uncertain
    val canIgnorePendingQuote: Boolean
        get() = (unreadableQuoteRequest || quoteRecoveryAllowed) && !isLoadingForm
    private val quoteRecoveryAllowed: Boolean get() = hasDormantQuote || quotePending && quoteFetchFailures >= 2
    val armToken: ArmToken? get() = (submission as? EditorSubmissionState.Armed)?.token

    val canSubmit: Boolean
        get() = !isInitializing && formAvailable && !isLoadingForm && !isSubmitting && !isUploading &&
            !submission.blocksBodyMutation && bodyText.isNotBlank() && !quotePending && !quoteStorageUnavailable
}
