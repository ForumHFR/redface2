package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.ui.editor.WikiSearchState
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.BbcodeValidation
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
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
import fr.forumhfr.redface2.core.model.EditorSmileySource

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // One class per ViewModel keeps every code path co-located with its
// dispatcher / fake repositories — splitting per phase (#145 reply / #146 quote / #147 edit /
// #11 smiley) would shred the shared `Fake*Repository` test doubles into duplicated copies.
class PostEditorViewModelTest {

    private val previewParser = FakePreviewParser()
    private val replyRepository = FakeReplyRepository()
    private val editPostRepository = FakeEditPostRepository()
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
    fun `quote VM forwards quotedNumreponse and quoteRef to ReplyRepository on form fetch`() = runTest {
        replyRepository.formResult = Result.success(
            authenticatedForm(initialContent = "[quotemsg=2784595,768,1214571]hi[/quotemsg]"),
        )
        newReplyViewModel(quotedNumreponse = 2784595, quoteRef = 0)
        // Let the launched form fetch complete.
        testScheduler.advanceUntilIdle()

        val context = replyRepository.lastFetchedContext
        assertNotNull("fetchReplyForm must have been called", context)
        requireNotNull(context)
        assertEquals("quoted numreponse must propagate", 2784595, context.quotedNumreponse)
        assertEquals("quote ref must propagate", 0, context.quoteRef)
        assertTrue("isQuote must be true", context.isQuote)
    }

    @Test
    fun `quote VM hydrates the draft from initialContent on first form load`() = runTest {
        val prefill = "[quotemsg=2784595,768,1214571]hi[/quotemsg]"
        replyRepository.formResult = Result.success(authenticatedForm(initialContent = prefill))

        val viewModel = newReplyViewModel(quotedNumreponse = 2784595, quoteRef = 0)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse("Form must be fully loaded", settled.isLoadingForm)
            assertEquals("Draft hydrated with HFR prefill", prefill, settled.draft.text)
            assertTrue("Caret placed at the end of the prefill", settled.draftHydratedFromForm)
            assertEquals(prefill.length, settled.draft.selection.start)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multi-quote VM concatenates the prefills in selection order (#291)`() = runTest {
        // One #146 form fetch per quoted post — the FIRST carries the session form (hash_check),
        // the extras only contribute their prefill. HFR prefills end with a trailing blank line;
        // the merge must keep ONE blank line between quotes and one after the last.
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
            303 to Result.success(authenticatedForm(initialContent = "[quotemsg=303,3,9]c[/quotemsg]\n\n")),
            202 to Result.success(authenticatedForm(initialContent = "[quotemsg=202,2,9]b[/quotemsg]\n\n")),
        )

        // Selection order 101 → 303 → 202 is deliberately NOT post order.
        val viewModel = newReplyViewModel(
            quotedNumreponse = 101,
            quoteRef = 0,
            extraQuoteNumreponses = listOf(303, 202),
        )
        testScheduler.advanceUntilIdle()

        assertEquals("one fetch per quoted post", 3, replyRepository.formFetches)
        assertEquals(
            "extras must replay the quote contract with the numreponse swapped (no stale quoteRef)",
            listOf(101 to 0, 303 to null, 202 to null),
            replyRepository.fetchedContexts.map { it.quotedNumreponse to it.quoteRef },
        )
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse("Form must be fully loaded", settled.isLoadingForm)
            assertEquals(
                "prefills concatenated in SELECTION order, single blank line between quotes",
                "[quotemsg=101,1,9]a[/quotemsg]\n\n" +
                    "[quotemsg=303,3,9]c[/quotemsg]\n\n" +
                    "[quotemsg=202,2,9]b[/quotemsg]\n\n",
                settled.draft.text,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multi-quote VM fails the whole fetch when one extra quote fails (#291)`() = runTest {
        // Silently dropping a quote the user explicitly selected would be worse than the
        // retryable form-fetch error, so a failed extra fails the load.
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
            303 to Result.failure(java.io.IOException("boom")),
        )

        val viewModel = newReplyViewModel(
            quotedNumreponse = 101,
            quoteRef = null,
            extraQuoteNumreponses = listOf(303),
        )
        testScheduler.advanceUntilIdle()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isLoadingForm)
            assertEquals("draft must not hydrate from a partial multi-quote", "", settled.draft.text)
            assertEquals(SubmitError.Network, settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multi-quote VM fails the whole fetch when a prefill comes back blank (#291)`() = runTest {
        // A 200-OK form whose textarea prefill is EMPTY would otherwise silently drop a quote
        // the user explicitly selected — same contract as a failed extra: fail globally.
        replyRepository.formResultsByNumrep = mapOf(
            101 to Result.success(authenticatedForm(initialContent = "[quotemsg=101,1,9]a[/quotemsg]\n\n")),
            303 to Result.success(authenticatedForm(initialContent = "")),
        )

        val viewModel = newReplyViewModel(
            quotedNumreponse = 101,
            quoteRef = null,
            extraQuoteNumreponses = listOf(303),
        )
        testScheduler.advanceUntilIdle()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isLoadingForm)
            assertEquals("draft must not hydrate from a partial multi-quote", "", settled.draft.text)
            assertEquals(SubmitError.Hfr(ReplyFailureReason.Unknown), settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `quote VM does not overwrite a draft the user already typed`() = runTest {
        // User-typed content lands on the VM before the form fetch completes —
        // we gate the fetch with a CompletableDeferred so we can interleave them
        // exactly like the production race (network in flight, user typing).
        val formGate = CompletableDeferred<Unit>()
        replyRepository.formResult = Result.success(
            authenticatedForm(initialContent = "[quotemsg=2784595,768,1214571]hi[/quotemsg]"),
        )
        replyRepository.formGate = formGate

        val viewModel = newReplyViewModel(quotedNumreponse = 2784595, quoteRef = 0)
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
    fun `submit with InvalidHashCheck silently refetches without clobbering quote draft`() = runTest {
        // `handleSubmitOutcome(InvalidHashCheck)` resets `loadedForm = null` and
        // re-invokes `loadReplyFormIfPossible()`. The `draftHydratedFromForm`
        // guard must prevent the refetch from overwriting whatever the user has
        // typed since the initial hydration. We assert : (a) the second form
        // fetch did happen, (b) the user's edited draft survives.
        val prefill = "[quotemsg=2784595,768,1214571]hi[/quotemsg]"
        replyRepository.formResult = Result.success(authenticatedForm(initialContent = prefill))
        replyRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val viewModel = newReplyViewModel(quotedNumreponse = 2784595, quoteRef = 0)
        testScheduler.advanceUntilIdle()
        assertEquals("initial form fetch", 1, replyRepository.formFetches)

        // User edits the prefill (cursor at end → add a real reply after the quote).
        val edited = "$prefill\n\nReply"
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue(edited)))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        testScheduler.advanceUntilIdle()

        assertEquals("silent refetch after InvalidHashCheck", 2, replyRepository.formFetches)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(
                "User edit must survive the silent refetch — draftHydratedFromForm blocks the rewrite",
                edited,
                settled.draft.text,
            )
            assertTrue("draftHydratedFromForm stays true across refetch", settled.draftHydratedFromForm)
            assertEquals(
                SubmitError.Hfr(ReplyFailureReason.InvalidHashCheck),
                settled.submitError,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `quote hydration refreshes preview when preview was already visible`() = runTest {
        // Race : user opens quote editor, opens the preview pane WHILE the form
        // is still loading, form arrives → both `draft` and `preview` must reflect
        // the `[quotemsg=…]` prefill. Before round 2 the preview AST stayed empty
        // until the next `ContentChanged` / `TogglePreview`, which felt like a bug
        // even though the draft was fine.
        val formGate = CompletableDeferred<Unit>()
        val prefill = "[quotemsg=2784595,768,1214571]hi[/quotemsg]"
        replyRepository.formResult = Result.success(authenticatedForm(initialContent = prefill))
        replyRepository.formGate = formGate

        val viewModel = newReplyViewModel(quotedNumreponse = 2784595, quoteRef = 0)
        // Toggle preview BEFORE the form lands.
        viewModel.submit(PostEditorIntent.TogglePreview)
        // Now release the form fetch.
        formGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals("Draft hydrated with HFR prefill", prefill, settled.draft.text)
            assertTrue("Preview was visible before hydration", settled.isPreviewVisible)
            assertEquals(
                "Preview AST must reflect the hydrated draft",
                previewParser.contentFor(prefill),
                settled.preview,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Suppress("LongParameterList") // test helper: every param is an optional, defaulted scenario knob.
    private fun newReplyViewModel(
        subcat: Int? = SAMPLE_SUBCAT,
        quotedNumreponse: Int? = null,
        quoteRef: Int? = null,
        extraQuoteNumreponses: List<Int> = emptyList(),
        diagnostics: DiagnosticsLog = DiagnosticsLog(),
        userPreferencesRepository: UserPreferencesRepository = FakeUserPreferencesRepository(),
    ): PostEditorViewModel =
        PostEditorViewModel(
            request = PostEditorRequest(
                mode = PostEditorMode.Reply,
                cat = SAMPLE_CAT,
                topicId = SAMPLE_TOPIC_ID,
                numreponse = null,
                page = SAMPLE_PAGE,
                subcat = subcat,
                quotedNumreponse = quotedNumreponse,
                quoteRef = quoteRef,
                extraQuoteNumreponses = extraQuoteNumreponses,
            ),
            previewParser = previewParser,
            replyRepository = replyRepository,
            editPostRepository = editPostRepository,
            smileyRepository = smileyRepository,
            userPreferencesRepository = userPreferencesRepository,
            diagnostics = diagnostics,
        )

    // ----- Phase 2F-B (#11) : smiley picker ----------------------------------

    @Test
    fun `SmileyPickerOpened transitions the state to Open`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.state.test {
            skipItems(1) // initial idle state
            viewModel.submit(PostEditorIntent.SmileyPickerOpened)
            val opened = expectMostRecentItem()
            val picker = opened.smileyPicker
            assert(picker is SmileyPickerState.Open) {
                "expected Open, got $picker"
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SmileyPickerDismissed sets the state back to Hidden`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.SmileyPickerOpened)
        viewModel.submit(PostEditorIntent.SmileyPickerDismissed)
        viewModel.state.test {
            val state = expectMostRecentItem()
            assert(state.smileyPicker == SmileyPickerState.Hidden) {
                "expected Hidden after dismiss, got ${state.smileyPicker}"
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `short queries stay below the 2-char threshold and do not hit the repository`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.SmileyPickerOpened)
        viewModel.submit(PostEditorIntent.SmileySearchQueryChanged("ja"))
        // The 2-char query is below threshold, so no debounce kicks in either ; assert by
        // call-count rather than racing the dispatcher.
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
    fun `queries above the threshold hit the repository after the debounce`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.SmileyPickerOpened)
        viewModel.submit(PostEditorIntent.SmileySearchQueryChanged("jap"))
        // Advance through the 300 ms debounce.
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        assertEquals(1, smileyRepository.callCount)
        assertEquals("jap", smileyRepository.lastQuery)
    }

    @Test
    fun `successful wiki search lands as Results in the picker`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.SmileyPickerOpened)
        viewModel.submit(PostEditorIntent.SmileySearchQueryChanged("jap"))
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        smileyRepository.completeNext(
            listOf(
                EditorSmiley(
                    token = "[:haha jap]",
                    imageUrl = "https://forum-images.hardware.fr/images/perso/haha%20jap.gif",
                    source = EditorSmileySource.WIKI,
                ),
            ),
        )
        viewModel.state.test {
            val state = expectMostRecentItem()
            val picker = state.smileyPicker as SmileyPickerState.Open
            val wiki = picker.wiki as WikiSearchState.Results
            assertEquals(1, wiki.items.size)
            assertEquals("[:haha jap]", wiki.items[0].token)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failed wiki search lands as Error and keeps the picker open`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.SmileyPickerOpened)
        viewModel.submit(PostEditorIntent.SmileySearchQueryChanged("jap"))
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
        val viewModel = newReplyViewModel(diagnostics = diagnostics)
        viewModel.submit(PostEditorIntent.SmileyPickerOpened)
        viewModel.submit(PostEditorIntent.SmileySearchQueryChanged("secret"))
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
        viewModel.submit(PostEditorIntent.ContentChanged(
            androidx.compose.ui.text.input.TextFieldValue("hello", androidx.compose.ui.text.TextRange(5)),
        ))
        viewModel.submit(PostEditorIntent.SmileyPickerOpened)
        viewModel.submit(PostEditorIntent.SmileySelected(":jap:"))
        viewModel.state.test {
            val state = expectMostRecentItem()
            // Surrounding spaces convention from `putSmiley` is honoured.
            assertEquals("hello :jap: ", state.draft.text)
            assertEquals(12, state.draft.selection.start)
            // Picker auto-closes so the user can keep typing.
            assertEquals(SmileyPickerState.Hidden, state.smileyPicker)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SmileySelected cancels the in-flight wiki search`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.SmileyPickerOpened)
        viewModel.submit(PostEditorIntent.SmileySearchQueryChanged("jap"))
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        assertEquals(1, smileyRepository.callCount)

        viewModel.submit(PostEditorIntent.SmileySelected(":jap:"))
        testScheduler.runCurrent()

        assertEquals(1, smileyRepository.cancellationCount)
        viewModel.state.test {
            val state = expectMostRecentItem()
            assertEquals(SmileyPickerState.Hidden, state.smileyPicker)
            cancelAndIgnoreRemainingEvents()
        }
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

    private fun newEditViewModel(
        subcat: Int? = SAMPLE_SUBCAT,
        numreponse: Int? = SAMPLE_EDITED_NUMREPONSE,
        diagnostics: DiagnosticsLog = DiagnosticsLog(),
        userPreferencesRepository: UserPreferencesRepository = FakeUserPreferencesRepository(),
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
            diagnostics = diagnostics,
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
    ) : UserPreferencesRepository {
        private val confirmBeforePosting = MutableStateFlow(confirmBeforePosting)

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

        override fun observeShowDtSection(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setShowDtSection(enabled: Boolean) = Unit

        override fun observeFlagsAutoRefresh(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setFlagsAutoRefresh(enabled: Boolean) = Unit

        override fun observeTopicPageFabs(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setTopicPageFabs(enabled: Boolean) = Unit

        override fun observeMpUnreadBadge(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setMpUnreadBadge(enabled: Boolean) = Unit

        override fun observeTopicPollsExpanded(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setTopicPollsExpanded(enabled: Boolean) = Unit

        override fun observeStartScreen(): Flow<StartScreenPreference> =
            MutableStateFlow(StartScreenPreference())

        override suspend fun setStartScreen(preference: StartScreenPreference) = Unit

        // #459 — upload provider / imgur Client-ID are irrelevant to this ViewModel; default stubs.
        override fun observeUploadProvider(): Flow<UploadProviderId> =
            MutableStateFlow(UploadProviderId.DIBERIE)

        override suspend fun setUploadProvider(provider: UploadProviderId) = Unit

        override fun observeImgurClientId(): Flow<String> = MutableStateFlow("")

        override suspend fun setImgurClientId(clientId: String) = Unit
    }

    private companion object {
        const val SAMPLE_CAT = 23
        const val SAMPLE_TOPIC_ID = 35_395
        const val SAMPLE_PAGE = 20
        const val SAMPLE_SUBCAT = 550
        const val SAMPLE_EDITED_NUMREPONSE = 100
    }
}
