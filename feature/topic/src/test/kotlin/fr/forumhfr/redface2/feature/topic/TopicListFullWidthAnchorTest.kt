package fr.forumhfr.redface2.feature.topic

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.POST_CARD_SHELL_DIVIDER_TAG
import fr.forumhfr.redface2.core.ui.post.PostListScaffold
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * #884 — anchor guard on the « posts en pleine largeur » DYNAMIC toggle (review Sol r4), at the
 * lowest mountable level (nothing mounts TopicLoadedContent — same stance as
 * [TopicListFullWidthGeometryTest]): the real [PostListScaffold] hosting the real list structure —
 * the unconditional zero-size lead slot, real [TopicPostCard]s keyed by `numreponse` (each in the
 * per-item Column that also hosts the [LastReadMarker] below the last-read post), and the real
 * [PageBoundaryCard] as the closing island. Contract: flipping the preference mid-read recomposes
 * the geometry (contentPadding/arrangement/flat via the `TopicListLayout.kt` helpers) WITHOUT
 * moving the reading position — the scrolled-to post stays the first visible item (same index AND
 * key, still at its top) — and the marker + boundary card survive the flip in reading order. The
 * boundary is checked by a second scroll to the list tail: pinning the anchor at the top requires
 * more content below it than the viewport holds, so the closing island is necessarily off-screen
 * at anchor time (Lazy composes visible items only — an off-screen node has no bounds to assert).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TopicListFullWidthAnchorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `flipping full-width mid-scroll keeps the anchor item and the marker-boundary order`() {
        val fullWidth = mutableStateOf(false)
        val listState = LazyListState()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostListScaffold(
                    listState = listState,
                    contentPadding = topicListContentPadding(fullWidthPosts = fullWidth.value),
                    verticalArrangement = topicListArrangement(fullWidthPosts = fullWidth.value),
                ) {
                    // Lead slot — occupies index 0 unconditionally, zero-size on a poll-less
                    // topic (the index invariant of the real list, cf. TopicLoadedContent).
                    item { }
                    items(items = posts, key = { post -> post.numreponse }) { post ->
                        // Same per-item structure as the real list: a Column hosting the card
                        // and, below the last-read post, the traversing marker (#600 — INSIDE
                        // the post's item, never an extra list item).
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TopicPostCard(
                                post = post,
                                citedCount = 0,
                                onQuote = null,
                                onEdit = null,
                                flat = fullWidth.value,
                            )
                            if (post.numreponse == LAST_READ_NUMREPONSE) {
                                LastReadMarker()
                            }
                        }
                    }
                    // Intermediate-page boundary — a keyless positional sentinel, padded as an
                    // island in full-width mode (cf. TopicLoadedContent).
                    item {
                        PageBoundaryCard(
                            donePage = DONE_PAGE,
                            onNextPage = {},
                            modifier = Modifier.islandPadding(fullWidthPosts = fullWidth.value),
                        )
                    }
                }
            }
        }

        // Scroll to the last-read post (index 4 = key 104: lead slot at 0, posts from 1). The 8
        // posts + boundary below it overfill the 780.dp viewport, so the target really pins at
        // the top — no end-of-list clamp leaving the previous item peeking above it.
        composeTestRule.runOnIdle { runBlocking { listState.scrollToItem(TARGET_INDEX) } }
        composeTestRule.waitForIdle()

        // The anchor is the LazyList scroll position itself (firstVisibleItemIndex/offset — what
        // Lazy carries across relayouts), NOT `visibleItemsInfo.first()`: the viewport starts at
        // -beforeContentPadding (-16.dp here), so the info list also carries the PREVIOUS item
        // peeking through the top-padding band above the anchor (the 8.dp card gap < 16.dp).
        val before = listState.layoutInfo.visibleItemsInfo.filter { it.offset >= 0 }
        assertEquals(TARGET_INDEX, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
        assertEquals(TARGET_INDEX, before.first().index)
        assertEquals(LAST_READ_NUMREPONSE, before.first().key)
        assertMarkerRidesBelowItsPost()

        // Flip « posts en pleine largeur » mid-read.
        fullWidth.value = true
        composeTestRule.waitForIdle()

        // The flip really landed: the flat shell hairline appeared on the (now full-bleed) cards…
        composeTestRule
            .onAllNodesWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .onFirst()
            .assertExists()
        // …and the reading position did NOT jump: same anchor item, index AND key, still pinned
        // at its top edge (same anchored-items capture as above).
        val after = listState.layoutInfo.visibleItemsInfo.filter { it.offset >= 0 }
        assertEquals(TARGET_INDEX, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
        assertEquals(before.first().index, after.first().index)
        assertEquals(before.first().key, after.first().key)
        // The items below it kept their keys in order — full-width only removes gutters and gaps,
        // so the visible window may only GROW at its bottom edge, never reshuffle.
        assertEquals(before.map { it.key }, after.map { it.key }.take(before.size))
        // The marker survived the flip, still riding below the SAME post.
        assertMarkerRidesBelowItsPost()

        // Tail check, still in full-width mode: the boundary card is the closing island.
        composeTestRule.runOnIdle { runBlocking { listState.scrollToItem(BOUNDARY_INDEX) } }
        composeTestRule.waitForIdle()

        assertEquals(BOUNDARY_INDEX, listState.layoutInfo.visibleItemsInfo.last().index)
        val boundary = boundaryNode()
        boundary.assertIsDisplayed()
        // Reading order: the boundary closes the list, below the last post's body…
        val lastPostBodyTop = composeTestRule
            .onNodeWithText(bodyText(LAST_NUMREPONSE), useUnmergedTree = true)
            .getBoundsInRoot().top
        assertTrue(
            "the boundary card must close the list, below the last post",
            lastPostBodyTop < boundary.getBoundsInRoot().top,
        )
        // …and it kept its island inset (8.dp) while the posts around it bleed full width.
        boundary.assertLeftPositionInRootIsEqualTo(8.dp)
    }

    /**
     * The [LastReadMarker] is displayed and reads in list order: below the body of the
     * [LAST_READ_NUMREPONSE] post it belongs to.
     */
    private fun assertMarkerRidesBelowItsPost() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val marker = composeTestRule
            .onNodeWithText(context.getString(R.string.topic_last_read_marker))
        marker.assertIsDisplayed()
        val anchorBodyTop = composeTestRule
            .onNodeWithText(bodyText(LAST_READ_NUMREPONSE), useUnmergedTree = true)
            .getBoundsInRoot().top
        assertTrue(
            "the marker must sit below its post's body",
            anchorBodyTop < marker.getBoundsInRoot().top,
        )
    }

    private fun boundaryNode() = composeTestRule.onNodeWithText(
        ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.topic_page_boundary_done, DONE_PAGE),
    )

    private val posts = (FIRST_NUMREPONSE..LAST_NUMREPONSE).map { numreponse ->
        Post(
            numreponse = numreponse,
            author = "Auteur $numreponse",
            date = Instant.EPOCH,
            content = PostContent(
                blocks = listOf(
                    PostBlock.Paragraph(inlines = listOf(PostInline.Text(bodyText(numreponse)))),
                ),
            ),
            avatarUrl = null,
            isEditable = false,
            isOwnPost = false,
            quotedAuthors = emptyList(),
            postIndex = null,
            quoteRef = 1,
            profileId = null,
        )
    }

    private fun bodyText(numreponse: Int): String = "Corps du post $numreponse."

    private companion object {
        /** 12 posts + lead slot + boundary card = 14 list items, like a plausible real page. */
        const val FIRST_NUMREPONSE = 101
        const val LAST_NUMREPONSE = 112

        /** The scroll target — the post carrying the [LastReadMarker] below it. */
        const val LAST_READ_NUMREPONSE = 104

        /** List index of [LAST_READ_NUMREPONSE]: the lead slot owns index 0, posts start at 1. */
        const val TARGET_INDEX = 4

        /** List index of the closing [PageBoundaryCard]: lead slot + the 12 posts before it. */
        const val BOUNDARY_INDEX = 13

        const val DONE_PAGE = 3
    }
}
