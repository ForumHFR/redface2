package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.PostListScaffold
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #884 — MOUNTED geometry of the topic-list helpers (vague 3), on the real [PostListScaffold]
 * (nothing mounts TopicLoadedContent — this is the lowest mountable level). Contract Sol: in
 * full-width mode a post item bleeds edge to edge with NO gap to the next item, while an island
 * (any non-post item, wrapped by `Modifier.islandPadding`) keeps ~8.dp of local breathing room —
 * compensating the list gutter and rhythm it lost. Card mode stays byte-identical (gutter 8.dp,
 * gap 8.dp, identity islandPadding).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TopicListFullWidthGeometryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun mountList(fullWidth: Boolean) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostListScaffold(
                    listState = LazyListState(),
                    contentPadding = topicListContentPadding(fullWidthPosts = fullWidth),
                    verticalArrangement = topicListArrangement(fullWidthPosts = fullWidth),
                ) {
                    item {
                        Box(
                            Modifier
                                .islandPadding(fullWidthPosts = fullWidth)
                                .fillMaxWidth()
                                .height(ITEM_HEIGHT)
                                .testTag("island"),
                        )
                    }
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(ITEM_HEIGHT)
                                .testTag("post"),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `full width - posts bleed edge to edge while islands keep a local inset`() {
        mountList(fullWidth = true)

        // Island: 8.dp horizontal inset of its own, 4.dp above and below (16 list top + 4).
        composeTestRule.onNodeWithTag("island")
            .assertLeftPositionInRootIsEqualTo(8.dp)
            .assertWidthIsEqualTo(360.dp - 16.dp)
            .assertTopPositionInRootIsEqualTo(16.dp + 4.dp)
        // Post: full bleed, and NO list gap — it starts right at the island item's bottom edge
        // (16 list top + island item = 4 + 40 + 4).
        composeTestRule.onNodeWithTag("post")
            .assertLeftPositionInRootIsEqualTo(0.dp)
            .assertWidthIsEqualTo(360.dp)
            .assertTopPositionInRootIsEqualTo(16.dp + 4.dp + ITEM_HEIGHT + 4.dp)
    }

    @Test
    fun `card mode - the historical gutters and rhythm are untouched`() {
        mountList(fullWidth = false)

        // Both items sit inside the 8.dp list gutter, islandPadding being identity.
        composeTestRule.onNodeWithTag("island")
            .assertLeftPositionInRootIsEqualTo(8.dp)
            .assertWidthIsEqualTo(360.dp - 16.dp)
            .assertTopPositionInRootIsEqualTo(16.dp)
        // The 8.dp spacedBy rhythm separates the two items (16 top + 40 island + 8 gap).
        composeTestRule.onNodeWithTag("post")
            .assertLeftPositionInRootIsEqualTo(8.dp)
            .assertWidthIsEqualTo(360.dp - 16.dp)
            .assertTopPositionInRootIsEqualTo(16.dp + ITEM_HEIGHT + 8.dp)
    }

    private companion object {
        val ITEM_HEIGHT = 40.dp
    }
}
