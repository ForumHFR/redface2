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
 * #610/#842/#991 — diagnostic-only Roborazzi captures of `[img]` sizing
 * (`PostImageMaxWidth.DEFAULT` = 95 %, no upscale).
 *
 * Visual proof of the current properties (config `w360dp-h780dp` → block height cap
 * [blockImageMaxHeightDp] = `max(400, 0.5×780)` = 400 dp):
 *  - **inline height cap 200 (conservative, #842)**: a 360×640 portrait inside prose (inline path, sp)
 *    stays ~113×200 so it never breaks the text flow;
 *  - **block fills the width (#842)**: the SAME portrait as a standalone `PostBlock.Image` (block path)
 *    now renders ~225×400 under the recalibrated cap — deliberately taller/wider than the inline box
 *    (each image takes only one path per the width ≥ 240 promotion threshold), reversing #610's flat
 *    200 that squeezed it to ~113×200 (~48 % width);
 *  - **no block upscale**: a small 80×60 image posted alone stays 80×60 centred — pre-#610
 *    `fillMaxWidth` blew it up to the column width;
 *  - **bounded**: a large 4:3 photo is still bounded (width cap → ~295×221 here), not the legacy
 *    480 dp letterbox nor a scroll-destroying blow-up.
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
        putSuccess(portraitUrl, IntrinsicMediaMetadata(IntSize(360, 640), mimeType = null))
        putSuccess(smallUrl, IntrinsicMediaMetadata(IntSize(80, 60), mimeType = null))
        putSuccess(photoUrl, IntrinsicMediaMetadata(IntSize(4000, 3000), mimeType = null))
    }

    @Test
    fun portraitInlineInProse() {
        // The #610/#813-repro paragraph shape: text + [img] + text → INLINE path. Inline keeps the
        // conservative 200 height cap (#842) → ~113×200, so an in-prose image never breaks the flow.
        capture("img_inline_in_prose_cap200", widthDp = 360) {
            PostRenderer(content = inlineInProseContent())
        }
    }

    @Test
    fun portraitStandaloneBlockFillsWidth() {
        // #842 — same portrait as a standalone PostBlock.Image → BLOCK path, recalibrated cap 400 dp
        // (h780dp) → ~225×400, deliberately larger than the inline box above (compare
        // img_inline_in_prose_cap200). Reverses #610's flat 200 (~113×200, ~48 % width).
        capture("img_842_standalone_block_fills_width", widthDp = 360) {
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
    fun largePhotoBlockStaysBounded() {
        // #842/#991 — 4000×3000 stays bounded by the default 95 % width cap → ~312×234 here.
        // 480 dp letterbox, not a full-screen blow-up). The recalibrated height cap never upscales.
        capture("img_842_large_block_bounded", widthDp = 360) {
            PostRenderer(content = standaloneBlockContent(photoUrl))
        }
    }

    @Test
    fun largePhotoBlockP90() {
        capture("img_991_large_block_p90", widthDp = 360, postImageMaxWidth = PostImageMaxWidth.P90) {
            PostRenderer(content = standaloneBlockContent(photoUrl))
        }
    }

    @Test
    fun largePhotoBlockP100() {
        capture("img_991_large_block_p100", widthDp = 360, postImageMaxWidth = PostImageMaxWidth.P100) {
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
        widthDp: Int,
        postImageMaxWidth: PostImageMaxWidth = PostImageMaxWidth.DEFAULT,
        content: @Composable () -> Unit,
    ) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides seededCache()) {
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
        composeTestRule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/$name.png",
        )
    }
}
