package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import org.junit.Assert.assertEquals
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
}
