package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.BbcodeValidation
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import java.io.IOException
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
    fun `submit refuses when subcat is zero (HFR moderator-space wire shape)`() = runTest {
        // 0 is HFR's wire shape for the moderator-only space (cf. cat=0 family).
        // No fixture exercises subcat=0 on a user topic, so write flows refuse it
        // to avoid sending a malformed POST.
        val viewModel = newReplyViewModel(subcat = 0)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(SubmitError.MissingSubcat, settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, replyRepository.formFetches)
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

    private fun newReplyViewModel(
        subcat: Int? = SAMPLE_SUBCAT,
        quotedNumreponse: Int? = null,
        quoteRef: Int? = null,
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
            ),
            previewParser = previewParser,
            replyRepository = replyRepository,
            editPostRepository = editPostRepository,
            smileyRepository = smileyRepository,
            diagnostics = fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog(),
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

    private fun newEditViewModel(
        subcat: Int? = SAMPLE_SUBCAT,
        numreponse: Int? = SAMPLE_EDITED_NUMREPONSE,
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
            diagnostics = fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog(),
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

        override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm {
            formFetches += 1
            lastFetchedContext = context
            formGate?.await()
            formException?.let { throw it }
            return formResult.getOrThrow()
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

        override suspend fun searchWiki(userId: Int, query: String): List<EditorSmiley> {
            callCount += 1
            lastUserId = userId
            lastQuery = query
            val deferred = kotlinx.coroutines.CompletableDeferred<List<EditorSmiley>>()
            pending = deferred
            return deferred.await()
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
        const val SAMPLE_PAGE = 20
        const val SAMPLE_SUBCAT = 550
        const val SAMPLE_EDITED_NUMREPONSE = 100
    }
}
