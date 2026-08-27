package fr.forumhfr.redface2.feature.editor
import fr.forumhfr.redface2.core.ui.editor.UploadError
import fr.forumhfr.redface2.core.ui.editor.UploadProgress

import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.BbcodeValidation
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
import fr.forumhfr.redface2.core.domain.preferences.SmileyPickerDecoration
import fr.forumhfr.redface2.core.domain.preferences.CategoryBandStyle
import fr.forumhfr.redface2.core.domain.preferences.FlagGlyphStyle
import fr.forumhfr.redface2.core.domain.preferences.AvatarAppearance
import fr.forumhfr.redface2.core.domain.preferences.CategoryFlagFilter
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.AccentColor
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.domain.preferences.PlusLusIndicatorStyle
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.WritingSurfacePreset
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.upload.UploadedImage
import fr.forumhfr.redface2.core.domain.upload.UploadedImageRecord
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import fr.forumhfr.redface2.core.model.EditorSmiley

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // One class per ViewModel keeps every code path co-located with its
// dispatcher / fake repositories — splitting per phase (#145 reply / #146 quote / #147 edit /
// #11 smiley) would shred the shared `Fake*Repository` test doubles into duplicated copies.
class PostEditorViewModelTest {

    private val previewParser = FakePreviewParser()
    private val replyRepository = FakeReplyRepository()
    private val editPostRepository = FakeEditPostRepository()
    private val smileyRepository = FakeSmileyRepository()
    private val draftStore = FakeEditorDraftStore()
    private val uploadRepository = FakeUploadRepository()
    private val imageUploadReader = FakeImageUploadReader()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
            assertEquals("", initial.draft.text)
            assertFalse(initial.isPreviewVisible)
            assertEquals(PostContent(blocks = emptyList()), initial.preview)
            assertEquals(BbcodeValidation.Idle, initial.validation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `content changes update the draft and clear the empty-validation hint`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("hello", TextRange(5))))
        viewModel.state.test {
            val state = awaitItem()
            assertEquals("hello", state.draft.text)
            assertEquals(BbcodeValidation.Idle, state.validation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toolbar action wraps the current selection`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("hello", TextRange(0, 5))))
        viewModel.submit(PostEditorIntent.ToolbarActionClicked(BbcodeAction.Bold))
        viewModel.state.test {
            val state = awaitItem()
            assertEquals("[b]hello[/b]", state.draft.text)
            assertEquals(3, state.draft.selection.start)
            assertEquals(8, state.draft.selection.end)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling preview parses the current draft and shows it`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("[b]hi[/b]", TextRange(9))))
        viewModel.submit(PostEditorIntent.TogglePreview)
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
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("hello", TextRange(5))))
        assertEquals(0, previewParser.callCount)
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
                SubmitError.Hfr(ReplyFailureReason.LoginRequired),
                settled.submitError,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit blank content does not POST`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.SubmitClicked)
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
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))
        viewModel.effects.test {
            viewModel.submit(PostEditorIntent.SubmitClicked)
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
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isSubmitting)
            assertEquals(
                SubmitError.Hfr(ReplyFailureReason.AntiFlood),
                settled.submitError,
            )
            // Draft survives.
            assertEquals("Hello!", settled.draft.text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit network error keeps the draft and surfaces Network error`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitException = IOException("disconnected")
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isSubmitting)
            assertEquals(SubmitError.Network, settled.submitError)
            assertEquals("Hello!", settled.draft.text)
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
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))

        viewModel.submit(PostEditorIntent.SubmitClicked) // launches the first submit; suspends on gate
        viewModel.submit(PostEditorIntent.SubmitClicked) // must be a no-op (job already active)

        gate.complete(Unit)
        assertEquals(1, replyRepository.submitCalls)
    }

    @Test
    fun `submit refuses when subcat is null and surfaces MissingSubcat`() = runTest {
        val viewModel = newReplyViewModel(subcat = null)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(SubmitError.MissingSubcat, settled.submitError)
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
            assertEquals(SubmitError.MissingSubcat, settled.submitError)
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
            assertEquals(SubmitError.SessionExpired, settled.submitError)
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
            assertEquals(SubmitError.Network, settled.submitError)
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
            assertEquals(SubmitError.Hfr(ReplyFailureReason.Unknown), settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `quote cards leave the open-time fetch plain (#604 lot 3)`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(initialQuotes = listOf(card(2784595)))
        // Let the launched form fetch complete.
        testScheduler.advanceUntilIdle()

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
            assertEquals("field stays empty — citations are cards, not BBCode", "", settled.draft.text)
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
        testScheduler.advanceUntilIdle()
        assertEquals("open-time fetch is the plain form only", 1, replyRepository.formFetches)

        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Reply")))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        testScheduler.advanceUntilIdle()

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
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Reply")))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        testScheduler.advanceUntilIdle()

        assertEquals("nothing must reach HFR on a failed materialisation", 0, replyRepository.submitCalls)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isSubmitting)
            assertEquals(SubmitError.Network, settled.submitError)
            assertEquals("cards intact", listOf(101, 303), settled.quotes.map { it.numreponse })
            assertEquals("body intact", "Reply", settled.draft.text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit fails whole when a card's prefill comes back blank (#291)`() = runTest {
        // A 200-OK form whose textarea prefill is EMPTY would otherwise silently drop a quote
        // the user explicitly selected — same contract as a failed fetch: fail globally.
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
            303 to Result.success(authenticatedForm(initialContent = "")),
        )

        val viewModel = newReplyViewModel(initialQuotes = listOf(card(101), card(303)))
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Reply")))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        testScheduler.advanceUntilIdle()

        assertEquals("nothing must reach HFR", 0, replyRepository.submitCalls)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isSubmitting)
            assertEquals(SubmitError.Hfr(ReplyFailureReason.Unknown), settled.submitError)
            assertEquals("cards intact", listOf(101, 303), settled.quotes.map { it.numreponse })
            assertEquals("body intact", "Reply", settled.draft.text)
            cancelAndIgnoreRemainingEvents()
        }
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
        viewModel.submit(
            PostEditorIntent.ContentChanged(
                androidx.compose.ui.text.input.TextFieldValue("My own message"),
            ),
        )
        // Now let the form load.
        formGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(
                "User-typed content must be preserved when the form arrives late",
                "My own message",
                settled.draft.text,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `option toggle intents flip the corresponding state booleans`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ToggleSignature(true))
        viewModel.submit(PostEditorIntent.ToggleSmileyDisabled(true))
        viewModel.submit(PostEditorIntent.ToggleEmailNotification(true))
        testScheduler.advanceUntilIdle()

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
        testScheduler.advanceUntilIdle()

        val settled = viewModel.state.value
        assertTrue("signature default true reflects HFR checked attribute", settled.signatureEnabled)
        assertFalse(settled.smileyDisabled)
        assertTrue(settled.emailNotificationEnabled)
        assertTrue("optionsHydratedFromForm flips after first load", settled.optionsHydratedFromForm)
    }

    @Test
    fun `silent refetch must not overwrite user-toggled options`() = runTest {
        // First load : HFR ships everything false. User flips signature on.
        // InvalidHashCheck triggers a silent refetch with the same defaults.
        // The user's toggle must survive — same anti-clobber rule as the draft.
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val viewModel = newReplyViewModel()
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ToggleSignature(true))
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("hi")))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        testScheduler.advanceUntilIdle()

        assertEquals("refetch happened", 2, replyRepository.formFetches)
        val settled = viewModel.state.value
        assertTrue("signature toggle must survive the refetch", settled.signatureEnabled)
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
        testScheduler.advanceUntilIdle()

        assertEquals("edit fetch called once", 1, editPostRepository.formFetches)
        val context = editPostRepository.lastFetchedContext
        assertNotNull("EditPostContext must reach the repository", context)
        requireNotNull(context)
        assertEquals(SAMPLE_EDITED_NUMREPONSE, context.numreponse)
        assertEquals(SAMPLE_TOPIC_ID, context.topicId)
        assertEquals(SAMPLE_SUBCAT, context.subcat)

        val settled = viewModel.state.value
        assertEquals("Existing post body", settled.draft.text)
        assertTrue(settled.draftHydratedFromForm)
    }

    @Test
    fun `Edit submit calls EditPostRepository with the numreponse from the route`() = runTest {
        editPostRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/foo/bar-sujet_35395_20.htm#t100",
            targetPage = 20,
        )
        val viewModel = newEditViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("rewritten body")))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        testScheduler.advanceUntilIdle()

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
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("rewritten body")))

        viewModel.effects.test {
            viewModel.submit(PostEditorIntent.SubmitClicked)
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
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("new reply")))

        viewModel.effects.test {
            viewModel.submit(PostEditorIntent.SubmitClicked)
            val effect = awaitItem() as PostEditorEffect.SubmitSucceeded
            assertEquals(20, effect.targetPage)
            assertNull("Reply must keep scrollTo null — anchor is #bas", effect.scrollTo)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Quote success forwards the parser-extracted numreponse as scrollTo (issue #200)`() = runTest {
        // Issue #200 — quote anchors `#t{numreponse}` on the success URL so the parser
        // surfaces the new post id directly. The ViewModel must propagate it as scrollTo
        // so the topic screen scrolls to the freshly-created quote post after the
        // post-submit force refresh.
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/foo/bar-sujet_148750_1.htm#t2523833",
            targetPage = 1,
            numreponse = 2_523_833,
        )
        val viewModel = newReplyViewModel()
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("citing")))

        viewModel.effects.test {
            viewModel.submit(PostEditorIntent.SubmitClicked)
            val effect = awaitItem() as PostEditorEffect.SubmitSucceeded
            assertEquals(1, effect.targetPage)
            assertEquals(
                "quote success must scroll to the new quote post id from the parser",
                2_523_833,
                effect.scrollTo,
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
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("rewritten body")))

        viewModel.effects.test {
            viewModel.submit(PostEditorIntent.SubmitClicked)
            val effect = awaitItem() as PostEditorEffect.SubmitSucceeded
            assertEquals(20, effect.targetPage)
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
        testScheduler.advanceUntilIdle()
        assertEquals("initial plain form fetch", 1, replyRepository.formFetches)

        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Reply")))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        testScheduler.advanceUntilIdle()

        // Open (plain) + the card's quote-form materialisation + the silent plain refetch.
        assertEquals("silent refetch after InvalidHashCheck", 3, replyRepository.formFetches)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals("user text must survive the silent refetch", "Reply", settled.draft.text)
            assertEquals("cards must survive too", listOf(2784595), settled.quotes.map { it.numreponse })
            assertEquals(
                SubmitError.Hfr(ReplyFailureReason.InvalidHashCheck),
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
        viewModel.submit(PostEditorIntent.TogglePreview)
        // Now release the form fetch.
        formGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals("Draft hydrated with the existing post body", "existing post body", settled.draft.text)
            assertTrue("Preview was visible before hydration", settled.isPreviewVisible)
            assertEquals(
                "Preview AST must reflect the hydrated draft",
                previewParser.contentFor("existing post body"),
                settled.preview,
            )
            cancelAndIgnoreRemainingEvents()
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
    ): PostEditorViewModel =
        PostEditorViewModel(
            request = PostEditorRequest(
                mode = PostEditorMode.Reply,
                cat = SAMPLE_CAT,
                topicId = SAMPLE_TOPIC_ID,
                numreponse = null,
                page = SAMPLE_PAGE,
                subcat = subcat,
                initialQuotes = initialQuotes,
                resumeSharedDraft = resumeSharedDraft,
            ),
            previewParser = previewParser,
            replyRepository = replyRepository,
            editPostRepository = editPostRepository,
            smileyRepository = smileyRepository,
            userPreferencesRepository = userPreferencesRepository,
            draftStore = draftStore,
            diagnostics = diagnostics,
            uploadRepository = uploadRepository,
            imageUploadReader = imageUploadReader,
            authRepository = authRepository,
            quoteMaterializer = TopicReplyQuoteMaterializer(replyRepository),
        )

    // ----- Phase 2F-B (#11) / #441 : smiley picker ----------------------------------
    // The picker machinery (open/dismiss, ≤ 2-chars gate, debounce, stale-result guards,
    // #824 restore-on-reopen) lives in the shared SmileyPickerController and is covered by
    // SmileyPickerControllerTest — no duplicate coverage here. These tests only exercise
    // what stays a ViewModel concern : the insertion intent, the userId plumbed from the
    // parsed form, and the diagnostics policy on search failure.

    @Test
    fun `the wiki search carries the form's parsed userId (#441)`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm().copy(userId = 54_596))
        val viewModel = newReplyViewModel()
        testScheduler.advanceUntilIdle() // form fetch → userId hydrated into state

        viewModel.smileyPicker.open()
        viewModel.smileyPicker.onQueryChanged("jap")
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()

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
        testScheduler.runCurrent()

        assertEquals(0, smileyRepository.lastUserId)
    }

    @Test
    fun `failed wiki search does not leak user id or query through diagnostics`() = runTest {
        val diagnostics = DiagnosticsLog()
        val viewModel = newReplyViewModel(diagnostics = diagnostics)
        viewModel.smileyPicker.open()
        viewModel.smileyPicker.onQueryChanged("secret")
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()

        smileyRepository.failNext(
            IOException("https://forum.hardware.fr/message-smi-mp-aj.php?user_id=12345&findsmilies=secret"),
        )
        testScheduler.runCurrent()

        val messages = diagnostics.entries.value.joinToString(separator = "\n") { it.message }
        assertTrue(messages.contains("wiki smiley search failed: IOException"))
        assertFalse(messages.contains("12345"))
        assertFalse(messages.contains("secret"))
        assertFalse(messages.contains("findsmilies"))
    }

    @Test
    fun `SmileySelected inserts the token at the caret and closes the picker`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("hello", TextRange(5))))
        viewModel.smileyPicker.open()
        viewModel.submit(PostEditorIntent.SmileySelected(":jap:"))
        viewModel.state.test {
            val state = expectMostRecentItem()
            // Surrounding spaces convention from `putSmiley` is honoured.
            assertEquals("hello :jap: ", state.draft.text)
            assertEquals(12, state.draft.selection.start)
            cancelAndIgnoreRemainingEvents()
        }
        // Picker auto-closes (through the controller) so the user can keep typing ;
        // #824 makes the same controller restore the search on the next open.
        assertEquals(SmileyPickerState.Hidden, viewModel.smileyPicker.state.value)
    }

    @Test
    fun `ImageUrlInserted inserts img token at caret and refreshes visible preview`() = runTest {
        val viewModel = newReplyViewModel()

        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("photo: ", TextRange(7))))
        viewModel.submit(PostEditorIntent.TogglePreview)
        viewModel.submit(PostEditorIntent.ImageUrlInserted(" https://rehost.diberie.com/Picture/Get/r/511520 "))

        viewModel.state.test {
            val state = expectMostRecentItem()
            val expected = "photo: [img]https://rehost.diberie.com/Picture/Get/r/511520[/img]"
            assertEquals(expected, state.draft.text)
            assertEquals(expected.length, state.draft.selection.start)
            assertEquals(previewParser.contentFor(expected), state.preview)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ImageUrlInserted ignores unsafe schemes`() = runTest {
        val viewModel = newReplyViewModel()

        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("photo: ", TextRange(7))))
        viewModel.submit(PostEditorIntent.ImageUrlInserted("content://media/external/images/1"))

        viewModel.state.test {
            val state = expectMostRecentItem()
            assertEquals("photo: ", state.draft.text)
            assertEquals(7, state.draft.selection.start)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ----- #459 PR2 : image upload from the photo picker ---------------------

    @Test
    fun `ImagePicked reads the uri uploads with the lowercased userId and inserts img at caret`() = runTest {
        // Authenticated with a MIXED-CASE pseudo to prove the userId is lowercased before reaching
        // the repository (the upload foundation byte-matches on the lowercased pseudo).
        val authRepository = FakeAuthRepository(AuthState.Authenticated("XaTriX"))
        imageUploadReader.result =
            ImageUpload(bytes = byteArrayOf(1, 2, 3), mimeType = "image/png", displayName = "p.png")
        uploadRepository.uploadResult = uploadedImage("https://rehost.diberie.com/Picture/Get/f/42")
        val viewModel = newReplyViewModel(authRepository = authRepository)
        testScheduler.advanceUntilIdle()

        val pickedUri = "content://media/external/images/99"
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("photo: ", TextRange(7))))
        viewModel.submit(PostEditorIntent.ImagePicked(pickedUri))
        testScheduler.advanceUntilIdle()

        assertEquals("the picked uri must be read once", pickedUri, imageUploadReader.lastUri)
        assertEquals("upload called once", 1, uploadRepository.uploadCalls)
        assertEquals("userId must be lowercased before the upload", "xatrix", uploadRepository.lastUserId)
        viewModel.state.test {
            val state = expectMostRecentItem()
            val expected = "photo: [img]https://rehost.diberie.com/Picture/Get/f/42[/img]"
            assertEquals("img token inserted at the caret on success", expected, state.draft.text)
            assertEquals("caret lands after the inserted token", expected.length, state.draft.selection.start)
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
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("photo: ", TextRange(7))))
        viewModel.submit(PostEditorIntent.ImagePicked("content://media/external/images/1"))
        testScheduler.advanceUntilIdle()

        viewModel.state.test {
            val state = expectMostRecentItem()
            assertEquals("draft is untouched on failure", "photo: ", state.draft.text)
            // #474 — a non-2xx response maps to a Server error carrying the HTTP code + host, so the
            // banner can name « diberie » and « HTTP 503 » instead of one vague « erreur de l'hébergeur ».
            assertEquals(
                "Server failure maps to a Server upload error with the code + provider",
                UploadError.Server(code = 503, providerId = UploadProviderId.DIBERIE),
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
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("photo: ", TextRange(7))))
        viewModel.submit(PostEditorIntent.ImagePicked("content://media/external/images/2"))
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("draft is untouched on a malformed response", "photo: ", state.draft.text)
        assertEquals(
            "Malformed maps to a distinct Malformed upload error carrying the provider",
            UploadError.Malformed(providerId = UploadProviderId.IMGUR),
            state.uploadError,
        )
        assertFalse("upload flag cleared on failure", state.isUploading)
    }

    @Test
    fun `ImagePicked maps TooLarge UnsupportedType and Network onto distinct UploadError variants`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/gif", displayName = null)

        uploadRepository.uploadException = UploadException.TooLarge(maxBytes = 1024)
        val tooLarge = newReplyViewModel(authRepository = authRepository)
        testScheduler.advanceUntilIdle()
        tooLarge.submit(PostEditorIntent.ImagePicked("content://x/1"))
        testScheduler.advanceUntilIdle()
        assertEquals(UploadError.TooLarge, tooLarge.state.value.uploadError)

        uploadRepository.uploadException = UploadException.UnsupportedType(mimeType = "image/gif")
        val unsupported = newReplyViewModel(authRepository = authRepository)
        testScheduler.advanceUntilIdle()
        unsupported.submit(PostEditorIntent.ImagePicked("content://x/2"))
        testScheduler.advanceUntilIdle()
        assertEquals(UploadError.UnsupportedType, unsupported.state.value.uploadError)

        uploadRepository.uploadException = UploadException.Network(java.io.IOException("offline"))
        val network = newReplyViewModel(authRepository = authRepository)
        testScheduler.advanceUntilIdle()
        network.submit(PostEditorIntent.ImagePicked("content://x/3"))
        testScheduler.advanceUntilIdle()
        assertEquals(UploadError.Network, network.state.value.uploadError)
    }

    @Test
    fun `ImagePicked maps an unreadable picked uri onto the Network error and inserts nothing`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        // The reader fails (UploadException.Network) BEFORE the upload — the repository must never
        // be reached, and the editor surfaces the same Network surface as a transport failure.
        imageUploadReader.exception = UploadException.Network(java.io.IOException("stream gone"))
        val viewModel = newReplyViewModel(authRepository = authRepository)
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("photo: ", TextRange(7))))
        viewModel.submit(PostEditorIntent.ImagePicked("content://x/1"))
        testScheduler.advanceUntilIdle()

        assertEquals("a failed read must not reach the upload", 0, uploadRepository.uploadCalls)
        val state = viewModel.state.value
        assertEquals("draft untouched when the read fails", "photo: ", state.draft.text)
        assertEquals(UploadError.Network, state.uploadError)
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
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ImagePicked("content://x/1"))
        testScheduler.runCurrent()
        assertTrue("isUploading must be true while the upload is in flight", viewModel.state.value.isUploading)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertFalse("isUploading must clear once the upload resolves", viewModel.state.value.isUploading)
    }

    @Test
    fun `ImagePicked is ignored for an anonymous client (no upload, no error)`() = runTest {
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = null)
        val viewModel = newReplyViewModel(authRepository = FakeAuthRepository(AuthState.Anonymous))
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("hi", TextRange(2))))
        viewModel.submit(PostEditorIntent.ImagePicked("content://x/1"))
        testScheduler.advanceUntilIdle()

        assertEquals("anonymous pick must not read the uri", 0, imageUploadReader.readCalls)
        assertEquals("anonymous pick must not upload", 0, uploadRepository.uploadCalls)
        val state = viewModel.state.value
        assertEquals("hi", state.draft.text)
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
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ImagePicked("content://x/1")) // launches, suspends on gate
        viewModel.submit(PostEditorIntent.ImagePicked("content://x/2")) // must be a no-op
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("only the first pick uploads", 1, uploadRepository.uploadCalls)
    }

    @Test
    fun `UploadErrorDismissed clears the upload error banner`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        imageUploadReader.result = ImageUpload(bytes = byteArrayOf(1), mimeType = "image/png", displayName = null)
        uploadRepository.uploadException = UploadException.Network(java.io.IOException("offline"))
        val viewModel = newReplyViewModel(authRepository = authRepository)
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ImagePicked("content://x/1"))
        testScheduler.advanceUntilIdle()
        assertEquals(UploadError.Network, viewModel.state.value.uploadError)

        viewModel.submit(PostEditorIntent.UploadErrorDismissed)

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
        testScheduler.advanceUntilIdle()

        viewModel.submit(
            PostEditorIntent.ImagesPicked(listOf("content://x/1", "content://x/2", "content://x/3")),
        )
        testScheduler.advanceUntilIdle()

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
        assertEquals("one [img] per image, each on its own line, in pick order", expected, state.draft.text)
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
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ImagesPicked(listOf("content://x/1", "content://x/2")))
        testScheduler.advanceUntilIdle()
        assertEquals(
            "after the first image completes the counter shows 1/2",
            UploadProgress(completed = 1, total = 2),
            viewModel.state.value.uploadProgress,
        )
        assertTrue("still uploading while the 2nd image is in flight", viewModel.state.value.isUploading)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
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
        testScheduler.advanceUntilIdle()

        viewModel.submit(
            PostEditorIntent.ImagesPicked(listOf("content://x/1", "content://x/2", "content://x/3")),
        )
        testScheduler.advanceUntilIdle()

        assertEquals("the batch stops after the failing upload — no 3rd attempt", 2, uploadRepository.uploadCalls)
        val state = viewModel.state.value
        assertEquals(
            "only the image uploaded before the failure is inserted",
            "[img]https://h/Picture/Get/f/1[/img]",
            state.draft.text,
        )
        assertEquals(
            "the typed error from the failing upload surfaces",
            UploadError.Server(code = 500, providerId = UploadProviderId.DIBERIE),
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
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ImagesPicked(listOf("content://x/1", "content://x/2")))
        testScheduler.advanceUntilIdle() // uploads run; 1st inserts + schedules autosave, 2nd fails; debounce elapses

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
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ImagePicked("content://x/1"))
        testScheduler.advanceUntilIdle()

        assertEquals(
            "REDUCED shows the reduced URL, clickable to the original",
            "[url=https://rehost.diberie.com/Picture/Get/f/42]" +
                "[img]https://rehost.diberie.com/Picture/Get/r/42[/img][/url]",
            viewModel.state.value.draft.text,
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
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ImagePicked("content://x/1"))
        testScheduler.advanceUntilIdle()

        assertEquals(
            "LINKED wraps the full image so a click opens the original",
            "[url=https://h/Picture/Get/f/42][img]https://h/Picture/Get/f/42[/img][/url]",
            viewModel.state.value.draft.text,
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
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ImagePicked("content://x/1"))
        testScheduler.advanceUntilIdle()

        assertEquals(
            "no reduced URL → REDUCED falls back to the full URL (same as LINKED)",
            "[url=https://i.imgur.com/x.png][img]https://i.imgur.com/x.png[/img][/url]",
            viewModel.state.value.draft.text,
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
            replyRepository = replyRepository,
            editPostRepository = editPostRepository,
            smileyRepository = smileyRepository,
            userPreferencesRepository = userPreferencesRepository,
            draftStore = draftStore,
            diagnostics = diagnostics,
            uploadRepository = uploadRepository,
            imageUploadReader = imageUploadReader,
            authRepository = authRepository,
            quoteMaterializer = TopicReplyQuoteMaterializer(replyRepository),
        )

    // ----- #312 : confirmation avant publication ------------------------------

    @Test
    fun `confirm-before-posting OFF keeps the one-tap reply submit unchanged`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val viewModel = newReplyViewModel() // default fake → preference OFF
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))

        viewModel.submit(PostEditorIntent.SubmitClicked)

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
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))

        viewModel.submit(PostEditorIntent.SubmitClicked)

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
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        assertEquals(0, replyRepository.submitCalls)

        viewModel.effects.test {
            viewModel.submit(PostEditorIntent.SubmitConfirmed)
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
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))
        viewModel.submit(PostEditorIntent.SubmitClicked)

        viewModel.submit(PostEditorIntent.SubmitConfirmationDismissed)

        assertFalse(viewModel.state.value.showSubmitConfirmation)
        assertEquals("dismiss must not POST anything", 0, replyRepository.submitCalls)
        assertEquals("the draft survives the dismissal", "Hello!", viewModel.state.value.draft.text)
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
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.SubmitClicked)

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
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("rewritten body")))

        viewModel.submit(PostEditorIntent.SubmitClicked)
        assertTrue(viewModel.state.value.showSubmitConfirmation)
        assertEquals("no edit POST before the user confirms", 0, editPostRepository.submitCalls)

        viewModel.submit(PostEditorIntent.SubmitConfirmed)
        testScheduler.advanceUntilIdle()

        assertEquals(1, editPostRepository.submitCalls)
        assertFalse(viewModel.state.value.showSubmitConfirmation)
    }

    @Test
    fun `confirm-before-posting ON re-clicking submit keeps the dialog armed without posting`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(
            userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = true),
        )
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        assertTrue("the confirmation dialog must be armed", viewModel.state.value.showSubmitConfirmation)

        // Second tap while the dialog is up (double-tap race) : idempotent — re-raising
        // `showSubmitConfirmation = true` is a no-op, and no POST may slip through.
        viewModel.submit(PostEditorIntent.SubmitClicked)

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
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        assertTrue("the confirmation dialog must be armed", viewModel.state.value.showSubmitConfirmation)

        viewModel.submit(PostEditorIntent.SubmitConfirmed) // launches the POST ; suspends on gate
        viewModel.submit(PostEditorIntent.SubmitConfirmed) // must be a no-op (canSubmit / submitJob guards)

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
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("draft body", TextRange(10))))
        // Below the 750 ms debounce nothing is written yet.
        testScheduler.advanceTimeBy(300L)
        testScheduler.runCurrent()
        assertEquals("no save before the debounce window elapses", 0, draftStore.saveCount)

        testScheduler.advanceTimeBy(500L)
        testScheduler.runCurrent()
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        assertEquals("draft body", draftStore.saved[key]?.body)
        assertFalse("reply drafts are not private", draftStore.saved[key]?.isPrivate == true)
    }

    @Test
    fun `autosave coalesces a burst of keystrokes into a single write`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("a")))
        testScheduler.advanceTimeBy(200L)
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("ab")))
        testScheduler.advanceTimeBy(200L)
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("abc")))
        testScheduler.advanceTimeBy(800L)
        testScheduler.runCurrent()

        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        assertEquals("only the final value survives the debounce", 1, draftStore.saveCount)
        assertEquals("abc", draftStore.saved[key]?.body)
    }

    @Test
    fun `emptying the draft deletes the cached row instead of saving a blank`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("something")))
        testScheduler.advanceTimeBy(800L)
        testScheduler.runCurrent()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("")))
        testScheduler.advanceTimeBy(800L)
        testScheduler.runCurrent()

        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        assertTrue("blank draft must delete the row", draftStore.deletedKeys.contains(key))
    }

    @Test
    fun `a stored draft is surfaced as restorable on init (never auto-applied)`() = runTest {
        draftStore.preload(
            EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID),
            EditorDraftStore.Draft(body = "rescued text"),
        )
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals("draft is offered, not silently applied", "rescued text", settled.restorableDraft)
            assertEquals("the live draft stays empty until the user restores", "", settled.draft.text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restoring fills the draft and clears the banner`() = runTest {
        draftStore.preload(
            EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID),
            EditorDraftStore.Draft(body = "rescued text"),
        )
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.DraftRestoreRequested)
        testScheduler.advanceUntilIdle()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals("rescued text", settled.draft.text)
            assertEquals("caret lands at the end of the restored body", 12, settled.draft.selection.start)
            assertNull("the banner is cleared after restoring", settled.restorableDraft)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `discarding deletes the cached draft and clears the banner`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        draftStore.preload(key, EditorDraftStore.Draft(body = "rescued text"))
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.DraftDiscardRequested)
        testScheduler.advanceUntilIdle()

        assertTrue("discard deletes the row", draftStore.deletedKeys.contains(key))
        assertNull("the banner is cleared after discarding", viewModel.state.value.restorableDraft)
    }

    @Test
    fun `a successful reply submit deletes the cached draft`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val viewModel = newReplyViewModel()
        testScheduler.advanceUntilIdle()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))

        viewModel.submit(PostEditorIntent.SubmitClicked)
        testScheduler.advanceUntilIdle()

        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        assertTrue("a sent reply must drop its draft", draftStore.deletedKeys.contains(key))
    }

    @Test
    fun `Edit autosave and delete-on-submit use the editPost key`() = runTest {
        editPostRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/foo/bar-sujet_35395_20.htm#t100",
            targetPage = 20,
        )
        val viewModel = newEditViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("rewritten body")))
        testScheduler.advanceTimeBy(800L)
        testScheduler.runCurrent()
        val key = EditorDraftKey.editPost(SAMPLE_CAT, SAMPLE_EDITED_NUMREPONSE)
        assertEquals("rewritten body", draftStore.saved[key]?.body)

        viewModel.submit(PostEditorIntent.SubmitClicked)
        testScheduler.advanceUntilIdle()
        assertTrue("a saved edit must drop its draft", draftStore.deletedKeys.contains(key))
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
        testScheduler.advanceUntilIdle()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals("texte de la sheet", settled.draft.text)
            assertNull("no banner on an escalation hand-over", settled.restorableDraft)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `resumeSharedDraft with no stored draft leaves the field empty — cards carry the citations`() = runTest {
        // (Replaced the pre-lot-3 « prepend the quote prefill » contract : the plain reply form
        // has no prefill anymore, the escalated citations arrive as cards.)
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(resumeSharedDraft = true, initialQuotes = listOf(card(101)))
        testScheduler.advanceUntilIdle()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals("", settled.draft.text)
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
        testScheduler.advanceUntilIdle()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(
                "pre-lot-3 flow restored : the field hydrates the merged [quotemsg] prefills",
                "[quotemsg=101,1,9]a[/quotemsg]\n\n[quotemsg=202,2,9]b[/quotemsg]",
                settled.draft.text.trimEnd(),
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
        testScheduler.advanceUntilIdle()

        val draft = viewModel.state.value.draft
        assertEquals("[quotemsg=101,1,9]a[/quotemsg]\n", draft.text)
        assertEquals("caret sits after the newline", draft.text.length, draft.selection.start)
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
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("déjà tapé")))
        formGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(
            "[quotemsg=101,1,9]a[/quotemsg]\n\ndéjà tapé",
            viewModel.state.value.draft.text,
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
        testScheduler.advanceUntilIdle()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(
                "[quotemsg=101,1,9]a[/quotemsg]\n\ntexte de la sheet",
                settled.draft.text,
            )
            assertNull("no banner on an escalation hand-over", settled.restorableDraft)
            assertEquals(
                "exactly one quote block — no double hydration",
                1,
                Regex("\\[quotemsg=101").findAll(settled.draft.text).count(),
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
        testScheduler.advanceUntilIdle()
        val openFetches = replyRepository.formFetches

        viewModel.submit(
            PostEditorIntent.ContentChanged(TextFieldValue("[quotemsg=101,1,9]a[/quotemsg]\n\nma réponse")),
        )
        viewModel.submit(PostEditorIntent.SubmitClicked)
        testScheduler.advanceUntilIdle()

        assertEquals("no re-materialisation at submit", openFetches, replyRepository.formFetches)
        assertEquals("[quotemsg=101,1,9]a[/quotemsg]\n\nma réponse", replyRepository.lastSubmittedBbcode)
        assertNull(
            "plain submit context — the quote already lives in the content",
            replyRepository.lastSubmittedContext?.quotedNumreponse,
        )
    }

    // ----- #604 lot 3 : cartes de citation dans l'éditeur ---------------------

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
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("body")))

        viewModel.submit(PostEditorIntent.QuoteRemoved(101))
        assertEquals(listOf(202), viewModel.state.value.quotes.map { it.numreponse })

        viewModel.submit(PostEditorIntent.QuotesCleared)
        assertEquals(emptyList<Int>(), viewModel.state.value.quotes.map { it.numreponse })
        assertEquals("body untouched by card mutations", "body", viewModel.state.value.draft.text)
    }

    @Test
    fun `QuoteMoved reorders within bounds and no-ops outside`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel(initialQuotes = listOf(card(101), card(202), card(303)))

        viewModel.submit(PostEditorIntent.QuoteMoved(303, delta = -1))
        assertEquals(listOf(101, 303, 202), viewModel.state.value.quotes.map { it.numreponse })

        viewModel.submit(PostEditorIntent.QuoteMoved(101, delta = -1))
        assertEquals(
            "first card cannot move up",
            listOf(101, 303, 202),
            viewModel.state.value.quotes.map { it.numreponse },
        )

        viewModel.submit(PostEditorIntent.QuoteMoved(999, delta = 1))
        assertEquals(
            "unknown card is a no-op",
            listOf(101, 303, 202),
            viewModel.state.value.quotes.map { it.numreponse },
        )
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
        testScheduler.advanceUntilIdle()

        assertTrue("cards alone must arm the submit (blank body)", viewModel.state.value.canSubmit)
        viewModel.submit(PostEditorIntent.SubmitClicked)
        testScheduler.advanceUntilIdle()

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
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ToggleSignature(true))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        testScheduler.advanceUntilIdle()

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
        testScheduler.advanceUntilIdle()

        // Type, then close IMMEDIATELY — well inside the 750 ms debounce window.
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("dernier mot")))
        viewModel.submit(PostEditorIntent.CloseRequested)

        val effect = viewModel.effects.first()
        assertEquals(PostEditorEffect.CloseCommitted, effect)
        assertEquals(
            "the tail of the draft must reach the row before the pop",
            "dernier mot",
            draftStore.saved[EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)]?.body,
        )
    }

    @Test
    fun `CloseRequested with a blank body deletes the row and still closes`() = runTest {
        val key = EditorDraftKey.reply(SAMPLE_CAT, SAMPLE_TOPIC_ID)
        draftStore.preload(key, EditorDraftStore.Draft(body = "stale"))
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.CloseRequested)

        val effect = viewModel.effects.first()
        assertEquals(PostEditorEffect.CloseCommitted, effect)
        assertTrue("an emptied editor must not leave a stale row", draftStore.deletedKeys.contains(key))
    }

    @Test
    fun `CloseRequested during an in-flight submit is ignored (gate #803)`() = runTest {
        val submitGate = CompletableDeferred<Unit>()
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        replyRepository.submitGate = submitGate
        val viewModel = newReplyViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("en vol")))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        // Back pressed while the POST is in flight — must be inert (parity with the sheet, #788).
        viewModel.submit(PostEditorIntent.CloseRequested)
        submitGate.complete(Unit)
        testScheduler.advanceUntilIdle()

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
        testScheduler.advanceUntilIdle()

        viewModel.submit(PostEditorIntent.CloseRequested)
        viewModel.submit(PostEditorIntent.CloseRequested)
        testScheduler.advanceUntilIdle()

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
            submitCalls += 1
            lastSubmittedContext = context
            lastSubmittedBbcode = bbcodeContent
            lastSubmittedOptions = options
            submitGate?.await()
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
            submitCalls += 1
            lastSubmittedContext = context
            lastSubmittedBbcode = bbcodeContent
            lastSubmittedOptions = options
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
        private val confirmBeforePosting = MutableStateFlow(confirmBeforePosting)
        private val quoteCardsEnabled = MutableStateFlow(quoteCardsEnabled)

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
        override fun observeQuoteCardsEnabled(): Flow<Boolean> = quoteCardsEnabled
        override suspend fun setQuoteCardsEnabled(enabled: Boolean) {
            quoteCardsEnabled.value = enabled
        }

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

        // #973 — the block-GIF display profile is irrelevant to the editor; stubbed at the M default.
        override fun observeMediaDisplayProfile(): Flow<MediaDisplayProfile> =
            MutableStateFlow(MediaDisplayProfile.M)

        override suspend fun setMediaDisplayProfile(profile: MediaDisplayProfile) = Unit

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
        override fun observeAccentColor(): Flow<AccentColor> = MutableStateFlow(AccentColor.ROSE)
        override suspend fun setAccentColor(color: AccentColor) = Unit

        // #1132 — Forum flag-filter preference is irrelevant to the editor; default ALL stub.
        override fun observeForumCategoryFlagFilter(): Flow<CategoryFlagFilter> =
            MutableStateFlow(CategoryFlagFilter.ALL)

        override suspend fun setForumCategoryFlagFilter(filter: CategoryFlagFilter) = Unit
    }

    /**
     * #405 — in-memory fake [EditorDraftStore]. Records the last save / delete per key and serves
     * preloaded drafts for the restore-on-init tests. `updatedAt` is irrelevant to the ViewModel
     * (the store stamps it in production), so it is left at 0.
     */
    private class FakeEditorDraftStore : EditorDraftStore {
        val saved: MutableMap<String, EditorDraftStore.Draft> = mutableMapOf()
        val deletedKeys: MutableList<String> = mutableListOf()
        var saveCount: Int = 0
            private set

        /** Preload a draft so a VM created afterwards restores it on init. */
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
