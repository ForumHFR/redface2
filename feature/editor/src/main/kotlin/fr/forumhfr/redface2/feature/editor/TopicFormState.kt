package fr.forumhfr.redface2.feature.editor
import fr.forumhfr.redface2.core.ui.editor.UploadError
import fr.forumhfr.redface2.core.ui.editor.UploadProgress

import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.domain.editor.BbcodeValidation
import fr.forumhfr.redface2.core.domain.editor.validateBbcodeDraft
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.write.TopicFormSubcategoryChoice
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction

/**
 * MVI state of the topic-level form. [TopicFormMode.EditFirstPost] edits the
 * first post of an existing topic (Phase 2D #148) ; [TopicFormMode.New] creates
 * a brand-new topic (Phase 2E #149).
 *
 * Reuses [SubmitError] from `PostEditorState.kt` for the error envelope —
 * the failure variants HFR can return are identical for post-level and
 * topic-level edits (same `bdd.php` endpoint, same classifier).
 */
data class TopicFormState(
    val mode: TopicFormMode,
    val cat: Int?,
    val subcat: Int?,
    val topicId: Int?,
    val page: Int?,
    val numreponse: Int?,
    val subject: TextFieldValue = TextFieldValue(),
    val draft: TextFieldValue = TextFieldValue(),
    val preview: PostContent = PostContent(blocks = emptyList()),
    val isPreviewVisible: Boolean = false,
    val validation: BbcodeValidation = BbcodeValidation.Idle,
    val selectedSubcat: Int? = null,
    val subcategoryChoices: List<TopicFormSubcategoryChoice> = emptyList(),
    /**
     * #213 — mirror of [TopicForm.hasSubcategorySelect]. `true` when the parsed
     * form carried a `<select name=subcat>` (the category HAS sub-categories),
     * `false` when it did not (a category WITHOUT sub-category, e.g. cat IA,
     * which posts with `subcat=0`). Drives the [canSubmit] branch for the New
     * mode : a sub-category-less cat is submittable with `subcat=0` and no
     * explicit pick, while a cat WITH sub-categories still requires
     * `selectedSubcat > 0`. Defaults to `true` so the historical contract holds.
     */
    val hasSubcategorySelect: Boolean = true,
    val pollPresent: Boolean = false,
    val pollEditable: Boolean = false,
    val signatureEnabled: Boolean = false,
    val smileyDisabled: Boolean = false,
    val emailNotificationEnabled: Boolean = false,
    val isLoadingForm: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitError: SubmitError? = null,
    /**
     * True once we have hydrated `subject` from the parsed form. Kept independent
     * from [draftHydratedFromServer] so a slow fetch that lands while the user
     * was already editing the subject still hydrates the draft (and vice versa),
     * but never clobbers the field the user had touched.
     */
    val subjectHydratedFromServer: Boolean = false,
    /** Mirror of [subjectHydratedFromServer] for the BBCode draft. */
    val draftHydratedFromServer: Boolean = false,
    /** Mirror of [PostEditorState.optionsHydratedFromForm] for the same anti-clobber reason. */
    val optionsHydratedFromForm: Boolean = false,
    /**
     * `true` when the parsed form looked anonymous (`<input name=pseudo>` empty
     * + `<input name=password>` visible). Phase 2E refuses to submit in that
     * case — the auth state is the cookie jar, but HFR will reject an anonymous
     * POST anyway and #154 explicitly forbids exposing the legacy anonymous flow.
     */
    val isAnonymous: Boolean = false,
    // #441 — the smiley picker state no longer lives here : visibility + wiki search moved
    // to the shared `SmileyPickerController` exposed as `TopicFormViewModel.smileyPicker`.
    /**
     * HFR user id parsed from the form HTML (cf. [TopicForm.userId]). Used by the wiki
     * smiley search call (read by the `SmileyPickerController` lambda). `null` when the
     * form is anonymous or unparseable — the controller falls back to `user_id=0`. Same
     * anti-clobber rule as the other hydrated fields : a silent `InvalidHashCheck` refetch
     * must never erase a previously known id.
     */
    val userId: Int? = null,
    /**
     * #312 — `true` while the « Confirmation avant publication » dialog is shown. Mirrors
     * [PostEditorState.showSubmitConfirmation]: only raised when the preference is on AND
     * [canSubmit] already validated the form (we never confirm an unsendable form).
     */
    val showSubmitConfirmation: Boolean = false,
    /**
     * #405 — the body of a previously-cached draft for this form's key, surfaced on init when a
     * non-empty draft was found. Mirrors [PostEditorState.restorableDraft]. [restorableSubject]
     * carries the cached subject (topic-level forms have one) ; both fill the editor on
     * « Restaurer » and are dropped on « Ignorer ».
     */
    val restorableDraft: String? = null,
    val restorableSubject: String? = null,
    /**
     * #459 — `true` while an image upload (single or batch) is in flight. Drives the toolbar
     * spinner AND locks the body field (`readOnly`) so the caret cannot move between two
     * programmatic `[img]` insertions. Mirrors [PostEditorState.isUploading].
     */
    val isUploading: Boolean = false,
    /** #459 — typed upload failure surfaced as a dismissible banner ; mirrors [PostEditorState.uploadError]. */
    val uploadError: UploadError? = null,
    /** #459 — « n/N » batch counter (null for a single image) ; mirrors [PostEditorState.uploadProgress]. */
    val uploadProgress: UploadProgress? = null,
) {
    /**
     * Submit is allowed when the mode-specific routing context is complete,
     * the user has typed a non-blank subject AND content, the form was
     * successfully loaded, the session is not anonymous, and we are not
     * already submitting.
     *
     * #213 — the New (create-topic) branch now supports a category WITHOUT a
     * sub-category (e.g. cat IA, cat=32) : when the parsed form carried no
     * `<select name=subcat>` ([hasSubcategorySelect] = false), HFR posts with
     * `subcat=0`, so submit is allowed without an explicit `selectedSubcat`.
     * A category WITH sub-categories still requires `selectedSubcat > 0` so the
     * user cannot post into « no sub-category » by accident.
     *
     * EditFirstPost stays strict (`subcat > 0` / `selectedSubcat > 0`) because the
     * FP form contract in a cat WITHOUT sub-category is **not captured** (only the
     * REPLY and CREATE IA forms are), and the FP parser fail-fasts on a missing
     * select — relaxing it here would contradict that invariant. Treat cat-0-subcat
     * for EditFirstPost as a separate follow-up under #213, once that form is captured.
     */
    val canSubmit: Boolean
        get() = when (mode) {
            TopicFormMode.EditFirstPost ->
                cat != null &&
                    (subcat != null && subcat > 0) &&
                    topicId != null &&
                    numreponse != null &&
                    page != null &&
                    (selectedSubcat != null && selectedSubcat > 0) &&
                    subject.text.isNotBlank() &&
                    draft.text.isNotBlank() &&
                    !isLoadingForm &&
                    !isSubmitting &&
                    !isAnonymous
            TopicFormMode.New ->
                cat != null &&
                    isSubcatChoiceComplete &&
                    subject.text.isNotBlank() &&
                    draft.text.isNotBlank() &&
                    !isLoadingForm &&
                    !isSubmitting &&
                    !isAnonymous
        }

    /**
     * #213 — true when the sub-category routing is complete enough to POST a new
     * topic : either the cat has no sub-category at all (`hasSubcategorySelect`
     * false → `subcat=0` is the wire value), or the user has picked a real
     * sub-category (`selectedSubcat > 0`).
     */
    private val isSubcatChoiceComplete: Boolean
        get() = !hasSubcategorySelect || (selectedSubcat != null && selectedSubcat > 0)
}

/**
 * Intents emitted by [TopicFormScreen]. Mirrors `PostEditorIntent` but for the
 * topic-level surface — `SubjectChanged` and `SubcatSelected` are the only
 * intents the post-level editor does not need.
 */
sealed interface TopicFormIntent {
    data class SubjectChanged(val value: TextFieldValue) : TopicFormIntent
    data class ContentChanged(val value: TextFieldValue) : TopicFormIntent
    data class ToolbarActionClicked(val action: BbcodeAction) : TopicFormIntent
    data object TogglePreview : TopicFormIntent
    data object SubmitClicked : TopicFormIntent

    /**
     * #312 — user confirmed the « Confirmation avant publication » dialog. Executes the real
     * submission directly, BYPASSING the preference re-check (otherwise the dialog would loop).
     */
    data object SubmitConfirmed : TopicFormIntent

    /** #312 — user dismissed the confirmation dialog. No submission happens; the draft stays. */
    data object SubmitConfirmationDismissed : TopicFormIntent

    data object ErrorDismissed : TopicFormIntent
    data class SubcatSelected(val id: Int) : TopicFormIntent
    data class ToggleSignature(val enabled: Boolean) : TopicFormIntent
    data class ToggleSmileyDisabled(val disabled: Boolean) : TopicFormIntent
    data class ToggleEmailNotification(val enabled: Boolean) : TopicFormIntent
    /**
     * The user tapped a smiley in the picker : insert the token at the caret. #441 — open /
     * dismiss / query-change are no longer intents (the sheet talks directly to the shared
     * `SmileyPickerController` exposed as `TopicFormViewModel.smileyPicker`) ; only the
     * insertion stays MVI because it mutates the draft.
     */
    data class SmileySelected(val token: String) : TopicFormIntent

    /** Phase 2F-E (#189) — insert `[img]url[/img]` for a validated remote image URL. */
    data class ImageUrlInserted(val url: String) : TopicFormIntent

    /**
     * #459 — images picked by the photo picker, as Uri strings in pick order. Read + uploaded
     * sequentially, one `[img]` inserted per success. Mirrors [PostEditorIntent.ImagesPicked].
     */
    data class ImagesPicked(val uris: List<String>) : TopicFormIntent

    /** #459 — dismiss the upload-error banner. Mirrors [PostEditorIntent.UploadErrorDismissed]. */
    data object UploadErrorDismissed : TopicFormIntent

    /** #405 — restore the editor from the cached draft (subject + body). */
    data object DraftRestoreRequested : TopicFormIntent

    /** #405 — discard the cached draft : delete the row and clear the banner. */
    data object DraftDiscardRequested : TopicFormIntent

    /**
     * #803 pattern (state-hygiene audit 2026-07-05) — the user is leaving the form (system
     * back). The ViewModel flushes the pending debounced autosave FIRST, then emits
     * [TopicFormEffect.CloseCommitted] — closing through the ViewModel is what guarantees the
     * last < 750 ms of typing reach the #405 row (a plain pop would cancel the debounce with
     * the ViewModel). Mirrors [PostEditorIntent.CloseRequested].
     */
    data object CloseRequested : TopicFormIntent
}

/**
 * One-shot effects from [TopicFormViewModel]. Same shape as
 * `PostEditorEffect.SubmitSucceeded(targetPage, scrollTo)` so the navigation
 * host can reuse the existing topic-refresh logic.
 */
sealed interface TopicFormEffect {
    data class SubmitSucceeded(
        val targetPage: Int?,
        val scrollTo: Int? = null,
    ) : TopicFormEffect

    /**
     * Phase 2E (#149) — emitted on a successful create-topic POST.
     *
     * Live capture (#214, fixture `write_create_topic_success_response.html`) confirmed
     * HFR redirects to the **category listing** after a create and never returns the
     * created topic id : `newTopicId` / `newNumreponse` are therefore *always* null on
     * the create path (the non-null branch is kept only for the theoretical case HFR
     * starts anchoring `sujet_{id}_{page}#t{N}` like reply/quote does).
     *
     * Since direct navigation is impossible, [subject] carries the **exact posted title**
     * so the navigation host can ask the category listing it lands on to highlight the
     * freshly-created row by exact-title match (the #206 workaround, « Exact post-création »).
     */
    data class NewTopicCreated(
        val cat: Int,
        val subcat: Int,
        val newTopicId: Int?,
        val newNumreponse: Int?,
        /** Exact subject the user posted ; used to highlight the new row in the listing (#206). */
        val subject: String,
    ) : TopicFormEffect

    /**
     * #803 pattern — the draft is persisted, the form may now actually pop (the save is AWAITED
     * before the effect, so navigation can never cancel it). Mirrors
     * `PostEditorEffect.CloseCommitted`.
     */
    data object CloseCommitted : TopicFormEffect
}

internal fun TopicFormState.withDraft(updated: TextFieldValue): TopicFormState =
    copy(
        draft = updated,
        validation = validateBbcodeDraft(updated.text),
        submitError = if (updated.text != draft.text) null else submitError,
    )
