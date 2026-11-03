package fr.forumhfr.redface2.feature.messages

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
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionAdapter
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionScenario
import fr.forumhfr.redface2.core.domain.editor.SubmissionBodyMutation
import fr.forumhfr.redface2.core.domain.editor.SubmissionErrorProbe
import fr.forumhfr.redface2.core.domain.editor.SubmissionProbe
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerContract
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.PrivateMessageQuote
import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import fr.forumhfr.redface2.core.ui.editor.UploadError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateMessageReplyViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private var submissionClock = DiagnosticsClock { mainDispatcher.scheduler.currentTime * 1_000_000L }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val request = PrivateMessageReplyRequest(threadId = 3195237, page = 1)
    private val quote = PrivateMessageQuote(numreponse = 1_980_000_004, ref = 4)
    private val quoteRequest = PrivateMessageReplyRequest(
        threadId = 3_000_001,
        page = 1,
        quote = quote,
    )

    private val previewParser = BbcodePreviewParser { PostContent(blocks = emptyList()) }

    /**
     * #312 — preferences mock. `observeConfirmBeforePosting` is the only member the reply
     * ViewModel consumes; a hot [MutableStateFlow] mirrors the DataStore observe shape.
     */
    private fun smileyRepository(): SmileyRepository = mockk(relaxed = true)

    /**
     * #405 — in-memory fake [EditorDraftStore]. Records save / delete and serves preloaded drafts
     * for the restore-on-init test. A fresh instance per test (held on the class) keeps assertions
     * isolated ; the existing tests ignore it (no draft preloaded → restore is a no-op).
     */
    private var draftStore = VersionedEditorDraftStore()
    private val favoriteRepository = FakeHfrFavoriteSmileyRepository()
    private val localCollections = FakeSmileyLocalCollections()

    private fun userPreferences(confirmBeforePosting: Boolean = false): UserPreferencesRepository =
        mockk {
            every { observeConfirmBeforePosting() } returns MutableStateFlow(confirmBeforePosting)
            // #459 — the composer now mirrors the image-insert preference on init.
            every { observeEditorImageInsert() } returns MutableStateFlow(EditorImageInsert.REDUCED)
            every { observeImagePickerMode() } returns MutableStateFlow(ImagePickerMode.DEFAULT)
        }

    private fun form(
        hashCheck: String = "abc",
        hiddenFields: Map<String, String> = mapOf(
            "cat" to "prive",
            "post" to "3195237",
            "numrep" to "1980677227",
            "signature" to "1",
        ),
        isAnonymous: Boolean = false,
        initialContent: String = "",
        options: ReplyFormOptions = ReplyFormOptions(),
    ): ReplyForm = ReplyForm(
        hashCheck = hashCheck,
        sujet = "Sujet prive de test",
        hiddenFields = hiddenFields,
        isAnonymous = isAnonymous,
        initialContent = initialContent,
        options = options,
    )

    /** #606 — owner DT/MultiMP form: HFR serves the `newdest` CSV (members minus the owner). */
    private fun ownerForm(
        newdest: String = "alice, bob, Bébé Yoda, stitch+, Administration",
    ): ReplyForm = form(
        hiddenFields = mapOf(
            "cat" to "prive",
            "post" to "4242424",
            "numrep" to "1990000111",
            "owntopic" to "1",
            "signature" to "1",
            "newdest" to newdest,
        ),
    )

    @Test
    fun `picked images upload and insert one img per success (#459)`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), uploads, reader, DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        advanceUntilIdle()

        viewModel.onImagesPicked(listOf("content://pick/1", "content://pick/2"))
        advanceUntilIdle()

        assertEquals(listOf("content://pick/1", "content://pick/2"), reader.readUris)
        assertEquals(2, uploads.uploadCalls)
        assertEquals(2, Regex("\\[img]").findAll(viewModel.state.value.draft.text).count())
        assertFalse(viewModel.state.value.isUploading)
    }

    @Test
    fun `empty picker result shows a banner without starting an upload`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val diagnostics = DiagnosticsLog()
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), uploads, reader, diagnostics, submissionClock, smileyRepository(),
            favoriteRepository, localCollections,
        )
        advanceUntilIdle()
        viewModel.onContentChanged(
            TextFieldValue(text = "draft", selection = TextRange(1, 4)),
        )
        advanceUntilIdle()
        val stateBefore = viewModel.state.value

        viewModel.effects.test {
            viewModel.onImagePickerEvent(
                ImagePickerEvent.Result(
                    contract = ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS,
                    uris = emptyList(),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                stateBefore.copy(uploadError = UploadError.NoImageReceived),
                viewModel.state.value,
            )
            assertFalse(viewModel.state.value.isUploading)
            assertTrue(reader.readUris.isEmpty())
            assertEquals(0, uploads.uploadCalls)
            assertEquals(
                listOf(
                    "result contract=OpenMultipleDocuments count=0 sources=[]",
                    "onImagesPicked count=0",
                ),
                diagnostics.entries.value.filter { it.tag == "ImagePicker" }.map { it.message },
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `picker result logs a safe source then triggers the existing upload`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val diagnostics = DiagnosticsLog()
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), uploads, reader, diagnostics, submissionClock, smileyRepository(),
            favoriteRepository, localCollections,
        )
        advanceUntilIdle()
        val uri = "content://media/picker/private/photo-alice.jpg"

        viewModel.onImagePickerEvent(
            ImagePickerEvent.Result(
                contract = ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
                uris = listOf(uri),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(uri), reader.readUris)
        assertEquals(1, uploads.uploadCalls)
        assertNull(viewModel.state.value.uploadError)
        assertFalse(viewModel.state.value.isUploading)
        assertEquals(
            listOf(
                "result contract=PickMultipleVisualMedia count=1 sources=[content://media]",
                "onImagesPicked count=1",
            ),
            diagnostics.entries.value.filter { it.tag == "ImagePicker" }.map { it.message },
        )
        assertFalse(diagnostics.entries.value.joinToString { it.message }.contains("photo-alice"))
    }

    @Test
    fun `loads the form on init and hydrates signature from the hidden field`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        val state = viewModel.state.value
        assertFalse(state.isLoadingForm)
        assertTrue(state.formAvailable)
        assertFalse(state.formError)
        // The quick-reply form carries signature as a hidden `=1`, so the toggle defaults on.
        assertTrue("signature default should be hydrated from the hidden field", state.signatureEnabled)
    }

    @Test
    fun `quote form hydrates verbatim and quote-only submit preserves options and newdest`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val prefill = "[quotemsg=1980000004,4,990001]Citation serveur[/quotemsg]\n"
        val quoteForm = form(
            hiddenFields = mapOf(
                "cat" to "prive",
                "post" to "3000001",
                "numrep" to "1980000004",
                "numreponse" to "",
                "newdest" to "alice, bob",
            ),
            initialContent = prefill,
            options = ReplyFormOptions(signatureEnabled = true),
        )
        coEvery { repository.fetchReplyForm(any(), any()) } returns quoteForm
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            quoteRequest, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        val state = viewModel.state.value
        assertEquals("the server prefill must stay byte-for-byte in the editor", prefill, state.draft.text)
        assertTrue("a quote with no added body is submit-ready", state.canSubmit)
        assertTrue("checkbox defaults still hydrate on a quote", state.signatureEnabled)
        assertEquals(listOf("alice", "bob"), state.recipients)

        viewModel.onSubmit()

        coVerify {
            repository.submitReply(
                context = match { it.quote == quote },
                form = quoteForm,
                bbcodeContent = prefill,
                options = ReplyFormOptions(signatureEnabled = true),
                // No member edit: the repository forwards the original `newdest` verbatim.
                recipientsOverride = null,
            )
        }
    }

    @Test
    fun `multi quote hydrates every server prefill and submits with the first locator`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val firstPrefill = "[quotemsg=303,2,1]Trois[/quotemsg]\n"
        val secondPrefill = "[quotemsg=101,9,1]Un[/quotemsg]\n"
        val multiQuoteRequest = PrivateMessageReplyRequest(
            threadId = 3_000_001,
            page = 6,
            initialQuotes = listOf(
                QuoteSelection(QuoteLocator(page = 8, numreponse = 303, ref = 2), "Carol", "Trois"),
                QuoteSelection(QuoteLocator(page = 3, numreponse = 101, ref = 9), "Alice", "Un"),
            ),
        )
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers {
            val context = firstArg<PrivateMessageReplyContext>()
            val quote = requireNotNull(context.quote)
            form(
                hashCheck = "hash-${quote.numreponse}",
                hiddenFields = mapOf("numrep" to quote.numreponse.toString()),
                initialContent = if (quote.numreponse == 303) firstPrefill else secondPrefill,
            )
        }
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            multiQuoteRequest, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        val expected = firstPrefill + "\n" + secondPrefill
        assertEquals(expected, viewModel.state.value.draft.text)
        viewModel.onSubmit()

        coVerify {
            repository.submitReply(
                context = match { submitContext ->
                    submitContext.page == 8 &&
                        submitContext.quote == PrivateMessageQuote(numreponse = 303, ref = 2)
                },
                form = match { submittedForm -> submittedForm.hashCheck == "hash-303" },
                bbcodeContent = expected,
                options = any(),
                recipientsOverride = null,
            )
        }
    }

    @Test
    fun `late quote form prefixes the prefill once without clobbering typed text`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val formGate = CompletableDeferred<ReplyForm>()
        val prefill = "[quotemsg=1980000004,4,990001]Citation serveur[/quotemsg]\n"
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { formGate.await() }
        val viewModel = PrivateMessageReplyViewModel(
            quoteRequest, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        viewModel.onContentChanged(TextFieldValue("Texte saisi pendant le GET"))
        formGate.complete(
            form(
                hiddenFields = mapOf("numrep" to "1980000004", "numreponse" to ""),
                initialContent = prefill,
            ),
        )
        advanceUntilIdle()

        val expected = prefill + "\nTexte saisi pendant le GET"
        assertEquals(expected, viewModel.state.value.draft.text)

        // A retry/re-subscription to the same server form refreshes the token only; it cannot append
        // a second quote block because draftHydratedFromForm is already true.
        viewModel.retryFormLoad()
        advanceUntilIdle()
        assertEquals(expected, viewModel.state.value.draft.text)
        assertEquals(1, Regex("\\[quotemsg=").findAll(viewModel.state.value.draft.text).count())
    }

    @Test
    fun `stored draft is offered alongside an untouched quote prefill`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val prefill = "[quotemsg=1980000004,4,990001]Citation serveur[/quotemsg]\n"
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = prefill)
        draftStore.preload(
            EditorDraftKey.mpReply(quoteRequest.threadId),
            EditorDraftStore.Draft(body = "Ancien brouillon", isPrivate = true),
        )

        val viewModel = PrivateMessageReplyViewModel(
            quoteRequest, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        advanceUntilIdle()

        assertEquals(prefill, viewModel.state.value.draft.text)
        assertEquals("Ancien brouillon", viewModel.state.value.restorableDraft)
    }

    @Test
    fun `invalid hash refetch stays on the quote context and never duplicates its prefill`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val prefill = "[quotemsg=1980000004,4,990001]Citation serveur[/quotemsg]\n"
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = prefill)
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)
        val viewModel = PrivateMessageReplyViewModel(
            quoteRequest, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        viewModel.onSubmit()
        runCurrent() // Wait for the form refresh after the certain failure.

        assertEquals(prefill, viewModel.state.value.draft.text)
        coVerify(exactly = 2) {
            repository.fetchReplyForm(
                match { it.quote == quote },
                allowEmbeddedFallback = true,
            )
        }
    }

    @Test
    fun `picker invokes the injected HFR add and remove without reloading the form`() = runTest {
        localCollections.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        val wiki = EditorSmiley("[:fictif_geste:2]", "https://example.invalid/geste.gif", EditorSmileySource.WIKI)
        val favorite = wiki.copy(source = EditorSmileySource.HFR_FAVORITE)
        favoriteRepository.addResult = HfrFavoriteSmileyMutationResult.Success(listOf(favorite))
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        testScheduler.advanceUntilIdle()
        viewModel.smileyPicker.open()
        viewModel.smileyPicker.onFavoriteClicked(wiki)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(wiki.token), favoriteRepository.added)
        assertEquals(listOf(favorite), (viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys)

        viewModel.smileyPicker.onFavoriteClicked(wiki)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(wiki.token), favoriteRepository.removed)
        assertEquals(listOf("alice", "alice"), favoriteRepository.owners)
        assertTrue((viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys.isEmpty())
        coVerify(exactly = 1) { repository.fetchReplyForm(any(), any()) }
    }

    @Test
    fun `late reply form cannot authorize favorites after an account switch`() = runTest {
        localCollections.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        val favorite = EditorSmiley("[:alice]", "https://example.invalid/a.gif", EditorSmileySource.HFR_FAVORITE)
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isLoadingForm)
        localCollections.snapshot.value = SmileyLocalCollectionSnapshot("bob")
        advanceUntilIdle()
        gate.complete(form().copy(favoriteSmileys = listOf(favorite)))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoadingForm)
        viewModel.smileyPicker.open()
        val open = viewModel.smileyPicker.state.value as SmileyPickerState.Open
        assertTrue(open.favoriteSmileys.isEmpty())
        assertNull(open.onFavoriteChange)
        viewModel.smileyPicker.onFavoriteClicked(favorite)
        advanceUntilIdle()
        assertTrue(favoriteRepository.owners.isEmpty())
    }

    @Test
    fun `reply favorites insert the perso token and follow a reloaded form while open`() = runTest {
        val favorite = EditorSmiley(
            "[:fictif espace:3]", "https://example.invalid/fictif.gif", EditorSmileySource.HFR_FAVORITE,
        )
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form().copy(favoriteSmileys = listOf(favorite))
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.smileyPicker.open()
        val open = viewModel.smileyPicker.state.value as SmileyPickerState.Open
        assertEquals(listOf(favorite), open.favoriteSmileys)
        viewModel.onContentChanged(TextFieldValue("hello", TextRange(5)))
        viewModel.onSmileySelected(open.favoriteSmileys.single().token)
        assertEquals("hello [:fictif espace:3] ", viewModel.state.value.draft.text)
        assertEquals(SmileyPickerState.Hidden, viewModel.smileyPicker.state.value)

        viewModel.smileyPicker.open()
        val replacement = favorite.copy(token = "[:fictif_b]", imageUrl = "https://example.invalid/fictif_b.gif")
        coEvery { repository.fetchReplyForm(any(), any()) } returns form().copy(favoriteSmileys = listOf(replacement))
        viewModel.retryFormLoad()
        advanceUntilIdle()
        assertEquals(
            listOf(replacement),
            (viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys,
        )

        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        viewModel.retryFormLoad()
        advanceUntilIdle()
        assertTrue((viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys.isEmpty())
    }

    @Test
    fun `the wiki search carries the loaded form's userId (#440)`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form().copy(userId = 54596)
        val smileys = smileyRepository()

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileys, favoriteRepository, localCollections,
        )

        viewModel.smileyPicker.open()
        viewModel.smileyPicker.onQueryChanged("jap")
        advanceUntilIdle()

        coVerify(exactly = 1) { smileys.searchWiki(54596, "jap") }
    }

    @Test
    fun `submit success raises SubmitSucceeded for the conversation`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))

        viewModel.effects.test {
            viewModel.onSubmit()
            assertEquals(
                PrivateMessageReplyEffect.SubmitSucceeded(threadId = 3195237, page = 1),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(viewModel.state.value.isSubmitting)
        assertNull(viewModel.state.value.submitError)
    }

    @Test
    fun `empty-message failure shows the banner and keeps the draft`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("non-blank"))
        viewModel.onSubmit()

        val state = viewModel.state.value
        assertEquals(PrivateMessageReplyError.Empty, state.submitError)
        assertEquals("non-blank", state.draft.text)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `unknown recipient in a reply is a generic certain refusal without a recipient field instruction`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.UnknownRecipient)
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("Private reply"))
        viewModel.effects.test {
            viewModel.onSubmit()
            runCurrent()
            expectNoEvents()
        }

        val state = viewModel.state.value
        assertEquals(PrivateMessageReplyError.Refused, state.submitError)
        assertEquals(R.string.messages_reply_error_refused, state.submitError?.bannerResId)
        assertEquals("Private reply", state.draft.text)
        assertNull(draftStore.entries.values.single().attempt)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `invalid hash check refetches the form silently`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("hello"))
        viewModel.onSubmit()
        runCurrent() // Wait for the form refresh after the certain failure.

        assertEquals(PrivateMessageReplyError.InvalidHashCheck, viewModel.state.value.submitError)
        // init fetch + the silent refetch after the expired hash_check.
        coVerify(exactly = 2) { repository.fetchReplyForm(any(), any()) }
    }

    @Test
    fun `invalid hash check refetch preserves the user option toggles`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        // First load hydrates signature ON (hidden `signature=1`); the user turns it OFF.
        viewModel.onToggleSignature(false)
        viewModel.onContentChanged(TextFieldValue("hello"))
        viewModel.onSubmit()
        runCurrent() // Wait for the form refresh after the certain failure.

        // The silent refetch after the expired hash_check must NOT re-hydrate the toggle back to
        // HFR's default — the user's choice survives into the second submit.
        assertFalse(
            "InvalidHashCheck refetch must preserve the user's signature toggle",
            viewModel.state.value.signatureEnabled,
        )
        coVerify(exactly = 2) { repository.fetchReplyForm(any(), any()) }
    }

    @Test
    fun `unknown response maps to uncertainty with conversation verification`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("hello"))
        viewModel.onSubmit()

        val state = viewModel.state.value
        assertEquals(PrivateMessageReplyError.Uncertain, state.submitError)
        // Non-destructive: the draft survives so the user can verify and retry.
        assertEquals("hello", state.draft.text)
    }

    @Test
    fun `form fetch failure shows the retry state`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } throws IOException("network down")

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        val state = viewModel.state.value
        assertTrue(state.formError)
        assertFalse(state.formAvailable)
        assertFalse(state.isLoadingForm)
    }

    @Test
    fun `anonymous form is treated as a form error`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(isAnonymous = true)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        assertTrue(viewModel.state.value.formError)
        assertFalse(viewModel.state.value.formAvailable)
    }

    @Test
    fun `context carries the request thread and page`() {
        // Guards the route → context mapping the repository relies on.
        val context = PrivateMessageReplyContext(threadId = request.threadId, page = request.page)
        assertEquals(3195237, context.threadId)
        assertEquals(1, context.page)
    }

    // ----- #312 : confirmation avant publication ------------------------------

    @Test
    fun `confirm-before-posting OFF keeps the one-tap submit unchanged`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()

        coVerify(exactly = 1) { repository.submitReply(any(), any(), any(), any(), any()) }
        assertFalse(viewModel.state.value.showSubmitConfirmation)
    }

    @Test
    fun `confirm-before-posting ON parks the submit behind the confirmation dialog`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()

        assertTrue("the confirmation dialog must be armed", viewModel.state.value.showSubmitConfirmation)
        assertFalse("nothing is in flight while the dialog is up", viewModel.state.value.isSubmitting)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `confirm-before-posting ON onSubmitConfirmed executes the real submission without re-confirming`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }

        viewModel.effects.test {
            val armToken = viewModel.state.value.armToken
            advanceTimeBy(400) // ED-166: a deliberate confirmation.
            viewModel.onSubmitConfirmed(armToken)
            assertEquals(
                PrivateMessageReplyEffect.SubmitSucceeded(threadId = 3195237, page = 1),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
        // Confirm bypasses the preference re-check : exactly one POST, no dialog re-arm.
        coVerify(exactly = 1) { repository.submitReply(any(), any(), any(), any(), any()) }
        assertFalse(viewModel.state.value.showSubmitConfirmation)
    }

    @Test
    fun `confirm-before-posting ON dismissing the dialog sends nothing and keeps the draft`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()

        viewModel.onSubmitConfirmationDismissed()

        assertFalse(viewModel.state.value.showSubmitConfirmation)
        assertEquals("the draft survives the dismissal", "Coucou en privé.", viewModel.state.value.draft.text)
        assertNull(viewModel.state.value.submitError)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `confirm-before-posting ON never confirms an empty draft`() = runTest {
        // The confirmation slots in AFTER validation : a blank draft fails `canSubmit`, so no
        // dialog may appear (confirming an unsendable form would be a lie).
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onSubmit()

        assertFalse("invalid form must not raise the dialog", viewModel.state.value.showSubmitConfirmation)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `confirm-before-posting ON re-submitting while the dialog is up keeps it armed without posting`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()
        assertTrue("the confirmation dialog must be armed", viewModel.state.value.showSubmitConfirmation)

        // Second tap while the dialog is up (double-tap race) : idempotent — re-raising
        // `showSubmitConfirmation = true` is a no-op, and no POST may slip through.
        viewModel.onSubmit()

        assertTrue("the dialog must stay armed", viewModel.state.value.showSubmitConfirmation)
        assertFalse("nothing is in flight while the dialog is up", viewModel.state.value.isSubmitting)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `confirm-before-posting ON rapid double onSubmitConfirmed posts exactly once`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        // Hold the first confirmed submit in flight : with UnconfinedTestDispatcher a
        // non-suspending mock would complete synchronously and the second confirm would
        // legitimately re-fire. The gate keeps the same submission permit active across both confirms.
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        }

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()
        assertTrue("the confirmation dialog must be armed", viewModel.state.value.showSubmitConfirmation)

        val armToken = viewModel.state.value.armToken
        advanceTimeBy(400) // ED-166: a deliberate confirmation.

        viewModel.onSubmitConfirmed(armToken) // launches the POST ; suspends on gate
        viewModel.onSubmitConfirmed(armToken) // must be a no-op (same permit / terminal guards)

        gate.complete(Unit)

        coVerify(exactly = 1) { repository.submitReply(any(), any(), any(), any(), any()) }
        assertFalse(viewModel.state.value.showSubmitConfirmation)
        assertFalse(viewModel.state.value.isSubmitting)
    }

    // ----- #405 : draft autosave / restore -----------------------------------

    @Test
    fun `autosave persists the private body under the mpReply key after the debounce`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        viewModel.onContentChanged(TextFieldValue("private draft"))
        advanceTimeBy(800L)

        val key = EditorDraftKey.mpReply(request.threadId)
        assertEquals("private draft", draftStore.saved[key]?.body)
        assertTrue("MP drafts must be flagged private for the logout purge", draftStore.saved[key]?.isPrivate == true)
    }

    @Test
    fun `a stored MP draft is surfaced as restorable on init`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "rescued MP", isPrivate = true),
        )
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        assertEquals("rescued MP", viewModel.state.value.restorableDraft)
        assertEquals("draft is not auto-applied", "", viewModel.state.value.draft.text)

        viewModel.onDraftRestoreRequested()
        assertEquals("rescued MP", viewModel.state.value.draft.text)
        assertNull(viewModel.state.value.restorableDraft)
    }

    @Test
    fun `reply recreation reoffers until decision and typing still autosaves`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val key = EditorDraftKey.mpReply(request.threadId)
        val savedStateHandle = SavedStateHandle()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        draftStore.preload(
            key,
            EditorDraftStore.Draft(body = "rescued MP", isPrivate = true),
        )
        val first = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        )
        assertEquals("rescued MP", first.state.value.restorableDraft)
        assertNull(savedStateHandle.get<String>("draft_restore_offer_fingerprint"))

        val recreated = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        )
        assertEquals("rescued MP", recreated.state.value.restorableDraft)

        recreated.onContentChanged(TextFieldValue("new live MP"))
        advanceTimeBy(800L)
        assertEquals("new live MP", draftStore.saved[key]?.body)
        assertEquals("tester", draftStore.lastSavedOwner)

        recreated.onDraftRestoreRequested()
        advanceUntilIdle()
        assertNull(savedStateHandle.get<String>("draft_restore_offer_fingerprint"))
        assertEquals("new live MP\n\nrescued MP", draftStore.saved[key]?.body)

        val afterRestore = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        )
        assertEquals("new live MP\n\nrescued MP", afterRestore.state.value.restorableDraft)
    }

    @Test
    fun `reply process recreation offers a newer autosaved draft after live state was lost`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val savedStateHandle = SavedStateHandle()
        val key = EditorDraftKey.mpReply(request.threadId)
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        draftStore.preload(key, EditorDraftStore.Draft(body = "first MP", isPrivate = true))
        val first = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        )
        assertEquals("first MP", first.state.value.restorableDraft)

        draftStore.preload(key, EditorDraftStore.Draft(body = "new MP", isPrivate = true))
        val recreated = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        )

        assertEquals("new MP", recreated.state.value.restorableDraft)
    }

    @Test
    fun `private reply hydration removes an identical cached draft offer`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val body = "server body"
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = body)
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = body, isPrivate = true),
        )

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        assertEquals(body, viewModel.state.value.draft.text)
        assertNull(viewModel.state.value.restorableDraft)
    }

    @Test
    fun `restoring appends the offered private reply after live text`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "rescued MP", isPrivate = true),
        )
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        viewModel.onContentChanged(TextFieldValue("fresh MP"))
        viewModel.onDraftRestoreRequested()

        assertEquals("fresh MP\n\nrescued MP", viewModel.state.value.draft.text)
        assertNull(viewModel.state.value.restorableDraft)
    }

    @Test
    fun `moving selection in a hydrated private reply keeps the restore offer`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val key = EditorDraftKey.mpReply(request.threadId)
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = "server body")
        draftStore.preload(key, EditorDraftStore.Draft(body = "rescued MP", isPrivate = true))
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        val hydrated = viewModel.state.value.draft

        viewModel.onContentChanged(hydrated.copy(selection = TextRange(3)))
        advanceTimeBy(800L)

        assertEquals("rescued MP", viewModel.state.value.restorableDraft)
        assertEquals("rescued MP", draftStore.saved[key]?.body)
    }

    @Test
    fun `discarding deletes the cached MP draft`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val savedStateHandle = SavedStateHandle()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val key = EditorDraftKey.mpReply(request.threadId)
        draftStore.preload(key, EditorDraftStore.Draft(body = "rescued MP", isPrivate = true))
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        )

        viewModel.onDraftDiscardRequested()
        advanceUntilIdle()

        assertEquals("", draftStore.saved[key]?.body)
        assertNull(viewModel.state.value.restorableDraft)
        assertTrue(savedStateHandle.get<String>("draft_restore_offer_fingerprint") != null)

        val recreated = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        )
        assertNull(recreated.state.value.restorableDraft)
    }

    @Test
    fun `a successful MP reply clears content and retains the published marker`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals("", draftStore.saved[EditorDraftKey.mpReply(request.threadId)]?.body)
        assertEquals(
            EditorDraftStore.AttemptStatus.PUBLISHED,
            draftStore.entries[EditorDraftKey.mpReply(request.threadId)]?.attempt?.status,
        )
    }

    // ----- #803 pattern : dirty close (flush before pop), state-hygiene audit 2026-07-05 -----

    @Test
    fun `onCloseRequested flushes the pending debounce before CloseCommitted`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        // Type, then close IMMEDIATELY — well inside the 750 ms debounce window. The flush must
        // persist the state at close time, not the snapshot the debounce captured at scheduling.
        viewModel.onContentChanged(TextFieldValue("dernier mot"))
        viewModel.onCloseRequested()

        val effect = viewModel.effects.first()
        assertEquals(PrivateMessageReplyEffect.CloseCommitted, effect)
        val saved = draftStore.saved[EditorDraftKey.mpReply(request.threadId)]
        assertEquals("the tail of the draft must reach the row before the pop", "dernier mot", saved?.body)
        assertTrue("MP drafts must stay flagged private", saved?.isPrivate == true)
    }

    @Test
    fun `onCloseRequested with a blank live body preserves a pending restore offer`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val key = EditorDraftKey.mpReply(request.threadId)
        draftStore.preload(key, EditorDraftStore.Draft(body = "stale", isPrivate = true))
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        viewModel.onCloseRequested()

        val effect = viewModel.effects.first()
        assertEquals(PrivateMessageReplyEffect.CloseCommitted, effect)
        assertNull("closing must not rewrite a pending offer", draftStore.lastSavedOwner)
        assertFalse(draftStore.deletedKeys.contains(key))
        assertEquals("stale", draftStore.saved[key]?.body)
        assertEquals("stale", viewModel.state.value.restorableDraft)
    }

    @Test
    fun `onCloseRequested with a blank body and no pending offer deletes the stale row`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        val key = EditorDraftKey.mpReply(request.threadId)

        viewModel.onContentChanged(TextFieldValue("temporary body"))
        advanceTimeBy(800L)
        viewModel.onContentChanged(TextFieldValue(""))
        viewModel.onCloseRequested()

        assertEquals(PrivateMessageReplyEffect.CloseCommitted, viewModel.effects.first())
        assertEquals("", draftStore.saved[key]?.body)
    }

    @Test
    fun `onCloseRequested during an in-flight submit is ignored (gate #803)`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        // Hold the POST in flight so the close lands while isSubmitting is true.
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        }
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("en vol"))

        viewModel.onSubmit()
        // Close requested while the POST is in flight — must be inert (gate #803).
        viewModel.onCloseRequested()
        gate.complete(Unit)
        advanceUntilIdle()

        viewModel.effects.test {
            assertTrue(
                "the submit outcome must be the ONLY effect — no CloseCommitted",
                awaitItem() is PrivateMessageReplyEffect.SubmitSucceeded,
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a second onCloseRequested is a no-op (gate #803)`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        viewModel.onCloseRequested()
        viewModel.onCloseRequested()
        advanceUntilIdle()

        viewModel.effects.test {
            assertEquals(PrivateMessageReplyEffect.CloseCommitted, awaitItem())
            // A double close must never yield a second pop (it would remove the screen BELOW).
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ----- #606 : owner manages DT/MultiMP members --------------------------------

    @Test
    fun `a non-owner form exposes no member editor and never overrides newdest`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form() // no newdest
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        assertFalse(viewModel.state.value.canManageRecipients)
        assertTrue(viewModel.state.value.recipients.isEmpty())

        // Even if add/remove are called on a participant form, they no-op.
        viewModel.onAddRecipient("intruder")
        viewModel.onRemoveRecipient("anyone")
        assertTrue(viewModel.state.value.recipients.isEmpty())

        viewModel.onContentChanged(TextFieldValue("hello"))
        viewModel.onSubmit()
        // A participant never overrides the member list — recipientsOverride stays null.
        coVerify {
            repository.submitReply(
                context = any(),
                form = any(),
                bbcodeContent = any(),
                options = any(),
                recipientsOverride = null,
            )
        }
    }

    @Test
    fun `an owner form hydrates the members CSV preserving order, accents, plus and spaces`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm()

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        assertTrue(viewModel.state.value.canManageRecipients)
        assertEquals(
            listOf("alice", "bob", "Bébé Yoda", "stitch+", "Administration"),
            viewModel.state.value.recipients,
        )
    }

    @Test
    fun `owner submit without edits reposts the member CSV verbatim`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("hello"))

        viewModel.onSubmit()
        // No edit was made, so the VM passes `recipientsOverride = null`: the repository then
        // forwards HFR's original `newdest` hidden field VERBATIM (covered by the repo test), never
        // round-tripping the list through parse → join. This is the loss-less invariant for a normal
        // owner reply — the member list must not change just because the owner replied.
        coVerify {
            repository.submitReply(
                context = any(),
                form = any(),
                bbcodeContent = any(),
                options = any(),
                recipientsOverride = null,
            )
        }
    }

    @Test
    fun `owner adds a member appended at the end`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm(newdest = "alice, bob")

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        viewModel.onAddRecipient("  charlie  ") // trimmed before insertion
        assertEquals(listOf("alice", "bob", "charlie"), viewModel.state.value.recipients)
    }

    @Test
    fun `owner add refuses an exact-trimmed duplicate`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm(newdest = "alice, bob")

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        viewModel.onAddRecipient(" alice ")
        assertEquals("no duplicate appended", listOf("alice", "bob"), viewModel.state.value.recipients)
    }

    @Test
    fun `owner remove is exact-match so bob does not delete bob2`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm(newdest = "alice, bob, bob2")

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        viewModel.onRemoveRecipient("bob")
        assertEquals(listOf("alice", "bob2"), viewModel.state.value.recipients)
    }

    @Test
    fun `owner can remove down to one but never the last member`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm(newdest = "alice, bob")

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )

        viewModel.onRemoveRecipient("alice")
        assertEquals("down to one is allowed", listOf("bob"), viewModel.state.value.recipients)

        viewModel.onRemoveRecipient("bob")
        assertEquals("the last member can't be removed", listOf("bob"), viewModel.state.value.recipients)
    }

    @Test
    fun `owner edits travel to the repository as the recomposed CSV`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm(newdest = "alice, bob")
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onRemoveRecipient("alice")
        viewModel.onAddRecipient("Bébé Yoda")
        viewModel.onContentChanged(TextFieldValue("hello"))

        viewModel.onSubmit()
        coVerify {
            repository.submitReply(
                context = any(),
                form = any(),
                bbcodeContent = any(),
                options = any(),
                recipientsOverride = "bob, Bébé Yoda",
            )
        }
    }

    @Test
    fun `the member list survives a silent hash-check refetch`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm(newdest = "alice, bob")
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onRemoveRecipient("alice") // user edit before the failed submit
        viewModel.onContentChanged(TextFieldValue("hello"))
        viewModel.onSubmit()
        runCurrent() // Wait for the form refresh after the certain failure.

        // The silent refetch after the expired hash_check must NOT re-hydrate the member list back
        // to HFR's prefill — the user's removal survives.
        assertEquals(listOf("bob"), viewModel.state.value.recipients)
        coVerify(exactly = 2) { repository.fetchReplyForm(any(), any()) }
    }

    @Test
    fun `an unedited list re-hydrates from a fresher form on a silent refetch`() = runTest {
        val outcomeGate = CompletableDeferred<Unit>()
        draftStore.finishGate = outcomeGate
        val repository = mockk<PrivateMessageWriteRepository>()
        // 1st load: alice, bob. The silent refetch after InvalidHashCheck returns a fresher form
        // where a member changed elsewhere (charlie added). With no local edit (recipientsDirty
        // false), the VM must pick up the fresher list rather than keep the stale prefill.
        coEvery { repository.fetchReplyForm(any(), any()) } returnsMany listOf(
            ownerForm(newdest = "alice, bob"),
            ownerForm(newdest = "alice, bob, charlie"),
        )
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        )
        viewModel.onContentChanged(TextFieldValue("hello")) // no member edit
        viewModel.onSubmit()
        runCurrent()
        // The GET must not adopt a new form while the durable outcome still holds the body lock.
        coVerify(exactly = 1) { repository.fetchReplyForm(any(), any()) }
        assertEquals(listOf("alice", "bob"), viewModel.state.value.recipients)
        outcomeGate.complete(Unit)
        runCurrent()

        assertEquals(listOf("alice", "bob", "charlie"), viewModel.state.value.recipients)
    }

    // ----- #606 : RecipientCsv codec ----------------------------------------------

    @Test
    fun `RecipientCsv parse trims each element, drops empties and preserves order`() {
        assertEquals(
            listOf("alice", "bob", "Bébé Yoda", "stitch+"),
            RecipientCsv.parse(" alice ,bob,  Bébé Yoda , stitch+ , "),
        )
        assertTrue(RecipientCsv.parse(null).isEmpty())
        assertTrue(RecipientCsv.parse("").isEmpty())
    }

    @Test
    fun `RecipientCsv join uses HFR's comma-space separator`() {
        assertEquals("alice, bob, stitch+", RecipientCsv.join(listOf("alice", "bob", "stitch+")))
    }

    /** #1301-ST — adaptateur utilisant les mêmes mocks de formulaire et fakes de brouillon/upload. */
    internal fun submissionAdapter(
        scope: TestScope,
        preferences: Flow<Boolean>,
        scenario: EditorSubmissionScenario,
    ): EditorSubmissionAdapter {
        draftStore = scenario.store
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("tester"))
        val authRepository = object : AuthRepository by FakeAuthRepository() {
            override fun observeAuthState(): Flow<AuthState> = auth
        }
        submissionClock = DiagnosticsClock { scope.testScheduler.currentTime * 1_000_000L }
        scenario.changeAccount = {
            scenario.store.activeOwner = "other"
            auth.value = AuthState.Authenticated("other")
        }
        val postGate = CompletableDeferred<Unit>()
        val uploadCompletion = CompletableDeferred<Unit>()
        val uploads = FakeUploadRepository().apply { uploadGate = uploadCompletion }
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "stored draft"),
        )
        var formFetches = 0
        var submissionCalls = 0
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers {
            formFetches++
            form()
        }
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } coAnswers {
            submissionCalls += 1
            scenario.awaitPost(postGate)
            scenario.postFailure?.let { throw it }
            scenario.postRejection?.let { ReplySubmitResult.Failure(it) }
                ?: ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        }
        val userPreferences = object : UserPreferencesRepository by userPreferences() {
            override fun observeConfirmBeforePosting(): Flow<Boolean> = preferences
        }
        fun createViewModel() = PrivateMessageReplyViewModel(
            request = request,
            repository = repository,
            previewParser = previewParser,
            userPreferencesRepository = userPreferences,
            draftStore = draftStore,
            authRepository = authRepository,
            uploadRepository = uploads,
            imageUploadReader = FakeImageUploadReader(),
            diagnostics = DiagnosticsLog(),
            clock = submissionClock,
            smileyRepository = smileyRepository(),
            hfrFavoriteSmileyRepository = favoriteRepository,
            smileyLocalCollections = localCollections,
        )
        var viewModel = createViewModel()
        val effects = mutableListOf<PrivateMessageReplyEffect>()
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
            override val unsupportedMutations = mapOf(
                SubmissionBodyMutation.IMAGE_URL to "aucune entrée URL d'image dans cet éditeur MP",
                SubmissionBodyMutation.SHARED_RESTORE to "aucune entrée resumeSharedDraft pour les MP",
            )
            override val uploadUnsupportedReason: String? = null
            override val postCalls get() = submissionCalls
            override val formFetchCalls get() = formFetches
            override val uploadCalls get() = uploads.uploadCalls
            override val draftSaves get() = draftStore.saveCount
            override val draftWrites get() = draftStore.writes
            override val successEffects get() = effects.count { it is PrivateMessageReplyEffect.SubmitSucceeded }
            override val closeEffects get() = effects.count { it is PrivateMessageReplyEffect.CloseCommitted }

            override fun editBody(body: String) {
                viewModel.onContentChanged(TextFieldValue(body, TextRange(body.length)))
            }

            override fun clickSubmit() = viewModel.onSubmit()
            override fun confirm() = viewModel.onSubmitConfirmed(viewModel.state.value.armToken)
            override fun confirmIdentity(id: Long?) = viewModel.onSubmitConfirmed(arms[id])
            override fun dismissConfirmation() = viewModel.onSubmitConfirmationDismissed()
            override fun close() = viewModel.onCloseRequested()
            override fun completePost() { postGate.complete(Unit) }
            override fun completeUpload() { uploadCompletion.complete(Unit) }
            override fun deliverLateUploadCallback() {
                val insert = PrivateMessageReplyViewModel::class.java.getDeclaredMethod(
                    "insertImageBbcodeAtCaret", String::class.java, Boolean::class.javaPrimitiveType,
                )
                insert.isAccessible = true
                insert.invoke(viewModel, "[img]https://example.test/late.png[/img]", false)
            }
            override fun dispose() = viewModel.viewModelScope.cancel()
            override fun startUpload() = viewModel.onImagesPicked(listOf("content://pick/1"))

            override fun snapshot(): SubmissionProbe = viewModel.state.value.let {
                it.armToken?.let { token -> arms[token.id] = token }
                SubmissionProbe(
                    it.draft.text, it.canSubmit, it.isSubmitting, it.isUploading, it.showSubmitConfirmation,
                    armId = it.armToken?.id,
                    error = when (it.submitError) {
                        PrivateMessageReplyError.Uncertain -> SubmissionErrorProbe.UNCERTAIN
                        PrivateMessageReplyError.DraftWriteFailed -> SubmissionErrorProbe.DRAFT_WRITE_FAILED
                        else -> null
                    },
                    restoreOffered = it.restorableDraft != null,
                    blocksClose = it.submission.blocksBodyMutation,
                )
            }

            override fun mutateBody(mutation: SubmissionBodyMutation) {
                when (mutation) {
                    SubmissionBodyMutation.TYPING -> editBody("changed during POST")
                    SubmissionBodyMutation.SMILEY -> viewModel.onSmileySelected(":jap:")
                    SubmissionBodyMutation.FORMAT -> viewModel.onToolbarAction(BbcodeAction.Bold)
                    SubmissionBodyMutation.RESTORE -> {
                        checkNotNull(viewModel.state.value.restorableDraft) { "l'offre #1415 doit être présente" }
                        viewModel.onDraftRestoreRequested()
                    }
                    SubmissionBodyMutation.IMAGE_URL, SubmissionBodyMutation.SHARED_RESTORE ->
                        error("cas explicitement désactivé")
                }
            }
        }
    }

}
