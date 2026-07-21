package fr.forumhfr.redface2

import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
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
 * removing the coil-gif/coil-svg dependency (or the explicit adds with service loading off)
 * trips it either way.
 */
@RunWith(RobolectricTestRunner::class)
class RedfaceApplicationImageLoaderTest {

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
