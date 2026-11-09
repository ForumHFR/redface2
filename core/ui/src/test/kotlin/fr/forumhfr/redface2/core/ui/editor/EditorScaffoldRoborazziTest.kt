package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.testing.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Isolated capture matrix in preset L, plus the J landscape reference at fontScale 1 × M in both themes.
 * Insets are synthetic, including the documented insufficient low window.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScaffoldRoborazziTest {
    @get:Rule val compose = createComposeRule()
    private var host by mutableStateOf(EditorScaffoldFixture())
    private data class Profile(val name: String, val width: Int, val height: Int, val ime: Int,
        val nav: Int = 30, val right: Int = 0, val fieldHeight: Float)
    private val profiles = listOf(
        Profile("portrait", 360, 740, 386, fieldHeight = 214f),
        Profile("landscape-s10e", 760, 360, 198, nav = 0, right = 24, fieldHeight = 90f),
        Profile("low-window", 360, 470, 357, fieldHeight = 41f),
    )
    private val matrix = listOf(false, true).flatMap { dark ->
        listOf(1f, 1.3f).flatMap { scale -> profiles.map { Triple(dark, scale, it) } }
    }

    @Test
    fun `final B5 assembly matrix in both themes at 1 and 1_3 times L`() {
        compose.setContent { key(host) { host.Content() } }
        matrix.forEach { (dark, scale, profile) ->
            (0..17).forEach { sample -> capture(dark, scale, profile, sample) }
        }
    }

    @Test
    fun `first sample of every profile theme and scale matches the same references in reverse order`() {
        compose.setContent { key(host) { host.Content() } }
        // Reuse sample 0 filenames: verification must expose any dependence on the preceding case.
        matrix.asReversed().forEach { (dark, scale, profile) -> capture(dark, scale, profile, sample = 0) }
    }

    @Test
    fun `J landscape S10e at fontScale 1 times M shows three complete lines in both themes`() {
        compose.setContent { key(host) { host.Content() } }
        val profile = profiles.single { it.name == "landscape-s10e" }
        listOf(false, true).forEach { dark ->
            capture(dark, 1f, profile, sample = 0, fontPreset = FontScalePreference.M)
            assertThreeCompleteLines()
        }
    }

    private fun assertThreeCompleteLines() {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        val layout = layouts.single()
        val viewportTop = host.scroll.value
        val viewportBottom = viewportTop + host.scroll.viewportSize
        val completeLines = (0 until layout.lineCount).count { line ->
            layout.getLineTop(line) >= viewportTop && layout.getLineBottom(line) <= viewportBottom
        }
        assertTrue("J: the real BTF2 viewport must show three complete lines at 1 × M", completeLines >= 3)
    }

    private fun capture(dark: Boolean, scale: Float, profile: Profile, sample: Int,
        fontPreset: FontScalePreference = FontScalePreference.L) {
        compose.runOnIdle {
            // key(host) retains the Android view and its insets holder. Dispatch through the mounted
            // fixture BEFORE replacing it, so the fresh field's first measure sees this profile's insets.
            // Pixel density and the Android window stay fixed by @Config; only local bounds/scale vary.
            host.insets(right = profile.right, nav = profile.nav, ime = profile.ime)
            // Configure all composition inputs in this same UI turn, before the new host can be measured.
            host = EditorScaffoldFixture()
            host.metrics.value = EditorWindowMetrics(profile.height.dp, 24.dp, profile.nav.dp)
            host.width.value = profile.width.dp; host.dark.value = dark; host.scale.value = scale
            host.fontPreset.value = fontPreset
            host.body.edit { selection = TextRange.Zero }
            host.chrome.value = sampleChrome(sample)
            host.panel.value = sample == 16
            host.detail.value = if (sample == 15) host.chrome.value.event else null
        }
        compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("editor-scaffold-field")),
            useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(TextRange.Zero, host.body.selection)
            // No edit occurred: even a viewport shorter than the first line must retain its initial scroll.
            assertEquals(0, host.scroll.value)
            assertEquals(0, host.inputSessions)
            assertNull(host.inputConnection)
        }
        // Inspect the mounted body even when a panel clears its merged semantics.
        val field = compose.onNodeWithTag("editor-scaffold-field", useUnmergedTree = true)
            .assertExists().getUnclippedBoundsInRoot()
        assertEquals(profile.fieldHeight, field.height.value, 0.5f)
        val presetSuffix = if (fontPreset == FontScalePreference.L) "" else "_${fontPreset.name.lowercase()}"
        compose.onNodeWithTag("scaffold-bench").captureRoboImage(
            "src/test/screenshots/editor_scaffold_${profile.name}_${dark}_${scale}_$sample$presetSuffix.png")
    }

    private fun sampleChrome(sample: Int): EditorScaffoldChrome {
        val mode = when (sample) {
            2, 5 -> EditorBarMode.Format
            3 -> EditorBarMode.Color
            4 -> EditorBarMode.Image
            else -> EditorBarMode.Normal
        }
        return EditorScaffoldChrome(
            bar = EditorBarState(mode, if (sample == 5) host.frozenArm else null),
            firstStatus = host.statuses[0].takeUnless { sample == 0 },
            secondStatus = host.statuses[1].takeUnless { sample == 0 },
            shortcut = if (sample == 1) EditorBarShortcut(R.drawable.ic_editor_bold, "Gras", {}) else null,
            loadingForm = sample == 6, uploading = sample in 8..10,
            uploadProgress = if (sample in 9..10) UploadProgress(1, 3) else null,
            bodyReadOnly = sample == 7,
            controlsEnabled = sample != 7,
            event = sampleEvent(sample),
        )
    }

    private fun sampleEvent(sample: Int): EditorEvent? = when (sample) {
        7 -> controlEvent(EditorEventContent.InProgress(EditorText(R.string.editor_event_sending)))
        8, 9 -> controlEvent(EditorEventContent.InProgress(uploadText(1)))
        10, 15 -> controlEvent(EditorEventContent.Problem(EditorErrors.network))
        11 -> controlEvent(finishedUpload(1))
        12 -> controlEvent(finishedUpload(10))
        13 -> controlEvent(EditorEventContent.Information(EditorText(R.string.editor_event_already_quoted)))
        14 -> controlEvent(EditorEventContent.Problem(EditorErrors.missingTitle))
        17 -> controlEvent(EditorEventContent.Problem(EditorErrors.noImageReceived))
        else -> null
    }
}
