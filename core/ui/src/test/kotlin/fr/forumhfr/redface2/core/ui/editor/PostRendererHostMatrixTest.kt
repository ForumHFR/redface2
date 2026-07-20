package fr.forumhfr.redface2.core.ui.editor

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
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #958 (Lot 2, §5 matrice hôtes) — the EDITOR PREVIEW host, exercised through the REAL production
 * composable ([BbcodePreview]), not a bare PostRenderer with a null local: the preview never
 * provides [fr.forumhfr.redface2.core.ui.post.LocalPostImageActions], so every content image —
 * even a linked one — is TOTALLY inert (no tap, no long-press, no interactive role). The topic
 * body/signature and MP hosts have their own PostRendererHostMatrixTest INSIDE their modules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererHostMatrixTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val imageUrl = "https://rehost.diberie.com/Picture/Get/f/preview.png"

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(imageUrl, ColorImage(0xFF2E7D32.toInt(), width = 80, height = 60))
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    @Test
    fun `a linked content image in the editor preview is totally inert`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                BbcodePreview(
                    content = PostContent(
                        blocks = listOf(
                            PostBlock.Paragraph(
                                inlines = listOf(
                                    PostInline.Text("aperçu "),
                                    PostInline.Link(
                                        url = "https://example.org/full",
                                        children = listOf(
                                            PostInline.InlineImage(url = imageUrl, description = "photo"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("photo")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }
}
