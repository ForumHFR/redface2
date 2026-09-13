package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.Decoder
import coil3.disk.DiskCache
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.svg.SvgDecoder
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real HTTP bodies and real decoders: requests, transfers and decodes are distinct counters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ContentMediaTransferTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<ImageRequest>()
    private val headerDecodes = AtomicInteger()
    private val painterDecodes = AtomicInteger()
    private val targets = CopyOnWriteArrayList<coil3.size.Size>()
    private var loader: ImageLoader? = null

    @After
    fun close() {
        loader?.shutdown()
        server.shutdown()
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun mount(
        body: ByteArray,
        mime: String,
        policy: PostMediaDiskCachePolicy,
    ): Pair<String, MediaAttemptLedger> {
        server.enqueue(MockResponse().setHeader("Content-Type", mime).setBody(Buffer().write(body)))
        // A second fetch would get bytes as well, making a duplicate visible as a count failure.
        server.enqueue(MockResponse().setHeader("Content-Type", mime).setBody(Buffer().write(body)))
        val url = server.url("/content").toString()
        val imageLoader = ImageLoader.Builder(context)
            .diskCache { DiskCache.Builder().directory(temporary.newFolder("coil").toOkioPath()).build() }
            .components { add(SvgDecoder.Factory()) }
            .eventListener(object : EventListener() {
                override fun onStart(request: ImageRequest) {
                    requests.add(request)
                }

                override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
                    if (request.decoderFactory is ProbeMetadataDecoder.Factory) {
                        headerDecodes.incrementAndGet()
                    } else {
                        painterDecodes.incrementAndGet()
                        targets.add(options.size)
                    }
                }
            })
            .build()
        loader = imageLoader
        SingletonImageLoader.setUnsafe(imageLoader)
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalMediaAttemptLedger provides ledger,
                    LocalIntrinsicMediaSizeCache provides cache,
                    LocalPostMediaDiskCachePolicy provides policy,
                ) {
                    Box(Modifier.width(320.dp)) {
                        PostRenderer(
                            PostContent(listOf(PostBlock.Paragraph(listOf(
                                PostInline.Text("image "), PostInline.InlineImage(url, "content"),
                            )))),
                        )
                    }
                }
            }
        }
        compose.waitUntil(10_000) { ledger.hasSucceeded(url, MediaAttemptKind.PAINTER) }
        compose.waitForIdle()
        return url to ledger
    }

    @Test
    fun `public PNG costs two requests one transfer one header decode and one painter decode`() {
        val (url, ledger) = mount(png(), "image/png", PostMediaDiskCachePolicy.ENABLED)
        assertEquals(MediaAttemptKind.PROBE, ledger.geometryOf(url)?.provenance)
        assertCounts(requestCount = 2, probeCount = 1)
    }

    @Test
    fun `SVG G2 costs two requests one transfer and no cold to measured second decode`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 60">
            <rect width="100" height="60" fill="red"/></svg>""".toByteArray()
        val (url, ledger) = mount(svg, "image/svg+xml", PostMediaDiskCachePolicy.ENABLED)
        assertEquals(MediaAttemptKind.PAINTER, ledger.geometryOf(url)?.provenance)
        assertCounts(requestCount = 2, probeCount = 1)
        val expected = coil3.size.Size(1024, 768) // 320dp × density 3: width cap 912px; height 600px.
        assertEquals(expected, targets.single())
    }

    @Test
    fun `private content costs one request one transfer one decode and no content probe`() {
        val (url, ledger) = mount(png(), "image/png", PostMediaDiskCachePolicy.DISABLED)
        assertEquals(MediaAttemptKind.PAINTER, ledger.geometryOf(url)?.provenance)
        assertCounts(requestCount = 1, probeCount = 0)
        assertTrue(requests.all { !it.diskCachePolicy.readEnabled && !it.diskCachePolicy.writeEnabled })
    }

    private fun assertCounts(requestCount: Int, probeCount: Int) {
        assertEquals("Coil requests", requestCount, requests.size)
        assertEquals("HTTP body transfers", 1, server.requestCount)
        assertEquals("header-only decodes", probeCount, headerDecodes.get())
        assertEquals("pixel/vector decodes", 1, painterDecodes.get())
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
