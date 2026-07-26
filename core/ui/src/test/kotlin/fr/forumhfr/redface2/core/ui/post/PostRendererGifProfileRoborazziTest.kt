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
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
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
 * #973 (§8 [AMENDEMENT-v1.5-2]) — diagnostic-only Roborazzi captures of the block-GIF display
 * profile (S ×1,0 / M ×1,5 / L ×2,5).
 *
 * One reconstituted post (text + a block GIF + a block non-GIF image + text) rendered under the
 * three profiles, flipped through the REAL provisioning path (`RedfaceTheme(reading = …)` →
 * [fr.forumhfr.redface2.core.ui.theme.LocalMediaDisplayProfile]). Visual proof of the contract:
 *  - **the eligible GIF grows S→M→L**: 320×240 px native (probe MIME `image/gif` seeded on the
 *    atomic cache metadata) → ~106.7×80 dp (S), ~160×120 dp (M), ~266.7×200 dp (L) — all under
 *    the fImage/capBloc hard caps at this width, so the factor is fully visible;
 *  - **the non-GIF image never moves**: same 320×240 px native but probe MIME `image/png` →
 *    ~106.7×80 dp under ALL three profiles (strict v1.5 no-upscale, §8 eligibility is
 *    MIME-of-probe only — the URL extension is never authoritative).
 *
 * Images are fed by a [FakeImageLoaderEngine] returning distinct-coloured [ColorImage]s at native
 * px (GIF green, PNG blue), and the intrinsic cache is pre-seeded (size + MIME) so the first
 * composition is already at the final size. Light theme so the coloured boxes read clearly.
 *
 * Not a CI golden gate (the Roborazzi Gradle plugin is not applied — AGP 9, cf.
 * takahirom/roborazzi#781). Run on demand; PNGs land under `core/ui/build/outputs/roborazzi/`
 * (gitignored) :
 *
 *     ./scripts/docker-dev.sh ./gradlew :core:ui:testDebugUnitTest \
 *         --tests '*PostRendererGifProfileRoborazziTest*' --console=plain --no-daemon
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererGifProfileRoborazziTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val gifUrl = "https://rehost.diberie.com/Picture/Get/f/anim320x240.gif"
    private val pngUrl = "https://rehost.diberie.com/Picture/Get/f/photo320x240.png"
    private val nativeSize = IntSize(320, 240)

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(gifUrl, ColorImage(Color(0xFF2E7D32).toArgb(), width = 320, height = 240))
            .intercept(pngUrl, ColorImage(Color(0xFF1565C0).toArgb(), width = 320, height = 240))
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    /**
     * Pre-seed the ATOMIC metadata the probe would have deposited: same native size for both
     * URLs, but only the GIF carries the eligible probe MIME (§8) — the PNG pins the control.
     */
    private fun seededCache(): IntrinsicMediaSizeCache = DefaultIntrinsicMediaSizeCache().apply {
        putSuccess(gifUrl, IntrinsicMediaMetadata(nativeSize, mimeType = "image/gif"))
        putSuccess(pngUrl, IntrinsicMediaMetadata(nativeSize, mimeType = "image/png"))
    }

    @Test
    fun gifProfileS() {
        // S ×1,0 = strict v1.5 baseline: GIF and PNG both at native 320×240 px (~106.7×80 dp).
        captureProfile(MediaDisplayProfile.S, "gif_973_profile_s")
    }

    @Test
    fun gifProfileM() {
        // M ×1,5 (shipped default): the GIF grows to 480×360 px (~160×120 dp), the PNG stays put.
        captureProfile(MediaDisplayProfile.M, "gif_973_profile_m")
    }

    @Test
    fun gifProfileL() {
        // L ×2,5: the GIF grows to 800×600 px (~266.7×200 dp, still under both caps here), the
        // PNG stays put — the S/M/L series makes the monotonic growth visually undeniable.
        captureProfile(MediaDisplayProfile.L, "gif_973_profile_l")
    }

    /** Text + block GIF + block non-GIF + text — both images take the measured BLOCK path. */
    private fun reconstitutedPost() = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(
                inlines = listOf(PostInline.Text("Un GIF animé posté en bloc (éligible §8) :")),
            ),
            PostBlock.Image(url = gifUrl, description = "gif anime"),
            PostBlock.Paragraph(
                inlines = listOf(PostInline.Text("La même taille native en PNG (témoin, jamais agrandie) :")),
            ),
            PostBlock.Image(url = pngUrl, description = "photo png"),
            PostBlock.Paragraph(
                inlines = listOf(PostInline.Text("Fin du post — seul le GIF suit le profil S/M/L.")),
            ),
        ),
    )

    private fun captureProfile(profile: MediaDisplayProfile, name: String) {
        capture(name, profile, widthDp = 360) {
            PostRenderer(content = reconstitutedPost())
        }
    }

    private fun capture(
        name: String,
        profile: MediaDisplayProfile,
        widthDp: Int,
        content: @Composable () -> Unit,
    ) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides seededCache()) {
                RedfaceTheme(
                    darkTheme = false,
                    amoledTheme = false,
                    dynamicColor = false,
                    reading = ReadingDisplaySettings(mediaDisplayProfile = profile),
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
