package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.domain.preferences.PostImageMaxWidth
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #876 v1.6-1 — diagnostic-only captures of content sizing at density 3:
 * inline keeps its 200 sp cap; block uses the useful-window height cap; both use the density
 * ceiling and fImage. The small 80×60 source renders at 80×60 dp, while large photos remain
 * width-capped. Keep capture paths stable for comparison with pre-amendment outputs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererImageParityRoborazziTest {

    private val ledger = MediaAttemptLedger()

    @get:Rule
    val composeTestRule = createComposeRule()

    private val portraitUrl = "https://i.example.org/portrait360x640.jpg"
    private val smallUrl = "https://i.example.org/reaction80x60.png"
    private val photoUrl = "https://i.example.org/photo4000x3000.jpg"

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(portraitUrl, ColorImage(Color(0xFF1565C0).toArgb(), width = 360, height = 640))
            .intercept(smallUrl, ColorImage(Color(0xFF2E7D32).toArgb(), width = 80, height = 60))
            .intercept(photoUrl, ColorImage(Color(0xFFC62828).toArgb(), width = 4000, height = 3000))
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    /** Pre-seed the authority before composition for exact first-frame boxes. */
    private fun seedGeometry() {
        ledger.acceptGeometry(
            portraitUrl, 0, IntrinsicMediaMetadata(IntSize(360, 640), mimeType = null), MediaAttemptKind.PROBE,
        )
        ledger.acceptGeometry(
            smallUrl, 0, IntrinsicMediaMetadata(IntSize(80, 60), mimeType = null), MediaAttemptKind.PROBE,
        )
        ledger.acceptGeometry(
            photoUrl, 0, IntrinsicMediaMetadata(IntSize(4000, 3000), mimeType = null), MediaAttemptKind.PROBE,
        )
    }

    @Test
    fun portraitInlineInProse() {
        // The #610/#813-repro paragraph shape: text + [img] + text → INLINE path. Inline keeps the
        // conservative 200 height cap (#842) → ~113×200, so an in-prose image never breaks the flow.
        capture("img_inline_in_prose_cap200", portraitUrl, widthDp = 360) {
            PostRenderer(content = inlineInProseContent())
        }
    }

    @Test
    fun portraitStandaloneBlockFillsWidth() {
        // With the 328 dp inner column, fImage allows 312 dp; capBloc limits the portrait
        // to about 307×546 dp (height derived from the rounded width).
        capture("img_842_standalone_block_fills_width", portraitUrl, widthDp = 360) {
            PostRenderer(content = standaloneBlockContent(portraitUrl))
        }
    }

    @Test
    fun smallImageBlockUsesDensityCeiling() {
        // v1.6-1: 80×60 native pixels become 240×180 physical pixels = 80×60 dp.
        capture("img_610_small_block_native", smallUrl, widthDp = 360) {
            PostRenderer(content = standaloneBlockContent(smallUrl))
        }
    }

    @Test
    fun largePhotoBlockStaysBounded() {
        // #842/#991 — 4000×3000 stays bounded by the default 95 % width cap → ~312×234 here.
        capture("img_842_large_block_bounded", photoUrl, widthDp = 360) {
            PostRenderer(content = standaloneBlockContent(photoUrl))
        }
    }

    @Test
    fun largePhotoBlockP90() {
        capture("img_991_large_block_p90", photoUrl, widthDp = 360, postImageMaxWidth = PostImageMaxWidth.P90) {
            PostRenderer(content = standaloneBlockContent(photoUrl))
        }
    }

    @Test
    fun largePhotoBlockP100() {
        capture("img_991_large_block_p100", photoUrl, widthDp = 360, postImageMaxWidth = PostImageMaxWidth.P100) {
            PostRenderer(content = standaloneBlockContent(photoUrl))
        }
    }

    private fun inlineInProseContent() = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(
                inlines = listOf(
                    PostInline.Text("Texte avant la photo "),
                    PostInline.InlineImage(url = portraitUrl, description = "portrait"),
                    PostInline.Text(" puis texte après : en inline la photo est capée à 200 de haut (#842)."),
                ),
            ),
        ),
    )

    private fun standaloneBlockContent(url: String) = PostContent(
        blocks = listOf(PostBlock.Image(url = url, description = "photo")),
    )

    private fun capture(
        name: String,
        imageUrl: String,
        widthDp: Int,
        postImageMaxWidth: PostImageMaxWidth = PostImageMaxWidth.DEFAULT,
        content: @Composable () -> Unit,
    ) {
        seedGeometry()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalMediaAttemptLedger provides ledger) {
                RedfaceTheme(
                    darkTheme = false,
                    amoledTheme = false,
                    dynamicColor = false,
                    reading = ReadingDisplaySettings(postImageMaxWidth = postImageMaxWidth),
                ) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Box(
                            modifier = Modifier
                                .width(widthDp.dp)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(16.dp),
                        ) {
                            content()
                        }
                    }
                }
            }
        }
        // v1.6-10: capture the settled painter, after the exact first-frame box has loaded.
        composeTestRule.waitUntil(5_000) { ledger.hasSucceeded(imageUrl, MediaAttemptKind.PAINTER) }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/$name.png",
        )
    }
}
