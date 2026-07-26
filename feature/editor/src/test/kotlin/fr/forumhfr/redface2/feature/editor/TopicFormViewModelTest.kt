package fr.forumhfr.redface2.feature.editor
import fr.forumhfr.redface2.core.ui.editor.UploadError
import fr.forumhfr.redface2.core.ui.editor.UploadProgress

import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
import fr.forumhfr.redface2.core.domain.preferences.CategoryBandStyle
import fr.forumhfr.redface2.core.domain.preferences.FlagGlyphStyle
import fr.forumhfr.redface2.core.domain.preferences.AvatarAppearance
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.AccentColor
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.domain.preferences.PlusLusIndicatorStyle
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.upload.UploadedImage
import fr.forumhfr.redface2.core.domain.upload.UploadedImageRecord
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.WritingSurfacePreset
import fr.forumhfr.redface2.core.domain.write.TopicFormRepository
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.write.EditFirstPostContext
import fr.forumhfr.redface2.core.model.write.NewTopicContext
import fr.forumhfr.redface2.core.model.write.NewTopicSubmitResult
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.model.write.TopicForm
import fr.forumhfr.redface2.core.model.write.TopicFormSubcategoryChoice
import fr.forumhfr.redface2.core.model.write.TopicPollForm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
@Suppress("LargeClass") // One class per ViewModel keeps every code path co-located with the
// shared `Fake*Repository` test doubles — splitting per phase (#148 edit FP / #149 create /
// #213 cat-0-subcat / #11 smiley) would shred the fakes into duplicated copies. Mirrors the
// same suppression already carried by `PostEditorViewModelTest`.
class TopicFormViewModelTest {

    private val previewParser = FakePreviewParser()
    private val topicFormRepository = FakeTopicFormRepository()
    private val smileyRepository = FakeSmileyRepository()
    private val draftStore = FakeEditorDraftStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init hydrates subject draft and per-post options from the parsed form`() = runTest {
        val viewModel = newViewModel()
        viewModel.state.test {
            val hydrated = awaitHydratedState()
            assertEquals("Sample first post title", hydrated.subject.text)
            assertEquals("Body BBCode goes here.", hydrated.draft.text)
            assertEquals(SAMPLE_SUBCAT, hydrated.selectedSubcat)
            assertEquals(2, hydrated.subcategoryChoices.size)
            assertTrue(hydrated.signatureEnabled)
            assertFalse(hydrated.smileyDisabled)
            assertFalse(hydrated.emailNotificationEnabled)
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
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("user override", TextRange(13))))
            // Submit triggers InvalidHashCheck → silent refetch with the same fake form.
            viewModel.submit(TopicFormIntent.SubmitClicked)
            val finalState = expectMostRecentItem()
            assertEquals("User-overridden subject", finalState.subject.text)
            assertEquals("user override", finalState.draft.text)
            // The user-facing banner stays armed on InvalidHashCheck even though
            // the refetch is silent — without this the user has no way to know
            // their first submit was rejected.
            val submitError = finalState.submitError
            assertTrue(
                "expected submitError to be SubmitError.Hfr(InvalidHashCheck), was $submitError",
                submitError is SubmitError.Hfr &&
                    submitError.reason == ReplyFailureReason.InvalidHashCheck,
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
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Edited body", TextRange(11))))
            viewModel.submit(TopicFormIntent.SubcatSelected(SAMPLE_OTHER_SUBCAT))
            viewModel.submit(TopicFormIntent.ToggleSignature(enabled = false))
            viewModel.submit(TopicFormIntent.ToggleSmileyDisabled(disabled = true))
            viewModel.submit(TopicFormIntent.ToggleEmailNotification(enabled = true))
            viewModel.submit(TopicFormIntent.SubmitClicked)
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
            viewModel.submit(TopicFormIntent.SubmitClicked)
            val effect = awaitItem() as TopicFormEffect.SubmitSucceeded
            assertEquals(1, effect.targetPage)
            assertEquals(SAMPLE_NUMREPONSE, effect.scrollTo)
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
            val hydrated = expectMostRecentItem()
            assertEquals("My pre-fetch subject", hydrated.subject.text)
            assertEquals("Body BBCode goes here.", hydrated.draft.text)
            // Subject is not flagged as server-hydrated (user edited it first),
            // but draft is. The two flags are independent now.
            assertFalse(hydrated.subjectHydratedFromServer)
            assertTrue(hydrated.draftHydratedFromServer)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `slow fetch preserves user-edited draft but still hydrates subject`() = runTest {
        val gate = CompletableDeferred<Unit>()
        topicFormRepository.formGate = gate
        val viewModel = newViewModel()
        viewModel.state.test {
            val blank = awaitItem()
            assertFalse(blank.subjectHydratedFromServer)
            assertFalse(blank.draftHydratedFromServer)
            // User types into the content while the fetch is still in flight.
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("pre-fetch draft", TextRange(15))))
            skipItems(1)
            gate.complete(Unit)
            val hydrated = expectMostRecentItem()
            assertEquals("Sample first post title", hydrated.subject.text)
            assertEquals("pre-fetch draft", hydrated.draft.text)
            assertTrue(hydrated.subjectHydratedFromServer)
            assertFalse(hydrated.draftHydratedFromServer)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refetch after InvalidHashCheck never clobbers either subject or draft`() = runTest {
        topicFormRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)
        val viewModel = newViewModel()
        viewModel.state.test {
            awaitHydratedState()
            // User overwrites both fields after the initial hydration.
            viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("user subject after hydrate")))
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("user draft after hydrate", TextRange(24))))
            // Submit → InvalidHashCheck → silent refetch.
            viewModel.submit(TopicFormIntent.SubmitClicked)
            val finalState = expectMostRecentItem()
            assertEquals("user subject after hydrate", finalState.subject.text)
            assertEquals("user draft after hydrate", finalState.draft.text)
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
            assertEquals("", final.draft.text)
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
        viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Body", TextRange(4))))
        viewModel.submit(TopicFormIntent.SubmitClicked)
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
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Corps IA", TextRange(8))))
            // canSubmit must be true WITHOUT any SubcatSelected.
            val ready = expectMostRecentItem()
            assertTrue("cat-without-subcat must enable submit with subcat=0", ready.canSubmit)
            viewModel.submit(TopicFormIntent.SubmitClicked)
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
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Body", TextRange(4))))
            val blocked = expectMostRecentItem()
            assertFalse("missing subcat selection must keep submit disabled", blocked.canSubmit)
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.submit(TopicFormIntent.SubmitClicked)
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
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Body 2E", TextRange(7))))
            // User overrides the entry chip to a different sub-category.
            viewModel.submit(TopicFormIntent.SubcatSelected(SAMPLE_OTHER_SUBCAT))
            viewModel.submit(TopicFormIntent.ToggleSignature(enabled = true))
            viewModel.submit(TopicFormIntent.SubmitClicked)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, topicFormRepository.newTopicSubmitCalls)
        assertEquals("Topic 2E", topicFormRepository.lastSubmittedSubject)
        assertEquals("Body 2E", topicFormRepository.lastSubmittedBbcode)
        assertEquals(SAMPLE_OTHER_SUBCAT, topicFormRepository.lastSubmittedSubcat)
        val options = requireNotNull(topicFormRepository.lastSubmittedOptions)
        assertTrue(options.signatureEnabled)
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
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Body", TextRange(4))))
            viewModel.submit(TopicFormIntent.SubcatSelected(SAMPLE_OTHER_SUBCAT))
            viewModel.submit(TopicFormIntent.SubmitClicked)
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
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Body", TextRange(4))))
            viewModel.submit(TopicFormIntent.SubcatSelected(SAMPLE_OTHER_SUBCAT))
            viewModel.submit(TopicFormIntent.SubmitClicked)
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
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("user draft after hydrate", TextRange(24))))
            viewModel.submit(TopicFormIntent.SubmitClicked)
            val finalState = expectMostRecentItem()
            assertEquals("user subject after hydrate", finalState.subject.text)
            assertEquals("user draft after hydrate", finalState.draft.text)
            val submitError = finalState.submitError
            assertTrue(
                "expected submitError to be Hfr(InvalidHashCheck), was $submitError",
                submitError is SubmitError.Hfr &&
                    submitError.reason == ReplyFailureReason.InvalidHashCheck,
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
                "expected SubmitError.Hfr(LoginRequired), was $error",
                error is SubmitError.Hfr && error.reason == ReplyFailureReason.LoginRequired,
            )
            cancelAndIgnoreRemainingEvents()
        }
        // User still tries to submit — the VM short-circuits without calling the repo.
        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Topic")))
        viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Body", TextRange(4))))
        viewModel.submit(TopicFormIntent.SubcatSelected(SAMPLE_SUBCAT))
        viewModel.submit(TopicFormIntent.SubmitClicked)
        assertEquals(0, topicFormRepository.newTopicSubmitCalls)
    }

    // ----- Phase 2F-C (#11) / #441 : smiley picker ----------------------------------
    // The picker machinery (open/dismiss, ≤ 2-chars gate, debounce, stale-result guards,
    // #824 restore-on-reopen) lives in the shared SmileyPickerController and is covered by
    // SmileyPickerControllerTest — no duplicate coverage here. These tests only exercise
    // what stays a ViewModel concern : the insertion intent, the userId plumbed from the
    // parsed form, and the diagnostics policy on search failure.

    @Test
    fun `the wiki search carries the hydrated userId (#441)`() = runTest {
        val viewModel = newViewModel()
        // Wait for the form to be hydrated so userId lands in state.
        viewModel.state.test {
            awaitHydratedState()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.smileyPicker.open()
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
        viewModel.smileyPicker.open()
        viewModel.smileyPicker.onQueryChanged("jap")
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        assertEquals(0, smileyRepository.lastUserId)
    }

    @Test
    fun `failed wiki search does not leak user id or query through diagnostics`() = runTest {
        val diagnostics = DiagnosticsLog()
        val viewModel = newViewModel(diagnostics = diagnostics)
        viewModel.smileyPicker.open()
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
        viewModel.submit(
            TopicFormIntent.ContentChanged(TextFieldValue("hello", TextRange(5))),
        )
        viewModel.submit(TopicFormIntent.TogglePreview)
        viewModel.smileyPicker.open()
        viewModel.submit(TopicFormIntent.SmileySelected(":jap:"))
        viewModel.state.test {
            val state = expectMostRecentItem()
            // Surrounding-spaces convention from `insertBbcodeToken` is honoured.
            assertEquals("hello :jap: ", state.draft.text)
            assertEquals(12, state.draft.selection.start)
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
        viewModel.smileyPicker.open()
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

        viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("image: ", TextRange(7))))
        viewModel.submit(TopicFormIntent.TogglePreview)
        viewModel.submit(TopicFormIntent.ImageUrlInserted("https://example.com/pic.gif"))

        viewModel.state.test {
            val state = expectMostRecentItem()
            val expected = "image: [img]https://example.com/pic.gif[/img]"
            assertEquals(expected, state.draft.text)
            assertEquals(expected.length, state.draft.selection.start)
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

        viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("image: ", TextRange(7))))
        viewModel.submit(TopicFormIntent.ImageUrlInserted("javascript:alert(1)"))

        assertEquals("image: ", viewModel.state.value.draft.text)
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

        viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("image: ", TextRange(7))))
        viewModel.submit(TopicFormIntent.ImageUrlInserted("https://example.com/pic.gif"))

        val expected =
            "image: [url=https://example.com/pic.gif][img]https://example.com/pic.gif[/img][/url]"
        assertEquals(expected, viewModel.state.value.draft.text)
    }

    // ----- #312 : confirmation avant publication ------------------------------

    @Test
    fun `confirm-before-posting OFF keeps the one-tap EditFirstPost submit unchanged`() = runTest {
        topicFormRepository.submitResult = ReplySubmitResult.Success(targetPage = 1, refreshUrl = "/hfr/")
        val viewModel = newViewModel() // default fake → preference OFF
        viewModel.state.test {
            awaitHydratedState()
            viewModel.submit(TopicFormIntent.SubmitClicked)
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
            viewModel.submit(TopicFormIntent.SubmitClicked)
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
        viewModel.submit(TopicFormIntent.SubmitClicked)
        assertEquals(0, topicFormRepository.submitCalls)

        viewModel.effects.test {
            viewModel.submit(TopicFormIntent.SubmitConfirmed)
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
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Body", TextRange(4))))
            viewModel.submit(TopicFormIntent.SubmitClicked)
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
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Body", TextRange(4))))
            viewModel.submit(TopicFormIntent.SubmitClicked)
            val parked = expectMostRecentItem()
            assertTrue("the confirmation dialog must be armed", parked.showSubmitConfirmation)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("no POST before the user confirms", 0, topicFormRepository.newTopicSubmitCalls)

        viewModel.submit(TopicFormIntent.SubmitConfirmed)

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
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Body", TextRange(4))))
            viewModel.submit(TopicFormIntent.SubmitClicked)
            viewModel.submit(TopicFormIntent.SubmitConfirmationDismissed)
            val finalState = expectMostRecentItem()
            assertFalse(finalState.showSubmitConfirmation)
            assertEquals("the subject survives the dismissal", "Topic", finalState.subject.text)
            assertEquals("the draft survives the dismissal", "Body", finalState.draft.text)
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
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Body", TextRange(4))))
            viewModel.submit(TopicFormIntent.SubmitClicked)
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
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Body", TextRange(4))))
            viewModel.submit(TopicFormIntent.SubmitClicked)
            val parked = expectMostRecentItem()
            assertTrue("the confirmation dialog must be armed", parked.showSubmitConfirmation)
            cancelAndIgnoreRemainingEvents()
        }

        // Second tap while the dialog is up (double-tap race) : idempotent — re-raising
        // `showSubmitConfirmation = true` is a no-op, and no POST may slip through.
        viewModel.submit(TopicFormIntent.SubmitClicked)

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
            viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Body", TextRange(4))))
            viewModel.submit(TopicFormIntent.SubmitClicked)
            val parked = expectMostRecentItem()
            assertTrue("the confirmation dialog must be armed", parked.showSubmitConfirmation)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.submit(TopicFormIntent.SubmitConfirmed) // launches the POST ; suspends on gate
        viewModel.submit(TopicFormIntent.SubmitConfirmed) // must be a no-op (canSubmit / submitJob guards)

        gate.complete(Unit)

        assertEquals("rapid double confirm must POST exactly once", 1, topicFormRepository.newTopicSubmitCalls)
        assertFalse(viewModel.state.value.showSubmitConfirmation)
        assertFalse(viewModel.state.value.isSubmitting)
    }

    // ----- #405 : draft autosave / restore -----------------------------------

    @Test
    fun `New autosave persists subject and body under the newTopic key`() = runTest {
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("My title")))
        viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("My body")))
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
        testScheduler.advanceUntilIdle()
        assertEquals("rescued body", viewModel.state.value.restorableDraft)
        assertEquals("rescued title", viewModel.state.value.restorableSubject)
        assertEquals("draft is not auto-applied", "", viewModel.state.value.draft.text)

        viewModel.submit(TopicFormIntent.DraftRestoreRequested)
        testScheduler.advanceUntilIdle()

        assertEquals("rescued body", viewModel.state.value.draft.text)
        assertEquals("rescued title", viewModel.state.value.subject.text)
        assertNull(viewModel.state.value.restorableDraft)
    }

    @Test
    fun `New discard deletes the cached draft and clears the banner`() = runTest {
        val key = EditorDraftKey.newTopic(SAMPLE_CAT)
        draftStore.preload(key, EditorDraftStore.Draft(body = "rescued body"))
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.DraftDiscardRequested)
        testScheduler.advanceUntilIdle()

        assertTrue(draftStore.deletedKeys.contains(key))
        assertNull(viewModel.state.value.restorableDraft)
    }

    @Test
    fun `a successful new-topic submit deletes the cached draft`() = runTest {
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        testScheduler.advanceUntilIdle()
        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("My title")))
        viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("My body")))

        viewModel.submit(TopicFormIntent.SubmitClicked)
        testScheduler.advanceUntilIdle()

        assertEquals("submit must have happened", 1, topicFormRepository.newTopicSubmitCalls)
        assertTrue(
            "a created topic must drop its draft",
            draftStore.deletedKeys.contains(EditorDraftKey.newTopic(SAMPLE_CAT)),
        )
    }

    @Test
    fun `EditFirstPost autosave and delete-on-submit use the editFirstPost key`() = runTest {
        val viewModel = newViewModel()
        testScheduler.advanceUntilIdle()

        // The FP form hydrated subject + body ; change the body so a draft is worth saving.
        viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("rewritten FP body")))
        testScheduler.advanceTimeBy(800L)
        testScheduler.runCurrent()
        val key = EditorDraftKey.editFirstPost(SAMPLE_CAT, SAMPLE_NUMREPONSE)
        assertEquals("rewritten FP body", draftStore.saved[key]?.body)

        viewModel.submit(TopicFormIntent.SubmitClicked)
        testScheduler.advanceUntilIdle()
        assertEquals(1, topicFormRepository.submitCalls)
        assertTrue("a saved FP must drop its draft", draftStore.deletedKeys.contains(key))
    }

    // ──────────────────────────────────────────────────────────────────────
    // #803 pattern — dirty close (flush before pop), state-hygiene audit 2026-07-05.
    // Same contract as PostEditorViewModelTest's « #604 lot 4a » block.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `CloseRequested flushes the pending debounce before CloseCommitted`() = runTest {
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        testScheduler.advanceUntilIdle()

        // Type, then close IMMEDIATELY — well inside the 750 ms debounce window. The flush must
        // persist the state at close time, not the snapshot the debounce captured at scheduling.
        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Mon titre")))
        viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("dernier mot")))
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
    fun `CloseRequested with blank subject and body deletes the row and still closes`() = runTest {
        val key = EditorDraftKey.newTopic(SAMPLE_CAT)
        draftStore.preload(key, EditorDraftStore.Draft(body = "stale", subject = "stale title"))
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.CloseRequested)

        val effect = viewModel.effects.first()
        assertEquals(TopicFormEffect.CloseCommitted, effect)
        assertTrue("an emptied form must not leave a stale row", draftStore.deletedKeys.contains(key))
    }

    @Test
    fun `CloseRequested with a subject only saves the draft instead of deleting it`() = runTest {
        // The delete branch requires BOTH fields blank : a titled-but-bodyless topic in progress
        // is still worth restoring.
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
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
        testScheduler.advanceUntilIdle()
        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Mon titre")))
        viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Mon corps")))

        viewModel.submit(TopicFormIntent.SubmitClicked)
        // Back pressed while the POST is in flight — must be inert (gate #803: popping would
        // cancel the submit with the viewModelScope and leave the server state unknown).
        viewModel.submit(TopicFormIntent.CloseRequested)
        gate.complete(Unit)
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
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.CloseRequested)
        viewModel.submit(TopicFormIntent.CloseRequested)
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
    fun `picked images upload and insert one img per success, in pick order (#459)`() = runTest {
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()
        val viewModel = newTopicViewModel(
            entrySubcat = SAMPLE_SUBCAT,
            uploadRepository = uploads,
            imageUploadReader = reader,
        )
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1", "content://pick/2")))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("content://pick/1", "content://pick/2"), reader.readUris)
        assertEquals(2, uploads.uploadCalls)
        val state = viewModel.state.value
        assertEquals(2, Regex("\\[img]").findAll(state.draft.text).count())
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
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1")))
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
        testScheduler.advanceUntilIdle()

        viewModel.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1", "content://pick/2")))
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(UploadError.TooLarge, state.uploadError)
        assertFalse(state.isUploading)
        assertEquals(1, uploads.uploadCalls)
        assertEquals("", state.draft.text)

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
        testScheduler.advanceUntilIdle()
        viewModel.submit(TopicFormIntent.SubjectChanged(TextFieldValue("Mon titre")))
        viewModel.submit(TopicFormIntent.ContentChanged(TextFieldValue("Mon corps")))
        viewModel.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1")))
        assertTrue("sanity: the upload must be in flight", viewModel.state.value.isUploading)

        viewModel.submit(TopicFormIntent.SubmitClicked)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("no POST may slip through mid-upload", 0, topicFormRepository.newTopicSubmitCalls)
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
        topicFormRepository = topicFormRepository,
        smileyRepository = smileyRepository,
        userPreferencesRepository = userPreferencesRepository,
        draftStore = draftStore,
        diagnostics = diagnostics,
        authRepository = authRepository,
        uploadRepository = uploadRepository,
        imageUploadReader = imageUploadReader,
    )

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

    private fun newViewModel(
        diagnostics: DiagnosticsLog = DiagnosticsLog(),
        userPreferencesRepository: UserPreferencesRepository = FakeUserPreferencesRepository(),
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
        topicFormRepository = topicFormRepository,
        smileyRepository = smileyRepository,
        userPreferencesRepository = userPreferencesRepository,
        draftStore = draftStore,
        diagnostics = diagnostics,
        authRepository = FakeAuthRepository(),
        uploadRepository = FakeUploadRepository(),
        imageUploadReader = FakeImageUploadReader(),
    )

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
        /** When set, holds the upload in flight until the test releases it (mirrors PostEditor's fake). */
        var uploadGate: CompletableDeferred<Unit>? = null
        var uploadCalls: Int = 0
            private set

        override suspend fun uploadWithCurrentProvider(image: ImageUpload, userId: String): UploadedImage {
            uploadCalls += 1
            uploadGate?.await()
            uploadException?.let { throw it }
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
        // #312 — holds an in-flight submit (both Edit FP and New) until the test releases it,
        // mirroring `FakeReplyRepository.submitGate` in `PostEditorViewModelTest`.
        var submitGate: CompletableDeferred<Unit>? = null

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

        override suspend fun fetchEditFirstPostForm(context: EditFirstPostContext): TopicForm {
            formFetches += 1
            lastFetchedContext = context
            formGate?.await()
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
            submitCalls += 1
            lastSubmittedContext = context
            lastSubmittedSubject = subject
            lastSubmittedBbcode = bbcodeContent
            lastSubmittedSubcat = selectedSubcat
            lastSubmittedOptions = options
            submitGate?.await()
            return submitResult ?: error("submitResult not set")
        }

        override suspend fun fetchNewTopicForm(context: NewTopicContext): TopicForm {
            newTopicFormFetches += 1
            lastNewTopicContext = context
            formGate?.await()
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
            newTopicSubmitCalls += 1
            lastNewTopicContext = context
            lastSubmittedSubject = subject
            lastSubmittedBbcode = bbcodeContent
            lastSubmittedSubcat = selectedSubcat
            lastSubmittedOptions = options
            submitGate?.await()
            return newTopicSubmitResult ?: error("newTopicSubmitResult not set")
        }
    }

    /**
     * Phase 2F-C (#11 partial) — fake smiley repository. Same shape as the one in
     * `PostEditorViewModelTest` ; holds a single pending deferred so tests can drive
     * loading / success / error transitions of the wiki search without touching the network.
     */
    private class FakeSmileyRepository : SmileyRepository {
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
        override fun observeAmoledEnabled(): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setAmoledEnabled(enabled: Boolean) = Unit
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

        override fun observeTopicSignatures(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setTopicSignatures(enabled: Boolean) = Unit

        override fun observeFoldLongQuotes(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setFoldLongQuotes(enabled: Boolean) = Unit

        override fun observeTopicFullWidthPosts(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setTopicFullWidthPosts(enabled: Boolean) = Unit

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

        // #973 — the block-GIF display profile is irrelevant to the topic form; stubbed at the M default.
        override fun observeMediaDisplayProfile(): Flow<MediaDisplayProfile> =
            MutableStateFlow(MediaDisplayProfile.M)

        override suspend fun setMediaDisplayProfile(profile: MediaDisplayProfile) = Unit

        override fun observeDebugBoundsOverlay(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setDebugBoundsOverlay(enabled: Boolean) = Unit

        override fun observeHideSystemNavBar(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setHideSystemNavBar(enabled: Boolean) = Unit

        override fun observeImmersiveBackButton(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setImmersiveBackButton(enabled: Boolean) = Unit

        override fun observeImmersiveNavBarReveal(): Flow<ImmersiveNavBarReveal> =
            MutableStateFlow(ImmersiveNavBarReveal.MANUAL)

        override suspend fun setImmersiveNavBarReveal(mode: ImmersiveNavBarReveal) = Unit
        override fun observeAccentColor(): Flow<AccentColor> = MutableStateFlow(AccentColor.ROSE)
        override suspend fun setAccentColor(color: AccentColor) = Unit
    }

    /** #405 — in-memory fake [EditorDraftStore], same shape as the one in `PostEditorViewModelTest`. */
    private class FakeEditorDraftStore : EditorDraftStore {
        val saved: MutableMap<String, EditorDraftStore.Draft> = mutableMapOf()
        val deletedKeys: MutableList<String> = mutableListOf()
        var saveCount: Int = 0
            private set

        fun preload(key: String, draft: EditorDraftStore.Draft) {
            saved[key] = draft
        }

        override suspend fun currentOwner(): String? = "tester"

        override suspend fun load(owner: String?, key: String): EditorDraftStore.Draft? = saved[key]

        override suspend fun save(owner: String?, key: String, draft: EditorDraftStore.Draft) {
            saveCount += 1
            saved[key] = draft
        }

        override suspend fun delete(owner: String?, key: String) {
            deletedKeys += key
            saved.remove(key)
        }
    }

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
