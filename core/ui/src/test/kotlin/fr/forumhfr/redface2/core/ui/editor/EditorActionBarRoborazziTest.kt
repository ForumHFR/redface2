package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.testing.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Record-only. Both scales use preset L. Native IME/TalkBack remain device acceptance checks. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h480dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorActionBarRoborazziTest {
    @get:Rule val compose = createComposeRule()
    private val host = EditorActionBarFixture()

    @Test
    fun `ED20 to ED95 record all bar states in both themes scales and width classes`() {
        compose.setContent { host.Content(showBody = false) }
        listOf(false, true).forEach { dark ->
            listOf(1f, 1.3f).forEach { scale ->
                listOf(360.dp to false, 360.dp to true, 760.dp to true).forEach { (width, compact) ->
                    recordSamples(dark, scale, width, compact)
                }
            }
        }
    }

    private fun recordSamples(dark: Boolean, scale: Float, width: Dp, compact: Boolean) {
        compose.runOnIdle {
            host.dark.value = dark; host.scale.value = scale; host.width.value = width
            host.compact.value = compact; host.header.value = true
            host.firstStatus.value = EditorAppBarStatus(R.drawable.ic_editor_quote, "12 citations", {}, 12)
            host.secondStatus.value = EditorAppBarStatus(R.drawable.ic_editor_restore, "Brouillon retrouvé", {})
        }
        (0..17).forEach { sample ->
            compose.runOnIdle { showSample(sample) }
            val target = compose.onNodeWithTag("bar")
            assertEquals(if (compact) 48f else 56f, target.getUnclippedBoundsInRoot().height.value, 0.5f)
            target.captureRoboImage("src/test/screenshots/editor_action_bar_" +
                "${sample}_${dark}_${scale}_${width.value}_$compact.png")
        }
    }

    private fun showSample(sample: Int) {
        host.bar.value = EditorBarState(sampleMode(sample), if (sample == 6 || sample == 16) host.frozenArm else null)
        host.enabled.value = sample != 17
        host.imageUrl.value = sample != 5
        host.shortcut.value = if (sample == 1) EditorBarShortcut(R.drawable.ic_editor_bold, "Gras", {}) else null
        host.loading.value = sample == 7
        host.sending.value = sample == 8
        host.uploading.value = sample in 9..11
        host.progress.value = if (sample in 10..11) UploadProgress(1, 3) else null
        host.event.value = sampleEvent(sample)
    }

    private fun sampleMode(sample: Int): EditorBarMode = when (sample) {
        2, 6 -> EditorBarMode.Format
        3 -> EditorBarMode.Color
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
