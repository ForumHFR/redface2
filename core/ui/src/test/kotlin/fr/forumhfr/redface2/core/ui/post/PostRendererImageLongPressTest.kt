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
 * gives a linked INLINE content image its own interaction node. Since #1279, tap opens the viewer
 * for an image-like link and the browser otherwise; long-press opens the contextual menu (mutually
 * exclusive), both gated by the host capability ([LocalPostImageActions] != null) — on null hosts
 * (editor preview / signatures) a content image is TOTALLY inert. cc-images (#256) keep the link overlay and gain no
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
    private val smallUrl = "https://rehost.diberie.com/Picture/Get/f/small.png"
    private val ccUrl = "https://example.org/emojis-micro/1f600.png?hfr-cc-image=true"
    private val fullLinkUrl = "https://example.org/full"
    private val fullImageUrl = "https://cdn.example.org/original/full.PNG?download=1"
    private val thumbnailUrl = "https://rehost.diberie.com/Picture/Get/t/1"
    private val originalUrl = "https://rehost.diberie.com/Picture/Get/f/1"
    private val dataUrl = "data:image/png;base64,AAAA"

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
            .intercept(smallUrl, ColorImage(0xFF8E24AA.toInt(), width = 80, height = 60))
            .intercept(ccUrl, ColorImage(0xFFF9A825.toInt(), width = 16, height = 16))
            .intercept(thumbnailUrl, ColorImage(0xFF2E7D32.toInt(), width = 400, height = 300))
            .intercept(dataUrl, ColorImage(0xFF2E7D32.toInt(), width = 400, height = 300))
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

    private fun linkedInlineImageContent(url: String = inlineUrl, linkUrl: String = fullLinkUrl) = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(
                inlines = listOf(
                    PostInline.Text("regarde "),
                    PostInline.Link(
                        url = linkUrl,
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

    private fun linkedBlockImageContent(linkUrl: String) = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(
                inlines = listOf(
                    PostInline.Link(
                        url = linkUrl,
                        children = listOf(PostInline.InlineImage(url = blockUrl, description = "promue")),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `tap on an unlinked block image opens the viewer`() {
        var opened: PostImageTarget? = null
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(
                        onLongPress = {},
                        onOpenViewer = { opened = it },
                    ),
                ) {
                    PostRenderer(
                        content = PostContent(
                            blocks = listOf(PostBlock.Image(blockUrl, description = "diagramme")),
                        ),
                        selectable = true,
                    )
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("diagramme").performTouchInput { click() }

        assertEquals(PostImageTarget(blockUrl, "diagramme", null), opened)
    }

    @Test
    fun `tap on a linked image-like block opens the viewer instead of the browser`() {
        var opened: PostImageTarget? = null
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(
                        onLongPress = {},
                        onOpenViewer = { opened = it },
                    ),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(content = linkedBlockImageContent(fullImageUrl), selectable = true)
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("promue").performTouchInput { click() }

        assertEquals(PostImageTarget(blockUrl, "promue", fullImageUrl), opened)
        assertNull(uriHandler.opened)
    }

    @Test
    fun `tap on a linked non-image block keeps opening the browser`() {
        var opened: PostImageTarget? = null
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(
                        onLongPress = {},
                        onOpenViewer = { opened = it },
                    ),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(content = linkedBlockImageContent(fullLinkUrl), selectable = true)
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("promue").performTouchInput { click() }

        assertNull(opened)
        assertEquals(fullLinkUrl, uriHandler.opened)
    }

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
        cache.putSuccess(blockUrl, IntrinsicMediaMetadata(androidx.compose.ui.unit.IntSize(800, 600), mimeType = null))
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
    fun `tap on a linked image-like inline image opens the viewer instead of the browser`() {
        var opened: PostImageTarget? = null
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(
                        onLongPress = {},
                        onOpenViewer = { opened = it },
                    ),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(
                        content = linkedInlineImageContent(thumbnailUrl, linkUrl = originalUrl),
                        selectable = true,
                    )
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("photo")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
            .performTouchInput { click() }

        assertEquals(PostImageTarget(thumbnailUrl, "photo", originalUrl), opened)
        val request = opened?.let { viewerRequestFor(it, diskCache = true) }
        assertEquals(originalUrl, request?.sourceUrl)
        assertEquals(thumbnailUrl, request?.previewUrl)
        assertNull(uriHandler.opened)
    }

    @Test
    fun `tap on a linked inline image with an image extension opens the viewer`() {
        var opened: PostImageTarget? = null
        val linkUrl = "https://example.com/photo.jpg"
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(
                        onLongPress = {},
                        onOpenViewer = { opened = it },
                    ),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(content = linkedInlineImageContent(linkUrl = linkUrl), selectable = true)
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("photo").performTouchInput { click() }

        assertEquals(PostImageTarget(inlineUrl, "photo", linkUrl), opened)
        val request = opened?.let { viewerRequestFor(it, diskCache = true) }
        assertEquals(linkUrl, request?.sourceUrl)
        assertEquals(inlineUrl, request?.previewUrl)
        assertNull(uriHandler.opened)
    }

    @Test
    fun `tap on a linked non-image inline image keeps opening the browser`() {
        // #1279 narrows the historical browser expectation to non-image links; the tap still
        // belongs to the image node itself (Role.Image + OnClick), outside the LinkAnnotation.
        var opened: PostImageTarget? = null
        val linkUrl = "https://example.com/page"
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(
                        onLongPress = {},
                        onOpenViewer = { opened = it },
                    ),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(content = linkedInlineImageContent(linkUrl = linkUrl), selectable = false)
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("photo")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
            .performTouchInput { click() }

        assertEquals(linkUrl, uriHandler.opened)
        assertNull(opened)
    }

    @Test
    fun `long press on a linked inline image still reaches the handler`() {
        var received: PostImageTarget? = null
        var opened: PostImageTarget? = null
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(
                        onLongPress = { received = it },
                        onOpenViewer = { opened = it },
                    ),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(
                        content = linkedInlineImageContent(thumbnailUrl, linkUrl = originalUrl),
                        selectable = true,
                    )
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("photo")
            .performTouchInput { longClick() }

        assertEquals(PostImageTarget(thumbnailUrl, "photo", originalUrl), received)
        assertNull("the long-press must NOT open the viewer", opened)
        assertNull("the long-press must NOT open the link", uriHandler.opened)
    }

    @Test
    fun `linked inline image stays inert without a host`() {
        // #958/#1279 — on null hosts (editor preview, signature), even an image-like linked
        // content image loses its historical link-overlay tap: the placeholder left
        // the LinkAnnotation and no interaction node replaces it. Text links stay live (their own
        // ranges are untouched — pinned structurally in PostRendererLinkSplitTest).
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides null,
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(
                        content = linkedInlineImageContent(thumbnailUrl, linkUrl = originalUrl),
                        selectable = false,
                    )
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
    fun `linked inline data image still opens its link without a long-press menu`() {
        var opened: PostImageTarget? = null
        var received: PostImageTarget? = null
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(
                        onLongPress = { received = it },
                        onOpenViewer = { opened = it },
                    ),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(
                        content = linkedInlineImageContent(dataUrl, linkUrl = originalUrl),
                        selectable = false,
                    )
                }
            }
        }

        // #1279 — the thumbnail's own URL is not menu-eligible (data:) so the viewer and the
        // contextual menu are both out, but the image keeps the historical browser tap of its
        // link, exactly like the block path.
        composeTestRule.onNodeWithContentDescription("photo")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            .performTouchInput { click() }

        assertNull(opened)
        assertNull(received)
        assertEquals(originalUrl, uriHandler.opened)
    }

    @Test
    fun `linked block data image opens its link without a long-press menu`() {
        var opened: PostImageTarget? = null
        var received: PostImageTarget? = null
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(
                        onLongPress = { received = it },
                        onOpenViewer = { opened = it },
                    ),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(
                        content = PostContent(
                            blocks = listOf(
                                PostBlock.Paragraph(
                                    inlines = listOf(
                                        PostInline.Link(
                                            url = originalUrl,
                                            children = listOf(
                                                PostInline.InlineImage(url = dataUrl, description = "promue"),
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
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            .performTouchInput { click() }

        assertNull(opened)
        assertNull(received)
        assertEquals(originalUrl, uriHandler.opened)
    }

    @Test
    fun `a linked cc-image keeps the link overlay tap and gains no tap surface of its own`() {
        // #256/#958 — cc-images STAY under the LinkAnnotation: the tap keeps going through the
        // text link machinery (works on every host), and the split gives them no OnClick of
        // their own.
        var opened: PostImageTarget? = null
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(
                        onLongPress = {},
                        onOpenViewer = { opened = it },
                    ),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(
                        content = PostContent(
                            blocks = listOf(
                                PostBlock.Paragraph(
                                    inlines = listOf(
                                        PostInline.Text("regarde "),
                                        PostInline.Link(
                                            url = fullImageUrl,
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
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            .performTouchInput { click() }

        assertEquals(fullImageUrl, uriHandler.opened)
        assertNull(opened)
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
    fun `a small linked image keeps the platform touch-target expansion - AMENDEMENT-Lot3-1`() {
        // §5 amendé ([AMENDEMENT-Lot3-1], gate Sol Lot 3) : une image de contenu rendue SOUS le
        // minimum touch target plateforme (48 dp — ici 80×60 px = 26,7×20 dp @d3) reçoit
        // l'EXPANSION de cible tactile a11y d'Android : un tap dans la bande de padding, HORS
        // du bitmap mais dans la cible étendue, déclenche quand même l'action. La « hitbox =
        // bitmap » du Lot 2 s'entend AU-DELÀ de ce minimum (les tests de frontière stricte
        // utilisent des fixtures ≥ 48 dp).
        val uriHandler = RecordingUriHandler()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalPostImageActions provides PostImageActions(onLongPress = {}),
                    LocalUriHandler provides uriHandler,
                ) {
                    PostRenderer(content = linkedInlineImageContent(smallUrl), selectable = false)
                }
            }
        }

        val stripPx = with(composeTestRule.density) { INLINE_IMAGE_HORIZONTAL_PADDING.toPx() }
        composeTestRule.onNodeWithContentDescription("photo")
            .performTouchInput { click(Offset(-stripPx / 2f, center.y)) }

        assertEquals(fullLinkUrl, uriHandler.opened)
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
        // ineligible URL's inertness without poking the base selection machinery. The data: url
        // is not served by the fake loader, so the node is the #960 P3 ERROR slot (whose single
        // action is the per-URL retry — never the §5 long-press menu pinned here).
        val errorWithAlt = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(fr.forumhfr.redface2.core.ui.R.string.post_image_error_with_alt, "photo")
        composeTestRule.onNodeWithContentDescription(errorWithAlt)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))

        assertNull(received)
    }
}
