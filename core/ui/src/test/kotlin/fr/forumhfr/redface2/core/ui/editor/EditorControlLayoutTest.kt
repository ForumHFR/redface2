package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.github.takahirom.roborazzi.captureRoboImage
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

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorControlLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `ED133 delivered summaries fit text only budget under injected normal and compact constraints`() {
        val sample = mutableStateOf(controlEvent(EditorEventContent.Problem(EditorErrors.uncertain)))
        val height = mutableStateOf(56.dp)
        var layout: TextLayoutResult? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                RedfaceTheme(reading = ReadingDisplaySettings(fontScale = FontScalePreference.L)) {
                    Surface { Box(Modifier.width(185.dp).height(height.value)) {
                        EditorEventControl(sample.value, 185.dp, { _, _ -> },
                            modifier = Modifier.testTag("control"), onSummaryTextLayout = { layout = it })
                    } }
                }
            }
        }
        val contents = editorErrorSamples.map { EditorEventContent.Problem(it) } + listOf(
            EditorEventContent.InProgress(EditorText(R.string.editor_event_sending)),
            EditorEventContent.Information(EditorText(R.string.editor_event_already_quoted)),
        ) + (1..10).flatMap { total ->
            (1..total).map { index ->
                EditorEventContent.InProgress(EditorText(R.string.editor_event_uploading, listOf(index, total)))
            } + finishedUpload(total)
        } + (2..10).flatMap { total ->
            (0 until total).map { completed ->
                EditorEventContent.Problem(
                    EditorErrors.stopped(EditorErrors.tooLarge, UploadProgress(completed, total)),
                )
            }
        }
        listOf(56.dp, 48.dp).forEach { injectedHeight ->
            compose.runOnIdle { height.value = injectedHeight }
            contents.forEach { content ->
                compose.runOnIdle { sample.value = controlEvent(content) }
                compose.waitForIdle()
                val result = requireNotNull(layout)
                assertEquals(1.3f, result.layoutInput.density.fontScale, 0.001f)
                assertEquals(11f * FontScalePreference.L.factor, result.layoutInput.style.fontSize.value, 0.001f)
                assertEquals(content.toString(), 1, result.lineCount)
                assertFalse(content.toString(), result.isLineEllipsized(0))
                assertEquals(result.layoutInput.text.length, result.getLineEnd(0))
                val pixelsPerDp = result.layoutInput.density.density
                assertTrue(result.size.width / pixelsPerDp <= EDITOR_SUMMARY_TEXT_WIDTH_DP + 0.5f)
                val intrinsic = result.multiParagraph.intrinsics.maxIntrinsicWidth / pixelsPerDp
                assertTrue(content.toString(), intrinsic <= EDITOR_SUMMARY_TEXT_WIDTH_DP)
                if (content is EditorEventContent.Finished) {
                    val count = requireNotNull(content.summary.quantity)
                    assertEquals(if (count == 1) "1 insérée" else "$count insérées", result.layoutInput.text.text)
                    compose.onAllNodes(hasContentDescription(
                        if (count == 1) "1 image insérée, Masquer" else "$count images insérées, Masquer",
                    )).assertCountEquals(2) // Consultable name and permanent live announcement.
                }
                val bounds = compose.onNodeWithTag("control").getUnclippedBoundsInRoot()
                assertEquals(185f, bounds.width.value, 0.5f)
                assertEquals(48f, bounds.height.value, 0.5f)
                if ((content as? EditorEventContent.Problem)?.error?.cause in
                    listOf(EditorCause.Uncertain, EditorCause.DraftWriteFailed)) {
                    compose.onNodeWithTag("control").captureRoboImage(
                        "build/outputs/roborazzi/editor_budget_${contents.indexOf(content)}_" +
                            "${injectedHeight.value}.png",
                    )
                }
            }
        }
    }

    @Test
    fun `ED133 a 155 dp intrinsic text is not ellipsized in the 161 dp slot`() {
        val width = mutableStateOf(185.dp)
        var layout: TextLayoutResult? = null
        compose.setContent {
            RedfaceTheme {
                val density = LocalDensity.current
                val reference = rememberTextMeasurer().measure(
                    EditorErrors.draftWriteFailed.summary.resolve(), style = MaterialTheme.typography.labelSmall,
                ).multiParagraph.intrinsics.maxIntrinsicWidth
                // Simulate a font/device variation without weakening the catalogue's 150 dp assertion.
                val scale = density.fontScale * with(density) { 155.dp.toPx() } / reference
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    EditorEventControl(controlEvent(EditorEventContent.Problem(EditorErrors.draftWriteFailed)),
                        width.value, { _, _ -> }, onSummaryTextLayout = { layout = it })
                }
            }
        }
        listOf(185.dp, 90.dp).forEach { injectedWidth ->
            compose.runOnIdle { width.value = injectedWidth }
            compose.waitForIdle()
            val result = requireNotNull(layout)
            val pixelsPerDp = result.layoutInput.density.density
            assertEquals(155f, result.multiParagraph.intrinsics.maxIntrinsicWidth / pixelsPerDp, 1f)
            assertEquals(injectedWidth.value - 24f, result.layoutInput.constraints.maxWidth / pixelsPerDp, 0.5f)
            assertEquals(1, result.lineCount)
            assertEquals(injectedWidth == 90.dp, result.isLineEllipsized(0))
            if (injectedWidth == 185.dp) assertEquals(result.layoutInput.text.length, result.getLineEnd(0))
        }
    }

    @Test
    fun `ED91 narrow fallback ellipsizes visually while preserving the full accessible name`() {
        var layout: TextLayoutResult? = null
        compose.setContent {
            RedfaceTheme { Surface {
                EditorEventControl(controlEvent(EditorEventContent.Problem(EditorErrors.draftWriteFailed)),
                    width = 90.dp, onAction = { _, _ -> }, onSummaryTextLayout = { layout = it })
            } }
        }
        compose.waitForIdle()
        assertTrue(requireNotNull(layout).isLineEllipsized(0))
        compose.onNode(hasClickAction()).assert(hasContentDescription("Brouillon non écrit, Afficher les détails"))
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        val bounds = compose.onNode(hasClickAction()).getUnclippedBoundsInRoot()
        assertEquals(90f, bounds.width.value, 0.5f)
        assertTrue(bounds.height.value >= 48f)
        compose.onNode(hasClickAction()).captureRoboImage("build/outputs/roborazzi/editor_event_narrow.png")
    }

    @Test
    fun `ED40 absent events keep only the live footprint and visible targets center in injected heights`() {
        val event = mutableStateOf<EditorEvent?>(null)
        val dark = mutableStateOf(false)
        val height = mutableStateOf(56.dp)
        compose.setContent {
            RedfaceTheme(darkTheme = dark.value) { Surface {
                Row(Modifier.width(360.dp).height(height.value).testTag("sample")
                    .padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(185.dp)) {
                        EditorEventControl(event.value, 185.dp, { _, _ -> }, Modifier.testTag("control")
                            .then(if (event.value == null) Modifier else Modifier.height(height.value)))
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.width(139.dp).height(48.dp).testTag("submit-slot"))
                }
            } }
        }
        val cases = listOf(
            null,
            controlEvent(EditorEventContent.Problem(EditorErrors.uncertain)),
            controlEvent(EditorEventContent.Problem(EditorErrors.missingTitle)),
            controlEvent(EditorEventContent.InProgress(EditorText(R.string.editor_event_sending))),
            controlEvent(EditorEventContent.InProgress(uploadText(2), UploadProgress(1, 3))),
            controlEvent(finishedUpload(3)),
            controlEvent(EditorEventContent.Information(EditorText(R.string.editor_event_already_quoted))),
        )
        listOf(false, true).forEach { isDark ->
            listOf(56.dp, 48.dp).forEach { injectedHeight ->
                cases.forEachIndexed { index, sample ->
                    compose.runOnIdle {
                        event.value = sample
                        dark.value = isDark
                        height.value = injectedHeight
                    }
                    compose.onAllNodes(hasClickAction()).assertCountEquals(if (sample?.action == null) 0 else 1)
                    val bounds = compose.onNodeWithTag("sample").getUnclippedBoundsInRoot()
                    assertEquals(360f, bounds.width.value, 0.5f)
                    assertEquals(injectedHeight.value, bounds.height.value, 0.5f)
                    val control = compose.onNodeWithTag("control").getUnclippedBoundsInRoot()
                    assertEquals(if (sample == null) 1f else 185f, control.width.value, 0.5f)
                    assertEquals(if (sample == null) 1f else injectedHeight.value, control.height.value, 0.5f)
                    assertEquals(12f, (control.left - bounds.left).value, 0.5f)
                    if (sample != null) {
                        val icon = compose.onNode(SemanticsMatcher.keyIsDefined(EditorEventIconTintKey),
                            useUnmergedTree = true).getUnclippedBoundsInRoot()
                        assertEquals(4f, (icon.left - control.left).value, 0.5f)
                        val submit = compose.onNodeWithTag("submit-slot").getUnclippedBoundsInRoot()
                        assertEquals(8f, (submit.left - control.right).value, 0.5f)
                    }
                    if (sample?.action != null) {
                        val target = compose.onNode(hasClickAction()).getUnclippedBoundsInRoot()
                        assertEquals(48f, target.height.value, 0.5f)
                        assertEquals((injectedHeight.value - 48f) / 2, (target.top - control.top).value, 0.5f)
                    }
                    compose.onNodeWithTag("sample").captureRoboImage(
                        "build/outputs/roborazzi/editor_event_${index}_${isDark}_${injectedHeight.value}.png",
                    )
                }
            }
        }
    }
}
