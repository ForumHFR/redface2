package fr.forumhfr.redface2.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.request.ErrorResult
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.testing.captureRoboImage
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1417: isolated tone block, deterministic 15 px sprites or offline, 360/411 dp × 1/1.3 × three themes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, coil3.annotation.DelicateCoilApi::class)
class MessageIconPickerRoborazziTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun spritesAt360Dp() = captureMatrix(width = 360, offline = false)

    @Test
    fun offlineAt360Dp() = captureMatrix(width = 360, offline = true)

    @Test
    @Config(qualifiers = "fr-rFR-w411dp-h780dp-xxhdpi")
    fun spritesAt411Dp() = captureMatrix(width = 411, offline = false)

    @Test
    @Config(qualifiers = "fr-rFR-w411dp-h780dp-xxhdpi")
    fun offlineAt411Dp() = captureMatrix(width = 411, offline = true)

    private fun captureMatrix(width: Int, offline: Boolean) {
        installImages(offline)
        val sample = mutableStateOf(Sample("light", 1f))
        compose.setContent {
            val current = sample.value
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, current.scale)) {
                RedfaceTheme(darkTheme = current.theme != "light", amoledTheme = current.theme == "amoled",
                    dynamicColor = false) {
                    key(current) {
                        Column {
                            Surface(Modifier.fillMaxWidth().testTag("tone-capture")) {
                                Box(Modifier.padding(16.dp)) {
                                    MessageIconPicker(12, true, {})
                                }
                            }
                        }
                    }
                }
            }
        }
        listOf("light", "dark", "amoled").forEach { theme ->
            listOf(1f, 1.3f).forEach { scale ->
                compose.runOnIdle { sample.value = Sample(theme, scale) }
                compose.waitForIdle()
                compose.onNodeWithText("Clin d'œil").assertIsDisplayed()
                val target = compose.onNodeWithTag("tone-capture").assertWidthIsEqualTo(width.dp)
                val state = if (offline) "offline" else "sprites"
                target.captureRoboImage("src/test/screenshots/message_tone_${width}_fs${scale}_${theme}_$state.png")
            }
        }
    }

    private fun installImages(offline: Boolean) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val builder = FakeImageLoaderEngine.Builder()
        if (offline) {
            builder.default { chain ->
                ErrorResult(image = null, request = chain.request, throwable = IOException("Offline"))
            }
        } else {
            // Synthetic pixel art, not an HFR fixture: edges make accidental filtering visible in the captures.
            val bitmap = Bitmap.createBitmap(15, 15, Bitmap.Config.ARGB_8888)
            for (y in 0 until 15) for (x in 0 until 15) {
                bitmap.setPixel(x, y, if ((x + y) % 2 == 0) 0xFF1565C0.toInt() else 0xFFFFC107.toInt())
            }
            builder.default(bitmap.asImage())
        }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(builder.build()) }.build())
    }

    private data class Sample(val theme: String, val scale: Float)
}
