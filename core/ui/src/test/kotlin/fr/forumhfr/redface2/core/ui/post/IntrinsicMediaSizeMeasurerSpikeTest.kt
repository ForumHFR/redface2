package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.test.FakeImageLoaderEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #175 SPIKE — compiler + runtime gate for the Coil intrinsic-measurement path.
 *
 * Confirms (the design study flagged these as NOT provable by docs, only by the compiler):
 *  - `coil3.Image.width/height` is the dimension API after `execute(...)` (#257: the request is now a
 *    probe request (#959: header-only decoder now); the `ColorImage` fixtures are ≤ 70px so the
 *    bound is a no-op and these assertions are unchanged) ;
 *  - `coil3.test.ColorImage(width, height)` exists and propagates its dimensions to `image.width/height`
 *    (so Robolectric tests can pin sizes deterministically, no network/decode) ;
 *  - `FakeImageLoaderEngine` + `runTest` drive the suspend measurement.
 *
 * If this compiles and passes, the Coil API assumptions of the #175 plan hold.
 */
@RunWith(RobolectricTestRunner::class)
class IntrinsicMediaSizeMeasurerSpikeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `measures native dimensions of fake smileys via Coil`() = runTest {
        val engine = FakeImageLoaderEngine.Builder()
            .intercept("https://hfr/perso70x50.gif", ColorImage(width = 70, height = 50))
            .intercept("https://hfr/micro15x15.gif", ColorImage(width = 15, height = 15))
            .intercept("https://hfr/builtin16.gif", ColorImage(width = 16, height = 16))
            .build()
        val loader = ImageLoader.Builder(context).components { add(engine) }.build()

        assertEquals(IntSize(70, 50), measureIntrinsicMediaSize("https://hfr/perso70x50.gif", context, loader)?.size)
        assertEquals(IntSize(15, 15), measureIntrinsicMediaSize("https://hfr/micro15x15.gif", context, loader)?.size)
        assertEquals(IntSize(16, 16), measureIntrinsicMediaSize("https://hfr/builtin16.gif", context, loader)?.size)
    }
}
