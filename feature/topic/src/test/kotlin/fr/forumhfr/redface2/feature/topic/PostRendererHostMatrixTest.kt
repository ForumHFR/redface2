package fr.forumhfr.redface2.feature.topic

import android.content.Context
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
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.PostImageTarget
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #958 (Lot 2, §5 matrice hôtes) — the two TOPIC hosts, exercised through the REAL production
 * card ([TopicPostCard]) in its own module:
 *  - the post BODY receives [fr.forumhfr.redface2.core.ui.post.LocalPostImageActions] from the
 *    screen's `onImageLongPress` wiring → a linked content image owns tap AND long-press menu;
 *  - the SIGNATURE below the very same body is rendered OUTSIDE the provider on purpose → its
 *    images stay TOTALLY inert, even with the handler provided to the card.
 * MP and editor-preview hosts have their own PostRendererHostMatrixTest inside their modules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererHostMatrixTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val bodyImageUrl = "https://rehost.diberie.com/Picture/Get/f/body.png"
    private val signatureImageUrl = "https://rehost.diberie.com/Picture/Get/f/signature.png"
    private val fullLinkUrl = "https://example.org/full"

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(bodyImageUrl, ColorImage(0xFF2E7D32.toInt(), width = 80, height = 60))
            .intercept(signatureImageUrl, ColorImage(0xFF1565C0.toInt(), width = 80, height = 60))
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    private fun linkedImageParagraph(url: String, description: String) = PostBlock.Paragraph(
        inlines = listOf(
            PostInline.Text("regarde "),
            PostInline.Link(
                url = fullLinkUrl,
                children = listOf(PostInline.InlineImage(url = url, description = description)),
            ),
        ),
    )

    private fun postWithSignature(): Post = Post(
        numreponse = 16244,
        author = "XaTriX",
        date = Instant.EPOCH,
        content = PostContent(blocks = listOf(linkedImageParagraph(bodyImageUrl, "corps"))),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
        quoteRef = 1,
        profileId = null,
        signature = PostContent(blocks = listOf(linkedImageParagraph(signatureImageUrl, "sig"))),
    )

    private fun setCard(onImageLongPress: ((PostImageTarget) -> Unit)?) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPostCard(
                    post = postWithSignature(),
                    citedCount = 0,
                    showSignature = true,
                    onQuote = null,
                    onEdit = null,
                    onImageLongPress = onImageLongPress,
                )
            }
        }
    }

    @Test
    fun `a linked body image owns tap and long-press menu when the screen wires image actions`() {
        var received: PostImageTarget? = null
        setCard(onImageLongPress = { received = it })

        composeTestRule.onNodeWithContentDescription("corps")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performTouchInput { longClick() }

        assertEquals(bodyImageUrl, received?.url)
        assertEquals(fullLinkUrl, received?.linkUrl)
    }

    @Test
    fun `a linked signature image stays totally inert on the very same card`() {
        setCard(onImageLongPress = {})

        composeTestRule.onNodeWithContentDescription("sig")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun `without the screen wiring even the body image is inert`() {
        setCard(onImageLongPress = null)

        composeTestRule.onNodeWithContentDescription("corps")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }
}
