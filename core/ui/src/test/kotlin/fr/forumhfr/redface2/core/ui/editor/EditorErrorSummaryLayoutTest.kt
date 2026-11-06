package fr.forumhfr.redface2.core.ui.editor

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ED-133 option B: text-only contract; deliberately does not compose the future action bar. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorErrorSummaryLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `all maximum summaries fit 150 dp at system 1_3 and L and submit stays below 150 dp`() {
        val layouts = linkedMapOf<String, TextLayoutResult>()
        compose.setContent {
            val originalDensity = LocalDensity.current
            val config = Configuration(LocalConfiguration.current).apply { fontScale = 1.3f }
            val context = LocalContext.current.createConfigurationContext(config)
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(originalDensity.density, context.resources.configuration.fontScale),
            ) {
                RedfaceTheme(darkTheme = false, reading = ReadingDisplaySettings(fontScale = FontScalePreference.L)) {
                    val measurer = rememberTextMeasurer()
                    val style = MaterialTheme.typography.labelSmall
                    val density = LocalDensity.current
                    val labels = (editorErrorSamples.map { it.summary } +
                        EditorText(R.string.editor_summary_stopped, listOf(10, 10)))
                        .map { it.resolve(context) } + "Accès restreint" // Reserved #242: test only.
                    SideEffect {
                        labels.distinct().forEach { label ->
                            layouts[label] = measurer.measure(
                                AnnotatedString(label), style = style, maxLines = 1,
                                constraints = Constraints(maxWidth = with(density) {
                                    EDITOR_SUMMARY_TEXT_WIDTH_DP.dp.roundToPx()
                                }),
                            )
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        listOf(false, true).forEach { armed ->
                            ArmedSubmitButton(
                                ArmedSubmitState(armed, enabled = true),
                                ArmedSubmitLabels("Envoyer", "Confirmer"),
                                ArmedSubmitActions({}, {}, {}),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(360f, compose.onRoot().getUnclippedBoundsInRoot().width.value, 0.5f)
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { (label, layout) ->
            val density = layout.layoutInput.density
            val intrinsic = layout.multiParagraph.intrinsics.maxIntrinsicWidth / density.density
            val message = "$label: intrinsic=${intrinsic}dp, budget=$EDITOR_SUMMARY_TEXT_WIDTH_DP"
            println("ED-133 $message")
            assertEquals(message, 3f, density.density, 0.001f)
            assertEquals(message, 1.3f, density.fontScale, 0.001f)
            assertEquals(message, 11f * FontScalePreference.L.factor, layout.layoutInput.style.fontSize.value, 0.001f)
            assertTrue(message, intrinsic <= EDITOR_SUMMARY_TEXT_WIDTH_DP)
            assertEquals(message, 1, layout.lineCount)
            assertFalse(message, layout.multiParagraph.didExceedMaxLines)
            assertFalse(message, layout.isLineEllipsized(0))
            assertEquals(message, label.length, layout.getLineEnd(0))
            assertTrue(message, layout.getLineRight(0) / density.density <= EDITOR_SUMMARY_TEXT_WIDTH_DP)
        }
        listOf("Envoyer", "Confirmer").forEach { label ->
            // The button itself includes padding and reserves both labels, even when only one is visible.
            val width = compose.onNodeWithText(label).getUnclippedBoundsInRoot().width.value
            assertTrue("$label button: ${width}dp", width <= 150f)
        }
    }
}
