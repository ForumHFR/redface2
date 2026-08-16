package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #953 (F3) — the quick-reply sheet must SURVIVE an activity recreation that happens while a POST
 * is in flight. [QuickReplyViewModel] is scoped to the topic's nav entry, so it outlives the
 * recreation and its buffered [QuickReplyEffect.SubmitSucceeded] waits for a collector — but the
 * collector lives in the sheet's composition, mounted by the `quickReplyFor` launch state. Before
 * this fix that state was a plain `remember`: the recreation dropped it, the sheet never came
 * back, and the buffered success was REPLAYED at the next manual opening (instant close, phantom
 * refresh/scroll, basket purge).
 *
 * The host below is the [QuickReplySheet] mount contract reduced to its moving parts (the real
 * sheet resolves its ViewModel through `hiltViewModel`, unavailable in a JVM test): the
 * [rememberQuickReplyLaunch] production state + the sheet analog whose `LaunchedEffect(viewModel)`
 * calls `onSheetOpened` then collects the effects — the exact collector lifecycle of
 * QuickReplySheet.kt. [StateRestorationTester.emulateSavedInstanceStateRestore] plays the
 * recreation: composition destroyed and rebuilt from saved state, while the test-held ViewModel
 * survives like its nav-entry-scoped production counterpart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickReplyRestorationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `recreation mid-submit re-mounts the sheet and consumes the success exactly once`() {
        val restorationTester = StateRestorationTester(composeTestRule)
        val repository = GatedSubmitRepository()
        val viewModel = restorationViewModel(repository)
        val submitted = mutableListOf<Pair<Int?, Int?>>()

        restorationTester.setContent {
            var quickReplyFor by rememberQuickReplyLaunch()
            Column {
                TextButton(
                    onClick = { quickReplyFor = QuickReplyLaunch(request = REQUEST) },
                    modifier = Modifier.testTag(OPEN_TAG),
                ) { Text("open") }
                quickReplyFor?.let { launch ->
                    // Sheet analog — the QuickReplySheet contract under test: a marker node for
                    // the mount, plus the effect collector keyed on the VM ALONE (pinned contract,
                    // QuickReplySheet.kt), torn down with the composition like the real sheet.
                    Box(modifier = Modifier.testTag(SHEET_TAG))
                    LaunchedEffect(viewModel) {
                        viewModel.onSheetOpened(launch.initialQuotes)
                        viewModel.effects.collect { effect ->
                            when (effect) {
                                is QuickReplyEffect.SubmitSucceeded -> {
                                    submitted += effect.targetPage to effect.scrollTo
                                    quickReplyFor = null
                                }
                                is QuickReplyEffect.EscalateToFullEditor -> Unit
                            }
                        }
                    }
                }
            }
        }

        // Open the sheet, then start a submit that stays in flight (the fake POST parks on a gate).
        composeTestRule.onNodeWithTag(OPEN_TAG).performClick()
        composeTestRule.onNodeWithTag(SHEET_TAG).assertExists()
        composeTestRule.waitForIdle()
        viewModel.onTextChanged(TextFieldValue("réponse en vol"))
        viewModel.onSubmitClicked()
        composeTestRule.waitForIdle()
        assertTrue("the POST must still be in flight", viewModel.state.value.isSubmitting)

        // Activity recreation while the POST is in flight.
        restorationTester.emulateSavedInstanceStateRestore()

        // The launch state is saveable: the sheet is RE-MOUNTED, its collector back up.
        composeTestRule.onNodeWithTag(SHEET_TAG).assertExists()

        // The POST completes after the recreation — the restored collector consumes the success
        // LEGITIMATELY: clean close + one single onSubmitted delivery.
        repository.submitGate.complete(Unit)
        composeTestRule.waitForIdle()
        assertEquals(listOf<Pair<Int?, Int?>>(TARGET_PAGE to SCROLL_TO), submitted)
        composeTestRule.onNodeWithTag(SHEET_TAG).assertDoesNotExist()

        // A later manual opening must not replay the effect: the sheet stays up, count stays 1.
        composeTestRule.onNodeWithTag(OPEN_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(SHEET_TAG).assertExists()
        assertEquals(1, submitted.size)
    }

    @Test
    fun `the saver round-trips a full launch across recreation`() {
        val restorationTester = StateRestorationTester(composeTestRule)
        val launch = QuickReplyLaunch(
            request = REQUEST,
            initialQuotes = listOf(
                QuoteSelection(
                    locator = QuoteLocator(page = 3, numreponse = 101, ref = 7),
                    author = "alice",
                    excerpt = "premier extrait",
                ),
                QuoteSelection(
                    locator = QuoteLocator(page = 4, numreponse = 202, ref = null),
                    author = "bob",
                    excerpt = "second extrait",
                ),
            ),
            consumesBasket = true,
        )
        var observed: QuickReplyLaunch? = null

        restorationTester.setContent {
            var quickReplyFor by rememberQuickReplyLaunch()
            TextButton(
                onClick = { quickReplyFor = launch },
                modifier = Modifier.testTag(OPEN_TAG),
            ) { Text("open") }
            observed = quickReplyFor
        }

        composeTestRule.onNodeWithTag(OPEN_TAG).performClick()
        composeTestRule.waitForIdle()
        assertEquals(launch, observed)

        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        assertEquals(launch, observed)
    }

    private fun restorationViewModel(repository: ReplyRepository): QuickReplyViewModel =
        QuickReplyViewModel(
            request = REQUEST,
            replyRepository = repository,
            quoteMaterializer = TopicReplyQuoteMaterializer(repository),
            draftStore = InMemoryDraftStore(),
            userPreferencesRepository = FakeUserPreferencesRepository(),
        )

    private companion object {
        const val OPEN_TAG = "quick_reply_restoration_open"
        const val SHEET_TAG = "quick_reply_restoration_sheet"
        val REQUEST = QuickReplyRequest(cat = 23, subcat = 401, topicId = 35421, page = 3)
    }
}

private const val TARGET_PAGE = 7
private const val SCROLL_TO = 4242

/** The POST parks on [submitGate] so the recreation can happen while it is in flight. */
private class GatedSubmitRepository : ReplyRepository {
    val submitGate = CompletableDeferred<Unit>()

    override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm = ReplyForm(
        hashCheck = "hash",
        sujet = "sujet",
        hiddenFields = emptyMap(),
        isAnonymous = false,
        initialContent = "",
    )

    override suspend fun submitReply(
        context: ReplyContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions,
    ): ReplySubmitResult {
        submitGate.await()
        return ReplySubmitResult.Success(refreshUrl = null, targetPage = TARGET_PAGE, numreponse = SCROLL_TO)
    }
}

private class InMemoryDraftStore : EditorDraftStore {
    private var storedBody: String? = null

    override suspend fun currentOwner(): String? = "xaat"

    override suspend fun load(owner: String?, key: String): EditorDraftStore.Draft? =
        storedBody?.let { EditorDraftStore.Draft(body = it) }

    override suspend fun save(owner: String?, key: String, draft: EditorDraftStore.Draft) {
        storedBody = draft.body
    }

    override suspend fun delete(owner: String?, key: String) {
        storedBody = null
    }
}
