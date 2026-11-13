package fr.forumhfr.redface2.core.ui.sheet

import android.view.View
import android.view.WindowInsetsAnimation
import android.view.animation.LinearInterpolator
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorDraftOfferFixture
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerSheet
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import fr.forumhfr.redface2.core.ui.editor.revealEditorControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Tagbug DEV #2805926 — a sheet opened over the keyboard must not follow the keyboard's retraction
 * frame by frame. The platform phases are dispatched as API 30+ does: prepare, the END-state insets,
 * start, then one progress per frame, then end.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettledSheetWindowInsetsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a keyboard that stays up keeps padding the sheet`() {
        val probe = mountProbe()
        compose.runOnIdle { ImeDriver(probe.view).settle(KEYBOARD_PX) }
        compose.runOnIdle {
            assertEquals(KEYBOARD_PX, probe.live)
            assertEquals(KEYBOARD_PX, probe.settled)
        }
    }

    @Test fun `a retracting keyboard is released at once while its live inset still animates`() {
        val probe = mountProbe()
        val ime = ImeDriver(probe.view)
        compose.runOnIdle { ime.settle(KEYBOARD_PX) }
        compose.runOnIdle { ime.start(from = KEYBOARD_PX, to = 0) }
        compose.runOnIdle {
            assertEquals("The live inset has not moved yet", KEYBOARD_PX, probe.live)
            assertEquals("The sheet already has its final inset", NAVIGATION_PX, probe.settled)
        }
        compose.runOnIdle { ime.progress(KEYBOARD_PX / 2) }
        compose.runOnIdle {
            assertEquals(KEYBOARD_PX / 2, probe.live)
            assertEquals(NAVIGATION_PX, probe.settled)
        }
        compose.runOnIdle { ime.end() }
        compose.runOnIdle {
            assertEquals(0, probe.live)
            assertEquals(NAVIGATION_PX, probe.settled)
        }
    }

    @Test fun `a requested keyboard is anticipated at once`() {
        val probe = mountProbe()
        val ime = ImeDriver(probe.view)
        compose.runOnIdle { ime.settle(0) }
        compose.runOnIdle { ime.start(from = 0, to = KEYBOARD_PX) }
        compose.runOnIdle {
            assertEquals(0, probe.live)
            assertEquals(KEYBOARD_PX, probe.settled)
        }
    }

    @Test fun `the draft offer sheet takes its final height when the retraction starts`() {
        val fixture = EditorDraftOfferFixture()
        compose.setContent { fixture.Content() }
        compose.onNode(hasContentDescription("Offre de brouillon") and
            hasAnyAncestor(hasTestTag("editor-scaffold-app-bar"))).revealEditorControl().performClick()
        assertSheetIgnoresRetractionFrames("editor-draft-offer-sheet")
    }

    @Test fun `the smiley sheet takes its final height when the retraction starts`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                SmileyPickerSheet(SmileyPickerState.Open(), {}, {}, {})
            }
        }
        assertSheetIgnoresRetractionFrames("smiley_picker_sheet_layout")
    }

    private fun assertSheetIgnoresRetractionFrames(tag: String) {
        compose.onNode(isDialog()).assertExists()
        // A ModalBottomSheet owns a separate window: only its own decor view reaches its insets.
        val ime = compose.runOnIdle { ImeDriver(ShadowDialog.getLatestDialog().window!!.decorView) }
        compose.runOnIdle { ime.settle(KEYBOARD_PX) }
        val overKeyboard = sheetHeight(tag)
        compose.runOnIdle { ime.start(from = KEYBOARD_PX, to = 0) }
        val settled = sheetHeight(tag)
        assertTrue("The sheet must release the keyboard's room at once", settled < overKeyboard)
        for (frame in listOf(KEYBOARD_PX * 3 / 4, KEYBOARD_PX / 2, KEYBOARD_PX / 4)) {
            compose.runOnIdle { ime.progress(frame) }
            assertEquals("Frame at $frame px must not resize the sheet", settled, sheetHeight(tag))
        }
        compose.runOnIdle { ime.end() }
        assertEquals(settled, sheetHeight(tag))
    }

    private fun sheetHeight(tag: String): Int = compose.onNodeWithTag(tag).fetchSemanticsNode().size.height

    private class Probe {
        lateinit var view: View
        var live = -1
        var settled = -1
    }

    private fun mountProbe(): Probe {
        val probe = Probe()
        compose.setContent {
            val density = LocalDensity.current
            probe.view = LocalView.current
            // Plain fields: both reads subscribe this composition to the inset states.
            probe.live = WindowInsets.ime.getBottom(density)
            probe.settled = settledSheetWindowInsets().getBottom(density)
        }
        return probe
    }

    /** One keyboard animation on [view]'s window, phase by phase. */
    private class ImeDriver(private val view: View) {
        private val animation =
            WindowInsetsAnimation(android.view.WindowInsets.Type.ime(), LinearInterpolator(), DURATION_MS)

        fun settle(imePx: Int) {
            ViewCompat.dispatchApplyWindowInsets(view, insets(imePx))
        }

        fun start(from: Int, to: Int) {
            view.dispatchWindowInsetsAnimationPrepare(animation)
            ViewCompat.dispatchApplyWindowInsets(view, insets(to))
            val lower = android.graphics.Insets.of(0, 0, 0, minOf(from, to))
            val upper = android.graphics.Insets.of(0, 0, 0, maxOf(from, to))
            view.dispatchWindowInsetsAnimationStart(animation, WindowInsetsAnimation.Bounds(lower, upper))
        }

        fun progress(imePx: Int) {
            view.dispatchWindowInsetsAnimationProgress(insets(imePx).toWindowInsets()!!, listOf(animation))
        }

        fun end() = view.dispatchWindowInsetsAnimationEnd(animation)

        private fun insets(imePx: Int): WindowInsetsCompat = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAVIGATION_PX))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
            .setVisible(WindowInsetsCompat.Type.ime(), imePx > 0)
            .build()
    }

    private companion object {
        const val KEYBOARD_PX = 750
        const val NAVIGATION_PX = 72
        const val DURATION_MS = 200L
    }
}
