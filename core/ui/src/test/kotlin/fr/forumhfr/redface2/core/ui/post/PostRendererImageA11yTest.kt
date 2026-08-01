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
 *  - A11Y-3/-4 : no phantom action — every announced action is effective. Since #960 P3 the
 *    error slot DOES announce a per-URL retry (universal, ledger-backed — see
 *    PostRendererErrorRetryTest); the phantom-action pin here covers the non-error states.
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
    fun `a failed inline image keeps its semantics node with the error description - A11Y-5`() {
        // #960 P3 — the inline error slot swaps the description for the CONTRACTUAL error
        // wording carrying the alt (annexe a11y, États §6) — the node itself survives.
        setPost(
            paragraph(
                PostInline.Text("regarde "),
                PostInline.InlineImage(url = deadUrl, description = "morte"),
            ),
        )

        val errorWithAlt = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.post_image_error_with_alt, "morte")
        composeTestRule.onNodeWithContentDescription(errorWithAlt).assertExists()
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

        val errorSecond = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.post_image_error_with_alt, "deuxieme")
        val first = composeTestRule.onNodeWithContentDescription("premiere").getBoundsInRoot()
        // #960 P3 — the failed slot carries the error description (annexe a11y, États §6).
        val second = composeTestRule.onNodeWithContentDescription(errorSecond).getBoundsInRoot()
        val third = composeTestRule.onNodeWithContentDescription("troisieme").getBoundsInRoot()
        // Same line, reading order = visual order: strictly increasing left edges.
        assertTrue("failed image must keep its slot between its neighbours", first.left < second.left)
        assertTrue("failed image must keep its slot between its neighbours", second.left < third.left)
    }

    @Test
    fun `a failed image on a null host announces the retry and nothing else - A11Y-4`() {
        // #960 P3 — the error slot's SINGLE action is the universal per-URL retry (Role.Button
        // « Réessayer ») — even on a null host, and even for a LINKED image: the §5 matrix
        // (link tap, long-press menu) gates a RENDERED image, never the error slot.
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

        val errorWithAlt = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.post_image_error_with_alt, "morte")
        val node = composeTestRule.onNodeWithContentDescription(errorWithAlt)
        node.assert(
            SemanticsMatcher("click action labelled with the retry wording") { n ->
                n.config.getOrElseNullable(SemanticsActions.OnClick) { null }
                    ?.label?.contains("Réessayer", ignoreCase = true) == true
            },
        )
        node.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun `no retry action is announced on HEALTHY images - A11Y-3`() {
        // #960 P3 inverted the Lot 2 « never announced » pin for the ERROR slot only (per-URL
        // ledger retry, universal — PostRendererErrorRetryTest). A healthy image must still
        // announce NO retry: the wording on a working image would be a phantom action.
        setPost(
            PostBlock.Image(url = servedUrl, description = "servie"),
            paragraph(PostInline.InlineImage(url = secondServedUrl, description = "servie aussi")),
        )

        val retryLabelled = SemanticsMatcher("has a retry-labelled click action") { node ->
            val click = node.config.getOrElseNullable(SemanticsActions.OnClick) { null }
            click?.label?.contains("Réessayer", ignoreCase = true) == true
        }
        composeTestRule.onAllNodes(retryLabelled).assertCountEquals(0)
    }
}
