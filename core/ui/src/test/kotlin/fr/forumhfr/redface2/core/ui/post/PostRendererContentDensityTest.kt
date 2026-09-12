package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
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

/** #876 v1.6-1: the renderer wires the same ceiling before any px-to-dp/sp conversion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostRendererContentDensityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val rootUrl = "https://images.example.org/"
    private val recordedDecodeSizes = CopyOnWriteArrayList<Pair<String, Size>>()

    @OptIn(DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(
                { it is String && it.startsWith(rootUrl) },
                ColorImage(0xFF2E7D32.toInt(), width = 80, height = 60),
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
            ImageLoader.Builder(context).components { add(recorder); add(engine) }.build(),
        )
    }

    private fun content(name: String, inline: Boolean = false): PostContent {
        val image = PostInline.InlineImage(url = rootUrl + name, description = name)
        return PostContent(
            blocks = listOf(
                PostBlock.Paragraph(
                    inlines = if (inline) listOf(PostInline.Text("avant "), image, PostInline.Text(" après"))
                    else listOf(image),
                ),
            ),
        )
    }

    private fun assertNativeDp(name: String) {
        val bounds = composeTestRule.onNodeWithContentDescription(name).getBoundsInRoot()
        assertEquals(name, 80f, (bounds.right - bounds.left).value, 1.1f)
        assertEquals(name, 60f, (bounds.bottom - bounds.top).value, 1.1f)
    }

    @Test
    fun `GIF without extension lying extension static GIF and missing MIME share the static ceiling`() {
        // Metadata variants deliberately share the same native pair. Real GIF bytes are covered
        // by IntrinsicMediaProbeTest; this test exercises their sizing at the renderer boundary.
        val cases = listOf(
            "without-extension" to "image/gif",
            "gif-behind.jpg" to "image/gif",
            "static.gif" to "image/gif",
            "jpeg-behind.gif" to "image/jpeg",
            "painter-only.gif" to null,
        )
        val cache = DefaultIntrinsicMediaSizeCache().apply {
            cases.forEach { (name, mime) ->
                putSuccess(rootUrl + name, IntrinsicMediaMetadata(IntSize(80, 60), mime))
            }
        }
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    Column { cases.forEach { (name, _) -> PostRenderer(content(name)) } }
                }
            }
        }
        cases.forEach { (name, _) -> assertNativeDp(name) }
    }

    @Test
    fun `inline content is enlarged once and its painter still requests native pixels`() {
        val name = "inline.gif"
        val cache = DefaultIntrinsicMediaSizeCache().apply {
            putSuccess(rootUrl + name, IntrinsicMediaMetadata(IntSize(80, 60), "image/gif"))
        }
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    PostRenderer(content(name, inline = true))
                }
            }
        }
        assertNativeDp(name)
        composeTestRule.waitForIdle()
        val sizes = recordedDecodeSizes.filter { it.first == rootUrl + name }.map { it.second }
        assertTrue("the inline painter must have run", sizes.isNotEmpty())
        sizes.forEach { assertEquals(Size(80, 60), it) }
    }

    @Test
    fun `changing density recomputes the block box without requesting an enlarged decode`() {
        val name = "density.jpg"
        val cache = DefaultIntrinsicMediaSizeCache().apply {
            putSuccess(rootUrl + name, IntrinsicMediaMetadata(IntSize(80, 60), "image/jpeg"))
        }
        var density by mutableStateOf(1f)
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density, 1f),
                    LocalIntrinsicMediaSizeCache provides cache,
                ) {
                    PostRenderer(content(name))
                }
            }
        }
        composeTestRule.waitForIdle()
        val before = composeTestRule.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
        assertEquals(80f, before.width, 1f)
        composeTestRule.runOnIdle { density = 3f }
        composeTestRule.waitForIdle()
        val after = composeTestRule.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
        assertEquals(240f, after.width, 1f)
        assertEquals(180f, after.height, 1f)
        val sizes = recordedDecodeSizes.filter { it.first == rootUrl + name }.map { it.second }
        assertEquals(listOf(Size(80, 60)), sizes)
    }
}
