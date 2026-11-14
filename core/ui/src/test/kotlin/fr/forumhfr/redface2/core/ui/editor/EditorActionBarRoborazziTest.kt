package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.EditorShortcutSurface
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.testing.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Isolated captures. Both scales use preset L. Native IME/TalkBack remain device acceptance checks. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h480dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorActionBarRoborazziTest {
    @get:Rule val compose = createComposeRule()
    private var host by mutableStateOf(EditorActionBarFixture())

    @Test
    fun `ED20 to ED95 capture distinct bar states with level B axes`() {
        compose.setContent { key(host) { host.Content(showBody = false) } }
        listOf(false, true).forEach { dark ->
            listOf(1f, 1.3f).filter { !dark || it == 1f }.forEach { scale ->
                listOf(360.dp to false, 360.dp to true, 760.dp to true).forEach { (width, compact) ->
                    recordSamples(dark, scale, width, compact)
                }
            }
        }
    }

    private fun recordSamples(dark: Boolean, scale: Float, width: Dp, compact: Boolean) {
        val samples = if (compact) 0..19 else 0..18
        samples.filter { sample ->
            // The narrow compact viewport hides these changes; the wide row is font-scale invariant.
            !(compact && width == 360.dp && sample in setOf(1, 5)) &&
                !(!compact && scale == 1.3f && (sample in 0..7 || sample in 17..18))
        }.forEach { sample ->
            compose.runOnIdle {
                // Reset remembered tool scroll, focus and event-announcement state for every capture.
                host = EditorActionBarFixture()
                host.dark.value = dark; host.scale.value = scale; host.width.value = width
                host.compact.value = compact; host.header.value = true
                host.firstStatus.value = EditorAppBarStatus(R.drawable.ic_editor_quote, "12 citations", {}, 12)
                host.secondStatus.value = EditorAppBarStatus(R.drawable.ic_ms_restore_page, "Brouillon retrouvé", {})
                showSample(sample)
            }
            val target = compose.onNodeWithTag("bar")
            assertEquals(if (compact) 48f else 56f, target.getUnclippedBoundsInRoot().height.value, 0.5f)
            if (sample == 19) assertCompactSweepStaysInsideDisc()
            target.captureRoboImage("src/test/screenshots/editor_action_bar_" +
                "${sample}_${dark}_${scale}_${width.value}_$compact.png")
        }
    }

    private fun assertCompactSweepStaysInsideDisc() {
        val target = compose.onNodeWithTag("editor-bar-submit")
        val bounds = target.getUnclippedBoundsInRoot()
        assertEquals(48f, bounds.width.value, 0.5f)
        assertEquals(48f, bounds.height.value, 0.5f)
        val image = target.captureToImage().asAndroidBitmap()
        val pixelsPerDp = image.width / 48f
        val barColor = image.getPixel(0, 0)
        // Exclude one pixel of antialiasing at the visible 40 dp circle's edge.
        val radius = 20f * pixelsPerDp + 1f
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val dx = x + 0.5f - image.width / 2f
            val dy = y + 0.5f - image.height / 2f
            if (dx * dx + dy * dy > radius * radius) {
                assertEquals("Countdown must leave the bar visible outside the 40 dp disc at ($x, $y)",
                    barColor, image.getPixel(x, y))
            }
        }
        // Both samples are inside the disc and outside the icon: an absent or full scrim must fail too.
        assertNotEquals("The right half must be swept while the left half stays unswept",
            image.getPixel((10f * pixelsPerDp).toInt(), image.height / 2),
            image.getPixel((38f * pixelsPerDp).toInt(), image.height / 2))
    }

    private fun showSample(sample: Int) {
        val arm = when (sample) {
            6, 16 -> host.frozenArm
            19 -> host.frozenHalfArm
            else -> null
        }
        host.bar.value = EditorBarState(sampleMode(sample), arm)
        host.enabled.value = sample != 17 && sample != 18
        host.imageUrl.value = sample != 5
        host.shortcut.value = if (sample == 1) {
            EditorBarShortcut(EditorShortcut.BOLD, EditorShortcutSurface.POST)
        } else null
        host.loading.value = sample == 7
        host.sending.value = sample == 8
        host.uploading.value = sample in 9..11
        host.progress.value = if (sample in 10..11) UploadProgress(1, 3) else null
        host.event.value = sampleEvent(sample)
    }

    private fun sampleMode(sample: Int): EditorBarMode = when (sample) {
        2, 6 -> EditorBarMode.Format
        3, 18 -> EditorBarMode.Color
        4, 5 -> EditorBarMode.Image
        else -> EditorBarMode.Normal
    }

    private fun sampleEvent(sample: Int): EditorEvent? = when (sample) {
        8 -> controlEvent(EditorEventContent.InProgress(EditorText(R.string.editor_event_sending)))
        9, 10 -> controlEvent(EditorEventContent.InProgress(uploadText(2), host.progress.value))
        11 -> controlEvent(EditorEventContent.Problem(EditorErrors.network))
        12, 16 -> host.finished()
        13 -> controlEvent(EditorEventContent.Information(EditorText(R.string.editor_event_already_quoted)))
        14 -> controlEvent(EditorEventContent.Problem(EditorErrors.missingTitle))
        15 -> controlEvent(EditorEventContent.Problem(EditorErrors.noImageReceived))
        else -> null
    }
}
