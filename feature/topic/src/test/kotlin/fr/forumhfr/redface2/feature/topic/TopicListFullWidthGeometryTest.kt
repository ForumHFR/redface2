package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

    /**
     * #983 — a post item that also renders the « Dernier message lu » separator. The reported defect
     * was the asymmetry: the item's own `spacedBy(8.dp)` above the separator against the list's
     * `Arrangement.Top` (0.dp) below it. Here no container inserts a gap and the separator carries
     * its two 4.dp half-gaps itself, so the rhythm is symmetric — and it stays TRAVERSING (edge to
     * edge like the posts it cuts through, unlike an island card which re-insets by 8.dp).
     */
    private fun mountPostWithSeparator(fullWidth: Boolean) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostListScaffold(
                    listState = LazyListState(),
                    contentPadding = topicListContentPadding(fullWidthPosts = fullWidth),
                    verticalArrangement = topicListArrangement(fullWidthPosts = fullWidth),
                ) {
                    item {
                        Column(
                            verticalArrangement = topicPostChildrenArrangement(
                                fullWidthPosts = fullWidth,
                            ),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(ITEM_HEIGHT)
                                    .testTag("post-with-separator"),
                            )
                            Box(
                                Modifier
                                    .separatorPadding(fullWidthPosts = fullWidth)
                                    .fillMaxWidth()
                                    .height(SEPARATOR_HEIGHT)
                                    .testTag("separator"),
                            )
                        }
                    }
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(ITEM_HEIGHT)
                                .testTag("post-after"),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `full width - the nested separator is symmetric and stays traversing`() {
        mountPostWithSeparator(fullWidth = true)

        // 16 list top, then the post.
        composeTestRule.onNodeWithTag("post-with-separator")
            .assertTopPositionInRootIsEqualTo(16.dp)
        // 4.dp ABOVE the separator (its own half-gap, no container gap), and full bleed: a
        // traversing rule, NOT an island — no 8.dp horizontal inset.
        composeTestRule.onNodeWithTag("separator")
            .assertTopPositionInRootIsEqualTo(16.dp + ITEM_HEIGHT + 4.dp)
            .assertLeftPositionInRootIsEqualTo(0.dp)
            .assertWidthIsEqualTo(360.dp)
        // …and 4.dp BELOW it: 16 + 40 + 4 + 10 + 4. This equality with the value above IS the fix.
        composeTestRule.onNodeWithTag("post-after")
            .assertTopPositionInRootIsEqualTo(16.dp + ITEM_HEIGHT + 4.dp + SEPARATOR_HEIGHT + 4.dp)
    }

    @Test
    fun `card mode - the separator keeps the historical 8dp rhythm inside the gutter`() {
        mountPostWithSeparator(fullWidth = false)

        // separatorPadding is identity here: the item's own spacedBy(8.dp) places the separator,
        // the list's 8.dp rhythm places the next item, and the 8.dp gutter insets both.
        composeTestRule.onNodeWithTag("separator")
            .assertTopPositionInRootIsEqualTo(16.dp + ITEM_HEIGHT + 8.dp)
            .assertLeftPositionInRootIsEqualTo(8.dp)
            .assertWidthIsEqualTo(360.dp - 16.dp)
        composeTestRule.onNodeWithTag("post-after")
            .assertTopPositionInRootIsEqualTo(16.dp + ITEM_HEIGHT + 8.dp + SEPARATOR_HEIGHT + 8.dp)
    }

    @Test
    fun `full width - two consecutive islands add up to the lost 8dp rhythm`() {
        // #983 (gate Sol) — the island↔island seam is NOT driven by the hairline decision (no post
        // sits there): it is the sum of two 4.dp half-gaps. Pinned so the halves can never be
        // re-tuned into an asymmetric pair, which is the defect class this issue is about.
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostListScaffold(
                    listState = LazyListState(),
                    contentPadding = topicListContentPadding(fullWidthPosts = true),
                    verticalArrangement = topicListArrangement(fullWidthPosts = true),
                ) {
                    repeat(2) { index ->
                        item {
                            Box(
                                Modifier
                                    .islandPadding(fullWidthPosts = true)
                                    .fillMaxWidth()
                                    .height(ITEM_HEIGHT)
                                    .testTag("island$index"),
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("island0")
            .assertTopPositionInRootIsEqualTo(16.dp + 4.dp)
        // 16 list top + 4 + 40 + 4 (first island's lower half) + 4 (second island's upper half).
        composeTestRule.onNodeWithTag("island1")
            .assertTopPositionInRootIsEqualTo(16.dp + 4.dp + ITEM_HEIGHT + 4.dp + 4.dp)
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

        /** #983 — stands for the « Dernier message lu » rule (a Row of 2.dp dividers + a pill). */
        val SEPARATOR_HEIGHT = 10.dp
    }
}
