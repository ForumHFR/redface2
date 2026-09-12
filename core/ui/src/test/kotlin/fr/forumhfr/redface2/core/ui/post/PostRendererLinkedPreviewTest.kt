package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
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
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.size.Size
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
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
 * #876 v1.6-1 — linked and unlinked content share the density ceiling. Former preview guards
 * (same host, distinct link, native axis <= 400) no longer affect sizing. Decode remains native;
 * cold and painter-only paths retain their geometry authority.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererLinkedPreviewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** The real diberie shape: a `/t/` thumbnail wrapped in a link to its `/f/` full-size page. */
    private val thumbUrl = "https://reho.st/t/abcdef0123456789.jpg"
    private val fullUrl = "https://reho.st/f/abcdef0123456789.jpg"
    private val foreignUrl = "https://example.org/f/abcdef0123456789.jpg"

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    /** Requested sizes of non-probe painter requests. */
    private val recordedDecodeSizes = CopyOnWriteArrayList<Pair<String, Size>>()

    @OptIn(DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(
                { it is String && it.startsWith("https://reho.st/") },
                ColorImage(0xFF2E7D32.toInt(), width = 150, height = 112),
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
            ImageLoader.Builder(appContext).components {
                add(recorder)
                add(engine)
            }.build(),
        )
    }

    private fun img(url: String, desc: String) = PostInline.InlineImage(url = url, description = desc)

    /** `[url=link][img]url[/img][/url]` isolated on its line → the BLOCK path with a linkUrl. */
    private fun linkedBlock(url: String, link: String, desc: String) = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(inlines = listOf(PostInline.Link(url = link, children = listOf(img(url, desc))))),
        ),
    )

    private fun cacheOf(
        url: String = thumbUrl,
        native: IntSize = IntSize(150, 112),
        mime: String? = "image/jpeg",
    ) = DefaultIntrinsicMediaSizeCache().apply {
        putSuccess(url, IntrinsicMediaMetadata(native, mimeType = mime))
    }

    private fun setPost(
        cache: IntrinsicMediaSizeCache,
        content: PostContent,
        ledger: MediaAttemptLedger? = null,
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    val withLedger = ledger ?: LocalMediaAttemptLedger.current
                    CompositionLocalProvider(
                        LocalMediaAttemptLedger provides withLedger,
                    ) {
                        PostRenderer(content = content)
                    }
                }
            }
        }
    }

    private val DpRect.w get() = (right - left).value
    private val DpRect.h get() = (bottom - top).value

    private fun boundsOf(desc: String) = composeTestRule.onNodeWithContentDescription(desc).getBoundsInRoot()

    private val configuredDensity: Float get() = appContext.resources.displayMetrics.density

    @Test
    fun `une miniature liée de 150x112 suit le plafond de contenu`() {
        setPost(cache = cacheOf(), content = linkedBlock(thumbUrl, fullUrl, "miniature"))
        val bounds = boundsOf("miniature")
        assertEquals(150f, bounds.w, 2f)
        assertEquals(112f, bounds.h, 2f)
    }

    @Test
    fun `un auto-lien reçoit le même agrandissement que la miniature liée`() {
        setPost(cache = cacheOf(), content = linkedBlock(thumbUrl, thumbUrl, "autolien"))
        val bounds = boundsOf("autolien")
        assertEquals(150f, bounds.w, 2f)
        assertEquals(112f, bounds.h, 2f)
    }

    @Test
    fun `une miniature liée vers un autre hôte reçoit le même plafond`() {
        setPost(cache = cacheOf(), content = linkedBlock(thumbUrl, foreignUrl, "horshote"))
        val bounds = boundsOf("horshote")
        assertEquals(150f, bounds.w, 2f)
        assertEquals(112f, bounds.h, 2f)
    }

    @Test
    fun `une image liée de 800x600 grandit jusqu'au cap de largeur`() {
        setPost(
            cache = cacheOf(native = IntSize(800, 600)),
            content = linkedBlock(thumbUrl, fullUrl, "grande"),
        )
        val bounds = boundsOf("grande")
        assertEquals(342f, bounds.w, 2f)
        assertEquals(256.7f, bounds.h, 2f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h780dp-hdpi")
    fun `le GIF lié suit la densité basse sans le facteur L retiré`() {
        assertEquals("le qualifier hdpi doit s'appliquer", 1.5f, configuredDensity, 0.001f)
        setPost(
            cache = cacheOf(mime = "image/gif"),
            content = linkedBlock(thumbUrl, fullUrl, "gifgagne"),
        )
        val bounds = boundsOf("gifgagne")
        assertEquals(150f, bounds.w, 2f)
        assertEquals(112f, bounds.h, 2f)
    }

    @Test
    fun `le décodage d'un aperçu lié agrandi reste borné au natif`() {
        setPost(cache = cacheOf(), content = linkedBlock(thumbUrl, fullUrl, "decodage"))
        composeTestRule.waitForIdle()
        val bounds = boundsOf("decodage")
        assertEquals(150f, bounds.w, 2f)
        val sizes = recordedDecodeSizes.filter { it.first == thumbUrl }.map { it.second }
        assertTrue("at least one painter decode must have run", sizes.isNotEmpty())
        sizes.forEach { size ->
            assertEquals("decode must stay at native (was $size)", Size(150, 112), size)
        }
    }

    @Test
    fun `le hard cap fImage re-clampe un aperçu lié éligible`() {
        setPost(
            cache = cacheOf(native = IntSize(400, 300)),
            content = linkedBlock(thumbUrl, fullUrl, "capee"),
        )
        composeTestRule.waitForIdle()
        val bounds = boundsOf("capee")
        assertEquals(342f, bounds.w, 2f)
        assertEquals(256.7f, bounds.h, 2f)
        val sizes = recordedDecodeSizes.filter { it.first == thumbUrl }.map { it.second }
        assertTrue("at least one painter decode must have run", sizes.isNotEmpty())
        sizes.forEach { size ->
            assertEquals("decode must stay at native (was $size)", Size(400, 300), size)
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h780dp-ldpi")
    fun `à densité inférieure à 1 le plafond de contenu garantit le plancher`() {
        assertEquals("le qualifier ldpi doit s'appliquer", 0.75f, configuredDensity, 0.001f)
        setPost(cache = cacheOf(), content = linkedBlock(thumbUrl, fullUrl, "basse"))
        val bounds = boundsOf("basse")
        assertEquals(200f, bounds.w, 2f)
        assertEquals(149.3f, bounds.h, 2f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h780dp-hdpi")
    fun `à densité 1 virgule 5 le plafond suit la densité`() {
        assertEquals("le qualifier hdpi doit s'appliquer", 1.5f, configuredDensity, 0.001f)
        setPost(cache = cacheOf(), content = linkedBlock(thumbUrl, fullUrl, "moyenne"))
        val bounds = boundsOf("moyenne")
        assertEquals(150f, bounds.w, 2f)
        assertEquals(112f, bounds.h, 2f)
    }

    @OptIn(DelicateCoilApi::class)
    @Test
    fun `le chemin painter-only agrandit dès la première mesure`() {
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(thumbUrl, ColorImage(0xFF6A1B9A.toInt(), width = 150, height = 112))
            .build()
        val probeKiller = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                if (chain.request.decoderFactory is ProbeMetadataDecoder.Factory) {
                    return ErrorResult(
                        image = null,
                        request = chain.request,
                        throwable = IllegalStateException("probe refused by host"),
                    )
                }
                return chain.proceed()
            }
        }
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(appContext).components {
                add(probeKiller)
                add(engine)
            }.build(),
        )
        val cache = DefaultIntrinsicMediaSizeCache()
        setPost(
            cache = cache,
            content = linkedBlock(thumbUrl, fullUrl, "painter"),
            ledger = MediaAttemptLedger(),
        )

        composeTestRule.waitUntil(timeoutMillis = 5_000) { cache.get(thumbUrl) != null }
        assertEquals(IntSize(150, 112), cache.get(thumbUrl)?.size)
        assertEquals("le dépôt painter ne porte AUCUN MIME", null, cache.get(thumbUrl)?.mimeType)
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {}

        val bounds = composeTestRule.onNodeWithTag(BLOCK_IMAGE_TEST_TAG).getBoundsInRoot()
        assertEquals(150f, bounds.w, 2f)
        assertEquals(112f, bounds.h, 2f)
    }

    @Test
    fun `le slot cold d'un aperçu lié ne prend aucun facteur`() {
        val deadUrl = "https://images.example.org/never-served/cold-thumb.jpg"
        val deadLink = "https://images.example.org/never-served/cold-page.jpg"
        setPost(
            cache = DefaultIntrinsicMediaSizeCache(),
            content = linkedBlock(deadUrl, deadLink, "froide"),
            ledger = MediaAttemptLedger(),
        )
        val bounds = composeTestRule.onNodeWithTag(BLOCK_IMAGE_TEST_TAG).getBoundsInRoot()
        val width = bounds.w
        assertTrue("cold width ~0.95×container, was $width", width in 320f..355f)
        assertEquals(width * 0.75f, bounds.h, 1f)
    }

    @Test
    fun `une image inline liée reçoit le même plafond que le bloc`() {
        setPost(
            cache = cacheOf(),
            content = PostContent(
                blocks = listOf(
                    PostBlock.Paragraph(
                        inlines = listOf(
                            PostInline.Text("avant "),
                            PostInline.Link(url = fullUrl, children = listOf(img(thumbUrl, "danstexte"))),
                            PostInline.Text(" après"),
                        ),
                    ),
                ),
            ),
        )
        val bounds = boundsOf("danstexte")
        assertEquals(150f, bounds.w, 1.1f)
        assertEquals(112f, bounds.h, 1.1f)
    }
}
