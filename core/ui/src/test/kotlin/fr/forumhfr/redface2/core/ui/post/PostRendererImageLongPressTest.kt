package fr.forumhfr.redface2.core.ui.post

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #831 — pins the long-press plumbing of the image contextual menu on BOTH image render paths
 * (inline `[img]` inside the selectable Text, and block image), and the two OFF states:
 * no [LocalPostImageActions] provided (MP / editor preview / signatures) and an ineligible URL
 * (`data:`). The gesture must reach the handler even under the `selectable = true`
 * SelectionContainer (#281) — the very interception #831 is about. The fine finger-level
 * interplay with selection drags is dogfooded on the emulator at integration time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererImageLongPressTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val inlineUrl = "https://rehost.diberie.com/Picture/Get/f/inline.png"
    private val blockUrl = "https://rehost.diberie.com/Picture/Get/f/block.png"

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(inlineUrl, ColorImage(0xFF2E7D32.toInt(), width = 80, height = 60))
            .intercept(blockUrl, ColorImage(0xFF1565C0.toInt(), width = 400, height = 300))
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    private fun inlineImageContent(url: String) = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(
                inlines = listOf(
                    PostInline.Text("regarde "),
                    PostInline.InlineImage(url = url, description = "photo"),
                    PostInline.Text(" !"),
                ),
            ),
        ),
    )

    @Test
    fun `long press on an inline image reaches the provided handler with the image URL`() {
        var received: PostImageTarget? = null
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = { received = it }),
                ) {
                    PostRenderer(content = inlineImageContent(inlineUrl), selectable = true)
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("photo")
            .performTouchInput { longClick() }

        assertEquals(inlineUrl, received?.url)
        assertEquals("photo", received?.description)
        assertNull(received?.linkUrl)
    }

    @Test
    fun `long press on a block image reaches the provided handler`() {
        var received: PostImageTarget? = null
        val content = PostContent(
            blocks = listOf(PostBlock.Image(url = blockUrl, description = "diagramme")),
        )
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = { received = it }),
                ) {
                    PostRenderer(content = content, selectable = true)
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("diagramme")
            .performTouchInput { longClick() }

        assertEquals(blockUrl, received?.url)
        assertNull(received?.linkUrl)
    }

    @Test
    fun `without provided actions the image stays inert (MP, preview, signatures)`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                // No CompositionLocalProvider: the default null keeps every surface unchanged.
                PostRenderer(content = inlineImageContent(inlineUrl), selectable = true)
            }
        }

        composeTestRule.onNodeWithContentDescription("photo")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            // A long press must not crash either — it simply lands in the selection machinery.
            .performTouchInput { longClick() }
    }

    @Test
    fun `an ineligible URL keeps the image inert even with actions provided`() {
        var received: PostImageTarget? = null
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = { received = it }),
                ) {
                    PostRenderer(
                        content = inlineImageContent("data:image/png;base64,iVBORw0KGgo="),
                        selectable = true,
                    )
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("photo")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            .performTouchInput { longClick() }

        assertNull(received)
    }
}
