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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.MultipleFailureException
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ED-133: summary text budget and both submit controls at system 1.3 × L. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorErrorSummaryLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `all maximum summaries fit 150 dp at system 1_3 and L and submit stays 48 dp`() {
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
                        EditorText(R.plurals.editor_summary_stopped, listOf(10, 10), quantity = 10))
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
                            EditorBarSubmit(
                                ArmedSubmitState(armed, enabled = true),
                                ArmedSubmitActions({}, {}, {}),
                                compactHeight = false,
                                consumeInformation = { false },
                            )
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
        val failures = mutableListOf<Throwable>()
        val rootWidth = compose.onRoot().getUnclippedBoundsInRoot().width.value
        if (kotlin.math.abs(rootWidth - 360f) > 0.5f) failures += AssertionError("Root width: ${rootWidth}dp")
        if (layouts.isEmpty()) failures += AssertionError("ED-133: no summaries measured")
        val violations = linkedMapOf<String, List<String>>()
        layouts.forEach { (label, layout) ->
            val density = layout.layoutInput.density
            val intrinsic = layout.multiParagraph.intrinsics.maxIntrinsicWidth / density.density
            println("ED-133 $label: intrinsic=${intrinsic}dp, lines=${layout.lineCount}, " +
                "ellipse=${layout.isLineEllipsized(0)}, budget=$EDITOR_SUMMARY_TEXT_WIDTH_DP")
            summaryViolations(label, layout).takeIf { it.isNotEmpty() }?.let { violations[label] = it }
        }
        // Keep the full-screen control's 48 dp reservation and the legacy button's width coverage.
        listOf("Envoyer", "Confirmer").forEach { label ->
            val bounds = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            println("ED-133 EditorBarSubmit $label: width=${bounds.width} height=${bounds.height}, target=48dp")
            if (kotlin.math.abs(bounds.width.value - 48f) > 0.5f ||
                kotlin.math.abs(bounds.height.value - 48f) > 0.5f) {
                failures += AssertionError("EditorBarSubmit $label: $bounds != 48 × 48dp")
            }
            val width = compose.onNodeWithText(label).getUnclippedBoundsInRoot().width.value
            println("ED-133 ArmedSubmitButton $label: width=${width}dp, budget=150")
            if (width > 150f) failures += AssertionError("ArmedSubmitButton $label: ${width}dp > 150dp")
        }
        val d44Labels = setOf("Envoi incertain", "Brouillon non écrit")
        val ordinary = violations.filterKeys { it !in d44Labels }
        val d44 = violations.filterKeys { it in d44Labels }
        if (ordinary.isNotEmpty()) failures += AssertionError("ED-133 dépassements complets : $ordinary")
        // Separate assertion: D44 text is protected and must never be shortened to make this green.
        if (d44.isNotEmpty()) failures += AssertionError("Réserve D44 — textes inchangés, budget 150 dp : $d44")
        MultipleFailureException.assertEmpty(failures)
    }

    private fun summaryViolations(label: String, layout: TextLayoutResult): List<String> = buildList {
        val density = layout.layoutInput.density
        val intrinsic = layout.multiParagraph.intrinsics.maxIntrinsicWidth / density.density
        if (kotlin.math.abs(density.density - 3f) > 0.001f) add("density=${density.density}")
        if (kotlin.math.abs(density.fontScale - 1.3f) > 0.001f) add("fontScale=${density.fontScale}")
        val expectedSize = 11f * FontScalePreference.L.factor
        if (kotlin.math.abs(layout.layoutInput.style.fontSize.value - expectedSize) > 0.001f) {
            add("style != labelSmall L")
        }
        if (intrinsic > EDITOR_SUMMARY_TEXT_WIDTH_DP) add("intrinsic=${intrinsic}dp")
        if (layout.lineCount != 1) add("lines=${layout.lineCount}")
        if (layout.multiParagraph.didExceedMaxLines) add("maxLines exceeded")
        if (layout.isLineEllipsized(0)) add("ellipsis")
        if (layout.getLineEnd(0) != label.length) add("last character missing")
        if (layout.getLineRight(0) / density.density > EDITOR_SUMMARY_TEXT_WIDTH_DP) add("right edge > budget")
    }
}
