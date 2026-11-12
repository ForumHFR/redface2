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
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionAdapter
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionScenario
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionBodyMutation
import fr.forumhfr.redface2.core.domain.editor.SubmissionErrorProbe
import fr.forumhfr.redface2.core.domain.editor.SubmissionProbe
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
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.upload.UploadedImage
import fr.forumhfr.redface2.core.domain.upload.UploadedImageRecord
import fr.forumhfr.redface2.core.domain.write.TopicFormRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyWikiKeywordsResult
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.EditorPreviewKey
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.ImagePickerContract
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.editor.WritingSurfacePreset
import fr.forumhfr.redface2.core.model.write.EditFirstPostContext
import fr.forumhfr.redface2.core.model.write.MissingFirstPostSubcategoryException
import fr.forumhfr.redface2.core.model.write.NewTopicContext
import fr.forumhfr.redface2.core.model.write.NewTopicSubmitResult
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.model.write.TopicForm
import fr.forumhfr.redface2.core.model.write.TopicFormSubcategoryChoice
import fr.forumhfr.redface2.core.model.write.TopicPollForm
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.EditorErrorAction
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EventKey
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerOpenIntent
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import fr.forumhfr.redface2.core.ui.editor.UploadProgress
import fr.forumhfr.redface2.core.ui.editor.toEditorError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // One class per ViewModel keeps every code path co-located with the
// shared `Fake*Repository` test doubles — splitting per phase (#148 edit FP / #149 create /
// #213 cat-0-subcat / #11 smiley) would shred the fakes into duplicated copies. Mirrors the
// same suppression already carried by `PostEditorViewModelTest`.
class TopicFormViewModelTest {

    private val previewParser = FakePreviewParser()
    private val topicFormRepository = FakeTopicFormRepository()
    private val smileyRepository = FakeSmileyRepository()
    private var draftStore: VersionedEditorDraftStore = FakeEditorDraftStore()
    private val dispatcher = UnconfinedTestDispatcher()
    private var submissionClock = DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L }
    private val favoriteRepository = FakeHfrFavoriteSmileyRepository()
    private val localCollections = FakeSmileyLocalCollections()

    private val viewModels = mutableListOf<TopicFormViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `editor shortcut follows persisted changes including NONE without changing the draft`() = runTest {
        val preferences = FakeUserPreferencesRepository()
        preferences.setEditorShortcut(EditorShortcut.RED)
        val vm = newTopicViewModel(entrySubcat = null, userPreferencesRepository = preferences)
        syncBody()
        assertEquals(EditorShortcut.RED, vm.state.value.editorShortcut)
        val body = vm.bodyFieldState.text.toString()
        preferences.setEditorShortcut(EditorShortcut.IMAGE_URL)
        syncBody()
        assertEquals(EditorShortcut.IMAGE_URL, vm.state.value.editorShortcut)
        preferences.setEditorShortcut(EditorShortcut.NONE)
        syncBody()
        assertEquals(EditorShortcut.NONE, vm.state.value.editorShortcut)
        assertEquals(body, vm.bodyFieldState.text.toString())
    }

    @Test
    fun `ED35 incomplete title and subcategory refuse twice without arm or POST in both modes`() = runTest {
        for (creating in listOf(false, true)) {
            val preferences = FakeUserPreferencesRepository(confirmBeforePosting = true)
            val vm = if (creating) newTopicViewModel(null, userPreferencesRepository = preferences)
                else newViewModel(userPreferencesRepository = preferences)
            syncBody()
            vm.editBody(TextFieldValue("Body"))
            vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue()))
            repeat(2) {
                vm.submit(TopicFormIntent.SubmitClicked())
                assertEquals(EditorErrors.missingTitle, vm.state.value.submitError)
                assertFalse(vm.state.value.showSubmitConfirmation)
                assertNull(vm.state.value.armToken)
                val event = vm.editorEvents.visibleEvent!!
                vm.editorEvents.dispatch(EditorEventInput.Activate(event.key))
                assertNull(vm.editorEvents.visibleEvent)
            }
            vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Titre")))
            // A loaded form may still carry the placeholder; exercise the live VM guard, not its projection.
            vm.submit(TopicFormIntent.SubcatSelected(0))
            repeat(2) {
                vm.submit(TopicFormIntent.SubmitClicked())
                assertEquals(EditorErrors.missingSubcategory, vm.state.value.submitError)
                assertNull(vm.state.value.armToken)
                val event = vm.editorEvents.visibleEvent!!
                vm.editorEvents.dispatch(EditorEventInput.Activate(event.key))
            }
            assertEquals(0, topicFormRepository.submitCalls + topicFormRepository.newTopicSubmitCalls)
            assertEquals(0, draftStore.beginCalls)
            vm.submit(TopicFormIntent.SubmitConfirmed(null))
            assertEquals(0, topicFormRepository.submitCalls + topicFormRepository.newTopicSubmitCalls)
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `ED100 parser refusal reaches first post chrome and a supported reload clears it`() = runTest {
        // Same typed failure as the real no-select response in TopicFormParserTest and repository test.
        topicFormRepository.formFailure = MissingFirstPostSubcategoryException()
        val vm = newViewModel()
        val refused = vm.state.value
        assertFalse(refused.hasLoadedForm)
        assertTrue(refused.firstPostSubcategoryRefused)
        assertTrue(refused.hasSubcategorySelect)
        assertTrue(TopicFormChromeState(refused).unsupportedFirstPost)
        assertFalse(TopicFormChromeState(refused).showSubcategory)
        assertFalse(refused.sendActivatable)
        assertEquals(EditorErrors.invalidForm, refused.submitError)
        vm.submit(TopicFormIntent.SubmitClicked())
        vm.submit(TopicFormIntent.SubmitConfirmed(null))
        assertNull(vm.state.value.armToken)
        assertEquals(0, draftStore.beginCalls)
        assertEquals(0, topicFormRepository.submitCalls)

        topicFormRepository.formFailure = java.io.IOException("offline")
        vm.submit(TopicFormIntent.ReloadRequested)
        assertTrue(vm.state.value.firstPostSubcategoryRefused)
        assertTrue(vm.state.value.hasSubcategorySelect)
        assertTrue(TopicFormChromeState(vm.state.value).unsupportedFirstPost)
        topicFormRepository.formFailure = null
        vm.submit(TopicFormIntent.ReloadRequested)
        syncBody()
        assertTrue(vm.state.value.hasSubcategorySelect)
        assertFalse(vm.state.value.firstPostSubcategoryRefused)
        assertFalse(TopicFormChromeState(vm.state.value).unsupportedFirstPost)
        assertTrue(TopicFormChromeState(vm.state.value).showSubcategory)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun `anonymous complete forms deliver two send refusals without arming or durable attempt`() = runTest {
        for (creating in listOf(false, true)) {
            topicFormRepository.formResult = topicFormRepository.formResult.copy(isAnonymous = true)
            topicFormRepository.newTopicFormResult = topicFormRepository.newTopicFormResult.copy(isAnonymous = true)
            val preferences = FakeUserPreferencesRepository(confirmBeforePosting = true)
            val vm = if (creating) newTopicViewModel(SAMPLE_SUBCAT, userPreferencesRepository = preferences)
                else newViewModel(userPreferencesRepository = preferences)
            vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Titre")))
            vm.editBody(TextFieldValue("Body"))
            vm.editorEvents.dispatch(EditorEventInput.Acknowledge(vm.editorEvents.visibleEvent!!.key))
            val keys = mutableListOf<EventKey>()
            repeat(2) {
                assertTrue(vm.state.value.sendActivatable)
                vm.submit(TopicFormIntent.SubmitClicked())
                assertEquals(EditorErrors.loginRequired, vm.state.value.submitError)
                val event = vm.editorEvents.visibleEvent!!
                assertEquals(EditorErrors.loginRequired, (event.content as EditorEventContent.Problem).error)
                keys += event.key
                assertNull(vm.state.value.armToken)
                assertFalse(vm.state.value.showSubmitConfirmation)
                vm.editorEvents.dispatch(EditorEventInput.Acknowledge(keys.last()))
                assertNull(vm.editorEvents.visibleEvent)
            }
            assertNotEquals(keys.first(), keys.last())
            vm.submit(TopicFormIntent.SubmitConfirmed(null))
            assertEquals(0, draftStore.beginCalls)
            assertEquals(0, topicFormRepository.submitCalls + topicFormRepository.newTopicSubmitCalls)
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `unchanged anonymous reload retains its refusal without redelivery even after acknowledgement`() = runTest {
        for (creating in listOf(false, true)) {
            topicFormRepository.formResult = topicFormRepository.formResult.copy(isAnonymous = true)
            topicFormRepository.newTopicFormResult = topicFormRepository.newTopicFormResult.copy(isAnonymous = true)
            val vm = if (creating) newTopicViewModel(SAMPLE_SUBCAT) else newViewModel()
            val original = vm.editorEvents.visibleEvent!!
            vm.submit(TopicFormIntent.ReloadRequested)
            assertEquals(original, vm.editorEvents.visibleEvent)
            vm.editorEvents.dispatch(EditorEventInput.Acknowledge(original.key))
            vm.submit(TopicFormIntent.ReloadRequested)
            assertNull(vm.editorEvents.visibleEvent)
            assertEquals(EditorErrors.loginRequired, vm.state.value.submitError)
            assertNull(vm.state.value.armToken)
            assertEquals(0, draftStore.beginCalls)
            vm.viewModelScope.cancel()
        }
        assertEquals(3, topicFormRepository.formFetches)
        assertEquals(3, topicFormRepository.newTopicFormFetches)
    }

    @Test
    fun `successful reload resolves form network errors in new topic and first post modes`() = runTest {
        for (creating in listOf(false, true)) {
            topicFormRepository.formFailure = java.io.IOException("offline")
            val vm = if (creating) newTopicViewModel(entrySubcat = SAMPLE_SUBCAT) else newViewModel()
            syncBody()
            testScheduler.advanceUntilIdle()
            assertEquals(EditorErrors.formNetwork, vm.state.value.submitError)
            topicFormRepository.formFailure = null
            vm.submit(TopicFormIntent.ReloadRequested)
            syncBody()
            testScheduler.advanceUntilIdle()
            assertNull(vm.state.value.submitError)
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `cancelled form fetch clears loading without an error in new topic and first post modes`() = runTest {
        for (creating in listOf(false, true)) {
            topicFormRepository.formFailure = kotlinx.coroutines.CancellationException("GET cancelled")
            val vm = if (creating) newTopicViewModel(entrySubcat = SAMPLE_SUBCAT) else newViewModel()
            syncBody()
            testScheduler.advanceUntilIdle()
            assertFalse(vm.state.value.isLoadingForm)
            assertFalse(vm.state.value.hasLoadedForm)
            assertNull(vm.state.value.submitError)
            assertNull(vm.editorEvents.visibleEvent) // A cancellation is not a read failure.
            topicFormRepository.formFailure = null
            vm.submit(TopicFormIntent.ReloadRequested)
            syncBody()
            testScheduler.advanceUntilIdle()
            assertTrue(vm.state.value.hasLoadedForm)
            assertNull(vm.state.value.submitError)
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `successful reload keeps uncertainty in new topic and first post modes`() = runTest {
        topicFormRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        topicFormRepository.newTopicSubmitResult = NewTopicSubmitResult.Failure(ReplyFailureReason.Unknown)
        for (creating in listOf(false, true)) {
            draftStore = FakeEditorDraftStore()
            val vm = if (creating) newTopicViewModel(entrySubcat = SAMPLE_SUBCAT) else newViewModel()
            syncBody()
            testScheduler.advanceUntilIdle()
            vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue("title")))
            vm.editBody(TextFieldValue("body"))
            vm.submit(TopicFormIntent.SubmitClicked())
            syncBody()
            testScheduler.advanceUntilIdle()
            assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
            vm.submit(TopicFormIntent.ReloadRequested)
            syncBody()
            testScheduler.advanceUntilIdle()
            assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `new upload clears the old failure only after passing the upload guard`() = runTest {
        val diagnostics = DiagnosticsLog()
        val uploads = FakeUploadRepository().apply { uploadException = UploadException.TooLarge(1) }
        val vm = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            diagnostics = diagnostics,
            uploadRepository = uploads,
        )
        syncBody()
        testScheduler.advanceUntilIdle()
        vm.submit(TopicFormIntent.ImagesPicked(listOf("content://first")))
        syncBody()
        testScheduler.advanceUntilIdle()
        assertEquals(EditorErrors.tooLarge, vm.state.value.uploadError)
        uploads.uploadException = null
        val gate = CompletableDeferred<Unit>()
        uploads.uploadGate = gate
        vm.submit(TopicFormIntent.ImagesPicked(listOf("content://second")))
        assertNull(vm.state.value.uploadError)
        val pickerEventsBefore = diagnostics.entries.value.count { it.tag == "ImagePicker" }
        vm.submit(TopicFormIntent.ImagePickerEventReceived(
            ImagePickerEvent.Unavailable(ImagePickerMode.PHOTO_PICKER, ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA),
        ))
        assertEquals(pickerEventsBefore + 1, diagnostics.entries.value.count { it.tag == "ImagePicker" })
        assertEquals(EditorErrors.pickerAbsent, vm.state.value.uploadError)
        vm.submit(TopicFormIntent.ImagesPicked(List(11) { "content://ignored/$it" }))
        assertEquals(EditorErrors.pickerAbsent, vm.state.value.uploadError)
        gate.complete(Unit)
        syncBody()
        testScheduler.advanceUntilIdle()
        vm.submit(TopicFormIntent.ImagesPicked(listOf("content://third")))
        syncBody()
        testScheduler.advanceUntilIdle()
        assertNull(vm.state.value.uploadError)
        assertEquals(3, uploads.uploadCalls)
    }

    @Test
    fun `partial upload retains count failure and inserted image after typing`() = runTest {
        val uploads = FakeUploadRepository().apply {
            uploadException = UploadException.TooLarge(1)
            failOnCall = 2
        }
        val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT, uploadRepository = uploads)
        syncBody()
        testScheduler.advanceUntilIdle()
        vm.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1", "content://pick/2", "content://pick/3")))
        syncBody()
        testScheduler.advanceUntilIdle()
        assertEquals(2, uploads.uploadCalls)
        assertEquals(1, Regex("\\[img]").findAll(vm.state.value.bodyText).count())
        val expected = EditorErrors.stopped(EditorErrors.tooLarge, UploadProgress(1, 3))
        assertEquals(expected, vm.state.value.uploadError)
        vm.editBody(TextFieldValue(vm.state.value.bodyText + "typed"))
        assertEquals(expected, vm.state.value.uploadError)
        assertFalse(vm.state.value.isUploading)
    }

    @Test
    fun `ED131 errors survive body selection subject selection subcategory options and insertions`() = runTest {
        topicFormRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
        val vm = newViewModel()
        syncBody()
        testScheduler.advanceUntilIdle()
        vm.submit(TopicFormIntent.SubmitClicked())
        syncBody()
        testScheduler.advanceUntilIdle()
        vm.submit(TopicFormIntent.ImagePickerEventReceived(
            ImagePickerEvent.Unavailable(ImagePickerMode.PHOTO_PICKER, ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA),
        ))
        vm.editBody(TextFieldValue("body"))
        vm.editBody(TextFieldValue("body", TextRange(0, 2)))
        assertEquals(EditorErrors.empty, vm.state.value.submitError)
        assertEquals(EditorErrors.pickerAbsent, vm.state.value.uploadError)
        listOf(
            TopicFormIntent.SubjectChanged(TextFieldValue("title")),
            TopicFormIntent.SubjectChanged(TextFieldValue("title", TextRange(0, 2))),
            TopicFormIntent.SubcatSelected(SAMPLE_SUBCAT),
            TopicFormIntent.ToggleSignature(false),
            TopicFormIntent.ToolbarActionClicked(BbcodeAction.Bold),
        ).forEach { intent ->
            vm.submit(intent)
            assertEquals(intent.toString(), EditorErrors.empty, vm.state.value.submitError)
            assertEquals(intent.toString(), EditorErrors.pickerAbsent, vm.state.value.uploadError)
        }
    }

    @Test
    fun `init hydrates subject draft and per-post options from the parsed form`() = runTest {
        // A non-default tone (6) so the msgIcon assertion cannot pass on the
        // DEFAULT_MSG_ICON fallback. Local to this test: the shared fixture stays at "1".
        topicFormRepository.formResult = topicFormRepository.formResult.copy(msgIcon = "6")
        val viewModel = newViewModel()
        viewModel.state.test {
            val hydrated = awaitHydratedState()
            assertEquals("Sample first post title", hydrated.subject.text)
            assertEquals("Body BBCode goes here.", hydrated.bodyText)
            assertEquals(SAMPLE_SUBCAT, hydrated.selectedSubcat)
            assertEquals(2, hydrated.subcategoryChoices.size)
            assertTrue(hydrated.signatureEnabled)
            assertFalse(hydrated.smileyDisabled)
            assertFalse(hydrated.emailNotificationEnabled)
            assertEquals(6, hydrated.msgIcon)
            assertTrue(hydrated.subjectHydratedFromServer)
            assertTrue(hydrated.draftHydratedFromServer)
            assertTrue(hydrated.optionsHydratedFromForm)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, topicFormRepository.formFetches)
        val context = requireNotNull(topicFormRepository.lastFetchedContext)
        assertEquals(SAMPLE_CAT, context.cat)
        assertEquals(SAMPLE_SUBCAT, context.subcat)
        assertEquals(SAMPLE_TOPIC_ID, context.topicId)
        assertEquals(SAMPLE_NUMREPONSE, context.numreponse)
        assertEquals(1, context.page)
    }

    @Test
    fun `silent refetch after invalid hash check does not clobber user edits`() = runTest {
        topicFormRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)
        val viewModel = newViewModel()
        viewModel.state.test {
            awaitHydratedState()
            // User retypes the subject AND the body in their own words.
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("User-overridden subject")))
            viewModel.editBody(TextFieldValue("user override", TextRange(13)))
            viewModel.submit(TopicFormIntent.MsgIconSelected(6))
            // Submit triggers InvalidHashCheck → silent refetch with the same fake form.
            viewModel.submit(TopicFormIntent.SubmitClicked())
            val finalState = expectMostRecentItem()
            assertEquals("User-overridden subject", finalState.subject.text)
            assertEquals("user override", finalState.bodyText)
            assertEquals(6, finalState.msgIcon)
            // The user-facing banner stays armed on InvalidHashCheck even though
            // the refetch is silent — without this the user has no way to know
            // their first submit was rejected.
            val submitError = finalState.submitError
            assertTrue(
                "expected submitError to be InvalidHashCheck.toEditorError(), was $submitError",
                submitError == EditorErrors.invalidHashCheck,
            )
            cancelAndIgnoreRemainingEvents()
        }
        // 2 fetches : initial + silent refetch after InvalidHashCheck.
        assertEquals(2, topicFormRepository.formFetches)
    }

    @Test
    fun `submit forwards current subject draft subcat and options to the repository`() = runTest {
        topicFormRepository.submitResult = ReplySubmitResult.Success(targetPage = 1, refreshUrl = "/hfr/")
        val viewModel = newViewModel()
        viewModel.state.test {
            awaitHydratedState()
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Edited title")))
            viewModel.editBody(TextFieldValue("Edited body", TextRange(11)))
            viewModel.submit(TopicFormIntent.SubcatSelected(SAMPLE_OTHER_SUBCAT))
            viewModel.submit(TopicFormIntent.ToggleSignature(enabled = false))
            viewModel.submit(TopicFormIntent.ToggleSmileyDisabled(disabled = true))
            viewModel.submit(TopicFormIntent.ToggleEmailNotification(enabled = true))
            viewModel.submit(TopicFormIntent.SubmitClicked())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, topicFormRepository.submitCalls)
        assertEquals("Edited title", topicFormRepository.lastSubmittedSubject)
        assertEquals("Edited body", topicFormRepository.lastSubmittedBbcode)
        assertEquals(SAMPLE_OTHER_SUBCAT, topicFormRepository.lastSubmittedSubcat)
        val options = requireNotNull(topicFormRepository.lastSubmittedOptions)
        assertFalse(options.signatureEnabled)
        assertTrue(options.smileyDisabled)
        assertTrue(options.emailNotificationEnabled)
    }

    @Test
    fun `submit success emits SubmitSucceeded with scrollTo equal to numreponse`() = runTest {
        topicFormRepository.submitResult = ReplySubmitResult.Success(targetPage = 1, refreshUrl = "/hfr/")
        val viewModel = newViewModel()
        viewModel.state.test {
            awaitHydratedState()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.effects.test {
            viewModel.submit(TopicFormIntent.SubmitClicked())
            val effect = awaitItem() as TopicFormEffect.SubmitSucceeded
            assertEquals(1, effect.targetPage)
            assertEquals(SAMPLE_NUMREPONSE, effect.scrollTo)
            assertEquals(PostSubmitOperation.Edit, effect.operation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `slow fetch preserves user-edited subject but still hydrates draft`() = runTest {
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = gate
        val viewModel = newViewModel()
        viewModel.state.test {
            // First emission is the initial blank state (the fetch is gated).
            val blank = awaitItem()
            assertFalse(blank.subjectHydratedFromServer)
            assertFalse(blank.draftHydratedFromServer)
            // User types into the subject while the fetch is still in flight.
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("My pre-fetch subject")))
            skipItems(1)
            // Form arrives.
            gate.complete(Unit)
            syncBody()
            val hydrated = expectMostRecentItem()
            assertEquals("My pre-fetch subject", hydrated.subject.text)
            assertEquals("Body BBCode goes here.", hydrated.bodyText)
            // Subject is not flagged as server-hydrated (user edited it first),
            // but draft is. The two flags are independent now.
            assertFalse(hydrated.subjectHydratedFromServer)
            assertTrue(hydrated.draftHydratedFromServer)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `slow fetch rejects early body input and hydrates both subject and draft`() = runTest {
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = gate
        val viewModel = newViewModel()
        viewModel.state.test {
            val blank = awaitItem()
            assertFalse(blank.subjectHydratedFromServer)
            assertFalse(blank.draftHydratedFromServer)
            // The first post must be known before typing can replace its body.
            viewModel.editBody(TextFieldValue("pre-fetch draft", TextRange(15)))
            assertEquals("", viewModel.bodyFieldState.text.toString())
            gate.complete(Unit)
            syncBody()
            val hydrated = expectMostRecentItem()
            assertEquals("Sample first post title", hydrated.subject.text)
            assertEquals(topicFormRepository.formResult.initialContent, hydrated.bodyText)
            assertTrue(hydrated.subjectHydratedFromServer)
            assertTrue(hydrated.draftHydratedFromServer)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun editFirstPostRejectsInsertionsUntilSeedKnown() = runTest {
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = gate
        val uploads = FakeUploadRepository()
        val vm = newViewModel(uploadRepository = uploads)
        val selection = vm.bodyFieldState.selection
        val intents = listOf(
            TopicFormIntent.ToolbarActionClicked(BbcodeAction.Bold),
            TopicFormIntent.ToolbarActionClicked(BbcodeAction.Quote),
            TopicFormIntent.SmileySelected(":o"),
            TopicFormIntent.ImageUrlInserted("https://example.test/image.png"),
            TopicFormIntent.ImagesPicked(listOf("content://pick/1")),
            TopicFormIntent.ImagePickerEventReceived(
                ImagePickerEvent.Result(ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA, listOf("content://pick/2")),
            ),
        )
        for (intent in intents) {
            vm.submit(intent)
            syncBody()
            assertEquals("Rejected $intent", "", vm.bodyFieldState.text.toString())
            assertEquals(selection, vm.bodyFieldState.selection)
            assertFalse(vm.state.value.isUploading)
        }
        // Exercise the final callback guard even though the upload launch itself was refused.
        val insert = TopicFormViewModel::class.java.getDeclaredMethod(
            "insertImageBbcodeAtCaret", String::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        insert.invoke(vm, "[img]https://example.test/late.png[/img]", false)
        syncBody()
        assertEquals("", vm.bodyFieldState.text.toString())
        assertEquals(selection, vm.bodyFieldState.selection)
        assertEquals(0, uploads.uploadCalls)
        assertEquals(0, draftStore.saveCount)

        gate.complete(Unit)
        syncBody()
        assertEquals(topicFormRepository.formResult.initialContent, vm.bodyFieldState.text.toString())
        assertFalse(vm.state.value.isAwaitingInitialBody)
        vm.submit(TopicFormIntent.SmileySelected(":o"))
        assertTrue(vm.bodyFieldState.text.toString().contains(":o"))
    }

    @Test
    @Suppress("UNCHECKED_CAST") // Inject an inconsistent state to exercise the independent send guard.
    fun editFirstPostCannotSubmitUnhydratedBody() = runTest {
        val vm = newViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        vm.submit(TopicFormIntent.SubmitClicked())
        val token = requireNotNull(vm.state.value.armToken)
        val state = TopicFormViewModel::class.java.getDeclaredField("_state").run {
            isAccessible = true
            get(vm) as MutableStateFlow<TopicFormState>
        }
        assertTrue(state.value.canSubmit)
        state.value = state.value.copy(draftHydratedFromServer = false)
        assertFalse(state.value.canSubmit)
        vm.submit(TopicFormIntent.SubmitClicked())
        vm.submit(TopicFormIntent.SubmitConfirmed(token))
        testScheduler.runCurrent()
        assertEquals(0, topicFormRepository.submitCalls)
        assertEquals(token, vm.state.value.armToken)
    }

    @Test
    fun formFailureKeepsBodyLockedUntilRetryOrExplicitRestore() = runTest {
        topicFormRepository.formFailure = java.io.IOException("offline")
        val uploads = FakeUploadRepository()
        val vm = newViewModel(uploadRepository = uploads)
        assertFalse(vm.state.value.isLoadingForm)
        assertTrue(vm.state.value.isAwaitingInitialBody)
        assertEquals(EditorErrors.formNetwork, vm.state.value.submitError)
        vm.editBody(TextFieldValue("early typing or paste"))
        vm.submit(TopicFormIntent.ToolbarActionClicked(BbcodeAction.Quote))
        vm.submit(TopicFormIntent.SmileySelected(":o"))
        vm.submit(TopicFormIntent.ImageUrlInserted("https://example.test/image.png"))
        vm.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1")))
        vm.submit(
            TopicFormIntent.ImagePickerEventReceived(
                ImagePickerEvent.Result(ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA, listOf("content://pick/2")),
            ),
        )
        syncBody()
        assertEquals("", vm.bodyFieldState.text.toString())
        assertEquals(0, uploads.uploadCalls)
        assertFalse(vm.state.value.canSubmit)

        val gate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = gate
        topicFormRepository.formFailure = null
        vm.submit(TopicFormIntent.ReloadRequested)
        assertTrue(vm.state.value.isLoadingForm)
        assertTrue(vm.state.value.isAwaitingInitialBody)
        gate.complete(Unit)
        syncBody()
        assertFalse(vm.state.value.isAwaitingInitialBody)
        assertNull(vm.state.value.submitError)
        vm.editBody(TextFieldValue("known body edited"))
        assertEquals("known body edited", vm.bodyFieldState.text.toString())
    }

    @Test
    fun parserRejectedFormKeepsBodyLockedAndOffersReload() = runTest {
        // #1512 — what `parseEditFirstPost` raises for a form without its body textarea.
        topicFormRepository.formFailure = IllegalStateException("content_form textarea missing from first-post form")
        val vm = newViewModel()
        assertFalse(vm.state.value.isLoadingForm)
        assertTrue(vm.state.value.isAwaitingInitialBody)
        assertEquals(EditorErrors.invalidForm, vm.state.value.submitError)
        assertEquals(EditorErrorAction.Reload, vm.state.value.submitError?.action)
        vm.editBody(TextFieldValue("early typing"))
        syncBody()
        assertEquals("", vm.bodyFieldState.text.toString())
        assertFalse(vm.state.value.canSubmit)

        topicFormRepository.formFailure = null
        vm.submit(TopicFormIntent.ReloadRequested)
        syncBody()
        assertFalse(vm.state.value.isAwaitingInitialBody)
        assertNull(vm.state.value.submitError)
        vm.editBody(TextFieldValue("known body edited"))
        assertEquals("known body edited", vm.bodyFieldState.text.toString())
        assertEquals(0, topicFormRepository.submitCalls)
    }

    @Test
    fun blankServerBodyIsKnownAndUnlocksEditing() = runTest {
        for (seed in listOf("", " \n")) {
            topicFormRepository.formResult = topicFormRepository.formResult.copy(initialContent = seed)
            val vm = newViewModel()
            assertTrue(vm.state.value.draftHydratedFromServer)
            assertFalse(vm.state.value.isAwaitingInitialBody)
            assertEquals(seed, vm.state.value.draftHydratedContent)
            assertEquals(seed, vm.bodyFieldState.text.toString())
            assertFalse(vm.state.value.canSubmit)
            vm.editBody(TextFieldValue("filled body"))
            assertEquals("filled body", vm.bodyFieldState.text.toString())
            assertTrue(vm.state.value.canSubmit)
        }
    }

    @Test
    fun newRemainsEditableWhileFormLoads() = runTest {
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = gate
        val uploads = FakeUploadRepository()
        val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT, uploadRepository = uploads)
        assertTrue(vm.state.value.isLoadingForm)
        assertFalse(vm.state.value.isAwaitingInitialBody)
        vm.editBody(TextFieldValue("new body", TextRange(8)))
        vm.submit(TopicFormIntent.ToolbarActionClicked(BbcodeAction.Quote))
        vm.submit(TopicFormIntent.SmileySelected(":o"))
        vm.submit(TopicFormIntent.ImageUrlInserted("https://example.test/image.png"))
        vm.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1")))
        syncBody()
        val body = vm.bodyFieldState.text.toString()
        assertTrue(body.startsWith("new body"))
        assertTrue(body.contains("[quote]"))
        assertTrue(body.contains(":o"))
        assertTrue(body.contains("[img]https://example.test/image.png[/img]"))
        assertEquals(1, uploads.uploadCalls)
        gate.complete(Unit)
        syncBody()
        assertEquals(body, vm.bodyFieldState.text.toString())
    }

    @Test
    fun knownBodyRemainsEditableDuringRefetch() = runTest {
        val vm = newViewModel()
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = gate
        vm.submit(TopicFormIntent.ReloadRequested)
        assertTrue(vm.state.value.isLoadingForm)
        assertFalse(vm.state.value.isAwaitingInitialBody)
        vm.editBody(TextFieldValue("edited during refetch", TextRange(21)))
        vm.submit(TopicFormIntent.SmileySelected(":o"))
        val body = vm.bodyFieldState.text.toString()
        assertTrue(body.startsWith("edited during refetch"))
        assertTrue(body.contains(":o"))
        gate.complete(Unit)
        syncBody()
        assertEquals(body, vm.bodyFieldState.text.toString())
    }

    @Test
    fun `refetch after InvalidHashCheck never clobbers either subject or draft`() = runTest {
        topicFormRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)
        val viewModel = newViewModel()
        viewModel.state.test {
            awaitHydratedState()
            // User overwrites both fields after the initial hydration.
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("user subject after hydrate")))
            viewModel.editBody(TextFieldValue("user draft after hydrate", TextRange(24)))
            // Submit → InvalidHashCheck → silent refetch.
            viewModel.submit(TopicFormIntent.SubmitClicked())
            val finalState = expectMostRecentItem()
            assertEquals("user subject after hydrate", finalState.subject.text)
            assertEquals("user draft after hydrate", finalState.bodyText)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, topicFormRepository.formFetches)
    }

    // ---- Phase 2E (#149) — New mode ---------------------------------------

    @Test
    fun `New mode loads the form and hydrates subcategories without touching subject or draft`() = runTest {
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        viewModel.state.test {
            val final = expectMostRecentItem()
            assertEquals(TopicFormMode.New, final.mode)
            // subject / draft stay empty — the create-topic flow has nothing
            // to hydrate from the server.
            assertEquals("", final.subject.text)
            assertEquals("", final.bodyText)
            // Hydration flags are locked to `true` from the start so a silent
            // refetch can't clobber the user's saisie.
            assertTrue(final.subjectHydratedFromServer)
            assertTrue(final.draftHydratedFromServer)
            // Options + choices arrived.
            assertEquals(3, final.subcategoryChoices.size)
            assertFalse(final.isLoadingForm)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, topicFormRepository.newTopicFormFetches)
        val context = requireNotNull(topicFormRepository.lastNewTopicContext)
        assertEquals(SAMPLE_CAT, context.cat)
        assertEquals(SAMPLE_SUBCAT, context.entrySubcat)
    }

    @Test
    fun `New mode honours subcat passed via TopicFormRequest when HFR pre-selects nothing`() = runTest {
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        viewModel.state.test {
            val final = expectMostRecentItem()
            // Even though `form.selectedSubcat == null`, the state should fall
            // back to the entry chip (`request.subcat`) for the dropdown
            // default value. Otherwise the user would have to re-pick the
            // same subcat they just came from.
            assertEquals(SAMPLE_SUBCAT, final.selectedSubcat)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `New mode without selectedSubcat keeps submit disabled`() = runTest {
        val viewModel = newTopicViewModel(entrySubcat = null)
        viewModel.state.test {
            val final = expectMostRecentItem()
            // No entry chip, no HFR pre-selection : `selectedSubcat` stays null.
            assertNull(final.selectedSubcat)
            // Even with non-blank subject / draft, canSubmit must stay false.
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Topic")))
        viewModel.editBody(TextFieldValue("Body", TextRange(4)))
        viewModel.submit(TopicFormIntent.SubmitClicked())
        // No POST happened because canSubmit returned false.
        assertEquals(0, topicFormRepository.newTopicSubmitCalls)
    }

    @Test
    fun `New mode in a cat without sub-category enables submit with subcat 0 and posts it`() = runTest {
        // #213 — cat IA (cat=32) has no sub-category : HFR serves a create form with
        // no `<select name=subcat>`, so the parser returns `hasSubcategorySelect = false`,
        // `selectedSubcat = null` and no choices. The submit must still be allowed
        // (the « cat without sub-category » case posts `subcat=0`) without the user
        // ever picking a sub-category, and the repository must receive `subcat = 0`.
        topicFormRepository.newTopicFormResult = TopicForm(
            hashCheck = "FAKE_HASH",
            subject = "",
            initialContent = "",
            userId = SAMPLE_USER_ID,
            selectedSubcat = null,
            subcategoryChoices = emptyList(),
            hasSubcategorySelect = false,
            hiddenFields = mapOf("cat" to "32"),
            options = ReplyFormOptions(),
            msgIcon = "1",
            poll = TopicPollForm(present = false, fields = emptyMap(), editableInThisVersion = false),
            isAnonymous = false,
        )
        topicFormRepository.newTopicSubmitResult = NewTopicSubmitResult.Success(
            newTopicId = null,
            newNumreponse = null,
            targetCat = IA_CAT,
            targetSubcat = 0,
            refreshUrl = null,
        )
        val viewModel = newTopicViewModel(cat = IA_CAT, entrySubcat = null)
        viewModel.state.test {
            val hydrated = expectMostRecentItem()
            // No select, no entry chip : selectedSubcat stays null but the form
            // signals a sub-category-less cat.
            assertNull(hydrated.selectedSubcat)
            assertFalse(hydrated.hasSubcategorySelect)
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Sujet IA")))
            viewModel.editBody(TextFieldValue("Corps IA", TextRange(8)))
            // canSubmit must be true WITHOUT any SubcatSelected.
            val ready = expectMostRecentItem()
            assertTrue("cat-without-subcat must enable submit with subcat=0", ready.canSubmit)
            viewModel.submit(TopicFormIntent.SubmitClicked())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, topicFormRepository.newTopicSubmitCalls)
        assertEquals(
            "subcat=0 must be posted for a cat without sub-category",
            0,
            topicFormRepository.lastSubmittedSubcat,
        )
    }

    @Test
    fun `New mode in a cat WITH sub-categories keeps submit disabled until a subcat is picked`() = runTest {
        // Symmetric guard for FIX 2 : a cat WITH sub-categories (the default fixture,
        // `hasSubcategorySelect = true`, no pre-selection) must keep submit disabled
        // until the user picks a sub-category. Relaxing the cat-0-subcat case must not
        // leak into the normal flow.
        val viewModel = newTopicViewModel(cat = SAMPLE_CAT, entrySubcat = null)
        viewModel.state.test {
            val hydrated = expectMostRecentItem()
            assertTrue("default fixture exposes a subcat select", hydrated.hasSubcategorySelect)
            assertNull(hydrated.selectedSubcat)
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Topic")))
            viewModel.editBody(TextFieldValue("Body", TextRange(4)))
            val blocked = expectMostRecentItem()
            assertFalse("missing subcat selection must keep submit disabled", blocked.canSubmit)
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.submit(TopicFormIntent.SubmitClicked())
        assertEquals(0, topicFormRepository.newTopicSubmitCalls)
    }

    @Test
    fun `New mode submit forwards subject draft subcat and options to submitNewTopic`() = runTest {
        topicFormRepository.newTopicSubmitResult = NewTopicSubmitResult.Success(
            newTopicId = null,
            newNumreponse = null,
            targetCat = SAMPLE_CAT,
            targetSubcat = SAMPLE_OTHER_SUBCAT,
            refreshUrl = null,
        )
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        viewModel.state.test {
            expectMostRecentItem() // drain hydration
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Topic 2E")))
            viewModel.editBody(TextFieldValue("Body 2E", TextRange(7)))
            // User overrides the entry chip to a different sub-category.
            viewModel.submit(TopicFormIntent.SubcatSelected(SAMPLE_OTHER_SUBCAT))
            viewModel.submit(TopicFormIntent.ToggleSignature(enabled = true))
            viewModel.submit(TopicFormIntent.MsgIconSelected(6))
            viewModel.submit(TopicFormIntent.SubmitClicked())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, topicFormRepository.newTopicSubmitCalls)
        assertEquals("Topic 2E", topicFormRepository.lastSubmittedSubject)
        assertEquals("Body 2E", topicFormRepository.lastSubmittedBbcode)
        assertEquals(SAMPLE_OTHER_SUBCAT, topicFormRepository.lastSubmittedSubcat)
        val options = requireNotNull(topicFormRepository.lastSubmittedOptions)
        assertTrue(options.signatureEnabled)
        assertEquals("6", topicFormRepository.lastSubmittedForm?.msgIcon)
    }

    @Test
    fun `New mode success emits NewTopicCreated with the parsed ids`() = runTest {
        topicFormRepository.newTopicSubmitResult = NewTopicSubmitResult.Success(
            newTopicId = 148_750,
            newNumreponse = 2_523_830,
            targetCat = SAMPLE_CAT,
            targetSubcat = SAMPLE_OTHER_SUBCAT,
            refreshUrl = null,
        )
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        viewModel.state.test {
            expectMostRecentItem()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.effects.test {
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Topic")))
            viewModel.editBody(TextFieldValue("Body", TextRange(4)))
            viewModel.submit(TopicFormIntent.SubcatSelected(SAMPLE_OTHER_SUBCAT))
            viewModel.submit(TopicFormIntent.SubmitClicked())
            val effect = awaitItem() as TopicFormEffect.NewTopicCreated
            assertEquals(SAMPLE_CAT, effect.cat)
            assertEquals(SAMPLE_OTHER_SUBCAT, effect.subcat)
            assertEquals(148_750, effect.newTopicId)
            assertEquals(2_523_830, effect.newNumreponse)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `New mode success carries the posted subject for the listing highlight (#206)`() = runTest {
        // HFR never returns the created topic id (#214), so the only handle the listing has
        // to highlight the fresh row is the exact title the user posted. Assert the effect
        // carries it even on the realistic null-id success path.
        topicFormRepository.newTopicSubmitResult = NewTopicSubmitResult.Success(
            newTopicId = null,
            newNumreponse = null,
            targetCat = SAMPLE_CAT,
            targetSubcat = SAMPLE_OTHER_SUBCAT,
            refreshUrl = null,
        )
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        viewModel.state.test {
            expectMostRecentItem()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.effects.test {
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Mon nouveau sujet")))
            viewModel.editBody(TextFieldValue("Body", TextRange(4)))
            viewModel.submit(TopicFormIntent.SubcatSelected(SAMPLE_OTHER_SUBCAT))
            viewModel.submit(TopicFormIntent.SubmitClicked())
            val effect = awaitItem() as TopicFormEffect.NewTopicCreated
            assertNull(effect.newTopicId)
            assertEquals("Mon nouveau sujet", effect.subject)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `New mode refetch after InvalidHashCheck does not clobber subject or draft`() = runTest {
        topicFormRepository.newTopicSubmitResult =
            NewTopicSubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        viewModel.state.test {
            expectMostRecentItem() // drain initial hydration
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("user subject after hydrate")))
            viewModel.editBody(TextFieldValue("user draft after hydrate", TextRange(24)))
            viewModel.submit(TopicFormIntent.SubmitClicked())
            val finalState = expectMostRecentItem()
            assertEquals("user subject after hydrate", finalState.subject.text)
            assertEquals("user draft after hydrate", finalState.bodyText)
            val submitError = finalState.submitError
            assertTrue(
                "expected submitError to be Hfr(InvalidHashCheck), was $submitError",
                submitError == EditorErrors.invalidHashCheck,
            )
            cancelAndIgnoreRemainingEvents()
        }
        // 2 fetches : initial + silent refetch after InvalidHashCheck.
        assertEquals(2, topicFormRepository.newTopicFormFetches)
    }

    @Test
    fun `New mode rejects an anonymous form with LoginRequired`() = runTest {
        topicFormRepository.newTopicFormResult = topicFormRepository.newTopicFormResult
            .copy(isAnonymous = true)
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        viewModel.state.test {
            val final = expectMostRecentItem()
            assertTrue(final.isAnonymous)
            val error = final.submitError
            assertTrue(
                "expected LoginRequired.toEditorError(), was $error",
                error == EditorErrors.loginRequired,
            )
            cancelAndIgnoreRemainingEvents()
        }
        // User still tries to submit — the VM short-circuits without calling the repo.
        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Topic")))
        viewModel.editBody(TextFieldValue("Body", TextRange(4)))
        viewModel.submit(TopicFormIntent.SubcatSelected(SAMPLE_SUBCAT))
        viewModel.submit(TopicFormIntent.SubmitClicked())
        assertEquals(0, topicFormRepository.newTopicSubmitCalls)
    }

    // ----- Phase 2F-C (#11) / #441 : smiley picker ----------------------------------
    // The picker machinery (open/dismiss, ≤ 2-chars gate, debounce, stale-result guards,
    // #824 restore-on-reopen) lives in the shared SmileyPickerController and is covered by
    // SmileyPickerControllerTest — no duplicate coverage here. These tests only exercise
    // what stays a ViewModel concern : the insertion intent, the userId plumbed from the
    // parsed form, and the diagnostics policy on search failure.

    @Test
    fun `picker invokes the injected HFR add and remove without reloading the form`() = runTest {
        localCollections.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        val wiki = EditorSmiley("[:fictif_geste:2]", "https://example.invalid/geste.gif", EditorSmileySource.WIKI)
        val favorite = wiki.copy(source = EditorSmileySource.HFR_FAVORITE)
        favoriteRepository.addResult = HfrFavoriteSmileyMutationResult.Success(listOf(favorite))
        val viewModel = newViewModel()
        syncBody()
        testScheduler.advanceUntilIdle()
        val fetches = topicFormRepository.formFetches
        viewModel.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
        viewModel.smileyPicker.onFavoriteClicked(wiki)
        syncBody()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(wiki.token), favoriteRepository.added)
        assertEquals(listOf(favorite), (viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys)

        viewModel.smileyPicker.onFavoriteClicked(wiki)
        syncBody()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(wiki.token), favoriteRepository.removed)
        assertEquals(listOf("alice", "alice"), favoriteRepository.owners)
        assertTrue((viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys.isEmpty())
        assertEquals(fetches, topicFormRepository.formFetches)
    }

    @Test
    fun `late new and edited topic forms cannot authorize favorites after an account switch`() = runTest {
        val favorite = EditorSmiley("[:alice]", "https://example.invalid/a.gif", EditorSmileySource.HFR_FAVORITE)
        listOf(false, true).forEach { createTopic ->
            localCollections.snapshot.value = SmileyLocalCollectionSnapshot("alice")
            val gate = CompletableDeferred<Unit>()
            topicFormRepository.formGate = gate
            topicFormRepository.formResult = topicFormRepository.formResult.copy(favoriteSmileys = listOf(favorite))
            topicFormRepository.newTopicFormResult =
                topicFormRepository.newTopicFormResult.copy(favoriteSmileys = listOf(favorite))
            val viewModel = if (createTopic) newTopicViewModel(SAMPLE_SUBCAT) else newViewModel()
            syncBody()
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value.isLoadingForm)
            localCollections.snapshot.value = SmileyLocalCollectionSnapshot("bob")
            syncBody()
            testScheduler.advanceUntilIdle()
            gate.complete(Unit)
            syncBody()
            testScheduler.advanceUntilIdle()

            assertFalse(viewModel.state.value.isLoadingForm)
            viewModel.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
            val open = viewModel.smileyPicker.state.value as SmileyPickerState.Open
            assertTrue(open.favoriteSmileys.isEmpty())
            assertNull(open.onFavoriteChange)
            viewModel.smileyPicker.onFavoriteClicked(favorite)
            syncBody()
            testScheduler.advanceUntilIdle()
            assertTrue(favoriteRepository.owners.isEmpty())
        }
    }

    @Test
    fun `topic form favorites reach the picker and insert the exact perso token`() = runTest {
        val favorite = EditorSmiley(
            "[:fictif espace:3]", "https://example.invalid/fictif.gif", EditorSmileySource.HFR_FAVORITE,
        )
        topicFormRepository.formResult = topicFormRepository.formResult.copy(favoriteSmileys = listOf(favorite))
        val viewModel = newViewModel()
        syncBody()
        testScheduler.advanceUntilIdle()

        viewModel.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
        val open = viewModel.smileyPicker.state.value as SmileyPickerState.Open
        assertEquals(listOf(favorite), open.favoriteSmileys)
        viewModel.editBody(TextFieldValue("hello", TextRange(5)))
        viewModel.submit(TopicFormIntent.SmileySelected(open.favoriteSmileys.single().token))

        syncBody()
        assertEquals("hello [:fictif espace:3] ", viewModel.state.value.bodyText)
        assertEquals(SmileyPickerState.Hidden, viewModel.smileyPicker.state.value)
        assertEquals(0, smileyRepository.callCount)
    }

    @Test
    fun `reloaded topic forms replace favorites then remove the section while the picker stays open`() = runTest {
        val favoriteA = EditorSmiley(
            "[:fictif_a:3]", "https://example.invalid/fictif_a.gif", EditorSmileySource.HFR_FAVORITE,
        )
        val favoriteB = favoriteA.copy(token = "[:fictif_b]", imageUrl = "https://example.invalid/fictif_b.gif")
        topicFormRepository.formResult = topicFormRepository.formResult.copy(favoriteSmileys = listOf(favoriteA))
        topicFormRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)
        val viewModel = newViewModel()
        syncBody()
        testScheduler.advanceUntilIdle()

        viewModel.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
        assertEquals(listOf(favoriteA), (viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys)

        topicFormRepository.formResult = topicFormRepository.formResult.copy(favoriteSmileys = listOf(favoriteB))
        viewModel.submit(TopicFormIntent.SubmitClicked())
        syncBody()
        testScheduler.advanceUntilIdle()

        assertEquals(2, topicFormRepository.formFetches)
        assertEquals(listOf(favoriteB), (viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys)

        topicFormRepository.formResult = topicFormRepository.formResult.copy(favoriteSmileys = emptyList())
        viewModel.submit(TopicFormIntent.SubmitClicked())
        syncBody()
        testScheduler.advanceUntilIdle()

        assertEquals(3, topicFormRepository.formFetches)
        assertTrue((viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys.isEmpty())
    }

    @Test
    fun `the wiki search carries the hydrated userId (#441)`() = runTest {
        val viewModel = newViewModel()
        // Wait for the form to be hydrated so userId lands in state.
        viewModel.state.test {
            awaitHydratedState()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
        viewModel.smileyPicker.onQueryChanged("jap")
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        assertEquals(1, smileyRepository.callCount)
        assertEquals("jap", smileyRepository.lastQuery)
        // The form's parsed userId is plumbed through the controller's userId lambda —
        // the controller falls back to 0 only when `state.userId` is `null`.
        assertEquals(SAMPLE_USER_ID, smileyRepository.lastUserId)
    }

    @Test
    fun `wiki search falls back to user id 0 when the form did not expose a userId`() = runTest {
        topicFormRepository.formResult = topicFormRepository.formResult.copy(userId = null)
        val viewModel = newViewModel()
        viewModel.state.test {
            awaitHydratedState()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
        viewModel.smileyPicker.onQueryChanged("jap")
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        assertEquals(0, smileyRepository.lastUserId)
    }

    @Test
    fun `failed wiki search does not leak user id or query through diagnostics`() = runTest {
        val diagnostics = DiagnosticsLog()
        val viewModel = newViewModel(diagnostics = diagnostics)
        viewModel.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
        viewModel.smileyPicker.onQueryChanged("secret")
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()

        smileyRepository.failNext(
            java.io.IOException("https://forum.hardware.fr/message-smi-mp-aj.php?user_id=12345&findsmilies=secret"),
        )
        testScheduler.runCurrent()

        val messages = diagnostics.entries.value.joinToString(separator = "\n") { it.message }
        assertTrue(messages.contains("wiki smiley search failed: IOException"))
        assertFalse(messages.contains("12345"))
        assertFalse(messages.contains("secret"))
        assertFalse(messages.contains("findsmilies"))
    }

    @Test
    fun `SmileySelected inserts the token at the caret closes the picker and refreshes the preview`() = runTest {
        val viewModel = newViewModel()
        viewModel.state.test {
            awaitHydratedState()
            cancelAndIgnoreRemainingEvents()
        }
        // Move caret to end of draft, open preview, then pick a smiley.
        viewModel.editBody(TextFieldValue("hello", TextRange(5)))
        viewModel.submit(TopicFormIntent.TogglePreview)
        viewModel.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
        viewModel.submit(TopicFormIntent.SmileySelected(":jap:"))
        syncBody()
        viewModel.state.test {
            val state = expectMostRecentItem()
            // Surrounding-spaces convention from `insertBbcodeToken` is honoured.
            assertEquals("hello :jap: ", state.bodyText)
            assertEquals(12, viewModel.bodyFieldState.selection.start)
            // Preview was visible : the new draft text must be re-parsed.
            val firstBlock = state.preview.blocks.first() as PostBlock.Paragraph
            val firstInline = firstBlock.inlines.first() as PostInline.Text
            assertEquals("hello :jap: ", firstInline.value)
            cancelAndIgnoreRemainingEvents()
        }
        // Picker auto-closes (through the controller) ; #824 restores the search on reopen.
        assertEquals(SmileyPickerState.Hidden, viewModel.smileyPicker.state.value)
    }

    @Test
    fun `New mode also searches the wiki with the hydrated userId`() = runTest {
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        viewModel.state.test {
            // Wait for the form fetch to land.
            val hydrated = expectMostRecentItem()
            assertEquals(SAMPLE_USER_ID, hydrated.userId)
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
        viewModel.smileyPicker.onQueryChanged("jap")
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        assertEquals(SAMPLE_USER_ID, smileyRepository.lastUserId)
    }

    @Test
    fun `ImageUrlInserted works in topic-level forms and refreshes visible preview`() = runTest {
        val viewModel = newViewModel()
        viewModel.state.test {
            awaitHydratedState()
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.editBody(TextFieldValue("image: ", TextRange(7)))
        viewModel.submit(TopicFormIntent.TogglePreview)
        viewModel.submit(TopicFormIntent.ImageUrlInserted("https://example.com/pic.gif"))
        syncBody()

        viewModel.state.test {
            val state = expectMostRecentItem()
            val expected = "image: [img]https://example.com/pic.gif[/img]"
            assertEquals(expected, state.bodyText)
            assertEquals(expected.length, viewModel.bodyFieldState.selection.start)
            val block = state.preview.blocks.single() as PostBlock.Paragraph
            val inline = block.inlines.single() as PostInline.Text
            assertEquals(expected, inline.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ImageUrlInserted ignores invalid URLs in topic-level forms`() = runTest {
        val viewModel = newViewModel()
        viewModel.state.test {
            awaitHydratedState()
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.editBody(TextFieldValue("image: ", TextRange(7)))
        viewModel.submit(TopicFormIntent.ImageUrlInserted("javascript:alert(1)"))

        assertEquals("image: ", viewModel.state.value.bodyText)
    }

    @Test
    fun `ImageUrlInserted honours the EditorImageInsert preference in topic-level forms`() = runTest {
        // #459 PR2 (Codex P2#1) — the topic composer must respect the same preference as the post
        // editor. A pasted URL has no reduced variant, so REDUCED wraps the full URL in [url].
        val viewModel = newViewModel(
            userPreferencesRepository =
                FakeUserPreferencesRepository(editorImageInsert = EditorImageInsert.REDUCED),
        )
        viewModel.state.test {
            awaitHydratedState()
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.editBody(TextFieldValue("image: ", TextRange(7)))
        viewModel.submit(TopicFormIntent.ImageUrlInserted("https://example.com/pic.gif"))

        val expected =
            "image: [url=https://example.com/pic.gif][img]https://example.com/pic.gif[/img][/url]"
        syncBody()
        assertEquals(expected, viewModel.state.value.bodyText)
    }

    // ----- #312 : confirmation avant publication ------------------------------

    @Test
    fun `confirm-before-posting OFF keeps the one-tap EditFirstPost submit unchanged`() = runTest {
        topicFormRepository.submitResult = ReplySubmitResult.Success(targetPage = 1, refreshUrl = "/hfr/")
        val viewModel = newViewModel() // default fake → preference OFF
        viewModel.state.test {
            awaitHydratedState()
            viewModel.submit(TopicFormIntent.SubmitClicked())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("OFF must POST directly, no dialog detour", 1, topicFormRepository.submitCalls)
        assertFalse(viewModel.state.value.showSubmitConfirmation)
    }

    @Test
    fun `confirm-before-posting ON parks the EditFirstPost submit behind the confirmation dialog`() = runTest {
        topicFormRepository.submitResult = ReplySubmitResult.Success(targetPage = 1, refreshUrl = "/hfr/")
        val viewModel = newViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        viewModel.state.test {
            awaitHydratedState()
            viewModel.submit(TopicFormIntent.SubmitClicked())
            val parked = expectMostRecentItem()
            assertTrue("the confirmation dialog must be armed", parked.showSubmitConfirmation)
            assertFalse("nothing is in flight while the dialog is up", parked.isSubmitting)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("no POST before the user confirms", 0, topicFormRepository.submitCalls)
    }

    @Test
    fun `confirm-before-posting ON EditFirstPost confirm executes the real submission once`() = runTest {
        topicFormRepository.submitResult = ReplySubmitResult.Success(targetPage = 1, refreshUrl = "/hfr/")
        val viewModel = newViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        viewModel.state.test {
            awaitHydratedState()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.submit(TopicFormIntent.SubmitClicked())
        assertEquals(0, topicFormRepository.submitCalls)

        viewModel.effects.test {
            testScheduler.advanceTimeBy(400L)
            viewModel.submit(TopicFormIntent.SubmitConfirmed(viewModel.state.value.armToken))
            val effect = awaitItem() as TopicFormEffect.SubmitSucceeded
            assertEquals(1, effect.targetPage)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("confirm must bypass the preference and POST exactly once", 1, topicFormRepository.submitCalls)
        assertFalse(
            "the dialog must close on confirm — no « confirmation → confirmation » loop",
            viewModel.state.value.showSubmitConfirmation,
        )
    }

    @Test
    fun `confirm-before-posting OFF keeps the one-tap New submit unchanged`() = runTest {
        topicFormRepository.newTopicSubmitResult = NewTopicSubmitResult.Success(
            newTopicId = null,
            newNumreponse = null,
            targetCat = SAMPLE_CAT,
            targetSubcat = SAMPLE_SUBCAT,
            refreshUrl = null,
        )
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT) // default fake → OFF
        viewModel.state.test {
            expectMostRecentItem() // drain hydration
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Topic")))
            viewModel.editBody(TextFieldValue("Body", TextRange(4)))
            viewModel.submit(TopicFormIntent.SubmitClicked())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("OFF must POST directly, no dialog detour", 1, topicFormRepository.newTopicSubmitCalls)
        assertFalse(viewModel.state.value.showSubmitConfirmation)
    }

    @Test
    fun `confirm-before-posting ON parks the New submit then confirm posts it`() = runTest {
        topicFormRepository.newTopicSubmitResult = NewTopicSubmitResult.Success(
            newTopicId = null,
            newNumreponse = null,
            targetCat = SAMPLE_CAT,
            targetSubcat = SAMPLE_SUBCAT,
            refreshUrl = null,
        )
        val viewModel = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        viewModel.state.test {
            expectMostRecentItem() // drain hydration
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Topic")))
            viewModel.editBody(TextFieldValue("Body", TextRange(4)))
            viewModel.submit(TopicFormIntent.SubmitClicked())
            val parked = expectMostRecentItem()
            assertTrue("the confirmation dialog must be armed", parked.showSubmitConfirmation)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("no POST before the user confirms", 0, topicFormRepository.newTopicSubmitCalls)

        testScheduler.advanceTimeBy(400L)
        viewModel.submit(TopicFormIntent.SubmitConfirmed(viewModel.state.value.armToken))

        assertEquals(1, topicFormRepository.newTopicSubmitCalls)
        assertFalse(viewModel.state.value.showSubmitConfirmation)
    }

    @Test
    fun `confirm-before-posting ON dismissing the dialog sends nothing and keeps subject and draft`() = runTest {
        val viewModel = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        viewModel.state.test {
            expectMostRecentItem() // drain hydration
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Topic")))
            viewModel.editBody(TextFieldValue("Body", TextRange(4)))
            viewModel.submit(TopicFormIntent.SubmitClicked())
            viewModel.submit(TopicFormIntent.SubmitConfirmationDismissed)
            val finalState = expectMostRecentItem()
            assertFalse(finalState.showSubmitConfirmation)
            assertEquals("the subject survives the dismissal", "Topic", finalState.subject.text)
            assertEquals("the draft survives the dismissal", "Body", finalState.bodyText)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("dismiss must not POST anything", 0, topicFormRepository.newTopicSubmitCalls)
        assertEquals(0, topicFormRepository.submitCalls)
    }

    @Test
    fun `confirm-before-posting ON never confirms an invalid New form`() = runTest {
        // The confirmation slots in AFTER the canSubmit gate : with no sub-category picked (cat
        // WITH sub-categories, no entry chip), the form is invalid so no dialog may appear.
        val viewModel = newTopicViewModel(
            entrySubcat = null,
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        viewModel.state.test {
            expectMostRecentItem() // drain hydration
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Topic")))
            viewModel.editBody(TextFieldValue("Body", TextRange(4)))
            viewModel.submit(TopicFormIntent.SubmitClicked())
            val finalState = expectMostRecentItem()
            assertFalse("invalid form must not raise the dialog", finalState.showSubmitConfirmation)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, topicFormRepository.newTopicSubmitCalls)
    }

    @Test
    fun `confirm-before-posting ON re-clicking submit keeps the New dialog armed without posting`() = runTest {
        val viewModel = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        viewModel.state.test {
            expectMostRecentItem() // drain hydration
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Topic")))
            viewModel.editBody(TextFieldValue("Body", TextRange(4)))
            viewModel.submit(TopicFormIntent.SubmitClicked())
            val parked = expectMostRecentItem()
            assertTrue("the confirmation dialog must be armed", parked.showSubmitConfirmation)
            cancelAndIgnoreRemainingEvents()
        }

        // Second tap while the dialog is up (double-tap race) : idempotent — re-raising
        // `showSubmitConfirmation = true` is a no-op, and no POST may slip through.
        viewModel.submit(TopicFormIntent.SubmitClicked())

        assertTrue("the dialog must stay armed", viewModel.state.value.showSubmitConfirmation)
        assertFalse("nothing is in flight while the dialog is up", viewModel.state.value.isSubmitting)
        assertEquals("no POST while the dialog is parked", 0, topicFormRepository.newTopicSubmitCalls)
    }

    @Test
    fun `confirm-before-posting ON rapid double confirm posts the New topic exactly once`() = runTest {
        // Hold the first confirmed submit in flight : with UnconfinedTestDispatcher a
        // non-suspending fake would complete synchronously and the second confirm would
        // legitimately re-fire. Same shape as the `submitGate` double-submit test in
        // `PostEditorViewModelTest`. EditFirstPost shares the same guards hoisted into
        // `onSubmitClicked`, so New-mode coverage protects both submit paths.
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.submitGate = gate
        topicFormRepository.newTopicSubmitResult = NewTopicSubmitResult.Success(
            newTopicId = null,
            newNumreponse = null,
            targetCat = SAMPLE_CAT,
            targetSubcat = SAMPLE_SUBCAT,
            refreshUrl = null,
        )
        val viewModel = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        viewModel.state.test {
            expectMostRecentItem() // drain hydration
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Topic")))
            viewModel.editBody(TextFieldValue("Body", TextRange(4)))
            viewModel.submit(TopicFormIntent.SubmitClicked())
            val parked = expectMostRecentItem()
            assertTrue("the confirmation dialog must be armed", parked.showSubmitConfirmation)
            cancelAndIgnoreRemainingEvents()
        }

        testScheduler.advanceTimeBy(400L)
        viewModel.submit(TopicFormIntent.SubmitConfirmed(viewModel.state.value.armToken))
        viewModel.submit(TopicFormIntent.SubmitConfirmed(viewModel.state.value.armToken))

        gate.complete(Unit)

        assertEquals("rapid double confirm must POST exactly once", 1, topicFormRepository.newTopicSubmitCalls)
        assertFalse(viewModel.state.value.showSubmitConfirmation)
        assertFalse(viewModel.state.value.isSubmitting)
    }

    // #1512 — the live buffer wins over the descending UI projection on every boundary.
    @Test
    fun submitUsesLiveBodyBeforeMirrorEmission() = runTest {
        val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue("title")))
        vm.bodyFieldState.edit { append("live body") }
        assertEquals("", vm.state.value.bodyText)
        assertFalse(vm.state.value.canSubmit)
        vm.submit(TopicFormIntent.SubmitClicked())
        testScheduler.runCurrent()
        assertEquals("live body", topicFormRepository.lastSubmittedBbcode)
        assertEquals(1, topicFormRepository.newTopicSubmitCalls)
    }

    @Test
    fun submitRejectsBlankLiveBodyWhenMirrorStillAllowsIt() = runTest {
        val vm = newViewModel()
        assertTrue(vm.state.value.canSubmit)
        vm.bodyFieldState.edit { replace(0, length, "") }
        assertTrue(vm.state.value.bodyText.isNotBlank())
        vm.submit(TopicFormIntent.SubmitClicked())
        testScheduler.runCurrent()
        assertEquals(0, draftStore.beginCalls)
        assertEquals(0, topicFormRepository.submitCalls)
    }

    @Test
    fun armingAndConfirmingValidateLiveBodyBeforeMirrorEmission() = runTest {
        val vm = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue("title")))
        vm.bodyFieldState.edit { append("live body") }
        vm.submit(TopicFormIntent.SubmitClicked())
        val token = vm.state.value.armToken
        assertTrue(token != null)
        syncBody()
        assertEquals("the descending mirror must not disarm", token, vm.state.value.armToken)
        testScheduler.advanceTimeBy(500)
        vm.bodyFieldState.edit { replace(0, length, "confirmed body") }
        assertEquals("live body", vm.state.value.bodyText)
        vm.submit(TopicFormIntent.SubmitConfirmed(token))
        testScheduler.runCurrent()
        assertEquals("confirmed body", topicFormRepository.lastSubmittedBbcode)
        assertEquals(1, topicFormRepository.newTopicSubmitCalls)
    }

    @Test
    fun confirmationRejectsBlankLiveBody() = runTest {
        val vm = newViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        vm.submit(TopicFormIntent.SubmitClicked())
        val token = vm.state.value.armToken
        testScheduler.advanceTimeBy(500)
        vm.bodyFieldState.edit { replace(0, length, "") }
        vm.submit(TopicFormIntent.SubmitConfirmed(token))
        testScheduler.runCurrent()
        assertEquals(0, draftStore.beginCalls)
        assertEquals(0, topicFormRepository.submitCalls)
    }

    @Test
    fun submitFreezesBodyTitleSubcategoryAndOptionsBeforePersistence() = runTest {
        topicFormRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val gate = CompletableDeferred<Unit>()
        draftStore.beginGate = gate
        val vm = newViewModel()
        vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue("frozen title")))
        vm.submit(TopicFormIntent.SubcatSelected(SAMPLE_OTHER_SUBCAT))
        vm.submit(TopicFormIntent.ToggleSignature(false))
        vm.submit(TopicFormIntent.ToggleSmileyDisabled(true))
        vm.submit(TopicFormIntent.ToggleEmailNotification(true))
        vm.submit(TopicFormIntent.MsgIconSelected(6))
        vm.bodyFieldState.edit { replace(0, length, "frozen body") }
        vm.submit(TopicFormIntent.SubmitClicked())
        assertTrue(vm.state.value.isSubmitting)
        vm.editBody(TextFieldValue("late input"))
        vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue("late title")))
        vm.submit(TopicFormIntent.SubcatSelected(SAMPLE_SUBCAT))
        vm.submit(TopicFormIntent.ToggleSignature(true))
        vm.submit(TopicFormIntent.ToggleSmileyDisabled(false))
        vm.submit(TopicFormIntent.ToggleEmailNotification(false))
        vm.submit(TopicFormIntent.MsgIconSelected(1))
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals("frozen body", vm.bodyFieldState.text.toString())
        assertEquals("frozen body", topicFormRepository.lastSubmittedBbcode)
        assertEquals("frozen title", topicFormRepository.lastSubmittedSubject)
        assertEquals(SAMPLE_OTHER_SUBCAT, topicFormRepository.lastSubmittedSubcat)
        assertEquals(ReplyFormOptions(false, true, true), topicFormRepository.lastSubmittedOptions)
        assertEquals("6", topicFormRepository.lastSubmittedForm?.msgIcon)
        val saved = draftStore.saved.getValue(EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE))
        assertEquals("frozen body", saved.body)
        assertEquals("frozen title", saved.subject)
    }

    @Test
    fun autosaveReadsLiveBodyAtDeadline() = runTest {
        val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        testScheduler.advanceTimeBy(800)
        testScheduler.runCurrent()
        assertEquals(0, draftStore.saveCount)
        vm.editBody(TextFieldValue("first"))
        testScheduler.advanceTimeBy(749)
        vm.bodyFieldState.edit { replace(0, length, "latest") }
        assertEquals("first", vm.state.value.bodyText)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals("latest", draftStore.saved[EditorDraftKey.newTopic(SAMPLE_CAT)]?.body)
    }

    @Test
    fun selectionChangeDoesNotAutosave() = runTest {
        val vm = newViewModel()
        vm.editBody(TextFieldValue("body"))
        testScheduler.advanceUntilIdle()
        val writes = draftStore.saveCount
        vm.bodyFieldState.edit { selection = TextRange(1, 3) }
        syncBody()
        testScheduler.advanceUntilIdle()
        assertEquals(writes, draftStore.saveCount)
        assertEquals(TextRange(1, 3), vm.bodyFieldState.selection)
    }

    @Test
    fun closeFlushesLiveBodyBeforeMirrorEmission() = runTest {
        val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        vm.bodyFieldState.edit { append("last keystroke") }
        assertEquals("", vm.state.value.bodyText)
        vm.submit(TopicFormIntent.CloseRequested)
        assertEquals(TopicFormEffect.CloseCommitted, vm.effects.first())
        assertEquals("last keystroke", draftStore.saved[EditorDraftKey.newTopic(SAMPLE_CAT)]?.body)
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun uncertainAttemptRestoresIntoStableBufferWithoutIdenticalAutosave() = runTest {
        topicFormRepository.newTopicSubmitResult = NewTopicSubmitResult.Failure(ReplyFailureReason.Unknown)
        val original = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        original.submit(TopicFormIntent.SubjectChanged(TextFieldValue("uncertain title")))
        original.editBody(TextFieldValue("uncertain body"))
        original.submit(TopicFormIntent.SubmitClicked())
        testScheduler.advanceUntilIdle()
        original.viewModelScope.cancel()
        val writes = draftStore.saveCount
        val restored = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        val field = restored.bodyFieldState
        syncBody()
        assertTrue(field === restored.bodyFieldState)
        assertFalse(field.undoState.canUndo)
        assertEquals("uncertain body", field.text.toString())
        assertEquals(TextRange(14), field.selection)
        assertEquals("uncertain title", restored.state.value.subject.text)
        assertEquals(EditorErrors.uncertain, restored.state.value.submitError)
        assertNull(restored.state.value.restorableDraft)
        testScheduler.advanceTimeBy(800)
        testScheduler.runCurrent()
        assertEquals(writes, draftStore.saveCount)
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun uncertainAttemptAfterConsumedEarlyTypingDoesNotAutosaveRestoredContent() = runTest {
        topicFormRepository.newTopicSubmitResult = NewTopicSubmitResult.Failure(ReplyFailureReason.Unknown)
        val original = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        original.submit(TopicFormIntent.SubjectChanged(TextFieldValue("uncertain title")))
        original.editBody(TextFieldValue("uncertain body"))
        original.submit(TopicFormIntent.SubmitClicked())
        testScheduler.advanceUntilIdle()
        original.viewModelScope.cancel()
        val key = EditorDraftKey.newTopic(SAMPLE_CAT)
        val entry = draftStore.entries.getValue(key)
        assertEquals(EditorDraftStore.AttemptStatus.UNCERTAIN, entry.attempt?.status)
        val writes = draftStore.writes
        val saves = draftStore.saveCount
        val loadCalls = draftStore.loadCalls
        val gate = CompletableDeferred<Unit>()
        draftStore.loadGate = gate
        val restored = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        val field = restored.bodyFieldState
        assertEquals(loadCalls + 1, draftStore.loadCalls)
        assertFalse(restored.state.value.isAwaitingInitialBody)

        restored.editBody(TextFieldValue("typed during read"))
        syncBody()
        // Consume the input before restoration, so the mirror cannot schedule a later autosave for it.
        assertEquals("typed during read", restored.state.value.bodyText)
        assertFalse(gate.isCompleted)
        assertEquals(entry, draftStore.entries[key])
        assertEquals(writes, draftStore.writes)
        assertEquals(saves, draftStore.saveCount)

        gate.complete(Unit)
        syncBody()
        assertTrue(field === restored.bodyFieldState)
        assertEquals("uncertain body", field.text.toString())
        assertEquals("uncertain title", restored.state.value.subject.text)
        assertEquals(EditorErrors.uncertain, restored.state.value.submitError)
        assertNull(restored.state.value.restorableDraft)
        assertTrue(field.undoState.canUndo)
        testScheduler.advanceTimeBy(800)
        testScheduler.runCurrent()
        assertEquals(writes, draftStore.writes)
        assertEquals(saves, draftStore.saveCount)
        assertEquals(entry.version, draftStore.entries[key]?.version)
        assertEquals(entry, draftStore.entries[key])
        field.undoState.undo()
        assertEquals("typed during read", field.text.toString())
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun uncertainFirstPostAttemptRestoresBeforeFormArrives() = runTest {
        topicFormRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val original = newViewModel()
        original.submit(TopicFormIntent.SubjectChanged(TextFieldValue("uncertain title")))
        original.editBody(TextFieldValue("uncertain first post"))
        original.submit(TopicFormIntent.SubmitClicked())
        testScheduler.advanceUntilIdle()
        original.viewModelScope.cancel()
        val writes = draftStore.saveCount
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = gate
        val restored = newViewModel()
        assertTrue(restored.state.value.isLoadingForm)
        assertTrue(restored.state.value.draftHydratedFromServer)
        assertFalse(restored.state.value.isAwaitingInitialBody)
        assertEquals("uncertain first post", restored.bodyFieldState.text.toString())
        assertEquals("uncertain title", restored.state.value.subject.text)
        assertEquals(EditorErrors.uncertain, restored.state.value.submitError)
        assertFalse(restored.bodyFieldState.undoState.canUndo)
        assertNull(restored.state.value.restorableDraft)
        gate.complete(Unit)
        syncBody()
        assertEquals("uncertain first post", restored.bodyFieldState.text.toString())
        testScheduler.advanceTimeBy(800)
        testScheduler.runCurrent()
        assertEquals(writes, draftStore.saveCount)
    }

    @Test
    fun `ED45 ED170 Masquer acknowledges a restored topic attempt before recreation`() = runTest {
        for (mode in TopicFormMode.entries) {
            draftStore = FakeEditorDraftStore()
            val key = topicDraftKey(mode)
            val attempt = restoredUncertainAttempt(mode)
            val posts = postCount()
            val vm = openTopic(mode)
            assertEquals(EditorSubmissionState.Uncertain(attempt), vm.state.value.submission)
            val adapter = chromeAdapter(vm)
            val event = vm.editorEvents.state.visibleEvent!!.key
            adapter.host.barActions.onEvent(event, EditorEventAction.Details)
            adapter.host.dismissDetails(event)
            syncBody()
            assertNull(adapter.host.detail)
            assertEquals(EditorSubmissionState.Ready, vm.state.value.submission)
            assertNull(vm.state.value.submitError)
            // The acknowledgement reserves PersistingAttempt: no « Envoi… » may outlive it.
            assertNoSendingEvent(vm)
            assertNull(vm.editorEvents.state.visibleEvent)
            assertNull(draftStore.entries[key]?.attempt)
            assertEquals("uncertain body", vm.bodyFieldState.text.toString())
            assertEquals("uncertain title", vm.state.value.subject.text)
            vm.viewModelScope.cancel()

            val recreated = openTopic(mode)
            assertEquals(EditorSubmissionState.Ready, recreated.state.value.submission)
            assertNull(recreated.state.value.submitError)
            assertTrue(recreated.editorEvents.state.events.isEmpty())
            assertEquals("uncertain body", draftStore.saved[key]?.body)
            assertEquals(posts, postCount())
        }
    }

    @Test
    fun `ED45 ED170 Masquer also acknowledges a freshly failed topic submission`() = runTest {
        for (mode in TopicFormMode.entries) {
            draftStore = FakeEditorDraftStore()
            val posts = postCount()
            val vm = failUncertainly(mode)
            val adapter = chromeAdapter(vm)
            val event = vm.editorEvents.state.visibleEvent!!.key
            adapter.host.barActions.onEvent(event, EditorEventAction.Details)
            adapter.host.dismissDetails(event)
            syncBody()
            assertEquals(EditorSubmissionState.Ready, vm.state.value.submission)
            assertNull(vm.state.value.submitError)
            assertNoSendingEvent(vm)
            assertNull(vm.editorEvents.state.visibleEvent)
            assertNull(draftStore.entries[topicDraftKey(mode)]?.attempt)
            assertEquals("uncertain body", vm.bodyFieldState.text.toString())
            assertEquals(posts + 1, postCount())
        }
    }

    @Test
    fun `ED85 ED170 Back or detail closing keeps topic uncertainty and recreation offers it again`() = runTest {
        for (mode in TopicFormMode.entries) {
            draftStore = FakeEditorDraftStore()
            val key = topicDraftKey(mode)
            val attempt = restoredUncertainAttempt(mode)
            val posts = postCount()
            val closings = listOf<(TopicFormChromeAdapter, EventKey) -> Unit>(
                { adapter, _ -> adapter.back() }, { adapter, event -> adapter.host.closeDetails(event) })
            closings.forEachIndexed { index, close ->
                val vm = openTopic(mode)
                val adapter = chromeAdapter(vm)
                val event = vm.editorEvents.state.visibleEvent!!.key
                adapter.host.barActions.onEvent(event, EditorEventAction.Details)
                close(adapter, event)
                syncBody()
                assertNull("$mode closing $index", adapter.host.detail)
                assertNull(vm.editorEvents.state.visibleEvent)
                assertEquals(EditorSubmissionState.Uncertain(attempt), vm.state.value.submission)
                assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
                assertEquals(attempt, draftStore.entries[key]?.attempt?.token)
                vm.viewModelScope.cancel()
            }

            val recreated = openTopic(mode)
            assertEquals(EditorSubmissionState.Uncertain(attempt), recreated.state.value.submission)
            assertEquals(EditorErrors.uncertain, recreated.state.value.submitError)
            assertEquals(EditorEventContent.Problem(EditorErrors.uncertain),
                recreated.editorEvents.state.visibleEvent?.content)
            assertEquals(posts, postCount())
        }
    }

    @Test
    fun `ED170 Masquer preserves topic uncertainty on failed durable acknowledgement and recreation`() = runTest {
        for (mode in TopicFormMode.entries) {
            draftStore = FakeEditorDraftStore()
            val key = topicDraftKey(mode)
            val attempt = restoredUncertainAttempt(mode)
            val posts = postCount()
            val vm = openTopic(mode)
            draftStore.finishFailure = java.io.IOException("disk unavailable")
            val finishCalls = draftStore.finishCalls
            val adapter = chromeAdapter(vm)
            val event = vm.editorEvents.state.visibleEvent!!.key
            adapter.host.barActions.onEvent(event, EditorEventAction.Details)
            adapter.host.dismissDetails(event)
            syncBody()
            assertEquals(finishCalls + 1, draftStore.finishCalls)
            assertEquals(EditorSubmissionState.Uncertain(attempt), vm.state.value.submission)
            assertEquals(EditorErrors.uncertain, vm.state.value.submitError)
            // The kept uncertainty is signalled again in this session, bound to the same attempt, without « Envoi… ».
            assertNoSendingEvent(vm)
            val kept = vm.editorEvents.state.visibleEvent!!
            assertEquals(EditorEventContent.Problem(EditorErrors.uncertain), kept.content)
            assertNotEquals(event, kept.key)
            assertEquals(attempt, vm.editorEvents.uncertainAttempt(kept.key))
            assertEquals(attempt, draftStore.entries[key]?.attempt?.token)
            assertEquals("uncertain body", vm.bodyFieldState.text.toString())
            vm.viewModelScope.cancel()

            draftStore.finishFailure = null
            val recreated = openTopic(mode)
            assertEquals(EditorSubmissionState.Uncertain(attempt), recreated.state.value.submission)
            assertEquals(EditorEventContent.Problem(EditorErrors.uncertain),
                recreated.editorEvents.state.visibleEvent?.content)
            assertEquals(posts, postCount())
        }
    }

    @Test
    fun `ED35 ED170 a missing title refusal keeps the restored uncertainty bound until its Masquer`() = runTest {
        for (mode in TopicFormMode.entries) {
            draftStore = FakeEditorDraftStore()
            val key = topicDraftKey(mode)
            val attempt = restoredUncertainAttempt(mode)
            val posts = postCount()
            val vm = openTopic(mode)
            val intents = mutableListOf<TopicFormIntent>()
            val adapter = chromeAdapter(vm, intents)
            val uncertain = vm.editorEvents.state.visibleEvent!!.key
            assertEquals("$mode", attempt, vm.editorEvents.uncertainAttempt(uncertain))
            vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue()))
            adapter.host.submitActions.onSubmit()
            syncBody()
            // The local refusal is delivered behind the earlier warning, without arm, reservation or POST.
            assertEquals("$mode", listOf(TopicFormIntent.SubmitClicked()), intents)
            assertEquals(EditorErrors.missingTitle, vm.state.value.submitError)
            assertTrue(vm.editorEvents.state.events.any {
                it.content == EditorEventContent.Problem(EditorErrors.missingTitle)
            })
            assertNull(vm.state.value.armToken)
            assertEquals(EditorSubmissionState.Uncertain(attempt), vm.state.value.submission)
            assertEquals(uncertain, vm.editorEvents.state.visibleEvent?.key)
            assertEquals(attempt, vm.editorEvents.uncertainAttempt(uncertain))
            assertEquals(attempt, draftStore.entries[key]?.attempt?.token)
            assertEquals(posts, postCount())

            // Masquer of the still bound warning acknowledges the same attempt durably.
            adapter.host.barActions.onEvent(uncertain, EditorEventAction.Details)
            adapter.host.dismissDetails(uncertain)
            syncBody()
            assertEquals(listOf(TopicFormIntent.SubmitClicked(), TopicFormIntent.ErrorDismissed), intents)
            assertNull(adapter.host.detail)
            assertEquals(EditorSubmissionState.Ready, vm.state.value.submission)
            assertNull(draftStore.entries[key]?.attempt)
            assertTrue(vm.editorEvents.state.events.none { it.key == uncertain })
            // The acknowledgement reserves without launching: no ED-44 cleanup, the refusal is now in front
            // (gate R1b minors r1 m4, m-c).
            assertEquals("$mode", EditorEventContent.Problem(EditorErrors.missingTitle),
                vm.editorEvents.state.visibleEvent?.content)
            assertNoSendingEvent(vm)
            assertEquals(posts, postCount())
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `ED45 ED170 local topic Masquer in Ready allows the next failed autosave to deliver information`() = runTest {
        for (mode in TopicFormMode.entries) assertLocalMasquerReleasesAutosave(mode, rearm = false)
    }

    @Test
    fun `ED45 ED170 local topic Masquer after rearming allows the next failed autosave to deliver information`() =
        runTest { for (mode in TopicFormMode.entries) assertLocalMasquerReleasesAutosave(mode, rearm = true) }

    /** I1: Masquer of « Brouillon non écrit » releases deferred draft information, as on dev, without storage. */
    private fun assertLocalMasquerReleasesAutosave(mode: TopicFormMode, rearm: Boolean) {
        draftStore = FakeEditorDraftStore()
        val posts = postCount()
        val vm = openTopic(mode, FakeUserPreferencesRepository(confirmBeforePosting = rearm))
        draftStore.beginFailure = java.io.IOException("disk full")
        vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue("unsent title")))
        vm.editBody(TextFieldValue("unsent body"))
        vm.submit(TopicFormIntent.SubmitClicked())
        if (rearm) {
            dispatcher.scheduler.advanceTimeBy(401)
            vm.submit(TopicFormIntent.SubmitConfirmed(vm.state.value.armToken))
        }
        syncBody()
        assertEquals("$mode", EditorSubmissionState.Ready, vm.state.value.submission)
        assertEquals(EditorErrors.draftWriteFailed, vm.state.value.submitError)
        assertEquals(1, draftStore.beginCalls)
        val intents = mutableListOf<TopicFormIntent>()
        val adapter = chromeAdapter(vm, intents)
        if (rearm) {
            adapter.host.submitActions.onSubmit()
            syncBody()
            assertTrue(vm.state.value.submission is EditorSubmissionState.Armed)
        }
        val submission = vm.state.value.submission
        val activity = listOf(draftStore.writes, draftStore.beginCalls, draftStore.finishCalls, draftStore.saveCount)
        val key = vm.editorEvents.state.visibleEvent!!.key
        assertEquals(EditorEventContent.Problem(EditorErrors.draftWriteFailed),
            vm.editorEvents.state.visibleEvent?.content)
        adapter.host.barActions.onEvent(key, EditorEventAction.Details)
        adapter.host.dismissDetails(key)
        syncBody()
        assertEquals(List(if (rearm) 1 else 0) { TopicFormIntent.SubmitClicked() } +
            TopicFormIntent.DraftWriteFailureDismissed, intents)
        assertEquals(submission, vm.state.value.submission)
        assertNull(vm.state.value.submitError)
        assertNull(adapter.host.detail)
        assertNull(vm.editorEvents.state.visibleEvent)
        assertEquals(activity,
            listOf(draftStore.writes, draftStore.beginCalls, draftStore.finishCalls, draftStore.saveCount))
        assertNoSendingEvent(vm)

        // On dev, the next autosave failure is delivered again once the attempt write failure was hidden.
        draftStore.saveFailure = java.io.IOException("still full")
        vm.editBody(TextFieldValue("continued composition"))
        dispatcher.scheduler.advanceTimeBy(751)
        syncBody()
        assertEquals("$mode", EditorErrors.draftSaveFailed, vm.state.value.submitError)
        assertEquals(EditorEventContent.Problem(EditorErrors.draftSaveFailed),
            vm.editorEvents.state.visibleEvent?.content)
        assertEquals("continued composition", vm.bodyFieldState.text.toString())
        assertEquals(posts, postCount())
        assertEquals(0, draftStore.finishCalls)
        assertNoSendingEvent(vm)
        vm.viewModelScope.cancel()
    }

    private fun openTopic(
        mode: TopicFormMode,
        preferences: UserPreferencesRepository = FakeUserPreferencesRepository(),
    ): TopicFormViewModel = if (mode == TopicFormMode.New) {
        newTopicViewModel(entrySubcat = SAMPLE_SUBCAT, userPreferencesRepository = preferences)
    } else {
        newViewModel(userPreferencesRepository = preferences)
    }

    private fun topicDraftKey(mode: TopicFormMode): String = if (mode == TopicFormMode.New) {
        EditorDraftKey.newTopic(SAMPLE_CAT)
    } else {
        EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE)
    }

    private fun postCount() = topicFormRepository.submitCalls + topicFormRepository.newTopicSubmitCalls

    /** ED-170: an ambiguous POST leaves an uncertain durable attempt under this mode's draft key. */
    private fun failUncertainly(mode: TopicFormMode): TopicFormViewModel {
        topicFormRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        topicFormRepository.newTopicSubmitResult = NewTopicSubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = openTopic(mode)
        vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue("uncertain title")))
        vm.editBody(TextFieldValue("uncertain body"))
        vm.submit(TopicFormIntent.SubmitClicked())
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.submission is EditorSubmissionState.Uncertain)
        return vm
    }

    /** The failed ViewModel dies; the next opening restores its attempt from the store. */
    private fun restoredUncertainAttempt(mode: TopicFormMode): EditorDraftStore.AttemptToken {
        failUncertainly(mode).viewModelScope.cancel()
        return checkNotNull(draftStore.entries[topicDraftKey(mode)]?.attempt).token
    }

    private fun chromeAdapter(vm: TopicFormViewModel, intents: MutableList<TopicFormIntent> = mutableListOf()) =
        TopicFormChromeAdapter({ vm.state.value }, vm.bodyFieldState, vm.editorEvents, { intent ->
            intents += intent
            vm.submit(intent)
        }, {}, {}, vm.chromeMemory)

    private fun assertNoSendingEvent(vm: TopicFormViewModel) =
        assertTrue(vm.editorEvents.state.events.none { it.content is EditorEventContent.InProgress })

    @Test
    fun restoreReplacesUntouchedSeed() = runTest {
        val vm = editWithRestoreOffer()
        assertRestoreAndSingleUndo(vm, TextFieldValue("seed", TextRange(1, 3)), "draft")
    }

    @Test
    fun restoreReplacesBlankField() = runTest {
        val vm = editWithRestoreOffer()
        assertRestoreAndSingleUndo(vm, TextFieldValue("  \n", TextRange(1)), "draft")
    }

    @Test
    fun restoreReplacesBlankFieldWhenSeedEmpty() = runTest {
        draftStore.preload(EditorDraftKey.newTopic(SAMPLE_CAT), EditorDraftStore.Draft(body = "draft"))
        val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        assertRestoreAndSingleUndo(vm, TextFieldValue("  ", TextRange(2)), "draft")
    }

    @Test
    fun restoreKeepsTextTypedAfterSeed() = runTest {
        val vm = editWithRestoreOffer()
        assertRestoreAndSingleUndo(vm, TextFieldValue("seed suffix", TextRange(1)), "draft\n\nsuffix")
    }

    @Test
    fun restoreTreatsBlankSuffixAsUntouchedSeed() = runTest {
        val vm = editWithRestoreOffer()
        assertRestoreAndSingleUndo(vm, TextFieldValue("seed  \n", TextRange(4)), "draft")
    }

    @Test
    fun restoreTrimsTrailingWhitespaceBeforeSeparator() = runTest {
        draftStore.preload(EditorDraftKey.newTopic(SAMPLE_CAT), EditorDraftStore.Draft(body = "draft"))
        val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        assertRestoreAndSingleUndo(vm, TextFieldValue("typed \n\n", TextRange(2)), "typed\n\ndraft")
    }

    @Test
    fun restoreAppendsAfterTypedTextWhenSeedEmpty() = runTest {
        draftStore.preload(EditorDraftKey.newTopic(SAMPLE_CAT), EditorDraftStore.Draft(body = "draft"))
        val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        assertRestoreAndSingleUndo(vm, TextFieldValue("typed  \n", TextRange(1, 3)), "typed\n\ndraft")
        assertEquals("", vm.state.value.draftHydratedContent)
    }

    @Test
    fun restoreAppendsWhenSeedEditedInside() = runTest {
        val vm = editWithRestoreOffer()
        assertRestoreAndSingleUndo(vm, TextFieldValue("seXd  \n", TextRange(2)), "seXd\n\ndraft")
    }

    @Test
    fun restoreIdenticalOfferedBodyRestoresTitleWithoutDuplication() = runTest {
        topicFormRepository.formResult = topicFormRepository.formResult.copy(subject = "", initialContent = "seed")
        draftStore.preload(
            EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE),
            EditorDraftStore.Draft(body = "seed", subject = "restored title"),
        )
        val vm = newViewModel()
        assertEquals("seed", vm.state.value.restorableDraft)
        vm.submit(TopicFormIntent.DraftRestoreRequested)
        assertEquals("seed", vm.bodyFieldState.text.toString())
        assertEquals(TextRange(4), vm.bodyFieldState.selection)
        assertEquals("restored title", vm.state.value.subject.text)
    }

    @Test
    fun restoreTitleOnlyOfferKeepsNonBlankLiveTitle() = runTest {
        topicFormRepository.formResult = topicFormRepository.formResult.copy(
            subject = "live title", initialContent = "seed",
        )
        for (offeredBody in listOf("", "   ")) {
            draftStore = FakeEditorDraftStore()
            draftStore.preload(
                EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE),
                EditorDraftStore.Draft(body = offeredBody, subject = "stored title"),
            )
            val vm = newViewModel()
            vm.editBody(TextFieldValue("seed", TextRange(1, 3)))
            assertEquals(offeredBody, vm.state.value.restorableDraft)
            vm.submit(TopicFormIntent.DraftRestoreRequested)
            assertEquals("seed", vm.bodyFieldState.text.toString())
            assertEquals(TextRange(1, 3), vm.bodyFieldState.selection)
            assertEquals("live title", vm.state.value.subject.text)
            assertNull(vm.state.value.restorableDraft)
            vm.viewModelScope.cancel()
        }
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun titleOnlyRestoreAfterHydrationKeepsBodyAndSingleUndoInBothModes() = runTest {
        topicFormRepository.formResult = topicFormRepository.formResult.copy(subject = "", initialContent = "seed")
        for (creating in listOf(false, true)) {
            for (offeredBody in listOf("", "   ")) {
                draftStore = FakeEditorDraftStore()
                val key = if (creating) {
                    EditorDraftKey.newTopic(SAMPLE_CAT)
                } else {
                    EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE)
                }
                draftStore.preload(key, EditorDraftStore.Draft(body = offeredBody, subject = "restored title"))
                val vm = if (creating) newTopicViewModel(entrySubcat = SAMPLE_SUBCAT) else newViewModel()
                val seed = if (creating) "" else "seed"
                assertFalse(vm.state.value.isLoadingForm)
                assertTrue(vm.state.value.draftHydratedFromServer)
                assertFalse(vm.state.value.isAwaitingInitialBody)
                assertEquals(seed, vm.bodyFieldState.text.toString())
                assertEquals(offeredBody, vm.state.value.restorableDraft)
                assertFalse(vm.bodyFieldState.undoState.canUndo)
                val typed = TextFieldValue("$seed suffix", TextRange(1, 3))
                vm.editBody(typed)
                assertTrue(vm.bodyFieldState.undoState.canUndo)

                vm.submit(TopicFormIntent.DraftRestoreRequested)
                syncBody()
                assertEquals(typed.text, vm.bodyFieldState.text.toString())
                assertEquals(typed.selection, vm.bodyFieldState.selection)
                assertEquals(seed, vm.state.value.draftHydratedContent)
                assertTrue(vm.state.value.draftHydratedFromServer)
                assertEquals("restored title", vm.state.value.subject.text)
                assertNull(vm.state.value.restorableDraft)
                assertNull(vm.state.value.restorableSubject)
                assertTrue(vm.bodyFieldState.undoState.canUndo)
                vm.bodyFieldState.undoState.undo()
                assertEquals(seed, vm.bodyFieldState.text.toString())
                assertEquals(TextRange(seed.length), vm.bodyFieldState.selection)
                assertFalse(vm.bodyFieldState.undoState.canUndo)
                vm.viewModelScope.cancel()
            }
        }
    }

    @Test
    fun restoreIdenticalLiveBodyDoesNotDuplicateIt() = runTest {
        val vm = editWithRestoreOffer()
        vm.editBody(TextFieldValue("draft", TextRange(1, 3)))
        vm.submit(TopicFormIntent.DraftRestoreRequested)
        assertEquals("draft", vm.bodyFieldState.text.toString())
        assertEquals(TextRange(5), vm.bodyFieldState.selection)
        assertEquals("seed", vm.state.value.draftHydratedContent)
    }

    @Test
    fun editReopenRestoreYieldsSingleCopy() = runTest {
        topicFormRepository.formResult = topicFormRepository.formResult.copy(initialContent = "seed")
        val original = newViewModel()
        original.editBody(TextFieldValue("saved revision"))
        original.submit(TopicFormIntent.CloseRequested)
        assertEquals(TopicFormEffect.CloseCommitted, original.effects.first())
        original.viewModelScope.cancel()
        val reopened = newViewModel()
        assertEquals("saved revision", reopened.state.value.restorableDraft)
        assertRestoreAndSingleUndo(reopened, TextFieldValue("seed", TextRange(4)), "saved revision")
    }

    @Test
    fun lateHydrationRejectsInputBeforeMirrorEmission() = runTest {
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = gate
        val vm = newViewModel()
        vm.editBody(TextFieldValue("typed before form"), advanceMirror = false)
        assertEquals("", vm.bodyFieldState.text.toString())
        assertEquals("", vm.state.value.bodyText)
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(topicFormRepository.formResult.initialContent, vm.bodyFieldState.text.toString())
        assertEquals(topicFormRepository.formResult.subject, vm.state.value.subject.text)
        assertTrue(vm.state.value.draftHydratedFromServer)
        assertEquals(topicFormRepository.formResult.initialContent, vm.state.value.draftHydratedContent)
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun rejectedEarlyBlankInputDoesNotPolluteHydrationUndoHistory() = runTest {
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = gate
        val vm = newViewModel()
        vm.editBody(TextFieldValue("  ", TextRange(2)))
        assertEquals("", vm.bodyFieldState.text.toString())
        assertFalse(vm.bodyFieldState.undoState.canUndo)
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(topicFormRepository.formResult.initialContent, vm.bodyFieldState.text.toString())
        assertFalse(vm.bodyFieldState.undoState.canUndo)
        vm.bodyFieldState.undoState.undo()
        assertEquals(topicFormRepository.formResult.initialContent, vm.bodyFieldState.text.toString())
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun restoreDisabledWhileSeedFormLoads() = runTest {
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = gate
        draftStore.preload(
            EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE),
            EditorDraftStore.Draft(body = "draft", subject = "draft title"),
        )
        val vm = newViewModel()
        val loading = vm.state.value
        val initialSelection = vm.bodyFieldState.selection
        assertTrue(loading.isLoadingForm)
        assertFalse(loading.draftHydratedFromServer)
        assertFalse(loading.subjectHydratedFromServer)
        assertEquals("draft", loading.restorableDraft)
        assertEquals("draft title", loading.restorableSubject)

        vm.submit(TopicFormIntent.DraftRestoreRequested)
        syncBody()
        assertEquals(loading, vm.state.value)
        assertEquals("", vm.bodyFieldState.text.toString())
        assertEquals(initialSelection, vm.bodyFieldState.selection)
        assertFalse(vm.bodyFieldState.undoState.canUndo)
        assertEquals(0, draftStore.saveCount)

        gate.complete(Unit)
        syncBody()
        val seed = topicFormRepository.formResult.initialContent
        assertFalse(vm.state.value.isLoadingForm)
        assertFalse(vm.state.value.isAwaitingInitialBody)
        assertTrue(vm.state.value.draftHydratedFromServer)
        assertTrue(vm.state.value.subjectHydratedFromServer)
        assertEquals(seed, vm.bodyFieldState.text.toString())
        assertEquals(seed, vm.state.value.draftHydratedContent)
        assertEquals("draft", vm.state.value.restorableDraft)
        assertEquals("draft title", vm.state.value.restorableSubject)
        val hydratedSelection = vm.bodyFieldState.selection

        vm.submit(TopicFormIntent.DraftRestoreRequested)
        syncBody()
        assertEquals("draft", vm.bodyFieldState.text.toString())
        assertEquals(topicFormRepository.formResult.subject, vm.state.value.subject.text)
        assertEquals(seed, vm.state.value.draftHydratedContent)
        assertNull(vm.state.value.restorableDraft)
        assertEquals(TextRange(5), vm.bodyFieldState.selection)
        vm.bodyFieldState.undoState.undo()
        assertEquals(seed, vm.bodyFieldState.text.toString())
        assertEquals(hydratedSelection, vm.bodyFieldState.selection)
    }

    @Test
    fun restoreAllowedWithEmptySeedAfterFormFailure() = runTest {
        topicFormRepository.formFailure = java.io.IOException("offline")
        draftStore.preload(
            EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE),
            EditorDraftStore.Draft(body = "D"),
        )
        val vm = newViewModel()
        assertFalse(vm.state.value.isLoadingForm)
        assertTrue(vm.state.value.isAwaitingInitialBody)
        assertEquals(EditorErrors.formNetwork, vm.state.value.submitError)
        assertEquals("", vm.state.value.draftHydratedContent)
        assertEquals("", vm.bodyFieldState.text.toString())
        assertEquals("D", vm.state.value.restorableDraft)

        vm.submit(TopicFormIntent.DraftRestoreRequested)
        syncBody()
        assertEquals("D", vm.bodyFieldState.text.toString())
        assertTrue(vm.state.value.draftHydratedFromServer)
        assertFalse(vm.state.value.isAwaitingInitialBody)
        assertEquals("", vm.state.value.draftHydratedContent)
        assertTrue(vm.state.value.draftHydratedFromServer)
        assertNull(vm.state.value.restorableDraft)
        assertEquals(EditorErrors.formNetwork, vm.state.value.submitError)

        // The restore decision survives erasure: only the blank title may hydrate on reload.
        vm.editBody(TextFieldValue(""))
        assertEquals("", vm.bodyFieldState.text.toString())
        topicFormRepository.formFailure = null
        vm.submit(TopicFormIntent.ReloadRequested)
        syncBody()
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.isLoadingForm)
        assertNull(vm.state.value.submitError)
        assertEquals("", vm.bodyFieldState.text.toString())
        assertEquals("", vm.state.value.draftHydratedContent)
        assertTrue(vm.state.value.draftHydratedFromServer)
        assertEquals("Sample first post title", vm.state.value.subject.text)
        assertNull(vm.state.value.restorableDraft)
    }

    @Test
    fun titleOnlyRestoreAfterFormFailurePreservesHydrationPolicyInBothModes() = runTest {
        for (creating in listOf(false, true)) {
            for (offeredBody in listOf("", "   ")) {
                topicFormRepository.formFailure = java.io.IOException("offline")
                draftStore = FakeEditorDraftStore()
                val key = if (creating) {
                    EditorDraftKey.newTopic(SAMPLE_CAT)
                } else {
                    EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE)
                }
                draftStore.preload(key, EditorDraftStore.Draft(body = offeredBody, subject = "restored title"))
                val vm = if (creating) newTopicViewModel(entrySubcat = SAMPLE_SUBCAT) else newViewModel()
                assertFalse(vm.state.value.isLoadingForm)
                assertEquals(EditorErrors.formNetwork, vm.state.value.submitError)
                assertEquals(offeredBody, vm.state.value.restorableDraft)
                assertEquals("", vm.bodyFieldState.text.toString())
                // New topics start with hydration disabled; edit-first-post still awaits its server body.
                assertEquals(creating, vm.state.value.draftHydratedFromServer)
                assertEquals(!creating, vm.state.value.isAwaitingInitialBody)

                vm.submit(TopicFormIntent.DraftRestoreRequested)
                syncBody()
                assertEquals("restored title", vm.state.value.subject.text)
                assertEquals("", vm.bodyFieldState.text.toString())
                assertEquals(TextRange(0), vm.bodyFieldState.selection)
                assertEquals("", vm.state.value.draftHydratedContent)
                assertEquals(creating, vm.state.value.draftHydratedFromServer)
                assertEquals(!creating, vm.state.value.isAwaitingInitialBody)
                assertEquals(EditorErrors.formNetwork, vm.state.value.submitError)
                assertNull(vm.state.value.restorableDraft)
                assertNull(vm.state.value.restorableSubject)
                if (!creating) {
                    vm.editBody(TextFieldValue("still locked after title-only restore"))
                    assertEquals("", vm.bodyFieldState.text.toString())
                }

                topicFormRepository.formFailure = null
                vm.submit(TopicFormIntent.ReloadRequested)
                syncBody()
                testScheduler.advanceUntilIdle()
                val expectedBody = if (creating) "" else topicFormRepository.formResult.initialContent
                assertEquals(expectedBody, vm.bodyFieldState.text.toString())
                assertEquals(expectedBody, vm.state.value.draftHydratedContent)
                assertTrue(vm.state.value.draftHydratedFromServer)
                assertEquals("restored title", vm.state.value.subject.text)
                assertFalse(vm.state.value.isLoadingForm)
                assertFalse(vm.state.value.isAwaitingInitialBody)
                assertNull(vm.state.value.submitError)
                vm.viewModelScope.cancel()
            }
        }
    }

    @Test
    fun titleOnlyRestoreAfterHydrationKeepsSeedBody() = runTest {
        topicFormRepository.formResult = topicFormRepository.formResult.copy(subject = "")
        draftStore.preload(
            EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE),
            EditorDraftStore.Draft(body = "", subject = "T"),
        )
        val vm = newViewModel()
        val seed = topicFormRepository.formResult.initialContent
        assertEquals(seed, vm.bodyFieldState.text.toString())
        assertTrue(vm.state.value.draftHydratedFromServer)
        assertEquals("", vm.state.value.restorableDraft)
        vm.editBody(TextFieldValue(seed, TextRange(1, 3)))

        vm.submit(TopicFormIntent.DraftRestoreRequested)
        syncBody()
        assertEquals("T", vm.state.value.subject.text)
        assertEquals(seed, vm.bodyFieldState.text.toString())
        assertEquals(seed, vm.state.value.draftHydratedContent)
        assertEquals(TextRange(1, 3), vm.bodyFieldState.selection)
        assertTrue(vm.state.value.draftHydratedFromServer)
        assertFalse(vm.state.value.isAwaitingInitialBody)
        assertNull(vm.state.value.restorableDraft)
    }

    @Test
    fun delayedNewDraftOfferDoesNotReplaceLiveTyping() = runTest {
        for (mirrored in listOf(true, false)) {
            val gate = CompletableDeferred<Unit>()
            draftStore = FakeEditorDraftStore().apply { loadGate = gate }
            draftStore.preload(
                EditorDraftKey.newTopic(SAMPLE_CAT),
                EditorDraftStore.Draft(body = "stored body", subject = "stored title"),
            )
            val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
            if (mirrored) {
                // The mirror runs during the initial draft read (R0c PR 3).
                vm.editBody(TextFieldValue("typed before load"))
            } else {
                // Direct edit without apply notifications: the mirror still lags when the read completes.
                vm.bodyFieldState.edit { append("typed before load") }
            }
            assertEquals("typed before load", vm.bodyFieldState.text.toString())
            assertEquals(if (mirrored) "typed before load" else "", vm.state.value.bodyText)
            gate.complete(Unit)
            testScheduler.runCurrent()
            assertNull(vm.state.value.restorableDraft)
            assertEquals("typed before load", vm.bodyFieldState.text.toString())
            assertEquals("", vm.state.value.draftHydratedContent)
        }
    }

    @Test
    fun initialDraftReadAutosavesNewTypingButRejectsFirstPostTyping() = runTest {
        for (creating in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            draftStore = FakeEditorDraftStore().apply { loadGate = gate }
            val key = if (creating) {
                EditorDraftKey.newTopic(SAMPLE_CAT)
            } else {
                EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE)
            }
            draftStore.preload(key, EditorDraftStore.Draft(body = "stored body", subject = "stored title"))
            val vm = if (creating) newTopicViewModel(entrySubcat = SAMPLE_SUBCAT) else newViewModel()
            assertEquals(1, draftStore.loadCalls)
            vm.editBody(TextFieldValue("typed before load"))
            syncBody()
            // New input is consumed before the binding initializes; first-post input stays rejected.
            val acceptedBody = if (creating) "typed before load" else ""
            assertEquals(acceptedBody, vm.bodyFieldState.text.toString())
            assertEquals(acceptedBody, vm.state.value.bodyText)
            assertEquals(!creating, vm.state.value.isAwaitingInitialBody)
            assertFalse(gate.isCompleted)
            assertEquals(0, draftStore.saveCount)
            assertEquals("stored body", draftStore.saved[key]?.body)

            gate.complete(Unit)
            syncBody()
            val seed = if (creating) "" else topicFormRepository.formResult.initialContent
            val liveBody = if (creating) acceptedBody else seed
            assertEquals(if (creating) null else "stored body", vm.state.value.restorableDraft)
            assertEquals(liveBody, vm.bodyFieldState.text.toString())
            assertEquals(seed, vm.state.value.draftHydratedContent)
            assertFalse(vm.state.value.isAwaitingInitialBody)
            testScheduler.advanceTimeBy(800)
            testScheduler.runCurrent()
            assertEquals(if (creating) acceptedBody else "stored body", draftStore.saved[key]?.body)
            assertEquals(if (creating) 1 else 0, draftStore.saveCount)
            assertEquals(if (creating) 1 else 0, draftStore.writes)
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun formArrivalKeepsOfferWhenEarlyInputIsRejected() = runTest {
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = gate
        draftStore.preload(
            EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE),
            EditorDraftStore.Draft(body = "stored body", subject = topicFormRepository.formResult.subject),
        )
        val vm = newViewModel()
        vm.editBody(TextFieldValue("stored body"), advanceMirror = false)
        assertEquals("", vm.bodyFieldState.text.toString())
        assertEquals("", vm.state.value.bodyText)
        gate.complete(Unit)
        testScheduler.runCurrent()
        val seed = topicFormRepository.formResult.initialContent
        assertEquals("stored body", vm.state.value.restorableDraft)
        assertEquals(seed, vm.bodyFieldState.text.toString())
        assertEquals(seed, vm.state.value.draftHydratedContent)
        testScheduler.advanceTimeBy(800)
        testScheduler.runCurrent()
        assertEquals("stored body", draftStore.saved[EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE)]?.body)
        assertEquals(0, draftStore.saveCount)
    }

    @Test
    fun formArrivalComparesOfferWithLiveBodyBeforeMirrorEmission() = runTest {
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = gate
        draftStore.preload(
            EditorDraftKey.newTopic(SAMPLE_CAT),
            EditorDraftStore.Draft(body = "stored body"),
        )
        val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        assertEquals("stored body", vm.state.value.restorableDraft)
        // Direct edit without apply notifications: the mirror has not emitted yet when the form arrives.
        vm.bodyFieldState.edit { append("stored body") }
        assertEquals("stored body", vm.bodyFieldState.text.toString())
        assertEquals("", vm.state.value.bodyText)

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertNull(vm.state.value.restorableDraft)
        assertNull(vm.state.value.restorableSubject)
        assertEquals("stored body", vm.bodyFieldState.text.toString())
    }

    @Test
    fun seedDoesNotChangeOnRefetchOrRestore() = runTest {
        val vm = editWithRestoreOffer()
        topicFormRepository.formResult = topicFormRepository.formResult.copy(initialContent = "refetched seed")
        vm.submit(TopicFormIntent.ReloadRequested)
        testScheduler.runCurrent()
        assertEquals("seed", vm.state.value.draftHydratedContent)
        assertEquals("seed", vm.bodyFieldState.text.toString())
        vm.submit(TopicFormIntent.DraftRestoreRequested)
        assertEquals("draft", vm.bodyFieldState.text.toString())
        assertEquals("seed", vm.state.value.draftHydratedContent)
    }

    @Test
    fun serverHydrationDoesNotAutosaveAnIdenticalDraft() = runTest {
        val vm = newViewModel()
        syncBody()
        testScheduler.advanceTimeBy(800)
        testScheduler.runCurrent()
        assertEquals(topicFormRepository.formResult.initialContent, vm.state.value.bodyText)
        assertEquals(0, draftStore.saveCount)
        assertEquals(0, draftStore.writes)
    }

    @Test
    fun seedAutosaveNeverOverwritesOfferedDraft() = runTest {
        val vm = editWithRestoreOffer()
        val key = EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE)
        syncBody()
        testScheduler.advanceTimeBy(800)
        testScheduler.runCurrent()
        assertEquals("seed", vm.state.value.bodyText)
        assertEquals("draft", vm.state.value.restorableDraft)
        assertEquals("draft", draftStore.saved[key]?.body)
        assertEquals(0, draftStore.saveCount)
    }

    @Test
    fun pendingTitleAutosaveCannotWriteServerSeedOverOfferedDraft() = runTest {
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = gate
        val key = EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE)
        draftStore.preload(key, EditorDraftStore.Draft(body = "draft", subject = "draft title"))
        val vm = newViewModel()
        vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue("typed title")))
        testScheduler.advanceTimeBy(300)
        gate.complete(Unit)
        syncBody()
        testScheduler.advanceTimeBy(800)
        testScheduler.runCurrent()
        assertEquals(topicFormRepository.formResult.initialContent, vm.bodyFieldState.text.toString())
        assertEquals("draft", vm.state.value.restorableDraft)
        assertEquals("draft", draftStore.saved[key]?.body)
        assertEquals("draft title", draftStore.saved[key]?.subject)
        assertEquals(0, draftStore.saveCount)
    }

    @Test
    fun bodyMutationsAreBlockedDuringUploadButHeadersAndCallbackAreAccepted() = runTest {
        val gate = CompletableDeferred<Unit>()
        val uploads = FakeUploadRepository().apply { uploadGate = gate }
        draftStore.preload(EditorDraftKey.newTopic(SAMPLE_CAT), EditorDraftStore.Draft(body = "draft"))
        val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT, uploadRepository = uploads)
        vm.editBody(TextFieldValue("typed", TextRange(2, 4)))
        vm.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1")))
        assertTrue(vm.state.value.isUploading)
        vm.submit(TopicFormIntent.DraftRestoreRequested)
        vm.submit(TopicFormIntent.ToolbarActionClicked(BbcodeAction.Quote))
        vm.submit(TopicFormIntent.SmileySelected(":jap:"))
        vm.submit(TopicFormIntent.ImageUrlInserted("https://example.com/blocked.png"))
        vm.submit(TopicFormIntent.ImagesPicked(listOf("content://ignored")))
        vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue("accepted title")))
        vm.submit(TopicFormIntent.SubcatSelected(SAMPLE_OTHER_SUBCAT))
        val signatureEnabled = !vm.state.value.signatureEnabled
        vm.submit(TopicFormIntent.ToggleSignature(signatureEnabled))
        vm.editBody(TextFieldValue("blocked"))
        assertEquals("typed", vm.bodyFieldState.text.toString())
        assertEquals(TextRange(2, 4), vm.bodyFieldState.selection)
        assertEquals("draft", vm.state.value.restorableDraft)
        assertEquals("accepted title", vm.state.value.subject.text)
        assertEquals(SAMPLE_OTHER_SUBCAT, vm.state.value.selectedSubcat)
        assertEquals(signatureEnabled, vm.state.value.signatureEnabled)
        assertEquals(1, uploads.uploadCalls)
        gate.complete(Unit)
        syncBody()
        assertTrue(vm.bodyFieldState.text.contains("[img]"))
        assertEquals("draft", vm.state.value.restorableDraft)
        assertFalse(vm.state.value.isUploading)
    }

    @Test
    fun fastUploadCannotReplaceFirstPostBeforeFormArrives() = runTest {
        val formGate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = formGate
        val seed = topicFormRepository.formResult.initialContent
        val uploads = FakeUploadRepository() // Completes immediately if an upload is allowed to start.
        val vm = newViewModel(uploadRepository = uploads)
        vm.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1")))
        assertTrue(vm.state.value.isLoadingForm)
        assertFalse(vm.state.value.isUploading)
        assertEquals(0, uploads.uploadCalls)
        assertEquals("", vm.bodyFieldState.text.toString())

        formGate.complete(Unit)
        syncBody()
        assertFalse(vm.state.value.isLoadingForm)
        assertFalse(vm.state.value.isUploading)
        assertTrue(vm.state.value.draftHydratedFromServer)
        assertEquals(seed, vm.bodyFieldState.text.toString())
        assertEquals(TextRange(seed.length), vm.bodyFieldState.selection)
        assertEquals(seed, vm.state.value.draftHydratedContent)

        // Picking again after hydration may upload; its result is appended to the actual first post.
        vm.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1")))
        syncBody()
        val imageUrl = "https://rehost.diberie.com/Picture/Get/f/1"
        // Hydration parks the caret at the end; the first completed upload adds no leading newline.
        val expected = "$seed[img]$imageUrl[/img]"
        assertEquals(expected, vm.bodyFieldState.text.toString())
        assertEquals(TextRange(expected.length), vm.bodyFieldState.selection)
        assertEquals(seed, vm.state.value.draftHydratedContent)
        assertFalse(vm.state.value.isUploading)
        assertEquals(1, uploads.uploadCalls)
        vm.submit(TopicFormIntent.SubmitClicked())
        testScheduler.advanceUntilIdle()
        assertEquals(1, topicFormRepository.submitCalls)
        assertEquals(expected, topicFormRepository.lastSubmittedBbcode)
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun restoreWhileArmedDisarmsThenRestoresAndRejectsTheOldConfirmation() = runTest {
        topicFormRepository.formResult = topicFormRepository.formResult.copy(initialContent = "seed")
        draftStore.preload(
            EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE),
            EditorDraftStore.Draft(body = "draft"),
        )
        val vm = newViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        vm.bodyFieldState.edit { selection = TextRange(1, 3) }
        vm.submit(TopicFormIntent.SubmitClicked())
        val token = requireNotNull(vm.state.value.armToken)
        assertTrue(vm.state.value.showSubmitConfirmation)

        vm.submit(TopicFormIntent.DraftRestoreRequested)
        assertNull(vm.state.value.armToken)
        assertFalse(vm.state.value.showSubmitConfirmation)
        assertEquals("draft", vm.bodyFieldState.text.toString())
        assertEquals(TextRange(5), vm.bodyFieldState.selection)
        assertEquals("seed", vm.state.value.draftHydratedContent)
        assertNull(vm.state.value.restorableDraft)
        testScheduler.advanceTimeBy(400)
        vm.submit(TopicFormIntent.SubmitConfirmed(token))
        testScheduler.runCurrent()
        assertEquals(0, topicFormRepository.submitCalls)
        vm.bodyFieldState.undoState.undo()
        assertEquals("seed", vm.bodyFieldState.text.toString())
        assertEquals(TextRange(1, 3), vm.bodyFieldState.selection)
    }

    private fun editWithRestoreOffer(): TopicFormViewModel {
        topicFormRepository.formResult = topicFormRepository.formResult.copy(initialContent = "seed")
        draftStore.preload(
            EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE),
            EditorDraftStore.Draft(body = "draft"),
        )
        return newViewModel()
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun assertRestoreAndSingleUndo(vm: TopicFormViewModel, before: TextFieldValue, expected: String) {
        vm.editBody(before)
        val seed = vm.state.value.draftHydratedContent
        vm.submit(TopicFormIntent.DraftRestoreRequested)
        assertEquals(expected, vm.bodyFieldState.text.toString())
        assertEquals(TextRange(expected.length), vm.bodyFieldState.selection)
        assertEquals(seed, vm.state.value.draftHydratedContent)
        assertNull(vm.state.value.restorableDraft)
        assertTrue(vm.bodyFieldState.undoState.canUndo)
        vm.bodyFieldState.undoState.undo()
        assertEquals(before.text, vm.bodyFieldState.text.toString())
        assertEquals(before.selection, vm.bodyFieldState.selection)
    }

    @Test
    fun previewIsClearedWhenHiddenAndRecomputedWhenReopened() = runTest {
        val vm = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            defaultDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.editBody(TextFieldValue("old"))
        vm.submit(TopicFormIntent.TogglePreview)
        testScheduler.runCurrent()
        assertEquals(previewParser.parsePreview("old"), vm.state.value.preview)
        vm.submit(TopicFormIntent.TogglePreview)
        assertEquals(PostContent(emptyList()), vm.state.value.preview)
        vm.editBody(TextFieldValue("new"))
        vm.submit(TopicFormIntent.TogglePreview)
        assertEquals(PostContent(emptyList()), vm.state.value.preview)
        testScheduler.runCurrent()
        assertEquals(previewParser.parsePreview("new"), vm.state.value.preview)
        vm.submit(TopicFormIntent.TogglePreview)
        vm.submit(TopicFormIntent.TogglePreview)
        testScheduler.runCurrent()
        assertEquals(previewParser.parsePreview("new"), vm.state.value.preview)
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
        val oldPreview = previewParser.parsePreview("old")
        val newPreview = previewParser.parsePreview("new")
        val parser = BbcodePreviewParser { text ->
            if (text == "old") {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "preview barrier timed out" }
                oldPreview
            } else {
                newPreview
            }
        }
        val vm = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT, previewParser = parser, defaultDispatcher = parserDispatcher,
        )
        val published = mutableListOf<PostContent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.state.collect { published += it.preview }
        }
        try {
            vm.editBody(TextFieldValue("old"))
            vm.submit(TopicFormIntent.TogglePreview)
            testScheduler.runCurrent()
            assertTrue("parse must run off Main", started.await(5, TimeUnit.SECONDS))
            if (hide) {
                vm.submit(TopicFormIntent.TogglePreview)
            } else {
                // No notification: the final live-buffer check must reject the old result itself.
                vm.bodyFieldState.edit { replace(0, length, "new") }
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
    @OptIn(ExperimentalFoundationApi::class)
    fun formattingSmileysQuotesAndImageUrlsEachUseOneUndoableEdit() = runTest {
        val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        for (intent in listOf(
            TopicFormIntent.ToolbarActionClicked(BbcodeAction.Bold),
            TopicFormIntent.ToolbarActionClicked(BbcodeAction.Quote),
            TopicFormIntent.SmileySelected(":jap:"),
            TopicFormIntent.ImageUrlInserted("https://example.com/image.png"),
        )) {
            vm.editBody(TextFieldValue("before selected after", TextRange(7, 15)))
            vm.submit(intent)
            assertTrue(intent.toString(), vm.bodyFieldState.text.toString() != "before selected after")
            vm.bodyFieldState.undoState.undo()
            assertEquals(intent.toString(), "before selected after", vm.bodyFieldState.text.toString())
            assertEquals(intent.toString(), TextRange(7, 15), vm.bodyFieldState.selection)
        }
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun uploadedImageUsesOneUndoableEdit() = runTest {
        val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        vm.editBody(TextFieldValue("before selected after", TextRange(7, 15)))
        vm.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1")))
        syncBody()
        assertTrue(vm.bodyFieldState.text.contains("[img]"))
        vm.bodyFieldState.undoState.undo()
        assertEquals("before selected after", vm.bodyFieldState.text.toString())
        assertEquals(TextRange(7, 15), vm.bodyFieldState.selection)
    }

    // ----- #405 : draft autosave / restore -----------------------------------

    @Test
    fun `New autosave persists subject and body under the newTopic key`() = runTest {
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        syncBody()
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("My title")))
        viewModel.editBody(TextFieldValue("My body"))
        testScheduler.advanceTimeBy(800L)
        testScheduler.runCurrent()

        val key = EditorDraftKey.newTopic(SAMPLE_CAT)
        assertEquals("My body", draftStore.saved[key]?.body)
        assertEquals("My title", draftStore.saved[key]?.subject)
        assertFalse("topic drafts are not private", draftStore.saved[key]?.isPrivate == true)
    }

    @Test
    fun `New restore surfaces and applies the cached subject and body`() = runTest {
        draftStore.preload(
            EditorDraftKey.newTopic(SAMPLE_CAT),
            EditorDraftStore.Draft(body = "rescued body", subject = "rescued title"),
        )
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        syncBody()
        testScheduler.advanceUntilIdle()
        assertEquals("rescued body", viewModel.state.value.restorableDraft)
        assertEquals("rescued title", viewModel.state.value.restorableSubject)
        assertEquals("draft is not auto-applied", "", viewModel.state.value.bodyText)

        viewModel.submit(TopicFormIntent.DraftRestoreRequested)
        syncBody()
        testScheduler.advanceUntilIdle()

        assertEquals("rescued body", viewModel.state.value.bodyText)
        assertEquals("rescued title", viewModel.state.value.subject.text)
        assertNull(viewModel.state.value.restorableDraft)
    }

    @Test
    fun `New recreation reoffers until decision and typing still autosaves`() = runTest {
        val key = EditorDraftKey.newTopic(SAMPLE_CAT)
        val savedStateHandle = SavedStateHandle()
        draftStore.preload(
            key,
            EditorDraftStore.Draft(body = "rescued body", subject = "rescued title"),
        )
        val first = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            savedStateHandle = savedStateHandle,
        )
        syncBody()
        testScheduler.advanceUntilIdle()
        assertEquals("rescued body", first.state.value.restorableDraft)
        assertNull(savedStateHandle.get<String>("draft_restore_offer_fingerprint"))

        val recreated = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            savedStateHandle = savedStateHandle,
        )
        syncBody()
        testScheduler.advanceUntilIdle()
        assertEquals("rescued body", recreated.state.value.restorableDraft)
        assertEquals("rescued title", recreated.state.value.restorableSubject)

        recreated.submit(TopicFormIntent.SubjectChanged(TextFieldValue("new live title")))
        recreated.editBody(TextFieldValue("new live body"))
        testScheduler.advanceTimeBy(800L)
        testScheduler.runCurrent()
        assertEquals("new live body", draftStore.saved[key]?.body)
        assertEquals("new live title", draftStore.saved[key]?.subject)
        assertEquals("tester", draftStore.lastSavedOwner)

        recreated.submit(TopicFormIntent.DraftRestoreRequested)
        syncBody()
        testScheduler.advanceUntilIdle()
        assertNull(savedStateHandle.get<String>("draft_restore_offer_fingerprint"))
        assertEquals("new live body\n\nrescued body", draftStore.saved[key]?.body)

        val afterRestore = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            savedStateHandle = savedStateHandle,
        )
        syncBody()
        testScheduler.advanceUntilIdle()
        assertNull(afterRestore.state.value.restorableDraft)
        assertEquals("new live body\n\nrescued body", afterRestore.bodyFieldState.text.toString())
        assertEquals("new live title", afterRestore.state.value.subject.text)
    }

    @Test
    fun `New process recreation offers a newer autosaved draft after live state was lost`() = runTest {
        val key = EditorDraftKey.newTopic(SAMPLE_CAT)
        val savedStateHandle = SavedStateHandle()
        draftStore.preload(key, EditorDraftStore.Draft(body = "first body", subject = "first title"))
        val first = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            savedStateHandle = savedStateHandle,
        )
        syncBody()
        testScheduler.advanceUntilIdle()
        assertEquals("first body", first.state.value.restorableDraft)

        draftStore.preload(key, EditorDraftStore.Draft(body = "new body", subject = "new title"))
        val recreated = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            savedStateHandle = savedStateHandle,
        )
        syncBody()
        testScheduler.advanceUntilIdle()

        assertEquals("new body", recreated.state.value.restorableDraft)
        assertEquals("new title", recreated.state.value.restorableSubject)
    }

    @Test
    fun `EditFirstPost server hydration does not hide a different cached draft`() = runTest {
        draftStore.preload(
            EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE),
            EditorDraftStore.Draft(body = "unfinished body", subject = "unfinished title"),
        )

        val viewModel = newViewModel()
        syncBody()
        testScheduler.advanceUntilIdle()

        assertEquals(topicFormRepository.formResult.initialContent, viewModel.state.value.bodyText)
        assertEquals(topicFormRepository.formResult.subject, viewModel.state.value.subject.text)
        assertEquals("unfinished body", viewModel.state.value.restorableDraft)
        assertEquals("unfinished title", viewModel.state.value.restorableSubject)
    }

    @Test
    fun `EditFirstPost server hydration removes an identical cached draft offer`() = runTest {
        draftStore.preload(
            EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE),
            EditorDraftStore.Draft(
                body = topicFormRepository.formResult.initialContent,
                subject = topicFormRepository.formResult.subject,
            ),
        )

        val viewModel = newViewModel()
        syncBody()
        testScheduler.advanceUntilIdle()

        assertNull(viewModel.state.value.restorableDraft)
        assertNull(viewModel.state.value.restorableSubject)
    }

    @Test
    fun `moving selection in a hydrated first post keeps the restore offer`() = runTest {
        val key = EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE)
        draftStore.preload(
            key,
            EditorDraftStore.Draft(body = "unfinished body", subject = "unfinished title"),
        )
        val viewModel = newViewModel()
        syncBody()
        testScheduler.advanceUntilIdle()
        val hydrated = TextFieldValue(viewModel.bodyFieldState.text.toString(), viewModel.bodyFieldState.selection)

        viewModel.editBody(hydrated.copy(selection = TextRange(4)))
        testScheduler.advanceTimeBy(800L)
        testScheduler.runCurrent()

        assertEquals("unfinished body", viewModel.state.value.restorableDraft)
        assertEquals("unfinished body", draftStore.saved[key]?.body)
    }

    @Test
    fun `typing a new topic subject leaves the pending offer row untouched`() = runTest {
        val key = EditorDraftKey.newTopic(SAMPLE_CAT)
        draftStore.preload(
            key,
            EditorDraftStore.Draft(body = "rescued body", subject = "old title"),
        )
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        syncBody()
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("new title")))
        testScheduler.advanceTimeBy(800L)
        testScheduler.runCurrent()

        assertEquals("no hybrid row may be written while the visible body is blank", 0, draftStore.saveCount)
        assertEquals("rescued body", draftStore.saved[key]?.body)
        assertEquals("old title", draftStore.saved[key]?.subject)
        assertEquals("rescued body", viewModel.state.value.restorableDraft)
        viewModel.submit(TopicFormIntent.DraftRestoreRequested)
        syncBody()
        testScheduler.advanceUntilIdle()
        assertEquals("rescued body", viewModel.state.value.bodyText)
        assertEquals("new title", viewModel.state.value.subject.text)
    }

    @Test
    fun `restoring appends the offered topic body and keeps a live title`() = runTest {
        draftStore.preload(
            EditorDraftKey.newTopic(SAMPLE_CAT),
            EditorDraftStore.Draft(body = "rescued body", subject = "rescued title"),
        )
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        syncBody()
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("fresh title")))
        viewModel.editBody(TextFieldValue("fresh body"))
        viewModel.submit(TopicFormIntent.DraftRestoreRequested)

        syncBody()
        assertEquals("fresh body\n\nrescued body", viewModel.state.value.bodyText)
        assertEquals("fresh title", viewModel.state.value.subject.text)
        assertNull(viewModel.state.value.restorableDraft)
    }

    @Test
    fun `New discard deletes the cached draft and clears the banner`() = runTest {
        val key = EditorDraftKey.newTopic(SAMPLE_CAT)
        val savedStateHandle = SavedStateHandle()
        draftStore.preload(key, EditorDraftStore.Draft(body = "rescued body"))
        val viewModel = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            savedStateHandle = savedStateHandle,
        )
        syncBody()
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.DraftDiscardRequested)
        syncBody()
        testScheduler.advanceUntilIdle()

        assertEquals("", draftStore.saved[key]?.body)
        assertNull(viewModel.state.value.restorableDraft)
        assertTrue(savedStateHandle.get<String>("draft_restore_offer_fingerprint") != null)

        val recreated = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            savedStateHandle = savedStateHandle,
        )
        syncBody()
        testScheduler.advanceUntilIdle()
        assertNull(recreated.state.value.restorableDraft)
    }

    @Test
    fun `a successful new-topic submit deletes the cached draft`() = runTest {
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        syncBody()
        testScheduler.advanceUntilIdle()
        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("My title")))
        viewModel.editBody(TextFieldValue("My body"))

        viewModel.submit(TopicFormIntent.SubmitClicked())
        syncBody()
        testScheduler.advanceUntilIdle()

        assertEquals("submit must have happened", 1, topicFormRepository.newTopicSubmitCalls)
        assertTrue(
            "a created topic must drop its draft",
            draftStore.entries[EditorDraftKey.newTopic(SAMPLE_CAT)]?.attempt?.status ==
                EditorDraftStore.AttemptStatus.PUBLISHED,
        )
        assertEquals("", draftStore.saved[EditorDraftKey.newTopic(SAMPLE_CAT)]?.body)
    }

    @Test
    fun `EditFirstPost autosave and delete-on-submit use the editFirstPost key`() = runTest {
        val viewModel = newViewModel()
        syncBody()
        testScheduler.advanceUntilIdle()

        // The FP form hydrated subject + body ; change the body so a draft is worth saving.
        viewModel.editBody(TextFieldValue("rewritten FP body"))
        testScheduler.advanceTimeBy(800L)
        testScheduler.runCurrent()
        val key = EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE)
        assertEquals("rewritten FP body", draftStore.saved[key]?.body)

        viewModel.submit(TopicFormIntent.SubmitClicked())
        syncBody()
        testScheduler.advanceUntilIdle()
        assertEquals(1, topicFormRepository.submitCalls)
        assertEquals("", draftStore.saved[key]?.body)
        assertEquals(EditorDraftStore.AttemptStatus.PUBLISHED, draftStore.entries[key]?.attempt?.status)
    }

    // ──────────────────────────────────────────────────────────────────────
    // #803 pattern — dirty close (flush before pop), state-hygiene audit 2026-07-05.
    // Same contract as PostEditorViewModelTest's « #604 lot 4a » block.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `CloseRequested flushes the pending debounce before CloseCommitted`() = runTest {
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        syncBody()
        testScheduler.advanceUntilIdle()

        // Type, then close IMMEDIATELY — well inside the 750 ms debounce window. The flush must
        // persist the state at close time, not the snapshot the debounce captured at scheduling.
        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Mon titre")))
        viewModel.editBody(TextFieldValue("dernier mot"))
        viewModel.submit(TopicFormIntent.CloseRequested)

        val effect = viewModel.effects.first()
        assertEquals(TopicFormEffect.CloseCommitted, effect)
        val key = EditorDraftKey.newTopic(SAMPLE_CAT)
        assertEquals(
            "the tail of the draft must reach the row before the pop",
            "dernier mot",
            draftStore.saved[key]?.body,
        )
        assertEquals("Mon titre", draftStore.saved[key]?.subject)
    }

    @Test
    fun `CloseRequested with blank live fields preserves a pending restore offer`() = runTest {
        val key = EditorDraftKey.newTopic(SAMPLE_CAT)
        draftStore.preload(key, EditorDraftStore.Draft(body = "stale", subject = "stale title"))
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        syncBody()
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.CloseRequested)

        val effect = viewModel.effects.first()
        assertEquals(TopicFormEffect.CloseCommitted, effect)
        assertEquals("closing must not rewrite a pending offer", 0, draftStore.saveCount)
        assertFalse(draftStore.deletedKeys.contains(key))
        assertEquals("stale", draftStore.saved[key]?.body)
        assertEquals("stale", viewModel.state.value.restorableDraft)
    }

    @Test
    fun `CloseRequested with blank fields and no pending offer deletes the stale row`() = runTest {
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        syncBody()
        testScheduler.advanceUntilIdle()
        val key = EditorDraftKey.newTopic(SAMPLE_CAT)

        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("temporary title")))
        viewModel.editBody(TextFieldValue("temporary body"))
        testScheduler.advanceTimeBy(800L)
        testScheduler.runCurrent()
        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("")))
        viewModel.editBody(TextFieldValue(""))
        viewModel.submit(TopicFormIntent.CloseRequested)

        assertEquals(TopicFormEffect.CloseCommitted, viewModel.effects.first())
        assertEquals("", draftStore.saved[key]?.body)
    }

    @Test
    fun `CloseRequested with a subject only saves the draft instead of deleting it`() = runTest {
        // The delete branch requires BOTH fields blank : a titled-but-bodyless topic in progress
        // is still worth restoring.
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        syncBody()
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Titre seul")))
        viewModel.submit(TopicFormIntent.CloseRequested)

        val effect = viewModel.effects.first()
        assertEquals(TopicFormEffect.CloseCommitted, effect)
        val key = EditorDraftKey.newTopic(SAMPLE_CAT)
        assertEquals("Titre seul", draftStore.saved[key]?.subject)
        assertFalse(draftStore.deletedKeys.contains(key))
    }

    @Test
    fun `CloseRequested during an in-flight submit is ignored (gate #803)`() = runTest {
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.submitGate = gate
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        syncBody()
        testScheduler.advanceUntilIdle()
        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Mon titre")))
        viewModel.editBody(TextFieldValue("Mon corps"))

        viewModel.submit(TopicFormIntent.SubmitClicked())
        // Back pressed while the POST is in flight — must be inert (gate #803: popping would
        // cancel the submit with the viewModelScope and leave the server state unknown).
        viewModel.submit(TopicFormIntent.CloseRequested)
        gate.complete(Unit)
        syncBody()
        testScheduler.advanceUntilIdle()

        viewModel.effects.test {
            assertTrue(
                "the submit outcome must be the ONLY effect — no CloseCommitted",
                awaitItem() is TopicFormEffect.NewTopicCreated,
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a second CloseRequested is a no-op (gate #803)`() = runTest {
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        syncBody()
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.CloseRequested)
        viewModel.submit(TopicFormIntent.CloseRequested)
        syncBody()
        testScheduler.advanceUntilIdle()

        viewModel.effects.test {
            assertEquals(TopicFormEffect.CloseCommitted, awaitItem())
            // A double back must never yield a second pop (it would remove the screen BELOW).
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // #459 — image upload wiring on the topic composer (pattern copied from PostEditorViewModel;
    // the batch semantics themselves are pinned by PostEditorViewModelTest — here we prove the
    // topic-form copy is actually wired: authenticated pick uploads + inserts, anonymous is inert).
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `cancelled picker preserves the previous error body selection and effects without upload`() = runTest {
        val diagnostics = DiagnosticsLog()
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()
        val viewModel = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            diagnostics = diagnostics,
            uploadRepository = uploads,
            imageUploadReader = reader,
        )
        syncBody()
        testScheduler.advanceUntilIdle()
        viewModel.editBody(TextFieldValue(text = "draft", selection = TextRange(1, 4)))
        syncBody()
        testScheduler.advanceUntilIdle()
        viewModel.submit(
            TopicFormIntent.ImagePickerEventReceived(
                ImagePickerEvent.Unavailable(
                    ImagePickerMode.PHOTO_PICKER,
                    ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
                ),
            ),
        )
        testScheduler.advanceUntilIdle()
        assertEquals(EditorErrors.pickerAbsent, viewModel.state.value.uploadError)
        val stateBefore = viewModel.state.value

        viewModel.effects.test {
            ImagePickerContract.entries.forEach { contract ->
                diagnostics.clear()
                viewModel.submit(
                    TopicFormIntent.ImagePickerEventReceived(ImagePickerEvent.Cancelled(contract, resultCode = 0)),
                )
                syncBody()
                testScheduler.advanceUntilIdle()

                assertEquals(stateBefore, viewModel.state.value)
                assertEquals("draft", viewModel.bodyFieldState.text.toString())
                assertEquals(TextRange(1, 4), viewModel.bodyFieldState.selection)
                assertTrue(reader.readUris.isEmpty())
                assertEquals(0, uploads.uploadCalls)
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
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()
        val viewModel = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            diagnostics = diagnostics,
            uploadRepository = uploads,
            imageUploadReader = reader,
        )
        syncBody()
        testScheduler.advanceUntilIdle()
        viewModel.editBody(TextFieldValue(text = "draft", selection = TextRange(1, 4)))
        syncBody()
        testScheduler.advanceUntilIdle()
        val stateBefore = viewModel.state.value

        viewModel.effects.test {
            viewModel.submit(
                TopicFormIntent.ImagePickerEventReceived(
                    ImagePickerEvent.Result(
                        contract = ImagePickerContract.GET_MULTIPLE_CONTENTS,
                        uris = emptyList(),
                    ),
                ),
            )
            syncBody()
            testScheduler.advanceUntilIdle()

            assertEquals(
                stateBefore.copy(uploadError = EditorErrors.noImageReceived),
                viewModel.state.value,
            )
            assertFalse(viewModel.state.value.isUploading)
            assertTrue(reader.readUris.isEmpty())
            assertEquals(0, uploads.uploadCalls)
            assertEquals(
                listOf(
                    "result contract=GetMultipleContents count=0 sources=[]",
                    "onImagesPicked count=0",
                ),
                diagnostics.entries.value.filter { it.tag == "ImagePicker" }.map { it.message },
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.submit(TopicFormIntent.UploadErrorDismissed)
        testScheduler.advanceUntilIdle()
        assertNull(viewModel.state.value.uploadError)
    }

    @Test
    fun `single picker result keeps the upload path and no empty-result error`() = runTest {
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()
        val viewModel = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            uploadRepository = uploads,
            imageUploadReader = reader,
        )
        syncBody()
        testScheduler.advanceUntilIdle()
        val uri = "content://media/picker/photo/1"

        viewModel.submit(
            TopicFormIntent.ImagePickerEventReceived(
                ImagePickerEvent.Result(
                    contract = ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
                    uris = listOf(uri),
                ),
            ),
        )
        syncBody()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(uri), reader.readUris)
        assertEquals(1, uploads.uploadCalls)
        assertNull(viewModel.state.value.uploadError)
        assertFalse(viewModel.state.value.isUploading)
    }

    @Test
    fun `document picker logs eleven raw uris then uploads the first ten`() = runTest {
        val diagnostics = DiagnosticsLog()
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()
        val viewModel = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            diagnostics = diagnostics,
            uploadRepository = uploads,
            imageUploadReader = reader,
        )
        syncBody()
        testScheduler.advanceUntilIdle()
        val uris = (1..11).map {
            "content://com.android.providers.media.documents/document/image%3A$it"
        }

        viewModel.submit(
            TopicFormIntent.ImagePickerEventReceived(
                ImagePickerEvent.Result(
                    contract = ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS,
                    uris = uris,
                ),
            ),
        )
        syncBody()
        testScheduler.advanceUntilIdle()

        assertEquals(uris.take(10), reader.readUris)
        assertEquals(10, uploads.uploadCalls)
        assertEquals(EditorErrors.imageLimit, viewModel.state.value.uploadError)
        assertEquals(
            listOf(
                "result contract=OpenMultipleDocuments count=11 " +
                    "sources=[content://com.android.providers.media.documents]",
                "onImagesPicked count=11",
            ),
            diagnostics.entries.value.filter { it.tag == "ImagePicker" }.map { it.message },
        )
    }

    @Test
    fun `ED37 URL upload and image wrapping cancel the typing debounce with one checkpoint`() = runTest {
        val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        syncBody()
        val key = EditorDraftKey.newTopic(SAMPLE_CAT)
        val saves = draftStore.saveCount
        val writes = draftStore.writes
        vm.editBody(TextFieldValue("nom", TextRange(3, 0)))
        dispatcher.scheduler.advanceTimeBy(300)
        syncBody()
        assertEquals(saves, draftStore.saveCount)
        assertEquals(writes, draftStore.writes)
        vm.submit(TopicFormIntent.ToolbarActionClicked(BbcodeAction.Image))
        syncBody()
        assertEquals("[img]nom[/img]", draftStore.saved[key]?.body)
        assertEquals(saves + 1, draftStore.saveCount)
        assertEquals(writes + 1, draftStore.writes)
        // The image checkpoint must cancel the typing debounce, including a delayed mirror.
        dispatcher.scheduler.advanceTimeBy(1_000)
        syncBody()
        assertEquals(saves + 1, draftStore.saveCount)
        assertEquals(writes + 1, draftStore.writes)
        val now = dispatcher.scheduler.currentTime
        vm.submit(TopicFormIntent.ImageUrlInserted("https://example.test/image.png"))
        syncBody()
        assertEquals(vm.bodyFieldState.text.toString(), draftStore.saved[key]?.body)
        val beforeUpload = vm.bodyFieldState.text.toString()
        vm.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1")))
        syncBody()
        assertTrue(vm.bodyFieldState.text.toString() != beforeUpload)
        assertEquals(vm.bodyFieldState.text.toString(), draftStore.saved[key]?.body)
        assertEquals(now, dispatcher.scheduler.currentTime)
    }

    @Test
    fun `ED37 image actions and stop cannot checkpoint while sending`() = runTest {
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.submitGate = gate
        val vm = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Title")))
        vm.editBody(TextFieldValue("body", TextRange(4)))
        vm.submit(TopicFormIntent.SubmitClicked())
        syncBody()
        assertTrue(vm.state.value.submission is EditorSubmissionState.Sending)
        val saves = draftStore.saveCount
        val writes = draftStore.writes
        val entry = draftStore.entries[EditorDraftKey.newTopic(SAMPLE_CAT)]

        vm.submit(TopicFormIntent.ToolbarActionClicked(BbcodeAction.Image))
        vm.submit(TopicFormIntent.ImageUrlInserted("https://example.test/image.png"))
        vm.submit(TopicFormIntent.Stopped)
        dispatcher.scheduler.advanceTimeBy(1_000)
        syncBody()

        assertTrue(vm.state.value.submission is EditorSubmissionState.Sending)
        assertEquals("body", vm.bodyFieldState.text.toString())
        assertEquals(saves, draftStore.saveCount)
        assertEquals(writes, draftStore.writes)
        assertEquals(entry, draftStore.entries[EditorDraftKey.newTopic(SAMPLE_CAT)])
        gate.complete(Unit)
    }

    @Test
    fun `picked images upload and insert one img per success, in pick order (#459)`() = runTest {
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()
        val viewModel = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            uploadRepository = uploads,
            imageUploadReader = reader,
        )
        syncBody()
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1", "content://pick/2")))
        syncBody()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("content://pick/1", "content://pick/2"), reader.readUris)
        assertEquals(2, uploads.uploadCalls)
        val state = viewModel.state.value
        assertEquals(2, Regex("\\[img]").findAll(state.bodyText).count())
        assertFalse(state.isUploading)
        assertEquals(null, state.uploadError)
        assertEquals(null, state.uploadProgress)
    }

    @Test
    fun `an anonymous session ignores the pick entirely (#459)`() = runTest {
        val uploads = FakeUploadRepository()
        val viewModel = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            uploadRepository = uploads,
        )
        syncBody()
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1")))
        syncBody()
        testScheduler.advanceUntilIdle()

        assertEquals(0, uploads.uploadCalls)
        assertFalse(viewModel.state.value.isUploading)
    }

    @Test
    fun `a failed upload surfaces a typed banner and stops the batch (#459)`() = runTest {
        val uploads = FakeUploadRepository().apply {
            uploadException = UploadException.TooLarge(maxBytes = 1)
        }
        val viewModel = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            uploadRepository = uploads,
        )
        syncBody()
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1", "content://pick/2")))
        syncBody()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(EditorErrors.stopped(EditorErrors.tooLarge, UploadProgress(0, 2)), state.uploadError)
        assertFalse(state.isUploading)
        assertEquals(1, uploads.uploadCalls)
        assertEquals("", state.bodyText)

        viewModel.submit(TopicFormIntent.UploadErrorDismissed)
        assertEquals(null, viewModel.state.value.uploadError)
    }

    @Test
    fun `SubmitClicked is inert while an image upload is in flight (#953 F5)`() = runTest {
        // Mirror of the PostEditor guard : a tap on « Envoyer » must not race the in-flight
        // upload and POST before the [img] markup is inserted.
        val gate = CompletableDeferred<Unit>()
        val uploads = FakeUploadRepository().apply { uploadGate = gate }
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT, uploadRepository = uploads)
        syncBody()
        testScheduler.advanceUntilIdle()
        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Mon titre")))
        viewModel.editBody(TextFieldValue("Mon corps"))
        viewModel.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1")))
        assertTrue("sanity: the upload must be in flight", viewModel.state.value.isUploading)

        viewModel.submit(TopicFormIntent.SubmitClicked())
        gate.complete(Unit)
        syncBody()
        testScheduler.advanceUntilIdle()

        assertEquals("no POST may slip through mid-upload", 0, topicFormRepository.newTopicSubmitCalls)
    }

    /** #1301-ST — les deux modes partagent le repository et le stockage déjà exercés ici. */
    internal fun submissionAdapter(
        creating: Boolean,
        scope: TestScope,
        preferences: Flow<Boolean>,
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
        val uploadCompletion = CompletableDeferred<Unit>()
        val uploads = FakeUploadRepository().apply { uploadGate = uploadCompletion }
        topicFormRepository.submitException = scenario.postFailure
        topicFormRepository.submitGate = postGate
        topicFormRepository.submitScenario = scenario
        scenario.postRejection?.let {
            topicFormRepository.submitResult = ReplySubmitResult.Failure(it)
            topicFormRepository.newTopicSubmitResult = NewTopicSubmitResult.Failure(it)
        }
        val key = if (creating) {
            EditorDraftKey.newTopic(SAMPLE_CAT)
        } else {
            EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE)
        }
        draftStore.preload(key, EditorDraftStore.Draft(body = "stored draft", subject = "stored title"))
        val userPreferences = object : UserPreferencesRepository by FakeUserPreferencesRepository() {
            override fun observeConfirmBeforePosting(): Flow<Boolean> = preferences
        }
        fun createViewModel() = if (creating) {
            newTopicViewModel(
                entrySubcat = SAMPLE_SUBCAT,
                userPreferencesRepository = userPreferences,
                uploadRepository = uploads,
                authRepository = authRepository,
                advanceMirror = false,
            )
        } else {
            newViewModel(
                userPreferencesRepository = userPreferences,
                uploadRepository = uploads, authRepository = authRepository,
                advanceMirror = false,
            )
        }
        var viewModel = createViewModel()
        val effects = mutableListOf<TopicFormEffect>()
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
                SubmissionBodyMutation.SHARED_RESTORE to "aucune entrée resumeSharedDraft pour un sujet",
            )
            override val uploadUnsupportedReason: String? = null
            override val postCalls get() = topicFormRepository.submitCalls + topicFormRepository.newTopicSubmitCalls
            override val formFetchCalls get() =
                topicFormRepository.formFetches + topicFormRepository.newTopicFormFetches
            override val uploadCalls get() = uploads.uploadCalls
            override val draftSaves get() = draftStore.saveCount
            override val draftWrites get() = draftStore.writes
            override val successEffects get() = effects.count {
                it is TopicFormEffect.SubmitSucceeded || it is TopicFormEffect.NewTopicCreated
            }
            override val closeEffects get() = effects.count { it is TopicFormEffect.CloseCommitted }

            override fun editBody(body: String) {
                viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Contract title")))
                viewModel.editBody(TextFieldValue(body, TextRange(body.length)), advanceMirror = false)
            }

            override fun clickSubmit() = viewModel.submit(TopicFormIntent.SubmitClicked())
            override fun confirm() = viewModel.submit(TopicFormIntent.SubmitConfirmed(viewModel.state.value.armToken))
            override fun confirmIdentity(id: Long?) = viewModel.submit(TopicFormIntent.SubmitConfirmed(arms[id]))
            override fun dismissConfirmation() = viewModel.submit(TopicFormIntent.SubmitConfirmationDismissed)
            override fun close() = viewModel.submit(TopicFormIntent.CloseRequested)
            override fun completePost() { postGate.complete(Unit) }
            override fun completeUpload() { uploadCompletion.complete(Unit) }
            // Reflection exercises the actual final callback guard even when upload launch is refused.
            override fun deliverLateUploadCallback() {
                val insert = TopicFormViewModel::class.java.getDeclaredMethod(
                    "insertImageBbcodeAtCaret", String::class.java, Boolean::class.javaPrimitiveType,
                )
                insert.isAccessible = true
                insert.invoke(viewModel, "[img]https://example.test/late.png[/img]", false)
            }

            override fun dispose() = viewModel.viewModelScope.cancel()
            override fun startUpload() = viewModel.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1")))

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
                        EditorErrors.invalidForm -> SubmissionErrorProbe.PREPARATION_FAILED
                        else -> null
                    },
                    restoreOffered = it.restorableDraft != null,
                    offeredBody = it.restorableDraft,
                    blocksClose = it.submission.blocksBodyMutation,
                )
            }

            override fun mutateBody(mutation: SubmissionBodyMutation) {
                when (mutation) {
                    SubmissionBodyMutation.TYPING -> editBody("changed during POST")
                    SubmissionBodyMutation.SMILEY -> viewModel.submit(TopicFormIntent.SmileySelected(":jap:"))
                    SubmissionBodyMutation.FORMAT ->
                        viewModel.submit(TopicFormIntent.ToolbarActionClicked(BbcodeAction.Bold))
                    SubmissionBodyMutation.IMAGE_URL ->
                        viewModel.submit(TopicFormIntent.ImageUrlInserted("https://example.test/image.png"))
                    SubmissionBodyMutation.RESTORE -> {
                        checkNotNull(viewModel.state.value.restorableDraft) { "l'offre #1415 doit être présente" }
                        viewModel.submit(TopicFormIntent.DraftRestoreRequested)
                    }
                    SubmissionBodyMutation.SHARED_RESTORE -> error("cas explicitement désactivé")
                }
            }
        }
    }

    @Suppress("LongParameterList") // test factory mirroring the ViewModel's injected dependencies.
    private fun newTopicViewModel(
        entrySubcat: Int?,
        cat: Int = SAMPLE_CAT,
        diagnostics: DiagnosticsLog = DiagnosticsLog(),
        userPreferencesRepository: UserPreferencesRepository = FakeUserPreferencesRepository(),
        authRepository: AuthRepository = FakeAuthRepository(),
        uploadRepository: UploadRepository = FakeUploadRepository(),
        imageUploadReader: ImageUploadReader = FakeImageUploadReader(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        advanceMirror: Boolean = true,
        previewParser: BbcodePreviewParser = this.previewParser,
        defaultDispatcher: CoroutineDispatcher = dispatcher,
    ): TopicFormViewModel = TopicFormViewModel(
        request = TopicFormRequest(
            mode = TopicFormMode.New,
            cat = cat,
            subcat = entrySubcat,
            topicId = null,
            page = null,
            numreponse = null,
        ),
        previewParser = previewParser,
        defaultDispatcher = defaultDispatcher,
        clock = submissionClock,
        topicFormRepository = topicFormRepository,
        smileyRepository = smileyRepository,
        hfrFavoriteSmileyRepository = favoriteRepository,
        smileyLocalCollections = localCollections,
        userPreferencesRepository = userPreferencesRepository,
        draftStore = draftStore,
        diagnostics = diagnostics,
        authRepository = authRepository,
        uploadRepository = uploadRepository,
        imageUploadReader = imageUploadReader,
        savedStateHandle = savedStateHandle,
    ).also { registerViewModel(it, advanceMirror) }

    private fun registerViewModel(vm: TopicFormViewModel, advanceMirror: Boolean) {
        viewModels += vm
        if (advanceMirror) syncBody()
    }

    private fun syncBody() {
        Snapshot.sendApplyNotifications()
        dispatcher.scheduler.runCurrent()
    }

    /** Synthetic user input; TopicFormInputTest covers real BTF2 typing and paste. */
    private fun TopicFormViewModel.editBody(value: TextFieldValue, advanceMirror: Boolean = true) {
        bodyFieldState.edit {
            if (asCharSequence().toString() != value.text) replace(0, length, value.text)
            selection = value.selection
            with(bodyInputTransformation) { transformInput() }
        }
        Snapshot.sendApplyNotifications()
        if (advanceMirror) dispatcher.scheduler.runCurrent()
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<TopicFormState>.awaitHydratedState(): TopicFormState {
        // Under `UnconfinedTestDispatcher`, the init coroutine fetches the form
        // before the subscription is established, so the first emission is
        // already the hydrated state. We assert this rather than skipping.
        val hydrated = awaitItem()
        check(hydrated.subjectHydratedFromServer && hydrated.draftHydratedFromServer) {
            "expected first emission to be hydrated, was $hydrated"
        }
        return hydrated
    }

    @Suppress("LongParameterList") // Test factory mirroring the ViewModel's injected dependencies.
    private fun newViewModel(
        authRepository: AuthRepository = FakeAuthRepository(),
        diagnostics: DiagnosticsLog = DiagnosticsLog(),
        userPreferencesRepository: UserPreferencesRepository = FakeUserPreferencesRepository(),
        uploadRepository: UploadRepository = FakeUploadRepository(),
        advanceMirror: Boolean = true,
        previewParser: BbcodePreviewParser = this.previewParser,
        defaultDispatcher: CoroutineDispatcher = dispatcher,
    ): TopicFormViewModel = TopicFormViewModel(
        request = TopicFormRequest(
            mode = TopicFormMode.EditFirstPost,
            cat = SAMPLE_CAT,
            subcat = SAMPLE_SUBCAT,
            topicId = SAMPLE_TOPIC_ID,
            page = 1,
            numreponse = SAMPLE_NUMREPONSE,
        ),
        previewParser = previewParser,
        defaultDispatcher = defaultDispatcher,
        clock = submissionClock,
        topicFormRepository = topicFormRepository,
        smileyRepository = smileyRepository,
        hfrFavoriteSmileyRepository = favoriteRepository,
        smileyLocalCollections = localCollections,
        userPreferencesRepository = userPreferencesRepository,
        draftStore = draftStore,
        diagnostics = diagnostics,
        authRepository = authRepository,
        uploadRepository = uploadRepository,
        imageUploadReader = FakeImageUploadReader(),
    ).also { registerViewModel(it, advanceMirror) }

    /** #459 — canned [ImageUploadReader] (1-byte PNG), records the picked uris in call order. */
    private class FakeImageUploadReader : ImageUploadReader {
        val readUris: MutableList<String> = mutableListOf()

        override suspend fun read(uri: String): ImageUpload {
            readUris += uri
            return ImageUpload(bytes = byteArrayOf(0), mimeType = "image/png", displayName = null)
        }
    }

    /** #459 — fake [UploadRepository] ; only [uploadWithCurrentProvider] matters here. */
    private class FakeUploadRepository : UploadRepository {
        var uploadException: Throwable? = null
        var failOnCall: Int = 1
        /** When set, holds the upload in flight until the test releases it (mirrors PostEditor's fake). */
        var uploadGate: CompletableDeferred<Unit>? = null
        var uploadCalls: Int = 0
            private set

        override suspend fun uploadWithCurrentProvider(image: ImageUpload, userId: String): UploadedImage {
            uploadCalls += 1
            uploadGate?.await()
            uploadException?.takeIf { uploadCalls >= failOnCall }?.let { throw it }
            return UploadedImage(
                provider = UploadProviderId.DIBERIE,
                imageUrl = "https://rehost.diberie.com/Picture/Get/f/1",
                thumbnailUrl = null,
                resizedUrl = null,
                deleteHandle = null,
                expiresAt = null,
            )
        }

        override fun observeUploads(userId: String): kotlinx.coroutines.flow.Flow<List<UploadedImageRecord>> =
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())

        override suspend fun delete(record: UploadedImageRecord, userId: String): Boolean = false
    }

    /** #459 — fixed [AuthState] so the VM resolves (or not) an upload `userId`. */
    private class FakeAuthRepository(
        private val authState: AuthState = AuthState.Authenticated("alice"),
    ) : AuthRepository {
        override fun observeAuthState(): kotlinx.coroutines.flow.Flow<AuthState> =
            kotlinx.coroutines.flow.MutableStateFlow(authState)

        override suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated> =
            Result.success(AuthState.Authenticated(pseudo))

        override suspend fun logout() = Unit
    }

    private class FakePreviewParser : BbcodePreviewParser {
        override fun parsePreview(bbcode: String): PostContent = PostContent(
            blocks = listOf(PostBlock.Paragraph(listOf(PostInline.Text(bbcode)))),
        )
    }

    private class FakeTopicFormRepository : TopicFormRepository {
        var formResult: TopicForm = TopicForm(
            hashCheck = "FAKE_HASH",
            subject = "Sample first post title",
            initialContent = "Body BBCode goes here.",
            userId = SAMPLE_USER_ID,
            selectedSubcat = SAMPLE_SUBCAT,
            subcategoryChoices = listOf(
                TopicFormSubcategoryChoice(id = SAMPLE_SUBCAT, label = "Divers", selected = true),
                TopicFormSubcategoryChoice(id = SAMPLE_OTHER_SUBCAT, label = "Autre", selected = false),
            ),
            hiddenFields = mapOf("cat" to "23", "post" to "35395"),
            options = ReplyFormOptions(
                signatureEnabled = true,
                smileyDisabled = false,
                emailNotificationEnabled = false,
            ),
            msgIcon = "1",
            poll = TopicPollForm(present = false, fields = emptyMap(), editableInThisVersion = false),
            isAnonymous = false,
        )

        // Mirror used by `fetchNewTopicForm` : the create-topic form has no
        // pre-selected subcat (HFR omits `selected`), so `selectedSubcat = null`.
        var newTopicFormResult: TopicForm = TopicForm(
            hashCheck = "FAKE_HASH",
            subject = "",
            initialContent = "",
            userId = SAMPLE_USER_ID,
            selectedSubcat = null,
            subcategoryChoices = listOf(
                TopicFormSubcategoryChoice(id = null, label = "Aucune", selected = false),
                TopicFormSubcategoryChoice(id = SAMPLE_SUBCAT, label = "Divers", selected = false),
                TopicFormSubcategoryChoice(id = SAMPLE_OTHER_SUBCAT, label = "Autre", selected = false),
            ),
            hiddenFields = mapOf("cat" to "23", "from_subcat" to "$SAMPLE_SUBCAT"),
            options = ReplyFormOptions(
                signatureEnabled = false,
                smileyDisabled = false,
                emailNotificationEnabled = false,
            ),
            msgIcon = "1",
            poll = TopicPollForm(present = false, fields = emptyMap(), editableInThisVersion = false),
            isAnonymous = false,
        )

        var submitResult: ReplySubmitResult? = ReplySubmitResult.Success(
            targetPage = 1,
            refreshUrl = "/hfr/",
        )

        var newTopicSubmitResult: NewTopicSubmitResult? = NewTopicSubmitResult.Success(
            newTopicId = null,
            newNumreponse = null,
            targetCat = SAMPLE_CAT,
            targetSubcat = SAMPLE_SUBCAT,
            refreshUrl = null,
        )

        var formGate: CompletableDeferred<Unit>? = null
        var formFailure: Throwable? = null
        // #312 — holds an in-flight submit (both Edit FP and New) until the test releases it,
        // mirroring `FakeReplyRepository.submitGate` in `PostEditorViewModelTest`.
        var submitGate: CompletableDeferred<Unit>? = null
        var submitScenario: EditorSubmissionScenario? = null
        var submitException: java.io.IOException? = null

        var formFetches: Int = 0
            private set
        var newTopicFormFetches: Int = 0
            private set
        var submitCalls: Int = 0
            private set
        var newTopicSubmitCalls: Int = 0
            private set
        var lastFetchedContext: EditFirstPostContext? = null
            private set
        var lastSubmittedContext: EditFirstPostContext? = null
            private set
        var lastNewTopicContext: NewTopicContext? = null
            private set
        var lastSubmittedSubject: String? = null
            private set
        var lastSubmittedBbcode: String? = null
            private set
        var lastSubmittedSubcat: Int? = null
            private set
        var lastSubmittedOptions: ReplyFormOptions? = null
            private set
        var lastSubmittedForm: TopicForm? = null
            private set

        override suspend fun fetchEditFirstPostForm(context: EditFirstPostContext): TopicForm {
            formFetches += 1
            lastFetchedContext = context
            formGate?.await()
            formFailure?.let { throw it }
            return formResult
        }

        override suspend fun submitEditFirstPost(
            context: EditFirstPostContext,
            form: TopicForm,
            subject: String,
            bbcodeContent: String,
            selectedSubcat: Int,
            options: ReplyFormOptions,
        ): ReplySubmitResult {
            submitScenario?.prepare()
            submitCalls += 1
            lastSubmittedContext = context
            lastSubmittedSubject = subject
            lastSubmittedBbcode = bbcodeContent
            lastSubmittedSubcat = selectedSubcat
            lastSubmittedOptions = options
            lastSubmittedForm = form
            submitGate?.let { gate -> submitScenario?.awaitPost(gate) ?: gate.await() }
            submitException?.let { throw it }
            return submitResult ?: error("submitResult not set")
        }

        override suspend fun fetchNewTopicForm(context: NewTopicContext): TopicForm {
            newTopicFormFetches += 1
            lastNewTopicContext = context
            formGate?.await()
            formFailure?.let { throw it }
            return newTopicFormResult
        }

        override suspend fun submitNewTopic(
            context: NewTopicContext,
            form: TopicForm,
            subject: String,
            bbcodeContent: String,
            selectedSubcat: Int,
            options: ReplyFormOptions,
        ): NewTopicSubmitResult {
            submitScenario?.prepare()
            newTopicSubmitCalls += 1
            lastNewTopicContext = context
            lastSubmittedSubject = subject
            lastSubmittedBbcode = bbcodeContent
            lastSubmittedSubcat = selectedSubcat
            lastSubmittedOptions = options
            lastSubmittedForm = form
            submitGate?.let { gate -> submitScenario?.awaitPost(gate) ?: gate.await() }
            submitException?.let { throw it }
            return newTopicSubmitResult ?: error("newTopicSubmitResult not set")
        }
    }

    /**
     * Phase 2F-C (#11 partial) — fake smiley repository. Same shape as the one in
     * `PostEditorViewModelTest` ; holds a single pending deferred so tests can drive
     * loading / success / error transitions of the wiki search without touching the network.
     */
    private class FakeSmileyRepository : SmileyRepository {
        override fun cachedWikiKeywords(token: String): SmileyWikiKeywordsResult.Loaded? = null

        override suspend fun getWikiKeywords(token: String) = SmileyWikiKeywordsResult.Failed

        private var pending: CompletableDeferred<List<EditorSmiley>>? = null
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
            val deferred = CompletableDeferred<List<EditorSmiley>>()
            pending = deferred
            return try {
                deferred.await()
            } catch (error: kotlinx.coroutines.CancellationException) {
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
     * topic form; every other member is stubbed at its default (same shape as the
     * `FakeUserPreferencesRepository` in `PostEditorViewModelTest` / `TopicViewModelTest`).
     */
    private class FakeUserPreferencesRepository(
        confirmBeforePosting: Boolean = false,
        editorImageInsert: EditorImageInsert = EditorImageInsert.FULL,
    ) : UserPreferencesRepository {
        override fun observeEditorPreviewPositions(): Flow<Map<EditorPreviewKey, Float>> =
            MutableStateFlow(emptyMap())

        override suspend fun setEditorPreviewPosition(key: EditorPreviewKey, sourceDp: Float) = Unit

        private val confirmBeforePosting = MutableStateFlow(confirmBeforePosting)
        private val editorImageInsert = MutableStateFlow(editorImageInsert)

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

        override fun observeQuoteCardsEnabled(): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setQuoteCardsEnabled(enabled: Boolean) = Unit

        // #806 — the writing-surface preset routes taps in :feature:topic, not here; default stub.
        override fun observeWritingSurfacePreset(): Flow<WritingSurfacePreset> =
            MutableStateFlow(WritingSurfacePreset.FULL_EDITOR)

        override suspend fun setWritingSurfacePreset(preset: WritingSurfacePreset) = Unit

        // #1128 — keep interface fakes aligned with the shared image-selector preference.
        private val imagePickerMode = MutableStateFlow(ImagePickerMode.DEFAULT)

        override fun observeImagePickerMode(): Flow<ImagePickerMode> = imagePickerMode

        private val editorShortcut = MutableStateFlow(EditorShortcut.NONE)
        override fun observeEditorShortcut(): Flow<EditorShortcut> = editorShortcut
        override suspend fun setEditorShortcut(shortcut: EditorShortcut) { editorShortcut.value = shortcut }

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

        override fun observeEditorImageInsert(): Flow<EditorImageInsert> = editorImageInsert

        override suspend fun setEditorImageInsert(mode: EditorImageInsert) {
            editorImageInsert.value = mode
        }

        // #287 — reading display presets are irrelevant to the topic form; stubbed at defaults.
        override fun observeDisplayDensity(): Flow<DisplayDensity> = MutableStateFlow(DisplayDensity.COMFORT)

        override suspend fun setDisplayDensity(density: DisplayDensity) = Unit

        override fun observeFontScale(): Flow<FontScalePreference> = MutableStateFlow(FontScalePreference.M)

        override suspend fun setFontScale(scale: FontScalePreference) = Unit

        override fun observeAppLauncherIcon(): Flow<AppLauncherIcon> =
            MutableStateFlow(AppLauncherIcon.CLASSIC)

        override suspend fun setAppLauncherIcon(icon: AppLauncherIcon) = Unit

        // #973 — the block-GIF display profile is irrelevant to the topic form; stubbed at the M default.
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

    /** #405 — in-memory fake [EditorDraftStore], same shape as the one in `PostEditorViewModelTest`. */
    private class FakeEditorDraftStore : VersionedEditorDraftStore()

    private companion object {
        const val SAMPLE_CAT = 23
        const val IA_CAT = 32
        const val SAMPLE_TOPIC_ID = 35_395
        const val SAMPLE_SUBCAT = 550
        const val SAMPLE_OTHER_SUBCAT = 388
        const val SAMPLE_NUMREPONSE = 100
        const val SAMPLE_USER_ID = 1_234_567
    }
}
