package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.domain.write.TopicFormRepository
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
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
class TopicFormViewModelTest {

    private val previewParser = FakePreviewParser()
    private val topicFormRepository = FakeTopicFormRepository()
    private val smileyRepository = FakeSmileyRepository()

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

    // ----- Phase 2F-C (#11) : smiley picker ----------------------------------

    @Test
    fun `SmileyPickerOpened transitions the picker to Open`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(TopicFormIntent.SmileyPickerOpened)
        viewModel.state.test {
            val state = expectMostRecentItem()
            val picker = state.smileyPicker
            assertTrue("expected Open, got $picker", picker is SmileyPickerState.Open)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SmileyPickerDismissed closes the sheet and cancels the in-flight search`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(TopicFormIntent.SmileyPickerOpened)
        viewModel.submit(TopicFormIntent.SmileySearchQueryChanged("jap"))
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        assertEquals(1, smileyRepository.callCount)

        viewModel.submit(TopicFormIntent.SmileyPickerDismissed)
        testScheduler.runCurrent()

        assertEquals(1, smileyRepository.cancellationCount)
        viewModel.state.test {
            val state = expectMostRecentItem()
            assertEquals(SmileyPickerState.Hidden, state.smileyPicker)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `short queries stay below the 2-char threshold and do not hit the repository`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(TopicFormIntent.SmileyPickerOpened)
        viewModel.submit(TopicFormIntent.SmileySearchQueryChanged("ja"))
        // Below threshold : the gate is synchronous, no debounce kicks in.
        assertEquals(0, smileyRepository.callCount)
        viewModel.state.test {
            val state = expectMostRecentItem()
            val picker = state.smileyPicker as SmileyPickerState.Open
            assertEquals(WikiSearchState.Idle, picker.wiki)
            assertEquals("ja", picker.query)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `queries above the threshold hit the repository with the hydrated userId after debounce`() = runTest {
        val viewModel = newViewModel()
        // Wait for the form to be hydrated so userId lands in state.
        viewModel.state.test {
            awaitHydratedState()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.submit(TopicFormIntent.SmileyPickerOpened)
        viewModel.submit(TopicFormIntent.SmileySearchQueryChanged("jap"))
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        assertEquals(1, smileyRepository.callCount)
        assertEquals("jap", smileyRepository.lastQuery)
        // The form's parsed userId is plumbed through to the search call — the
        // repository falls back to 0 only when `state.userId` is `null`.
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
        viewModel.submit(TopicFormIntent.SmileyPickerOpened)
        viewModel.submit(TopicFormIntent.SmileySearchQueryChanged("jap"))
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        assertEquals(0, smileyRepository.lastUserId)
    }

    @Test
    fun `failed wiki search lands as Error and keeps the picker open`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(TopicFormIntent.SmileyPickerOpened)
        viewModel.submit(TopicFormIntent.SmileySearchQueryChanged("jap"))
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        smileyRepository.failNext(java.io.IOException("offline"))
        viewModel.state.test {
            val state = expectMostRecentItem()
            val picker = state.smileyPicker as SmileyPickerState.Open
            assertEquals(WikiSearchState.Error, picker.wiki)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failed wiki search does not leak user id or query through diagnostics`() = runTest {
        val diagnostics = DiagnosticsLog()
        val viewModel = newViewModel(diagnostics = diagnostics)
        viewModel.submit(TopicFormIntent.SmileyPickerOpened)
        viewModel.submit(TopicFormIntent.SmileySearchQueryChanged("secret"))
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
        // Move caret to start of draft, open preview, then pick a smiley.
        viewModel.submit(
            TopicFormIntent.ContentChanged(TextFieldValue("hello", TextRange(5))),
        )
        viewModel.submit(TopicFormIntent.TogglePreview)
        viewModel.submit(TopicFormIntent.SmileyPickerOpened)
        viewModel.submit(TopicFormIntent.SmileySelected(":jap:"))
        viewModel.state.test {
            val state = expectMostRecentItem()
            // Surrounding-spaces convention from `insertBbcodeToken` is honoured.
            assertEquals("hello :jap: ", state.draft.text)
            assertEquals(12, state.draft.selection.start)
            assertEquals(SmileyPickerState.Hidden, state.smileyPicker)
            // Preview was visible : the new draft text must be re-parsed.
            val firstBlock = state.preview.blocks.first() as PostBlock.Paragraph
            val firstInline = firstBlock.inlines.first() as PostInline.Text
            assertEquals("hello :jap: ", firstInline.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `two successive queries do not allow the first response to clobber the second`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(TopicFormIntent.SmileyPickerOpened)
        viewModel.submit(TopicFormIntent.SmileySearchQueryChanged("jap"))
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        // Second keystroke arrives BEFORE the first response lands.
        viewModel.submit(TopicFormIntent.SmileySearchQueryChanged("jape"))
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        // The repo recorded the second call ; the first was cancelled.
        assertEquals(2, smileyRepository.callCount)
        assertEquals(1, smileyRepository.cancellationCount)
        assertEquals("jape", smileyRepository.lastQuery)

        // The latest response (for "jape") lands ; the earlier "jap" job was cancelled
        // so its result cannot overwrite the current state.
        smileyRepository.completeNext(
            listOf(
                EditorSmiley(
                    token = "[:jape]",
                    imageUrl = "https://forum-images.hardware.fr/images/perso/jape.gif",
                    source = EditorSmileySource.WIKI,
                ),
            ),
        )
        viewModel.state.test {
            val state = expectMostRecentItem()
            val picker = state.smileyPicker as SmileyPickerState.Open
            val wiki = picker.wiki as WikiSearchState.Results
            assertEquals(1, wiki.items.size)
            assertEquals("[:jape]", wiki.items[0].token)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `same query typed twice within debounce window fires only one request`() = runTest {
        // Covers the identity guard `coroutineContext[Job] !== smileySearchJob` : when the
        // SAME query is sent twice before the first 300 ms debounce resolves, the second
        // QueryChanged cancels the first job ; the second job survives the debounce and
        // is the only one to land a request.
        val viewModel = newViewModel()
        viewModel.submit(TopicFormIntent.SmileyPickerOpened)
        viewModel.submit(TopicFormIntent.SmileySearchQueryChanged("jap"))
        // Inside the debounce window — no fire yet.
        testScheduler.advanceTimeBy(100L)
        testScheduler.runCurrent()
        assertEquals(0, smileyRepository.callCount)

        viewModel.submit(TopicFormIntent.SmileySearchQueryChanged("jap"))
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()

        // Exactly one network call : the first job was cancelled at ~100 ms by the second
        // QueryChanged, and the second job's identity guard saw itself as the live one.
        assertEquals(1, smileyRepository.callCount)
        assertEquals("jap", smileyRepository.lastQuery)
        // The first job never reached `searchWiki` (it was cancelled during its delay), so
        // no `cancellationCount` increment is expected here — the cancellation tally only
        // tracks cancellations that happen inside `searchWiki.await()`.
        assertEquals(0, smileyRepository.cancellationCount)
    }

    @Test
    fun `New mode also opens the smiley picker and uses the hydrated userId`() = runTest {
        val viewModel = newTopicViewModel(entrySubcat = SAMPLE_SUBCAT)
        viewModel.state.test {
            // Wait for the form fetch to land.
            val hydrated = expectMostRecentItem()
            assertEquals(SAMPLE_USER_ID, hydrated.userId)
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.submit(TopicFormIntent.SmileyPickerOpened)
        viewModel.submit(TopicFormIntent.SmileySearchQueryChanged("jap"))
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        assertEquals(SAMPLE_USER_ID, smileyRepository.lastUserId)
    }

    private fun newTopicViewModel(
        entrySubcat: Int?,
        diagnostics: DiagnosticsLog = DiagnosticsLog(),
    ): TopicFormViewModel = TopicFormViewModel(
        request = TopicFormRequest(
            mode = TopicFormMode.New,
            cat = SAMPLE_CAT,
            subcat = entrySubcat,
            topicId = null,
            page = null,
            numreponse = null,
        ),
        previewParser = previewParser,
        topicFormRepository = topicFormRepository,
        smileyRepository = smileyRepository,
        diagnostics = diagnostics,
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
        diagnostics = diagnostics,
    )

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

    private companion object {
        const val SAMPLE_CAT = 23
        const val SAMPLE_TOPIC_ID = 35_395
        const val SAMPLE_SUBCAT = 550
        const val SAMPLE_OTHER_SUBCAT = 388
        const val SAMPLE_NUMREPONSE = 100
        const val SAMPLE_USER_ID = 1_234_567
    }
}
