package fr.forumhfr.redface2.feature.topic

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.POST_CARD_SHELL_DIVIDER_TAG
import fr.forumhfr.redface2.core.ui.post.PostCardShellFlatBottomEdge
import fr.forumhfr.redface2.core.ui.theme.LocalFoldLongQuotes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * #884 — the « posts en pleine largeur » wiring on [TopicPostCard] (vague 3):
 *  - `flat = true` renders the SAME card boundary-less — full-bleed to the parent's edges, closed
 *    by the shell hairline — while the INTERNAL text gutters (header 12.dp, body 12.dp) stay
 *    untouched; `flat = false` stays the historical card (no divider);
 *  - flipping `flat` at runtime must NOT recreate the post subtree: an expanded long quote
 *    (`rememberSaveable` in PostRenderer) stays expanded across the toggle (garde Sol — same
 *    invariant family as `TopicZoomQuoteFoldTest`/#946);
 *  - resolution of the vague-2 a11y caveat: each post exposes EXACTLY ONE TalkBack heading,
 *    carried by the REAL pseudo text node (gold-sheen creator variant and plain variant alike) —
 *    not by a generic wrapper around the slot, which would double the heading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TopicPostCardFullWidthTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `flat card bleeds edge to edge, keeps its text gutters and closes with the divider`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPostCard(
                    post = samplePost(author = "Lt Ripley", content = paragraphContent()),
                    citedCount = 0,
                    onQuote = null,
                    onEdit = null,
                    flat = true,
                )
            }
        }

        // Full bleed: the card node (the shell's traversal group) spans the whole 360.dp parent…
        composeTestRule
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true),
                useUnmergedTree = true,
            )
            .assertLeftPositionInRootIsEqualTo(0.dp)
            .assertWidthIsEqualTo(360.dp)
        // …and the closing hairline spans it too (zero-gutter separation between posts).
        composeTestRule.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertLeftPositionInRootIsEqualTo(0.dp)
            .assertWidthIsEqualTo(360.dp)
        // The INTERNAL gutters are unchanged (contrainte dure #884): header avatar at 12.dp,
        // body text at 12.dp (Comfort preset) — the flat mode keeps the validated text gutters.
        composeTestRule.onNodeWithContentDescription("Avatar de Lt Ripley")
            .assertLeftPositionInRootIsEqualTo(12.dp)
        composeTestRule.onNodeWithText(BODY_TEXT, useUnmergedTree = true)
            .assertLeftPositionInRootIsEqualTo(12.dp)
    }

    @Test
    fun `flat card leaves its bottom edge when the list says something else closes it`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPostCard(
                    post = samplePost(author = "Lt Ripley", content = paragraphContent()),
                    citedCount = 0,
                    onQuote = null,
                    onEdit = null,
                    flat = true,
                    flatBottomEdge = PostCardShellFlatBottomEdge.NONE,
                )
            }
        }

        // #983 — the wiring of the sequence decision down to the shell: the card still bleeds edge
        // to edge, but draws no hairline (the following separator / island brings the boundary).
        composeTestRule.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true),
                useUnmergedTree = true,
            )
            .assertLeftPositionInRootIsEqualTo(0.dp)
            .assertWidthIsEqualTo(360.dp)
    }

    @Test
    fun `default card renders no divider`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPostCard(
                    post = samplePost(author = "Lt Ripley", content = paragraphContent()),
                    citedCount = 0,
                    onQuote = null,
                    onEdit = null,
                )
            }
        }

        // flat defaults to false: the historical card rendering, no hairline sneaks in.
        composeTestRule.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `an expanded long quote survives the flat toggle`() {
        // Garde Sol — flipping the preference mid-read must not throw away rememberSaveable state
        // below the card (the long-quote fold in PostRenderer, cf. #946/TopicZoomQuoteFoldTest).
        val flat = mutableStateOf(false)
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalFoldLongQuotes provides true) {
                    TopicPostCard(
                        post = samplePost(author = "Lt Ripley", content = longQuoteContent()),
                        citedCount = 0,
                        onQuote = null,
                        onEdit = null,
                        flat = flat.value,
                    )
                }
            }
        }

        // Expand the folded long quote.
        composeTestRule.onNodeWithText("Déplier", substring = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Replier", substring = true).assertExists()

        // Flip to full-width at runtime.
        flat.value = true
        composeTestRule.waitForIdle()

        // The mode really flipped (the shell hairline appeared)…
        composeTestRule.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertExists()
        // …and the fold state survived: the quote is still expanded.
        composeTestRule.onNodeWithText("Replier", substring = true).assertExists()
    }

    @Test
    fun `plain pseudo - the card exposes exactly one heading, on the pseudo text node`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPostCard(
                    post = samplePost(author = "Lt Ripley"),
                    citedCount = 0,
                    onQuote = null,
                    onEdit = null,
                )
            }
        }

        assertSingleHeadingOnPseudo("Lt Ripley")
    }

    @Test
    fun `creator pseudo - the gold-sheen variant carries the same single heading`() {
        // "XaTriX" routes through CreatorPseudoText (#221) — the OTHER pseudo branch of
        // TopicPostIdentityHeader; both must carry the heading on their own text node.
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPostCard(
                    post = samplePost(author = "XaTriX"),
                    citedCount = 0,
                    onQuote = null,
                    onEdit = null,
                )
            }
        }

        assertSingleHeadingOnPseudo("XaTriX")
    }

    private fun assertSingleHeadingOnPseudo(author: String) {
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        // The heading rides on the pseudo text node itself (best TalkBack target)…
        composeTestRule
            .onNode(heading and hasText(author), useUnmergedTree = true)
            .assertExists()
        // …and it is the ONLY heading of the card — no generic wrapper doubling the announcement.
        composeTestRule
            .onAllNodes(heading, useUnmergedTree = true)
            .assertCountEquals(1)
    }

    private fun samplePost(
        author: String,
        content: PostContent = PostContent(blocks = emptyList()),
    ): Post = Post(
        numreponse = 16244,
        author = author,
        date = Instant.EPOCH,
        content = content,
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
        quoteRef = 1,
        profileId = null,
    )

    private fun paragraphContent(): PostContent = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(inlines = listOf(PostInline.Text(BODY_TEXT))),
        ),
    )

    private fun longQuoteContent(): PostContent {
        val longText = buildString {
            repeat(30) { append("Ligne de citation numéro $it assez longue pour dépasser le seuil. ") }
        }
        return PostContent(
            blocks = listOf(
                PostBlock.Quote(
                    author = "tinc",
                    numreponse = 42,
                    page = 1,
                    content = PostContent(
                        blocks = listOf(
                            PostBlock.Paragraph(inlines = listOf(PostInline.Text(longText))),
                        ),
                    ),
                ),
                PostBlock.Paragraph(inlines = listOf(PostInline.Text("réponse au-dessous"))),
            ),
        )
    }

    private companion object {
        const val BODY_TEXT = "Corps du post pour mesurer la gouttière interne."
    }
}
