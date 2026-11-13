package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftOffer
import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.BbcodeValidation
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import fr.forumhfr.redface2.core.model.write.TopicFormSubcategoryChoice
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.UploadProgress

/**
 * MVI state of the topic-level form. [TopicFormMode.EditFirstPost] edits the
 * first post of an existing topic (Phase 2D #148) ; [TopicFormMode.New] creates
 * a brand-new topic (Phase 2E #149).
 *
 * Reuses [EditorError] from the shared `core:ui` catalogue for the error envelope —
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
    /** Descending projection of the live ViewModel buffer; never an editable source. */
    val bodyText: String = "",
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
     * `selectedSubcat > 0`.
     * Defaults to `true` so the historical contract holds.
     */
    val hasSubcategorySelect: Boolean = true,
    /**
     * ED-100: the strict first-post parser refused a missing selector. Only the chrome
     * uses this refusal; [hasSubcategorySelect] keeps mirroring the last loaded form.
     * Like that mirror, it is not saved in the ED-81 checkpoint and is rebuilt by GET.
     * A successful form load clears it; other fetch failures keep the refusal.
     */
    val firstPostSubcategoryRefused: Boolean = false,
    val pollPresent: Boolean = false,
    val pollEditable: Boolean = false,
    val signatureEnabled: Boolean = false,
    val smileyDisabled: Boolean = false,
    val emailNotificationEnabled: Boolean = false,
    /** Selected HFR `MsgIcon` radio value. The topic form exposes the current 1..16 choices. */
    val msgIcon: Int = DEFAULT_MSG_ICON,
    val isInitializing: Boolean = false,
    /** Lock the title only while a recreated session waits for its authoritative draft read. */
    val isRestoringSession: Boolean = false,
    /** Successful GET in this instance; restored content alone never proves a usable form. */
    val hasLoadedForm: Boolean = false,
    val isLoadingForm: Boolean = false,
    val isSubmitting: Boolean = false,
    /** ED-161/169: terminal success and in-flight reservation are state, never a replayed effect. */
    val submission: EditorSubmissionState = EditorSubmissionState.Ready,
    val submitError: EditorError? = null,
    /**
     * True once we have hydrated `subject` from the parsed form. Kept independent
     * from [draftHydratedFromServer] so a slow fetch that lands while the user
     * was already editing the subject still hydrates the draft (and vice versa),
     * but never clobbers the field the user had touched.
     */
    val subjectHydratedFromServer: Boolean = false,
    /** Exact untouched subject received from HFR; null until a non-blank hydration lands. */
    val subjectHydratedContent: String? = null,
    /** True once the BBCode body is known, including a blank server body or an explicit restore. */
    val draftHydratedFromServer: Boolean = false,
    /** Exact server seed, set only when body hydration is applied; empty for a new topic. */
    val draftHydratedContent: String = "",
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
    /** ED-164: UI projection of submission.Armed; the token carries identity and deadline. */
    val showSubmitConfirmation: Boolean = false,
    /**
     * #405 — the body of a previously-cached draft for this form's key, surfaced on init when a
     * non-empty draft was found. Mirrors [PostEditorState.restorableDraft]. [restorableSubject]
     * carries the cached subject (topic-level forms have one) ; both fill the editor on
     * « Restaurer » and are dropped on « Ignorer ».
     */
    val restorableDraft: String? = null,
    val draftOffers: List<DraftOffer> = emptyList(),
    val restorableSubject: String? = null,
    /** ED-10/85: a close could not save the fields; the dialog asks before anything is abandoned. */
    val showUnsavedCloseDialog: Boolean = false,
    /**
     * #459 — `true` while an image upload (single or batch) is in flight. Drives the toolbar
     * spinner AND locks the body field (`readOnly`) so the caret cannot move between two
     * programmatic `[img]` insertions. Mirrors [PostEditorState.isUploading].
     */
    val isUploading: Boolean = false,
    /** #459 — typed upload failure surfaced as a dismissible banner ; mirrors [PostEditorState.uploadError]. */
    val uploadError: EditorError? = null,
    /** #459 — « n/N » batch counter (null for a single image) ; mirrors [PostEditorState.uploadProgress]. */
    val uploadProgress: UploadProgress? = null,
    /** #1128 — observed continuously so the next image pick uses the current preference. */
    val imagePickerMode: ImagePickerMode = ImagePickerMode.DEFAULT,
    val editorShortcut: EditorShortcut = EditorShortcut.NONE,
) {
    /** A failed initial fetch leaves the first post unknown until reload or explicit restore. */
    val isAwaitingInitialBody: Boolean
        get() = mode == TopicFormMode.EditFirstPost && !draftHydratedFromServer

    /** ED-35: incomplete headers and anonymous forms remain activatable for a local refusal. */
    val sendActivatable: Boolean
        get() {
            val formReady = hasLoadedForm && !isLoadingForm && !isInitializing
            val bodyReady = !isAwaitingInitialBody && bodyText.isNotBlank()
            val idle = !isSubmitting && !submission.blocksBodyMutation && !isUploading
            val supported = mode == TopicFormMode.New || !firstPostSubcategoryRefused
            val available = formReady && bodyReady && idle
            return available && supported && hasSubmitContext
        }

    /** Same route requirements as the context builders; no enabled send can silently lack its route. */
    private val hasSubmitContext: Boolean
        get() = when (mode) {
            TopicFormMode.New -> (cat ?: 0) > 0
            TopicFormMode.EditFirstPost -> {
                val topicKnown = cat != null && (subcat ?: 0) > 0 && (topicId ?: 0) > 0
                topicKnown && (numreponse ?: 0) > 0 && page == 1
            }
        }

    /**
     * Submit is allowed when the mode-specific routing context is complete,
     * the user has typed a non-blank subject AND content, the form was
     * successfully loaded, the session is not anonymous, we are not already
     * submitting, and no image upload is in flight ([isUploading], #459/#953 F5 —
     * same guard as [PostEditorState.canSubmit] : a tap on « Envoyer » must not
     * race the upload and POST before the `[img]` markup is inserted).
     * EditFirstPost also requires a known initial body, including an explicit draft restore.
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
    val armToken: ArmToken? get() = (submission as? EditorSubmissionState.Armed)?.token

    val canSubmit: Boolean
        get() = hasLoadedForm && !isInitializing && when (mode) {
            TopicFormMode.EditFirstPost ->
                hasSubcategorySelect &&
                    cat != null &&
                    (subcat != null && subcat > 0) &&
                    topicId != null &&
                    numreponse != null &&
                    page != null &&
                    (selectedSubcat != null && selectedSubcat > 0) &&
                    subject.text.isNotBlank() &&
                    bodyText.isNotBlank() &&
                    draftHydratedFromServer &&
                    !isLoadingForm &&
                    !isSubmitting &&
                    !submission.blocksBodyMutation &&
                    !isUploading &&
                    !isAnonymous
            TopicFormMode.New ->
                cat != null &&
                    isSubcatChoiceComplete &&
                    subject.text.isNotBlank() &&
                    bodyText.isNotBlank() &&
                    !isLoadingForm &&
                    !isSubmitting &&
                    !submission.blocksBodyMutation &&
                    !isUploading &&
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
    data object Started : TopicFormIntent
    data object Stopped : TopicFormIntent
    data class SubjectChanged(val value: TextFieldValue) : TopicFormIntent
    data class ToolbarActionClicked(val action: BbcodeAction) : TopicFormIntent
    data object TogglePreview : TopicFormIntent
    data class SubmitClicked(val timeoutMillis: Long = 4_000L) : TopicFormIntent

    /**
     * ED-164/166: confirms only this exact arm, after 400 ms and before its monotonic deadline.
     */
    data class SubmitConfirmed(val token: ArmToken?) : TopicFormIntent

    /** #312 — user dismissed the confirmation dialog. No submission happens; the draft stays. */
    data object SubmitConfirmationDismissed : TopicFormIntent

    data object ErrorDismissed : TopicFormIntent

    /** Explicit Masquer of a failed attempt write; releases deferred draft information without storage. */
    data object DraftWriteFailureDismissed : TopicFormIntent

    data object ReloadRequested : TopicFormIntent
    data class SubcatSelected(val id: Int) : TopicFormIntent
    data class ToggleSignature(val enabled: Boolean) : TopicFormIntent
    data class ToggleSmileyDisabled(val disabled: Boolean) : TopicFormIntent
    data class ToggleEmailNotification(val enabled: Boolean) : TopicFormIntent
    data class MsgIconSelected(val n: Int) : TopicFormIntent
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

    /** #988 — launch/result boundary emitted by the shared picker before upload filtering. */
    data class ImagePickerEventReceived(val event: ImagePickerEvent) : TopicFormIntent

    /** #459 — dismiss the upload-error banner. Mirrors [PostEditorIntent.UploadErrorDismissed]. */
    data object UploadErrorDismissed : TopicFormIntent

    /** #405 — restore the editor from the cached draft (subject + body). */
    data object DraftRestoreRequested : TopicFormIntent

    /** #405 — discard the cached draft : delete the row and clear the banner. */
    data object DraftDiscardRequested : TopicFormIntent

    /** ED-40/121 — « Restaurer » on the offered version chosen in the sheet's list, by its identity. */
    data class DraftOfferRestored(val id: String) : TopicFormIntent

    /** ED-121 — « Ignorer » removes only the chosen offered version ; the fields are untouched. */
    data class DraftOfferDiscarded(val id: String) : TopicFormIntent

    /**
     * #803 pattern (state-hygiene audit 2026-07-05) — the user is leaving the form (system
     * back). The ViewModel flushes the pending debounced autosave FIRST, then emits
     * [TopicFormEffect.CloseCommitted] — closing through the ViewModel is what guarantees the
     * last < 750 ms of typing reach the #405 row (a plain pop would cancel the debounce with
     * the ViewModel). Mirrors [PostEditorIntent.CloseRequested].
     */
    data object CloseRequested : TopicFormIntent

    /** "Rester": the unsaved-close dialog is dismissed, the form stays open with its fields. */
    data object UnsavedCloseDismissed : TopicFormIntent

    /** "Quitter sans enregistrer": the explicit abandonment of what could not be saved. */
    data object UnsavedCloseConfirmed : TopicFormIntent
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
        val operation: PostSubmitOperation = PostSubmitOperation.Edit,
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
