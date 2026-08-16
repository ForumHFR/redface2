package fr.forumhfr.redface2.feature.editor
import fr.forumhfr.redface2.core.ui.editor.UploadError
import fr.forumhfr.redface2.core.ui.editor.UploadProgress

import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.write.EditPostRepository
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.applyBbcodeAction
import fr.forumhfr.redface2.core.ui.editor.imageInsertBbcodeOrNull
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
import kotlinx.coroutines.flow.first
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
@Suppress("LongParameterList", "LargeClass")
// LongParameterList — Hilt constructor injection, one dependency per collaborator (#459 PR2 adds
// the upload reader + repository + auth source for the in-editor image upload).
// LargeClass — one class per ViewModel co-locates every code path (#145 reply / #146 quote /
// #147 edit / #11 smiley / #189 image-url / #405 draft / #459 image-upload) with its dispatcher ;
// splitting per phase would shred the shared state transformers (same rationale as TopicFormVM).
class PostEditorViewModel @AssistedInject constructor(
    @Assisted private val request: PostEditorRequest,
    private val previewParser: BbcodePreviewParser,
    private val replyRepository: ReplyRepository,
    private val editPostRepository: EditPostRepository,
    private val smileyRepository: SmileyRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val draftStore: EditorDraftStore,
    private val diagnostics: DiagnosticsLog,
    private val uploadRepository: UploadRepository,
    private val imageUploadReader: ImageUploadReader,
    private val authRepository: AuthRepository,
    private val quoteMaterializer: TopicReplyQuoteMaterializer,
) : ViewModel() {

    private val _state: MutableStateFlow<PostEditorState> = MutableStateFlow(
        PostEditorState(
            mode = request.mode,
            cat = request.cat,
            topicId = request.topicId,
            numreponse = request.numreponse,
            page = request.page,
            subcat = request.subcat,
            // #805 arbitrage — the handoff quotes are NOT seeded here anymore : their rendering
            // (cards vs inline [quotemsg]) is decided per opening from the persisted preference,
            // which is a suspend read — see [resolveQuoteRenderingThenLoad] in init.
            quotes = emptyList(),
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

    /**
     * #405 — stable, content-free draft key for this editor session, or null when the routing args
     * cannot identify a target (no autosave/restore then). Reply ignores the quoted numreponse on
     * purpose (cf. [EditorDraftKey]). Edit needs the numreponse to identify the post.
     */
    private val draftKey: String? = when (request.mode) {
        PostEditorMode.Reply -> request.topicId?.let { EditorDraftKey.reply(request.cat, it) }
        PostEditorMode.Edit -> request.numreponse?.let { EditorDraftKey.editPost(request.cat, it) }
    }

    /** #405 — debounced autosave coroutine ; relaunched (cancelling the previous) on each edit. */
    private var autosaveJob: Job? = null

    /**
     * #312 — mirror of the persisted « Confirmation avant publication » preference. Collected on
     * init (same DataStore-consumption shape as `TopicViewModel.observeTopicTopBarAutoHide`) and
     * read synchronously at submit time. Kept off [PostEditorState] because the UI never renders
     * the preference itself — only the dialog visibility flag it gates.
     */
    private var confirmBeforePosting: Boolean = false

    /**
     * #459 PR2 — mirror of the persisted [EditorImageInsert] preference (full / linked / reduced).
     * Collected on init like [confirmBeforePosting] and read SYNCHRONOUSLY at insert time so the
     * image-URL / upload paths can mutate the draft in the same frame as the user action (no
     * suspend between the action and the draft mutation — Codex PR2 review). Default mirrors the
     * repository's REDUCED default until the first emission lands.
     */
    private var imageInsertMode: EditorImageInsert = EditorImageInsert.REDUCED

    /**
     * #459 PR2 — active lowercased HFR pseudo (the upload `userId`), or null when anonymous.
     * Captured from the auth stream exactly like `MyImagesViewModel` / `FlagsViewModel` so an
     * [PostEditorIntent.ImagePicked] can scope the upload to the right owner without re-reading the
     * flow. Kept off [PostEditorState] : the editor never renders the pseudo, only gates on its
     * presence.
     */
    private var activeUserId: String? = null

    /**
     * #405 — account that owned this editor when it opened, snapshotted from [draftStore] so a
     * mid-edit account switch can't write this session's body under another account (Codex beta
     * review). Captured in [restoreDraftIfAny]; null until then / for an anonymous session.
     */
    private var draftOwner: String? = null

    /** #459 PR2 — in-flight image upload job ; one at a time, cancelled on [onCleared]. */
    private var uploadJob: Job? = null

    init {
        viewModelScope.launch {
            userPreferencesRepository.observeConfirmBeforePosting().collect { enabled ->
                confirmBeforePosting = enabled
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.observeEditorImageInsert().collect { mode ->
                imageInsertMode = mode
            }
        }
        viewModelScope.launch {
            authRepository.observeAuthState().collect { authState ->
                val newUserId = when (authState) {
                    AuthState.Anonymous -> null
                    is AuthState.Authenticated -> authState.pseudo.lowercase()
                }
                // Account switched/logged out mid-session: cancel any in-flight upload and pending
                // autosave so this session's image URL / body is never attributed to the new
                // account (Codex beta review). The draft store also drops the now-stale save.
                if (activeUserId != null && newUserId != activeUserId) {
                    uploadJob?.cancel()
                    autosaveJob?.cancel()
                    _state.update { it.copy(isUploading = false, uploadProgress = null) }
                }
                activeUserId = newUserId
            }
        }
        restoreDraftIfAny()
        when (request.mode) {
            PostEditorMode.Reply -> resolveQuoteRenderingThenLoad()
            PostEditorMode.Edit -> loadEditFormIfPossible()
        }
    }

    /**
     * #805 arbitrage — one preference read (`first()`, decided per opening) picks the citation
     * rendering for this session :
     *
     * - **cards ON** — the #604 lot 3 behaviour, unchanged : handoff previews become
     *   [PostEditorState.quotes] (deduplicated per numreponse, Reply-only), the open-time fetch
     *   stays PLAIN, `[quotemsg]` is materialised fresh at submit.
     * - **cards OFF (default)** — the pre-lot-3 flow : the open-time fetch IS the materialisation
     *   ([TopicReplyQuoteMaterializer.fetchFormWithQuotes]), so [launchFormFetch] hydrates the field
     *   with the merged `[quotemsg]` prefills through the existing anti-clobber guards, and
     *   [restoreDraftIfAny]'s `resumeSharedDraft` append stays commutative with it (#790).
     *   `state.quotes` stays empty → submit takes the plain path (the content is the field).
     */
    private fun resolveQuoteRenderingThenLoad() {
        val initialQuotes = request.initialQuotes.distinctBy { it.numreponse }
        if (initialQuotes.isEmpty()) {
            loadReplyFormIfPossible()
            return
        }
        viewModelScope.launch {
            if (userPreferencesRepository.observeQuoteCardsEnabled().first()) {
                _state.update { it.copy(quotes = initialQuotes) }
                loadReplyFormIfPossible()
            } else {
                loadReplyFormWithInlineQuotes(initialQuotes)
            }
        }
    }

    /**
     * #805 cards OFF — open-time fetch through the materializer (quote form + merged prefills).
     *
     * The prefill does NOT ride [launchFormFetch]'s hydration : that path only fills a BLANK
     * field (anti-clobber), so a `resumeSharedDraft` restore landing first would silently drop
     * the citation. Instead the merged `[quotemsg]` blocks are PREPENDED onto the live field
     * content at completion — commutative with the restore's append whichever lands first
     * (réserve Codex n°5), and typing started during the fetch survives. Options still hydrate
     * through [withFormHydration] (with a content-blanked form, so the text path stays inert).
     */
    private fun loadReplyFormWithInlineQuotes(quotes: List<QuoteSelection>) {
        val context = buildReplyContext() ?: run {
            _state.update { it.copy(submitError = SubmitError.MissingSubcat) }
            return
        }
        val quoteContext = context.copy(quotedNumreponse = quotes.first().numreponse, quoteRef = null)
        _state.update { it.copy(isLoadingForm = true, submitError = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                quoteMaterializer.fetchFormWithQuotes(
                    context = quoteContext,
                    extraQuoteNumreponses = quotes.drop(1).map { it.numreponse },
                )
            }
            outcome.fold(
                onSuccess = { form ->
                    loadedForm = form
                    val prefills = form.initialContent.trimEnd()
                    _state.update { current ->
                        val existing = current.draft.text
                        // #881 — a quote block that ends the field carries exactly ONE trailing
                        // newline so typing starts under the citation. In the prepend branch the
                        // existing "\n\n" separator already provides it; a later resumeSharedDraft
                        // append trimEnd()s the field first, so both orders stay commutative (#790).
                        val combined = if (existing.isBlank()) prefills + "\n" else prefills + "\n\n" + existing
                        current
                            .withFormHydration(form.copy(initialContent = ""), current.preview)
                            .copy(draft = TextFieldValue(text = combined, selection = TextRange(combined.length)))
                    }
                    scheduleAutosave()
                },
                onFailure = { error -> handleFetchFailure(error) },
            )
        }
    }

    /**
     * #405 — surface a cached draft for [draftKey] on the banner (never auto-apply : a quote prefill
     * or an edit body would otherwise be silently clobbered). Empty drafts are ignored.
     *
     * #790 exception — when the route carries `resumeSharedDraft` (escalation of a quick-reply
     * sheet, which JUST wrote the row), the body is APPENDED to the field instead of banner'd :
     * the escalation continues the same composition act. With cards OFF (#805 default) the
     * open-time quote form DOES carry a `[quotemsg]` prefill again : this append and the form
     * hydration prepend are commutative, guarded against double-hydration — the original #790
     * contract, restored.
     */
    private fun restoreDraftIfAny() {
        val key = draftKey ?: return
        viewModelScope.launch {
            draftOwner = draftStore.currentOwner()
            val body = draftStore.load(draftOwner, key)?.body
            if (body.isNullOrBlank()) return@launch
            if (request.resumeSharedDraft) {
                _state.update { current ->
                    // Conditional separator (gate #798): a late restore during typing must not
                    // glue the resumed body to the user's last word.
                    val combined = if (current.draft.text.isBlank()) {
                        body
                    } else {
                        current.draft.text.trimEnd() + "\n\n" + body
                    }
                    current.withDraft(TextFieldValue(text = combined, selection = TextRange(combined.length)))
                }
                scheduleAutosave()
            } else {
                _state.update { it.copy(restorableDraft = body) }
            }
        }
    }

    /**
     * #405 — debounced autosave of the current body. Blank body → delete the row so an emptied
     * editor never leaves a stale draft behind. The store stamps `updatedAt` and is a no-op without
     * an active session, so nothing is persisted for an anonymous client.
     */
    private fun scheduleAutosave() {
        if (draftKey == null) return
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            persistDraftNow()
        }
    }

    /** Immediate write of the current body (blank = delete the row, cf. [scheduleAutosave]). */
    private suspend fun persistDraftNow() {
        val key = draftKey ?: return
        val body = _state.value.draft.text
        if (body.isBlank()) {
            draftStore.delete(draftOwner, key)
        } else {
            draftStore.save(draftOwner, key, EditorDraftStore.Draft(body = body))
        }
    }

    /** #604 lot 4a — one-shot latch : a committed close is never re-emitted (gate #803). */
    private var closeRequested = false

    /**
     * #604 lot 4a — dirty close : flush the pending debounce so the last keystrokes reach the
     * #405 row, THEN let the UI pop (CloseCommitted). Without this, a system back < 750 ms
     * after typing cancelled the debounce with the ViewModel and silently dropped the tail of
     * the draft. Mirrors the quick-reply escalation (save awaited before the effect).
     *
     * Two guards (gate #803, NO-GO findings) :
     * - INERT while a POST is in flight — popping would cancel the submit with the
     *   viewModelScope and leave the server state unknown with a repostable draft (the sheet
     *   blocks its dismiss the same way, gate #788) ; on failure `isSubmitting` drops and the
     *   back works again, on success SubmitSucceeded pops anyway ;
     * - ONE-SHOT — a second back racing the first CloseCommitted must not emit a second
     *   effect (each `onClose` pops blindly : the second pop would remove the screen BELOW).
     */
    private fun onCloseRequested() {
        if (_state.value.isSubmitting || closeRequested) return
        closeRequested = true
        autosaveJob?.cancel()
        viewModelScope.launch {
            // The close must NEVER stay blocked on a failing flush (Room is not contractually
            // non-throwing — disk full, corrupted store) : the one-shot latch is already set, so
            // a swallowed failure here only costs the last <750 ms of typing (the debounced
            // autosave already persisted the rest) while a rethrow would leave the screen
            // unclosable. CancellationException still propagates (scope teardown is not an error).
            try {
                persistDraftNow()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort — the previous debounced write is what remains.
            }
            _effects.send(PostEditorEffect.CloseCommitted)
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
            is PostEditorIntent.SmileySelected -> onSmileySelected(intent.token)
            is PostEditorIntent.ImageUrlInserted -> onImageUrlInserted(intent.url)
            is PostEditorIntent.ImagePicked -> onImagePicked(intent.uri)
            is PostEditorIntent.ImagesPicked -> onImagesPicked(intent.uris)
            PostEditorIntent.UploadErrorDismissed -> _state.update { it.copy(uploadError = null) }
            PostEditorIntent.DraftRestoreRequested -> onDraftRestoreRequested()
            PostEditorIntent.DraftDiscardRequested -> onDraftDiscardRequested()
            is PostEditorIntent.QuoteRemoved -> onQuoteRemoved(intent.numreponse)
            is PostEditorIntent.QuoteMoved -> onQuoteMoved(intent.numreponse, intent.delta)
            PostEditorIntent.QuotesCleared -> _state.update { it.copy(quotes = emptyList()) }
            PostEditorIntent.CloseRequested -> onCloseRequested()
        }
    }

    /** #604 lot 3 — same card semantics as the quick-reply sheet (remove by numreponse). */
    private fun onQuoteRemoved(numreponse: Int) {
        _state.update { current ->
            current.copy(quotes = current.quotes.filterNot { it.numreponse == numreponse })
        }
    }

    /** #604 lot 3 — move the card one slot up ([delta] = -1) or down (+1) ; out-of-range = no-op. */
    private fun onQuoteMoved(numreponse: Int, delta: Int) {
        _state.update { current ->
            val index = current.quotes.indexOfFirst { it.numreponse == numreponse }
            val target = index + delta
            if (index < 0 || target < 0 || target > current.quotes.lastIndex) return@update current
            val reordered = current.quotes.toMutableList().apply {
                add(target, removeAt(index))
            }
            current.copy(quotes = reordered)
        }
    }

    /**
     * #405 — apply the cached body to the draft (caret at the end, like form hydration) and clear
     * the banner. Marks the draft hydrated so a late form fetch cannot overwrite the restored text.
     */
    private fun onDraftRestoreRequested() {
        val body = _state.value.restorableDraft ?: return
        _state.update { current ->
            current
                .withDraft(TextFieldValue(text = body, selection = TextRange(body.length)))
                .copy(restorableDraft = null, draftHydratedFromForm = true)
        }
        scheduleAutosave()
    }

    /** #405 — discard the cached draft : delete the row and clear the banner. */
    private fun onDraftDiscardRequested() {
        _state.update { it.copy(restorableDraft = null) }
        val key = draftKey ?: return
        viewModelScope.launch { draftStore.delete(draftOwner, key) }
    }

    /**
     * #441 — smiley picker (Standard + Wiki search), delegated to the shared
     * [SmileyPickerController] exactly like the MP composers (#387/#440) : the controller owns
     * open/dismiss/query, the 300 ms debounce and its race guards, and the #824
     * restore-search-on-reopen contract. This ViewModel keeps only the insertion
     * ([onSmileySelected] — a draft mutation, so it stays an MVI intent). Mixed wiring is
     * deliberate : the sheet talks to the controller directly for open/dismiss/query and goes
     * through [PostEditorIntent.SmileySelected] for insertion — do not re-route one into the
     * other for « consistency ».
     *
     * Lifecycle (réserve Codex #441) : this ViewModel's editorial context is frozen at
     * construction (`@Assisted request` ; one `PostEditorRoute` nav entry = one ViewModel,
     * cf. `RedfaceNavigation`), so the controller can never be re-armed for another post —
     * no targeted reset is needed beyond the ViewModel's own death.
     *
     * Failure logging stays a policy of THIS feature (the MP composers keep the controller's
     * no-op default) : class name only — never the query nor the userId (anti-leak test).
     */
    val smileyPicker = SmileyPickerController(
        scope = viewModelScope,
        searchWiki = smileyRepository::searchWiki,
        userId = { _state.value.userId },
        onSearchFailed = { error ->
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG_VM,
                "wiki smiley search failed: ${error::class.simpleName}",
            )
        },
    )

    private fun onSmileySelected(token: String) {
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
            if (withDraft.isPreviewVisible) {
                withDraft.copy(preview = previewParser.parsePreview(withDraft.draft.text))
            } else {
                withDraft
            }
        }
        // Close the picker on successful insertion ; the user can re-open it for another
        // smiley if they want to chain — #824 restores the search they just used. This
        // matches HFR web behaviour and keeps the sheet from squatting the screen between
        // two distant insertions.
        smileyPicker.dismiss()
        scheduleAutosave()
    }

    /**
     * #189 / #459 — a user-pasted image URL. The BBCode is shaped by the [EditorImageInsert]
     * preference ([imageInsertBbcodeOrNull] validates the http(s) scheme and applies full / linked /
     * reduced — a pasted URL has no reduced variant, so REDUCED degrades to LINKED). Reads the cached
     * [imageInsertMode] synchronously so the draft mutates in the same frame as the user action
     * (no suspend between the intent and the caret insertion — Codex PR2 review).
     */
    private fun onImageUrlInserted(url: String) {
        val bbcode = imageInsertBbcodeOrNull(fullUrl = url, mode = imageInsertMode) ?: return
        insertImageBbcodeAtCaret(bbcode, leadingNewline = false)
        scheduleAutosave()
    }

    /**
     * #459 — inserts an already-built image BBCode fragment at the caret. Same cursor contract as the
     * smiley path ([insertBbcodeToken] preserves the selection, the preview is refreshed), WITHOUT
     * surrounding spaces (an image is self-contained). [leadingNewline] prefixes a newline when the
     * caret is not already at a line start, so consecutive uploads land on their own lines instead of
     * running together.
     */
    private fun insertImageBbcodeAtCaret(bbcode: String, leadingNewline: Boolean) {
        _state.update { current ->
            val draft = current.draft
            val selection = draft.selection
            val caret = selection.start.coerceIn(0, draft.text.length)
            val needsNewline = leadingNewline && caret > 0 && draft.text[caret - 1] != '\n'
            val token = if (needsNewline) "\n$bbcode" else bbcode
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

    /**
     * #459 PR2 — pick→read→upload→insert. Reads the picked Uri's bytes (off-platform via
     * [ImageUploadReader]), uploads to the host of the current preference scoped to the active
     * [activeUserId] (lowercased pseudo), and on success inserts `[img]imageUrl[/img]` at the caret
     * via [insertImageUrlAtCaret] (same cursor contract as the smiley / URL paths). A typed
     * [UploadException] is mapped onto [UploadError] ; an anonymous client (no userId) is ignored —
     * the providers require an HFR session for the trace, and surfacing « connectez-vous » here
     * would duplicate the submit-time LoginRequired surface.
     */
    private fun onImagePicked(uri: String) = onImagesPicked(listOf(uri))

    /**
     * Multi-image upload — uploads the picked [uris] sequentially (one in-flight at a time, same
     * job/gate as the single path) and inserts `[img]url[/img]` at the caret for each success, in
     * pick order (each insertion advances the caret so the next lands after it). The batch stops at
     * the first failure: the already-inserted images stay and the typed [UploadError] is surfaced —
     * the user fixes the cause and re-picks the rest. A blank list, an anonymous client (no userId),
     * or an upload already in flight are ignored. [PostEditorState.uploadProgress] carries an « n/N »
     * counter while more than one image is in the batch (null for a single image).
     */
    private fun onImagesPicked(uris: List<String>) {
        val userId = activeUserId
        val targets = uris.filter { it.isNotBlank() }
        // One guard (ReturnCount): nothing in flight already, an authenticated owner, a non-empty pick.
        if (uploadJob?.isActive == true || userId == null || targets.isEmpty()) return
        val multiple = targets.size > 1
        _state.update {
            it.copy(
                isUploading = true,
                uploadError = null,
                uploadProgress = if (multiple) UploadProgress(completed = 0, total = targets.size) else null,
            )
        }
        uploadJob = viewModelScope.launch {
            // Snapshot the cached preference once at batch start so all N inserts use a consistent
            // mode even if the user flips the setting mid-upload. Reading the cached field (not the
            // flow) keeps the upload free of an extra suspend point.
            val mode = imageInsertMode
            var completed = 0
            for (uri in targets) {
                val outcome = runCatching {
                    val image = imageUploadReader.read(uri)
                    uploadRepository.uploadWithCurrentProvider(image, userId)
                }
                val uploaded = outcome.getOrElse { error ->
                    handleUploadFailure(error)
                    return@launch
                }
                // BBCode shaped by the preference (full / linked / reduced) ; the reduced URL is the
                // host's smaller variant when it exposes one, else the full URL.
                val bbcode = imageInsertBbcodeOrNull(
                    fullUrl = uploaded.imageUrl,
                    displayUrl = uploaded.resizedUrl ?: uploaded.imageUrl,
                    mode = mode,
                )
                if (bbcode != null) {
                    // Each image after the first lands on its own line (no more run-together uploads).
                    insertImageBbcodeAtCaret(bbcode, leadingNewline = completed > 0)
                    // Persist after EACH insert, not once at the end: a later image failing must not
                    // lose the images already inserted into the draft (Codex review #490).
                    scheduleAutosave()
                }
                completed += 1
                if (multiple) {
                    _state.update { it.copy(uploadProgress = UploadProgress(completed, targets.size)) }
                }
            }
            _state.update { it.copy(isUploading = false, uploadError = null, uploadProgress = null) }
        }
    }

    private fun handleUploadFailure(error: Throwable) {
        if (error is CancellationException) {
            _state.update { it.copy(isUploading = false, uploadProgress = null) }
            throw error
        }
        val mapped = when (error) {
            is UploadException.TooLarge -> UploadError.TooLarge
            is UploadException.UnsupportedType -> UploadError.UnsupportedType
            // #474 — keep Server and Malformed distinct so the banner names the host + HTTP code
            // (Server) vs. an unreadable host response (Malformed), instead of one vague message.
            is UploadException.Server -> UploadError.Server(code = error.code, providerId = error.providerId)
            is UploadException.Malformed -> UploadError.Malformed(providerId = error.providerId)
            // #474 — a config error (blank Imgur Client-ID) must point at Settings, not connectivity.
            is UploadException.Configuration -> UploadError.Configuration
            is UploadException.Network -> UploadError.Network
            else -> UploadError.Network
        }
        diagnostics.record(
            DiagnosticsLog.Level.WARN,
            LOG_TAG_VM,
            "image upload failed: ${error::class.simpleName} → ${mapped::class.simpleName}",
        )
        _state.update { it.copy(isUploading = false, uploadError = mapped, uploadProgress = null) }
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
        scheduleAutosave()
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
        scheduleAutosave()
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
        // #604 lot 3 (mockup P3) — with cards ON (or no citations at all) the open-time fetch is
        // the PLAIN reply form : citations live as cards in [PostEditorState.quotes] and their
        // [quotemsg] blocks are materialised fresh at submit (see [onSubmitClicked]), exactly
        // like the quick-reply sheet. This fetch only warms the hash and hydrates the per-post
        // options. Cards OFF goes through [loadReplyFormWithInlineQuotes] instead (#805).
        launchFormFetch { replyRepository.fetchReplyForm(context) }
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

    private fun PostEditorState.resolveHydratedDraft(
        form: ReplyForm,
        shouldHydrate: Boolean,
    ): TextFieldValue = if (shouldHydrate) {
        TextFieldValue(
            text = form.initialContent,
            // Place caret at the end so the user can type their content
            // right after the prefill — matches HFR's web behavior.
            selection = TextRange(form.initialContent.length),
        )
    } else {
        draft
    }

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
        val nextDraft = resolveHydratedDraft(form, shouldHydrate)
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
                        submitReply(context, form, snapshot, options)
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

    /**
     * #604 lot 3 (mockup P3) — the Reply POST, quotes-aware. Without cards the cached plain form
     * is submitted as before. With cards the [quotemsg] blocks are materialised FRESH here (never
     * the cached plain form : the quote form carries the prefills AND the hash the submit rides),
     * card order = citation order, the typed body follows the quotes ; a quotes-only reply keeps
     * no trailing blank lines (same pinned BBCode as the quick-reply sheet). A failure anywhere
     * leaves body AND cards untouched — the state only mutates on success. One divergence from
     * the sheet : [options] comes from the editor's user-editable toggles, not the form defaults.
     */
    private suspend fun submitReply(
        context: ReplyContext,
        form: ReplyForm,
        snapshot: PostEditorState,
        options: ReplyFormOptions,
    ): ReplySubmitResult {
        val quotes = snapshot.quotes
        if (quotes.isEmpty()) {
            return replyRepository.submitReply(
                context = context,
                form = form,
                bbcodeContent = snapshot.draft.text,
                options = options,
            )
        }
        val quoteContext = context.copy(
            quotedNumreponse = quotes.first().numreponse,
            quoteRef = null,
        )
        val quoteForm = quoteMaterializer.fetchFormWithQuotes(
            context = quoteContext,
            extraQuoteNumreponses = quotes.drop(1).map { it.numreponse },
        )
        val body = snapshot.draft.text
        return replyRepository.submitReply(
            context = quoteContext,
            form = quoteForm,
            bbcodeContent = if (body.isBlank()) {
                quoteForm.initialContent.trimEnd()
            } else {
                quoteForm.initialContent.trimEnd() + "\n\n" + body
            },
            options = options,
        )
    }

    private suspend fun handleSubmitOutcome(
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
                // #405 — the message reached HFR ; the cached draft is now obsolete. Cancel any
                // pending autosave first so a debounced save cannot resurrect the row after delete.
                // The delete is AWAITED inside the submit coroutine (not launched) so the immediate
                // nav pop driven by SubmitSucceeded cannot cancel it before the row is gone (Codex).
                autosaveJob?.cancel()
                draftKey?.let { key -> draftStore.delete(draftOwner, key) }
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
        // #604 lot 3 — always the PLAIN context : citations are cards, and [submitReply]
        // derives the quote context (first card's numreponse) when materialising.
        return ReplyContext(
            cat = snapshot.cat,
            subcat = subcat,
            topicId = topicId,
            page = page,
        )
    }

    /**
     * #459 PR2 — cancel any in-flight image upload when the editor is torn down. `viewModelScope`
     * is already cancelled by the lifecycle, so this is belt-and-braces (the read/upload is then a
     * no-op against a dead state) — but it makes the « one upload, no leak » contract explicit and
     * lets a future non-viewModelScope job stay covered.
     */
    override fun onCleared() {
        uploadJob?.cancel()
        super.onCleared()
    }

    @AssistedFactory
    interface Factory {
        fun create(request: PostEditorRequest): PostEditorViewModel
    }

    private companion object {
        // Distinct from the repository's "ReplyRepository" tag so the diagnostics
        // panel makes it obvious which layer recorded an entry.
        private const val LOG_TAG_VM = "PostEditorVM"

        // #405 — idle window after the last edit before the draft is persisted. Long enough to
        // coalesce a burst of keystrokes into a single Room write, short enough that an accidental
        // navigation / process death right after typing still keeps the content.
        private const val AUTOSAVE_DEBOUNCE_MS = 750L
    }
}
