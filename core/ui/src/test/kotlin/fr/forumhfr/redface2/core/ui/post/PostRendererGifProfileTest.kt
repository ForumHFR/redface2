package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.Size
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.LocalMediaDisplayProfile
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #973 (contrat images §8 [AMENDEMENT-v1.5-2]) — the display profile wired into the RENDERER:
 *
 *  - `eligibleGifBloc` = BLOCK content media whose ATOMIC cache metadata carries the probe MIME
 *    `image/gif` — the URL extension is NEVER authoritative, a `null`/other MIME takes no factor;
 *  - the factor replaces the no-upscale ceiling of the §3 equation on the MEASURED block path
 *    only; the hard caps (fImage × container, capBloc) re-clamp any push past them;
 *  - the COLD §6 slot (no dimensions, no MIME yet) and the INLINE path take no factor — strict
 *    v1.5, pinned here per the §8 re-gate (measured AND cold paths verified);
 *  - the DECODE stays clamped at native: the factor is applied ONCE before decodeSizePx, so an
 *    enlarged GIF decodes its native pixels and is upscaled at draw (§7 unchanged).
 *
 * Screen 360×780 dp @xxhdpi (density 3): fImage cap = 0.95×1080 = 1026 px (342 dp), capBloc =
 * min(780, max(400, 390)) = 400 dp = 1200 px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostRendererGifProfileTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val gifUrl = "https://rehost.diberie.com/Picture/Get/f/anim.gif"

    /** (url, requested decode size) of every NON-probe (painter) request. */
    private val recordedDecodeSizes = CopyOnWriteArrayList<Pair<String, Size>>()

    @OptIn(DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(
                { it is String && it.startsWith("https://rehost.diberie.com/") },
                ColorImage(0xFF2E7D32.toInt(), width = 400, height = 300),
            )
            .build()
        val recorder = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                val data = chain.request.data
                if (data is String && chain.request.decoderFactory !is ProbeMetadataDecoder.Factory) {
                    recordedDecodeSizes.add(data to chain.size)
                }
                return chain.proceed()
            }
        }
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components {
                add(recorder)
                add(engine)
            }.build(),
        )
    }

    private fun paragraph(vararg inlines: PostInline) = PostContent(
        blocks = listOf(PostBlock.Paragraph(inlines = inlines.toList())),
    )

    private fun gifCache(url: String = gifUrl, native: IntSize = IntSize(400, 300), mime: String? = "image/gif") =
        DefaultIntrinsicMediaSizeCache().apply {
            putSuccess(url, IntrinsicMediaMetadata(native, mimeType = mime))
        }

    private fun setPost(
        cache: IntrinsicMediaSizeCache,
        profile: MediaDisplayProfile? = null,
        content: PostContent,
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    if (profile != null) {
                        CompositionLocalProvider(LocalMediaDisplayProfile provides profile) {
                            PostRenderer(content = content)
                        }
                    } else {
                        PostRenderer(content = content)
                    }
                }
            }
        }
    }

    private val DpRect.w get() = (right - left).value
    private val DpRect.h get() = (bottom - top).value
    private fun img(url: String, desc: String) = PostInline.InlineImage(url = url, description = desc)

    // ---------- chemin BLOC mesuré : le facteur s'applique ----------

    @Test
    fun `an eligible block GIF is enlarged by the default M profile`() {
        // No explicit provider: the THEME default (M, ×1,5) must flow. 400×300 px GIF under both
        // caps → 600×450 px = 200×150 dp @d3 (v1.5 rendered 133.3×100).
        setPost(cache = gifCache(), content = paragraph(img(gifUrl, "anim")))
        val bounds = composeTestRule.onNodeWithContentDescription("anim").getBoundsInRoot()
        assertEquals(200f, bounds.w, 2f)
        assertEquals(150f, bounds.h, 2f)
    }

    @Test
    fun `the L profile enlarges a small GIF by two and a half under the caps`() {
        // 400×300 ×2,5 = 1000×750 px, under fImage (1026) and capBloc (1200) → 333.3×250 dp.
        setPost(
            cache = gifCache(),
            profile = MediaDisplayProfile.L,
            content = paragraph(img(gifUrl, "anim")),
        )
        val bounds = composeTestRule.onNodeWithContentDescription("anim").getBoundsInRoot()
        assertEquals(333.3f, bounds.w, 2f)
        assertEquals(250f, bounds.h, 2f)
    }

    @Test
    fun `the width cap re-clamps an L push beyond it`() {
        // 800×600 ×2,5 would reach 2000 px: the fImage cap re-clamps to scale = 1026/800 →
        // 1026×770 px = 342×256.7 dp — the same hard bound as any oversized content image.
        setPost(
            cache = gifCache(native = IntSize(800, 600)),
            profile = MediaDisplayProfile.L,
            content = paragraph(img(gifUrl, "anim")),
        )
        val bounds = composeTestRule.onNodeWithContentDescription("anim").getBoundsInRoot()
        assertEquals(342f, bounds.w, 2f)
        assertEquals(256.7f, bounds.h, 2f)
    }

    // ---------- non-éligibles : no-upscale strict v1.5 ----------

    @Test
    fun `a non-GIF block image keeps the strict no-upscale`() {
        // Same URL shape, but the PROBE said PNG: never eligible, native physical size
        // (400×300 px = 133.3×100 dp) even under L.
        setPost(
            cache = gifCache(mime = "image/png"),
            profile = MediaDisplayProfile.L,
            content = paragraph(img(gifUrl, "photo")),
        )
        val bounds = composeTestRule.onNodeWithContentDescription("photo").getBoundsInRoot()
        assertEquals(133.3f, bounds.w, 2f)
        assertEquals(100f, bounds.h, 2f)
    }

    @Test
    fun `a block image without probe MIME keeps the strict no-upscale`() {
        // MIME null (painter G2 deposit, unidentified container, …) → non-eligible even though
        // the URL SAYS .gif — the extension is never authoritative (§8).
        setPost(
            cache = gifCache(mime = null),
            profile = MediaDisplayProfile.L,
            content = paragraph(img(gifUrl, "sansmime")),
        )
        val bounds = composeTestRule.onNodeWithContentDescription("sansmime").getBoundsInRoot()
        assertEquals(133.3f, bounds.w, 2f)
        assertEquals(100f, bounds.h, 2f)
    }

    @Test
    fun `an inline GIF keeps the v15 inline formula`() {
        // In-prose GIF (80×60, probe MIME image/gif) under L: the INLINE path is strictly
        // unchanged (plafond 1,0) — native physical 80 px = 26.7 dp, never 200 px.
        setPost(
            cache = gifCache(native = IntSize(80, 60)),
            profile = MediaDisplayProfile.L,
            content = paragraph(PostInline.Text("avant "), img(gifUrl, "danstexte"), PostInline.Text(" après")),
        )
        val bounds = composeTestRule.onNodeWithContentDescription("danstexte").getBoundsInRoot()
        assertEquals(80f / 3f, bounds.w, 1.1f)
        assertEquals(60f / 3f, bounds.h, 1.1f)
    }

    @Test
    fun `the cold block slot takes no factor`() {
        // Dead URL (never served, never measured): no dimensions, no MIME → the deterministic §6
        // cold slot, IDENTICAL under L (width = fImage × available, height = 0.75 × width). The
        // .gif extension changes nothing (§8: the URL is never authoritative).
        val deadGif = "https://images.example.org/never-served/cold-anim.gif"
        setPost(
            cache = DefaultIntrinsicMediaSizeCache(),
            profile = MediaDisplayProfile.L,
            content = paragraph(PostInline.Text("titre"), PostInline.LineBreak, img(deadGif, "froide")),
        )
        val bounds = composeTestRule.onNodeWithTag(BLOCK_IMAGE_TEST_TAG).getBoundsInRoot()
        val width = bounds.w
        assertTrue("cold width ~0.95×container, was $width", width in 320f..355f)
        assertEquals(width * 0.75f, bounds.h, 1f)
    }

    // ---------- §7 : le facteur s'applique UNE fois, le décodage reste au natif ----------

    @Test
    fun `the profile factor is applied once - the decode stays at native`() {
        // M enlarges the BOX (200×150 dp) but the painter decode request stays at the native
        // 400×300 px — never 600×450 (a double factor would decode the multiplied box). §7: the
        // enlargement happens at draw.
        setPost(cache = gifCache(), content = paragraph(img(gifUrl, "anim")))
        composeTestRule.waitForIdle()
        val bounds = composeTestRule.onNodeWithContentDescription("anim").getBoundsInRoot()
        assertEquals(200f, bounds.w, 2f)
        val sizes = recordedDecodeSizes.filter { it.first == gifUrl }.map { it.second }
        assertTrue("at least one painter decode must have run", sizes.isNotEmpty())
        sizes.forEach { size ->
            assertEquals("decode must stay at native (was $size)", Size(400, 300), size)
        }
    }

    @Test
    fun `flipping the profile recomputes the box without re-decoding at an upscaled size`() {
        // S → M at runtime: the box mechanically follows the new displayPx… while the ImageRequest
        // remember key (the decode size) does NOT flip — the decode of a profile-bound GIF is
        // native-clamped under BOTH profiles (§7 terminal clamp), so no re-decode is needed nor
        // allowed: exactly ONE painter request, at native size.
        val cache = gifCache()
        var profile by mutableStateOf(MediaDisplayProfile.S)
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalIntrinsicMediaSizeCache provides cache,
                    LocalMediaDisplayProfile provides profile,
                ) {
                    PostRenderer(content = paragraph(img(gifUrl, "anim")))
                }
            }
        }
        val atS = composeTestRule.onNodeWithContentDescription("anim").getBoundsInRoot()
        assertEquals(133.3f, atS.w, 2f)
        assertEquals(100f, atS.h, 2f)

        profile = MediaDisplayProfile.M
        composeTestRule.waitForIdle()

        val atM = composeTestRule.onNodeWithContentDescription("anim").getBoundsInRoot()
        assertEquals(200f, atM.w, 2f)
        assertEquals(150f, atM.h, 2f)
        val sizes = recordedDecodeSizes.filter { it.first == gifUrl }.map { it.second }
        assertEquals("one single native decode across the flip (was $sizes)", listOf(Size(400, 300)), sizes)
    }
}
