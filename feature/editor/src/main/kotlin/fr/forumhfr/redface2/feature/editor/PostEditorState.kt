package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.domain.editor.BbcodeValidation
import fr.forumhfr.redface2.core.domain.editor.validateBbcodeDraft
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason

/**
 * MVI state of the post-level editor. Local draft + parsed preview AST + the Phase
 * 2C submit lifecycle — reply (#145) and quote (#146) both reach HFR through
 * the same `ReplyRepository` and share this state. Edit / Edit FP / Create
 * topic come later (#147 / #148 / #149).
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
    /** `ref` parameter HFR included in the quote link — opaque, forwarded as-is. */
    val quoteRef: Int? = null,
    val draft: TextFieldValue = TextFieldValue(),
    val preview: PostContent = PostContent(blocks = emptyList()),
    val isPreviewVisible: Boolean = false,
    val validation: BbcodeValidation = BbcodeValidation.Idle,
    /** True while we GET the reply form to grab `hash_check`. */
    val isLoadingForm: Boolean = false,
    /** True while we POST `bddpost.php`. Guards against double submit. */
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
) {
    /**
     * Reply submission is allowed when : we know the routing context (page + subcat),
     * the user has typed something non-blank, the editor is not already submitting,
     * and we are not still fetching the form.
     */
    val canSubmit: Boolean
        get() = mode == PostEditorMode.Reply &&
            page != null &&
            // Reject the `null` unknown, the `-1` SUBCAT_UNKNOWN sentinel and the `0`
            // moderator-space wire shape (`Topic.hasSubcat` uses the same rule).
            (subcat != null && subcat > 0) &&
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

internal fun PostEditorState.withDraft(updated: TextFieldValue): PostEditorState =
    copy(
        draft = updated,
        validation = validateBbcodeDraft(updated.text),
        // Clear an error as soon as the user mutates the draft — they have implicitly
        // accepted that we will try again. Keep it on toolbar-only mutations though
        // (caller resets `submitError` directly when needed).
        submitError = if (updated.text != draft.text) null else submitError,
    )
