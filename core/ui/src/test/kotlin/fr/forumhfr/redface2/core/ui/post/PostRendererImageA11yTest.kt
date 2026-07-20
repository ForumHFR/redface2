package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #958 (Lot 2) — the a11y contract of content images (annexe a11y #876, A11Y-1..5):
 *  - A11Y-2 : contentDescription = HFR alt when present, otherwise the localized
 *    `post_inline_image_alt` fallback (« [image] ») — never null, never the raw URL;
 *  - A11Y-5 : the cold/error slot is the SAME semantics node as the final image — a failed
 *    painter never removes the node from the reading order;
 *  - A11Y-1 : one node per image, document order;
 *  - A11Y-3/-4 : no phantom action — in particular NO retry action is announced anywhere
 *    (no host has a per-image retry callback; `mediaRefreshGeneration` is a screen-level
 *    counter, not a retry affordance), and null hosts announce nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererImageA11yTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val servedUrl = "https://rehost.diberie.com/Picture/Get/f/served.png"
    private val secondServedUrl = "https://rehost.diberie.com/Picture/Get/f/served2.png"
    private val deadUrl = "https://images.example.org/dead-host/photo.jpg"

    private val fallbackAlt: String =
        ApplicationProvider.getApplicationContext<Context>().getString(R.string.post_inline_image_alt)

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // deadUrl is NOT intercepted → Coil error result, the production failure mode.
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(servedUrl, ColorImage(0xFF2E7D32.toInt(), width = 80, height = 60))
            .intercept(secondServedUrl, ColorImage(0xFF6A1B9A.toInt(), width = 80, height = 60))
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    private fun setPost(vararg blocks: PostBlock, host: PostImageActions? = PostImageActions(onLongPress = {})) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                if (host != null) {
                    CompositionLocalProvider(LocalPostImageActions provides host) {
                        PostRenderer(content = PostContent(blocks = blocks.toList()), selectable = false)
                    }
                } else {
                    PostRenderer(content = PostContent(blocks = blocks.toList()), selectable = false)
                }
            }
        }
    }

    private fun paragraph(vararg inlines: PostInline) = PostBlock.Paragraph(inlines = inlines.toList())

    @Test
    fun `an inline image without alt falls back to the localized image contentDescription`() {
        setPost(
            paragraph(
                PostInline.Text("regarde "),
                PostInline.InlineImage(url = servedUrl, description = null),
            ),
        )

        composeTestRule.onNodeWithContentDescription(fallbackAlt).assertExists()
    }

    @Test
    fun `an inline image with a blank alt falls back to the localized image contentDescription`() {
        setPost(
            paragraph(
                PostInline.Text("regarde "),
                PostInline.InlineImage(url = servedUrl, description = "  "),
            ),
        )

        composeTestRule.onNodeWithContentDescription(fallbackAlt).assertExists()
    }

    @Test
    fun `a block image without alt falls back to the localized image contentDescription`() {
        setPost(PostBlock.Image(url = servedUrl, description = null))

        composeTestRule.onNodeWithContentDescription(fallbackAlt).assertExists()
    }

    @Test
    fun `a failed inline image keeps its semantics node and description - A11Y-5`() {
        setPost(
            paragraph(
                PostInline.Text("regarde "),
                PostInline.InlineImage(url = deadUrl, description = "morte"),
            ),
        )

        composeTestRule.onNodeWithContentDescription("morte").assertExists()
    }

    @Test
    fun `a failed block image keeps its semantics node with the error description - A11Y-5`() {
        // The block error slot swaps the description for the CONTRACTUAL error wording carrying
        // the alt (annexe a11y, États §6: post_image_error_with_alt) — the node itself survives.
        setPost(PostBlock.Image(url = deadUrl, description = "morte"))

        val errorWithAlt = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.post_image_error_with_alt, "morte")
        composeTestRule.onNodeWithText(errorWithAlt).assertExists()
    }

    @Test
    fun `content images keep the document order in the semantics tree even with a failure - A11Y-1`() {
        setPost(
            paragraph(
                PostInline.Text("a "),
                PostInline.InlineImage(url = servedUrl, description = "premiere"),
                PostInline.Text(" b "),
                PostInline.InlineImage(url = deadUrl, description = "deuxieme"),
                PostInline.Text(" c "),
                PostInline.InlineImage(url = secondServedUrl, description = "troisieme"),
            ),
        )

        val first = composeTestRule.onNodeWithContentDescription("premiere").getBoundsInRoot()
        val second = composeTestRule.onNodeWithContentDescription("deuxieme").getBoundsInRoot()
        val third = composeTestRule.onNodeWithContentDescription("troisieme").getBoundsInRoot()
        // Same line, reading order = visual order: strictly increasing left edges.
        assertTrue("failed image must keep its slot between its neighbours", first.left < second.left)
        assertTrue("failed image must keep its slot between its neighbours", second.left < third.left)
    }

    @Test
    fun `a failed image on a null host announces no action at all - A11Y-4`() {
        setPost(
            paragraph(
                PostInline.Text("regarde "),
                PostInline.Link(
                    url = "https://example.org/full",
                    children = listOf(PostInline.InlineImage(url = deadUrl, description = "morte")),
                ),
            ),
            host = null,
        )

        composeTestRule.onNodeWithContentDescription("morte")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun `no retry action is ever announced - A11Y-3`() {
        // No host has a per-image retry callback (annexe a11y, decision « retry »): a click
        // action labelled with the retry wording anywhere in the tree would be a phantom action.
        setPost(
            PostBlock.Image(url = deadUrl, description = "morte"),
            paragraph(PostInline.InlineImage(url = deadUrl, description = "morte aussi")),
        )

        val retryLabelled = SemanticsMatcher("has a retry-labelled click action") { node ->
            val click = node.config.getOrElseNullable(SemanticsActions.OnClick) { null }
            click?.label?.contains("Réessayer", ignoreCase = true) == true
        }
        composeTestRule.onAllNodes(retryLabelled).assertCountEquals(0)
    }
}
