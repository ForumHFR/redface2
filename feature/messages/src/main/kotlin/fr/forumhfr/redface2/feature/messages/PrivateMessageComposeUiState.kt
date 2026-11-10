package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientState
import fr.forumhfr.redface2.core.ui.editor.UploadProgress

/**
 * MVI state of the new-conversation composer (#301 follow-up). Mirrors
 * [PrivateMessageReplyUiState]: the parsed `ReplyForm` stays private to the ViewModel; UI text lives
 * only in memory. The two user-typed routing fields HFR's standalone composer exposes are:
 *
 * - [recipients] — HFR's `dest` field. One pseudo, or several separated by commas (a MultiMP),
 *   per HFR's own field help. Legacy display adapter; [recipientState] preserves chips and buffer.
 * - [subject] — HFR's `sujet` field, capped at [SUBJECT_MAX_LENGTH] like the web composer's
 *   `maxlength=70` (the ViewModel truncates, so the cap holds even through paste).
 *
 * Submit-phase errors reuse [EditorError] : the POST rides the same `bddpost.php`
 * family, and the non-destructive-Unknown rule (draft kept, « vérifiez vos messages » banner)
 * applies identically since the success response was never exercised live.
 */
data class PrivateMessageComposeUiState(
    val isInitializing: Boolean = false,
    /** A successful GET, resumed checkpoint or explicit body edit proves the body is known. */
    val bodyKnown: Boolean = false,
    val isLoadingForm: Boolean = true,
    val formAvailable: Boolean = false,
    val formError: Boolean = false,
    val recipients: String = "",
    /** Private representation; recipients adapts the legacy chrome until C4. */
    val recipientState: EditorRecipientState = EditorRecipientState(
        recipients.split(',').filter(String::isNotBlank),
    ),
    /** Exact untouched recipient prefill supplied by routing/HFR, distinct from a user edit. */
    val recipientsHydratedContent: String? = null,
    val subject: String = "",
    val bodyText: String = "",
    val isPreviewVisible: Boolean = false,
    val preview: PostContent = PostContent(blocks = emptyList()),
    val signatureEnabled: Boolean = false,
    val smileyDisabled: Boolean = false,
    val emailNotificationEnabled: Boolean = false,
    val optionsHydratedFromForm: Boolean = false,
    val submission: EditorSubmissionState = EditorSubmissionState.Ready,
    val isSubmitting: Boolean = false,
    val submitError: EditorError? = null,
    val showSubmitConfirmation: Boolean = false,
    /**
     * #405 — body of a previously-cached new-conversation draft, surfaced on init when a non-empty
     * draft was found. [restorableSubject] / [restorableRecipients] carry the cached routing fields.
     * Matching ED-81 checkpoints resume silently; other rows remain explicit restore offers.
     * [restorableRecipients] is the private encoded checkpoint (or a historical CSV), never POST data.
     */
    val restorableDraft: String? = null,
    val restorableSubject: String? = null,
    val restorableRecipients: String? = null,
    /**
     * #459 — `true` while an image upload (single or batch) is in flight: toolbar spinner + body
     * field locked (`readOnly`) so the caret cannot move between two programmatic `[img]` insertions.
     */
    val isUploading: Boolean = false,
    /** #459 — typed upload failure surfaced as a dismissible banner (shared `:core:ui` taxonomy). */
    val uploadError: EditorError? = null,
    /** #459 — « n/N » batch counter (null for a single image). */
    val uploadProgress: UploadProgress? = null,
    /** #1128 — observed continuously so the next image pick uses the current preference. */
    val imagePickerMode: ImagePickerMode = ImagePickerMode.DEFAULT,
) {
    /** All three user-typed fields are required — HFR's « remplir tous les champs » rule. */
    val armToken: ArmToken? get() = (submission as? EditorSubmissionState.Armed)?.token

    val canSubmit: Boolean
        get() = !isInitializing && formAvailable && !isLoadingForm && !isSubmitting && !isUploading &&
            !submission.blocksBodyMutation && recipientState.buffer.isBlank() &&
            recipientState.chips.isNotEmpty() && subject.isNotBlank() && bodyText.isNotBlank()

    companion object {
        /** HFR's `maxlength=70` on the composer's `sujet` input (fixture `mp_compose_form.html`). */
        const val SUBJECT_MAX_LENGTH = 70
    }
}
