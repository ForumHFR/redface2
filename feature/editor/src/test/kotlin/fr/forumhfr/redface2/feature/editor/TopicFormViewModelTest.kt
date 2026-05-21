package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.write.TopicFormRepository
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.write.EditFirstPostContext
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

    @Test
    fun `New mode surfaces the placeholder and never fetches the form`() = runTest {
        val viewModel = TopicFormViewModel(
            request = TopicFormRequest(
                mode = TopicFormMode.New,
                cat = SAMPLE_CAT,
                subcat = SAMPLE_SUBCAT,
                topicId = null,
                page = null,
                numreponse = null,
            ),
            previewParser = previewParser,
            topicFormRepository = topicFormRepository,
            diagnostics = DiagnosticsLog(),
        )
        viewModel.state.test {
            val initial = awaitItem()
            assertEquals(TopicFormMode.New, initial.mode)
            assertFalse(initial.isLoadingForm)
            assertNull(initial.submitError)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, topicFormRepository.formFetches)
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

    private fun newViewModel(): TopicFormViewModel = TopicFormViewModel(
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
        diagnostics = DiagnosticsLog(),
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
        var submitResult: ReplySubmitResult? = ReplySubmitResult.Success(
            targetPage = 1,
            refreshUrl = "/hfr/",
        )
        var formGate: CompletableDeferred<Unit>? = null

        var formFetches: Int = 0
            private set
        var submitCalls: Int = 0
            private set
        var lastFetchedContext: EditFirstPostContext? = null
            private set
        var lastSubmittedContext: EditFirstPostContext? = null
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
    }

    private companion object {
        const val SAMPLE_CAT = 23
        const val SAMPLE_TOPIC_ID = 35_395
        const val SAMPLE_SUBCAT = 550
        const val SAMPLE_OTHER_SUBCAT = 388
        const val SAMPLE_NUMREPONSE = 100
    }
}
