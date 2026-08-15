package fr.forumhfr.redface2.feature.messages

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.PostImageTarget
import java.time.Instant
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
 * #958/#1051 — the MP host, exercised through the REAL production card ([MessageCard]) in its own
 * module. The thread now supplies `onImageLongPress`, so linked content images — inline in a mixed
 * paragraph or promoted to a block — expose their link tap and contextual-menu long-press.
 * `Role.Image` does not distinguish a wired image from an inert one because it comes from the image
 * composable; only `OnClick` and `OnLongClick` prove the host actions. Capability still comes from
 * callback presence: a direct host that omits the callback keeps the image totally inert. Topic
 * body/signature and editor-preview hosts have their own PostRendererHostMatrixTest inside their
 * modules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererHostMatrixTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val inlineImageUrl = "https://rehost.diberie.com/Picture/Get/f/mp-inline.png"
    private val blockImageUrl = "https://rehost.diberie.com/Picture/Get/f/mp-block.png"
    private val fullLinkUrl = "https://example.org/full"

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(inlineImageUrl, ColorImage(0xFF2E7D32.toInt(), width = 80, height = 60))
            .intercept(blockImageUrl, ColorImage(0xFF1565C0.toInt(), width = 400, height = 300))
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    private fun message(content: PostContent): Post = Post(
        numreponse = 1,
        author = "XaTriX",
        date = Instant.EPOCH,
        content = content,
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
    )

    private class RecordingUriHandler : UriHandler {
        var opened: String? = null

        override fun openUri(uri: String) {
            opened = uri
        }
    }

    private fun setCard(
        content: PostContent,
        onImageLongPress: ((PostImageTarget) -> Unit)? = null,
        uriHandler: UriHandler = RecordingUriHandler(),
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    MessageCard(
                        message = message(content),
                        onImageLongPress = onImageLongPress,
                    )
                }
            }
        }
    }

    @Test
    fun `a linked inline image exposes its tap and exact long-press target in a private message`() {
        var received: PostImageTarget? = null
        val uriHandler = RecordingUriHandler()
        setCard(
            content = PostContent(
                blocks = listOf(
                    PostBlock.Paragraph(
                        inlines = listOf(
                            PostInline.Text("regarde "),
                            PostInline.Link(
                                url = fullLinkUrl,
                                children = listOf(
                                    PostInline.InlineImage(url = inlineImageUrl, description = "photo"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            onImageLongPress = { received = it },
            uriHandler = uriHandler,
        )

        val image = composeTestRule.onNodeWithContentDescription("photo")
        image
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performTouchInput { click() }

        assertEquals(fullLinkUrl, uriHandler.opened)
        assertNull("a tap must not open the contextual menu", received)

        uriHandler.opened = null
        image.performTouchInput { longClick() }

        assertEquals(
            PostImageTarget(
                url = inlineImageUrl,
                description = "photo",
                linkUrl = fullLinkUrl,
            ),
            received,
        )
        assertNull("a long-press must not open the wrapping link", uriHandler.opened)
    }

    @Test
    fun `a promoted linked block exposes its tap and exact long-press target in a private message`() {
        // An isolated [url=…][img] paragraph is structurally promoted to a linked BLOCK (#957):
        // both renderer paths must carry the same MP host capability.
        var received: PostImageTarget? = null
        val uriHandler = RecordingUriHandler()
        setCard(
            content = PostContent(
                blocks = listOf(
                    PostBlock.Paragraph(
                        inlines = listOf(
                            PostInline.Link(
                                url = fullLinkUrl,
                                children = listOf(
                                    PostInline.InlineImage(url = blockImageUrl, description = "promue"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            onImageLongPress = { received = it },
            uriHandler = uriHandler,
        )

        val image = composeTestRule.onNodeWithContentDescription("promue")
        image
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performTouchInput { click() }

        assertEquals(fullLinkUrl, uriHandler.opened)
        assertNull("a tap must not open the contextual menu", received)

        uriHandler.opened = null
        image.performTouchInput { longClick() }

        assertEquals(
            PostImageTarget(
                url = blockImageUrl,
                description = "promue",
                linkUrl = fullLinkUrl,
            ),
            received,
        )
        assertNull("a long-press must not open the wrapping link", uriHandler.opened)
    }

    @Test
    fun `without the callback a linked private-message image stays totally inert`() {
        setCard(
            content = PostContent(
                blocks = listOf(
                    PostBlock.Paragraph(
                        inlines = listOf(
                            PostInline.Link(
                                url = fullLinkUrl,
                                children = listOf(
                                    PostInline.InlineImage(url = inlineImageUrl, description = "inerte"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithContentDescription("inerte")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }
}
