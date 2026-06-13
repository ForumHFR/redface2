package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.domain.editor.BbcodeValidation
import fr.forumhfr.redface2.core.domain.editor.validateBbcodeDraft
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.PostContent
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
     * `numreponse` of the post being quoted (Phase 2C, #146). When non-null the
     * editor opened in quote mode : HFR prefills `[quotemsg=…]` and we hydrate
     * the draft with it on form load. Same surface as a simple reply otherwise.
     */
    val quotedNumreponse: Int? = null,
    /**
     * `ref` parameter HFR included in the quote link when parseable — opaque,
     * forwarded as-is. May be null on a quote: HFR still quotes from
     * [quotedNumreponse] alone when the toolbar link was obfuscated.
     */
    val quoteRef: Int? = null,
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
     * and we are not still fetching the form. Phase 2D (#147) additionally requires
     * `numreponse` for [PostEditorMode.Edit] — without it we cannot identify which
     * post HFR should rewrite.
     */
    val canSubmit: Boolean
        get() = (mode == PostEditorMode.Reply || (mode == PostEditorMode.Edit && numreponse != null)) &&
            page != null &&
            // #213 — reject the `null` unknown and the `-1` SUBCAT_UNKNOWN sentinel.
            // `subcat = 0` is postable (cat without sub-category, e.g. IA) — see
            // `Topic.subcat` / `Topic.canReply`.
            (subcat != null && subcat >= 0) &&
            topicId != null &&
            draft.text.isNotBlank() &&
            !isSubmitting &&
            !isLoadingForm

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

/**
 * #459 PR2 / #474 — UI-facing image-upload failure. Maps the `:core:domain`
 * [fr.forumhfr.redface2.core.domain.upload.UploadException] variants onto an actionable message
 * (too large / unsupported type / host HTTP error / unreadable host response / network) instead of
 * a generic « erreur de l'hébergeur ». The HTTP [Server] case carries the status [code] and the
 * [providerId] so the banner can name the host and the exact code (#474); [Malformed] stays distinct
 * so an unreadable-but-2xx body reads differently from a flat HTTP refusal. Anonymous clients never
 * get here — the ViewModel ignores a pick without a userId.
 */
sealed interface UploadError {
    /** The picked image exceeds the host's accepted size. */
    data object TooLarge : UploadError

    /** The host rejected the MIME type. */
    data object UnsupportedType : UploadError

    /** The host answered a non-2xx HTTP status. [code] is the status, [providerId] the host (#474). */
    data class Server(val code: Int, val providerId: UploadProviderId) : UploadError

    /** The host answered 2xx but the body could not be parsed into the expected shape (#474). */
    data class Malformed(val providerId: UploadProviderId) : UploadError

    /** No network / DNS / timeout — also covers an unreadable picked Uri (mapped to Network). */
    data object Network : UploadError
}

/**
 * Progress of a multi-image upload batch: [completed] images uploaded and inserted out of [total]
 * picked. Surfaced as an « n/N » counter while [PostEditorState.isUploading] is true.
 */
data class UploadProgress(val completed: Int, val total: Int)



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
