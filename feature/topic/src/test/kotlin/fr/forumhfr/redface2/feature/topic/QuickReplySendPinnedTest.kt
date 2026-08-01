package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.write.QuotedPostPreview
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.QuoteCardsCallbacks
import fr.forumhfr.redface2.core.ui.editor.QuoteCardsColumn
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #855 — « Envoyer » is PINNED below the scrolling fields, never behind the keyboard fold. This
 * mounts the exact quick-reply layout skeleton (scrollable weight(1f, fill = false) fields column
 * + pinned send row, same card cap) inside a SHORT window — the h480dp qualifier plays the role
 * of the IME-shrunk viewport — and pins the contract : however tall the cards + field grow, the
 * send button stays inside the visible bounds without any scrolling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h480dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickReplySendPinnedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `send button stays visible on a short window despite five cards`() {
        val quotes = (1..5).map { n ->
            QuotedPostPreview(numreponse = n, author = "author$n", excerpt = "excerpt $n")
        }
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Réponse rapide")
                        QuoteCardsColumn(
                            quotes = quotes,
                            enabled = true,
                            callbacks = QuoteCardsCallbacks({}, {}, {}),
                            modifier = Modifier
                                .heightIn(max = QUICK_REPLY_MAX_CARDS_HEIGHT)
                                .verticalScroll(rememberScrollState()),
                        )
                        OutlinedTextField(
                            value = "brouillon",
                            onValueChange = {},
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {}, modifier = Modifier.testTag(SEND_TAG)) {
                            Text("Envoyer")
                        }
                    }
                }
            }
        }

        val send = composeTestRule.onNodeWithTag(SEND_TAG, useUnmergedTree = true)
        send.assertIsDisplayed()
        val sendBounds = send.fetchSemanticsNode().boundsInRoot
        val rootBounds = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "send button bottom ${sendBounds.bottom} overflows the ${rootBounds.bottom} window",
            sendBounds.bottom <= rootBounds.bottom + 1f,
        )
    }

    private companion object {
        const val SEND_TAG = "quick_reply_send_under_test"
    }
}
