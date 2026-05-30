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
import fr.forumhfr.redface2.core.model.SmileyKind
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #175 — diagnostic-only Roborazzi captures of the **adaptive inline-smiley rendering**.
 *
 * Visual proof of the two properties dogfood kept hammering on:
 *  - **adaptive sizing**: each smiley renders at its measured native size (no-upscale + cap), so a
 *    16×16 builtin stays tiny next to a 70×50 perso, a 15×15 micro-perso is NOT blown up to the old
 *    50×50 bucket, and a 200×140 giant is capped down to 70sp-high preserving ratio ;
 *  - **zero overlap**: a tall smiley grows its own line instead of colliding with the line above
 *    (media paragraphs drop bodyMedium's fixed lineHeight; the sprite rides the baseline via
 *    AboveBaseline and the ascent expands to contain it).
 *
 * Smileys are fed by a [FakeImageLoaderEngine] returning a distinct-coloured [ColorImage] at each
 * URL's native px, so the rendered box IS the intrinsic size — and the cache is pre-seeded so the
 * first composition is already at the final size (no async-measure timing dependency). Light theme
 * so the coloured boxes read clearly.
 *
 * Not a CI golden gate (the Roborazzi Gradle plugin is not applied — AGP 9, cf. takahirom/roborazzi#781).
 * Run on demand; PNGs land under `core/ui/build/outputs/roborazzi/` (gitignored) :
 *
 *     ./scripts/docker-dev.sh ./gradlew :core:ui:testDebugUnitTest \
 *         --tests '*PostRendererSmileyRoborazziTest*' --console=plain --no-daemon
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererSmileyRoborazziTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val builtinUrl = "https://forum.hardware.fr/icones/jap.gif"
    private val microUrl = "https://forum.hardware.fr/images/perso/m/micro15.gif"
    private val persoUrl = "https://forum.hardware.fr/images/perso/f/franzhermann.gif"
    private val giantUrl = "https://forum.hardware.fr/images/perso/g/giant200x140.gif"
    private val bannerUrl = "https://forum.hardware.fr/images/perso/b/banner500x120.gif"

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(builtinUrl, ColorImage(Color(0xFF1565C0).toArgb(), width = 16, height = 16))
            .intercept(microUrl, ColorImage(Color(0xFF8E24AA).toArgb(), width = 15, height = 15))
            .intercept(persoUrl, ColorImage(Color(0xFF2E7D32).toArgb(), width = 70, height = 50))
            .intercept(giantUrl, ColorImage(Color(0xFFC62828).toArgb(), width = 200, height = 140))
            .intercept(bannerUrl, ColorImage(Color(0xFFC62828).toArgb(), width = 500, height = 120))
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    /** Pre-seed the cache with the same native sizes the fake engine serves → synchronous render. */
    private fun seededCache(): IntrinsicMediaSizeCache = DefaultIntrinsicMediaSizeCache().apply {
        putSuccess(builtinUrl, IntSize(16, 16))
        putSuccess(microUrl, IntSize(15, 15))
        putSuccess(persoUrl, IntSize(70, 50))
        putSuccess(giantUrl, IntSize(200, 140))
        putSuccess(bannerUrl, IntSize(500, 120))
    }

    @Test
    fun adaptiveSmileySizesInline() {
        capture("smiley_175_adaptive_sizes", widthDp = 360) {
            PostRenderer(content = adaptiveSizesContent())
        }
    }

    @Test
    fun tallSmileyGrowsLineNoOverlap() {
        capture("smiley_175_tall_no_overlap", widthDp = 360) {
            PostRenderer(content = tallInTextContent())
        }
    }

    @Test
    fun largePersoRelativeCapInNarrowQuote() {
        // Narrow capture (280dp) so 90% of the quote's content width is below the 240sp absolute width
        // cap — the relative cap then visibly bites and the wide banner stops ~10% short of the edge.
        capture("smiley_175_relative_cap_in_quote", widthDp = 280) {
            PostRenderer(content = bannerInQuoteContent())
        }
    }

    private fun adaptiveSizesContent() = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(
                inlines = listOf(
                    PostInline.Text("Builtin "),
                    builtin("jap"),
                    PostInline.Text(" (16px) · micro perso "),
                    perso("micro15", microUrl),
                    PostInline.Text(" (15px, jamais upscalé) · perso "),
                    perso("franzhermann", persoUrl),
                    PostInline.Text(" (70×50) · géant "),
                    perso("giant", giantUrl),
                    PostInline.Text(" (200×140 capé à 70sp). Les tailles viennent de l'image, pas d'un bucket fixe."),
                ),
            ),
        ),
    )

    private fun tallInTextContent() = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(
                inlines = listOf(
                    PostInline.Text(
                        "Texte avant pour avoir une ligne au-dessus qui pourrait être percutée. " +
                            "On insère un grand smiley ",
                    ),
                    perso("giant", giantUrl),
                    PostInline.Text(
                        " au milieu du paragraphe : la ligne grandit pour le contenir et il ne " +
                            "chevauche ni la ligne du dessus ni celle du dessous. Suite du texte ensuite.",
                    ),
                ),
            ),
        ),
    )

    private fun bannerInQuoteContent() = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(inlines = listOf(PostInline.Text("Dans une citation, le conteneur est plus étroit :"))),
            PostBlock.Quote(
                author = "franzhermann",
                numreponse = null,
                page = null,
                content = PostContent(
                    blocks = listOf(
                        PostBlock.Paragraph(
                            inlines = listOf(
                                PostInline.Text("Une bannière large "),
                                perso("banner", bannerUrl),
                                PostInline.Text(
                                    " est ramenée à ~90% de la largeur dispo (cap relatif RF1 " +
                                        "max-width:90%) : elle s'arrête avant le bord et ne déborde pas.",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun builtin(code: String) =
        PostInline.Smiley(kind = SmileyKind.Builtin(code), imageUrl = builtinUrl)

    private fun perso(name: String, url: String) =
        PostInline.Smiley(kind = SmileyKind.Perso(name), imageUrl = url)

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
