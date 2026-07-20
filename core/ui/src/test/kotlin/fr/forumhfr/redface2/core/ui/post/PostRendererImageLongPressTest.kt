package fr.forumhfr.redface2.core.ui.post

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #831/#958 — pins the interaction contract of post images on BOTH render paths (inline `[img]`
 * inside the selectable Text, and block image). Since #958 (Lot 2, §5) the LinkAnnotation SPLIT
 * gives a linked INLINE content image its own interaction node: tap opens the enclosing link,
 * long-press opens the contextual menu (mutually exclusive), both gated by the host capability
 * ([LocalPostImageActions] != null) — on the three null hosts (MP / editor preview / signatures)
 * a content image is TOTALLY inert. cc-images (#256) keep the link overlay behaviour and gain no
 * tap surface of their own. The fine finger-level interplay with selection drags is covered on
 * device (banc S10e, cas 11.1).
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
    private val ccUrl = "https://example.org/emojis-micro/1f600.png?hfr-cc-image=true"
    private val fullLinkUrl = "https://example.org/full"

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // #959 — the inline fixture must stay ABOVE the 48 dp platform minimum touch target once
        // rendered at its native physical size (§3): 400×300 px at density 3 = 133×100 dp. A
        // smaller bitmap gets the legitimate platform touch-target EXPANSION, which would make
        // the padding-strip hitbox tests observe the expansion instead of the §5 bitmap hitbox.
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(inlineUrl, ColorImage(0xFF2E7D32.toInt(), width = 400, height = 300))
            .intercept(blockUrl, ColorImage(0xFF1565C0.toInt(), width = 400, height = 300))
            .intercept(ccUrl, ColorImage(0xFFF9A825.toInt(), width = 16, height = 16))
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    /** Captures what the runtime asked the platform to open — both the BasicText link machinery
     * and the image node's own click go through [LocalUriHandler]. */
    private class RecordingUriHandler : UriHandler {
        var opened: String? = null
        override fun openUri(uri: String) {
            opened = uri
        }
    }

    private fun linkedInlineImageContent(url: String = inlineUrl) = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(
                inlines = listOf(
                    PostInline.Text("regarde "),
                    PostInline.Link(
                        url = fullLinkUrl,
                        children = listOf(PostInline.InlineImage(url = url, description = "photo")),
                    ),
                ),
            ),
        ),
    )

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
        // The [url=…][img] shape from the field: since #957 an isolated image-only link is a
        // STRUCTURAL MediaRun (contract v1.4 §2 — no measurement involved) rendered as a block
        // carrying its enclosing link — the ONE path that combines a tap (browser) and the
        // #831 long-press on the same node.
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
    fun `a linked inline image in a mixed paragraph opens the menu on long-press with the real link URL`() {
        // #958 Lot 2 (§5) — INVERSION of the Lot 1B characterization ("stays behind the link
        // overlay"): the content-image placeholder now lives OUTSIDE the LinkAnnotation (split),
        // so the long-press reaches the image handler, and the target carries the REAL enclosing
        // link URL (Sol framing refinement: the menu must act on the true linkUrl).
        var received: PostImageTarget? = null
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = { received = it }),
                ) {
                    PostRenderer(content = linkedInlineImageContent(), selectable = false)
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("photo")
            .performTouchInput { longClick() }

        assertEquals(inlineUrl, received?.url)
        assertEquals(fullLinkUrl, received?.linkUrl)
    }

    @Test
    fun `tap on a linked inline image opens the link through the image node`() {
        // §5/I2.4 — the tap belongs to the image node itself (Role.Image + OnClick), not to the
        // text link machinery: the placeholder left the LinkAnnotation, the image opens its link.
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = {}),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(content = linkedInlineImageContent(), selectable = false)
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("photo")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
            .performTouchInput { click() }

        assertEquals(fullLinkUrl, uriHandler.opened)
    }

    @Test
    fun `long-press on a linked inline image never opens the link - tap and menu mutually exclusive`() {
        var received: PostImageTarget? = null
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = { received = it }),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(content = linkedInlineImageContent(), selectable = false)
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("photo")
            .performTouchInput { longClick() }

        assertNotNull("the long-press must reach the menu handler", received)
        assertNull("the long-press must NOT open the link", uriHandler.opened)
    }

    @Test
    fun `on a null host a linked inline image is totally inert`() {
        // #958 Lot 2 (§5 matrice I5.4) — on the three null hosts (MP, editor preview, signature)
        // a linked content image loses even its historical link-overlay tap: the placeholder left
        // the LinkAnnotation and no interaction node replaces it. Text links stay live (their own
        // ranges are untouched — pinned structurally in PostRendererLinkSplitTest).
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    PostRenderer(content = linkedInlineImageContent(), selectable = false)
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("photo")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            .performTouchInput { click() }

        assertNull(uriHandler.opened)
    }

    @Test
    fun `a linked cc-image keeps the link overlay tap and gains no tap surface of its own`() {
        // #256/#958 — cc-images STAY under the LinkAnnotation: the tap keeps going through the
        // text link machinery (works on every host), and the split gives them no OnClick of
        // their own.
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = {}),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(
                        content = PostContent(
                            blocks = listOf(
                                PostBlock.Paragraph(
                                    inlines = listOf(
                                        PostInline.Text("regarde "),
                                        PostInline.Link(
                                            url = fullLinkUrl,
                                            children = listOf(
                                                PostInline.InlineImage(url = ccUrl, description = "emoji"),
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

        composeTestRule.onNodeWithContentDescription("emoji")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .performTouchInput { click() }

        assertEquals(fullLinkUrl, uriHandler.opened)
    }

    @Test
    fun `the horizontal padding strip of a linked inline image is not tappable - hitbox is the bitmap`() {
        // §5 (Sol firm reserve) — the HITBOX is the BITMAP: a tap in the §4 4 dp padding strip
        // does nothing, a tap on the bitmap opens the link. The padding lives on a PARENT box
        // (see imageInlineContent: intra-node modifier order does not gate hit testing), so the
        // image node bounds ARE the bitmap box and the strip sits at NEGATIVE x in node
        // coordinates — its width computed from the rule's density, not hardcoded.
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = {}),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(content = linkedInlineImageContent(), selectable = false)
                }
            }
        }

        val stripPx = with(composeTestRule.density) { INLINE_IMAGE_HORIZONTAL_PADDING.toPx() }
        val node = composeTestRule.onNodeWithContentDescription("photo")
        node.performTouchInput { click(Offset(-stripPx / 2f, center.y)) }
        assertNull("a tap inside the 4 dp padding strip must stay inert", uriHandler.opened)

        node.performTouchInput { click(center) }
        assertEquals(fullLinkUrl, uriHandler.opened)
    }

    @Test
    fun `the text run of a split link stays tappable next to the image`() {
        // Frontier 11.1 — the SPLIT must not kill the text side of the link: the text run keeps
        // its LinkAnnotation (pinned structurally in PostRendererLinkSplitTest) AND the text link
        // machinery still opens it. The link text starts the paragraph, so a tap near the line
        // start/bottom (the text rides the line bottom next to a tall placeholder) hits its range.
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = {}),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(
                        content = PostContent(
                            blocks = listOf(
                                PostBlock.Paragraph(
                                    inlines = listOf(
                                        PostInline.Link(
                                            url = fullLinkUrl,
                                            children = listOf(
                                                PostInline.Text("lien "),
                                                PostInline.InlineImage(url = inlineUrl, description = "photo"),
                                            ),
                                        ),
                                        PostInline.Text(" suite"),
                                    ),
                                ),
                            ),
                        ),
                        selectable = false,
                    )
                }
            }
        }

        composeTestRule.onNode(hasText("lien", substring = true))
            .performTouchInput { click(Offset(20f, height - 20f)) }

        assertEquals(fullLinkUrl, uriHandler.opened)
    }

    @Test
    fun `long-press in the padding strip of a linked inline image opens nothing`() {
        // Frontier 11.1 — the long-press hitbox is the bitmap too: in the 4 dp strip neither the
        // menu nor the link may trigger.
        var received: PostImageTarget? = null
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = { received = it }),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(content = linkedInlineImageContent(), selectable = false)
                }
            }
        }

        val stripPx = with(composeTestRule.density) { INLINE_IMAGE_HORIZONTAL_PADDING.toPx() }
        composeTestRule.onNodeWithContentDescription("photo")
            .performTouchInput { longClick(Offset(-stripPx / 2f, center.y)) }

        assertNull("no menu from the padding strip", received)
        assertNull("no link from the padding strip", uriHandler.opened)
    }

    @Test
    fun `the long-press hitbox of an unlinked inline image is the bitmap not the placeholder`() {
        // INVERSION of the pre-#958 behaviour ("the long-press hitbox stays on the FULL
        // placeholder"): the §4 padding strip is inert for the menu-only path as well.
        var received: PostImageTarget? = null
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = { received = it }),
                ) {
                    PostRenderer(content = inlineImageContent(inlineUrl), selectable = false)
                }
            }
        }

        val stripPx = with(composeTestRule.density) { INLINE_IMAGE_HORIZONTAL_PADDING.toPx() }
        val node = composeTestRule.onNodeWithContentDescription("photo")
        node.performTouchInput { longClick(Offset(-stripPx / 2f, center.y)) }
        assertNull("no menu from the padding strip", received)

        node.performTouchInput { longClick(center) }
        assertEquals(inlineUrl, received?.url)
    }

    @Test
    fun `an unlinked inline image never gains a tap even with a host - menu only`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = {}),
                ) {
                    PostRenderer(content = inlineImageContent(inlineUrl), selectable = true)
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("photo")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
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
