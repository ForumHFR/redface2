package fr.forumhfr.redface2.feature.editor

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
import coil3.SingletonImageLoader
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.testing.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1417: star matrix per sprites/offline scene: both widths, text at 1.3, dark and AMOLED once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, coil3.annotation.DelicateCoilApi::class)
class MessageIconPickerRoborazziTest {
    @get:Rule val compose = createComposeRule()

    @After
    fun resetImageLoader() = SingletonImageLoader.reset()

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
        installMessageIconPickerTestImages(offline)
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
            listOf(1f, 1.3f).filter { scale ->
                (theme == "light" && scale == 1f) ||
                    (width == 360 && (theme == "light" || scale == 1f))
            }.forEach { scale ->
                compose.runOnIdle { sample.value = Sample(theme, scale) }
                compose.waitForIdle()
                compose.onNodeWithText("Clin d'œil").assertIsDisplayed()
                val target = compose.onNodeWithTag("tone-capture").assertWidthIsEqualTo(width.dp)
                val state = if (offline) "offline" else "sprites"
                target.captureRoboImage("src/test/screenshots/message_tone_${width}_fs${scale}_${theme}_$state.png")
            }
        }
    }

    private data class Sample(val theme: String, val scale: Float)
}
