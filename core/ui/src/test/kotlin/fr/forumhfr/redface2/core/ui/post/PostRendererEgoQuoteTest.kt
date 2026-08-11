package fr.forumhfr.redface2.core.ui.post

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.LocalBlockedQuoteAuthors
import fr.forumhfr.redface2.core.ui.theme.LocalEgoQuotePseudo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererEgoQuoteTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `top-level EgoQuote exposes its state description`() {
        setPost(content = content(quote(author = EGO_PSEUDO, text = "Réponse citée")))

        composeTestRule.onAllNodes(egoQuoteMatcher(), useUnmergedTree = true)
            .assertCountEquals(1)
    }

    @Test
    fun `folded long EgoQuote fades into the effective purple container`() {
        setPost(
            content = content(
                quote(
                    author = EGO_PSEUDO,
                    text = "Mur de texte assez long pour déclencher le repli et son fondu. ".repeat(12),
                ),
            ),
        )

        composeTestRule.onNodeWithTag(LONG_QUOTE_PREVIEW_TAG, useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    LongQuotePreviewFadeColorKey,
                    EGO_QUOTE_LIGHT,
                ),
            )
        composeTestRule.onNodeWithText("Déplier").assertExists()
    }

    @Test
    fun `EgoPost and folded EgoQuote expose both nested container colours`() {
        val longOwnQuote = quote(
            author = EGO_PSEUDO,
            text = "Mur de texte assez long pour déclencher le repli et son fondu. ".repeat(12),
        )
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    PostCardShell(
                        header = {},
                        body = {
                            CompositionLocalProvider(LocalEgoQuotePseudo provides EGO_CANONICAL) {
                                PostRenderer(content = content(longOwnQuote))
                            }
                        },
                        modifier = Modifier.testTag(EGO_POST_SHELL_TAG),
                        containerColorOverride = EGO_POST_LIGHT,
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(EGO_POST_SHELL_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PostCardShellContainerColorKey, EGO_POST_LIGHT))
        composeTestRule.onNodeWithTag(LONG_QUOTE_PREVIEW_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(LongQuotePreviewFadeColorKey, EGO_QUOTE_LIGHT))
    }

    @Test
    fun `revealed spoiler keeps a top-level own quote highlighted`() {
        setPost(
            content = content(
                PostBlock.Spoiler(
                    label = "Contenu masqué",
                    content = content(quote(author = EGO_PSEUDO, text = "citation sous spoiler")),
                ),
            ),
        )

        composeTestRule.onAllNodes(egoQuoteMatcher(), useUnmergedTree = true)
            .assertCountEquals(0)
        composeTestRule.onNodeWithText("(afficher)").performClick()
        composeTestRule.onNodeWithText("citation sous spoiler").assertExists()
        composeTestRule.onAllNodes(egoQuoteMatcher(), useUnmergedTree = true)
            .assertCountEquals(1)
    }

    @Test
    fun `revealing a depth-collapsed subtree keeps its nested own quote neutral`() {
        val ownQuoteBelowCollapsedDepth = quote(author = EGO_PSEUDO, text = "citation profonde de moi")
        val collapsed = quote(author = "Depth 3", child = ownQuoteBelowCollapsedDepth)
        val depth2 = quote(author = "Depth 2", child = collapsed)
        val depth1 = quote(author = "Depth 1", child = depth2)
        val depth0 = quote(author = "Depth 0", child = depth1)
        setPost(content = content(depth0))

        composeTestRule.onAllNodes(egoQuoteMatcher(), useUnmergedTree = true)
            .assertCountEquals(0)

        composeTestRule.onNodeWithText("Afficher").performClick()

        composeTestRule.onNodeWithText("citation profonde de moi", substring = true).assertExists()
        composeTestRule.onAllNodes(egoQuoteMatcher(), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun `revealing a blocked quote subtree keeps its nested own quote neutral`() {
        val nestedOwnQuote = quote(author = EGO_PSEUDO, text = "citation de moi sous blocage")
        val blockedRoot = quote(author = "Alice", child = nestedOwnQuote)
        setPost(content = content(blockedRoot), blockedCanonicals = setOf("alice"))

        composeTestRule.onNodeWithText("Citation de Alice masquée").assertExists()
        composeTestRule.onAllNodes(egoQuoteMatcher(), useUnmergedTree = true)
            .assertCountEquals(0)

        composeTestRule.onNodeWithText("Afficher").performClick()

        composeTestRule.onNodeWithText("citation de moi sous blocage").assertExists()
        composeTestRule.onAllNodes(egoQuoteMatcher(), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    private fun setPost(
        content: PostContent,
        blockedCanonicals: Set<String> = emptySet(),
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    CompositionLocalProvider(
                        LocalEgoQuotePseudo provides EGO_CANONICAL,
                        LocalBlockedQuoteAuthors provides blockedCanonicals,
                    ) {
                        PostRenderer(content = content)
                    }
                }
            }
        }
    }

    private fun egoQuoteMatcher(): SemanticsMatcher =
        SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            "Citation de votre message",
        )

    private fun content(block: PostBlock): PostContent = PostContent(blocks = listOf(block))

    private fun quote(author: String, text: String): PostBlock.Quote = PostBlock.Quote(
        author = author,
        numreponse = 42,
        page = 1,
        content = content(
            PostBlock.Paragraph(inlines = listOf(PostInline.Text(text))),
        ),
    )

    private fun quote(author: String, child: PostBlock.Quote): PostBlock.Quote = PostBlock.Quote(
        author = author,
        numreponse = 42,
        page = 1,
        content = content(child),
    )

    private companion object {
        const val EGO_PSEUDO = "XaTriX"
        const val EGO_CANONICAL = "xatrix"
        const val EGO_POST_SHELL_TAG = "ego-post-shell"
        val EGO_QUOTE_LIGHT = Color(0xFFEDE7FF)
        val EGO_POST_LIGHT = Color(0xFFE4EDFF)
    }
}
