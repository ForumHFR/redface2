package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.BbcodeValidation
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionAdapter
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionScenario
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.EditorWriteOwner
import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
import fr.forumhfr.redface2.core.domain.editor.SubmissionBodyMutation
import fr.forumhfr.redface2.core.domain.editor.SubmissionErrorProbe
import fr.forumhfr.redface2.core.domain.editor.SubmissionProbe
import fr.forumhfr.redface2.core.domain.editor.UnreadableInlineQuoteRequestException
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon
import fr.forumhfr.redface2.core.domain.preferences.AvatarAppearance
import fr.forumhfr.redface2.core.domain.preferences.CategoryBandStyle
import fr.forumhfr.redface2.core.domain.preferences.CategoryFlagFilter
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FlagGlyphStyle
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.domain.preferences.PlusLusIndicatorStyle
import fr.forumhfr.redface2.core.domain.preferences.PostImageCorners
import fr.forumhfr.redface2.core.domain.preferences.PostImageMaxWidth
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.SmileyPickerDecoration
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import fr.forumhfr.redface2.core.domain.preferences.ThemeColorPreferences
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.upload.UploadedImage
import fr.forumhfr.redface2.core.domain.upload.UploadedImageRecord
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.domain.write.truncateQuote
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyUsageEntry
import fr.forumhfr.redface2.core.model.SmileyWikiKeywordsResult
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.EditorPreviewKey
import fr.forumhfr.redface2.core.model.editor.ImagePickerContract
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.editor.WritingSurfacePreset
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorErrorAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventCollector
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorEventProjection
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorText
import fr.forumhfr.redface2.core.ui.editor.OperationRef
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import fr.forumhfr.redface2.core.ui.editor.UploadProgress
import fr.forumhfr.redface2.core.ui.editor.toEditorError
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // One class per ViewModel keeps every code path co-located with its
// dispatcher / fake repositories — splitting per phase (#145 reply / #146 quote / #147 edit /
// #11 smiley) would shred the shared `Fake*Repository` test doubles into duplicated copies.
class PostEditorViewModelTest {

    private val viewModels = mutableListOf<PostEditorViewModel>()
    private val previewParser = FakePreviewParser()
    private val replyRepository = FakeReplyRepository()
    private val editPostRepository = FakeEditPostRepository()
    private val smileyRepository = FakeSmileyRepository()
    private var draftStore: VersionedEditorDraftStore = FakeEditorDraftStore()
    private val dispatcher = UnconfinedTestDispatcher()
    private var submissionClock = DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L }
    private val favoriteRepository = FakeHfrFavoriteSmileyRepository()
    private val localCollections = FakeSmileyLocalCollections()
    private val uploadRepository = FakeUploadRepository()
    private val imageUploadReader = FakeImageUploadReader()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun registerViewModel(vm: PostEditorViewModel) {
        viewModels += vm
        syncBody()
    }

    private fun syncBody() {
        Snapshot.sendApplyNotifications()
        dispatcher.scheduler.runCurrent()
    }

    /** Drain async form/quote/upload edits as well as their descending snapshot mirror. */
    private fun settleBody() {
        syncBody()
        dispatcher.scheduler.advanceUntilIdle()
        syncBody()
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun PostEditorViewModel.bodyValue() =
        TextFieldValue(bodyFieldState.text.toString(), bodyFieldState.selection, bodyFieldState.composition)

    /** Synthetic input uses the production guard; PostEditorInputTest covers actual BTF2 input. */
    private fun PostEditorViewModel.editBody(value: TextFieldValue, advanceMirror: Boolean = true) {
        bodyFieldState.edit {
            if (asCharSequence().toString() != value.text) replace(0, length, value.text)
            selection = value.selection
            with(bodyInputTransformation) { transformInput() }
        }
        if (advanceMirror) syncBody()
    }

    private fun PostEditorViewModel.dispatch(intent: PostEditorIntent) {
        submit(intent)
        syncBody()
    }

    @Suppress("UNCHECKED_CAST") // Inject an unknown seed to probe independent send and callback guards.
    private fun PostEditorViewModel.forgetInitialBody() {
        val state = PostEditorViewModel::class.java.getDeclaredField("_state").run {
            isAccessible = true
            get(this@forgetInitialBody) as MutableStateFlow<PostEditorState>
        }
        state.value = state.value.copy(draftHydratedFromForm = false)
    }

    @Test
    fun submitAndConfirmUseLiveBodyBeforeMirrorNotification() = runTest {
        val prefs = FakeUserPreferencesRepository(confirmBeforePosting = true)
        replyRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
        val vm = newReplyViewModel(userPreferencesRepository = prefs)
        vm.editBody(TextFieldValue("first", TextRange(5)), advanceMirror = false)
        assertEquals("", vm.state.value.bodyText)
        vm.submit(PostEditorIntent.SubmitClicked())
        val obsolete = requireNotNull(vm.state.value.armToken)
        vm.editBody(TextFieldValue("latest", TextRange(6)), advanceMirror = false)
        assertNull(vm.state.value.armToken)
        vm.submit(PostEditorIntent.SubmitConfirmed(obsolete))
        assertEquals(0, replyRepository.submitCalls)
        vm.submit(PostEditorIntent.SubmitClicked())
        val fresh = requireNotNull(vm.state.value.armToken)
        testScheduler.advanceTimeBy(401)
        vm.submit(PostEditorIntent.SubmitConfirmed(fresh))
        testScheduler.runCurrent()
        assertEquals("latest", replyRepository.lastSubmittedBbcode)
        assertEquals("latest", draftStore.saved[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)]?.body)
    }

    @Test
    fun blankLiveBodyCannotSubmitUsingNonblankMirror() = runTest {
        val vm = newEditViewModel()
        assertTrue(vm.state.value.bodyText.isNotBlank())
        vm.editBody(TextFieldValue(""), advanceMirror = false)
        assertTrue(vm.state.value.bodyText.isNotBlank())
        vm.submit(PostEditorIntent.SubmitClicked())
        vm.submit(PostEditorIntent.SubmitConfirmed(vm.state.value.armToken))
        assertEquals(0, editPostRepository.submitCalls)
    }

    @Test
    fun replyFormHydrationReadsLiveBodyBeforeMirrorNotification() = runTest {
        val gate = CompletableDeferred<Unit>()
        replyRepository.formGate = gate
        replyRepository.formResult = Result.success(authenticatedForm("server prefill"))
        val vm = newReplyViewModel()
        vm.editBody(TextFieldValue("typed during GET", TextRange(4)), advanceMirror = false)
        assertEquals("", vm.state.value.bodyText)
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals("typed during GET", vm.bodyFieldState.text.toString())
        assertEquals(TextRange(4), vm.bodyFieldState.selection)
        assertFalse(vm.state.value.draftHydratedFromForm)
    }

    @Test
    fun autosaveReadsLiveBodyAtDeadlineWithoutSavingInitialEmptyField() = runTest {
        val vm = newReplyViewModel()
        testScheduler.advanceTimeBy(800)
        testScheduler.runCurrent()
        assertEquals(0, draftStore.saveCount)
        vm.editBody(TextFieldValue("first"))
        testScheduler.advanceTimeBy(749)
        vm.editBody(TextFieldValue("latest"), advanceMirror = false)
        assertEquals("first", vm.state.value.bodyText)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals("latest", draftStore.saved[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)]?.body)
    }

    @Test
    fun closeFlushesLiveBodyBeforeMirrorNotification() = runTest {
        val vm = newReplyViewModel()
        vm.editBody(TextFieldValue("last keystroke"), advanceMirror = false)
        assertEquals("", vm.state.value.bodyText)
        vm.submit(PostEditorIntent.CloseRequested)
        testScheduler.runCurrent()
        assertEquals("last keystroke", draftStore.saved[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)]?.body)
    }

    @Test
    fun selectionDisarmsWithoutAutosaveOrBodyEdit() = runTest {
        val vm = newEditViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        vm.submit(PostEditorIntent.SubmitClicked())
        assertNotNull(vm.state.value.armToken)
        val saves = draftStore.saveCount
        vm.editBody(vm.bodyValue().copy(selection = TextRange(1, 3)))
        testScheduler.advanceUntilIdle()
        assertNull(vm.state.value.armToken)
        assertEquals(saves, draftStore.saveCount)
        assertEquals(TextRange(1, 3), vm.bodyFieldState.selection)
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun inlineQuotesAcceptAnotherPortionAtInsertionBeforeMirrorNotification() = runTest {
        val gate = CompletableDeferred<Unit>()
        replyRepository.formGate = gate
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm("[quotemsg=101]server first[/quotemsg]")),
            202 to Result.success(authenticatedForm("[quotemsg=202]server second[/quotemsg]")),
        )
        val vm = newReplyViewModel(
            initialQuotes = listOf(card(101), card(202)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        val typed = TextFieldValue("[quotemsg=101]typed first[/quotemsg]\nmy text", TextRange(7))
        vm.editBody(typed, advanceMirror = false)
        assertEquals("", vm.state.value.bodyText)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        val body = vm.bodyFieldState.text.toString()
        assertEquals(2, Regex("\\[quotemsg=101]").findAll(body).count())
        assertEquals(1, Regex("\\[quotemsg=202]").findAll(body).count())
        assertTrue(body.startsWith("[quotemsg=101]server first[/quotemsg]"))
        assertTrue(body.endsWith(typed.text))
        assertEquals(listOf(101, 202), replyRepository.fetchedContexts.map { it.quotedNumreponse })
        assertFalse(vm.chromeEvents.state.events.any {
            it.content == EditorEventContent.Information(EditorText(R.string.editor_quote_already_delivered))
        })
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertEquals(body, draftStore.saved[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)]?.body)
        assertEquals(0, replyRepository.submitCalls)
        vm.bodyFieldState.undoState.undo()
        assertEquals(typed, vm.bodyValue())
    }

    @Test
    fun `ED52 inline delivers A once on replay then accepts B from the same post`() = runTest {
        val fullQuote = "[quotemsg=101]${longQuoteBody()}[/quotemsg]"
        val portionA = truncateQuote(fullQuote)
        assertTrue(portionA != fullQuote)
        replyRepository.formResult = Result.success(authenticatedForm(fullQuote))
        val notice = EditorEventContent.Information(EditorText(R.string.editor_quote_already_delivered))

        fun deliver(truncate: Boolean): PostEditorViewModel {
            val vm = newReplyViewModel(initialQuotes = listOf(card(101).copy(truncate = truncate)),
                userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
            settleBody()
            if (vm.state.value.restorableDraft != null) {
                vm.dispatch(PostEditorIntent.DraftRestoreRequested)
                settleBody()
            }
            return vm
        }

        val first = deliver(truncate = true)
        assertEquals("$portionA\n", first.bodyFieldState.text.toString())
        assertEquals(0, first.chromeEvents.state.events.count { it.content == notice })
        first.viewModelScope.cancel()

        val replay = deliver(truncate = true)
        assertEquals("$portionA\n", replay.bodyFieldState.text.toString())
        assertEquals(1, replay.chromeEvents.state.events.count { it.content == notice })
        replay.viewModelScope.cancel()

        val second = deliver(truncate = false)
        val expected = "$fullQuote\n\n$portionA\n"
        assertEquals(expected, second.bodyFieldState.text.toString())
        assertEquals(0, second.chromeEvents.state.events.count { it.content == notice })
        assertEquals(listOf(101, 101, 101), replyRepository.fetchedContexts.map { it.quotedNumreponse })
        val persisted = checkNotNull(draftStore.saved[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)])
        assertEquals(expected, persisted.body)
        assertNull(persisted.pendingInlineQuoteRequest)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ED52 duplicate notice waits for durable delivery and does not follow typing`() = runTest {
        val form = CompletableDeferred<Unit>()
        replyRepository.formGate = form
        val quote = "[quotemsg=101]quote[/quotemsg]"
        replyRepository.formResult = Result.success(authenticatedForm(quote))
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        vm.editBody(TextFieldValue(quote, TextRange(7, 2)))
        val write = CompletableDeferred<Unit>()
        draftStore.saveReturnGate = write
        form.complete(Unit)
        syncBody()
        assertFalse(vm.chromeEvents.state.events.any { it.content is EditorEventContent.Information })
        write.complete(Unit)
        settleBody()
        assertEquals(quote, vm.bodyFieldState.text.toString())
        assertEquals(TextRange(7, 2), vm.bodyFieldState.selection)
        assertNull(draftStore.saved[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)]?.pendingInlineQuoteRequest)
        val notices = vm.chromeEvents.state.events.filter { it.content is EditorEventContent.Information }
        assertEquals(1, notices.size)
        vm.editBody(TextFieldValue("$quote\nRéponse"))
        settleBody()
        assertEquals(notices, vm.chromeEvents.state.events.filter { it.content is EditorEventContent.Information })
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ED52 duplicate notice survives a CAS conflict and waits for the marker replay commit`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val quote = "[quotemsg=101]same portion[/quotemsg]"
        val notice = EditorEventContent.Information(EditorText(R.string.editor_quote_already_delivered))
        val get = CompletableDeferred<Unit>()
        replyRepository.formGate = get
        replyRepository.formResult = Result.success(authenticatedForm(quote))
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        vm.editBody(TextFieldValue(quote, TextRange(7, 2)))
        val other = EditorDraftSession(draftStore, key)
        other.load()
        assertTrue(other.save(EditorDraftStore.Draft(quote)) is EditorDraftStore.StoreWriteResult.Written)
        get.complete(Unit)
        syncBody()
        assertTrue(vm.state.value.quoteStorageUnavailable)
        assertNotNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertEquals(0, vm.chromeEvents.state.events.count { it.content == notice })

        val write = CompletableDeferred<Unit>()
        draftStore.saveReturnGate = write
        vm.dispatch(PostEditorIntent.ReloadRequested)
        assertTrue(vm.state.value.isLoadingForm)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertEquals(quote, draftStore.saved[key]?.body)
        assertEquals(listOf(101), replyRepository.fetchedContexts.map { it.quotedNumreponse })
        assertEquals(0, vm.chromeEvents.state.events.count { it.content == notice })
        write.complete(Unit)
        settleBody()
        assertEquals(quote, vm.bodyFieldState.text.toString())
        assertEquals(TextRange(7, 2), vm.bodyFieldState.selection)
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertFalse(vm.state.value.quoteStorageUnavailable)
        assertEquals(1, vm.chromeEvents.state.events.count { it.content == notice })
        vm.editBody(TextFieldValue("$quote\nRéponse"))
        settleBody()
        assertEquals(1, vm.chromeEvents.state.events.count { it.content == notice })
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ED52 restore retains duplicate blocks verbatim`() = runTest {
        val quote = "[quotemsg=101]same portion[/quotemsg]"
        val restored = "$quote\n\n$quote\nRéponse"
        draftStore.preload(EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID), EditorDraftStore.Draft(restored))
        val vm = newReplyViewModel()
        vm.dispatch(PostEditorIntent.DraftRestoreRequested)
        settleBody()
        assertEquals(restored, vm.bodyFieldState.text.toString())
        assertFalse(vm.chromeEvents.state.events.any { it.content is EditorEventContent.Information })
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ED52 submit suppresses materialised cards already present inline`() = assertCardDelivery("same portion")

    @Test
    fun `ED52 submit keeps another portion once even when two cards materialise it`() =
        assertCardDelivery("other portion")

    private fun assertCardDelivery(portion: String) = runTest {
        val inline = "[quotemsg=101]same portion[/quotemsg]\nRéponse"
        val materialised = "[quotemsg=101,1,9]$portion[/quotemsg]"
        replyRepository.formResultsByNumrep = mapOf(101 to Result.success(authenticatedForm(materialised)))
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val first = card(101)
        val vm = newReplyViewModel(initialQuotes = listOf(first, first.copy(excerpt = "another preview")))
        settleBody()
        vm.editBody(TextFieldValue(inline))
        vm.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()
        assertEquals(1, replyRepository.submitCalls)
        assertEquals(if (portion == "same portion") inline else "$materialised\n\n$inline",
            replyRepository.lastSubmittedBbcode)
        assertEquals(inline, vm.bodyFieldState.text.toString())
    }

    @Test
    fun `ED51 ED52 cards keep portions ordered and exact actions mutate only the selected card`() = runTest {
        val first = card(101)
        val portion = first.copy(excerpt = "another portion")
        val vm = newReplyViewModel(initialQuotes = listOf(first, first, portion, card(202)))
        assertEquals(listOf(first, portion, card(202)), vm.state.value.quotes)
        vm.dispatch(PostEditorIntent.QuoteMoved(101, -1, portion))
        assertEquals(listOf(portion, first, card(202)), vm.state.value.quotes)
        vm.dispatch(PostEditorIntent.QuoteRemoved(101, portion))
        assertEquals(listOf(first, card(202)), vm.state.value.quotes)
        assertEquals(listOf(EditorEventContent.Information(EditorText(R.string.editor_quote_already_delivered))),
            vm.chromeEvents.state.events.map { it.content }.filterIsInstance<EditorEventContent.Information>())
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ED52 Room reconciliation does not replay an already consumed card handoff`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val pending = PendingInlineQuoteRequest(SAMPLE_CAT, SAMPLE_TOPIC_ID,
            listOf(PendingInlineQuoteRequest.Quote(101, SAMPLE_PAGE)))
        draftStore.preload(key, EditorDraftStore.Draft("", pendingInlineQuoteRequest = pending))
        val get = CompletableDeferred<Unit>()
        replyRepository.formGate = get
        replyRepository.formResult = Result.success(authenticatedForm("[quotemsg=101]quote[/quotemsg]"))
        val vm = newReplyViewModel(initialQuotes = listOf(card(202), card(202), card(303)))
        vm.dispatch(PostEditorIntent.QuoteRemoved(202))
        val other = EditorDraftSession(draftStore, key)
        other.load()
        other.save(EditorDraftStore.Draft("concurrent body"))
        get.complete(Unit)
        syncBody()
        assertTrue(vm.state.value.quoteStorageUnavailable)
        vm.dispatch(PostEditorIntent.ReloadRequested)
        settleBody()
        assertEquals(listOf(card(303)), vm.state.value.quotes)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun inlineQuoteReceivedDuringUploadIsInsertedOnceAfterUpload() = runTest {
        val formGate = CompletableDeferred<Unit>()
        val uploadGate = CompletableDeferred<Unit>()
        replyRepository.formGate = formGate
        uploadRepository.uploadGate = uploadGate
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm("[quotemsg=101]quote[/quotemsg]")),
        )
        val vm = newReplyViewModel(
            initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        vm.submit(PostEditorIntent.ImagesPicked(listOf("content://pick/1")))
        assertTrue(vm.state.value.isUploading)
        formGate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals("", vm.bodyFieldState.text.toString())
        assertEquals(listOf(101), vm.state.value.pendingQuotes.map { it.numreponse })
        uploadGate.complete(Unit)
        testScheduler.advanceUntilIdle()
        val body = vm.bodyFieldState.text.toString()
        assertEquals(1, Regex("\\[quotemsg=101]").findAll(body).count())
        assertEquals(1, Regex("\\[img]").findAll(body).count())
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun inlineQuoteReloadStartedDuringUploadFetchesThenInsertsOnceAfterUpload() = runTest {
        val uploadGate = CompletableDeferred<Unit>()
        uploadRepository.uploadGate = uploadGate
        replyRepository.formResultsByNumrep = mapOf(101 to Result.failure(IOException("offline")))
        val vm = newReplyViewModel(
            initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()
        assertEquals(1, vm.state.value.quoteFetchFailures)
        vm.submit(PostEditorIntent.ImagesPicked(listOf("content://pick/1")))
        assertTrue(vm.state.value.isUploading)
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm("[quotemsg=101]quote[/quotemsg]")),
        )
        val fetchesBeforeReload = replyRepository.formFetches
        vm.dispatch(PostEditorIntent.ReloadRequested)
        syncBody()
        assertEquals("the GET is not dropped by the upload", fetchesBeforeReload + 1, replyRepository.formFetches)
        assertEquals("", vm.bodyFieldState.text.toString())
        assertEquals(listOf(101), vm.state.value.pendingQuotes.map { it.numreponse })
        uploadGate.complete(Unit)
        settleBody()
        val body = vm.bodyFieldState.text.toString()
        assertEquals(1, Regex("\\[quotemsg=101]").findAll(body).count())
        assertEquals(1, Regex("\\[img]").findAll(body).count())
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun selectionBeforeLateEditHydrationIsNotATypedEditSoHydrationIsNotUndoable() = runTest {
        assertFalse(lateEditHydrationIsUndoable(typedThenErased = false))
    }

    @Test
    fun typingThenErasingBeforeLateEditHydrationIsRejectedAndAddsNoHistory() = runTest {
        assertFalse(lateEditHydrationIsUndoable(typedThenErased = true))
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun lateEditHydrationIsUndoable(typedThenErased: Boolean): Boolean {
        val gate = CompletableDeferred<Unit>()
        editPostRepository.formGate = gate
        val vm = newEditViewModel()
        syncBody()
        if (typedThenErased) {
            vm.editBody(TextFieldValue("x", TextRange(1)))
            vm.editBody(TextFieldValue(""))
        } else {
            vm.editBody(TextFieldValue("", TextRange(0)))
        }
        gate.complete(Unit)
        settleBody()
        assertEquals("existing post body", vm.bodyFieldState.text.toString())
        return vm.bodyFieldState.undoState.canUndo
    }

    @Test
    fun editUploadIsRejectedUntilHydrationThenKeepsServerBody() = runTest {
        val formGate = CompletableDeferred<Unit>()
        val uploadGate = CompletableDeferred<Unit>()
        editPostRepository.formGate = formGate
        uploadRepository.uploadGate = uploadGate
        val vm = newEditViewModel()
        syncBody()
        vm.submit(PostEditorIntent.ImagesPicked(listOf("content://pick/1")))
        assertFalse(vm.state.value.isUploading)
        assertEquals(0, uploadRepository.uploadCalls)
        formGate.complete(Unit)
        syncBody()
        assertEquals("existing post body", vm.bodyFieldState.text.toString())
        assertTrue(vm.state.value.draftHydratedFromForm)
        vm.submit(PostEditorIntent.ImagesPicked(listOf("content://pick/1")))
        assertTrue(vm.state.value.isUploading)
        uploadGate.complete(Unit)
        settleBody()
        val body = vm.bodyFieldState.text.toString()
        assertTrue(body.contains("existing post body"))
        assertEquals(1, Regex("\\[img]").findAll(body).count())
        assertEquals(0, editPostRepository.submitCalls)
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun toolbarSmileyAndImageInsertionsUndoOnceAndRetainEarlierEdit() = runTest {
        val vm = newReplyViewModel()
        for (intent in listOf(
            PostEditorIntent.ToolbarActionClicked(BbcodeAction.Bold),
            PostEditorIntent.ToolbarActionClicked(BbcodeAction.Quote),
            PostEditorIntent.SmileySelected(":jap:"),
            PostEditorIntent.ImageUrlInserted("https://example.test/image.png"),
        )) {
            vm.editBody(TextFieldValue("earlier", TextRange(3)))
            val earlier = vm.bodyValue()
            vm.editBody(TextFieldValue("before", TextRange(1, 4)))
            val before = vm.bodyValue()
            vm.submit(intent)
            assertTrue(before.text != vm.bodyFieldState.text.toString())
            vm.bodyFieldState.undoState.undo()
            assertEquals(before, vm.bodyValue())
            vm.bodyFieldState.undoState.undo()
            assertEquals(earlier, vm.bodyValue())
            vm.bodyFieldState.undoState.redo()
            assertEquals(before, vm.bodyValue())
            vm.bodyFieldState.undoState.redo()
            assertTrue(before.text != vm.bodyFieldState.text.toString())
        }
    }

    @Test
    fun uploadBlocksBodyIntentsAndSmileyDismissesOnlyAfterAcceptedInsertion() = runTest {
        val gate = CompletableDeferred<Unit>()
        uploadRepository.uploadGate = gate
        val vm = newReplyViewModel()
        vm.editBody(TextFieldValue("body", TextRange(2)))
        vm.smileyPicker.open()
        vm.submit(PostEditorIntent.ImagesPicked(listOf("content://pick/1")))
        val before = vm.bodyValue()
        for (intent in listOf(
            PostEditorIntent.ToolbarActionClicked(BbcodeAction.Bold),
            PostEditorIntent.ImageUrlInserted("https://example.test/rejected.png"),
            PostEditorIntent.SmileySelected(":jap:"),
        )) {
            vm.submit(intent)
            assertEquals(before, vm.bodyValue())
        }
        assertTrue(vm.smileyPicker.state.value is SmileyPickerState.Open)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.bodyFieldState.text.toString().contains("[img]"))
        vm.submit(PostEditorIntent.SmileySelected(":jap:"))
        assertTrue(vm.bodyFieldState.text.toString().contains(":jap:"))
        assertEquals(SmileyPickerState.Hidden, vm.smileyPicker.state.value)
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun lateReplyHydrationPreservesHistoryWhenTypingWasErasedBeforeFormArrived() = runTest {
        val gate = CompletableDeferred<Unit>()
        replyRepository.formGate = gate
        replyRepository.formResult = Result.success(authenticatedForm("server prefill"))
        val vm = newReplyViewModel()
        vm.editBody(TextFieldValue("typed before form", TextRange(17)))
        vm.editBody(TextFieldValue(""))
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals("server prefill", vm.bodyFieldState.text.toString())
        assertTrue(vm.bodyFieldState.undoState.canUndo)
        vm.bodyFieldState.undoState.undo()
        assertEquals("", vm.bodyFieldState.text.toString())
        vm.bodyFieldState.undoState.undo()
        assertEquals("typed before form", vm.bodyFieldState.text.toString())
    }

    @Test
    fun previewDiscardsResultAfterEdit() = previewDiscardsStaleResult(hide = false)

    @Test
    fun previewDiscardsResultAfterHide() = previewDiscardsStaleResult(hide = true)

    private fun previewDiscardsStaleResult(hide: Boolean) = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val parserDispatcher = executor.asCoroutineDispatcher()
        val oldPreview = previewParser.contentFor("old")
        val newPreview = previewParser.contentFor("new")
        val parser = BbcodePreviewParser { text ->
            if (text == "old") {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "preview barrier timed out" }
                oldPreview
            } else {
                newPreview
            }
        }
        val vm = newReplyViewModel(previewParser = parser, defaultDispatcher = parserDispatcher)
        val published = mutableListOf<PostContent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.state.collect { published += it.preview }
        }
        try {
            vm.editBody(TextFieldValue("old"))
            vm.submit(PostEditorIntent.TogglePreview)
            testScheduler.runCurrent()
            assertTrue("parse must run off Main", started.await(5, TimeUnit.SECONDS))
            if (hide) {
                vm.submit(PostEditorIntent.TogglePreview)
            } else {
                vm.editBody(TextFieldValue("new"), advanceMirror = false)
            }
            release.countDown()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            testScheduler.runCurrent()
            assertFalse("old parse must never publish", oldPreview in published)
            if (hide) {
                assertFalse(vm.state.value.isPreviewVisible)
                assertEquals(PostContent(emptyList()), vm.state.value.preview)
            } else {
                syncBody()
                executor.submit {}.get(5, TimeUnit.SECONDS)
                testScheduler.runCurrent()
                assertEquals(newPreview, vm.state.value.preview)
            }
        } finally {
            release.countDown()
            vm.viewModelScope.cancel()
            parserDispatcher.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `Astra1 failed dormant ignore then durable autosave keeps uncertainty and both decisions`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val token = (draftStore.beginAttempt("tester", key, EditorDraftStore.Draft("uncertain body"), 0)
            as EditorDraftStore.BeginAttemptResult.Started).token
        draftStore.finishAttempt(token, EditorDraftStore.AttemptOutcome.UNCERTAIN)
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        syncBody()
        assertEquals(0, replyRepository.formFetches) // Includes the plain form GET.
        draftStore.saveFailure = IOException("disk")
        vm.dispatch(PostEditorIntent.PendingQuoteIgnored)
        syncBody()
        assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
        assertTrue(vm.state.value.hasDormantQuote)
        assertTrue(vm.state.value.canIgnorePendingQuote)
        draftStore.saveFailure = null
        vm.editBody(TextFieldValue("continued uncertainty"))
        settleBody()
        assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
        assertTrue(vm.state.value.hasDormantQuote)
        assertTrue(vm.state.value.canIgnorePendingQuote)
        assertEquals(token, draftStore.entries[key]?.attempt?.token)
        assertEquals(0, replyRepository.formFetches)
        assertEquals(0, replyRepository.submitCalls)
        val adapter = PostEditorChromeAdapter({ vm.state.value }, vm.bodyFieldState, vm.chromeEvents,
            { vm.dispatch(it) }, {}, {})
        val event = vm.chromeEvents.state.visibleEvent!!
        adapter.barActions.onEvent(event.key, EditorEventAction.Details)
        adapter.ignoreQuote(event.key)
        settleBody()
        assertEquals(1, replyRepository.formFetches) // One plain form GET, without the quote.
        assertNull(draftStore.entries[key]?.draft?.pendingInlineQuoteRequest)
        assertEquals("continued uncertainty", vm.bodyFieldState.text.toString())
        assertFalse(vm.state.value.hasDormantQuote)
        assertTrue(adapter.state.sendActivatable)
        assertEquals(token, draftStore.entries[key]?.attempt?.token)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `Astra2 M2 post conflict reloads Room and reconciles before a new delivery CAS`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        replyRepository.formResult = Result.success(authenticatedForm("[quotemsg=101]quote[/quotemsg]"))
        val get = CompletableDeferred<Unit>()
        replyRepository.formGate = get
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        syncBody()
        val other = EditorDraftSession(draftStore, key)
        other.load()
        other.save(EditorDraftStore.Draft("concurrent body"))
        get.complete(Unit)
        syncBody()
        assertTrue(vm.state.value.quoteStorageUnavailable)
        assertFalse(vm.state.value.canSubmit)
        vm.dispatch(PostEditorIntent.ReloadRequested)
        syncBody()
        assertEquals("concurrent body", vm.state.value.restorableDraft)
        assertEquals(1, replyRepository.formFetches)
        vm.dispatch(PostEditorIntent.DraftRestoreRequested)
        settleBody()
        val persisted = checkNotNull(draftStore.saved[key])
        assertTrue(persisted.body.contains("concurrent body"))
        assertEquals(1, persisted.body.split("[quotemsg=101]").size - 1)
        assertNull(persisted.pendingInlineQuoteRequest)
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ED52 conflicted delivery retries only its CAS after the user trims quote markup`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val quote = "[quotemsg=101]kept excerpt and removed tail[/quotemsg]"
        replyRepository.formResult = Result.success(authenticatedForm(quote))
        val get = CompletableDeferred<Unit>()
        replyRepository.formGate = get
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        val other = EditorDraftSession(draftStore, key)
        other.load()
        assertTrue(other.save(EditorDraftStore.Draft("")) is EditorDraftStore.StoreWriteResult.Written)
        get.complete(Unit)
        syncBody()
        assertEquals("$quote\n", vm.bodyFieldState.text.toString())
        assertTrue(vm.state.value.quoteStorageUnavailable)
        assertEquals("", draftStore.saved[key]?.body)
        assertNotNull(draftStore.saved[key]?.pendingInlineQuoteRequest)

        // Remove the markup as well as the tail; the pending request was already delivered in memory.
        val trimmed = TextFieldValue("kept excerpt", TextRange(12, 4))
        vm.editBody(trimmed)
        vm.dispatch(PostEditorIntent.ReloadRequested)
        settleBody()
        assertEquals(trimmed, vm.bodyValue())
        assertEquals(listOf(101), replyRepository.fetchedContexts.map { it.quotedNumreponse })
        assertEquals(trimmed.text, draftStore.saved[key]?.body)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertFalse(vm.state.value.quoteStorageUnavailable)
        assertFalse(vm.chromeEvents.state.events.any {
            it.content == EditorEventContent.Information(EditorText(R.string.editor_quote_already_delivered))
        })
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ED52 rereading a different request forgets the marker before an equal request returns`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val quote = "[quotemsg=101]portion A[/quotemsg]"
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(quote)),
            202 to Result.failure(IOException("offline")),
        )
        val get = CompletableDeferred<Unit>()
        replyRepository.formGate = get
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        val other = EditorDraftSession(draftStore, key)
        other.load()
        val original = checkNotNull(other.pendingInlineQuoteRequest)
        assertTrue(other.consumeInlineQuoteRequest(original) is EditorDraftStore.StoreWriteResult.Written)
        val different = PendingInlineQuoteRequest(SAMPLE_CAT, SAMPLE_TOPIC_ID,
            listOf(PendingInlineQuoteRequest.Quote(202, SAMPLE_PAGE)))
        assertTrue(other.enqueueInlineQuoteRequest(different) is EditorDraftStore.StoreWriteResult.Written)
        get.complete(Unit)
        syncBody()
        assertTrue(vm.state.value.quoteStorageUnavailable)
        assertEquals("$quote\n", vm.bodyFieldState.text.toString())

        // Remove A in memory, then reread B. Its failed GET must not retain A's delivery marker.
        vm.editBody(TextFieldValue(""))
        vm.dispatch(PostEditorIntent.ReloadRequested)
        assertEquals(listOf(202), vm.state.value.pendingQuotes.map { it.numreponse })
        assertEquals(EditorErrors.quoteFetchFailed(inline = true), vm.state.value.submitError)
        vm.dispatch(PostEditorIntent.ReloadRequested)
        assertTrue(vm.state.value.canIgnorePendingQuote)
        assertEquals(listOf(101, 202, 202), replyRepository.fetchedContexts.map { it.quotedNumreponse })

        // A returns as a new value-equal request before Ignore's CAS, causing a second conflict.
        assertTrue(other.consumeInlineQuoteRequest(different) is EditorDraftStore.StoreWriteResult.Written)
        val returned = PendingInlineQuoteRequest(original.cat, original.topicId, original.quotes)
        assertEquals(original, returned)
        assertTrue(other.enqueueInlineQuoteRequest(returned) is EditorDraftStore.StoreWriteResult.Written)
        vm.dispatch(PostEditorIntent.PendingQuoteIgnored)
        assertTrue(vm.state.value.quoteStorageUnavailable)
        assertEquals("", vm.bodyFieldState.text.toString())
        assertEquals(returned, draftStore.saved[key]?.pendingInlineQuoteRequest)
        vm.dispatch(PostEditorIntent.ReloadRequested)
        settleBody()
        assertEquals(listOf(101, 202, 202, 101), replyRepository.fetchedContexts.map { it.quotedNumreponse })
        assertEquals("$quote\n", vm.bodyFieldState.text.toString())
        assertEquals("$quote\n", draftStore.saved[key]?.body)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertFalse(vm.state.value.quoteStorageUnavailable)
        assertFalse(vm.chromeEvents.state.events.any { it.content is EditorEventContent.Information })
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `Q consumed elsewhere during GET then Conflict and Reload clears UI request and unlocks send`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        replyRepository.formResult = Result.success(authenticatedForm("[quotemsg=101]quote[/quotemsg]"))
        val get = CompletableDeferred<Unit>()
        replyRepository.formGate = get
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        vm.editBody(TextFieldValue("typed during GET"))
        val other = EditorDraftSession(draftStore, key)
        other.load()
        assertTrue(
            other.consumeInlineQuoteRequest(checkNotNull(other.pendingInlineQuoteRequest))
                is EditorDraftStore.StoreWriteResult.Written,
        )
        get.complete(Unit)
        syncBody()
        val current = vm.bodyValue()
        assertEquals("[quotemsg=101]quote[/quotemsg]\n\ntyped during GET", current.text)
        assertTrue(vm.state.value.quoteStorageUnavailable)
        assertTrue(vm.state.value.pendingQuotes.isNotEmpty())
        assertFalse(vm.state.value.canSubmit)
        vm.dispatch(PostEditorIntent.ReloadRequested)
        syncBody()
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertEquals(current, vm.bodyValue())
        assertFalse(vm.state.value.quoteStorageUnavailable)
        assertEquals(0, vm.state.value.quoteFetchFailures)
        assertTrue(vm.state.value.canSubmit)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `Reload after external unreadable request repair clears obsolete quote error and preserves text`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val pending = PendingInlineQuoteRequest(SAMPLE_CAT, SAMPLE_TOPIC_ID,
            listOf(PendingInlineQuoteRequest.Quote(101, 1)))
        draftStore.preload(key, EditorDraftStore.Draft("", pendingInlineQuoteRequest = pending))
        draftStore.loadFailure = UnreadableInlineQuoteRequestException()
        val vm = newReplyViewModel()
        vm.editBody(TextFieldValue("keep this text"))
        assertEquals(EditorErrors.unreadableQuoteRequest, vm.state.value.submitError)
        draftStore.loadFailure = null
        val other = EditorDraftSession(draftStore, key)
        other.load()
        other.consumeInlineQuoteRequest(pending)
        vm.dispatch(PostEditorIntent.ReloadRequested)
        syncBody()
        assertNull(vm.state.value.submitError)
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertEquals("keep this text", vm.bodyFieldState.text.toString())
        assertTrue(vm.state.value.canSubmit)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `Reload after dormant Q was consumed elsewhere preserves the uncertain attempt and warning`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val token = (draftStore.beginAttempt("tester", key, EditorDraftStore.Draft("uncertain body"), 0)
            as EditorDraftStore.BeginAttemptResult.Started).token
        draftStore.finishAttempt(token, EditorDraftStore.AttemptOutcome.UNCERTAIN)
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        val other = EditorDraftSession(draftStore, key)
        other.load()
        other.consumeInlineQuoteRequest(checkNotNull(other.pendingInlineQuoteRequest))
        val current = vm.bodyValue()
        vm.dispatch(PostEditorIntent.PendingQuoteIgnored)
        syncBody()
        assertTrue(vm.state.value.quoteStorageUnavailable)
        vm.dispatch(PostEditorIntent.ReloadRequested)
        syncBody()
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertEquals(current, vm.bodyValue())
        assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
        assertEquals(EditorSubmissionState.Uncertain(token), vm.state.value.submission)
        assertEquals(token, draftStore.entries[key]?.attempt?.token)
        assertEquals(EditorDraftStore.AttemptStatus.UNCERTAIN, draftStore.entries[key]?.attempt?.status)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `M2 conflicting dormant ignore reloads only Room before a successful new CAS`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val token = (draftStore.beginAttempt("tester", key, EditorDraftStore.Draft("uncertain body"), 0)
            as EditorDraftStore.BeginAttemptResult.Started).token
        draftStore.finishAttempt(token, EditorDraftStore.AttemptOutcome.UNCERTAIN)
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        val other = EditorDraftSession(draftStore, key)
        other.load()
        other.save(EditorDraftStore.Draft("uncertain body"))
        vm.dispatch(PostEditorIntent.PendingQuoteIgnored)
        syncBody()
        assertTrue(vm.state.value.quoteStorageUnavailable)
        assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
        vm.dispatch(PostEditorIntent.ReloadRequested)
        syncBody()
        assertEquals(0, replyRepository.formFetches)
        assertFalse(vm.state.value.quoteStorageUnavailable)
        vm.dispatch(PostEditorIntent.PendingQuoteIgnored)
        syncBody()
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertEquals(token, draftStore.entries[key]?.attempt?.token)
        assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
    }

    @Test
    fun `405 post autosaves C in the main row and keeps D in memory until Restore`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        draftStore.preload(key, EditorDraftStore.Draft("offered D"))
        replyRepository.formResult = Result.success(authenticatedForm("[quotemsg=101]quote[/quotemsg]"))
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        vm.editBody(TextFieldValue("current C"))
        testScheduler.advanceTimeBy(751)
        syncBody()
        assertEquals("current C", draftStore.saved[key]?.body)
        assertEquals(setOf(key), draftStore.entries.keys)
        assertEquals("offered D", vm.state.value.restorableDraft)
        assertNotNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertEquals(0, replyRepository.formFetches)
        vm.dispatch(PostEditorIntent.DraftRestoreRequested)
        settleBody()
        assertEquals("[quotemsg=101]quote[/quotemsg]\n\ncurrent C\n\noffered D", draftStore.saved[key]?.body)
        assertNull(vm.state.value.restorableDraft)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertEquals(1, replyRepository.formFetches)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `405 post autosaves C in the main row and keeps D in memory until Ignore`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        draftStore.preload(key, EditorDraftStore.Draft("offered D"))
        replyRepository.formResult = Result.success(authenticatedForm("[quotemsg=101]quote[/quotemsg]"))
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        vm.editBody(TextFieldValue("current C"))
        testScheduler.advanceTimeBy(751)
        syncBody()
        assertEquals("current C", draftStore.saved[key]?.body)
        assertEquals(setOf(key), draftStore.entries.keys)
        assertEquals("offered D", vm.state.value.restorableDraft)
        assertNotNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertEquals(0, replyRepository.formFetches)
        val get = CompletableDeferred<Unit>()
        replyRepository.formGate = get
        vm.dispatch(PostEditorIntent.DraftDiscardRequested)
        syncBody()
        assertEquals("current C", draftStore.saved[key]?.body)
        assertNotNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        get.complete(Unit)
        settleBody()
        assertEquals("[quotemsg=101]quote[/quotemsg]\n\ncurrent C", draftStore.saved[key]?.body)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertNull(vm.state.value.restorableDraft)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `Astra1 post storage information is deferred until explicit uncertainty acknowledgement`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val token = (draftStore.beginAttempt("tester", key, EditorDraftStore.Draft("uncertain body"), 0)
            as EditorDraftStore.BeginAttemptResult.Started).token
        draftStore.finishAttempt(token, EditorDraftStore.AttemptOutcome.UNCERTAIN)
        replyRepository.formResult = Result.success(authenticatedForm("[quotemsg=101]quote[/quotemsg]"))
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        draftStore.saveFailure = IOException("disk")
        vm.dispatch(PostEditorIntent.PendingQuoteIgnored)
        syncBody()
        assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
        draftStore.saveFailure = null
        val get = CompletableDeferred<Unit>()
        replyRepository.formGate = get
        vm.dispatch(PostEditorIntent.PendingQuoteResumed)
        syncBody()
        assertEquals(EditorErrors.draftSaveFailed, vm.state.value.submitError)
        assertEquals(1, replyRepository.formFetches)
        get.complete(Unit)
        settleBody()
        assertNull(vm.state.value.submitError)
        // Resuming the quote acknowledges through PersistingAttempt too: no « Envoi… » may remain.
        vm.assertNoSendingEvent()
    }

    @Test
    fun `m2 ordinary draft read failure does not block publication`() = runTest {
        draftStore.loadFailure = IOException("disk")
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val vm = newReplyViewModel(savedStateHandle = SavedStateHandle(
            mapOf("postEditor.pendingQuotes" to intArrayOf(1, 101))))
        syncBody()
        assertFalse(vm.state.value.quoteStorageUnavailable)
        assertEquals(EditorErrors.draftSaveFailed, vm.state.value.submitError)
        val events = vm.chromeEvents.state
        assertEquals(2, events.events.size) // v20 notice AND read failure survive the final opening.
        assertTrue(events.events.all { it.key.session == events.session && it.key.session.owner == "tester" })
        assertTrue(events.events.any { it.content is EditorEventContent.Information })
        assertEquals(EditorErrors.draftSaveFailed, (events.events.last().content as EditorEventContent.Problem).error)
        vm.editBody(TextFieldValue("current C"))
        assertTrue(vm.state.value.canSubmit)
        vm.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()
        assertEquals(1, replyRepository.submitCalls)
        assertEquals(EditorDraftStore.AttemptStatus.PUBLISHED,
            draftStore.entries[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)]?.attempt?.status)
    }

    @Test
    fun `m4 conflict reconcile does not offer a body identical to the live field`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val body = "[quotemsg=101]quote[/quotemsg]\n"
        replyRepository.formResult = Result.success(authenticatedForm(body))
        val get = CompletableDeferred<Unit>()
        replyRepository.formGate = get
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        val other = EditorDraftSession(draftStore, key)
        other.load()
        other.save(EditorDraftStore.Draft(body))
        get.complete(Unit)
        syncBody()
        assertTrue(vm.state.value.quoteStorageUnavailable)
        assertEquals(body, vm.bodyFieldState.text.toString())
        vm.dispatch(PostEditorIntent.ReloadRequested)
        settleBody()
        assertNull(vm.state.value.restorableDraft)
        assertEquals(body, vm.bodyFieldState.text.toString())
        assertFalse(vm.state.value.quoteStorageUnavailable)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertEquals(1, replyRepository.formFetches)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `B discarding an unreadable request without attempt clears the obsolete error`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val pending = PendingInlineQuoteRequest(SAMPLE_CAT, SAMPLE_TOPIC_ID,
            listOf(PendingInlineQuoteRequest.Quote(101, 1)))
        draftStore.preload(key, EditorDraftStore.Draft("ordinary body", pendingInlineQuoteRequest = pending))
        draftStore.loadFailure = UnreadableInlineQuoteRequestException()
        val vm = newReplyViewModel()
        syncBody()
        assertNull(draftStore.entries[key]?.attempt)
        assertEquals(EditorErrors.unreadableQuoteRequest, vm.state.value.submitError)
        assertEquals(0, replyRepository.formFetches)
        vm.editBody(TextFieldValue("current C"))
        vm.dispatch(PostEditorIntent.PendingQuoteIgnored)
        syncBody()
        assertNull(vm.state.value.submitError)
        assertFalse(vm.state.value.unreadableQuoteRequest)
        assertFalse(vm.state.value.canIgnorePendingQuote)
        assertTrue(vm.state.value.canSubmit)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertNull(draftStore.entries[key]?.attempt)
        assertEquals("ordinary body", draftStore.saved[key]?.body)
        assertEquals(1, replyRepository.formFetches)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `publication under an undecided ordinary offer leaves only the erased main row`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        draftStore.preload(key, EditorDraftStore.Draft("offered D"))
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val vm = newReplyViewModel()
        syncBody()
        vm.editBody(TextFieldValue("published C"))
        assertEquals("offered D", vm.state.value.restorableDraft)
        assertTrue(vm.state.value.canSubmit)
        vm.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()
        assertEquals(1, replyRepository.submitCalls)
        assertEquals("published C", replyRepository.lastSubmittedBbcode)
        assertEquals(setOf(key), draftStore.entries.keys)
        assertEquals("", draftStore.saved[key]?.body)
        val writes = draftStore.writes
        vm.dispatch(PostEditorIntent.CloseRequested)
        vm.editBody(TextFieldValue("late callback"))
        settleBody()
        assertEquals(writes, draftStore.writes)
        assertEquals("", draftStore.saved[key]?.body)
    }

    @Test
    fun `published quote entry restores an empty post composition without resurrecting sent content`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val token = (draftStore.beginAttempt("tester", key, EditorDraftStore.Draft("sent body"), 0)
            as EditorDraftStore.BeginAttemptResult.Started).token
        draftStore.finishAttempt(token, EditorDraftStore.AttemptOutcome.PUBLISHED)
        val producer = EditorDraftSession(draftStore, key)
        producer.load()
        producer.enqueueInlineQuoteRequest(PendingInlineQuoteRequest(SAMPLE_CAT, SAMPLE_TOPIC_ID,
            listOf(PendingInlineQuoteRequest.Quote(101, 1))))
        val get = CompletableDeferred<Unit>()
        replyRepository.formGate = get
        replyRepository.formResult = Result.success(authenticatedForm("[quotemsg=101]quote[/quotemsg]"))
        val vm = newReplyViewModel(quoteAction = false)
        syncBody()
        assertEquals("", vm.bodyFieldState.text.toString())
        assertNull(vm.state.value.restorableDraft)
        get.complete(Unit)
        settleBody()
        assertEquals("[quotemsg=101]quote[/quotemsg]\n", vm.bodyFieldState.text.toString())
        assertFalse(vm.bodyFieldState.text.toString().contains("sent body"))
        assertNull(draftStore.entries[key]?.attempt)
    }

    @Test
    fun `M1 post can explicitly discard an unreadable request and reopen its unresolved attempt`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val token = (draftStore.beginAttempt("tester", key, EditorDraftStore.Draft("unresolved body"), 0)
            as EditorDraftStore.BeginAttemptResult.Started).token
        draftStore.finishAttempt(token, EditorDraftStore.AttemptOutcome.UNCERTAIN)
        draftStore.enqueueInlineQuoteRequest("tester", key,
            PendingInlineQuoteRequest(SAMPLE_CAT, SAMPLE_TOPIC_ID, listOf(PendingInlineQuoteRequest.Quote(101, 1))), 1)
        draftStore.loadFailure = UnreadableInlineQuoteRequestException()
        val vm = newReplyViewModel()
        syncBody()
        assertTrue(vm.state.value.unreadableQuoteRequest)
        assertTrue(vm.state.value.canIgnorePendingQuote)
        assertEquals(EditorErrors.unreadableQuoteRequest, vm.state.value.submitError)
        assertEquals(0, replyRepository.formFetches)
        vm.dispatch(PostEditorIntent.PendingQuoteIgnored)
        syncBody()
        assertFalse(vm.state.value.quoteStorageUnavailable)
        assertFalse(vm.state.value.unreadableQuoteRequest)
        assertEquals("unresolved body", vm.bodyFieldState.text.toString())
        assertEquals(token, draftStore.entries[key]?.attempt?.token)
        assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun unreadableQuoteDiscardRestoresUncertainBodyAfterTypingThenErasingAndKeepsUndo() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val token = (draftStore.beginAttempt("tester", key, EditorDraftStore.Draft("unresolved body"), 0)
            as EditorDraftStore.BeginAttemptResult.Started).token
        draftStore.finishAttempt(token, EditorDraftStore.AttemptOutcome.UNCERTAIN)
        draftStore.enqueueInlineQuoteRequest("tester", key,
            PendingInlineQuoteRequest(SAMPLE_CAT, SAMPLE_TOPIC_ID, listOf(PendingInlineQuoteRequest.Quote(101, 1))), 1)
        draftStore.loadFailure = UnreadableInlineQuoteRequestException()
        val vm = newReplyViewModel()
        assertTrue(vm.state.value.unreadableQuoteRequest)
        vm.editBody(TextFieldValue("x", TextRange(1)))
        vm.editBody(TextFieldValue(""))
        assertEquals("", vm.bodyFieldState.text.toString())
        assertTrue(vm.bodyFieldState.undoState.canUndo)

        vm.dispatch(PostEditorIntent.PendingQuoteIgnored)
        settleBody()

        assertEquals("unresolved body", vm.bodyFieldState.text.toString())
        assertEquals("unresolved body", vm.state.value.bodyText)
        assertEquals("unresolved body", draftStore.saved[key]?.body)
        assertEquals(token, draftStore.entries[key]?.attempt?.token)
        assertFalse(vm.state.value.unreadableQuoteRequest)
        assertTrue(vm.bodyFieldState.undoState.canUndo)
        vm.bodyFieldState.undoState.undo()
        assertEquals("", vm.bodyFieldState.text.toString())
        assertTrue(vm.bodyFieldState.undoState.canUndo)
        vm.bodyFieldState.undoState.undo()
        assertEquals("x", vm.bodyFieldState.text.toString())
    }

    @Test
    fun unreadableQuoteDiscardDuringUploadRestoresUncertainBodyAndPersistsImage() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val token = (draftStore.beginAttempt("tester", key, EditorDraftStore.Draft("unresolved body"), 0)
            as EditorDraftStore.BeginAttemptResult.Started).token
        draftStore.finishAttempt(token, EditorDraftStore.AttemptOutcome.UNCERTAIN)
        draftStore.enqueueInlineQuoteRequest("tester", key,
            PendingInlineQuoteRequest(SAMPLE_CAT, SAMPLE_TOPIC_ID, listOf(PendingInlineQuoteRequest.Quote(101, 1))), 1)
        draftStore.loadFailure = UnreadableInlineQuoteRequestException()
        val uploadGate = CompletableDeferred<Unit>()
        uploadRepository.uploadGate = uploadGate
        val vm = newReplyViewModel()
        assertTrue(vm.state.value.unreadableQuoteRequest)
        assertTrue(vm.state.value.canIgnorePendingQuote)
        assertEquals("", vm.bodyFieldState.text.toString())
        vm.dispatch(PostEditorIntent.ImagePicked("content://pick/1"))
        assertTrue(vm.state.value.isUploading)

        vm.dispatch(PostEditorIntent.PendingQuoteIgnored)
        settleBody()

        assertTrue(vm.state.value.isUploading)
        assertFalse(vm.state.value.unreadableQuoteRequest)
        assertEquals("unresolved body", vm.bodyFieldState.text.toString())
        assertEquals("unresolved body", vm.state.value.bodyText)
        assertEquals("unresolved body", draftStore.saved[key]?.body)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        uploadGate.complete(Unit)
        settleBody()
        val expected = "unresolved body[img]${uploadRepository.uploadResult.imageUrl}[/img]"
        assertFalse(vm.state.value.isUploading)
        assertEquals(expected, vm.bodyFieldState.text.toString())
        assertEquals(expected, vm.state.value.bodyText)
        assertEquals(expected, draftStore.saved[key]?.body)
        assertEquals(token, draftStore.entries[key]?.attempt?.token)
        assertEquals(EditorDraftStore.AttemptStatus.UNCERTAIN, draftStore.entries[key]?.attempt?.status)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `autosave started during GET and returning late cannot restore the consumed post request`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        replyRepository.formResult = Result.success(authenticatedForm("[quotemsg=101]quote[/quotemsg]"))
        val get = CompletableDeferred<Unit>()
        replyRepository.formGate = get
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        vm.editBody(TextFieldValue("during GET"))
        val saved = CompletableDeferred<Unit>()
        draftStore.saveReturnGate = saved
        testScheduler.advanceTimeBy(751)
        syncBody()
        get.complete(Unit)
        syncBody()
        saved.complete(Unit)
        settleBody()
        assertEquals("[quotemsg=101]quote[/quotemsg]\n\nduring GET", draftStore.saved[key]?.body)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
    }

    @Test
    fun `Astra m4 restore intent is refused during image upload`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        draftStore.preload(key, EditorDraftStore.Draft("offered D"))
        imageUploadReader.result = ImageUpload(byteArrayOf(1), "image/png", null)
        val gate = CompletableDeferred<Unit>()
        uploadRepository.uploadGate = gate
        uploadRepository.uploadResult = uploadedImage("https://rehost.diberie.com/Picture/Get/f/7")
        val vm = newReplyViewModel(authRepository = FakeAuthRepository(AuthState.Authenticated("alice")))
        syncBody()
        vm.dispatch(PostEditorIntent.ImagePicked("content://x/1"))
        syncBody()
        assertTrue(vm.state.value.isUploading)
        val before = vm.bodyValue()
        vm.dispatch(PostEditorIntent.DraftRestoreRequested)
        assertEquals(before, vm.bodyValue())
        assertEquals("offered D", vm.state.value.restorableDraft)
        gate.complete(Unit)
        settleBody()
    }

    @Test
    fun `successful reload clears only loading errors and keeps an uncertain send`() = runTest {
        replyRepository.formException = IOException("offline")
        val vm = newReplyViewModel()
        settleBody()
        assertEquals(EditorErrors.formNetwork, vm.state.value.submitError)
        replyRepository.formException = null
        vm.dispatch(PostEditorIntent.ReloadRequested)
        settleBody()
        assertNull(vm.state.value.submitError)
        replyRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        vm.editBody(TextFieldValue("body"))
        vm.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()
        vm.dispatch(PostEditorIntent.ReloadRequested)
        settleBody()
        assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
        assertEquals(1, replyRepository.submitCalls)
    }

    @Test
    fun `draft failure waits for uncertainty acknowledgement and clears after the next durable write`() = runTest {
        replyRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = newReplyViewModel()
        settleBody()
        vm.editBody(TextFieldValue("attempt body"))
        vm.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val attempt = draftStore.entries[key]?.attempt

        draftStore.saveFailure = IOException("disk full")
        vm.editBody(TextFieldValue("edited after uncertainty"))
        testScheduler.advanceTimeBy(751)
        syncBody()
        assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
        assertEquals(attempt, draftStore.entries[key]?.attempt)
        assertEquals(1, replyRepository.submitCalls)

        vm.dispatch(PostEditorIntent.ErrorDismissed)
        syncBody()
        assertEquals(EditorErrors.draftSaveFailed, vm.state.value.submitError)
        assertNull(draftStore.entries[key]?.attempt)
        assertEquals("edited after uncertainty", vm.bodyFieldState.text.toString())

        draftStore.saveFailure = null
        testScheduler.advanceTimeBy(751)
        syncBody()
        assertNull(vm.state.value.submitError)
        assertEquals("edited after uncertainty", draftStore.saved[key]?.body)
        assertEquals(1, replyRepository.submitCalls)
    }

    @Test
    fun `ED45 ED170 Masquer acknowledges a restored attempt before recreation`() = runTest {
        val attempt = seedUncertainAttempt()
        val vm = newReplyViewModel()
        settleBody()
        val adapter = vm.chromeAdapter()
        val key = vm.chromeEvents.state.visibleEvent!!.key
        adapter.barActions.onEvent(key, EditorEventAction.Details)
        val acknowledgement = CompletableDeferred<Unit>()
        draftStore.finishGate = acknowledgement
        adapter.dismissDetails(key)
        assertTrue(vm.state.value.submission is EditorSubmissionState.PersistingAttempt)
        vm.assertNoSendingEvent(expectedSubmissions = 0)
        acknowledgement.complete(Unit)
        settleBody()
        assertNull(adapter.detail)
        assertEquals(EditorSubmissionState.Ready, vm.state.value.submission)
        // Acknowledgement must never register a send, including while storage is suspended.
        vm.assertNoSendingEvent(expectedSubmissions = 0)
        assertNull(vm.chromeEvents.state.visibleEvent)
        assertNull(draftStore.entries[attempt.key]?.attempt)
        assertEquals("uncertain body", vm.bodyFieldState.text.toString())
        vm.viewModelScope.cancel()

        val recreated = newReplyViewModel()
        settleBody()
        assertEquals(EditorSubmissionState.Ready, recreated.state.value.submission)
        assertNull(recreated.state.value.submitError)
        assertTrue(recreated.chromeEvents.state.events.isEmpty())
        assertEquals("uncertain body", draftStore.saved[attempt.key]?.body)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ED45 ED170 Masquer also acknowledges a freshly failed submission`() = runTest {
        replyRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = newReplyViewModel()
        settleBody()
        vm.editBody(TextFieldValue("sent body"))
        vm.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()
        assertTrue(vm.state.value.submission is EditorSubmissionState.Uncertain)
        val adapter = vm.chromeAdapter()
        val key = vm.chromeEvents.state.visibleEvent!!.key
        adapter.barActions.onEvent(key, EditorEventAction.Details)
        val acknowledgement = CompletableDeferred<Unit>()
        draftStore.finishGate = acknowledgement
        adapter.dismissDetails(key)
        assertTrue(vm.state.value.submission is EditorSubmissionState.PersistingAttempt)
        vm.assertNoSendingEvent(expectedSubmissions = 1)
        acknowledgement.complete(Unit)
        settleBody()
        assertEquals(EditorSubmissionState.Ready, vm.state.value.submission)
        vm.assertNoSendingEvent(expectedSubmissions = 1)
        assertNull(vm.chromeEvents.state.visibleEvent)
        assertNull(draftStore.entries[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)]?.attempt)
        assertEquals("sent body", vm.bodyFieldState.text.toString())
        assertEquals(1, replyRepository.submitCalls)
    }

    @Test
    fun `ED45 ED170 local Masquer in Ready allows the next failed autosave to deliver information`() = runTest {
        assertLocalMasquerReleasesAutosave(rearm = false)
    }

    @Test
    fun `ED45 ED170 local Masquer after rearming allows the next failed autosave to deliver information`() = runTest {
        assertLocalMasquerReleasesAutosave(rearm = true)
    }

    @Test
    fun `ED132 local Masquer bypasses the dormant quote projection veto and uses the real binding state`() = runTest {
        val vm = newReplyViewModel()
        settleBody()
        draftStore.beginFailure = IOException("disk full")
        vm.editBody(TextFieldValue("unsent body"))
        vm.dispatch(PostEditorIntent.SubmitClicked())
        assertEquals(EditorSubmissionState.Ready, vm.state.value.submission)
        assertEquals(EditorErrors.draftWriteFailed, vm.state.value.submitError)

        // Deliberately lag the UI projection: only the real binding may guard local acknowledgement.
        // A consistent dormant projection implies Uncertain, which the domain must refuse independently.
        @Suppress("UNCHECKED_CAST")
        val projected = PostEditorViewModel::class.java.getDeclaredField("_state").run {
            isAccessible = true
            get(vm) as MutableStateFlow<PostEditorState>
        }
        val pending = listOf(card(101))
        projected.value = projected.value.copy(
            pendingQuotes = pending, submission = EditorSubmissionState.Uncertain(null),
        )
        assertTrue(vm.state.value.hasDormantQuote)
        val writes = draftStore.writes
        val intents = mutableListOf<PostEditorIntent>()
        val adapter = vm.chromeAdapter(intents)
        val key = vm.chromeEvents.state.visibleEvent!!.key
        adapter.barActions.onEvent(key, EditorEventAction.Details)
        adapter.dismissDetails(key)
        syncBody()
        assertEquals(listOf(PostEditorIntent.DraftWriteFailureDismissed), intents)
        assertNull(vm.state.value.submitError)
        assertTrue(vm.state.value.hasDormantQuote)
        assertEquals(pending, vm.state.value.pendingQuotes)
        assertEquals(writes, draftStore.writes)
        assertEquals(0, draftStore.finishCalls)
        assertEquals(0, replyRepository.submitCalls)
        assertEquals("unsent body", vm.bodyFieldState.text.toString())
        vm.assertNoSendingEvent()
    }

    @Test
    fun `ED132 local Masquer keeps a real dormant quote and its uncertain attempt unresolved`() = runTest {
        val attempt = seedUncertainAttempt()
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        settleBody()
        assertTrue(vm.state.value.hasDormantQuote)
        val entry = draftStore.entries[attempt.key]
        val writes = draftStore.writes
        val finishCalls = draftStore.finishCalls
        // An unrelated local delivery cannot use Masquer to acknowledge the durable uncertainty.
        vm.chromeEvents.pickerProblem(EditorErrors.draftWriteFailed)
        val local = vm.chromeEvents.state.events.single {
            it.content == EditorEventContent.Problem(EditorErrors.draftWriteFailed)
        }
        val uncertain = vm.chromeEvents.state.visibleEvent!!
        vm.chromeEvents.dispatch(EditorEventInput.Acknowledge(uncertain.key))
        val intents = mutableListOf<PostEditorIntent>()
        val adapter = vm.chromeAdapter(intents)
        adapter.barActions.onEvent(local.key, EditorEventAction.Details)
        adapter.dismissDetails(local.key)
        syncBody()
        assertEquals(listOf(PostEditorIntent.DraftWriteFailureDismissed), intents)
        assertEquals(EditorSubmissionState.Uncertain(attempt), vm.state.value.submission)
        assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
        assertTrue(vm.state.value.hasDormantQuote)
        assertEquals(entry, draftStore.entries[attempt.key])
        assertEquals(writes, draftStore.writes)
        assertEquals(finishCalls, draftStore.finishCalls)
        assertEquals(0, replyRepository.submitCalls)
        vm.assertNoSendingEvent()
    }

    private fun TestScope.assertLocalMasquerReleasesAutosave(rearm: Boolean) {
        val vm = newReplyViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = rearm),
        )
        settleBody()
        draftStore.beginFailure = IOException("disk full")
        vm.editBody(TextFieldValue("unsent body"))
        vm.dispatch(PostEditorIntent.SubmitClicked())
        if (rearm) {
            testScheduler.advanceTimeBy(401)
            vm.dispatch(PostEditorIntent.SubmitConfirmed(vm.state.value.armToken))
        }
        assertEquals(EditorSubmissionState.Ready, vm.state.value.submission)
        assertEquals(EditorErrors.draftWriteFailed, vm.state.value.submitError)
        assertEquals(1, draftStore.beginCalls)
        val intents = mutableListOf<PostEditorIntent>()
        val adapter = vm.chromeAdapter(intents)
        if (rearm) {
            adapter.submitActions.onSubmit()
            syncBody()
            assertTrue(vm.state.value.submission is EditorSubmissionState.Armed)
        }
        val submission = vm.state.value.submission
        val activity = listOf(draftStore.writes, draftStore.beginCalls, draftStore.finishCalls, draftStore.saveCount)
        val key = vm.chromeEvents.state.visibleEvent!!.key
        assertEquals(EditorEventContent.Problem(EditorErrors.draftWriteFailed),
            vm.chromeEvents.state.visibleEvent?.content)
        adapter.barActions.onEvent(key, EditorEventAction.Details)
        adapter.dismissDetails(key)
        syncBody()
        assertEquals(List(if (rearm) 1 else 0) { PostEditorIntent.SubmitClicked() } +
            PostEditorIntent.DraftWriteFailureDismissed, intents)
        assertEquals(submission, vm.state.value.submission)
        assertNull(vm.state.value.submitError)
        assertNull(adapter.detail)
        assertNull(vm.chromeEvents.state.visibleEvent)
        assertEquals(activity,
            listOf(draftStore.writes, draftStore.beginCalls, draftStore.finishCalls, draftStore.saveCount))
        vm.assertNoSendingEvent()

        draftStore.saveFailure = IOException("still full")
        vm.editBody(TextFieldValue("continued composition"))
        testScheduler.advanceTimeBy(751)
        syncBody()
        assertEquals(EditorErrors.draftSaveFailed, vm.state.value.submitError)
        assertEquals(EditorEventContent.Problem(EditorErrors.draftSaveFailed),
            vm.chromeEvents.state.visibleEvent?.content)
        assertEquals("continued composition", vm.bodyFieldState.text.toString())
        assertEquals(0, replyRepository.submitCalls)
        assertEquals(0, draftStore.finishCalls)
        vm.assertNoSendingEvent()
    }

    @Test
    fun `ED85 ED170 Back closes uncertainty visually and recreation offers it again`() = runTest {
        val attempt = seedUncertainAttempt()
        val vm = newReplyViewModel()
        settleBody()
        val adapter = vm.chromeAdapter()
        val key = vm.chromeEvents.state.visibleEvent!!.key
        adapter.barActions.onEvent(key, EditorEventAction.Details)
        adapter.back()
        settleBody()
        assertNull(adapter.detail)
        assertNull(vm.chromeEvents.state.visibleEvent)
        assertEquals(EditorSubmissionState.Uncertain(attempt), vm.state.value.submission)
        assertEquals(attempt, draftStore.entries[attempt.key]?.attempt?.token)
        vm.viewModelScope.cancel()

        val recreated = newReplyViewModel()
        settleBody()
        assertEquals(EditorSubmissionState.Uncertain(attempt), recreated.state.value.submission)
        assertEquals(EditorErrors.uncertain, recreated.state.value.submitError)
        assertEquals(EditorEventContent.Problem(EditorErrors.uncertain),
            recreated.chromeEvents.state.visibleEvent?.content)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ED170 Masquer preserves uncertainty on failed durable acknowledgement and recreation`() = runTest {
        val attempt = seedUncertainAttempt()
        val vm = newReplyViewModel()
        settleBody()
        draftStore.finishFailure = IOException("disk unavailable")
        val finishCalls = draftStore.finishCalls
        val adapter = vm.chromeAdapter()
        val key = vm.chromeEvents.state.visibleEvent!!.key
        adapter.barActions.onEvent(key, EditorEventAction.Details)
        val acknowledgement = CompletableDeferred<Unit>()
        draftStore.finishGate = acknowledgement
        adapter.dismissDetails(key)
        assertTrue(vm.state.value.submission is EditorSubmissionState.PersistingAttempt)
        vm.assertNoSendingEvent(expectedSubmissions = 0)
        acknowledgement.complete(Unit)
        settleBody()
        assertEquals(finishCalls + 1, draftStore.finishCalls)
        assertEquals(EditorSubmissionState.Uncertain(attempt), vm.state.value.submission)
        assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
        // The kept uncertainty is signalled again without ever registering a send for acknowledgement.
        vm.assertNoSendingEvent(expectedSubmissions = 0)
        assertEquals(EditorEventContent.Problem(EditorErrors.uncertain), vm.chromeEvents.state.visibleEvent?.content)
        assertEquals(attempt, draftStore.entries[attempt.key]?.attempt?.token)
        assertEquals("uncertain body", vm.bodyFieldState.text.toString())
        vm.viewModelScope.cancel()

        val recreated = newReplyViewModel()
        settleBody()
        assertEquals(EditorSubmissionState.Uncertain(attempt), recreated.state.value.submission)
        assertEquals(EditorEventContent.Problem(EditorErrors.uncertain),
            recreated.chromeEvents.state.visibleEvent?.content)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ED132 ED170 Masquer leaves a dormant quote and its uncertain attempt unresolved`() = runTest {
        val attempt = seedUncertainAttempt()
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        settleBody()
        assertTrue(vm.state.value.hasDormantQuote)
        val finishCalls = draftStore.finishCalls
        val pending = draftStore.saved[attempt.key]?.pendingInlineQuoteRequest
        val intents = mutableListOf<PostEditorIntent>()
        val adapter = vm.chromeAdapter(intents)
        assertEquals(EditorEventContent.Problem(EditorErrors.uncertain), vm.chromeEvents.state.visibleEvent?.content)
        val key = vm.chromeEvents.state.visibleEvent!!.key
        adapter.barActions.onEvent(key, EditorEventAction.Details)
        adapter.dismissDetails(key)
        settleBody()
        // Masquer did ask for the acknowledgement; the dormant quote guard of the ViewModel refused it (r3 I1).
        assertTrue(PostEditorIntent.ErrorDismissed in intents)
        vm.assertNoSendingEvent()
        assertEquals(finishCalls, draftStore.finishCalls)
        assertEquals(EditorSubmissionState.Uncertain(attempt), vm.state.value.submission)
        assertEquals(attempt, draftStore.entries[attempt.key]?.attempt?.token)
        assertEquals(pending, draftStore.saved[attempt.key]?.pendingInlineQuoteRequest)
        assertTrue(vm.state.value.hasDormantQuote)
        assertEquals(0, replyRepository.formFetches)
        vm.viewModelScope.cancel()

        val recreated = newReplyViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        settleBody()
        assertTrue(recreated.state.value.hasDormantQuote)
        assertEquals(EditorSubmissionState.Uncertain(attempt), recreated.state.value.submission)
        assertEquals(EditorEventContent.Problem(EditorErrors.uncertain),
            recreated.chromeEvents.state.visibleEvent?.content)
        assertEquals(0, replyRepository.submitCalls)
    }

    private suspend fun seedUncertainAttempt(): EditorDraftStore.AttemptToken {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val attempt = (draftStore.beginAttempt("tester", key, EditorDraftStore.Draft("uncertain body"), 0)
            as EditorDraftStore.BeginAttemptResult.Started).token
        draftStore.finishAttempt(attempt, EditorDraftStore.AttemptOutcome.UNCERTAIN)
        return attempt
    }

    private fun PostEditorViewModel.chromeAdapter(intents: MutableList<PostEditorIntent> = mutableListOf()) =
        PostEditorChromeAdapter(
            { state.value }, bodyFieldState, chromeEvents, { intents += it; dispatch(it) }, {}, {}, chromeMemory,
        )

    private fun PostEditorViewModel.assertNoSendingEvent(expectedSubmissions: Int? = null) {
        assertTrue(chromeEvents.state.events.none { it.content is EditorEventContent.InProgress })
        expectedSubmissions?.let { expected ->
            assertEquals(expected, chromeEvents.state.currentOperations.count { it.ref is OperationRef.Submission })
        }
    }

    @Test
    fun autosaveRecoveryObservesNullErrorBeforeDurableSuccess() = runTest {
        withObservedProjections { projections ->
            val vm = newReplyViewModel()
            settleBody()
            draftStore.saveFailure = IOException("disk full")
            vm.editBody(TextFieldValue("unsaved body"))
            settleBody()
            assertEquals(EditorErrors.draftSaveFailed, vm.state.value.submitError)
            val failed = projections.last { it.submitError == EditorErrors.draftSaveFailed }
            projections.clear()

            draftStore.saveFailure = null
            vm.editBody(TextFieldValue("saved body"))
            settleBody()
            val reset = projections.indexOfFirst {
                it.submitError == null && it.durableWriteGeneration == failed.durableWriteGeneration
            }
            val durable = projections.indexOfFirst { it.durableWriteGeneration > failed.durableWriteGeneration }
            assertTrue("The collector must observe clearError before durableWriteSucceeded", reset >= 0)
            assertTrue("The successful write must follow the observed reset", durable > reset)
            assertNull(projections[durable].submitError)
            assertNull(vm.state.value.submitError)
            assertTrue(vm.chromeEvents.state.events.isEmpty())
            assertEquals("saved body", draftStore.saved[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)]?.body)
        }
    }

    @Test
    fun `successful draft write preserves a newer form loading error`() = runTest {
        withObservedProjections { projections ->
            val vm = newReplyViewModel()
            settleBody()
            draftStore.saveFailure = IOException("disk full")
            vm.editBody(TextFieldValue("unsaved body"))
            settleBody()
            assertEquals(EditorErrors.draftSaveFailed, vm.state.value.submitError)

            replyRepository.formException = IOException("offline")
            vm.dispatch(PostEditorIntent.ReloadRequested)
            settleBody()
            assertTrue(vm.chromeEvents.state.events.any {
                (it.content as? EditorEventContent.Problem)?.error == EditorErrors.draftSaveFailed
            })
            assertEquals(EditorErrors.formNetwork, vm.state.value.submitError)
            val generation = projections.last().durableWriteGeneration
            projections.clear()
            draftStore.saveFailure = null
            vm.editBody(TextFieldValue("saved body"))
            settleBody()
            assertTrue(projections.any { it.durableWriteGeneration > generation })
            assertTrue(projections.all { it.submitError == EditorErrors.formNetwork })
            assertEquals(EditorErrors.formNetwork, vm.state.value.submitError)
            assertEquals(listOf(EditorErrors.formNetwork), vm.chromeEvents.state.events.mapNotNull {
                (it.content as? EditorEventContent.Problem)?.error
            })
            assertEquals("saved body", draftStore.saved[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)]?.body)
        }
    }

    private fun withObservedProjections(block: (MutableList<EditorEventProjection>) -> Unit) {
        // StateFlow and the final event queue cannot reveal a reset that bypassed the collector.
        mockkConstructor(EditorEventCollector::class)
        try {
            val projections = mutableListOf<EditorEventProjection>()
            every { anyConstructed<EditorEventCollector>().observe(any(), any()) } answers {
                projections += secondArg<EditorEventProjection>()
                callOriginal()
            }
            block(projections)
        } finally {
            unmockkConstructor(EditorEventCollector::class)
        }
    }

    @Test
    fun `new upload replaces the previous failure but an ignored pick preserves the current error`() = runTest {
        uploadRepository.uploadException = UploadException.TooLarge(1)
        val vm = newReplyViewModel()
        settleBody()
        vm.dispatch(PostEditorIntent.ImagesPicked(listOf("content://first")))
        settleBody()
        assertEquals(EditorErrors.tooLarge, vm.state.value.uploadError)
        uploadRepository.uploadException = null
        val gate = CompletableDeferred<Unit>()
        uploadRepository.uploadGate = gate
        vm.dispatch(PostEditorIntent.ImagesPicked(listOf("content://second")))
        assertNull(vm.state.value.uploadError)
        vm.dispatch(PostEditorIntent.ImagePickerEventReceived(
            ImagePickerEvent.Unavailable(ImagePickerMode.PHOTO_PICKER, ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA),
        ))
        vm.dispatch(PostEditorIntent.ImagesPicked(List(11) { "content://ignored/$it" }))
        assertEquals(EditorErrors.pickerAbsent, vm.state.value.uploadError)
        gate.complete(Unit)
        settleBody()
        vm.dispatch(PostEditorIntent.ImagesPicked(listOf("content://third")))
        settleBody()
        assertNull(vm.state.value.uploadError)
        assertEquals(3, uploadRepository.uploadCalls)
    }

    @Test
    fun `ED131 submit and picker errors survive typing selection options and programmatic insertion`() = runTest {
        replyRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
        val vm = newReplyViewModel()
        settleBody()
        vm.editBody(TextFieldValue("body"))
        vm.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()
        vm.dispatch(PostEditorIntent.ImagePickerEventReceived(
            ImagePickerEvent.Unavailable(ImagePickerMode.PHOTO_PICKER, ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA),
        ))
        vm.editBody(TextFieldValue("changed"))
        vm.editBody(TextFieldValue("changed", TextRange(1, 3)))
        assertEquals(EditorErrors.empty, vm.state.value.submitError)
        assertEquals(EditorErrors.pickerAbsent, vm.state.value.uploadError)
        listOf(
            PostEditorIntent.ToggleSignature(true),
            PostEditorIntent.ToolbarActionClicked(BbcodeAction.Bold),
            PostEditorIntent.SmileySelected(":jap:"),
        ).forEach { intent ->
            vm.dispatch(intent)
            assertEquals(intent.toString(), EditorErrors.empty, vm.state.value.submitError)
            assertEquals(intent.toString(), EditorErrors.pickerAbsent, vm.state.value.uploadError)
        }
    }

    @Test
    fun `ED132 retry fetches pending quotes once in order and preserves concurrent typing without POST`() = runTest {
        replyRepository.formResultsByNumrep = mapOf(202 to Result.failure(IOException("offline")))
        val vm = newReplyViewModel(
            initialQuotes = listOf(card(202), card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()
        assertEquals(EditorErrors.quoteFetchFailed, vm.state.value.submitError)
        vm.editBody(TextFieldValue("before"))
        vm.dispatch(PostEditorIntent.ErrorDismissed)
        vm.dispatch(PostEditorIntent.SubmitClicked())
        vm.dispatch(PostEditorIntent.SubmitConfirmed(null))
        assertFalse(vm.state.value.canSubmit)
        assertEquals(0, replyRepository.submitCalls)
        assertEquals(listOf(202, 101), vm.state.value.pendingQuotes.map { it.numreponse })

        replyRepository.formResultsByNumrep = mapOf(
            202 to Result.success(authenticatedForm("[quotemsg=202]first[/quotemsg]")),
            101 to Result.success(authenticatedForm("[quotemsg=101]second[/quotemsg]")),
        )
        val gate = CompletableDeferred<Unit>()
        replyRepository.formGate = gate
        vm.dispatch(PostEditorIntent.ReloadRequested)
        vm.dispatch(PostEditorIntent.ReloadRequested)
        syncBody()
        vm.editBody(TextFieldValue("typed during retry"))
        vm.dispatch(PostEditorIntent.SubmitClicked())
        gate.complete(Unit)
        settleBody()
        val body = vm.bodyFieldState.text.toString()
        assertEquals(1, Regex("first").findAll(body).count())
        assertEquals(1, Regex("second").findAll(body).count())
        assertTrue(body.indexOf("first") < body.indexOf("second"))
        assertTrue(body.endsWith("typed during retry"))
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertEquals(3, replyRepository.formFetches)
        assertTrue(vm.state.value.canSubmit)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ED132 quote alone with empty prefill stays pending and never submits (#1033)`() = runTest {
        replyRepository.formResultsByNumrep = mapOf(101 to Result.success(authenticatedForm("")))
        val vm = newReplyViewModel(
            initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()
        vm.dispatch(PostEditorIntent.SubmitClicked())
        vm.dispatch(PostEditorIntent.ReloadRequested)
        settleBody()
        assertEquals(EditorErrors.quoteFetchFailed, vm.state.value.submitError)
        assertEquals("", vm.bodyFieldState.text.toString())
        assertEquals(listOf(101), vm.state.value.pendingQuotes.map { it.numreponse })
        assertFalse(vm.state.value.canSubmit)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ED132 repeated quote failure permits explicit abandonment without editing or POST`() = runTest {
        replyRepository.formResultsByNumrep = mapOf(101 to Result.failure(IOException("offline")))
        val vm = newReplyViewModel(
            initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()
        val typed = TextFieldValue("keep this text", TextRange(2, 5))
        vm.editBody(typed)
        assertEquals(1, vm.state.value.quoteFetchFailures)
        vm.dispatch(PostEditorIntent.PendingQuoteIgnored)
        assertFalse(vm.state.value.canIgnorePendingQuote)
        assertEquals(listOf(101), vm.state.value.pendingQuotes.map { it.numreponse })

        vm.dispatch(PostEditorIntent.ReloadRequested)
        settleBody()
        assertEquals(2, vm.state.value.quoteFetchFailures)
        assertTrue(vm.state.value.canIgnorePendingQuote)
        assertFalse(vm.state.value.canSubmit)
        vm.dispatch(PostEditorIntent.ErrorDismissed)
        assertNull(vm.state.value.submitError)
        assertFalse("acknowledgement alone cannot abandon", vm.state.value.canSubmit)
        assertEquals(listOf(101), vm.state.value.pendingQuotes.map { it.numreponse })
        // Show the banner again; another reload is harmless and the count stays saturated.
        vm.dispatch(PostEditorIntent.ReloadRequested)
        settleBody()
        assertEquals(2, vm.state.value.quoteFetchFailures)
        vm.dispatch(PostEditorIntent.PendingQuoteIgnored)
        settleBody()
        assertEquals(typed, vm.bodyValue())
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertNull(vm.state.value.submitError)
        assertEquals(0, vm.state.value.quoteFetchFailures)
        assertFalse(vm.state.value.canIgnorePendingQuote)
        assertTrue(vm.state.value.isSubmitEnabled)
        assertEquals(0, replyRepository.submitCalls)
        assertNull("abandonment warms the plain form only", replyRepository.lastFetchedContext?.quotedNumreponse)
        // A later explicit submit really works: enabled must not hide a missing form/hash.
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        vm.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()
        assertEquals(1, replyRepository.submitCalls)
        assertEquals(typed.text, replyRepository.lastSubmittedBbcode)
    }

    @Test
    fun `ED132 ignoring a quote cannot insert a plain form prefill into an empty body`() = runTest {
        replyRepository.formResultsByNumrep = mapOf(101 to Result.success(authenticatedForm("")))
        replyRepository.formResult = Result.success(authenticatedForm("unexpected plain prefill"))
        val vm = newReplyViewModel(
            initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()
        vm.dispatch(PostEditorIntent.ReloadRequested)
        settleBody()
        vm.dispatch(PostEditorIntent.PendingQuoteIgnored)
        settleBody()
        assertEquals(TextFieldValue(), vm.bodyValue())
        assertNull(vm.state.value.submitError)
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertFalse("an empty reply remains unsendable", vm.state.value.canSubmit)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ED132 failure count survives process recreation and ignored request is never replayed`() = runTest {
        replyRepository.formResultsByNumrep = mapOf(101 to Result.failure(IOException("offline")))
        val saved = SavedStateHandle()
        val first = newReplyViewModel(
            initialQuotes = listOf(card(101)), savedStateHandle = saved,
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()
        assertEquals(1, first.state.value.quoteFetchFailures)
        first.viewModelScope.cancel()
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        // The durable row retains coordinates; only the failure count comes from SavedStateHandle.
        val second = newReplyViewModel(
            savedStateHandle = restored,
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()
        assertEquals(2, second.state.value.quoteFetchFailures)
        assertTrue(second.state.value.canIgnorePendingQuote)
        second.editBody(TextFieldValue("still here"))
        second.dispatch(PostEditorIntent.PendingQuoteIgnored)
        settleBody()
        assertTrue(second.state.value.canSubmit)
        second.viewModelScope.cancel()
        val afterIgnore = SavedStateHandle(restored.keys().associateWith { restored.get<Any?>(it) })
        val quoteFetches = replyRepository.fetchedContexts.count { it.isQuote }
        val third = newReplyViewModel(
            initialQuotes = listOf(card(101)), savedStateHandle = afterIgnore, quoteAction = false,
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()
        assertTrue(third.state.value.pendingQuotes.isEmpty())
        assertEquals(0, third.state.value.quoteFetchFailures)
        assertEquals(quoteFetches, replyRepository.fetchedContexts.count { it.isQuote })
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `initial state mirrors the request and starts with an empty draft`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.state.test {
            val initial = awaitItem()
            assertEquals(PostEditorMode.Reply, initial.mode)
            assertEquals(SAMPLE_CAT, initial.cat)
            assertEquals(SAMPLE_TOPIC_ID, initial.topicId)
            assertEquals(SAMPLE_PAGE, initial.page)
            assertEquals(SAMPLE_SUBCAT, initial.subcat)
            assertEquals("", initial.bodyText)
            assertFalse(initial.isPreviewVisible)
            assertEquals(PostContent(blocks = emptyList()), initial.preview)
            assertEquals(BbcodeValidation.EmptyDraft, initial.validation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `content changes update the draft and clear the empty-validation hint`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.editBody(TextFieldValue("hello", TextRange(5)))
        viewModel.state.test {
            val state = awaitItem()
            assertEquals("hello", state.bodyText)
            assertEquals(BbcodeValidation.Idle, state.validation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toolbar action wraps the current selection`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.editBody(TextFieldValue("hello", TextRange(0, 5)))
        viewModel.dispatch(PostEditorIntent.ToolbarActionClicked(BbcodeAction.Bold))
        viewModel.state.test {
            val state = awaitItem()
            assertEquals("[b]hello[/b]", state.bodyText)
            assertEquals(3, viewModel.bodyFieldState.selection.start)
            assertEquals(8, viewModel.bodyFieldState.selection.end)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling preview parses the current draft and shows it`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.editBody(TextFieldValue("[b]hi[/b]", TextRange(9)))
        viewModel.dispatch(PostEditorIntent.TogglePreview)
        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.isPreviewVisible)
            assertEquals(previewParser.contentFor("[b]hi[/b]"), state.preview)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `preview hidden mode does not re-parse on every keystroke`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.editBody(TextFieldValue("hello", TextRange(5)))
        assertEquals(0, previewParser.callCount)
    }

    @Test
    fun `C8 new ViewModel after process death starts with preview closed`() = runTest {
        val saved = SavedStateHandle()
        val original = newReplyViewModel(savedStateHandle = saved)
        original.editBody(TextFieldValue("Message"))
        original.dispatch(PostEditorIntent.TogglePreview)
        assertTrue(original.state.value.isPreviewVisible)
        original.viewModelScope.cancel()
        // A new VM gets the same saved values, but no surviving composition or preview visibility.
        val restored = newReplyViewModel(savedStateHandle = SavedStateHandle(
            saved.keys().associateWith { saved.get<Any?>(it) },
        ))
        assertFalse(restored.state.value.isPreviewVisible)
        assertEquals(PostContent(emptyList()), restored.state.value.preview)
        assertTrue(restored.previewLayout !== original.previewLayout)
    }

    @Test
    fun `reply VM fetches the form on init and clears the loading flag`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        viewModel.state.test {
            // Skip the transient `isLoadingForm = true` state if any, settle on final.
            val settled = expectMostRecentItem()
            assertFalse(settled.isLoadingForm)
            assertNull(settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, replyRepository.formFetches)
    }

    @Test
    fun `reply VM surfaces login required when form is anonymous`() = runTest {
        replyRepository.formResult = Result.success(anonymousForm())
        val viewModel = newReplyViewModel()
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(
                ReplyFailureReason.LoginRequired.toEditorError(),
                settled.submitError,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `anonymous submit refusals deliver distinct events after acknowledgement`() = runTest {
        replyRepository.formResult = Result.success(anonymousForm())
        val vm = newReplyViewModel()
        vm.editBody(TextFieldValue("Hello!"))
        val initial = requireNotNull(vm.chromeEvents.state.visibleEvent)
        vm.chromeEvents.dispatch(EditorEventInput.Acknowledge(initial.key))
        assertNull(vm.chromeEvents.state.visibleEvent)

        val keys = List(2) {
            vm.dispatch(PostEditorIntent.SubmitClicked())
            val event = requireNotNull(vm.chromeEvents.state.visibleEvent)
            assertEquals(EditorEventContent.Problem(EditorErrors.loginRequired), event.content)
            assertEquals(listOf(event), vm.chromeEvents.state.events)
            vm.chromeEvents.dispatch(EditorEventInput.Activate(event.key))
            assertEquals(event.key, vm.chromeEvents.state.detailKey)
            vm.chromeEvents.dispatch(EditorEventInput.CloseDetails(event.key))
            assertNull(vm.chromeEvents.state.visibleEvent)
            assertEquals(EditorErrors.loginRequired, vm.state.value.submitError)
            event.key
        }
        assertNotEquals(keys.first(), keys.last())
        assertEquals(EditorSubmissionState.Ready, vm.state.value.submission)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `submit blank content does not POST`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `submit happy path emits SubmitSucceeded with targetPage`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/.../sujet_X_20.htm#bas",
            targetPage = 20,
        )
        val viewModel = newReplyViewModel()
        viewModel.editBody(TextFieldValue("Hello!", TextRange(6)))
        viewModel.effects.test {
            viewModel.dispatch(PostEditorIntent.SubmitClicked())
            val effect = awaitItem()
            assertEquals(PostEditorEffect.SubmitSucceeded(targetPage = 20), effect)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, replyRepository.submitCalls)
    }

    @Test
    fun `submit anti-flood preserves the draft and exposes the error`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.AntiFlood)
        val viewModel = newReplyViewModel()
        viewModel.editBody(TextFieldValue("Hello!", TextRange(6)))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isSubmitting)
            assertEquals(
                ReplyFailureReason.AntiFlood.toEditorError(),
                settled.submitError,
            )
            // Draft survives.
            assertEquals("Hello!", settled.bodyText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit network error keeps the draft and surfaces uncertainty`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitException = IOException("disconnected")
        val viewModel = newReplyViewModel()
        viewModel.editBody(TextFieldValue("Hello!", TextRange(6)))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isSubmitting)
            assertEquals(EditorErrors.uncertain, settled.submitError)
            assertEquals("Hello!", settled.bodyText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `double submit only triggers one POST when the first call is still in flight`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        // Hold the first submit pending until the test releases it. This is the only
        // realistic shape of "double submit": with UnconfinedTestDispatcher every
        // launch completes synchronously, so without a suspension point the second
        // click would observe submitJob as already-done and re-fire.
        val gate = CompletableDeferred<Unit>()
        replyRepository.submitGate = gate
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = newReplyViewModel()
        viewModel.editBody(TextFieldValue("Hello!", TextRange(6)))

        viewModel.dispatch(PostEditorIntent.SubmitClicked()) // launches the first submit; suspends on gate
        viewModel.dispatch(PostEditorIntent.SubmitClicked()) // must be a no-op (job already active)

        gate.complete(Unit)
        assertEquals(1, replyRepository.submitCalls)
    }

    @Test
    fun `submit refuses when subcat is null and surfaces MissingSubcat`() = runTest {
        val viewModel = newReplyViewModel(subcat = null)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(EditorErrors.missingSubcat, settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
        // No fetch attempted because we never had a valid ReplyContext.
        assertEquals(0, replyRepository.formFetches)
    }

    @Test
    fun `submit refuses when subcat is the SUBCAT_UNKNOWN sentinel`() = runTest {
        // Distinct from `subcat = null` : a v3-cache row carries the -1 sentinel,
        // which is non-null but must be treated as "unknown, refresh required".
        val viewModel = newReplyViewModel(subcat = -1)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(EditorErrors.missingSubcat, settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, replyRepository.formFetches)
    }

    @Test
    fun `subcat zero opens the reply form (cat without sub-category, e g IA)`() = runTest {
        // #213 — `subcat = 0` is HFR's wire shape for a category WITHOUT a
        // sub-category (e.g. cat=32 « Intelligence artificielle »). A live capture
        // of the IA reply form proved HFR posts with `subcat=0`, so the editor must
        // open : no MissingSubcat, the form is fetched, and the built ReplyContext
        // carries `subcat = 0` (not re-collapsed to the sentinel downstream).
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(subcat = 0)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertNull("subcat=0 is postable — no MissingSubcat", settled.submitError)
            assertFalse(settled.isLoadingForm)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, replyRepository.formFetches)
        assertEquals(0, replyRepository.lastFetchedContext?.subcat)
    }

    @Test
    fun `init form fetch surfaces SessionExpired when HFR redirects to login`() = runTest {
        replyRepository.formException = fr.forumhfr.redface2.core.domain.auth.SessionExpiredException(
            "https://forum.hardware.fr/login.php",
        )
        val viewModel = newReplyViewModel()
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isLoadingForm)
            assertEquals(EditorErrors.sessionExpired, settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `init form fetch surfaces Network error on IOException`() = runTest {
        replyRepository.formException = java.io.IOException("disconnected")
        val viewModel = newReplyViewModel()
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isLoadingForm)
            assertEquals(EditorErrors.formNetwork, settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `init form fetch maps unexpected exceptions to Hfr(Unknown) instead of crashing`() = runTest {
        replyRepository.formException = IllegalStateException("HFR returned a 500 page we don't know")
        val viewModel = newReplyViewModel()
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isLoadingForm)
            assertEquals(EditorErrors.invalidForm, settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `quote cards leave the open-time fetch plain (#604 lot 3)`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(initialQuotes = listOf(card(2784595)))
        // Let the launched form fetch complete.
        settleBody()

        val context = replyRepository.lastFetchedContext
        assertNotNull("fetchReplyForm must have been called", context)
        requireNotNull(context)
        assertNull("open-time fetch must be the PLAIN form — cards materialise at submit", context.quotedNumreponse)
        assertFalse("isQuote must be false at open", context.isQuote)
        assertEquals(listOf(2784595), viewModel.state.value.quotes.map { it.numreponse })
    }

    @Test
    fun `quote cards never prefill the field — the draft stays user-owned`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())

        val viewModel = newReplyViewModel(initialQuotes = listOf(card(2784595)))
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse("Form must be fully loaded", settled.isLoadingForm)
            assertEquals("field stays empty — citations are cards, not BBCode", "", settled.bodyText)
            assertFalse("nothing hydrated from the plain form", settled.draftHydratedFromForm)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit materialises the quote cards in card order (#291, #604 lot 3)`() = runTest {
        // One #146 form fetch per card AT SUBMIT — the FIRST carries the session form
        // (hash_check), the extras only contribute their prefill. HFR prefills end with a
        // trailing blank line; the merge keeps ONE blank line between quotes, the typed body
        // follows after a single blank line.
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
            303 to Result.success(authenticatedForm(initialContent = "[quotemsg=303,3,9]c[/quotemsg]\n\n")),
            202 to Result.success(authenticatedForm(initialContent = "[quotemsg=202,2,9]b[/quotemsg]\n\n")),
        )
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        // Card order 101 → 303 → 202 is deliberately NOT post order.
        val viewModel = newReplyViewModel(initialQuotes = listOf(card(101), card(303), card(202)))
        settleBody()
        assertEquals("open-time fetch is the plain form only", 1, replyRepository.formFetches)

        viewModel.editBody(TextFieldValue("Reply"))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        assertEquals("plain open fetch + one quote fetch per card", 4, replyRepository.formFetches)
        assertEquals(
            "cards replay the quote contract in CARD order (never a stale quoteRef)",
            listOf(null to null, 101 to null, 303 to null, 202 to null),
            replyRepository.fetchedContexts.map { it.quotedNumreponse to it.quoteRef },
        )
        assertEquals("one POST riding the first quote form's hash", 1, replyRepository.submitCalls)
        assertEquals(101, replyRepository.lastSubmittedContext?.quotedNumreponse)
        assertEquals(
            "prefills concatenated in card order, body after a single blank line",
            "[quotemsg=101,1,9]a[/quotemsg]\n\n" +
                "[quotemsg=303,3,9]c[/quotemsg]\n\n" +
                "[quotemsg=202,2,9]b[/quotemsg]\n\nReply",
            replyRepository.lastSubmittedBbcode,
        )
    }

    @Test
    fun `submit fails whole when one card's fetch fails — cards and body intact (#291)`() = runTest {
        // Silently dropping a quote the user explicitly selected would be worse than the
        // retryable error, so a failed card fails the whole submit — and loses NOTHING.
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
            303 to Result.failure(java.io.IOException("boom")),
        )

        val viewModel = newReplyViewModel(initialQuotes = listOf(card(101), card(303)))
        settleBody()
        viewModel.editBody(TextFieldValue("Reply"))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        assertEquals("nothing must reach HFR on a failed materialisation", 0, replyRepository.submitCalls)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isSubmitting)
            assertEquals(EditorErrors.quoteFetchFailed(inline = false), settled.submitError)
            assertEquals("cards intact", listOf(101, 303), settled.quotes.map { it.numreponse })
            assertEquals("body intact", "Reply", settled.bodyText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun cardQuoteReloadDuringUploadClearsFailureWithoutChangingBody() = runTest {
        replyRepository.formResultsByNumrep = mapOf(101 to Result.failure(IOException("offline")))
        val vm = newReplyViewModel(initialQuotes = listOf(card(101)))
        settleBody()
        vm.editBody(TextFieldValue("Reply", TextRange(5)))
        vm.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()
        assertEquals(EditorErrors.quoteFetchFailed(inline = false), vm.state.value.submitError)
        assertEquals(0, replyRepository.submitCalls)
        val before = vm.bodyValue()
        val uploadGate = CompletableDeferred<Unit>()
        uploadRepository.uploadGate = uploadGate
        vm.dispatch(PostEditorIntent.ImagePicked("content://pick/1"))
        assertTrue(vm.state.value.isUploading)
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm("[quotemsg=101]quote[/quotemsg]")),
        )
        val fetchesBeforeReload = replyRepository.formFetches

        vm.dispatch(PostEditorIntent.ReloadRequested)
        settleBody()

        assertEquals(fetchesBeforeReload + 1, replyRepository.formFetches)
        assertTrue(vm.state.value.isUploading)
        assertFalse(vm.state.value.isLoadingForm)
        assertNull(vm.state.value.submitError)
        assertEquals(before, vm.bodyValue())
        assertEquals("Reply", vm.state.value.bodyText)
        assertEquals(listOf(101), vm.state.value.quotes.map { it.numreponse })
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        uploadGate.complete(Unit)
        settleBody()
        val expected = "Reply[img]${uploadRepository.uploadResult.imageUrl}[/img]"
        assertEquals(expected, vm.bodyFieldState.text.toString())
        assertEquals(expected, draftStore.saved[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)]?.body)
        assertEquals(fetchesBeforeReload + 1, replyRepository.formFetches)
        assertNull(vm.state.value.submitError)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `submit keeps draft and certain failure when a card's prefill comes back blank (#291)`() = runTest {
        // A 200-OK form whose textarea prefill is EMPTY would otherwise silently drop a quote
        // the user explicitly selected — same contract as a failed fetch: fail globally.
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
            303 to Result.success(authenticatedForm(initialContent = "")),
        )

        val viewModel = newReplyViewModel(initialQuotes = listOf(card(101), card(303)))
        settleBody()
        viewModel.editBody(TextFieldValue("Reply"))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        assertEquals("nothing must reach HFR", 0, replyRepository.submitCalls)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isSubmitting)
            assertEquals(EditorErrors.quoteFetchFailed(inline = false), settled.submitError)
            assertEquals("cards intact", listOf(101, 303), settled.quotes.map { it.numreponse })
            assertEquals("body intact", "Reply", settled.bodyText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unreadable quote form is a certain preparation failure with zero POST`() = runTest {
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.failure(IllegalStateException("Reply form not found")),
        )
        val viewModel = newReplyViewModel(initialQuotes = listOf(card(101)))
        settleBody()
        viewModel.editBody(TextFieldValue("Reply"))

        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        assertEquals(0, replyRepository.submitCalls)
        assertEquals(EditorErrors.quoteFetchFailed(inline = false), viewModel.state.value.submitError)
        assertEquals("Reply", viewModel.bodyFieldState.text.toString())
        assertEquals(listOf(101), viewModel.state.value.quotes.map { it.numreponse })
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm("[quotemsg=101]loaded[/quotemsg]")),
        )
        viewModel.dispatch(PostEditorIntent.ReloadRequested)
        settleBody()
        assertEquals(0, replyRepository.submitCalls)
        assertEquals("Reply", viewModel.bodyFieldState.text.toString())
        assertEquals(listOf(101), viewModel.state.value.quotes.map { it.numreponse })
        assertNull(viewModel.state.value.submitError)
        assertFalse(viewModel.state.value.canIgnorePendingQuote)
        assertTrue(viewModel.state.value.canSubmit)
        viewModel.dispatch(PostEditorIntent.QuoteRemoved(101))
        assertTrue(viewModel.state.value.canSubmit)
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()
        assertEquals(1, replyRepository.submitCalls)
        assertEquals("Reply", replyRepository.lastSubmittedBbcode)
    }

    @Test
    fun `quote VM does not overwrite a draft the user already typed`() = runTest {
        // User-typed content lands on the VM before the form fetch completes —
        // we gate the fetch with a CompletableDeferred so we can interleave them
        // exactly like the production race (network in flight, user typing).
        val formGate = CompletableDeferred<Unit>()
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.formGate = formGate

        val viewModel = newReplyViewModel(initialQuotes = listOf(card(2784595)))
        // Type before the form lands.
        viewModel.editBody(androidx.compose.ui.text.input.TextFieldValue("My own message"))
        // Now let the form load.
        formGate.complete(Unit)
        syncBody()
        settleBody()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(
                "User-typed content must be preserved when the form arrives late",
                "My own message",
                settled.bodyText,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `option toggle intents flip the corresponding state booleans`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.dispatch(PostEditorIntent.ToggleSignature(true))
        viewModel.dispatch(PostEditorIntent.ToggleSmileyDisabled(true))
        viewModel.dispatch(PostEditorIntent.ToggleEmailNotification(true))
        settleBody()

        val settled = viewModel.state.value
        assertTrue("signatureEnabled flipped", settled.signatureEnabled)
        assertTrue("smileyDisabled flipped", settled.smileyDisabled)
        assertTrue("emailNotificationEnabled flipped", settled.emailNotificationEnabled)
    }

    @Test
    fun `options are seeded from form options on first load`() = runTest {
        replyRepository.formResult = Result.success(
            authenticatedForm().copy(
                options = fr.forumhfr.redface2.core.model.write.ReplyFormOptions(
                    signatureEnabled = true,
                    smileyDisabled = false,
                    emailNotificationEnabled = true,
                ),
            ),
        )
        val viewModel = newReplyViewModel()
        settleBody()

        val settled = viewModel.state.value
        assertTrue("signature default true reflects HFR checked attribute", settled.signatureEnabled)
        assertFalse(settled.smileyDisabled)
        assertTrue(settled.emailNotificationEnabled)
        assertTrue("optionsHydratedFromForm flips after first load", settled.optionsHydratedFromForm)
    }

    @Test
    fun `message tone is hydrated from the first form`() = runTest {
        // A non-default tone (6) so the assertion cannot pass on the DEFAULT_MSG_ICON fallback.
        replyRepository.formResult = Result.success(authenticatedForm().copy(msgIcon = "6"))

        val viewModel = newReplyViewModel()
        settleBody()

        assertEquals(6, viewModel.state.value.msgIcon)
        assertTrue(viewModel.state.value.optionsHydratedFromForm)
    }

    @Test
    fun `selected message tone reaches the submitted reply form`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm().copy(msgIcon = "1"))
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.dispatch(PostEditorIntent.MsgIconSelected(6))
        viewModel.editBody(TextFieldValue("hi"))
        assertEquals(6, viewModel.state.value.msgIcon)
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        assertEquals("6", replyRepository.lastSubmittedForm?.msgIcon)
    }

    @Test
    fun `silent refetch does not overwrite a user-selected message tone`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm().copy(msgIcon = "1"))
        replyRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.dispatch(PostEditorIntent.MsgIconSelected(6))
        viewModel.editBody(TextFieldValue("hi"))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        assertEquals("refetch happened", 2, replyRepository.formFetches)
        assertEquals(6, viewModel.state.value.msgIcon)
    }

    @Test
    fun `silent refetch must not overwrite user-toggled options`() = runTest {
        // First load : HFR ships everything false. User flips signature on.
        // InvalidHashCheck triggers a silent refetch with the same defaults.
        // The user's toggle must survive — same anti-clobber rule as the draft.
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val viewModel = newReplyViewModel()
        settleBody()
        viewModel.dispatch(PostEditorIntent.ToggleSignature(true))
        viewModel.editBody(TextFieldValue("hi"))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        assertEquals("refetch happened", 2, replyRepository.formFetches)
        val settled = viewModel.state.value
        assertTrue("signature toggle must survive the refetch", settled.signatureEnabled)
    }

    @Test
    fun editCannotSubmitOrConfirmAnUnknownInitialBody() = runTest {
        val vm = newEditViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        vm.submit(PostEditorIntent.SubmitClicked())
        val token = requireNotNull(vm.state.value.armToken)
        assertTrue(vm.state.value.canSubmit)
        vm.forgetInitialBody()
        assertFalse(vm.state.value.canSubmit)
        vm.submit(PostEditorIntent.SubmitClicked())
        testScheduler.advanceTimeBy(401)
        vm.submit(PostEditorIntent.SubmitConfirmed(token))
        testScheduler.runCurrent()
        assertEquals(0, editPostRepository.submitCalls)
        assertEquals(token, vm.state.value.armToken)
    }

    @Test
    fun editFormFailureRejectsTypingAndPasteUntilReloadHydratesWholePost() = runTest {
        editPostRepository.formException = IOException("offline")
        val vm = newEditViewModel()
        assertFalse(vm.state.value.isLoadingForm)
        assertTrue(vm.state.value.isAwaitingInitialBody)
        assertEquals(EditorErrors.formNetwork, vm.state.value.submitError)
        assertEquals(EditorErrorAction.Reload, vm.state.value.submitError?.action)
        vm.editBody(TextFieldValue("typed offline"))
        vm.editBody(TextFieldValue("pasted offline"))
        assertEquals("", vm.bodyFieldState.text.toString())
        assertFalse(vm.state.value.canSubmit)
        vm.submit(PostEditorIntent.SubmitClicked())
        vm.submit(PostEditorIntent.SubmitConfirmed(vm.state.value.armToken))
        assertEquals(0, editPostRepository.submitCalls)

        val gate = CompletableDeferred<Unit>()
        editPostRepository.formGate = gate
        editPostRepository.formException = null
        vm.submit(PostEditorIntent.ReloadRequested)
        assertTrue(vm.state.value.isLoadingForm)
        assertTrue(vm.state.value.isAwaitingInitialBody)
        vm.editBody(TextFieldValue("typed during reload"))
        gate.complete(Unit)
        settleBody()
        assertEquals("existing post body", vm.bodyFieldState.text.toString())
        assertEquals("existing post body", vm.state.value.draftRestoreSeed)
        assertFalse(vm.state.value.isAwaitingInitialBody)
        assertNull(vm.state.value.submitError)
        assertTrue(vm.state.value.canSubmit)
        assertEquals(2, editPostRepository.formFetches)
    }

    @Test
    fun editInsertionsAndUploadsAreRefusedWhileInitialBodyIsUnknown() = runTest {
        val gate = CompletableDeferred<Unit>()
        editPostRepository.formGate = gate
        val vm = newEditViewModel()
        vm.smileyPicker.open()
        val before = vm.bodyValue()
        for (intent in listOf(
            PostEditorIntent.ToolbarActionClicked(BbcodeAction.Bold),
            PostEditorIntent.ToolbarActionClicked(BbcodeAction.Quote),
            PostEditorIntent.SmileySelected(":jap:"),
            PostEditorIntent.ImageUrlInserted("https://example.test/image.png"),
            PostEditorIntent.ImagePicked("content://pick/1"),
            PostEditorIntent.ImagesPicked(listOf("content://pick/2")),
            PostEditorIntent.ImagePickerEventReceived(
                ImagePickerEvent.Result(ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA, listOf("content://pick/3")),
            ),
        )) {
            vm.submit(intent)
            assertEquals(before, vm.bodyValue())
            assertFalse(vm.state.value.isUploading)
        }
        assertTrue(vm.smileyPicker.state.value is SmileyPickerState.Open)
        assertEquals(0, imageUploadReader.readCalls)
        assertEquals(0, uploadRepository.uploadCalls)
        assertFalse(vm.state.value.canSubmit)
        gate.complete(Unit)
        settleBody()
        assertEquals("existing post body", vm.bodyFieldState.text.toString())
        vm.submit(PostEditorIntent.SmileySelected(":jap:"))
        assertTrue(vm.bodyFieldState.text.toString().contains(":jap:"))
        assertEquals(SmileyPickerState.Hidden, vm.smileyPicker.state.value)
    }

    @Test
    fun editUploadCallbackCannotInsertOrAutosaveAfterBodyBecomesUnknown() = runTest {
        val gate = CompletableDeferred<Unit>()
        uploadRepository.uploadGate = gate
        val vm = newEditViewModel()
        vm.submit(PostEditorIntent.ImagesPicked(listOf("content://pick/1")))
        assertTrue(vm.state.value.isUploading)
        val before = vm.bodyValue()
        val saves = draftStore.saveCount
        // Probe the async callback independently from the upload-start guard.
        vm.forgetInitialBody()
        gate.complete(Unit)
        settleBody()
        assertEquals(before, vm.bodyValue())
        assertEquals(saves, draftStore.saveCount)
        assertFalse(vm.state.value.isUploading)
        assertFalse(vm.state.value.canSubmit)
    }

    @Test
    fun parserRejectedEditFormKeepsBodyLockedAndOffersReload() = runTest {
        editPostRepository.formException = IllegalStateException("content_form textarea missing from edit form")
        val vm = newEditViewModel()
        assertEquals(EditorErrors.invalidForm, vm.state.value.submitError)
        assertEquals(EditorErrorAction.Reload, vm.state.value.submitError?.action)
        vm.editBody(TextFieldValue("partial replacement"))
        assertEquals("", vm.bodyFieldState.text.toString())
        assertTrue(vm.state.value.isAwaitingInitialBody)
        assertFalse(vm.state.value.canSubmit)
        editPostRepository.formException = null
        vm.submit(PostEditorIntent.ReloadRequested)
        settleBody()
        assertEquals("existing post body", vm.bodyFieldState.text.toString())
        assertFalse(vm.state.value.isAwaitingInitialBody)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun blankEditServerBodyIsKnownAndUnlocksWithoutChangingReply() = runTest {
        for (seed in listOf("", " \n")) {
            editPostRepository.formResult = Result.success(authenticatedForm(seed))
            val vm = newEditViewModel()
            assertTrue(vm.state.value.draftHydratedFromForm)
            assertFalse(vm.state.value.isAwaitingInitialBody)
            assertEquals(seed, vm.state.value.draftHydratedContent)
            assertEquals(seed, vm.state.value.draftRestoreSeed)
            assertEquals(seed, vm.bodyFieldState.text.toString())
            assertFalse(vm.state.value.canSubmit)
            vm.editBody(TextFieldValue("filled body"))
            assertEquals("filled body", vm.bodyFieldState.text.toString())
            assertTrue(vm.state.value.canSubmit)
            vm.viewModelScope.cancel()
        }
        val gate = CompletableDeferred<Unit>()
        replyRepository.formGate = gate
        replyRepository.formException = IOException("offline")
        val reply = newReplyViewModel()
        assertFalse(reply.state.value.isAwaitingInitialBody)
        reply.editBody(TextFieldValue("reply during GET"))
        gate.complete(Unit)
        settleBody()
        reply.submit(PostEditorIntent.SmileySelected(":jap:"))
        assertTrue(reply.bodyFieldState.text.toString().contains("reply during GET"))
        assertTrue(reply.bodyFieldState.text.toString().contains(":jap:"))
        assertFalse(reply.state.value.isAwaitingInitialBody)
    }

    @Test
    fun editAttemptRestoreUnlocksOnlyNonblankBodiesAndRefetchPreservesThem() = runTest {
        val key = EditorDraftKey.editPost(SAMPLE_CAT, SAMPLE_EDITED_NUMREPONSE)
        val token = EditorDraftStore.AttemptToken("tester", key, "uncertain", 1)
        for (body in listOf("", " \n", "restored body")) {
            draftStore.entries[key] = EditorDraftStore.DraftEntry(
                EditorDraftStore.Draft(body), 1,
                EditorDraftStore.Attempt(token, EditorDraftStore.AttemptStatus.UNCERTAIN),
            )
            editPostRepository.formException = IOException("offline")
            val vm = newEditViewModel()
            assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
            assertEquals(body.isNotBlank(), vm.state.value.draftHydratedFromForm)
            assertEquals(body.isBlank(), vm.state.value.isAwaitingInitialBody)
            vm.editBody(TextFieldValue("replacement after restore"))
            val expected = if (body.isBlank()) body else "replacement after restore"
            assertEquals(expected, vm.bodyFieldState.text.toString())
            assertEquals(body.isNotBlank(), vm.state.value.canSubmit)
            editPostRepository.formException = null
            vm.submit(PostEditorIntent.ReloadRequested)
            settleBody()
            val reloaded = if (body.isBlank()) "existing post body" else expected
            assertEquals(reloaded, vm.bodyFieldState.text.toString())
            assertFalse(vm.state.value.isAwaitingInitialBody)
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `Edit init fetches the edit form and hydrates draft from existing BBCode`() = runTest {
        editPostRepository.formResult = Result.success(
            ReplyForm(
                hashCheck = "EDIT_HASH",
                sujet = "Existing topic",
                hiddenFields = mapOf("numreponse" to SAMPLE_EDITED_NUMREPONSE.toString()),
                isAnonymous = false,
                initialContent = "Existing post body",
            ),
        )
        val viewModel = newEditViewModel()
        settleBody()

        assertEquals("edit fetch called once", 1, editPostRepository.formFetches)
        val context = editPostRepository.lastFetchedContext
        assertNotNull("EditPostContext must reach the repository", context)
        requireNotNull(context)
        assertEquals(SAMPLE_EDITED_NUMREPONSE, context.numreponse)
        assertEquals(SAMPLE_TOPIC_ID, context.topicId)
        assertEquals(SAMPLE_SUBCAT, context.subcat)

        val settled = viewModel.state.value
        assertEquals("Existing post body", settled.bodyText)
        assertTrue(settled.draftHydratedFromForm)
    }

    @Test
    fun `Edit submit calls EditPostRepository with the numreponse from the route`() = runTest {
        editPostRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/foo/bar-sujet_35395_20.htm#t100",
            targetPage = 20,
        )
        val viewModel = newEditViewModel()
        settleBody()

        viewModel.editBody(TextFieldValue("rewritten body"))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        assertEquals(1, editPostRepository.submitCalls)
        assertEquals(0, replyRepository.submitCalls)
        val context = editPostRepository.lastSubmittedContext
        assertNotNull(context)
        requireNotNull(context)
        assertEquals(SAMPLE_EDITED_NUMREPONSE, context.numreponse)
        assertEquals("rewritten body", editPostRepository.lastSubmittedBbcode)
    }

    @Test
    fun `Edit success emits SubmitSucceeded with scrollTo equal to the edited numreponse`() = runTest {
        editPostRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/foo/bar-sujet_35395_20.htm#t100",
            targetPage = 20,
        )
        val viewModel = newEditViewModel()
        settleBody()
        viewModel.editBody(TextFieldValue("rewritten body"))

        viewModel.effects.test {
            viewModel.dispatch(PostEditorIntent.SubmitClicked())
            val effect = awaitItem()
            assertTrue("must be a SubmitSucceeded — got $effect", effect is PostEditorEffect.SubmitSucceeded)
            val success = effect as PostEditorEffect.SubmitSucceeded
            assertEquals(20, success.targetPage)
            assertEquals(
                "edit success must surface the edited numreponse as scrollTo",
                SAMPLE_EDITED_NUMREPONSE,
                success.scrollTo,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Reply success leaves scrollTo null (only edit fills it)`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/foo/bar-sujet_35395_20.htm#bas",
            targetPage = 20,
        )
        val viewModel = newReplyViewModel()
        settleBody()
        viewModel.editBody(TextFieldValue("new reply"))

        viewModel.effects.test {
            viewModel.dispatch(PostEditorIntent.SubmitClicked())
            val effect = awaitItem() as PostEditorEffect.SubmitSucceeded
            assertEquals(20, effect.targetPage)
            assertNull("Reply must keep scrollTo null — anchor is #bas", effect.scrollTo)
            // #974 — a body without any `[quotemsg]` cites nothing : the topic lands at the bottom.
            assertEquals(emptyList<Int>(), effect.quotedNumreponses)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Quote success forwards the parser-extracted numreponse as scrollTo (issue #200)`() = runTest {
        // Issue #200 — quote anchors `#t{numreponse}` on the success URL, and that numreponse is
        // the CITED post (HFR anchors on the quote form's `numrep` : 2523833 is the post quoted
        // by `write_quote_form_bbcode_rich.html`, not the freshly-created reply). The ViewModel
        // must propagate it as scrollTo — the topic engine's landing hint (#974).
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/foo/bar-sujet_148750_1.htm#t2523833",
            targetPage = 1,
            numreponse = 2_523_833,
        )
        val viewModel = newReplyViewModel()
        settleBody()
        viewModel.editBody(TextFieldValue("citing"))

        viewModel.effects.test {
            viewModel.dispatch(PostEditorIntent.SubmitClicked())
            val effect = awaitItem() as PostEditorEffect.SubmitSucceeded
            assertEquals(1, effect.targetPage)
            assertEquals(
                "quote success must forward the CITED post id from the parser as scrollTo",
                2_523_833,
                effect.scrollTo,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Reply with cards forwards the cited numreponses for the topic landing (#974)`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
            303 to Result.success(authenticatedForm(initialContent = "[quotemsg=303,3,9]c[/quotemsg]\n\n")),
        )
        // HFR anchors the success on the FIRST cited post (`numrep`) ; the topic engine picks the
        // landing itself from the full card list, in card order.
        replyRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/foo/bar-sujet_35395_20.htm#t101",
            targetPage = 20,
            numreponse = 101,
        )
        val viewModel = newReplyViewModel(initialQuotes = listOf(card(101), card(303)))
        settleBody()
        viewModel.editBody(TextFieldValue("Reply"))

        viewModel.effects.test {
            viewModel.dispatch(PostEditorIntent.SubmitClicked())
            assertEquals(
                PostEditorEffect.SubmitSucceeded(
                    targetPage = 20,
                    scrollTo = 101,
                    quotedNumreponses = listOf(101, 303),
                    operation = PostSubmitOperation.Publish,
                ),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Edit success prefers the parser numreponse over the local hint (issue #200)`() = runTest {
        // Issue #200 — when HFR's `#t{N}` fragment carries a numreponse, the parser is
        // authoritative over the locally-known one. They should agree for edit, but if
        // HFR ever anchors on a different post id (e.g. a moderator merge / split) the
        // user lands where HFR said, not where we guessed.
        val parserExtracted = 999_999
        editPostRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/foo/bar-sujet_35395_20.htm#t$parserExtracted",
            targetPage = 20,
            numreponse = parserExtracted,
        )
        val viewModel = newEditViewModel()
        settleBody()
        viewModel.editBody(TextFieldValue("rewritten body"))

        viewModel.effects.test {
            viewModel.dispatch(PostEditorIntent.SubmitClicked())
            val effect = awaitItem() as PostEditorEffect.SubmitSucceeded
            assertEquals(20, effect.targetPage)
            assertEquals(PostSubmitOperation.Edit, effect.operation)
            assertEquals(
                "parser numreponse must win over the locally-known edit numreponse",
                parserExtracted,
                effect.scrollTo,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit with InvalidHashCheck silently refetches without clobbering draft or cards`() = runTest {
        // `handleSubmitOutcome(InvalidHashCheck)` resets `loadedForm = null` and re-invokes
        // `loadReplyFormIfPossible()` (plain form since #604 lot 3 — blank initialContent, so
        // the refetch has nothing to clobber the field with). We assert : (a) the silent
        // refetch did happen, (b) the user's text AND the cards survive.
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.formResultsByNumrep = mapOf(
            2784595 to Result.success(
                authenticatedForm(initialContent = "[quotemsg=2784595,768,1214571]hi[/quotemsg]\n\n"),
            ),
        )
        replyRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val viewModel = newReplyViewModel(initialQuotes = listOf(card(2784595)))
        settleBody()
        assertEquals("initial plain form fetch", 1, replyRepository.formFetches)

        viewModel.editBody(TextFieldValue("Reply"))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        // Open (plain) + the card's quote-form materialisation + the silent plain refetch.
        assertEquals("silent refetch after InvalidHashCheck", 3, replyRepository.formFetches)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals("user text must survive the silent refetch", "Reply", settled.bodyText)
            assertEquals("cards must survive too", listOf(2784595), settled.quotes.map { it.numreponse })
            assertEquals(
                ReplyFailureReason.InvalidHashCheck.toEditorError(),
                settled.submitError,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `edit hydration refreshes preview when preview was already visible`() = runTest {
        // Race : user opens the edit editor, opens the preview pane WHILE the form is still
        // loading, form arrives → both `draft` and `preview` must reflect the existing post
        // body. (Was a quote-prefill test before #604 lot 3 ; Edit is the only mode still
        // hydrating the field from `initialContent`, so the guard now lives here.)
        val formGate = CompletableDeferred<Unit>()
        editPostRepository.formGate = formGate

        val viewModel = newEditViewModel()
        // Toggle preview BEFORE the form lands.
        viewModel.dispatch(PostEditorIntent.TogglePreview)
        // Now release the form fetch.
        formGate.complete(Unit)
        syncBody()
        settleBody()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals("Draft hydrated with the existing post body", "existing post body", settled.bodyText)
            assertTrue("Preview was visible before hydration", settled.isPreviewVisible)
            assertEquals(
                "Preview AST must reflect the hydrated draft",
                previewParser.contentFor("existing post body"),
                settled.preview,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** #1301-ST — adaptateur des trois parcours, réutilisant les harnais et fakes de cette suite. */
    internal fun submissionAdapter(
        variant: String,
        scope: TestScope,
        preferences: Flow<Boolean>,
        delayedSharedRestore: Boolean,
        scenario: EditorSubmissionScenario,
    ): EditorSubmissionAdapter {
        submissionClock = DiagnosticsClock { scope.testScheduler.currentTime * 1_000_000L }
        draftStore = scenario.store
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("tester"))
        val authRepository = object : AuthRepository by FakeAuthRepository() {
            override fun observeAuthState(): Flow<AuthState> = auth
        }
        scenario.changeAccount = {
            scenario.store.activeOwner = "other"
            auth.value = AuthState.Authenticated("other")
        }
        val postGate = CompletableDeferred<Unit>()
        val uploadGate = CompletableDeferred<Unit>()
        val restoreGate = CompletableDeferred<Unit>()
        val editing = variant == "edit"
        val key = if (editing) {
            EditorDraftKey.editPost(SAMPLE_CAT, SAMPLE_EDITED_NUMREPONSE)
        } else {
            EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        }
        draftStore.preload(key, EditorDraftStore.Draft(body = "stored draft"))
        if (delayedSharedRestore && !editing) draftStore.loadGate = restoreGate
        replyRepository.submitException = scenario.postFailure
        editPostRepository.submitException = scenario.postFailure
        replyRepository.submitGate = postGate
        replyRepository.submitScenario = scenario
        replyRepository.submitResult = ReplySubmitResult.Success(targetPage = 20, refreshUrl = null)
        if (variant == "quote") {
            replyRepository.formResultsByNumrep = mapOf(
                101 to Result.success(authenticatedForm("[quotemsg=101,35395,1]quoted body[/quotemsg]")),
            )
        }
        editPostRepository.submitGate = postGate
        editPostRepository.submitScenario = scenario
        editPostRepository.submitResult = ReplySubmitResult.Success(targetPage = 20, refreshUrl = null)
        scenario.postRejection?.let {
            replyRepository.submitResult = ReplySubmitResult.Failure(it)
            editPostRepository.submitResult = ReplySubmitResult.Failure(it)
        }
        uploadRepository.uploadGate = uploadGate
        val defaults = FakeUserPreferencesRepository(quoteCardsEnabled = true)
        val userPreferences = object : UserPreferencesRepository by defaults {
            override fun observeConfirmBeforePosting(): Flow<Boolean> = preferences
        }
        fun createViewModel() = if (editing) {
            newEditViewModel(userPreferencesRepository = userPreferences, authRepository = authRepository)
        } else {
            newReplyViewModel(
                initialQuotes = if (variant == "quote") listOf(card(101)) else emptyList(),
                resumeSharedDraft = delayedSharedRestore,
                authRepository = authRepository,
                userPreferencesRepository = userPreferences,
            )
        }
        var viewModel = createViewModel()
        val effects = mutableListOf<PostEditorEffect>()
        fun collectEffects() = scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            viewModel.effects.collect { effects += it }
        }
        var collector = collectEffects()
        scenario.recreate = {
            collector.cancel()
            viewModel.viewModelScope.cancel()
            viewModel = createViewModel()
            collector = collectEffects()
        }
        scenario.replaceCollector = {
            collector.cancel()
            collector = collectEffects()
        }
        val arms = mutableMapOf<Long, ArmToken>()
        return object : EditorSubmissionAdapter {
            override val unsupportedMutations = if (editing) {
                mapOf(SubmissionBodyMutation.SHARED_RESTORE to "resumeSharedDraft réservé aux réponses/citations")
            } else {
                emptyMap()
            }
            override val uploadUnsupportedReason: String? = null
            override val postCalls get() = replyRepository.submitCalls + editPostRepository.submitCalls
            override val formFetchCalls get() = replyRepository.formFetches + editPostRepository.formFetches
            override val uploadCalls get() = uploadRepository.uploadCalls
            override val draftSaves get() = draftStore.saveCount
            override val draftWrites get() = draftStore.writes
            override val successEffects get() = effects.count { it is PostEditorEffect.SubmitSucceeded }
            override val closeEffects get() = effects.count { it is PostEditorEffect.CloseCommitted }

            override fun editBody(body: String) {
                viewModel.editBody(TextFieldValue(body, TextRange(body.length)), advanceMirror = false)
                Snapshot.sendApplyNotifications()
            }

            override fun clickSubmit() = viewModel.submit(PostEditorIntent.SubmitClicked())
            override fun confirm() = viewModel.submit(PostEditorIntent.SubmitConfirmed(viewModel.state.value.armToken))
            override fun confirmIdentity(id: Long?) = viewModel.submit(PostEditorIntent.SubmitConfirmed(arms[id]))
            override fun dismissConfirmation() = viewModel.submit(PostEditorIntent.SubmitConfirmationDismissed)
            override fun close() = viewModel.submit(PostEditorIntent.CloseRequested)
            override fun completePost() { postGate.complete(Unit) }
            override fun completeUpload() { uploadGate.complete(Unit) }
            // Reflection exercises the actual final callback guard even when upload launch is refused.
            override fun deliverLateUploadCallback() {
                val insert = PostEditorViewModel::class.java.getDeclaredMethod(
                    "insertImageBbcodeAtCaret", String::class.java, Boolean::class.javaPrimitiveType,
                )
                insert.isAccessible = true
                insert.invoke(viewModel, "[img]https://example.test/late.png[/img]", false)
            }

            override fun dispose() = viewModel.viewModelScope.cancel()
            override fun startUpload() = viewModel.submit(PostEditorIntent.ImagesPicked(listOf("content://pick/1")))

            override fun snapshot(): SubmissionProbe = viewModel.state.value.let {
                it.armToken?.let { token -> arms[token.id] = token }
                SubmissionProbe(
                    viewModel.bodyFieldState.text.toString(),
                    it.copy(bodyText = viewModel.bodyFieldState.text.toString()).canSubmit,
                    it.isSubmitting, it.isUploading, it.showSubmitConfirmation,
                    armId = it.armToken?.id,
                    error = when (it.submitError) {
                        EditorErrors.network -> SubmissionErrorProbe.NETWORK
                        EditorErrors.sessionExpired -> SubmissionErrorProbe.SESSION_EXPIRED
                        EditorErrors.uncertain -> SubmissionErrorProbe.UNCERTAIN
                        EditorErrors.draftWriteFailed -> SubmissionErrorProbe.DRAFT_WRITE_FAILED
                        EditorErrors.invalidForm, EditorErrors.quoteFetchFailed(inline = false) ->
                            SubmissionErrorProbe.PREPARATION_FAILED
                        else -> null
                    },
                    restoreOffered = it.restorableDraft != null,
                    blocksClose = it.submission.blocksBodyMutation,
                )
            }

            override fun mutateBody(mutation: SubmissionBodyMutation) {
                when (mutation) {
                    SubmissionBodyMutation.TYPING -> editBody("changed during POST")
                    SubmissionBodyMutation.SMILEY -> viewModel.submit(PostEditorIntent.SmileySelected(":jap:"))
                    SubmissionBodyMutation.FORMAT ->
                        viewModel.submit(PostEditorIntent.ToolbarActionClicked(BbcodeAction.Bold))
                    SubmissionBodyMutation.IMAGE_URL ->
                        viewModel.submit(PostEditorIntent.ImageUrlInserted("https://example.test/image.png"))
                    SubmissionBodyMutation.RESTORE -> {
                        checkNotNull(viewModel.state.value.restorableDraft) { "l'offre #1415 doit être présente" }
                        viewModel.submit(PostEditorIntent.DraftRestoreRequested)
                    }
                    SubmissionBodyMutation.SHARED_RESTORE -> restoreGate.complete(Unit)
                }
            }
        }
    }

    @Suppress("LongParameterList") // test helper: every param is an optional, defaulted scenario knob.
    private fun newReplyViewModel(
        subcat: Int? = SAMPLE_SUBCAT,
        initialQuotes: List<QuoteSelection> = emptyList(),
        resumeSharedDraft: Boolean = false,
        diagnostics: DiagnosticsLog = DiagnosticsLog(),
        // Test default = cards ON so the #604 lot 3 card suites keep exercising their mode ;
        // the #805 inline tests pass their own fake with `quoteCardsEnabled = false` (the
        // PRODUCTION default is false = inline [quotemsg] in the field).
        userPreferencesRepository: UserPreferencesRepository =
            FakeUserPreferencesRepository(quoteCardsEnabled = true),
        authRepository: AuthRepository = FakeAuthRepository(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        quoteAction: Boolean = true,
        previewParser: BbcodePreviewParser = this.previewParser,
        defaultDispatcher: CoroutineDispatcher = dispatcher,
    ): PostEditorViewModel {
        val cards = (userPreferencesRepository as? FakeUserPreferencesRepository)?.quoteCardsEnabled?.value ?: true
        if (quoteAction && !cards && initialQuotes.isNotEmpty()) {
            // Emulate the producer's durable boundary, never the ViewModel's restoration.
            val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
            val entry = draftStore.entries[key]?.takeUnless {
                it.attempt?.status == EditorDraftStore.AttemptStatus.PUBLISHED
            }
            val pending = PendingInlineQuoteRequest(SAMPLE_CAT, SAMPLE_TOPIC_ID, initialQuotes.map {
                PendingInlineQuoteRequest.Quote(it.numreponse, it.locator.page, it.locator.ref, it.truncate)
            })
            val draft = (entry?.draft ?: EditorDraftStore.Draft("")).copy(pendingInlineQuoteRequest = pending)
            draftStore.entries[key] = EditorDraftStore.DraftEntry(draft, (entry?.version ?: 0) + 1, entry?.attempt)
        }
        return PostEditorViewModel(
            request = PostEditorRequest(
                mode = PostEditorMode.Reply,
                cat = SAMPLE_CAT,
                topicId = SAMPLE_TOPIC_ID,
                numreponse = null,
                page = SAMPLE_PAGE,
                subcat = subcat,
                initialQuotes = if (cards) initialQuotes else emptyList(),
                resumeSharedDraft = resumeSharedDraft,
            ),
            previewParser = previewParser,
            defaultDispatcher = defaultDispatcher,
            clock = submissionClock,
            replyRepository = replyRepository,
            editPostRepository = editPostRepository,
            smileyRepository = smileyRepository,
            hfrFavoriteSmileyRepository = favoriteRepository,
            smileyLocalCollections = localCollections,
            userPreferencesRepository = userPreferencesRepository,
            draftStore = draftStore,
            diagnostics = diagnostics,
            uploadRepository = uploadRepository,
            imageUploadReader = imageUploadReader,
            authRepository = authRepository,
            quoteMaterializer = TopicReplyQuoteMaterializer(replyRepository),
            savedStateHandle = savedStateHandle,
        ).also(::registerViewModel)
    }

    // ----- Phase 2F-B (#11) / #441 : smiley picker ----------------------------------
    // The picker machinery (open/dismiss, ≤ 2-chars gate, debounce, stale-result guards,
    // #824 restore-on-reopen) lives in the shared SmileyPickerController and is covered by
    // SmileyPickerControllerTest — no duplicate coverage here. These tests only exercise
    // what stays a ViewModel concern : the insertion intent, the userId plumbed from the
    // parsed form, and the diagnostics policy on search failure.

    @Test
    fun `local collections and gestures use the injected shared component`() = runTest {
        val smiley = BUILTIN_HFR_SMILEYS.first()
        localCollections.snapshot.value = SmileyLocalCollectionSnapshot(
            "alice", top = listOf(SmileyUsageEntry(smiley.token, 2, 1)),
        )
        val viewModel = newReplyViewModel()
        settleBody()
        val fetches = replyRepository.formFetches
        viewModel.smileyPicker.open()
        val open = viewModel.smileyPicker.state.value as SmileyPickerState.Open
        assertEquals(listOf(smiley), open.mostUsedSmileys)
        requireNotNull(open.onLocalFavoriteChange)(smiley)
        settleBody()
        assertEquals(listOf(Triple("alice", smiley.token, smiley.imageUrl)), localCollections.added)
        assertEquals(fetches, replyRepository.formFetches)
    }

    @Test
    fun `picker invokes the injected HFR add and remove without reloading the form`() = runTest {
        localCollections.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        val wiki = EditorSmiley("[:fictif_geste:2]", "https://example.invalid/geste.gif", EditorSmileySource.WIKI)
        val favorite = wiki.copy(source = EditorSmileySource.HFR_FAVORITE)
        favoriteRepository.addResult = HfrFavoriteSmileyMutationResult.Success(listOf(favorite))
        val viewModel = newReplyViewModel()
        settleBody()
        val fetches = replyRepository.formFetches
        viewModel.smileyPicker.open()
        viewModel.smileyPicker.onFavoriteClicked(wiki)
        settleBody()
        assertEquals(listOf(wiki.token), favoriteRepository.added)
        assertEquals(listOf(favorite), (viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys)

        viewModel.smileyPicker.onFavoriteClicked(wiki)
        settleBody()
        assertEquals(listOf(wiki.token), favoriteRepository.removed)
        assertEquals(listOf("alice", "alice"), favoriteRepository.owners)
        assertTrue((viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys.isEmpty())
        assertEquals(fetches, replyRepository.formFetches)
    }

    @Test
    fun `late reply edit and inline quote forms cannot authorize favorites after an account switch`() = runTest {
        val favorite = EditorSmiley("[:alice]", "https://example.invalid/a.gif", EditorSmileySource.HFR_FAVORITE)
        val factories = listOf(
            { newReplyViewModel() },
            { newEditViewModel() },
            {
                newReplyViewModel(
                    initialQuotes = listOf(card(101)),
                    userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
                )
            },
        )
        factories.forEach { create ->
            localCollections.snapshot.value = SmileyLocalCollectionSnapshot("alice")
            val gate = CompletableDeferred<Unit>()
            replyRepository.formGate = gate
            editPostRepository.formGate = gate
            val form = authenticatedForm(initialContent = "[quotemsg=101,1,9]Quote[/quotemsg]\n")
                .copy(favoriteSmileys = listOf(favorite))
            replyRepository.formResult = Result.success(form)
            editPostRepository.formResult = Result.success(form)
            val viewModel = create()
            settleBody()
            assertTrue(viewModel.state.value.isLoadingForm)
            localCollections.snapshot.value = SmileyLocalCollectionSnapshot("bob")
            settleBody()
            gate.complete(Unit)
            settleBody()

            assertFalse(viewModel.state.value.isLoadingForm)
            assertNull(viewModel.state.value.submitError)
            viewModel.smileyPicker.open()
            val open = viewModel.smileyPicker.state.value as SmileyPickerState.Open
            assertTrue(open.favoriteSmileys.isEmpty())
            assertNull(open.onFavoriteChange)
            viewModel.smileyPicker.onFavoriteClicked(favorite)
            settleBody()
            assertTrue(favoriteRepository.owners.isEmpty())
        }
    }

    @Test
    fun `HFR form favorites reach the picker and insert the exact perso token`() = runTest {
        val favorite = EditorSmiley(
            "[:fictif espace:3]", "https://example.invalid/fictif.gif", EditorSmileySource.HFR_FAVORITE,
        )
        replyRepository.formResult = Result.success(authenticatedForm().copy(favoriteSmileys = listOf(favorite)))
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.smileyPicker.open()
        val open = viewModel.smileyPicker.state.value as SmileyPickerState.Open
        assertEquals(listOf(favorite), open.favoriteSmileys)
        viewModel.editBody(TextFieldValue("hello", TextRange(5)))
        viewModel.dispatch(PostEditorIntent.SmileySelected(open.favoriteSmileys.single().token))

        assertEquals("hello [:fictif espace:3] ", viewModel.bodyFieldState.text.toString())
        assertEquals(SmileyPickerState.Hidden, viewModel.smileyPicker.state.value)
        assertEquals(0, smileyRepository.callCount)
    }

    @Test
    fun `reloaded reply forms replace favorites then remove the section while the picker stays open`() = runTest {
        val favoriteA = EditorSmiley(
            "[:fictif_a:3]", "https://example.invalid/fictif_a.gif", EditorSmileySource.HFR_FAVORITE,
        )
        val favoriteB = favoriteA.copy(token = "[:fictif_b]", imageUrl = "https://example.invalid/fictif_b.gif")
        replyRepository.formResult = Result.success(authenticatedForm().copy(favoriteSmileys = listOf(favoriteA)))
        replyRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.smileyPicker.open()
        assertEquals(listOf(favoriteA), (viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys)
        viewModel.editBody(TextFieldValue("Reply"))

        replyRepository.formResult = Result.success(authenticatedForm().copy(favoriteSmileys = listOf(favoriteB)))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        assertEquals(2, replyRepository.formFetches)
        assertEquals(listOf(favoriteB), (viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys)

        replyRepository.formResult = Result.success(authenticatedForm())
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        assertEquals(3, replyRepository.formFetches)
        assertTrue((viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys.isEmpty())
    }

    @Test
    fun `the wiki search carries the form's parsed userId (#441)`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm().copy(userId = 54_596))
        val viewModel = newReplyViewModel()
        settleBody() // form fetch → userId hydrated into state

        viewModel.smileyPicker.open()
        viewModel.smileyPicker.onQueryChanged("jap")
        testScheduler.advanceTimeBy(400L)
        syncBody()

        assertEquals(1, smileyRepository.callCount)
        assertEquals("jap", smileyRepository.lastQuery)
        assertEquals(54_596, smileyRepository.lastUserId)
    }

    @Test
    fun `the wiki search falls back to user id 0 while the form exposed no userId`() = runTest {
        val viewModel = newReplyViewModel() // fake form carries no userId → state.userId null
        viewModel.smileyPicker.open()
        viewModel.smileyPicker.onQueryChanged("jap")
        testScheduler.advanceTimeBy(400L)
        syncBody()

        assertEquals(0, smileyRepository.lastUserId)
    }

    @Test
    fun `failed wiki search does not leak user id or query through diagnostics`() = runTest {
        val diagnostics = DiagnosticsLog()
        val viewModel = newReplyViewModel(diagnostics = diagnostics)
        viewModel.smileyPicker.open()
        viewModel.smileyPicker.onQueryChanged("secret")
        testScheduler.advanceTimeBy(400L)
        syncBody()

        smileyRepository.failNext(
            IOException("https://forum.hardware.fr/message-smi-mp-aj.php?user_id=12345&findsmilies=secret"),
        )
        syncBody()

        val messages = diagnostics.entries.value.joinToString(separator = "\n") { it.message }
        assertTrue(messages.contains("wiki smiley search failed: IOException"))
        assertFalse(messages.contains("12345"))
        assertFalse(messages.contains("secret"))
        assertFalse(messages.contains("findsmilies"))
    }

    @Test
    fun `SmileySelected inserts the token at the caret and closes the picker`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.editBody(TextFieldValue("hello", TextRange(5)))
        viewModel.smileyPicker.open()
        viewModel.dispatch(PostEditorIntent.SmileySelected(":jap:"))
        viewModel.state.test {
            val state = expectMostRecentItem()
            // Surrounding spaces convention from `putSmiley` is honoured.
            assertEquals("hello :jap: ", state.bodyText)
            assertEquals(12, viewModel.bodyFieldState.selection.start)
            cancelAndIgnoreRemainingEvents()
        }
        // Picker auto-closes (through the controller) so the user can keep typing ;
        // #824 makes the same controller restore the search on the next open.
        assertEquals(SmileyPickerState.Hidden, viewModel.smileyPicker.state.value)
    }

    @Test
    fun `ImageUrlInserted inserts img token at caret and refreshes visible preview`() = runTest {
        val viewModel = newReplyViewModel()

        viewModel.editBody(TextFieldValue("photo: ", TextRange(7)))
        viewModel.dispatch(PostEditorIntent.TogglePreview)
        viewModel.dispatch(PostEditorIntent.ImageUrlInserted(" https://rehost.diberie.com/Picture/Get/r/511520 "))

        viewModel.state.test {
            val state = expectMostRecentItem()
            val expected = "photo: [img]https://rehost.diberie.com/Picture/Get/r/511520[/img]"
            assertEquals(expected, state.bodyText)
            assertEquals(expected.length, viewModel.bodyFieldState.selection.start)
            assertEquals(previewParser.contentFor(expected), state.preview)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ImageUrlInserted ignores unsafe schemes`() = runTest {
        val viewModel = newReplyViewModel()

        viewModel.editBody(TextFieldValue("photo: ", TextRange(7)))
        viewModel.dispatch(PostEditorIntent.ImageUrlInserted("content://media/external/images/1"))

        viewModel.state.test {
            val state = expectMostRecentItem()
            assertEquals("photo: ", state.bodyText)
            assertEquals(7, viewModel.bodyFieldState.selection.start)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ----- #459 PR2 : image upload from the photo picker ---------------------

    @Test
    fun `cancelled picker preserves the previous error body selection and effects without upload`() = runTest {
        val diagnostics = DiagnosticsLog()
        val viewModel = newReplyViewModel(
            diagnostics = diagnostics,
            authRepository = FakeAuthRepository(AuthState.Authenticated("alice")),
        )
        settleBody()
        viewModel.editBody(TextFieldValue(text = "draft", selection = TextRange(1, 4)))
        settleBody()
        viewModel.dispatch(
            PostEditorIntent.ImagePickerEventReceived(
                ImagePickerEvent.Unavailable(
                    ImagePickerMode.PHOTO_PICKER,
                    ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
                ),
            ),
        )
        settleBody()
        assertEquals(EditorErrors.pickerAbsent, viewModel.state.value.uploadError)
        val stateBefore = viewModel.state.value

        viewModel.effects.test {
            ImagePickerContract.entries.forEach { contract ->
                diagnostics.clear()
                viewModel.dispatch(
                    PostEditorIntent.ImagePickerEventReceived(ImagePickerEvent.Cancelled(contract, resultCode = 0)),
                )
                settleBody()

                assertEquals(stateBefore, viewModel.state.value)
                assertEquals("draft", viewModel.bodyFieldState.text.toString())
                assertEquals(TextRange(1, 4), viewModel.bodyFieldState.selection)
                assertEquals(0, imageUploadReader.readCalls)
                assertEquals(0, uploadRepository.uploadCalls)
                assertEquals(
                    listOf("cancelled contract=${contract.diagnosticName} resultCode=0"),
                    diagnostics.entries.value.filter { it.tag == "ImagePicker" }.map { it.message },
                )
                expectNoEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty picker result shows a banner without starting an upload`() = runTest {
        val diagnostics = DiagnosticsLog()
        val viewModel = newReplyViewModel(
            diagnostics = diagnostics,
            authRepository = FakeAuthRepository(AuthState.Authenticated("alice")),
        )
        settleBody()
        viewModel.editBody(TextFieldValue(text = "draft", selection = TextRange(1, 4)))
        settleBody()
        val stateBefore = viewModel.state.value

        viewModel.effects.test {
            viewModel.dispatch(
                PostEditorIntent.ImagePickerEventReceived(
                    ImagePickerEvent.Result(
                        contract = ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
                        uris = emptyList(),
                    ),
                ),
            )
            settleBody()

            assertEquals(
                stateBefore.copy(uploadError = EditorErrors.noImageReceived),
                viewModel.state.value,
            )
            assertFalse(viewModel.state.value.isUploading)
            assertEquals(0, imageUploadReader.readCalls)
            assertEquals(0, uploadRepository.uploadCalls)
            assertEquals(
                listOf(
                    "result contract=PickMultipleVisualMedia count=0 sources=[]",
                    "onImagesPicked count=0",
                ),
                diagnostics.entries.value.filter { it.tag == "ImagePicker" }.map { it.message },
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.dispatch(PostEditorIntent.UploadErrorDismissed)
        settleBody()
        assertNull(viewModel.state.value.uploadError)
    }

    @Test
    fun `single picker result keeps the upload path and no empty-result error`() = runTest {
        val viewModel = newReplyViewModel(
            authRepository = FakeAuthRepository(AuthState.Authenticated("alice")),
        )
        settleBody()
        val uri = "content://media/picker/photo/1"

        viewModel.dispatch(
            PostEditorIntent.ImagePickerEventReceived(
                ImagePickerEvent.Result(
                    contract = ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
                    uris = listOf(uri),
                ),
            ),
        )
        settleBody()

        assertEquals(listOf(uri), imageUploadReader.readUris)
        assertEquals(1, uploadRepository.uploadCalls)
        assertNull(viewModel.state.value.uploadError)
        assertFalse(viewModel.state.value.isUploading)
    }

    @Test
    fun `photo picker result logs eleven raw uris and keeps the first ten`() = runTest {
        val diagnostics = DiagnosticsLog()
        val viewModel = newReplyViewModel(
            diagnostics = diagnostics,
            authRepository = FakeAuthRepository(AuthState.Authenticated("alice")),
        )
        settleBody()
        val uris = (1..11).map {
            "content://com.android.providers.media.documents/document/image%3A$it"
        }

        viewModel.dispatch(
            PostEditorIntent.ImagePickerEventReceived(
                ImagePickerEvent.Result(
                    contract = ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
                    uris = uris,
                ),
            ),
        )
        settleBody()

        assertEquals(uris.take(10), imageUploadReader.readUris)
        assertEquals(10, uploadRepository.uploadCalls)
        assertEquals(EditorErrors.imageLimit, viewModel.state.value.uploadError)
        val messages = diagnostics.entries.value.filter { it.tag == "ImagePicker" }.map { it.message }
        assertEquals(
            listOf(
                "result contract=PickMultipleVisualMedia count=11 " +
                    "sources=[content://com.android.providers.media.documents]",
                "onImagesPicked count=11",
            ),
            messages,
        )
        assertFalse(messages.joinToString().contains("image%3A"))
        assertFalse(messages.joinToString().contains("document/"))
    }

    @Test
    fun `ImagePicked reads the uri uploads with the lowercased userId and inserts img at caret`() = runTest {
        // Authenticated with a MIXED-CASE pseudo to prove the userId is lowercased before reaching
        // the repository (the upload foundation byte-matches on the lowercased pseudo).
        val authRepository = FakeAuthRepository(AuthState.Authenticated("XaTriX"))
        imageUploadReader.result =
            ImageUpload(bytes = byteArrayOf(1, 2, 3), mimeType = "image/png", displayName = "p.png")
        uploadRepository.uploadResult = uploadedImage("https://rehost.diberie.com/Picture/Get/f/42")
        val viewModel = newReplyViewModel(authRepository = authRepository)
        settleBody()

        val pickedUri = "content://media/external/images/99"
        viewModel.editBody(TextFieldValue("photo: ", TextRange(7)))
        viewModel.dispatch(PostEditorIntent.ImagePicked(pickedUri))
        settleBody()

        assertEquals("the picked uri must be read once", pickedUri, imageUploadReader.lastUri)
        assertEquals("upload called once", 1, uploadRepository.uploadCalls)
        assertEquals("userId must be lowercased before the upload", "xatrix", uploadRepository.lastUserId)
        viewModel.state.test {
            val state = expectMostRecentItem()
            val expected = "photo: [img]https://rehost.diberie.com/Picture/Get/f/42[/img]"
            assertEquals("img token inserted at the caret on success", expected, state.bodyText)
            assertEquals(
                "caret lands after the inserted token",
                expected.length,
                viewModel.bodyFieldState.selection.start,
            )
            assertFalse("upload flag cleared on success", state.isUploading)
            assertNull("no error on success", state.uploadError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ImagePicked surfaces a typed error and inserts nothing when the upload fails`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/jpeg", displayName = null)
        uploadRepository.uploadException = UploadException.Server(code = 503, providerId = UploadProviderId.DIBERIE)
        val viewModel = newReplyViewModel(authRepository = authRepository)
        settleBody()

        viewModel.editBody(TextFieldValue("photo: ", TextRange(7)))
        viewModel.dispatch(PostEditorIntent.ImagePicked("content://media/external/images/1"))
        settleBody()

        viewModel.state.test {
            val state = expectMostRecentItem()
            assertEquals("draft is untouched on failure", "photo: ", state.bodyText)
            // #474 — a non-2xx response maps to a Server error carrying the HTTP code + host, so the
            // banner can name « diberie » and « HTTP 503 » instead of one vague « erreur de l'hébergeur ».
            assertEquals(
                "Server failure maps to a Server upload error with the code + provider",
                EditorErrors.server(code = 503, providerId = UploadProviderId.DIBERIE),
                state.uploadError,
            )
            assertFalse("upload flag cleared on failure", state.isUploading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ImagePicked maps a Malformed host response onto a distinct Malformed error`() = runTest {
        // #474 — a 2xx-but-unreadable body must NOT collapse into the same surface as an HTTP refusal:
        // it carries the host so the banner reads « Réponse illisible de l'hébergeur imgur ».
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = null)
        uploadRepository.uploadException = UploadException.Malformed(providerId = UploadProviderId.IMGUR)
        val viewModel = newReplyViewModel(authRepository = authRepository)
        settleBody()

        viewModel.editBody(TextFieldValue("photo: ", TextRange(7)))
        viewModel.dispatch(PostEditorIntent.ImagePicked("content://media/external/images/2"))
        settleBody()

        val state = viewModel.state.value
        assertEquals("draft is untouched on a malformed response", "photo: ", state.bodyText)
        assertEquals(
            "Malformed maps to a distinct Malformed upload error carrying the provider",
            EditorErrors.malformed(providerId = UploadProviderId.IMGUR),
            state.uploadError,
        )
        assertFalse("upload flag cleared on failure", state.isUploading)
    }

    @Test
    fun `ImagePicked maps TooLarge UnsupportedType and Network onto distinct EditorError variants`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/gif", displayName = null)

        uploadRepository.uploadException = UploadException.TooLarge(maxBytes = 1024)
        val tooLarge = newReplyViewModel(authRepository = authRepository)
        settleBody()
        tooLarge.dispatch(PostEditorIntent.ImagePicked("content://x/1"))
        settleBody()
        assertEquals(EditorErrors.tooLarge, tooLarge.state.value.uploadError)

        uploadRepository.uploadException = UploadException.UnsupportedType(mimeType = "image/gif")
        val unsupported = newReplyViewModel(authRepository = authRepository)
        settleBody()
        unsupported.dispatch(PostEditorIntent.ImagePicked("content://x/2"))
        settleBody()
        assertEquals(EditorErrors.unsupportedType, unsupported.state.value.uploadError)

        uploadRepository.uploadException = UploadException.Network(java.io.IOException("offline"))
        val network = newReplyViewModel(authRepository = authRepository)
        settleBody()
        network.dispatch(PostEditorIntent.ImagePicked("content://x/3"))
        settleBody()
        assertEquals(EditorErrors.uploadNetwork, network.state.value.uploadError)
    }

    @Test
    fun `ImagePicked maps an unreadable picked uri onto the Network error and inserts nothing`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        // The reader fails (UploadException.Network) BEFORE the upload — the repository must never
        // be reached, and the editor surfaces the same Network surface as a transport failure.
        imageUploadReader.exception = UploadException.Network(java.io.IOException("stream gone"))
        val viewModel = newReplyViewModel(authRepository = authRepository)
        settleBody()

        viewModel.editBody(TextFieldValue("photo: ", TextRange(7)))
        viewModel.dispatch(PostEditorIntent.ImagePicked("content://x/1"))
        settleBody()

        assertEquals("a failed read must not reach the upload", 0, uploadRepository.uploadCalls)
        val state = viewModel.state.value
        assertEquals("draft untouched when the read fails", "photo: ", state.bodyText)
        assertEquals(EditorErrors.uploadNetwork, state.uploadError)
        assertFalse(state.isUploading)
    }

    @Test
    fun `ImagePicked toggles isUploading while the upload is in flight`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = null)
        // Hold the upload pending so we can observe the intermediate isUploading = true.
        val gate = CompletableDeferred<Unit>()
        uploadRepository.uploadGate = gate
        uploadRepository.uploadResult = uploadedImage("https://rehost.diberie.com/Picture/Get/f/7")
        val viewModel = newReplyViewModel(authRepository = authRepository)
        settleBody()

        viewModel.dispatch(PostEditorIntent.ImagePicked("content://x/1"))
        syncBody()
        assertTrue("isUploading must be true while the upload is in flight", viewModel.state.value.isUploading)

        gate.complete(Unit)
        settleBody()
        assertFalse("isUploading must clear once the upload resolves", viewModel.state.value.isUploading)
    }

    @Test
    fun `ImagePicked is ignored for an anonymous client (no upload, no error)`() = runTest {
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = null)
        val viewModel = newReplyViewModel(authRepository = FakeAuthRepository(AuthState.Anonymous))
        settleBody()

        viewModel.editBody(TextFieldValue("hi", TextRange(2)))
        viewModel.dispatch(PostEditorIntent.ImagePicked("content://x/1"))
        settleBody()

        assertEquals("anonymous pick must not read the uri", 0, imageUploadReader.readCalls)
        assertEquals("anonymous pick must not upload", 0, uploadRepository.uploadCalls)
        val state = viewModel.state.value
        assertEquals("hi", state.bodyText)
        assertFalse(state.isUploading)
        assertNull(state.uploadError)
    }

    @Test
    fun `a second ImagePicked is ignored while the first upload is still in flight`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = null)
        val gate = CompletableDeferred<Unit>()
        uploadRepository.uploadGate = gate
        uploadRepository.uploadResult = uploadedImage("https://rehost.diberie.com/Picture/Get/f/7")
        val viewModel = newReplyViewModel(authRepository = authRepository)
        settleBody()

        viewModel.dispatch(PostEditorIntent.ImagePicked("content://x/1")) // launches, suspends on gate
        viewModel.dispatch(PostEditorIntent.ImagePicked("content://x/2")) // must be a no-op
        gate.complete(Unit)
        settleBody()

        assertEquals("only the first pick uploads", 1, uploadRepository.uploadCalls)
    }

    @Test
    fun `UploadErrorDismissed clears the upload error banner`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = null)
        uploadRepository.uploadException = UploadException.Network(java.io.IOException("offline"))
        val viewModel = newReplyViewModel(authRepository = authRepository)
        settleBody()
        viewModel.dispatch(PostEditorIntent.ImagePicked("content://x/1"))
        settleBody()
        assertEquals(EditorErrors.uploadNetwork, viewModel.state.value.uploadError)

        viewModel.dispatch(PostEditorIntent.UploadErrorDismissed)

        assertNull("dismissing clears the upload error", viewModel.state.value.uploadError)
    }

    @Test
    fun `ImagesPicked uploads every image and inserts an img per success in pick order`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = null)
        uploadRepository.uploadResults = listOf(
            Result.success(uploadedImage("https://h/Picture/Get/f/1")),
            Result.success(uploadedImage("https://h/Picture/Get/f/2")),
            Result.success(uploadedImage("https://h/Picture/Get/f/3")),
        )
        val viewModel = newReplyViewModel(authRepository = authRepository)
        settleBody()

        viewModel.dispatch(
            PostEditorIntent.ImagesPicked(listOf("content://x/1", "content://x/2", "content://x/3")),
        )
        settleBody()

        assertEquals("every picked image is uploaded", 3, uploadRepository.uploadCalls)
        assertEquals(
            "images are read in pick order (sequential, not reordered)",
            listOf("content://x/1", "content://x/2", "content://x/3"),
            imageUploadReader.readUris,
        )
        val state = viewModel.state.value
        // #459 PR-images follow-up — each image after the first lands on its OWN line (no more
        // run-together uploads). FULL mode here (the fake's default) keeps the plain [img] shape.
        val expected = "[img]https://h/Picture/Get/f/1[/img]\n" +
            "[img]https://h/Picture/Get/f/2[/img]\n" +
            "[img]https://h/Picture/Get/f/3[/img]"
        assertEquals("one [img] per image, each on its own line, in pick order", expected, state.bodyText)
        assertFalse("upload flag cleared at the end of the batch", state.isUploading)
        assertNull("progress cleared at the end of the batch", state.uploadProgress)
        assertNull("no error on a fully successful batch", state.uploadError)
    }

    @Test
    fun `ImagesPicked increments n of N as each image completes and clears it at the end`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = null)
        val gate = CompletableDeferred<Unit>()
        uploadRepository.uploadGate = gate
        // Hold ONLY the 2nd upload so the batch settles at 1/2 — proving the counter increments
        // past its initial 0/N (not just set-then-cleared).
        uploadRepository.gateOnCall = 2
        uploadRepository.uploadResults = listOf(
            Result.success(uploadedImage("https://h/1")),
            Result.success(uploadedImage("https://h/2")),
        )
        val viewModel = newReplyViewModel(authRepository = authRepository)
        settleBody()

        viewModel.dispatch(PostEditorIntent.ImagesPicked(listOf("content://x/1", "content://x/2")))
        settleBody()
        assertEquals(
            "after the first image completes the counter shows 1/2",
            UploadProgress(completed = 1, total = 2),
            viewModel.state.value.uploadProgress,
        )
        assertTrue("still uploading while the 2nd image is in flight", viewModel.state.value.isUploading)

        gate.complete(Unit)
        settleBody()
        assertNull("progress cleared once the batch resolves", viewModel.state.value.uploadProgress)
        assertFalse("isUploading cleared once the batch resolves", viewModel.state.value.isUploading)
    }

    @Test
    fun `ImagesPicked stops at the first failure and keeps the earlier images inserted`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = null)
        uploadRepository.uploadResults = listOf(
            Result.success(uploadedImage("https://h/Picture/Get/f/1")),
            Result.failure(UploadException.Server(code = 500, providerId = UploadProviderId.DIBERIE)),
            Result.success(uploadedImage("https://h/Picture/Get/f/3")),
        )
        val viewModel = newReplyViewModel(authRepository = authRepository)
        settleBody()

        viewModel.dispatch(
            PostEditorIntent.ImagesPicked(listOf("content://x/1", "content://x/2", "content://x/3")),
        )
        settleBody()

        assertEquals("the batch stops after the failing upload — no 3rd attempt", 2, uploadRepository.uploadCalls)
        val state = viewModel.state.value
        assertEquals(
            "only the image uploaded before the failure is inserted",
            "[img]https://h/Picture/Get/f/1[/img]",
            state.bodyText,
        )
        assertEquals(
            "the typed error from the failing upload surfaces",
            EditorErrors.stopped(
                EditorErrors.server(code = 500, providerId = UploadProviderId.DIBERIE), UploadProgress(1, 3),
            ),
            state.uploadError,
        )
        assertFalse("upload flag cleared on failure", state.isUploading)
        assertNull("progress cleared on failure", state.uploadProgress)
    }

    @Test
    fun `ImagesPicked autosaves the images inserted before a later image fails`() = runTest {
        // Codex review #490 — a mid-batch failure must not lose the already-inserted images from
        // draft persistence: each successful insert schedules an autosave, not just the whole batch.
        replyRepository.formResult = Result.success(authenticatedForm())
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = null)
        uploadRepository.uploadResults = listOf(
            Result.success(uploadedImage("https://h/Picture/Get/f/1")),
            Result.failure(UploadException.Network(java.io.IOException("down"))),
        )
        val viewModel = newReplyViewModel(authRepository = authRepository)
        settleBody()

        viewModel.dispatch(PostEditorIntent.ImagesPicked(listOf("content://x/1", "content://x/2")))
        settleBody() // uploads run; 1st inserts + schedules autosave, 2nd fails; debounce elapses

        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        assertEquals(
            "the image inserted before the failure must be persisted",
            "[img]https://h/Picture/Get/f/1[/img]",
            draftStore.saved[key]?.body,
        )
    }

    @Test
    fun `ImagePicked in REDUCED mode wraps the reduced image in a url to the original`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = null)
        uploadRepository.uploadResult = uploadedImage(
            imageUrl = "https://rehost.diberie.com/Picture/Get/f/42",
            resizedUrl = "https://rehost.diberie.com/Picture/Get/r/42",
        )
        val viewModel = newReplyViewModel(
            authRepository = authRepository,
            userPreferencesRepository = FakeUserPreferencesRepository(editorImageInsert = EditorImageInsert.REDUCED),
        )
        settleBody()

        viewModel.dispatch(PostEditorIntent.ImagePicked("content://x/1"))
        settleBody()

        assertEquals(
            "REDUCED shows the reduced URL, clickable to the original",
            "[url=https://rehost.diberie.com/Picture/Get/f/42]" +
                "[img]https://rehost.diberie.com/Picture/Get/r/42[/img][/url]",
            viewModel.bodyFieldState.text.toString(),
        )
    }

    @Test
    fun `ImagePicked in LINKED mode wraps the full image in a url to itself`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = null)
        uploadRepository.uploadResult = uploadedImage("https://h/Picture/Get/f/42")
        val viewModel = newReplyViewModel(
            authRepository = authRepository,
            userPreferencesRepository = FakeUserPreferencesRepository(editorImageInsert = EditorImageInsert.LINKED),
        )
        settleBody()

        viewModel.dispatch(PostEditorIntent.ImagePicked("content://x/1"))
        settleBody()

        assertEquals(
            "LINKED wraps the full image so a click opens the original",
            "[url=https://h/Picture/Get/f/42][img]https://h/Picture/Get/f/42[/img][/url]",
            viewModel.bodyFieldState.text.toString(),
        )
    }

    @Test
    fun `ImagePicked in REDUCED mode with no reduced url falls back to the full url`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = null)
        // imgur exposes no reduced variant (resizedUrl null) → REDUCED degrades to LINKED.
        uploadRepository.uploadResult = uploadedImage("https://i.imgur.com/x.png")
        val viewModel = newReplyViewModel(
            authRepository = authRepository,
            userPreferencesRepository = FakeUserPreferencesRepository(editorImageInsert = EditorImageInsert.REDUCED),
        )
        settleBody()

        viewModel.dispatch(PostEditorIntent.ImagePicked("content://x/1"))
        settleBody()

        assertEquals(
            "no reduced URL → REDUCED falls back to the full URL (same as LINKED)",
            "[url=https://i.imgur.com/x.png][img]https://i.imgur.com/x.png[/img][/url]",
            viewModel.bodyFieldState.text.toString(),
        )
    }

    private fun newEditViewModel(
        subcat: Int? = SAMPLE_SUBCAT,
        numreponse: Int? = SAMPLE_EDITED_NUMREPONSE,
        diagnostics: DiagnosticsLog = DiagnosticsLog(),
        userPreferencesRepository: UserPreferencesRepository = FakeUserPreferencesRepository(),
        authRepository: AuthRepository = FakeAuthRepository(),
    ): PostEditorViewModel =
        PostEditorViewModel(
            request = PostEditorRequest(
                mode = PostEditorMode.Edit,
                cat = SAMPLE_CAT,
                topicId = SAMPLE_TOPIC_ID,
                numreponse = numreponse,
                page = SAMPLE_PAGE,
                subcat = subcat,
            ),
            previewParser = previewParser,
            defaultDispatcher = dispatcher,
            clock = submissionClock,
            replyRepository = replyRepository,
            editPostRepository = editPostRepository,
            smileyRepository = smileyRepository,
            hfrFavoriteSmileyRepository = favoriteRepository,
            smileyLocalCollections = localCollections,
            userPreferencesRepository = userPreferencesRepository,
            draftStore = draftStore,
            diagnostics = diagnostics,
            uploadRepository = uploadRepository,
            imageUploadReader = imageUploadReader,
            authRepository = authRepository,
            quoteMaterializer = TopicReplyQuoteMaterializer(replyRepository),
        ).also(::registerViewModel)

    @Test
    fun `imagePickerMode reflects the observed preference and subsequent changes`() = runTest {
        val preferences = FakeUserPreferencesRepository()
        preferences.setImagePickerMode(ImagePickerMode.DOCUMENT_PICKER)
        val viewModel = newReplyViewModel(userPreferencesRepository = preferences)
        settleBody()

        assertEquals(ImagePickerMode.DOCUMENT_PICKER, viewModel.state.value.imagePickerMode)

        preferences.setImagePickerMode(ImagePickerMode.PHOTO_PICKER)
        settleBody()

        assertEquals(ImagePickerMode.PHOTO_PICKER, viewModel.state.value.imagePickerMode)
    }

    // ----- #312 : confirmation avant publication ------------------------------

    @Test
    fun `confirm-before-posting OFF keeps the one-tap reply submit unchanged`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val viewModel = newReplyViewModel() // default fake → preference OFF
        settleBody()
        viewModel.editBody(TextFieldValue("Hello!", TextRange(6)))

        viewModel.dispatch(PostEditorIntent.SubmitClicked())

        assertEquals("OFF must POST directly, no dialog detour", 1, replyRepository.submitCalls)
        assertFalse(viewModel.state.value.showSubmitConfirmation)
    }

    @Test
    fun `confirm-before-posting ON parks the reply submit behind the confirmation dialog`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val viewModel = newReplyViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        settleBody()
        viewModel.editBody(TextFieldValue("Hello!", TextRange(6)))

        viewModel.dispatch(PostEditorIntent.SubmitClicked())

        assertTrue("the confirmation dialog must be armed", viewModel.state.value.showSubmitConfirmation)
        assertEquals("no POST before the user confirms", 0, replyRepository.submitCalls)
        assertFalse("nothing is in flight while the dialog is up", viewModel.state.value.isSubmitting)
    }

    @Test
    fun `confirm-before-posting ON SubmitConfirmed executes the real submission without re-confirming`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/.../sujet_X_20.htm#bas",
            targetPage = 20,
        )
        val viewModel = newReplyViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        settleBody()
        viewModel.editBody(TextFieldValue("Hello!", TextRange(6)))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        assertEquals(0, replyRepository.submitCalls)

        viewModel.effects.test {
            testScheduler.advanceTimeBy(400L)
            viewModel.dispatch(PostEditorIntent.SubmitConfirmed(viewModel.state.value.armToken))
            val effect = awaitItem()
            assertEquals(PostEditorEffect.SubmitSucceeded(targetPage = 20), effect)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("confirm must bypass the preference and POST exactly once", 1, replyRepository.submitCalls)
        assertFalse(
            "the dialog must close on confirm — no « confirmation → confirmation » loop",
            viewModel.state.value.showSubmitConfirmation,
        )
    }

    @Test
    fun `confirm-before-posting ON dismissing the dialog sends nothing and keeps the draft`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        settleBody()
        viewModel.editBody(TextFieldValue("Hello!", TextRange(6)))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())

        viewModel.dispatch(PostEditorIntent.SubmitConfirmationDismissed)

        assertFalse(viewModel.state.value.showSubmitConfirmation)
        assertEquals("dismiss must not POST anything", 0, replyRepository.submitCalls)
        assertEquals("the draft survives the dismissal", "Hello!", viewModel.bodyFieldState.text.toString())
        assertNull(viewModel.state.value.submitError)
    }

    @Test
    fun `confirm-before-posting ON never confirms an invalid form`() = runTest {
        // The confirmation slots in AFTER validation : a blank draft fails `canSubmit`, so no
        // dialog may appear (confirming an unsendable form would be a lie).
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        settleBody()

        viewModel.dispatch(PostEditorIntent.SubmitClicked())

        assertFalse("invalid form must not raise the dialog", viewModel.state.value.showSubmitConfirmation)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `confirm-before-posting ON also guards the Edit submit`() = runTest {
        editPostRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/foo/bar-sujet_35395_20.htm#t100",
            targetPage = 20,
        )
        val viewModel = newEditViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        settleBody()
        viewModel.editBody(TextFieldValue("rewritten body"))

        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        assertTrue(viewModel.state.value.showSubmitConfirmation)
        assertEquals("no edit POST before the user confirms", 0, editPostRepository.submitCalls)

        testScheduler.advanceTimeBy(400L)
        viewModel.dispatch(PostEditorIntent.SubmitConfirmed(viewModel.state.value.armToken))
        settleBody()

        assertEquals(1, editPostRepository.submitCalls)
        assertFalse(viewModel.state.value.showSubmitConfirmation)
    }

    @Test
    fun `confirm-before-posting ON re-clicking submit keeps the dialog armed without posting`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        settleBody()
        viewModel.editBody(TextFieldValue("Hello!", TextRange(6)))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        assertTrue("the confirmation dialog must be armed", viewModel.state.value.showSubmitConfirmation)

        // Second tap while the dialog is up (double-tap race) : idempotent — re-raising
        // `showSubmitConfirmation = true` is a no-op, and no POST may slip through.
        viewModel.dispatch(PostEditorIntent.SubmitClicked())

        assertTrue("the dialog must stay armed", viewModel.state.value.showSubmitConfirmation)
        assertFalse("nothing is in flight while the dialog is up", viewModel.state.value.isSubmitting)
        assertEquals("no POST while the dialog is parked", 0, replyRepository.submitCalls)
    }

    @Test
    fun `confirm-before-posting ON rapid double confirm posts exactly once`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        // Hold the first confirmed submit in flight : with UnconfinedTestDispatcher a
        // non-suspending fake would complete synchronously and the second confirm would
        // legitimately re-fire. Same shape as `double submit only triggers one POST`.
        val gate = CompletableDeferred<Unit>()
        replyRepository.submitGate = gate
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val viewModel = newReplyViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        settleBody()
        viewModel.editBody(TextFieldValue("Hello!", TextRange(6)))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        assertTrue("the confirmation dialog must be armed", viewModel.state.value.showSubmitConfirmation)

        testScheduler.advanceTimeBy(400L)
        viewModel.dispatch(PostEditorIntent.SubmitConfirmed(viewModel.state.value.armToken))
        viewModel.dispatch(PostEditorIntent.SubmitConfirmed(viewModel.state.value.armToken))

        gate.complete(Unit)

        assertEquals("rapid double confirm must POST exactly once", 1, replyRepository.submitCalls)
        assertFalse(viewModel.state.value.showSubmitConfirmation)
        assertFalse(viewModel.state.value.isSubmitting)
    }

    // ----- #405 : draft autosave / restore -----------------------------------

    @Test
    fun `autosave persists the body under the reply key after the debounce`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.editBody(TextFieldValue("draft body", TextRange(10)))
        // Below the 750 ms debounce nothing is written yet.
        testScheduler.advanceTimeBy(300L)
        syncBody()
        assertEquals("no save before the debounce window elapses", 0, draftStore.saveCount)

        testScheduler.advanceTimeBy(500L)
        syncBody()
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        assertEquals("draft body", draftStore.saved[key]?.body)
        assertFalse("reply drafts are not private", draftStore.saved[key]?.isPrivate == true)
    }

    @Test
    fun `autosave coalesces a burst of keystrokes into a single write`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.editBody(TextFieldValue("a"))
        testScheduler.advanceTimeBy(200L)
        viewModel.editBody(TextFieldValue("ab"))
        testScheduler.advanceTimeBy(200L)
        viewModel.editBody(TextFieldValue("abc"))
        testScheduler.advanceTimeBy(800L)
        syncBody()

        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        assertEquals("only the final value survives the debounce", 1, draftStore.saveCount)
        assertEquals("abc", draftStore.saved[key]?.body)
    }

    @Test
    fun `emptying the draft without a pending offer deletes the cached row`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.editBody(TextFieldValue("something"))
        testScheduler.advanceTimeBy(800L)
        syncBody()
        viewModel.editBody(TextFieldValue(""))
        testScheduler.advanceTimeBy(800L)
        syncBody()

        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        assertEquals("", draftStore.saved[key]?.body)
    }

    @Test
    fun `a stored draft is surfaced as restorable on init (never auto-applied)`() = runTest {
        draftStore.preload(
            EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID),
            EditorDraftStore.Draft(body = "rescued text"),
        )
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals("draft is offered, not silently applied", "rescued text", settled.restorableDraft)
            assertEquals("the live draft stays empty until the user restores", "", settled.bodyText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `field refuses input until the initial owner read is complete`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        draftStore.preload(key, EditorDraftStore.Draft(body = "already visible"))
        draftStore.loadGate = CompletableDeferred()
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        syncBody()

        viewModel.editBody(TextFieldValue("already visible"))
        assertTrue(viewModel.state.value.isInitializing)
        assertEquals("", viewModel.bodyFieldState.text.toString())
        draftStore.loadGate?.complete(Unit)
        settleBody()

        assertEquals("", viewModel.bodyFieldState.text.toString())
        assertEquals("already visible", viewModel.state.value.restorableDraft)
    }

    @Test
    fun `upload cannot start before the initial owner read`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        draftStore.preload(key, EditorDraftStore.Draft(body = "older draft"))
        draftStore.loadGate = CompletableDeferred()
        replyRepository.formResult = Result.success(authenticatedForm())
        uploadRepository.uploadResult = uploadedImage("https://h/Picture/Get/f/1415")
        imageUploadReader.result =
            ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = "picked.png")
        val viewModel = newReplyViewModel(
            authRepository = FakeAuthRepository(AuthState.Authenticated("alice")),
        )
        syncBody()

        viewModel.dispatch(
            PostEditorIntent.ImagePickerEventReceived(
                ImagePickerEvent.Result(
                    contract = ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
                    uris = listOf("content://picker/1415"),
                ),
            ),
        )
        settleBody()
        draftStore.loadGate?.complete(Unit)
        settleBody()

        assertEquals("older draft", draftStore.saved[key]?.body)
        assertEquals("older draft", viewModel.state.value.restorableDraft)
        assertEquals(0, uploadRepository.uploadCalls)
    }

    @Test
    fun `ViewModel recreation reoffers before decision then silently resumes a matching checkpoint`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val savedStateHandle = SavedStateHandle()
        draftStore.preload(key, EditorDraftStore.Draft(body = "rescued text"))
        replyRepository.formResult = Result.success(authenticatedForm())
        val first = newReplyViewModel(savedStateHandle = savedStateHandle)
        settleBody()
        assertEquals("rescued text", first.state.value.restorableDraft)
        assertNull(savedStateHandle.get<String>("draft_restore_offer_fingerprint"))

        val recreated = newReplyViewModel(savedStateHandle = savedStateHandle)
        settleBody()
        assertEquals("rescued text", recreated.state.value.restorableDraft)

        recreated.editBody(TextFieldValue("new live text"))
        testScheduler.advanceTimeBy(800L)
        syncBody()
        assertEquals("new live text", draftStore.saved[key]?.body)
        assertEquals("rescued text", recreated.state.value.restorableDraft)
        assertEquals("tester", draftStore.lastSavedOwner)

        recreated.dispatch(PostEditorIntent.DraftRestoreRequested)
        settleBody()
        assertNull(savedStateHandle.get<String>("draft_restore_offer_fingerprint"))
        assertEquals("new live text\n\nrescued text", draftStore.saved[key]?.body)

        val afterRestore = newReplyViewModel(savedStateHandle = savedStateHandle)
        settleBody()
        assertNull(afterRestore.state.value.restorableDraft)
        assertEquals("new live text\n\nrescued text", afterRestore.bodyFieldState.text.toString())
    }

    @Test
    fun `process recreation offers a draft whose version diverges from the checkpoint`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val savedStateHandle = SavedStateHandle()
        draftStore.preload(key, EditorDraftStore.Draft(body = "first offered version"))
        replyRepository.formResult = Result.success(authenticatedForm())
        val first = newReplyViewModel(savedStateHandle = savedStateHandle)
        settleBody()
        assertEquals("first offered version", first.state.value.restorableDraft)

        first.editBody(TextFieldValue("newer autosaved version"))
        testScheduler.advanceTimeBy(751)
        syncBody()
        assertEquals("newer autosaved version", draftStore.saved[key]?.body)
        assertEquals("first offered version", first.state.value.restorableDraft)
        first.viewModelScope.cancel()
        draftStore.entries[key] = checkNotNull(draftStore.entries[key]).let { it.copy(version = it.version + 1) }
        val recreated = newReplyViewModel(savedStateHandle = savedStateHandle)
        settleBody()

        assertEquals("newer autosaved version", recreated.state.value.restorableDraft)
    }

    @Test
    fun `edit server hydration does not hide a different cached draft`() = runTest {
        draftStore.preload(
            EditorDraftKey.editPost(SAMPLE_CAT, SAMPLE_EDITED_NUMREPONSE),
            EditorDraftStore.Draft(body = "unfinished rewrite"),
        )

        val viewModel = newEditViewModel()
        settleBody()

        assertEquals("existing post body", viewModel.bodyFieldState.text.toString())
        assertEquals("unfinished rewrite", viewModel.state.value.restorableDraft)
    }

    @Test
    fun `edit server hydration removes an identical cached draft offer`() = runTest {
        draftStore.preload(
            EditorDraftKey.editPost(SAMPLE_CAT, SAMPLE_EDITED_NUMREPONSE),
            EditorDraftStore.Draft(body = "existing post body"),
        )

        val viewModel = newEditViewModel()
        settleBody()

        assertEquals("existing post body", viewModel.bodyFieldState.text.toString())
        assertNull(viewModel.state.value.restorableDraft)
    }

    @Test
    fun `moving selection in a hydrated post keeps the restore offer`() = runTest {
        draftStore.preload(
            EditorDraftKey.editPost(SAMPLE_CAT, SAMPLE_EDITED_NUMREPONSE),
            EditorDraftStore.Draft(body = "unfinished rewrite"),
        )
        val viewModel = newEditViewModel()
        settleBody()
        val hydrated = viewModel.bodyValue()

        viewModel.editBody(hydrated.copy(selection = TextRange(3)))
        testScheduler.advanceTimeBy(800L)
        syncBody()

        assertEquals("unfinished rewrite", viewModel.state.value.restorableDraft)
        val key = EditorDraftKey.editPost(SAMPLE_CAT, SAMPLE_EDITED_NUMREPONSE)
        assertEquals("unfinished rewrite", draftStore.saved[key]?.body)
    }

    @Test
    fun `restoring fills the draft and clears the banner`() = runTest {
        draftStore.preload(
            EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID),
            EditorDraftStore.Draft(body = "rescued text"),
        )
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.dispatch(PostEditorIntent.DraftRestoreRequested)
        settleBody()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals("rescued text", settled.bodyText)
            assertEquals("caret lands at the end of the restored body", 12, viewModel.bodyFieldState.selection.start)
            assertNull("the banner is cleared after restoring", settled.restorableDraft)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restoring appends the offered body after text typed since the offer`() = runTest {
        draftStore.preload(
            EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID),
            EditorDraftStore.Draft(body = "rescued text"),
        )
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.editBody(TextFieldValue("fresh text"))
        viewModel.dispatch(PostEditorIntent.DraftRestoreRequested)

        assertEquals("fresh text\n\nrescued text", viewModel.bodyFieldState.text.toString())
        assertNull(viewModel.state.value.restorableDraft)
    }

    @Test
    fun `ED52 process replay refetches pending content and keeps the restored quote exactly once`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val quote = "[quotemsg=101]already quoted[/quotemsg]"
        val body = "[quotemsg=101]already quoted[/quotemsg]\n\nrescued reply"
        val pending = PendingInlineQuoteRequest(SAMPLE_CAT, SAMPLE_TOPIC_ID,
            listOf(PendingInlineQuoteRequest.Quote(101, SAMPLE_PAGE)))
        draftStore.preload(key, EditorDraftStore.Draft(body, pendingInlineQuoteRequest = pending))
        replyRepository.formResult = Result.success(authenticatedForm(quote))
        val vm = newReplyViewModel(
            quoteAction = false,
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()
        assertEquals(body, vm.state.value.restorableDraft)
        assertEquals(0, replyRepository.formFetches)
        assertFalse(vm.state.value.canSubmit)
        assertEquals(listOf(101), vm.state.value.pendingQuotes.map { it.numreponse })
        vm.dispatch(PostEditorIntent.DraftRestoreRequested)
        settleBody()
        assertEquals(body, vm.bodyFieldState.text.toString())
        assertEquals(TextRange(body.length), vm.bodyFieldState.selection)
        assertEquals("", vm.state.value.draftRestoreSeed)
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertEquals(listOf(101), replyRepository.fetchedContexts.map { it.quotedNumreponse })
        assertEquals(body, draftStore.saved[key]?.body)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertEquals(1, vm.chromeEvents.state.events.count {
            it.content == EditorEventContent.Information(EditorText(R.string.editor_quote_already_delivered))
        })
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ED52 offline process replay then Ignore keeps restored portion A exactly once`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val quote = "[quotemsg=101]portion A[/quotemsg]"
        val body = "$quote\n\nrescued reply"
        val pending = PendingInlineQuoteRequest(SAMPLE_CAT, SAMPLE_TOPIC_ID,
            listOf(PendingInlineQuoteRequest.Quote(101, SAMPLE_PAGE)))
        draftStore.preload(key, EditorDraftStore.Draft(body, pendingInlineQuoteRequest = pending))
        replyRepository.formException = IOException("offline")
        val vm = newReplyViewModel(quoteAction = false,
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false))
        assertEquals(body, vm.state.value.restorableDraft)
        assertEquals(0, replyRepository.formFetches)
        vm.dispatch(PostEditorIntent.DraftRestoreRequested)
        assertEquals(body, vm.bodyFieldState.text.toString())
        assertEquals(EditorErrors.quoteFetchFailed(inline = true), vm.state.value.submitError)
        assertEquals(pending, draftStore.saved[key]?.pendingInlineQuoteRequest)
        vm.dispatch(PostEditorIntent.ReloadRequested)
        assertEquals(2, vm.state.value.quoteFetchFailures)
        assertTrue(vm.state.value.canIgnorePendingQuote)
        vm.dispatch(PostEditorIntent.PendingQuoteIgnored)
        settleBody()
        assertEquals(body, vm.bodyFieldState.text.toString())
        assertEquals(body, draftStore.saved[key]?.body)
        assertEquals(1, Regex("\\[quotemsg=101]").findAll(vm.bodyFieldState.text).count())
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertFalse(vm.state.value.canIgnorePendingQuote)
        assertEquals(listOf(101, 101, null), replyRepository.fetchedContexts.map { it.quotedNumreponse })
        assertFalse(vm.chromeEvents.state.events.any { it.content is EditorEventContent.Information })
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `ignoring the restore offer keeps the inline request and serves it after the decision`() = runTest {
        draftStore.preload(EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID), EditorDraftStore.Draft("rescued reply"))
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm("[quotemsg=101]requested quote[/quotemsg]")),
        )
        val vm = newReplyViewModel(
            initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()
        assertEquals(0, replyRepository.formFetches)
        vm.dispatch(PostEditorIntent.DraftDiscardRequested)
        settleBody()
        assertNull(vm.state.value.restorableDraft)
        assertEquals("[quotemsg=101]requested quote[/quotemsg]\n", vm.bodyFieldState.text.toString())
        assertEquals("", vm.state.value.draftRestoreSeed)
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertEquals(listOf(101), replyRepository.fetchedContexts.map { it.quotedNumreponse })
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `discarding deletes the cached draft and clears the banner`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        val savedStateHandle = SavedStateHandle()
        draftStore.preload(key, EditorDraftStore.Draft(body = "rescued text"))
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(savedStateHandle = savedStateHandle)
        settleBody()

        viewModel.dispatch(PostEditorIntent.DraftDiscardRequested)
        settleBody()

        assertEquals("", draftStore.saved[key]?.body)
        assertNull("the banner is cleared after discarding", viewModel.state.value.restorableDraft)
        assertTrue(savedStateHandle.get<String>("draft_restore_offer_fingerprint") != null)

        val recreated = newReplyViewModel(savedStateHandle = savedStateHandle)
        settleBody()
        assertNull(recreated.state.value.restorableDraft)
    }

    @Test
    fun `discarding cancels a pending autosave before deleting the cached draft`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        draftStore.preload(key, EditorDraftStore.Draft(body = "rescued text"))
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.editBody(TextFieldValue("fresh text"))
        viewModel.dispatch(PostEditorIntent.DraftDiscardRequested)
        settleBody()

        assertEquals("Ignore persists the live field once without reapplying the offer", 1, draftStore.saveCount)
        assertEquals("fresh text", draftStore.saved[key]?.body)
    }

    @Test
    fun `a successful reply submit deletes the cached draft`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val viewModel = newReplyViewModel()
        settleBody()
        viewModel.editBody(TextFieldValue("Hello!", TextRange(6)))

        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        assertEquals("", draftStore.saved[key]?.body)
        assertEquals(EditorDraftStore.AttemptStatus.PUBLISHED, draftStore.entries[key]?.attempt?.status)
    }

    @Test
    fun `Edit autosave and delete-on-submit use the editPost key`() = runTest {
        editPostRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/foo/bar-sujet_35395_20.htm#t100",
            targetPage = 20,
        )
        val viewModel = newEditViewModel()
        settleBody()

        viewModel.editBody(TextFieldValue("rewritten body"))
        testScheduler.advanceTimeBy(800L)
        syncBody()
        val key = EditorDraftKey.editPost(SAMPLE_CAT, SAMPLE_EDITED_NUMREPONSE)
        assertEquals("rewritten body", draftStore.saved[key]?.body)

        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()
        assertEquals("", draftStore.saved[key]?.body)
        assertEquals(EditorDraftStore.AttemptStatus.PUBLISHED, draftStore.entries[key]?.attempt?.status)
    }

    // ----- #790 : resumeSharedDraft (escalade de la réponse rapide) ----------

    @Test
    fun `resumeSharedDraft auto-applies the shared draft without the banner`() = runTest {
        draftStore.preload(
            EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID),
            EditorDraftStore.Draft(body = "texte de la sheet"),
        )
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(resumeSharedDraft = true)
        settleBody()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals("texte de la sheet", settled.bodyText)
            assertNull("no banner on an escalation hand-over", settled.restorableDraft)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sharedDraftReadPrecedesUploadAndPersistsTheInsertedImage() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        draftStore.preload(key, EditorDraftStore.Draft("shared body"))
        val loadGate = CompletableDeferred<Unit>()
        draftStore.loadGate = loadGate
        val uploadGate = CompletableDeferred<Unit>()
        uploadRepository.uploadGate = uploadGate
        val vm = newReplyViewModel(resumeSharedDraft = true)
        assertEquals("", vm.bodyFieldState.text.toString())
        assertEquals(0, replyRepository.formFetches)
        vm.dispatch(PostEditorIntent.ImagePicked("content://pick/1"))
        assertFalse(vm.state.value.isUploading)

        loadGate.complete(Unit)
        settleBody()

        assertEquals("shared body", vm.bodyFieldState.text.toString())
        assertEquals("shared body", vm.state.value.bodyText)
        assertEquals("shared body", draftStore.saved[key]?.body)
        assertNull(vm.state.value.restorableDraft)
        vm.dispatch(PostEditorIntent.ImagePicked("content://pick/1"))
        assertTrue(vm.state.value.isUploading)
        uploadGate.complete(Unit)
        settleBody()
        val expected = "shared body[img]${uploadRepository.uploadResult.imageUrl}[/img]"
        assertFalse(vm.state.value.isUploading)
        assertEquals(expected, vm.bodyFieldState.text.toString())
        assertEquals(expected, vm.state.value.bodyText)
        assertEquals(expected, draftStore.saved[key]?.body)
        assertNull(vm.state.value.restorableDraft)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `resumeSharedDraft rehydrates each new ViewModel without duplicating the stored text`() = runTest {
        val savedStateHandle = SavedStateHandle()
        draftStore.preload(
            EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID),
            EditorDraftStore.Draft(body = "texte de la sheet"),
        )
        replyRepository.formResult = Result.success(authenticatedForm())
        val first = newReplyViewModel(
            resumeSharedDraft = true,
            savedStateHandle = savedStateHandle,
        )
        settleBody()
        assertEquals("texte de la sheet", first.bodyFieldState.text.toString())
        first.dispatch(PostEditorIntent.Stopped)
        first.viewModelScope.cancel()

        val recreated = newReplyViewModel(
            resumeSharedDraft = true,
            savedStateHandle = savedStateHandle,
        )
        settleBody()

        assertEquals("texte de la sheet", recreated.bodyFieldState.text.toString())
        assertEquals("texte de la sheet", draftStore.saved[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)]?.body)
        assertNull(recreated.state.value.restorableDraft)
    }

    @Test
    fun `resumeSharedDraft with no stored draft leaves the field empty — cards carry the citations`() = runTest {
        // (Replaced the pre-lot-3 « prepend the quote prefill » contract : the plain reply form
        // has no prefill anymore, the escalated citations arrive as cards.)
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(resumeSharedDraft = true, initialQuotes = listOf(card(101)))
        settleBody()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals("", settled.bodyText)
            assertNull(settled.restorableDraft)
            assertEquals(listOf(101), settled.quotes.map { it.numreponse })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ----- #805 : cartes OFF (défaut production) — [quotemsg] hydraté à l'ouverture ----------

    @Test
    fun `cards OFF - the open fetch is the quote form and the field hydrates the merged prefills`() = runTest {
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
            202 to Result.success(authenticatedForm(initialContent = "[quotemsg=202,2,9]b[/quotemsg]\n\n")),
        )
        val viewModel = newReplyViewModel(
            initialQuotes = listOf(card(101), card(202)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(
                "pre-lot-3 flow restored : the field hydrates the merged [quotemsg] prefills",
                "[quotemsg=101,1,9]a[/quotemsg]\n\n[quotemsg=202,2,9]b[/quotemsg]",
                settled.bodyText.trimEnd(),
            )
            assertTrue("no card in inline mode", settled.quotes.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            "the open fetch replays the quote contract in citation order",
            listOf(101, 202),
            replyRepository.fetchedContexts.map { it.quotedNumreponse },
        )
    }

    @Test
    fun `cards OFF - a quote block ending the field carries exactly one trailing newline`() = runTest {
        // #881 — typing starts under the citation : one normalised newline (never two, never
        // the raw HFR trailing newline), caret on the fresh line.
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
        )
        val viewModel = newReplyViewModel(
            initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()

        val draft = viewModel.bodyValue()
        assertEquals("[quotemsg=101,1,9]a[/quotemsg]\n", draft.text)
        assertEquals("caret sits after the newline", draft.text.length, draft.selection.start)
    }

    @Test
    fun `cards OFF - a truncated quote prefill is inserted when the selection requests it`() = runTest {
        val prefill = "[quotemsg=101,1,9]${longQuoteBody()}[/quotemsg]"
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = prefill)),
        )
        val viewModel = newReplyViewModel(
            initialQuotes = listOf(card(101).copy(truncate = true)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()

        assertEquals(truncateQuote(prefill) + "\n", viewModel.bodyFieldState.text.toString())
    }

    @Test
    fun `cards OFF - a quote prepended onto typed text keeps the plain double-newline separator`() = runTest {
        // #881 réserve gate — the prepend branch adds NO extra newline : the "\n\n" separator
        // already puts the existing typing under the citation. The fetch is gated so the
        // typing deterministically lands first (same interleave as the race test above).
        val formGate = CompletableDeferred<Unit>()
        replyRepository.formGate = formGate
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
        )
        val viewModel = newReplyViewModel(
            initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        viewModel.editBody(TextFieldValue("déjà tapé"))
        formGate.complete(Unit)
        settleBody()

        assertEquals(
            "[quotemsg=101,1,9]a[/quotemsg]\n\ndéjà tapé",
            viewModel.bodyFieldState.text.toString(),
        )
    }

    @Test
    fun `cards OFF - resumeSharedDraft appends after the prefill with exactly one quote block`() = runTest {
        // Réserve Codex n°5 — the #790 trio restored : quote-form hydration (prepend) + shared-row
        // append must compose to ONE [quotemsg] block and a stable order, whichever lands first.
        draftStore.preload(
            EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID),
            EditorDraftStore.Draft(body = "texte de la sheet"),
        )
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
        )
        val viewModel = newReplyViewModel(
            resumeSharedDraft = true,
            initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(
                "[quotemsg=101,1,9]a[/quotemsg]\n\ntexte de la sheet",
                settled.bodyText,
            )
            assertNull("no banner on an escalation hand-over", settled.restorableDraft)
            assertEquals(
                "exactly one quote block — no double hydration",
                1,
                Regex("\\[quotemsg=101").findAll(settled.bodyText).count(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cards OFF - submit rides the plain path with the field content`() = runTest {
        // Réserve Codex n°6 — the content already carries the [quotemsg] block ; the submit is
        // the plain path (no re-materialisation), riding the warmed quote form.
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
        )
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val viewModel = newReplyViewModel(
            initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()
        val openFetches = replyRepository.formFetches

        viewModel.editBody(TextFieldValue("[quotemsg=101,1,9]a[/quotemsg]\n\nma réponse"))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        assertEquals("no re-materialisation at submit", openFetches, replyRepository.formFetches)
        assertEquals("[quotemsg=101,1,9]a[/quotemsg]\n\nma réponse", replyRepository.lastSubmittedBbcode)
        assertNull(
            "plain submit context — the quote already lives in the content",
            replyRepository.lastSubmittedContext?.quotedNumreponse,
        )
    }

    @Test
    fun `cards OFF - submit forwards the inline quotemsg numreponses for the topic landing (#974)`() = runTest {
        // Production default : no card is armed, the citations live as `[quotemsg]` BBCode in the
        // field (one prefilled at opening, one typed by hand). The success effect must still name
        // every cited post — the topic engine lands on the highest one (#974).
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
        )
        replyRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/foo/bar-sujet_35395_20.htm#t101",
            targetPage = 20,
            numreponse = 101,
        )
        val viewModel = newReplyViewModel(
            initialQuotes = listOf(card(101)),
            userPreferencesRepository = FakeUserPreferencesRepository(quoteCardsEnabled = false),
        )
        settleBody()
        assertTrue("inline mode arms no card", viewModel.state.value.quotes.isEmpty())
        viewModel.editBody(TextFieldValue(
                    "[quotemsg=101,1,9]a[/quotemsg]\n\n[quotemsg=303,3,9]c[/quotemsg]\n\nma réponse",
                ))

        viewModel.effects.test {
            viewModel.dispatch(PostEditorIntent.SubmitClicked())
            assertEquals(
                PostEditorEffect.SubmitSucceeded(
                    targetPage = 20,
                    scrollTo = 101,
                    quotedNumreponses = listOf(101, 303),
                    operation = PostSubmitOperation.Publish,
                ),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ----- #604 lot 3 : cartes de citation dans l'éditeur ---------------------

    @Test
    fun `durable inline request keeps its rendering and preserves distinct new card previews`() = runTest {
        val pending = PendingInlineQuoteRequest(SAMPLE_CAT, SAMPLE_TOPIC_ID,
            listOf(PendingInlineQuoteRequest.Quote(101, SAMPLE_PAGE)))
        draftStore.preload(EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID),
            EditorDraftStore.Draft(body = "", pendingInlineQuoteRequest = pending))
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm("[quotemsg=101]durable quote[/quotemsg]")),
            202 to Result.success(authenticatedForm("[quotemsg=202]card quote[/quotemsg]")),
        )
        val vm = newReplyViewModel(initialQuotes = listOf(card(101), card(202)))
        settleBody()
        assertEquals("[quotemsg=101]durable quote[/quotemsg]\n", vm.bodyFieldState.text.toString())
        assertEquals(listOf(202), vm.state.value.quotes.map { it.numreponse })
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertNull(draftStore.saved[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)]?.pendingInlineQuoteRequest)
        assertEquals(0, replyRepository.submitCalls)
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        vm.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()
        assertEquals(1, replyRepository.submitCalls)
        assertEquals("[quotemsg=202]card quote[/quotemsg]\n\n[quotemsg=101]durable quote[/quotemsg]\n",
            replyRepository.lastSubmittedBbcode)
        assertEquals(listOf(101, 202), replyRepository.fetchedContexts.mapNotNull { it.quotedNumreponse })
    }

    @Test
    fun `initial quote cards are seeded in order and deduplicated`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(initialQuotes = listOf(card(202), card(101), card(202)))
        assertEquals(listOf(202, 101), viewModel.state.value.quotes.map { it.numreponse })
    }

    @Test
    fun `QuoteRemoved and QuotesCleared drop cards without touching the body`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(initialQuotes = listOf(card(101), card(202)))
        viewModel.editBody(TextFieldValue("body"))

        viewModel.dispatch(PostEditorIntent.QuoteRemoved(101))
        assertEquals(listOf(202), viewModel.state.value.quotes.map { it.numreponse })

        viewModel.dispatch(PostEditorIntent.QuotesCleared)
        assertEquals(emptyList<Int>(), viewModel.state.value.quotes.map { it.numreponse })
        assertEquals("body untouched by card mutations", "body", viewModel.bodyFieldState.text.toString())
    }

    @Test
    fun `QuoteMoved reorders within bounds and no-ops outside`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(initialQuotes = listOf(card(101), card(202), card(303)))

        viewModel.dispatch(PostEditorIntent.QuoteMoved(303, delta = -1))
        assertEquals(listOf(101, 303, 202), viewModel.state.value.quotes.map { it.numreponse })

        viewModel.dispatch(PostEditorIntent.QuoteMoved(101, delta = -1))
        assertEquals(
            "first card cannot move up",
            listOf(101, 303, 202),
            viewModel.state.value.quotes.map { it.numreponse },
        )

        viewModel.dispatch(PostEditorIntent.QuoteMoved(999, delta = 1))
        assertEquals(
            "unknown card is a no-op",
            listOf(101, 303, 202),
            viewModel.state.value.quotes.map { it.numreponse },
        )
    }

    @Test
    fun `R2a repeated exact snapshot moves preserve the card and use its current position`() = runTest {
        val quotes = listOf(card(101), card(202), card(303), card(404))
        val quote = quotes[2]
        val viewModel = newReplyViewModel(initialQuotes = quotes)
        val move = PostEditorIntent.QuoteMoved(quote.numreponse, delta = -1, quote = quote)

        viewModel.dispatch(move)
        assertEquals(listOf(quotes[0], quote, quotes[1], quotes[3]), viewModel.state.value.quotes)
        assertSame("The Compose key remains the exact selected snapshot", quote, viewModel.state.value.quotes[1])

        viewModel.dispatch(move)
        assertEquals(listOf(quote, quotes[0], quotes[1], quotes[3]), viewModel.state.value.quotes)
        assertSame(quote, viewModel.state.value.quotes.first())
    }

    @Test
    fun `a quotes-only reply is submittable and keeps no trailing blank lines`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
            202 to Result.success(authenticatedForm(initialContent = "[quotemsg=202,2,9]b[/quotemsg]\n\n")),
        )
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val viewModel = newReplyViewModel(initialQuotes = listOf(card(101), card(202)))
        settleBody()

        assertTrue("cards alone must arm the submit (blank body)", viewModel.state.value.canSubmit)
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        assertEquals(
            "quotes-only reply: pinned BBCode, no trailing blank lines (same contract as the sheet)",
            "[quotemsg=101,1,9]a[/quotemsg]\n\n[quotemsg=202,2,9]b[/quotemsg]",
            replyRepository.lastSubmittedBbcode,
        )
    }

    @Test
    fun `submit with cards honours the editor's user-edited options`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
        )
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val viewModel = newReplyViewModel(initialQuotes = listOf(card(101)))
        settleBody()

        viewModel.dispatch(PostEditorIntent.ToggleSignature(true))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        settleBody()

        assertTrue(
            "the POST must ride the state's toggles, not the quote form's defaults",
            replyRepository.lastSubmittedOptions?.signatureEnabled == true,
        )
    }

    // ----- #604 lot 4a : dirty close (flush avant pop) -------------------------

    @Test
    fun `CloseRequested flushes the pending debounce before CloseCommitted`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        settleBody()

        // Type, then close IMMEDIATELY — well inside the 750 ms debounce window.
        viewModel.editBody(TextFieldValue("dernier mot"))
        viewModel.dispatch(PostEditorIntent.CloseRequested)

        val effect = viewModel.effects.first()
        assertEquals(PostEditorEffect.CloseCommitted, effect)
        assertEquals(
            "the tail of the draft must reach the row before the pop",
            "dernier mot",
            draftStore.saved[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)]?.body,
        )
    }

    @Test
    fun `CloseRequested with a blank live body preserves a pending restore offer`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        draftStore.preload(key, EditorDraftStore.Draft(body = "stale"))
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.dispatch(PostEditorIntent.CloseRequested)

        val effect = viewModel.effects.first()
        assertEquals(PostEditorEffect.CloseCommitted, effect)
        assertEquals("closing must not rewrite a pending offer", 0, draftStore.saveCount)
        assertFalse(draftStore.deletedKeys.contains(key))
        assertEquals("stale", draftStore.saved[key]?.body)
        assertEquals("stale", viewModel.state.value.restorableDraft)
    }

    @Test
    fun `CloseRequested during an in-flight submit is ignored (gate #803)`() = runTest {
        val submitGate = CompletableDeferred<Unit>()
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        replyRepository.submitGate = submitGate
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.editBody(TextFieldValue("en vol"))
        viewModel.dispatch(PostEditorIntent.SubmitClicked())
        // Back pressed while the POST is in flight — must be inert (parity with the sheet, #788).
        viewModel.dispatch(PostEditorIntent.CloseRequested)
        submitGate.complete(Unit)
        settleBody()

        viewModel.effects.test {
            assertTrue(
                "the submit outcome must be the ONLY effect — no CloseCommitted",
                awaitItem() is PostEditorEffect.SubmitSucceeded,
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a second CloseRequested is a no-op (gate #803)`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        settleBody()

        viewModel.dispatch(PostEditorIntent.CloseRequested)
        viewModel.dispatch(PostEditorIntent.CloseRequested)
        settleBody()

        viewModel.effects.test {
            assertEquals(PostEditorEffect.CloseCommitted, awaitItem())
            // A double back must never yield a second pop (it would remove the screen BELOW).
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** #604 lot 3 — quote-card snapshot, as the topic surface would build it at selection time. */
    private fun card(numreponse: Int, author: String = "auteur$numreponse"): QuoteSelection =
        QuoteSelection(
            locator = QuoteLocator(page = 3, numreponse = numreponse, ref = 1),
            author = author,
            excerpt = "extrait $numreponse",
        )

    private fun longQuoteBody(): String = (1..120).joinToString(separator = " ") { "mot" }

    private fun authenticatedForm(initialContent: String = ""): ReplyForm = ReplyForm(
        hashCheck = "FAKE_HASH",
        sujet = "Fake Topic",
        hiddenFields = mapOf("cat" to "23", "subcat" to "550", "post" to "35395", "page" to "20"),
        isAnonymous = false,
        initialContent = initialContent,
    )

    private fun anonymousForm(): ReplyForm = ReplyForm(
        hashCheck = "FAKE_HASH",
        sujet = "Fake Topic",
        hiddenFields = mapOf("cat" to "23", "subcat" to "550", "post" to "35395", "page" to "20"),
        isAnonymous = true,
    )

    private class FakePreviewParser : BbcodePreviewParser {
        var callCount: Int = 0
            private set

        override fun parsePreview(bbcode: String): PostContent {
            callCount += 1
            return contentFor(bbcode)
        }

        fun contentFor(bbcode: String): PostContent = PostContent(
            blocks = listOf(PostBlock.Paragraph(listOf(PostInline.Text(bbcode)))),
        )
    }

    private class FakeReplyRepository : ReplyRepository {
        var formResult: Result<ReplyForm> = Result.success(
            ReplyForm(
                hashCheck = "FAKE_HASH",
                sujet = "Fake Topic",
                hiddenFields = mapOf("cat" to "23", "subcat" to "550", "post" to "35395", "page" to "20"),
                isAnonymous = false,
            ),
        )
        var submitResult: ReplySubmitResult? = null
        var submitException: Throwable? = null
        var submitGate: CompletableDeferred<Unit>? = null
        var submitScenario: EditorSubmissionScenario? = null
        var formGate: CompletableDeferred<Unit>? = null
        var formException: Throwable? = null

        var formFetches: Int = 0
            private set
        var submitCalls: Int = 0
            private set
        var lastFetchedContext: ReplyContext? = null
            private set
        var lastSubmittedContext: ReplyContext? = null
            private set
        var lastSubmittedBbcode: String? = null
            private set
        var lastSubmittedForm: ReplyForm? = null
            private set

        // #291 — per-numrep responses for the multi-quote pipeline; falls back to [formResult]
        // when the quoted numreponse has no dedicated entry (single-quote / plain-reply tests).
        var formResultsByNumrep: Map<Int, Result<ReplyForm>> = emptyMap()
        val fetchedContexts: MutableList<ReplyContext> = mutableListOf()

        override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm {
            formFetches += 1
            lastFetchedContext = context
            fetchedContexts += context
            formGate?.await()
            formException?.let { throw it }
            val dedicated = context.quotedNumreponse?.let(formResultsByNumrep::get)
            return (dedicated ?: formResult).getOrThrow()
        }

        var lastSubmittedOptions: fr.forumhfr.redface2.core.model.write.ReplyFormOptions? = null
            private set

        override suspend fun submitReply(
            context: ReplyContext,
            form: ReplyForm,
            bbcodeContent: String,
            options: fr.forumhfr.redface2.core.model.write.ReplyFormOptions,
        ): ReplySubmitResult {
            submitScenario?.prepare()
            submitCalls += 1
            lastSubmittedContext = context
            lastSubmittedBbcode = bbcodeContent
            lastSubmittedForm = form
            lastSubmittedOptions = options
            if (submitScenario == null) currentCoroutineContext()[EditorWriteOwner]?.markEmissionPossible()
            submitGate?.let { gate -> submitScenario?.awaitPost(gate) ?: gate.await() }
            submitException?.let { throw it }
            return submitResult ?: error("submitResult not set")
        }
    }

    private class FakeEditPostRepository : fr.forumhfr.redface2.core.domain.write.EditPostRepository {
        var formResult: Result<ReplyForm> = Result.success(
            ReplyForm(
                hashCheck = "FAKE_EDIT_HASH",
                sujet = "Fake Topic",
                hiddenFields = mapOf(
                    "cat" to "23",
                    "subcat" to "550",
                    "post" to "35395",
                    "page" to "20",
                    "numreponse" to "100",
                ),
                isAnonymous = false,
                initialContent = "existing post body",
            ),
        )
        var submitResult: ReplySubmitResult? = null
        var submitException: Throwable? = null
        var submitGate: CompletableDeferred<Unit>? = null
        var submitScenario: EditorSubmissionScenario? = null
        var formException: Throwable? = null
        var formGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        var formFetches: Int = 0
            private set
        var submitCalls: Int = 0
            private set
        var lastFetchedContext: fr.forumhfr.redface2.core.model.write.EditPostContext? = null
            private set
        var lastSubmittedContext: fr.forumhfr.redface2.core.model.write.EditPostContext? = null
            private set
        var lastSubmittedBbcode: String? = null
            private set
        var lastSubmittedOptions: fr.forumhfr.redface2.core.model.write.ReplyFormOptions? = null
            private set

        override suspend fun fetchEditPostForm(
            context: fr.forumhfr.redface2.core.model.write.EditPostContext,
        ): ReplyForm {
            formFetches += 1
            lastFetchedContext = context
            formGate?.await()
            formException?.let { throw it }
            return formResult.getOrThrow()
        }

        override suspend fun submitEditPost(
            context: fr.forumhfr.redface2.core.model.write.EditPostContext,
            form: ReplyForm,
            bbcodeContent: String,
            options: fr.forumhfr.redface2.core.model.write.ReplyFormOptions,
        ): ReplySubmitResult {
            submitScenario?.prepare()
            submitCalls += 1
            lastSubmittedContext = context
            lastSubmittedBbcode = bbcodeContent
            lastSubmittedOptions = options
            if (submitScenario == null) currentCoroutineContext()[EditorWriteOwner]?.markEmissionPossible()
            submitGate?.let { gate -> submitScenario?.awaitPost(gate) ?: gate.await() }
            submitException?.let { throw it }
            return submitResult ?: error("submitResult not set")
        }
    }

    /**
     * Phase 2F-B (#11) — fake smiley repository. Holds a single deferred result so tests can
     * drive the loading / success / error transitions on the wiki search lifecycle without
     * touching the network. By default the search hangs forever ; tests that need a result
     * call `completeNext(...)` or `failNext(...)`.
     */
    private class FakeSmileyRepository :
        fr.forumhfr.redface2.core.domain.smiley.SmileyRepository {
        override fun cachedWikiKeywords(token: String): SmileyWikiKeywordsResult.Loaded? = null

        override suspend fun getWikiKeywords(token: String) = SmileyWikiKeywordsResult.Failed

        private var pending: kotlinx.coroutines.CompletableDeferred<List<EditorSmiley>>? = null
        var lastUserId: Int? = null
            private set
        var lastQuery: String? = null
            private set
        var callCount: Int = 0
            private set
        var cancellationCount: Int = 0
            private set

        override suspend fun searchWiki(userId: Int, query: String): List<EditorSmiley> {
            callCount += 1
            lastUserId = userId
            lastQuery = query
            val deferred = kotlinx.coroutines.CompletableDeferred<List<EditorSmiley>>()
            pending = deferred
            return try {
                deferred.await()
            } catch (error: CancellationException) {
                cancellationCount += 1
                throw error
            }
        }

        fun completeNext(items: List<EditorSmiley>) {
            requireNotNull(pending) { "no pending searchWiki to complete" }.complete(items)
            pending = null
        }

        fun failNext(error: Throwable) {
            requireNotNull(pending) { "no pending searchWiki to fail" }.completeExceptionally(error)
            pending = null
        }
    }

    /**
     * #312 — fake preferences repository. Only `observeConfirmBeforePosting` matters to the
     * editor; every other member is stubbed at its default (same shape as the
     * `FakeUserPreferencesRepository` in `TopicViewModelTest`).
     */
    private class FakeUserPreferencesRepository(
        confirmBeforePosting: Boolean = false,
        // Default FULL so the existing single-image insert tests keep asserting `[img]url[/img]`;
        // production defaults to REDUCED (cf. DataStoreUserPreferencesRepository), exercised by the
        // dedicated mode tests below.
        editorImageInsert: EditorImageInsert = EditorImageInsert.FULL,
        // #805 — false mirrors the production default (inline BBCode); the card tests opt in.
        quoteCardsEnabled: Boolean = false,
    ) : UserPreferencesRepository {
        override fun observeEditorPreviewPositions(): Flow<Map<EditorPreviewKey, Float>> =
            MutableStateFlow(emptyMap())

        override suspend fun setEditorPreviewPosition(key: EditorPreviewKey, sourceDp: Float) = Unit

        private val confirmBeforePosting = MutableStateFlow(confirmBeforePosting)
        val quoteCardsEnabled = MutableStateFlow(quoteCardsEnabled)

        override fun observeProxyConfig(): Flow<ProxyConfig> = MutableStateFlow(ProxyConfig())
        override suspend fun saveProxyConfig(config: ProxyConfig) = Unit
        override fun readProxyConfigForNetworkBootstrap(): ProxyConfig = ProxyConfig()
        override fun observeIgnoreTopicCache(): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setIgnoreTopicCache(enabled: Boolean) = Unit
        override fun observeFlagsGroupByCategory(): Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setFlagsGroupByCategory(enabled: Boolean) = Unit
        override fun observeFlagsHideReadCategories(): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setFlagsHideReadCategories(enabled: Boolean) = Unit
        override fun observeFlagsPerTabOverride(): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setFlagsPerTabOverride(enabled: Boolean) = Unit
        override fun observeFlagsViewSettings(type: FlagType): Flow<FlagsViewSettings> =
            MutableStateFlow(FlagsViewSettings())
        override suspend fun setFlagsGroupByCategoryForType(type: FlagType, enabled: Boolean) = Unit
        override suspend fun setFlagsHideReadCategoriesForType(type: FlagType, enabled: Boolean) = Unit
        override suspend fun setFlagsUnreadOnlyForType(type: FlagType, enabled: Boolean) = Unit
        override suspend fun setFlagsMarkerStyle(style: MarkerStyle) = Unit
        override suspend fun setFlagsSingleLineTitle(enabled: Boolean) = Unit
        override suspend fun setFlagsCategoryBandStyle(style: CategoryBandStyle) = Unit
        override suspend fun setFlagsMarkerBorder(enabled: Boolean) = Unit
        override suspend fun setFlagsShowLoadingBar(enabled: Boolean) = Unit
        override fun observeAvatarAppearance(): Flow<AvatarAppearance> = MutableStateFlow(AvatarAppearance())
        override suspend fun setAvatarBorder(enabled: Boolean) = Unit
        override suspend fun setFlagsPlusLusIndicatorStyle(style: PlusLusIndicatorStyle) = Unit
        override suspend fun setFlagsGlyphStyle(style: FlagGlyphStyle) = Unit
        override fun observeThemeMode(): Flow<ThemeMode> = MutableStateFlow(ThemeMode.SYSTEM)
        override suspend fun setThemeMode(mode: ThemeMode) = Unit

        override fun observeThemeColorPreferences(): Flow<ThemeColorPreferences> =
            MutableStateFlow(ThemeColorPreferences())

        override suspend fun setThemeColorPreferences(preferences: ThemeColorPreferences) = Unit
        override fun observeTopicTopBarAutoHide(): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setTopicTopBarAutoHide(enabled: Boolean) = Unit
        override fun observeConfirmBeforePosting(): Flow<Boolean> = confirmBeforePosting
        override suspend fun setConfirmBeforePosting(enabled: Boolean) {
            confirmBeforePosting.value = enabled
        }
        override fun observeQuoteCardsEnabled(): Flow<Boolean> = quoteCardsEnabled
        override suspend fun setQuoteCardsEnabled(enabled: Boolean) {
            quoteCardsEnabled.value = enabled
        }

        // #806 — the writing-surface preset routes taps in :feature:topic, not here; default stub.
        override fun observeWritingSurfacePreset(): Flow<WritingSurfacePreset> =
            MutableStateFlow(WritingSurfacePreset.FULL_EDITOR)

        override suspend fun setWritingSurfacePreset(preset: WritingSurfacePreset) = Unit

        // #1128 — keep interface fakes aligned with the shared image-selector preference.
        private val imagePickerMode = MutableStateFlow(ImagePickerMode.DEFAULT)

        override fun observeImagePickerMode(): Flow<ImagePickerMode> = imagePickerMode

        override suspend fun setImagePickerMode(mode: ImagePickerMode) {
            imagePickerMode.value = mode
        }

        override fun observeShowDtSection(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setShowDtSection(enabled: Boolean) = Unit

        override fun observeSyncPrivateMessagesWriteEnabled(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setSyncPrivateMessagesWriteEnabled(enabled: Boolean) = Unit

        override fun observeFlagsAutoRefresh(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setFlagsAutoRefresh(enabled: Boolean) = Unit

        override fun observeTopicPageFabs(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setTopicPageFabs(enabled: Boolean) = Unit

        override fun observeMpUnreadBadge(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setMpUnreadBadge(enabled: Boolean) = Unit

        override fun observeTopicPollsExpanded(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setTopicPollsExpanded(enabled: Boolean) = Unit

        override fun observeTopicUnansweredPollsExpanded(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setTopicUnansweredPollsExpanded(enabled: Boolean) = Unit

        override fun observeTopicSignatures(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setTopicSignatures(enabled: Boolean) = Unit

        override fun observeFoldLongQuotes(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setFoldLongQuotes(enabled: Boolean) = Unit

        override fun observeTopicFullWidthPosts(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setTopicFullWidthPosts(enabled: Boolean) = Unit

        override fun observeTopicEgoQuoteEnabled(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setTopicEgoQuoteEnabled(enabled: Boolean) = Unit

        override fun observeTopicEgoPostEnabled(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setTopicEgoPostEnabled(enabled: Boolean) = Unit

        override fun observeShowScrollbar(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setShowScrollbar(enabled: Boolean) = Unit

        override fun observeNavBarLabels(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setNavBarLabels(enabled: Boolean) = Unit

        override fun observeFunnyEmptyState(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setFunnyEmptyState(enabled: Boolean) = Unit

        override fun observeStartScreen(): Flow<StartScreenPreference> =
            MutableStateFlow(StartScreenPreference())

        override suspend fun setStartScreen(preference: StartScreenPreference) = Unit

        // #459 — upload provider / imgur Client-ID are irrelevant to this ViewModel; default stubs.
        override fun observeUploadProvider(): Flow<UploadProviderId> =
            MutableStateFlow(UploadProviderId.DIBERIE)

        override suspend fun setUploadProvider(provider: UploadProviderId) = Unit

        override fun observeImgurClientId(): Flow<String> = MutableStateFlow("")

        override suspend fun setImgurClientId(clientId: String) = Unit

        // #459 PR-images follow-up — editor image insert mode, configurable per test.
        private val editorImageInsert = MutableStateFlow(editorImageInsert)

        override fun observeEditorImageInsert(): Flow<EditorImageInsert> = editorImageInsert

        override suspend fun setEditorImageInsert(mode: EditorImageInsert) {
            editorImageInsert.value = mode
        }

        // #287 — reading display presets are irrelevant to the editor; stubbed at defaults.
        override fun observeDisplayDensity(): Flow<DisplayDensity> = MutableStateFlow(DisplayDensity.COMFORT)

        override suspend fun setDisplayDensity(density: DisplayDensity) = Unit

        override fun observeFontScale(): Flow<FontScalePreference> = MutableStateFlow(FontScalePreference.M)

        override suspend fun setFontScale(scale: FontScalePreference) = Unit

        override fun observeAppLauncherIcon(): Flow<AppLauncherIcon> =
            MutableStateFlow(AppLauncherIcon.CLASSIC)

        override suspend fun setAppLauncherIcon(icon: AppLauncherIcon) = Unit

        // #973 — the block-GIF display profile is irrelevant to the editor; stubbed at the M default.
        override fun observePostImageMaxWidth(): Flow<PostImageMaxWidth> =
            MutableStateFlow(PostImageMaxWidth.DEFAULT)

        override suspend fun setPostImageMaxWidth(width: PostImageMaxWidth) = Unit

        override fun observePostImageCorners(): Flow<PostImageCorners> =
            MutableStateFlow(PostImageCorners.DEFAULT)

        override suspend fun setPostImageCorners(corners: PostImageCorners) = Unit

        // #989 — délimiteur du picker : non exercé ici, présent pour satisfaire l'interface.
        override fun observeSmileyPickerDecoration(): Flow<SmileyPickerDecoration> =
            flowOf(SmileyPickerDecoration.NONE)

        override suspend fun setSmileyPickerDecoration(decoration: SmileyPickerDecoration) = Unit

        override fun observeDebugBoundsOverlay(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setDebugBoundsOverlay(enabled: Boolean) = Unit

        override fun observeHideSystemNavBar(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setHideSystemNavBar(enabled: Boolean) = Unit

        override fun observeImmersiveBackButton(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setImmersiveBackButton(enabled: Boolean) = Unit

        override fun observeImmersiveNavBarReveal(): Flow<ImmersiveNavBarReveal> =
            MutableStateFlow(ImmersiveNavBarReveal.MANUAL)

        override suspend fun setImmersiveNavBarReveal(mode: ImmersiveNavBarReveal) = Unit
        override fun observeAlwaysAskLinkApp(): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setAlwaysAskLinkApp(enabled: Boolean) = Unit

        // #1132 — Forum flag-filter preference is irrelevant to the editor; default ALL stub.
        private val menusCollapsed = MutableStateFlow(false)
        private val stickyCollapsed = MutableStateFlow(false)

        override fun observeForumCategoryMenusCollapsed(): Flow<Boolean> = menusCollapsed
        override suspend fun setForumCategoryMenusCollapsed(collapsed: Boolean) {
            menusCollapsed.value = collapsed
        }

        override fun observeForumCategoryStickyTopicsCollapsed(): Flow<Boolean> = stickyCollapsed
        override suspend fun setForumCategoryStickyTopicsCollapsed(collapsed: Boolean) {
            stickyCollapsed.value = collapsed
        }

        override fun observeForumCategoryFlagFilter(): Flow<CategoryFlagFilter> =
            MutableStateFlow(CategoryFlagFilter.ALL)

        override suspend fun setForumCategoryFlagFilter(filter: CategoryFlagFilter) = Unit
    }

    /**
     * #405 — in-memory fake [EditorDraftStore]. Records the last save / delete per key and serves
     * preloaded drafts for the restore-on-init tests. `updatedAt` is irrelevant to the ViewModel
     * (the store stamps it in production), so it is left at 0.
     */
    private class FakeEditorDraftStore : VersionedEditorDraftStore()

    private fun uploadedImage(imageUrl: String, resizedUrl: String? = null): UploadedImage = UploadedImage(
        provider = UploadProviderId.DIBERIE,
        imageUrl = imageUrl,
        thumbnailUrl = null,
        resizedUrl = resizedUrl,
        deleteHandle = null,
        expiresAt = null,
    )

    /**
     * #459 PR2 — fake [ImageUploadReader]. Returns a canned [ImageUpload] (or throws a preset
     * exception) and records the picked uri so the VM test can assert the read happened with the
     * right argument. Defaults to a 1-byte PNG so a happy-path test that does not set [result]
     * still gets a valid image.
     */
    private class FakeImageUploadReader : ImageUploadReader {
        var result: ImageUpload = ImageUpload(bytes = byteArrayOf(0), mimeType = "image/png", displayName = null)

        /** Set to make [read] throw — exercises the « unreadable picked Uri → Network » mapping. */
        var exception: Throwable? = null
        var lastUri: String? = null
            private set
        var readCalls: Int = 0
            private set

        /** Multi-image upload — every uri passed to [read], in call order, to assert pick order. */
        val readUris: MutableList<String> = mutableListOf()

        override suspend fun read(uri: String): ImageUpload {
            readCalls += 1
            lastUri = uri
            readUris += uri
            exception?.let { throw it }
            return result
        }
    }

    /**
     * #459 PR2 — fake [UploadRepository]. Only [uploadWithCurrentProvider] matters to the editor ;
     * the history / delete members are stubbed. [uploadGate] lets a test hold the upload pending to
     * observe the intermediate `isUploading = true`.
     */
    private class FakeUploadRepository : UploadRepository {
        var uploadResult: UploadedImage = UploadedImage(
            provider = UploadProviderId.DIBERIE,
            imageUrl = "https://rehost.diberie.com/Picture/Get/f/1",
            thumbnailUrl = null,
            resizedUrl = null,
            deleteHandle = null,
            expiresAt = null,
        )
        var uploadException: Throwable? = null
        var uploadGate: CompletableDeferred<Unit>? = null

        /**
         * Multi-image upload — when set, [uploadGate] is awaited ONLY on this 1-based call index
         * (e.g. 2 holds the 2nd upload so a test can observe the « 1/2 » progress after the 1st).
         * Null = the gate is awaited on every call (single-image gate tests).
         */
        var gateOnCall: Int? = null

        /**
         * Multi-image upload — per-call outcomes, consumed in order (call N → `uploadResults[N-1]`).
         * When set it takes precedence over [uploadResult] / [uploadException], so a test can make
         * the 2nd of three uploads fail and assert the batch stops there.
         */
        var uploadResults: List<Result<UploadedImage>>? = null
        var uploadCalls: Int = 0
            private set
        var lastUserId: String? = null
            private set

        override suspend fun uploadWithCurrentProvider(image: ImageUpload, userId: String): UploadedImage {
            uploadCalls += 1
            lastUserId = userId
            if (gateOnCall == null || gateOnCall == uploadCalls) uploadGate?.await()
            uploadResults?.let { return it[uploadCalls - 1].getOrThrow() }
            uploadException?.let { throw it }
            return uploadResult
        }

        override fun observeUploads(userId: String): Flow<List<UploadedImageRecord>> =
            MutableStateFlow(emptyList())

        override suspend fun delete(record: UploadedImageRecord, userId: String): Boolean = false
    }

    /**
     * #459 PR2 — fake [AuthRepository]. Emits a fixed [AuthState] so the VM resolves (or does not
     * resolve) an upload `userId`. Login / logout are no-ops — the editor only observes.
     */
    private class FakeAuthRepository(
        private val authState: AuthState = AuthState.Authenticated("alice"),
    ) : AuthRepository {
        override fun observeAuthState(): Flow<AuthState> = MutableStateFlow(authState)
        override suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated> =
            Result.success(AuthState.Authenticated(pseudo))
        override suspend fun logout() = Unit
    }

    private companion object {
        const val SAMPLE_CAT = 23
        const val SAMPLE_TOPIC_ID = 35_395
        const val SAMPLE_PAGE = 20
        const val SAMPLE_SUBCAT = 550
        const val SAMPLE_EDITED_NUMREPONSE = 100
    }
}
