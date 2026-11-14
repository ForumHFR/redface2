package fr.forumhfr.redface2

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.svg.SvgDecoder
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #960 P4 — the singleton [coil3.ImageLoader] configuration is the ONLY place decoders are
 * registered (every AsyncImage call site reads the singleton): losing one silently downgrades a
 * whole format to the §6 error slot. Pins the decoder roster — GIF (#109) and SVG (#960 P4,
 * « formats exotiques affichés »: the header-only probe cannot read SVG bounds by design, the
 * painter decodes and the G2 protocol takes the geometry from it). Coil 3 service-loads the
 * decoders of classpath artifacts. The bounded animated factory must precede any service-loaded
 * animated factory so every animated decode shares its semaphore (#1411).
 */
@RunWith(RobolectricTestRunner::class)
class RedfaceApplicationImageLoaderTest {

    @Test
    fun `the singleton loader routes network fetches through the injected image client`() = runTest {
        val imageClientCalled = AtomicBoolean(false)
        val imageClient = OkHttpClient.Builder()
            .addInterceptor {
                imageClientCalled.set(true)
                throw IOException("test stops after reaching the dedicated image client")
            }
            .build()
        val application = RedfaceApplication().apply { this.imageClient = imageClient }
        val loader = application.newImageLoader(RuntimeEnvironment.getApplication())
        val request = ImageRequest.Builder(RuntimeEnvironment.getApplication())
            .data("https://images.invalid/image.png")
            // Coil 3's DEFAULT disk cache is a process-wide singleton rooted at
            // java.io.tmpdir/coil3_disk_cache — shared by every test JVM of a Gradle run.
            // NetworkFetcher consults it BEFORE the network client, so a concurrent JVM can make
            // this request resolve (or fail) without ever reaching the injected client. Pin the
            // request to the network path so the assertion below is deterministic.
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()

        val result = loader.execute(request)

        assertTrue(imageClientCalled.get())
        assertTrue(result is ErrorResult)
        // Coil registers its own engine interceptor; the app must add none of its own (#1368).
        assertTrue(
            "URL rewriting belongs to the image OkHttp client, not Coil (#1368)",
            loader.components.interceptors.all { it::class.qualifiedName.orEmpty().startsWith("coil3.") },
        )
        loader.shutdown()
    }

    @Test
    fun `the singleton loader selects the bounded animated factory before any bare animated factory`() {
        val application = RedfaceApplication().apply { imageClient = OkHttpClient() }
        val loader = application.newImageLoader(RuntimeEnvironment.getApplication())
        try {
            val factories = loader.components.decoderFactories
            assertEquals(1, factories.count { it is BoundedAnimatedDecoderFactory })
            assertTrue(
                "animated decoding must use the shared semaphore before any bare factory (#1411)",
                factories.firstOrNull {
                    it is BoundedAnimatedDecoderFactory || it is AnimatedImageDecoder.Factory
                } is BoundedAnimatedDecoderFactory,
            )
            assertTrue(
                "the SVG decoder must be registered (#960 P4)",
                factories.any { it is SvgDecoder.Factory },
            )
        } finally {
            loader.shutdown()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `concurrent image requests share the bounded decoder factory permit`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val release = CompletableDeferred<Unit>()
        var fetched = 0
        var created = 0
        var active = 0
        var maxActive = 0
        val factory = BoundedAnimatedDecoderFactory(
            delegate = Decoder.Factory { _, _, _ ->
                created++
                Decoder {
                    active++
                    maxActive = maxOf(maxActive, active)
                    try {
                        release.await()
                        DecodeResult(
                            image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImage(),
                            isSampled = false,
                        )
                    } finally {
                        active--
                    }
                }
            },
        )
        val loader = ImageLoader.Builder(context)
            .coroutineContext(dispatcher)
            .components {
                add(Fetcher.Factory<SpyImage> { _, options, _ ->
                    Fetcher {
                        fetched++
                        SourceFetchResult(
                            source = ImageSource(Buffer(), options.fileSystem),
                            mimeType = "image/test",
                            dataSource = DataSource.MEMORY,
                        )
                    }
                })
                add(factory)
            }
            .build()
        try {
            val requests = List(2) { index ->
                async {
                    loader.execute(
                        ImageRequest.Builder(context)
                            .data(SpyImage(index))
                            .size(1)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .build(),
                    )
                }
            }
            runCurrent()

            assertEquals(2, fetched)
            assertEquals(2, created)
            assertEquals(1, active)
            requests.forEach { assertFalse(it.isCompleted) }

            release.complete(Unit)
            requests.forEach { assertTrue(it.await() is SuccessResult) }
            assertEquals(1, maxActive)
            assertEquals(0, active)
        } finally {
            release.complete(Unit)
            loader.shutdown()
        }
    }

    private data class SpyImage(val id: Int)
}
