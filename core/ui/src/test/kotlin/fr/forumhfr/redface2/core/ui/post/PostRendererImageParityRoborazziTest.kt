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
 * #610 — diagnostic-only Roborazzi captures of the unified inline/block `[img]` parity
 * (HFR-web `img { max-width: 90%; max-height: 200px }`, no upscale).
 *
 * Visual proof of the three #610 properties:
 *  - **inline/block parity**: the same 360×640 portrait renders at the SAME size (~113×200) inside
 *    prose (inline path, sp) and as a standalone `PostBlock.Image` (block path, dp) — pre-#610 the
 *    block was full-width and 480 dp tall while the inline was 113×200;
 *  - **no block upscale**: a small 80×60 image posted alone stays 80×60 centred — pre-#610
 *    `fillMaxWidth` blew it up to the column width;
 *  - **height cap**: a large 4:3 photo caps at 200 (267×200), not the legacy 480 dp letterbox.
 *
 * Images are fed by a [FakeImageLoaderEngine] returning distinct-coloured [ColorImage]s at native px,
 * and the intrinsic cache is pre-seeded so the first composition is already at the final size (no
 * async-measure timing dependency). Light theme so the coloured boxes read clearly.
 *
 * Not a CI golden gate (the Roborazzi Gradle plugin is not applied — AGP 9, cf. takahirom/roborazzi#781).
 * Run on demand; PNGs land under `core/ui/build/outputs/roborazzi/` (gitignored) :
 *
 *     ./scripts/docker-dev.sh ./gradlew :core:ui:testDebugUnitTest \
 *         --tests '*PostRendererImageParityRoborazziTest*' --console=plain --no-daemon
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererImageParityRoborazziTest {

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

    /** Pre-seed the cache with the same native sizes the fake engine serves → synchronous render. */
    private fun seededCache(): IntrinsicMediaSizeCache = DefaultIntrinsicMediaSizeCache().apply {
        putSuccess(portraitUrl, IntSize(360, 640))
        putSuccess(smallUrl, IntSize(80, 60))
        putSuccess(photoUrl, IntSize(4000, 3000))
    }

    @Test
    fun portraitInlineInProse() {
        // The #610/#813-repro paragraph shape: text + [img] + text → INLINE path. Expected ~113×200.
        capture("img_610_inline_in_prose", widthDp = 360) {
            PostRenderer(content = inlineInProseContent())
        }
    }

    @Test
    fun portraitStandaloneBlock() {
        // Same portrait as a standalone PostBlock.Image → BLOCK path. Expected the SAME ~113×200,
        // centred — compare with img_610_inline_in_prose (pre-#610: full width, 480 dp tall).
        capture("img_610_standalone_block", widthDp = 360) {
            PostRenderer(content = standaloneBlockContent(portraitUrl))
        }
    }

    @Test
    fun smallImageBlockKeepsNativeSize() {
        // 80×60 posted alone: stays 80×60 centred (pre-#610: fillMaxWidth upscale to 360 dp wide).
        capture("img_610_small_block_native", widthDp = 360) {
            PostRenderer(content = standaloneBlockContent(smallUrl))
        }
    }

    @Test
    fun largePhotoBlockCapsAt200() {
        // 4000×3000 → 267×200 (height cap), not the legacy full-width 480 dp letterbox.
        capture("img_610_large_block_cap200", widthDp = 360) {
            PostRenderer(content = standaloneBlockContent(photoUrl))
        }
    }

    private fun inlineInProseContent() = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(
                inlines = listOf(
                    PostInline.Text("Texte avant la photo "),
                    PostInline.InlineImage(url = portraitUrl, description = "portrait"),
                    PostInline.Text(" puis texte après : la photo est capée à 200 de haut (parité web)."),
                ),
            ),
        ),
    )

    private fun standaloneBlockContent(url: String) = PostContent(
        blocks = listOf(PostBlock.Image(url = url, description = "photo")),
    )

    private fun capture(name: String, widthDp: Int, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides seededCache()) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
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
        composeTestRule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/$name.png",
        )
    }
}
