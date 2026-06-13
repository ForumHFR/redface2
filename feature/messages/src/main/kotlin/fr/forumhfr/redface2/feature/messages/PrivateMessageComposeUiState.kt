package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.model.PostContent

/**
 * MVI state of the new-conversation composer (#301 follow-up). Mirrors
 * [PrivateMessageReplyUiState] (the parsed `ReplyForm` stays private to the ViewModel, no private
 * metadata in snapshots) plus the two user-typed routing fields HFR's standalone composer exposes :
 *
 * - [recipients] — HFR's `dest` field. One pseudo, or several separated by commas (a MultiMP),
 *   per HFR's own field help. Plain `String` : no selection/toolbar interplay to preserve.
 * - [subject] — HFR's `sujet` field, capped at [SUBJECT_MAX_LENGTH] like the web composer's
 *   `maxlength=70` (the ViewModel truncates, so the cap holds even through paste).
 *
 * Submit-phase errors reuse [PrivateMessageReplyError] : the POST rides the same `bddpost.php`
 * family, and the non-destructive-Unknown rule (draft kept, « vérifiez vos messages » banner)
 * applies identically since the success response was never exercised live.
 */
data class PrivateMessageComposeUiState(
    val isLoadingForm: Boolean = true,
    val formAvailable: Boolean = false,
    val formError: Boolean = false,
    val recipients: String = "",
    val subject: String = "",
    val draft: TextFieldValue = TextFieldValue(),
    val isPreviewVisible: Boolean = false,
    val preview: PostContent = PostContent(blocks = emptyList()),
    val signatureEnabled: Boolean = false,
    val smileyDisabled: Boolean = false,
    val emailNotificationEnabled: Boolean = false,
    val optionsHydratedFromForm: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitError: PrivateMessageReplyError? = null,
    val showSubmitConfirmation: Boolean = false,
    /**
     * #405 — body of a previously-cached new-conversation draft, surfaced on init when a non-empty
     * draft was found. [restorableSubject] / [restorableRecipients] carry the cached routing fields.
     * « Restaurer » pre-fills the composer ; « Ignorer » deletes the cached row. Never silently lost.
     */
    val restorableDraft: String? = null,
    val restorableSubject: String? = null,
    val restorableRecipients: String? = null,
) {
    /** All three user-typed fields are required — HFR's « remplir tous les champs » rule. */
    val canSubmit: Boolean
        get() = formAvailable && !isLoadingForm && !isSubmitting &&
            recipients.isNotBlank() && subject.isNotBlank() && draft.text.isNotBlank()

    companion object {
        /** HFR's `maxlength=70` on the composer's `sujet` input (fixture `mp_compose_form.html`). */
        const val SUBJECT_MAX_LENGTH = 70
    }
}
