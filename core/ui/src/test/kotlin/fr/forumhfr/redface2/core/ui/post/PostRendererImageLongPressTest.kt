package fr.forumhfr.redface2.core.ui.post

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
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
    fun `a short tap on an inline image never fires the long-press action`() {
        // Tap-transparency contract (Codex gate): the interceptor observes without consuming,
        // so a plain click must NOT open the menu — and by construction cannot eat the tap.
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
            .performTouchInput { click() }

        assertNull(received)
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
    fun `long press on a promoted linked image reaches the handler with the link URL`() {
        // The [url=…][img] shape from the field (Codex review: this non-regression was missing):
        // an image-only paragraph whose image measures past the inline caps is promoted to a
        // block (#224/#257) carrying its enclosing link — the ONE path that combines a tap
        // (browser) and the #831 long-press on the same node.
        var received: PostImageTarget? = null
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccess(blockUrl, androidx.compose.ui.unit.IntSize(800, 600))
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = { received = it }),
                    LocalIntrinsicMediaSizeCache provides cache,
                ) {
                    PostRenderer(
                        content = PostContent(
                            blocks = listOf(
                                PostBlock.Paragraph(
                                    inlines = listOf(
                                        PostInline.Link(
                                            url = "https://example.org/full",
                                            children = listOf(
                                                PostInline.InlineImage(url = blockUrl, description = "promue"),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        selectable = true,
                    )
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("promue")
            .performTouchInput { longClick() }

        assertEquals(blockUrl, received?.url)
        assertEquals("https://example.org/full", received?.linkUrl)
    }

    @Test
    fun `known limitation - a linked inline image in a mixed paragraph stays behind the link overlay`() {
        // CHARACTERIZATION, not a feature (2nd Codex gate arbitration): a [url=…][img] image whose
        // paragraph also carries text is NOT promoted to a block, and BasicText's LinkAnnotation
        // overlay intercepts the down on the placeholder region — the long-press never reaches the
        // image, tap AND long-press open the link exactly as before this PR. #831 stays OPEN for
        // this shape (viewer / global interception strategy). INVERT or DELETE this test when the
        // linked-inline case is actually covered.
        var received: PostImageTarget? = null
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = { received = it }),
                ) {
                    PostRenderer(
                        content = PostContent(
                            blocks = listOf(
                                PostBlock.Paragraph(
                                    inlines = listOf(
                                        PostInline.Text("regarde "),
                                        PostInline.Link(
                                            url = "https://example.org/full",
                                            children = listOf(
                                                PostInline.InlineImage(url = inlineUrl, description = "photo"),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        selectable = false,
                    )
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("photo")
            .performTouchInput { longClick() }

        assertNull(received)
    }

    @Test
    fun `without provided actions the image stays inert (MP, preview, signatures)`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                // No CompositionLocalProvider: the default null keeps every surface unchanged.
                PostRenderer(content = inlineImageContent(inlineUrl), selectable = true)
            }
        }

        // The semantics assertion IS the contract: no affordance means no modifier was installed.
        // No synthetic long press here — on a bare selectable text that lands in the base
        // selection machinery, whose magnifier NPEs under this Robolectric harness (not a #831
        // behaviour; identical to dev without this change).
        composeTestRule.onNodeWithContentDescription("photo")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
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

        // Same rationale as the no-actions case: the missing OnLongClick semantics pins the
        // ineligible URL's inertness without poking the base selection machinery.
        composeTestRule.onNodeWithContentDescription("photo")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))

        assertNull(received)
    }
}
