package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bounds at controlled stable stages; real transport/decode counting is in ContentMediaTransferTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererContentGeometryTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val url = "https://images.example.org/shared-content"
    private val ledger = MediaAttemptLedger()
    private val cache = DefaultIntrinsicMediaSizeCache(maxEntries = 1)
    private val requests = CopyOnWriteArrayList<ImageRequest>()
    private val releaseProbe = CompletableDeferred<Unit>()
    private val releasePainter = CompletableDeferred<Unit>()
    private var mounted by mutableStateOf(true)
    private var width by mutableStateOf(320.dp)
    private var epoch by mutableStateOf(0)

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun installLoader() {
        val engine = FakeImageLoaderEngine.Builder().intercept(url, ColorImage(width = 900, height = 600)).build()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        requests.add(chain.request)
                        if (chain.request.decoderFactory is ProbeMetadataDecoder.Factory) {
                            releaseProbe.await()
                        } else {
                            releasePainter.await()
                        }
                        return chain.proceed()
                    }
                })
                add(engine)
            }.build(),
        )
    }

    private fun inline(description: String) = PostBlock.Paragraph(
        listOf(PostInline.Text("image "), PostInline.InlineImage(url, description)),
    )

    private fun mount(policy: PostMediaDiskCachePolicy = PostMediaDiskCachePolicy.ENABLED) {
        installLoader()
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalMediaAttemptLedger provides ledger,
                    LocalIntrinsicMediaSizeCache provides cache,
                    LocalPostMediaDiskCachePolicy provides policy,
                ) {
                    Box(Modifier.width(width)) {
                        if (mounted) {
                            PostRenderer(
                                PostContent(
                                    listOf(
                                        PostBlock.Paragraph(listOf(PostInline.Text("epoch $epoch"))),
                                        PostBlock.Image(url, "block"),
                                        inline("inline"),
                                        PostBlock.Quote(
                                            author = "auteur", numreponse = null, page = null,
                                            content = PostContent(listOf(inline("quote"))),
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun boxes(): List<DpRect> = listOf("block", "inline", "quote").map {
        compose.onNodeWithContentDescription(it).getBoundsInRoot()
    }

    private fun painters() = requests.count { it.decoderFactory !is ProbeMetadataDecoder.Factory }

    private fun awaitPainters(count: Int) {
        // Drain the paused Robolectric main looper first: a snapshot write made from runOnIdle
        // (retry, remount, resize) needs one idle pass before waitUntil can observe Coil's requests.
        compose.waitForIdle()
        compose.waitUntil(5_000) { painters() == count }
        // Starting three requests alone does not prove all occurrence callbacks have settled.
        compose.waitUntil(5_000) {
            listOf("block", "inline", "quote").all {
                !compose.onNodeWithContentDescription(it).fetchSemanticsNode()
                    .config.contains(SemanticsProperties.StateDescription)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `cold shared URL waits then corrects once in block inline and quote with independent caps`() {
        mount()
        compose.waitUntil(5_000) { requests.isNotEmpty() }
        val cold = boxes()
        assertEquals(0, painters())
        compose.runOnIdle { releaseProbe.complete(Unit) }
        compose.waitUntil(5_000) { ledger.geometryOf(url) != null }
        val exact = boxes()
        assertNotEquals(cold, exact)
        assertTrue((exact[2].right - exact[2].left).value <= (exact[1].right - exact[1].left).value)
        compose.runOnIdle { releasePainter.complete(Unit) }
        awaitPainters(3)
        assertEquals(exact, boxes())
        assertEquals(1, requests.count { it.decoderFactory is ProbeMetadataDecoder.Factory })
        assertStableAfterMetadataAndEviction(exact)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/lot-b-content-cold-settled.png")
    }

    @Test
    fun `warm mount has no correction and recycling and constraints count as separate painters`() {
        ledger.acceptGeometry(url, 0, IntrinsicMediaMetadata(IntSize(900, 600), null), MediaAttemptKind.PROBE)
        mount()
        val warm = boxes()
        compose.runOnIdle { releasePainter.complete(Unit) }
        awaitPainters(3)
        assertEquals(warm, boxes())
        assertFalse(requests.any { it.decoderFactory is ProbeMetadataDecoder.Factory })
        assertStableAfterMetadataAndEviction(warm)

        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted = true }
        awaitPainters(9)
        assertEquals(warm, boxes())
        compose.runOnIdle { width = 240.dp }
        awaitPainters(12)
        assertNotEquals(warm, boxes())
        assertEquals(0, requests.count { it.decoderFactory is ProbeMetadataDecoder.Factory })
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/lot-b-content-warm-resized.png")
    }

    @Test
    fun `private host bypasses content probes and freezes G2 through late geometry`() {
        mount(PostMediaDiskCachePolicy.DISABLED)
        compose.waitUntil(5_000) { painters() == 1 }
        val initialRequest = requests.single()
        val cold = boxes()
        compose.runOnIdle { releasePainter.complete(Unit) }
        awaitPainters(3)
        assertFalse(requests.any { it.decoderFactory is ProbeMetadataDecoder.Factory })
        assertNotEquals(cold, boxes())
        assertTrue(requests.all { !it.diskCachePolicy.readEnabled && !it.diskCachePolicy.writeEnabled })
        assertEquals(initialRequest, requests.first())
        assertStableAfterMetadataAndEviction(boxes())
    }

    private fun assertStableAfterMetadataAndEviction(expected: List<DpRect>) {
        val count = requests.size
        val painterCount = painters()
        compose.runOnIdle {
            ledger.acceptGeometry(
                url, 0, IntrinsicMediaMetadata(IntSize(999, 111), "image/gif"), MediaAttemptKind.PROBE,
            )
            cache.putSuccess("evictor", IntrinsicMediaMetadata(IntSize(1, 1), null))
            epoch++
        }
        compose.waitForIdle()
        assertEquals(expected, boxes())
        assertEquals(count, requests.size)
        compose.runOnIdle { ledger.retryUrl(url) }
        awaitPainters(painterCount + 3)
        assertEquals(expected, boxes())
    }
}
