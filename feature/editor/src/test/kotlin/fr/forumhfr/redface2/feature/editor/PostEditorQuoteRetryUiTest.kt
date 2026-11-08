package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp")
class PostEditorQuoteRetryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `existing banner offers reload and emits no submit or dismiss intent`() {
        val intents = mutableListOf<PostEditorIntent>()
        compose.setContent {
            RedfaceTheme(darkTheme = false) {
                Column {
                    PostEditorAlertBanners(
                        PostEditorState(
                            mode = PostEditorMode.Reply, cat = 23, topicId = 35421,
                            numreponse = null, page = 1, subcat = 0,
                            pendingQuotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "alice", "")),
                            submitError = EditorErrors.quoteFetchFailed,
                        ),
                        onIntent = intents::add,
                    )
                }
            }
        }
        compose.onNodeWithText("Citation non chargée.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Recharger").performClick()
        assertEquals(listOf(PostEditorIntent.ReloadRequested), intents)
    }

    @Test
    fun `banner adds explicit ignore alongside reload only after the same request failed twice`() {
        val intents = mutableListOf<PostEditorIntent>()
        var state by mutableStateOf(PostEditorState(
            mode = PostEditorMode.Reply, cat = 23, topicId = 35421, numreponse = null, page = 1, subcat = 0,
            pendingQuotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "alice", "")),
            quoteFetchFailures = 1, submitError = EditorErrors.quoteFetchFailed,
        ))
        compose.setContent {
            RedfaceTheme(darkTheme = false) {
                Column { PostEditorAlertBanners(state, onIntent = intents::add) }
            }
        }
        compose.onNodeWithText("Ignorer la citation").assertDoesNotExist()
        compose.onNodeWithText("Recharger").performClick()
        compose.runOnIdle { state = state.copy(quoteFetchFailures = 2) }
        compose.onNodeWithText("Recharger").assertIsDisplayed().performClick()
        compose.onNodeWithText("Citation toujours non chargée.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Ignorer la citation").performClick()
        assertEquals(
            listOf(
                PostEditorIntent.ReloadRequested,
                PostEditorIntent.ReloadRequested,
                PostEditorIntent.PendingQuoteIgnored,
            ),
            intents,
        )
        assertTrue(intents.none { it is PostEditorIntent.SubmitClicked || it == PostEditorIntent.ErrorDismissed })
    }

    @Test
    fun `restore offer explains the pending quote without offering a load action`() {
        val intents = mutableListOf<PostEditorIntent>()
        compose.setContent {
            RedfaceTheme(darkTheme = false) {
                Column {
                    PostEditorAlertBanners(
                        PostEditorState(
                            mode = PostEditorMode.Reply, cat = 23, topicId = 35421,
                            numreponse = null, page = 1, subcat = 0,
                            restorableDraft = "saved body",
                            pendingQuotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "", "")),
                            submitError = EditorErrors.quoteFetchFailed,
                        ),
                        onIntent = intents::add,
                    )
                }
            }
        }
        compose.onNodeWithText("Citation en attente").assertIsDisplayed()
        compose.onNodeWithText("Recharger").assertDoesNotExist()
        compose.onNodeWithText("Restaurer").assertIsEnabled().performClick()
        compose.onNodeWithText("Ignorer").assertIsEnabled().performClick()
        assertEquals(listOf(PostEditorIntent.DraftRestoreRequested, PostEditorIntent.DraftDiscardRequested), intents)
    }

    @Test
    fun `restore stays disabled until the edit seed is known including after a failed load`() {
        val intents = mutableListOf<PostEditorIntent>()
        var state by mutableStateOf(PostEditorState(
            mode = PostEditorMode.Edit, cat = 23, topicId = 35421, numreponse = 101, page = 1, subcat = 0,
            restorableDraft = "saved body", isLoadingForm = true,
        ))
        compose.setContent {
            RedfaceTheme(darkTheme = false) {
                Column { PostEditorAlertBanners(state, onIntent = intents::add) }
            }
        }
        compose.onNodeWithText("Restaurer").assertIsDisplayed().assertIsNotEnabled().performClick()
        compose.onNodeWithText("Ignorer").assertIsEnabled()
        assertTrue(intents.isEmpty())
        compose.runOnIdle { state = state.copy(isLoadingForm = false, draftRestoreSeed = "") }
        compose.onNodeWithText("Restaurer").assertIsEnabled().performClick()
        assertEquals(listOf(PostEditorIntent.DraftRestoreRequested), intents)
    }

    @Test
    fun `dormant quote exposes both explicit decisions without submitting or dismissing uncertainty`() {
        val intents = mutableListOf<PostEditorIntent>()
        compose.setContent {
            RedfaceTheme(darkTheme = false) {
                Column {
                    PostEditorAlertBanners(
                        PostEditorState(
                            mode = PostEditorMode.Reply, cat = 23, topicId = 35421,
                            numreponse = null, page = 1, subcat = 0,
                            pendingQuotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "", "")),
                            submission = EditorSubmissionState.Uncertain(AttemptToken("tester", "key", "id", 1)),
                            submitError = EditorErrors.uncertain,
                        ),
                        onIntent = intents::add,
                    )
                }
            }
        }
        compose.onNodeWithText("Après vérification, reprendre la citation").performClick()
        compose.onNodeWithText("Ignorer la citation").performClick()
        assertEquals(listOf(PostEditorIntent.PendingQuoteResumed, PostEditorIntent.PendingQuoteIgnored), intents)
    }

    @Test
    fun `Astra1 dormant decisions remain present independently of submitError`() {
        val intents = mutableListOf<PostEditorIntent>()
        var state by mutableStateOf(PostEditorState(
            mode = PostEditorMode.Reply, cat = 23, topicId = 35421, numreponse = null, page = 1, subcat = 0,
            pendingQuotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "", "")),
            submission = EditorSubmissionState.Uncertain(AttemptToken("tester", "key", "id", 1)),
            submitError = EditorErrors.draftSaveFailed,
        ))
        compose.setContent {
            RedfaceTheme(darkTheme = false) { Column { PostEditorAlertBanners(state, intents::add) } }
        }
        compose.onNodeWithText("Après vérification, reprendre la citation").assertIsEnabled()
        compose.onNodeWithText("Ignorer la citation").assertIsEnabled()
        compose.runOnIdle { state = state.copy(submitError = null) }
        compose.onNodeWithText("Après vérification, reprendre la citation").performClick()
        compose.onNodeWithText("Ignorer la citation").performClick()
        assertEquals(listOf(PostEditorIntent.PendingQuoteResumed, PostEditorIntent.PendingQuoteIgnored), intents)
    }

    @Test
    fun `M1 unreadable request offers explicit ignore with no decoded coordinates`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false) {
                Column {
                    PostEditorAlertBanners(
                        PostEditorState(PostEditorMode.Reply, 23, 35421, null, 1, 0,
                            unreadableQuoteRequest = true, quoteStorageUnavailable = true,
                            submitError = EditorErrors.unreadableQuoteRequest),
                        onIntent = {},
                    )
                }
            }
        }
        compose.onNodeWithText("La demande de citation enregistrée est illisible.", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Ignorer la citation").assertIsEnabled()
        compose.onNodeWithText("Recharger").assertIsEnabled()
    }

    @Test
    fun `v20 transition explicitly tells the user to select the quote again`() {
        val intents = mutableListOf<PostEditorIntent>()
        compose.setContent {
            RedfaceTheme(darkTheme = false) {
                Column {
                    PostEditorAlertBanners(
                        PostEditorState(
                            mode = PostEditorMode.Reply, cat = 23, topicId = 35421,
                            numreponse = null, page = 1, subcat = 0, legacyQuoteRequestDropped = true,
                        ),
                        onIntent = intents::add,
                    )
                }
            }
        }
        compose.onNodeWithText("Veuillez sélectionner à nouveau le message à citer.", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Masquer").performClick()
        assertEquals(listOf(PostEditorIntent.LegacyQuoteNoticeDismissed), intents)
    }

}
