package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #275/#410 — layout contract of the `fillViewport` mode of [BbcodeTextField]:
 *
 * - with content SHORTER than the bounded viewport, the field still fills it (v108 dogfooding:
 *   no blank under the field, tapping anywhere in the area focuses) and the wrapping column has
 *   nothing to scroll;
 * - with content TALLER than the viewport, the field GROWS (no internal text scroll) and the
 *   wrapping column becomes the scrollable — the ancestor-scrollable path is the one that
 *   reliably re-anchors the cursor under the IME (the field's internal scroller takes part in
 *   bring-into-view but does not re-anchor on IME shrink).
 *
 * The IME interaction itself (resize re-anchoring, bring-into-view on refocus) is platform
 * behaviour that Robolectric cannot exercise — device dogfooding covers it; these tests pin the
 * structure that behaviour depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldViewportTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private companion object {
        const val OUTER_SCROLL_TAG = "outer_scroll"
    }

    @Test
    fun `short content - the field fills the viewport and nothing scrolls`() {
        setFieldContent(text = "court")

        val viewport = composeTestRule.onNodeWithTag(BBCODE_FIELD_VIEWPORT_TAG)
            .fetchSemanticsNode()
        val scrollRange = viewport.config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("nothing to scroll when the field fits", scrollRange.maxValue() == 0f)

        val field = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertTrue(
            "the field stretches to the whole bounded viewport (got ${field.size.height} " +
                "vs viewport ${viewport.size.height})",
            field.size.height >= viewport.size.height,
        )
    }

    @Test
    fun `long content - the field grows and the wrapping column scrolls`() {
        setFieldContent(text = (1..200).joinToString("\n") { "ligne $it" })

        val viewport = composeTestRule.onNodeWithTag(BBCODE_FIELD_VIEWPORT_TAG)
            .fetchSemanticsNode()
        val scrollRange = viewport.config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("the wrapping column owns the scroll", scrollRange.maxValue() > 0f)

        val field = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertTrue(
            "the field grows with its content instead of scrolling internally",
            field.size.height > viewport.size.height,
        )
    }

    @Test
    fun `moving the cursor to the end of long content scrolls the viewport to reveal it`() {
        // #447 — the field grows in external-scroll mode, so Compose never asks the wrapping
        // column to follow the caret on its own; this pins the explicit bring-into-view wiring.
        lateinit var value: MutableState<TextFieldValue>
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.size(320.dp, 400.dp)) {
                        value = remember {
                            mutableStateOf(
                                TextFieldValue(
                                    text = (1..200).joinToString("\n") { "ligne $it" },
                                    selection = TextRange.Zero,
                                ),
                            )
                        }
                        BbcodeTextField(
                            value = value.value,
                            onValueChange = { value.value = it },
                            label = "Message",
                            modifier = Modifier.fillMaxSize(),
                            fillViewport = true,
                        )
                    }
                }
            }
        }

        composeTestRule.onNode(hasSetTextAction()).requestFocus()
        composeTestRule.waitForIdle()
        assertCaretEndRevealed(BBCODE_FIELD_VIEWPORT_TAG, value)
    }

    @Test
    fun `default mode in an outer scrollable also follows the caret to the end`() {
        // #447 — same contract for the grow-with-content default inside an external
        // verticalScroll (the TopicFormScreen layout): the caret request must reach the
        // OUTER scrollable, which the field knows nothing about.
        lateinit var value: MutableState<TextFieldValue>
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.size(320.dp, 400.dp)) {
                        value = remember {
                            mutableStateOf(
                                TextFieldValue(
                                    text = (1..200).joinToString("\n") { "ligne $it" },
                                    selection = TextRange.Zero,
                                ),
                            )
                        }
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .testTag(OUTER_SCROLL_TAG),
                        ) {
                            BbcodeTextField(
                                value = value.value,
                                onValueChange = { value.value = it },
                                label = "Message",
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNode(hasSetTextAction()).requestFocus()
        composeTestRule.waitForIdle()
        assertCaretEndRevealed(OUTER_SCROLL_TAG, value)
    }

    /**
     * Moves the caret to the very end of [value] and asserts the scrollable [scrollableTag]
     * landed near its max range — the caret lives on the LAST line, so revealing it means
     * scrolling within one viewport-fraction of the bottom. A requester attached to the wrong
     * ancestor (or a rect read in the decorated box's coordinate space) under-scrolls by a
     * constant offset and fails the 95% bar; `> scrollBefore` alone would still pass.
     */
    private fun assertCaretEndRevealed(scrollableTag: String, value: MutableState<TextFieldValue>) {
        val scrollBefore = scrollValue(scrollableTag)

        composeTestRule.runOnIdle {
            value.value = value.value.copy(selection = TextRange(value.value.text.length))
        }
        composeTestRule.waitForIdle()

        val range = composeTestRule
            .onNodeWithTag(scrollableTag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        val scrollAfter = range.value()
        val maxValue = range.maxValue()
        assertTrue(
            "the scrollable followed the caret to the end " +
                "(before=$scrollBefore after=$scrollAfter max=$maxValue)",
            scrollAfter > scrollBefore && maxValue > 0f && scrollAfter >= maxValue * 0.95f,
        )
    }

    private fun scrollValue(tag: String): Float = composeTestRule
        .onNodeWithTag(tag)
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
        .value()

    @Test
    fun `pinned label stays fully visible after the viewport scrolled to an end-of-text caret (#872)`() {
        // The morning re-report of #872 : with the editor compressed (draft banner + IME) and a
        // restored draft, the open-time caret-follow scrolls the #275/#410 viewport to the LAST
        // line — the old FLOATING label (drawn inside the viewport) parked half-clipped at its
        // top edge, at fontScale 1. The pinned label lives ABOVE the scrollable, so it must
        // remain fully visible whatever the scroll position.
        lateinit var value: MutableState<TextFieldValue>
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    // 180.dp ≈ the crushed budget left to the field once the banner and the IME
                    // have eaten a short display (thibw's screenshot).
                    Box(Modifier.size(320.dp, 180.dp)) {
                        value = remember {
                            mutableStateOf(
                                TextFieldValue(
                                    text = (1..80).joinToString("\n") { "ligne $it" },
                                    selection = TextRange.Zero,
                                ),
                            )
                        }
                        BbcodeTextField(
                            value = value.value,
                            onValueChange = { value.value = it },
                            label = "Contenu BBCode",
                            modifier = Modifier.fillMaxSize(),
                            fillViewport = true,
                        )
                    }
                }
            }
        }

        composeTestRule.onNode(hasSetTextAction()).requestFocus()
        composeTestRule.waitForIdle()
        // Reproduce the trigger : caret to the end → the viewport scrolls to the bottom.
        composeTestRule.runOnIdle {
            value.value = value.value.copy(selection = TextRange(value.value.text.length))
        }
        composeTestRule.waitForIdle()
        assertTrue(
            "the viewport must actually be scrolled for the repro to be meaningful",
            scrollValue(BBCODE_FIELD_VIEWPORT_TAG) > 0f,
        )

        val label = composeTestRule.onNodeWithTag(BBCODE_FIELD_PINNED_LABEL_TAG)
            .fetchSemanticsNode()
        val viewport = composeTestRule.onNodeWithTag(BBCODE_FIELD_VIEWPORT_TAG)
            .fetchSemanticsNode()
        val labelBottom = label.positionInRoot.y + label.size.height
        assertTrue(
            "the pinned label renders entirely ABOVE the scrollable viewport " +
                "(label=${label.positionInRoot.y}..$labelBottom " +
                "viewportTop=${viewport.positionInRoot.y})",
            label.positionInRoot.y >= 0f && labelBottom <= viewport.positionInRoot.y,
        )
    }

    @Test
    fun `pinned-label mode keeps an accessible name on the field (#872, gate Sol)`() {
        setFieldContent(text = "court")

        val field = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode()
        val description = field.config[SemanticsProperties.ContentDescription]
        assertTrue(
            "the field must expose the label as its accessible name (got $description)",
            description.contains("Message"),
        )
    }

    @Test
    fun `default mode keeps the floating label (no pinned line)`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.size(320.dp, 400.dp)) {
                        BbcodeTextField(
                            value = TextFieldValue("court"),
                            onValueChange = {},
                            label = "Message",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(BBCODE_FIELD_PINNED_LABEL_TAG).assertDoesNotExist()
    }

    @Test
    fun `default mode keeps the plain bounded field`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.size(320.dp, 400.dp)) {
                        BbcodeTextField(
                            value = TextFieldValue("court"),
                            onValueChange = {},
                            label = "Message",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(BBCODE_FIELD_VIEWPORT_TAG).assertDoesNotExist()
    }

    private fun setFieldContent(text: String) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.size(320.dp, 400.dp)) {
                        BbcodeTextField(
                            value = TextFieldValue(text),
                            onValueChange = {},
                            label = "Message",
                            modifier = Modifier.fillMaxSize(),
                            fillViewport = true,
                        )
                    }
                }
            }
        }
    }
}
