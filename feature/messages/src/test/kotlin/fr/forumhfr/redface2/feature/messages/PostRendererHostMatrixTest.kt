package fr.forumhfr.redface2.feature.messages

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import java.time.Instant
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #958 (Lot 2, §5 matrice hôtes) — the MP host, exercised through the REAL production card
 * ([MessageCard]) in its own module: a private-message thread never provides
 * [fr.forumhfr.redface2.core.ui.post.LocalPostImageActions], so every content image — inline in
 * a mixed paragraph or promoted to a linked block — is TOTALLY inert (no tap even when linked,
 * no long-press, no interactive role). Topic body/signature and editor-preview hosts have their
 * own PostRendererHostMatrixTest inside their modules.
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

    private fun setCard(content: PostContent) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MessageCard(message = message(content))
            }
        }
    }

    @Test
    fun `a linked inline image in a private message is totally inert`() {
        setCard(
            PostContent(
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
        )

        composeTestRule.onNodeWithContentDescription("photo")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun `a promoted linked block image in a private message is totally inert`() {
        // An isolated [url=…][img] paragraph is structurally promoted to a linked BLOCK (#957):
        // the block path must be as inert as the inline one on this host.
        setCard(
            PostContent(
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
        )

        composeTestRule.onNodeWithContentDescription("promue")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }
}
