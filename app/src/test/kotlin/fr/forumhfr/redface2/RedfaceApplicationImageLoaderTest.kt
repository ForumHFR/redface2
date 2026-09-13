package fr.forumhfr.redface2

import coil3.gif.AnimatedImageDecoder
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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
 * decoders of classpath artifacts, so this pins the ROSTER available to the app's loader —
 * removing the coil-gif/coil-svg DEPENDENCY trips it; the explicit adds in RedfaceApplication
 * are documentation + belt for a disabled service loader and are NOT individually pinned here
 * (with service loading active the roster stays green without them — Sol P4).
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
    fun `the singleton loader registers the gif and svg decoders`() {
        val application = RedfaceApplication().apply { imageClient = OkHttpClient() }
        val loader = application.newImageLoader(RuntimeEnvironment.getApplication())
        val factories = loader.components.decoderFactories
        assertTrue(
            "the animated GIF decoder must stay registered (#109)",
            factories.any { it is AnimatedImageDecoder.Factory },
        )
        assertTrue(
            "the SVG decoder must be registered (#960 P4)",
            factories.any { it is SvgDecoder.Factory },
        )
    }
}
