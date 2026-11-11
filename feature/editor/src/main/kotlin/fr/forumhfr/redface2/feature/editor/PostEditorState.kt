package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.BbcodeValidation
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.UploadProgress

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
    /** ED-81: no field composition until the owner-bound read and rendering choice are complete. */
    val isInitializing: Boolean = false,
    /**
     * #604 lot 3 (mockup P3) — the armed quote CARDS, in citation order. Seeded from
     * [PostEditorRequest.initialQuotes], reorderable / removable / clearable from the UI
     * (#436 « Tout vider »). The field never contains their BBCode : the `[quotemsg]`
     * blocks are materialised fresh at submit, exactly like the quick-reply sheet
     * (one implementation, `TopicReplyQuoteMaterializer`). Always empty in [PostEditorMode.Edit].
     */
    val quotes: List<QuoteSelection> = emptyList(),
    /** ED-132: acknowledgement never abandons a requested inline quote. */
    val pendingQuotes: List<QuoteSelection> = emptyList(),
    /** Saturates at two; restored with the pending request after process death. */
    val quoteFetchFailures: Int = 0,
    /** ED-132: v20 Bundle coordinates were dropped, without importing them into Room. */
    val legacyQuoteRequestDropped: Boolean = false,
    /** Invalid/unreadable durable content cannot authorize submission. */
    val quoteStorageUnavailable: Boolean = false,
    val unreadableQuoteRequest: Boolean = false,
    /** Display-only mirror of the ViewModel-owned body field. */
    val bodyText: String = "",
    val preview: PostContent = PostContent(blocks = emptyList()),
    val isPreviewVisible: Boolean = false,
    val validation: BbcodeValidation = BbcodeValidation.Idle,
    /** True while we GET `message.php` (reply / quote / edit form) to grab `hash_check`. */
    val isLoadingForm: Boolean = false,
    /** Read identity is independent of a pending request: ordinary metadata may load under its offer. */
    val isFetchingQuote: Boolean = false,
    /** Availability is evidence from the private loadedForm, never inferred from a finished GET. */
    val hasLoadedForm: Boolean = false,
    val hasAuthenticatedForm: Boolean = false,
    /** ED-10/Q4 provisional source: loaded form only, empty when HFR omits its subject. */
    val topicTitle: String = "",
    /** True while we POST `bddpost.php` (reply / quote) or `bdd.php` (edit). Guards against double submit. */
    val isSubmitting: Boolean = false,
    /** ED-161/169: terminal success and in-flight reservation are state, never a replayed effect. */
    val submission: EditorSubmissionState = EditorSubmissionState.Ready,
    /** Surfaces an HFR-classified failure to the UI. Null means "no error to show". */
    val submitError: EditorError? = null,
    /**
     * Tracks whether we already prefilled [bodyText] from `ReplyForm.initialContent`.
     * Used by the ViewModel to make sure a stale form refetch (e.g. after
     * `InvalidHashCheck`) does not overwrite the user's in-progress edit. A known
     * empty server body or a non-blank restored draft also sets this flag.
     */
    val draftHydratedFromForm: Boolean = false,
    /** Exact untouched server prefill, used to distinguish hydration from a user edit (#1415). */
    val draftHydratedContent: String? = null,
    /** ED-89: in-memory server body actually hydrated in Edit; null until known, empty in Reply. */
    val draftRestoreSeed: String? = if (mode == PostEditorMode.Edit) null else "",
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
    /** Selected HFR `MsgIcon` radio value. The editor exposes only the current 1..16 choices. */
    val msgIcon: Int = DEFAULT_MSG_ICON,
    /**
     * Mirror of [draftHydratedFromForm] for the options. We do not want a
     * second form fetch (`InvalidHashCheck` refetch) to silently reset the
     * three toggles or message tone the user may have changed between the
     * first load and the submit attempt.
     */
    val optionsHydratedFromForm: Boolean = false,
    // #441 — the smiley picker state no longer lives here : visibility + wiki search moved
    // to the shared `SmileyPickerController` exposed as `PostEditorViewModel.smileyPicker`.
    /**
     * HFR user id parsed from the form HTML (cf. `ReplyForm.userId`). Used by the wiki
     * smiley search call (read by the `SmileyPickerController` lambda). `null` when the
     * form is anonymous or unparseable — the controller falls back to `user_id=0`.
     */
    val userId: Int? = null,
    /** ED-164: UI projection of submission.Armed; the token carries identity and deadline. */
    val showSubmitConfirmation: Boolean = false,
    /**
     * #405 — the body of a previously-cached draft for this editor's key, surfaced on init when a
     * non-empty draft was found. Non-null means « propose a restore » : the UI shows a banner with
     * « Restaurer » ([PostEditorIntent.DraftRestoreRequested]) and « Ignorer »
     * ([PostEditorIntent.DraftDiscardRequested]). A matching interrupted session resumes silently;
     * an unrelated revision remains an explicit offer.
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
     * error to show ». Cleared only via [PostEditorIntent.UploadErrorDismissed].
     */
    val uploadError: EditorError? = null,
    /**
     * Multi-image upload — progress of the in-flight batch, or null when no batch (or a single
     * image) is uploading. [UploadProgress.total] > 1 is what the editor uses to show an « n/N »
     * counter; a one-image upload keeps this null and only flips [isUploading].
     */
    val uploadProgress: UploadProgress? = null,
    /** #1128 — observed continuously so the next image pick uses the current preference. */
    val imagePickerMode: ImagePickerMode = ImagePickerMode.DEFAULT,
    val editorShortcut: EditorShortcut = EditorShortcut.NONE,
) {
    /** A failed initial edit fetch keeps the body locked until reload or a non-blank restore. */
    val isAwaitingInitialBody: Boolean
        get() = mode == PostEditorMode.Edit && !draftHydratedFromForm

    /**
     * Submission is allowed when : we know the routing context (page + subcat + topicId),
     * the user has typed something non-blank, the editor is not already submitting,
     * we are not still fetching the form, and no image upload is in flight. Phase 2D (#147)
     * additionally requires `numreponse` for [PostEditorMode.Edit] — without it we cannot
     * identify which post HFR should rewrite. The [isUploading] guard (#459) stops a tap on
     * « Envoyer » from racing an in-flight upload and posting before the image markup is inserted.
     */
    val armToken: ArmToken? get() = (submission as? EditorSubmissionState.Armed)?.token

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
            (bodyText.isNotBlank() || (mode == PostEditorMode.Reply && quotes.isNotEmpty())) &&
            !isAwaitingInitialBody &&
            !isInitializing && !isSubmitting &&
            !submission.blocksBodyMutation &&
            !isLoadingForm && !quoteStorageUnavailable && pendingQuotes.isEmpty() &&
            !isUploading

    /** ED-132: a readable inline request waits for this offer's explicit decision. */
    val isQuoteAwaitingDraftDecision: Boolean
        get() = mode == PostEditorMode.Reply && restorableDraft != null && pendingQuotes.isNotEmpty() &&
            !hasDormantQuote && !unreadableQuoteRequest && !quoteStorageUnavailable

    val hasDormantQuote: Boolean
        get() = pendingQuotes.isNotEmpty() && submission is EditorSubmissionState.Uncertain

    val canRestoreDraft: Boolean
        get() = !isInitializing && draftRestoreSeed != null && (mode != PostEditorMode.Edit || !isLoadingForm)

    @Suppress("ComplexCondition") // Explicit recovery, or an eligible readable request, plus busy-state guards.
    val canIgnorePendingQuote: Boolean
        get() = (unreadableQuoteRequest ||
            pendingQuotes.isNotEmpty() && (quoteFetchFailures >= 2 || hasDormantQuote)) &&
            !isLoadingForm &&
            !submission.blocksBodyMutation

    val isSubmitEnabled: Boolean get() = canSubmit
}
