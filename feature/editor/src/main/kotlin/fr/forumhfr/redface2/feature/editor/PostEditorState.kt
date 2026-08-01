package fr.forumhfr.redface2.feature.editor
import fr.forumhfr.redface2.core.ui.editor.UploadError
import fr.forumhfr.redface2.core.ui.editor.UploadProgress

import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.domain.editor.BbcodeValidation
import fr.forumhfr.redface2.core.domain.editor.validateBbcodeDraft
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.write.QuotedPostPreview
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason

/**
 * MVI state of the post-level editor. Local draft + parsed preview AST + the
 * write submit lifecycle. Reply (#145) and quote (#146) reach HFR through
 * `ReplyRepository` (POST `bddpost.php`) ; edit (#147) goes through
 * `EditPostRepository` (POST `bdd.php`). All three share this state. Topic-level
 * writes (edit FP #148 and create topic #149) live in [TopicFormState].
 */
data class PostEditorState(
    val mode: PostEditorMode,
    val cat: Int,
    val topicId: Int?,
    val numreponse: Int?,
    /** Page index of the topic for the reply form GET (Phase 2C). Null when unknown. */
    val page: Int?,
    /** Sub-category id required by HFR's write contract. Null when unknown — reply disabled. */
    val subcat: Int?,
    /**
     * #604 lot 3 (mockup P3) — the armed quote CARDS, in citation order. Seeded from
     * [PostEditorRequest.initialQuotes], reorderable / removable / clearable from the UI
     * (#436 « Tout vider »). The field never contains their BBCode : the `[quotemsg]`
     * blocks are materialised fresh at submit, exactly like the quick-reply sheet
     * (one implementation, `ReplyQuoteMaterializer`). Always empty in [PostEditorMode.Edit].
     */
    val quotes: List<QuotedPostPreview> = emptyList(),
    val draft: TextFieldValue = TextFieldValue(),
    val preview: PostContent = PostContent(blocks = emptyList()),
    val isPreviewVisible: Boolean = false,
    val validation: BbcodeValidation = BbcodeValidation.Idle,
    /** True while we GET `message.php` (reply / quote / edit form) to grab `hash_check`. */
    val isLoadingForm: Boolean = false,
    /** True while we POST `bddpost.php` (reply / quote) or `bdd.php` (edit). Guards against double submit. */
    val isSubmitting: Boolean = false,
    /** Surfaces an HFR-classified failure to the UI. Null means "no error to show". */
    val submitError: SubmitError? = null,
    /**
     * Tracks whether we already prefilled [draft] from `ReplyForm.initialContent`.
     * Used by the ViewModel to make sure a stale form refetch (e.g. after
     * `InvalidHashCheck`) does not overwrite the user's in-progress edit.
     */
    val draftHydratedFromForm: Boolean = false,
    /**
     * Per-post options the user can flip from the editor (Phase 2C, #146 round
     * 2 follow-up). Seeded from `ReplyForm.options` on the first form load and
     * never auto-overwritten by a refetch — same anti-clobber rule as
     * [draftHydratedFromForm]. The repository reads these values when building
     * the POST body, so flipping the toggle is immediately reflected on the
     * next submit.
     */
    val signatureEnabled: Boolean = false,
    /**
     * **Inverted semantics** (matches `ReplyFormOptions.smileyDisabled`): `true`
     * = the user opted to render HFR smileys as plain text. Do not read this as
     * « smileys actifs ». UI label : « Désactiver les smilies ».
     */
    val smileyDisabled: Boolean = false,
    val emailNotificationEnabled: Boolean = false,
    /**
     * Mirror of [draftHydratedFromForm] for the options. We do not want a
     * second form fetch (`InvalidHashCheck` refetch) to silently reset the
     * three toggles the user may have flipped between the first load and the
     * submit attempt.
     */
    val optionsHydratedFromForm: Boolean = false,
    /**
     * Phase 2F-B (#11 partial) — smiley picker visibility + wiki search state. Hidden by
     * default. Opening the picker is an Intent ; closing it is also an Intent, so the
     * bottom-sheet dismiss path stays MVI-correct.
     */
    val smileyPicker: SmileyPickerState = SmileyPickerState.Hidden,
    /**
     * HFR user id parsed from the form HTML (cf. `ReplyForm.userId`). Used by the wiki
     * smiley search call. `null` when the form is anonymous or unparseable — the
     * repository falls back to `user_id=0`.
     */
    val userId: Int? = null,
    /**
     * #312 — `true` while the « Confirmation avant publication » dialog is shown. Only ever set
     * when the persisted preference is on AND the submit already passed every validation gate
     * (we never ask to confirm a form that could not be submitted). Confirm / dismiss go through
     * [PostEditorIntent.SubmitConfirmed] / [PostEditorIntent.SubmitConfirmationDismissed].
     */
    val showSubmitConfirmation: Boolean = false,
    /**
     * #405 — the body of a previously-cached draft for this editor's key, surfaced on init when a
     * non-empty draft was found. Non-null means « propose a restore » : the UI shows a banner with
     * « Restaurer » ([PostEditorIntent.DraftRestoreRequested]) and « Ignorer »
     * ([PostEditorIntent.DraftDiscardRequested]). The draft is never silently applied so a fresh
     * quote prefill is not clobbered ; it is also never silently lost — discarding deletes the row.
     */
    val restorableDraft: String? = null,
    /**
     * #459 PR2 — `true` while an image picked from the photo picker is being read + uploaded to the
     * selected host. The toolbar's image-upload affordance shows a progress indicator and is
     * disabled so a second pick cannot race the in-flight upload.
     */
    val isUploading: Boolean = false,
    /**
     * #459 PR2 — typed upload failure surfaced after a failed pick→read→upload. Null means « no
     * error to show ». Cleared on a fresh content mutation or via [PostEditorIntent.UploadErrorDismissed].
     */
    val uploadError: UploadError? = null,
    /**
     * Multi-image upload — progress of the in-flight batch, or null when no batch (or a single
     * image) is uploading. [UploadProgress.total] > 1 is what the editor uses to show an « n/N »
     * counter; a one-image upload keeps this null and only flips [isUploading].
     */
    val uploadProgress: UploadProgress? = null,
) {
    /**
     * Submission is allowed when : we know the routing context (page + subcat + topicId),
     * the user has typed something non-blank, the editor is not already submitting,
     * we are not still fetching the form, and no image upload is in flight. Phase 2D (#147)
     * additionally requires `numreponse` for [PostEditorMode.Edit] — without it we cannot
     * identify which post HFR should rewrite. The [isUploading] guard (#459) stops a tap on
     * « Envoyer » from racing an in-flight upload and posting before the image markup is inserted.
     */
    val canSubmit: Boolean
        get() = (mode == PostEditorMode.Reply || (mode == PostEditorMode.Edit && numreponse != null)) &&
            page != null &&
            // #213 — reject the `null` unknown and the `-1` SUBCAT_UNKNOWN sentinel.
            // `subcat = 0` is postable (cat without sub-category, e.g. IA) — see
            // `Topic.subcat` / `Topic.canReply`.
            (subcat != null && subcat >= 0) &&
            topicId != null &&
            // #604 lot 3 — a quotes-only reply is sendable (same rule as the quick-reply
            // sheet : the materialised [quotemsg] blocks ARE the content). Edit keeps
            // requiring a non-blank body — its cards list is always empty.
            (draft.text.isNotBlank() || (mode == PostEditorMode.Reply && quotes.isNotEmpty())) &&
            !isSubmitting &&
            !isLoadingForm &&
            !isUploading

    val isSubmitEnabled: Boolean get() = canSubmit
}

/**
 * UI-facing error envelope. The repository's [ReplyFailureReason] is the canonical
 * type — we wrap it here so transport-level failures (IO, session) can land alongside
 * the HFR-classified reasons without leaking exception types into the View.
 */
sealed interface SubmitError {
    /** A classified failure surfaced verbatim by HFR (see fixtures). */
    data class Hfr(val reason: ReplyFailureReason) : SubmitError

    /** Network / IO error. The draft is preserved ; the user can retry. */
    data object Network : SubmitError

    /** Auth cookie was rejected mid-flow. The UI prompts a fresh login. */
    data object SessionExpired : SubmitError

    /**
     * The active topic page does not carry a `subcat` yet (cache pre-dates Phase 2C).
     * The UI tells the user to refresh the topic first.
     */
    data object MissingSubcat : SubmitError
}

// #459 — UploadError / UploadProgress were born here and are now promoted to
// `:core:ui` (core.ui.editor.EditorUpload) so the MP composers share the same
// upload vocabulary as the topic-side editors.

internal fun PostEditorState.withDraft(updated: TextFieldValue): PostEditorState =
    copy(
        draft = updated,
        validation = validateBbcodeDraft(updated.text),
        // Clear an error as soon as the user mutates the draft — they have implicitly
        // accepted that we will try again. Keep it on toolbar-only mutations though
        // (caller resets `submitError` directly when needed).
        submitError = if (updated.text != draft.text) null else submitError,
        // #459 PR2 — same rule for the image-upload error : a fresh text edit dismisses the stale
        // banner. A successful upload INSERTS text via this path, which also clears any prior error.
        uploadError = if (updated.text != draft.text) null else uploadError,
    )
