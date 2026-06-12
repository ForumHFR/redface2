package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.ui.editor.WikiSearchState
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.domain.write.EditPostRepository
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.applyBbcodeAction
import fr.forumhfr.redface2.core.ui.editor.imageBbcodeTokenOrNull
import fr.forumhfr.redface2.core.ui.editor.insertBbcodeToken
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel backing the post-level editor. Owns the BBCode draft, the parsed
 * preview AST, the preview-visibility toggle, and the Phase 2C (#145) reply
 * submission lifecycle :
 *
 * 1. On init, when the request is a reply with a known `(page, subcat, topicId)`,
 *    fetches the HFR reply form (`message.php`) to grab the per-session
 *    `hash_check` and the hidden contract fields.
 * 2. On [PostEditorIntent.SubmitClicked], POSTs `bddpost.php` via the repository
 *    and emits a one-shot [PostEditorEffect.SubmitSucceeded] on success.
 *
 * Anti-double-submit is enforced via [PostEditorState.isSubmitting] + a single
 * [submitJob] reference that ignores re-entry while in flight. Errors classified
 * by the repository are surfaced via [SubmitError]; the draft is preserved.
 */
@HiltViewModel(assistedFactory = PostEditorViewModel.Factory::class)
@Suppress("LongParameterList") // Hilt constructor injection — one dependency per collaborator.
class PostEditorViewModel @AssistedInject constructor(
    @Assisted private val request: PostEditorRequest,
    private val previewParser: BbcodePreviewParser,
    private val replyRepository: ReplyRepository,
    private val editPostRepository: EditPostRepository,
    private val smileyRepository: SmileyRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val diagnostics: DiagnosticsLog,
) : ViewModel() {

    private val _state: MutableStateFlow<PostEditorState> = MutableStateFlow(
        PostEditorState(
            mode = request.mode,
            cat = request.cat,
            topicId = request.topicId,
            numreponse = request.numreponse,
            page = request.page,
            subcat = request.subcat,
            quotedNumreponse = request.quotedNumreponse,
            quoteRef = request.quoteRef,
        ),
    )
    val state: StateFlow<PostEditorState> = _state.asStateFlow()

    private val _effects: Channel<PostEditorEffect> = Channel(capacity = Channel.BUFFERED)
    val effects: Flow<PostEditorEffect> = _effects.receiveAsFlow()

    /**
     * Cached form pulled lazily on [PostEditorMode.Reply]. Keeping it on the
     * ViewModel rather than the [PostEditorState] avoids leaking `hash_check`
     * through Compose snapshot tooling / state restoration.
     */
    private var loadedForm: ReplyForm? = null
    private var submitJob: Job? = null
    /** In-flight wiki smiley search ; cancelled on next query change / picker close. */
    private var smileySearchJob: Job? = null

    /**
     * #312 — mirror of the persisted « Confirmation avant publication » preference. Collected on
     * init (same DataStore-consumption shape as `TopicViewModel.observeTopicTopBarAutoHide`) and
     * read synchronously at submit time. Kept off [PostEditorState] because the UI never renders
     * the preference itself — only the dialog visibility flag it gates.
     */
    private var confirmBeforePosting: Boolean = false

    init {
        viewModelScope.launch {
            userPreferencesRepository.observeConfirmBeforePosting().collect { enabled ->
                confirmBeforePosting = enabled
            }
        }
        when (request.mode) {
            PostEditorMode.Reply -> loadReplyFormIfPossible()
            PostEditorMode.Edit -> loadEditFormIfPossible()
        }
    }

    @Suppress("CyclomaticComplexMethod") // MVI when-dispatch over the PostEditorIntent variants ; flat by design.
    fun submit(intent: PostEditorIntent) {
        when (intent) {
            is PostEditorIntent.ContentChanged -> onContentChanged(intent.value)
            is PostEditorIntent.ToolbarActionClicked -> onToolbarActionClicked(intent.action)
            PostEditorIntent.TogglePreview -> onTogglePreview()
            PostEditorIntent.SubmitClicked -> onSubmitClicked()
            PostEditorIntent.SubmitConfirmed -> onSubmitConfirmed()
            PostEditorIntent.SubmitConfirmationDismissed ->
                _state.update { it.copy(showSubmitConfirmation = false) }
            PostEditorIntent.ErrorDismissed -> _state.update { it.copy(submitError = null) }
            is PostEditorIntent.ToggleSignature ->
                _state.update { it.copy(signatureEnabled = intent.enabled) }
            is PostEditorIntent.ToggleSmileyDisabled ->
                _state.update { it.copy(smileyDisabled = intent.disabled) }
            is PostEditorIntent.ToggleEmailNotification ->
                _state.update { it.copy(emailNotificationEnabled = intent.enabled) }
            PostEditorIntent.SmileyPickerOpened -> onSmileyPickerOpened()
            PostEditorIntent.SmileyPickerDismissed -> onSmileyPickerDismissed()
            is PostEditorIntent.SmileySearchQueryChanged -> onSmileySearchQueryChanged(intent.query)
            is PostEditorIntent.SmileySelected -> onSmileySelected(intent.token)
            is PostEditorIntent.ImageUrlInserted -> onImageUrlInserted(intent.url)
        }
    }

    private fun onSmileyPickerOpened() {
        _state.update { current ->
            if (current.smileyPicker is SmileyPickerState.Open) current
            else current.copy(smileyPicker = SmileyPickerState.Open())
        }
    }

    private fun onSmileyPickerDismissed() {
        smileySearchJob?.cancel()
        smileySearchJob = null
        _state.update { it.copy(smileyPicker = SmileyPickerState.Hidden) }
    }

    private fun onSmileySearchQueryChanged(query: String) {
        _state.update { current ->
            val open = current.smileyPicker as? SmileyPickerState.Open ?: return@update current
            current.copy(smileyPicker = open.copy(query = query))
        }
        // Cancel in-flight searches so an older response can't overwrite a newer query.
        smileySearchJob?.cancel()
        if (query.length <= 2) {
            // Mirrors the HFR web composer's `query.length > 2` gate. Below threshold we
            // reset the wiki branch to Idle so the picker can render the Standard tab.
            _state.update { current ->
                val open = current.smileyPicker as? SmileyPickerState.Open ?: return@update current
                current.copy(smileyPicker = open.copy(wiki = WikiSearchState.Idle))
            }
            return
        }
        smileySearchJob = viewModelScope.launch {
            // 300 ms matches the JS `find_smilies_timer` debounce embedded in HFR's
            // /compressed/message.js — keeping it identical avoids surprising spikes if
            // the user types fast. We deliberately flip to `Loading` AFTER the debounce
            // so a user typing « jap » in one burst never sees a Loading flash before
            // the actual network call (the previous job is cancelled before its delay
            // resolves, so the state stays on the previous wiki snapshot until the
            // last keystroke survives the 300 ms idle window).
            delay(SMILEY_SEARCH_DEBOUNCE_MS)
            // Identity guard against the « same query typed twice in a 300 ms window »
            // race : if the user types « jap » → backspaces → re-types « jap » before
            // the first delay resolves, both jobs would pass the `open.query == query`
            // check (the query string is identical) and launch two parallel requests.
            // We compare the current job identity to the ViewModel's `smileySearchJob`
            // — the second `launchSmileySearch` call replaced the reference, so the
            // first job's `coroutineContext.job` is not the active one anymore. Abort.
            if (coroutineContext[Job] !== smileySearchJob) return@launch
            _state.update { current ->
                val open = current.smileyPicker as? SmileyPickerState.Open ?: return@update current
                if (open.query != query) return@update current
                current.copy(smileyPicker = open.copy(wiki = WikiSearchState.Loading))
            }
            val effectiveUserId = _state.value.userId ?: 0
            val outcome = runCatching { smileyRepository.searchWiki(effectiveUserId, query) }
            outcome.fold(
                onSuccess = { items ->
                    _state.update { current ->
                        val open = current.smileyPicker as? SmileyPickerState.Open
                            ?: return@update current
                        // Drop the result if the user closed the picker or typed a different
                        // query while we were waiting on the network.
                        if (open.query != query) return@update current
                        current.copy(smileyPicker = open.copy(wiki = WikiSearchState.Results(items)))
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    diagnostics.record(
                        DiagnosticsLog.Level.WARN,
                        LOG_TAG_VM,
                        "wiki smiley search failed: ${error::class.simpleName}",
                    )
                    _state.update { current ->
                        val open = current.smileyPicker as? SmileyPickerState.Open
                            ?: return@update current
                        if (open.query != query) return@update current
                        current.copy(smileyPicker = open.copy(wiki = WikiSearchState.Error))
                    }
                },
            )
        }
    }

    private fun onSmileySelected(token: String) {
        smileySearchJob?.cancel()
        smileySearchJob = null
        // Reuse the formatter helper so the surrounding-spaces convention from HFR's web
        // composer is honoured uniformly (cf. `BbcodeFormatter.insertBbcodeToken`).
        _state.update { current ->
            val draft = current.draft
            val selection = draft.selection
            val outcome = insertBbcodeToken(
                token = token,
                text = draft.text,
                selectionStart = selection.start,
                selectionEnd = selection.end,
            )
            val updatedDraft = TextFieldValue(
                text = outcome.text,
                selection = TextRange(outcome.selectionStart, outcome.selectionEnd),
            )
            val withDraft = current.withDraft(updatedDraft)
            val withPreview = if (withDraft.isPreviewVisible) {
                withDraft.copy(preview = previewParser.parsePreview(withDraft.draft.text))
            } else {
                withDraft
            }
            // Close the picker on successful insertion ; the user can re-open it for another
            // smiley if they want to chain. This matches HFR web behaviour and keeps the
            // sheet from squatting the screen between two distant insertions.
            withPreview.copy(smileyPicker = SmileyPickerState.Hidden)
        }
    }

    private fun onImageUrlInserted(url: String) {
        val token = imageBbcodeTokenOrNull(url) ?: return
        _state.update { current ->
            val draft = current.draft
            val selection = draft.selection
            val outcome = insertBbcodeToken(
                token = token,
                text = draft.text,
                selectionStart = selection.start,
                selectionEnd = selection.end,
                surroundWithSpaces = false,
            )
            val updatedDraft = TextFieldValue(
                text = outcome.text,
                selection = TextRange(outcome.selectionStart, outcome.selectionEnd),
            )
            val withDraft = current.withDraft(updatedDraft)
            if (withDraft.isPreviewVisible) {
                withDraft.copy(preview = previewParser.parsePreview(withDraft.draft.text))
            } else {
                withDraft
            }
        }
    }

    private fun onContentChanged(value: TextFieldValue) {
        _state.update { current ->
            val refreshed = current.withDraft(value)
            if (refreshed.isPreviewVisible) {
                refreshed.copy(preview = previewParser.parsePreview(refreshed.draft.text))
            } else {
                refreshed
            }
        }
    }

    private fun onToolbarActionClicked(action: BbcodeAction) {
        _state.update { current ->
            val draft = current.draft
            val selection = draft.selection
            val outcome = applyBbcodeAction(
                action = action,
                text = draft.text,
                selectionStart = selection.start,
                selectionEnd = selection.end,
            )
            val updatedDraft = TextFieldValue(
                text = outcome.text,
                selection = TextRange(outcome.selectionStart, outcome.selectionEnd),
            )
            val withDraft = current.withDraft(updatedDraft)
            if (withDraft.isPreviewVisible) {
                withDraft.copy(preview = previewParser.parsePreview(withDraft.draft.text))
            } else {
                withDraft
            }
        }
    }

    private fun onTogglePreview() {
        _state.update { current ->
            val nextVisible = !current.isPreviewVisible
            current.copy(
                isPreviewVisible = nextVisible,
                preview = if (nextVisible) previewParser.parsePreview(current.draft.text) else current.preview,
            )
        }
    }

    private fun loadReplyFormIfPossible() {
        val context = buildReplyContext() ?: run {
            _state.update { it.copy(submitError = SubmitError.MissingSubcat) }
            return
        }
        launchFormFetch { fetchReplyFormWithExtraQuotes(context) }
    }

    /**
     * #291 multi-quote — the quote form fetch (#146) returns ONE `[quotemsg]` prefill per
     * `numrep`, so additional quoted posts are fetched by replaying the same contract with
     * `quotedNumreponse` swapped, then concatenated into the first form's [ReplyForm.initialContent]
     * in selection order. Client-side only: HFR never sees a multi-numrep request, and the
     * submit still rides the FIRST form's `hash_check`/hidden fields (per-session, not
     * per-post — the single-quote and plain-reply paths already share them).
     *
     * Sequential on purpose: N is tiny (a handful of posts), order must be deterministic, and
     * a failed extra fails the whole fetch — silently dropping a quote the user explicitly
     * selected would be worse than the retryable form-fetch error.
     */
    private suspend fun fetchReplyFormWithExtraQuotes(context: ReplyContext): ReplyForm {
        val form = replyRepository.fetchReplyForm(context)
        val extras = request.extraQuoteNumreponses
        if (extras.isEmpty() || !context.isQuote) return form
        val prefills = buildList {
            add(form.initialContent)
            extras.forEach { numreponse ->
                // quoteRef is positional/cosmetic and belongs to the FIRST post only.
                add(
                    replyRepository
                        .fetchReplyForm(context.copy(quotedNumreponse = numreponse, quoteRef = null))
                        .initialContent,
                )
            }
        }
        val merged = prefills
            .map { prefill ->
                prefill.trimEnd().also { trimmed ->
                    // Codex review — a 200-OK form whose prefill came back BLANK would silently
                    // drop a quote the user explicitly selected (the exact failure mode the
                    // sequential design refuses). Fail the whole fetch instead; the mapped
                    // SubmitError keeps the editor on its retryable error path.
                    check(trimmed.isNotBlank()) { "multi-quote prefill came back blank" }
                }
            }
            .joinToString(separator = "\n\n", postfix = "\n\n")
        return form.copy(initialContent = merged)
    }

    private fun loadEditFormIfPossible() {
        val context = buildEditPostContext() ?: run {
            _state.update { it.copy(submitError = SubmitError.MissingSubcat) }
            return
        }
        launchFormFetch { editPostRepository.fetchEditPostForm(context) }
    }

    /**
     * Shared form-fetch pipeline used by reply (Phase 2C) and edit (Phase 2D).
     * The state update body is identical between the two flows : both hydrate
     * `draft`, `preview`, and the three per-post options once from
     * [ReplyForm.initialContent] / [ReplyForm.options], and both honour the
     * anti-clobber guards on refetch.
     */
    private fun launchFormFetch(fetch: suspend () -> ReplyForm) {
        _state.update { it.copy(isLoadingForm = true, submitError = null) }
        viewModelScope.launch {
            val outcome = runCatching { fetch() }
            outcome.fold(
                onSuccess = { form ->
                    loadedForm = form
                    // Pre-compute the preview AST outside the `_state.update {}`
                    // lambda : the lambda runs synchronously on whichever thread
                    // pumps the StateFlow (in practice the coroutine's dispatcher,
                    // i.e. `Dispatchers.Main.immediate` here) and `parsePreview`
                    // is a CPU-bound Jsoup-shaped pass that can block on long
                    // BBCode prefills. Reading the latest snapshot once, deciding,
                    // then handing the result to the lambda keeps the update body
                    // pure copy.
                    val snapshot = _state.value
                    val nextPreview = if (snapshot.shouldHydrateFrom(form) && snapshot.isPreviewVisible) {
                        previewParser.parsePreview(form.initialContent)
                    } else {
                        snapshot.preview
                    }
                    _state.update { current -> current.withFormHydration(form, nextPreview) }
                },
                onFailure = { error -> handleFetchFailure(error) },
            )
        }
    }

    /**
     * Mirror of the hydration condition used inside [withFormHydration] — kept
     * here so [launchFormFetch] can pre-compute the preview AST off the state
     * lambda without duplicating the truth.
     */
    private fun PostEditorState.shouldHydrateFrom(form: ReplyForm): Boolean =
        !draftHydratedFromForm &&
            draft.text.isBlank() &&
            form.initialContent.isNotBlank()

    /**
     * Pure state transformer : produces the next [PostEditorState] after a form
     * fetch lands. The preview AST is supplied by the caller (pre-computed off
     * the state-flow lambda to keep the heavier `parsePreview` call off the
     * UI dispatcher) ; we still re-check `shouldHydrate` against `current` so
     * a state mutation interleaved with the fetch (rare but possible) does
     * not push a preview derived from `form.initialContent` onto a draft the
     * user already started editing.
     *
     * Guarantees :
     * - `draft` is hydrated from `form.initialContent` only the first time and
     *   only if the user has not typed anything yet (`draftHydratedFromForm`
     *   prevents an `InvalidHashCheck` silent refetch from clobbering user
     *   edits).
     * - Options are hydrated from `form.options` only the first time
     *   (`optionsHydratedFromForm`) so a refetch never resets the user's
     *   toggle choices.
     * - `preview` is replaced by [nextPreview] only when the same
     *   `shouldHydrate` condition holds on the latest state AND the preview
     *   pane is visible. Otherwise the existing preview is preserved.
     */
    private fun PostEditorState.withFormHydration(
        form: ReplyForm,
        nextPreview: PostContent,
    ): PostEditorState {
        val shouldHydrate = shouldHydrateFrom(form)
        val nextDraft = if (shouldHydrate) {
            TextFieldValue(
                text = form.initialContent,
                // Place caret at the end so the user can type their content
                // right after the prefill — matches HFR's web behavior.
                selection = TextRange(form.initialContent.length),
            )
        } else {
            draft
        }
        val hydrateOptions = !optionsHydratedFromForm
        return copy(
            isLoadingForm = false,
            draft = nextDraft,
            // Only adopt the caller's pre-computed preview when the same
            // hydration condition holds on the *latest* state. If the user
            // typed in between the snapshot and this update, `shouldHydrate`
            // flips to false on `current` and we keep the user-driven
            // preview ; the parsed `nextPreview` is dropped.
            preview = if (shouldHydrate && isPreviewVisible) nextPreview else preview,
            draftHydratedFromForm = draftHydratedFromForm || shouldHydrate,
            signatureEnabled = if (hydrateOptions) form.options.signatureEnabled else signatureEnabled,
            smileyDisabled = if (hydrateOptions) form.options.smileyDisabled else smileyDisabled,
            emailNotificationEnabled = if (hydrateOptions) {
                form.options.emailNotificationEnabled
            } else {
                emailNotificationEnabled
            },
            optionsHydratedFromForm = true,
            // Phase 2F-B (#11) — keep the parsed userId around for the wiki smiley search.
            // Anti-clobber : do not overwrite once set, so a silent `InvalidHashCheck`
            // refetch on an anonymous fallback does not erase a previously known id.
            userId = userId ?: form.userId,
            submitError = if (form.isAnonymous) {
                SubmitError.Hfr(ReplyFailureReason.LoginRequired)
            } else {
                submitError
            },
        )
    }

    private fun handleFetchFailure(error: Throwable) {
        if (error is CancellationException) {
            // Symmetry with `handleSubmitFailure` : reset the in-flight flag before the throw so
            // a parent ViewModel that survives the cancellation (or any state observer reading
            // the snapshot before death) does not see `isLoadingForm = true` forever.
            _state.update { it.copy(isLoadingForm = false) }
            throw error
        }
        // Map known transport-level failures to a typed SubmitError ; classify the
        // rest as Unknown rather than letting the exception bubble up and crash the
        // process. The UI surfaces the same "unexpected response" message either way.
        val mapped = when (error) {
            is SessionExpiredException -> SubmitError.SessionExpired
            is IOException -> SubmitError.Network
            else -> SubmitError.Hfr(ReplyFailureReason.Unknown)
        }
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_VM,
            "fetch bubbled: ${error::class.simpleName}: ${error.message ?: "(no message)"} " +
                "→ ${mapped::class.simpleName}",
        )
        _state.update { it.copy(isLoadingForm = false, submitError = mapped) }
    }

    /**
     * #312 — confirm path. Closes the dialog and re-runs the submit pipeline with
     * `bypassConfirmation = true` so the real submission executes directly (re-checking the
     * preference here would loop « confirmation → confirmation » forever). The validation
     * guards run again on the latest snapshot, which is safe because the dialog is modal.
     */
    private fun onSubmitConfirmed() {
        _state.update { it.copy(showSubmitConfirmation = false) }
        onSubmitClicked(bypassConfirmation = true)
    }

    @Suppress("ReturnCount") // guard clauses are the natural shape of the dispatcher
    private fun onSubmitClicked(bypassConfirmation: Boolean = false) {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        val form = loadedForm ?: run {
            // Form not loaded yet — fetch it then bail out; user re-clicks once ready.
            when (snapshot.mode) {
                PostEditorMode.Reply -> loadReplyFormIfPossible()
                PostEditorMode.Edit -> loadEditFormIfPossible()
            }
            return
        }
        if (form.isAnonymous) {
            _state.update { it.copy(submitError = SubmitError.Hfr(ReplyFailureReason.LoginRequired)) }
            return
        }
        if (submitJob?.isActive == true) return
        // #312 — AFTER every validation gate (we never confirm an unsendable form), BEFORE the
        // real POST: when the preference is on, park the submit behind the confirmation dialog.
        // [onSubmitConfirmed] re-enters with `bypassConfirmation = true`.
        if (!bypassConfirmation && confirmBeforePosting) {
            _state.update { it.copy(showSubmitConfirmation = true) }
            return
        }

        val options = ReplyFormOptions(
            signatureEnabled = snapshot.signatureEnabled,
            smileyDisabled = snapshot.smileyDisabled,
            emailNotificationEnabled = snapshot.emailNotificationEnabled,
        )
        _state.update { it.copy(isSubmitting = true, submitError = null) }
        submitJob = viewModelScope.launch {
            val outcome = runCatching {
                when (snapshot.mode) {
                    PostEditorMode.Reply -> {
                        val context = buildReplyContext() ?: error("canSubmit lied about reply context")
                        replyRepository.submitReply(
                            context = context,
                            form = form,
                            bbcodeContent = snapshot.draft.text,
                            options = options,
                        )
                    }
                    PostEditorMode.Edit -> {
                        val context = buildEditPostContext() ?: error("canSubmit lied about edit context")
                        editPostRepository.submitEditPost(
                            context = context,
                            form = form,
                            bbcodeContent = snapshot.draft.text,
                            options = options,
                        )
                    }
                }
            }
            outcome.fold(
                onSuccess = { result -> handleSubmitOutcome(snapshot.mode, snapshot.numreponse, result) },
                onFailure = ::handleSubmitFailure,
            )
        }
    }

    private fun handleSubmitOutcome(
        mode: PostEditorMode,
        numreponse: Int?,
        result: ReplySubmitResult,
    ) {
        when (result) {
            is ReplySubmitResult.Success -> {
                // Issue #200 — HFR's success URL fragment carries `#t{numreponse}` for quote
                // and edit (the parser exposes it as `result.numreponse`), and `#bas` for a
                // plain reply (numreponse is null then). Prefer the value the parser pulled
                // out of HFR's own response over the local hint — they should agree for
                // edit, but the parser is authoritative if HFR ever picks a different post
                // index. Falls back to the locally-known numreponse for edit so existing
                // tests that don't populate the parser numreponse still scroll to the post.
                val scrollTo = result.numreponse ?: numreponse.takeIf { mode == PostEditorMode.Edit }
                _effects.trySend(
                    PostEditorEffect.SubmitSucceeded(targetPage = result.targetPage, scrollTo = scrollTo),
                )
                _state.update { it.copy(isSubmitting = false, submitError = null) }
            }
            is ReplySubmitResult.Failure -> {
                // InvalidHashCheck typically means the cached form has expired ;
                // refetch silently and let the user re-submit. The mode dictates
                // which fetch path to follow.
                if (result.reason == ReplyFailureReason.InvalidHashCheck) {
                    loadedForm = null
                    when (mode) {
                        PostEditorMode.Reply -> loadReplyFormIfPossible()
                        PostEditorMode.Edit -> loadEditFormIfPossible()
                    }
                }
                _state.update {
                    it.copy(isSubmitting = false, submitError = SubmitError.Hfr(result.reason))
                }
            }
        }
    }

    private fun handleSubmitFailure(error: Throwable) {
        if (error is CancellationException) {
            _state.update { it.copy(isSubmitting = false) }
            throw error
        }
        val mapped = when (error) {
            is SessionExpiredException -> SubmitError.SessionExpired
            is IOException -> SubmitError.Network
            else -> SubmitError.Hfr(ReplyFailureReason.Unknown)
        }
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_VM,
            "submit bubbled: ${error::class.simpleName}: ${error.message ?: "(no message)"} " +
                "→ ${mapped::class.simpleName}",
        )
        _state.update { it.copy(isSubmitting = false, submitError = mapped) }
    }

    @Suppress("ReturnCount") // Each guard returns null with a distinct reason
    private fun buildEditPostContext(): EditPostContext? {
        val snapshot = state.value
        val page = snapshot.page ?: return null
        val subcat = snapshot.subcat ?: return null
        val topicId = snapshot.topicId ?: return null
        val numreponse = snapshot.numreponse ?: return null
        // #213 — `subcat = 0` is postable (cat without sub-category) ; only the
        // SUBCAT_UNKNOWN sentinel (-1) is blocking. Keep `> 0` for topicId / numreponse.
        if (subcat < 0 || topicId <= 0 || numreponse <= 0) return null
        return EditPostContext(
            cat = snapshot.cat,
            subcat = subcat,
            topicId = topicId,
            page = page,
            numreponse = numreponse,
        )
    }

    @Suppress("ReturnCount") // Each guard returns null with a distinct reason
    private fun buildReplyContext(): ReplyContext? {
        val snapshot = state.value
        val page = snapshot.page ?: return null
        val subcat = snapshot.subcat ?: return null
        val topicId = snapshot.topicId ?: return null
        // #213 — mirror the `ReplyContext.init` rule : reject only the SUBCAT_UNKNOWN
        // sentinel (-1). `subcat = 0` is postable (cat without sub-category, e.g. IA).
        if (subcat < 0) return null
        return ReplyContext(
            cat = snapshot.cat,
            subcat = subcat,
            topicId = topicId,
            page = page,
            // Phase 2C (#146) : both fields are null for a simple reply ; both
            // non-null for a quote launched from `TopicScreen.onQuote`. The model
            // tolerates a quote with a null `quoteRef` for forward compat (HFR
            // could drop `ref` someday), but we keep them aligned in practice.
            quotedNumreponse = snapshot.quotedNumreponse,
            quoteRef = snapshot.quoteRef,
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(request: PostEditorRequest): PostEditorViewModel
    }

    private companion object {
        // Distinct from the repository's "ReplyRepository" tag so the diagnostics
        // panel makes it obvious which layer recorded an entry.
        private const val LOG_TAG_VM = "PostEditorVM"

        // HFR's web composer waits 300 ms after the last keystroke before calling
        // `find_smilies`, cf. `/compressed/message.js`. Mirror it so the wiki endpoint
        // sees roughly the same query rate as the web client.
        private const val SMILEY_SEARCH_DEBOUNCE_MS = 300L
    }
}
