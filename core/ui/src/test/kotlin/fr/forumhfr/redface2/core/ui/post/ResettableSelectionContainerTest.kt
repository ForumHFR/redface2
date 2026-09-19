package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResettableSelectionContainerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `epoch recreates the selection owner but preserves remembered post content`() {
        var epoch by mutableIntStateOf(0)
        compose.setContent {
            ResettableSelectionContainer(
                selectionEpoch = epoch,
                modifier = Modifier.testTag(CONTAINER_TAG),
            ) {
                var childState by remember { mutableIntStateOf(0) }
                Text(
                    text = childState.toString(),
                    modifier = Modifier
                        .testTag(CONTENT_TAG)
                        .clickable { childState += 1 },
                )
            }
        }
        val firstOwnerId = compose.onNodeWithTag(CONTAINER_TAG).fetchSemanticsNode().id
        compose.onNodeWithTag(CONTENT_TAG).performClick().assertTextEquals("1")

        compose.runOnIdle { epoch += 1 }

        val secondOwnerId = compose.onNodeWithTag(CONTAINER_TAG).fetchSemanticsNode().id
        assertNotEquals("the SelectionContainer must be recreated", firstOwnerId, secondOwnerId)
        compose.onNodeWithTag(CONTENT_TAG).assertTextEquals("1")
    }

    private companion object {
        const val CONTAINER_TAG = "selection-owner"
        const val CONTENT_TAG = "remembered-content"
    }
}
