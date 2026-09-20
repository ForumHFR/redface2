package fr.forumhfr.redface2.core.ui.editor

import android.widget.Magnifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** #447 — touch word and paragraph selection in the state-based BBCode field. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h780dp-xxhdpi",
    shadows = [NoopBbcodeTextFieldShadowMagnifier::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldSelectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var density: Density
    private lateinit var textLayout: TextLayoutResult

    @Test
    fun doubleTapSelectsOrdinaryWord() {
        setFieldContent(ORDINARY_TEXT)

        doubleTap(offset = 68)

        assertSelection(TextRange(67, 71))
    }

    @Test
    fun doubleTapSelectsHyphenSeparatedWordWithoutCrossingNewlines() {
        setFieldContent(NO_SPACE_TEXT)

        doubleTap(offset = 43)

        // ICU word breaking around hyphens differs between Robolectric and devices (the device
        // proof selects `aaaa`); the invariant guarded here is that the platform smart selection
        // no longer widens a word tap into the multi-line block reported on #447.
        assertSelectionWithin(TextRange(38, 56))
    }

    @Test
    fun tripleTapStillSelectsCurrentLine() {
        setFieldContent(NO_SPACE_TEXT)
        val position = touchPosition(offset = 43)

        composeTestRule.onNode(hasSetTextAction()).performTouchInput {
            repeat(3) { index ->
                down(0, position)
                advanceEventTime(20)
                up(0)
                if (index < 2) advanceEventTime(80)
            }
        }
        composeTestRule.waitForIdle()

        assertSelection(TextRange(38, 56))
    }

    @Test
    fun doubleTapRequestsSelectionToolbar() {
        val toolbar = RecordingTextToolbar()

        setFieldContent(
            text = ORDINARY_TEXT,
            textToolbar = toolbar,
            textContextMenuProvider = ForwardingTextContextMenuProvider(toolbar),
        )
        doubleTap(offset = 68)

        assertTrue("a touch selection must request the standard toolbar", toolbar.showMenuCalls > 0)
    }

    private fun setFieldContent(
        text: String,
        textToolbar: TextToolbar? = null,
        textContextMenuProvider: TextContextMenuProvider? = null,
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val resolvedTextToolbar = textToolbar ?: LocalTextToolbar.current
                val resolvedTextContextMenuProvider =
                    textContextMenuProvider ?: LocalTextContextMenuToolbarProvider.current
                CompositionLocalProvider(
                    LocalTextToolbar provides resolvedTextToolbar,
                    LocalTextContextMenuToolbarProvider provides resolvedTextContextMenuProvider,
                ) {
                    FieldContent(text)
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Suppress("ComposableNaming") // Test fixture, not a reusable UI component.
    @androidx.compose.runtime.Composable
    private fun FieldContent(text: String) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            density = LocalDensity.current
            val value = remember { mutableStateOf(TextFieldValue(text, TextRange.Zero)) }
            val scrollState = rememberScrollState()
            Box(Modifier.size(320.dp, 240.dp)) {
                BbcodeTextField(
                    value = value.value,
                    onValueChange = { value.value = it },
                    label = "Message",
                    modifier = Modifier.fillMaxSize(),
                    scrollState = scrollState,
                    onTextLayout = { getResult ->
                        getResult()?.let { textLayout = it }
                    },
                )
            }
        }
    }

    private fun doubleTap(offset: Int) {
        composeTestRule.onNode(hasSetTextAction()).performTouchInput {
            doubleClick(position = touchPosition(offset))
        }
        composeTestRule.waitForIdle()
    }

    private fun touchPosition(offset: Int): Offset {
        val characterCenter = textLayout.getBoundingBox(offset).center
        // OutlinedTextFieldDefaults.decorator uses 16 dp content padding on both axes. The
        // editable semantics node starts below BbcodeTextField's separate floating-label headroom.
        val contentPadding = with(density) { 16.dp.toPx() }
        return characterCenter + Offset(contentPadding, contentPadding)
    }

    private fun assertSelection(expected: TextRange) {
        assertEquals(expected, currentSelection())
    }

    private fun assertSelectionWithin(bounds: TextRange) {
        val selection = currentSelection()
        assertTrue("selection $selection escapes $bounds", !selection.collapsed)
        assertTrue("selection $selection escapes $bounds", selection.min >= bounds.min)
        assertTrue("selection $selection escapes $bounds", selection.max <= bounds.max)
    }

    private fun currentSelection(): TextRange {
        val config = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode().config
        return config[SemanticsProperties.TextSelectionRange]
    }

    private companion object {
        val ORDINARY_TEXT = List(6) { "le chat dort sur le tapis rouge" }.joinToString("\n")
        val NO_SPACE_TEXT = (1..40).joinToString("\n") { line ->
            "L${line.toString().padStart(2, '0')}-aaaa-bbbb-cccc"
        }
    }
}

/** Bridges Foundation's 1.11.2 context-menu provider to the requested legacy toolbar fake. */
private class ForwardingTextContextMenuProvider(
    private val toolbar: TextToolbar,
) : TextContextMenuProvider {

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        toolbar.showMenu(Rect.Zero, null, null, null, null)
        awaitCancellation()
    }
}

private class RecordingTextToolbar : TextToolbar {
    var showMenuCalls: Int = 0
        private set

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        showMenuCalls++
        status = TextToolbarStatus.Shown
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
    }
}

/** Robolectric's [Magnifier] has no Surface; the platform magnifier is proven on device. */
@Implements(Magnifier::class)
class NoopBbcodeTextFieldShadowMagnifier {

    @Implementation
    @Suppress("UnusedParameter") // Robolectric matches the platform signature.
    fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit

    @Implementation
    @Suppress("UnusedParameter") // Robolectric matches the platform signature.
    fun show(
        sourceCenterX: Float,
        sourceCenterY: Float,
        magnifierCenterX: Float,
        magnifierCenterY: Float,
    ) = Unit

    @Implementation
    fun update() = Unit

    @Implementation
    fun dismiss() = Unit
}
