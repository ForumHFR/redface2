package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostEditorViewModelTest {

    private val previewParser = FakePreviewParser()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state mirrors the request and starts with an empty draft`() = runTest {
        val viewModel = newViewModel()
        viewModel.state.test {
            val initial = awaitItem()
            assertEquals(PostEditorMode.Reply, initial.mode)
            assertEquals(23, initial.cat)
            assertEquals(35395, initial.topicId)
            assertEquals("", initial.draft.text)
            assertFalse(initial.isPreviewVisible)
            assertEquals(PostContent(blocks = emptyList()), initial.preview)
            // Initial state: the user has not typed anything yet, so we stay Idle rather
            // than flagging an empty draft. The transition to EmptyDraft happens only
            // after a real ContentChanged intent leaves the draft blank.
            assertEquals(BbcodeValidation.Idle, initial.validation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `content changes update the draft and clear the empty-validation hint`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(
            PostEditorIntent.ContentChanged(
                TextFieldValue(text = "hello", selection = TextRange(5)),
            ),
        )
        viewModel.state.test {
            val state = awaitItem()
            assertEquals("hello", state.draft.text)
            assertEquals(BbcodeValidation.Idle, state.validation)
            assertTrue(state.isSubmitEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toolbar action wraps the current selection`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(
            PostEditorIntent.ContentChanged(
                TextFieldValue(text = "hello", selection = TextRange(0, 5)),
            ),
        )
        viewModel.submit(PostEditorIntent.ToolbarActionClicked(BbcodeAction.Bold))
        viewModel.state.test {
            val state = awaitItem()
            assertEquals("[b]hello[/b]", state.draft.text)
            // Selection should still wrap the inserted content.
            assertEquals(3, state.draft.selection.start)
            assertEquals(8, state.draft.selection.end)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling preview parses the current draft and shows it`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(
            PostEditorIntent.ContentChanged(
                TextFieldValue(text = "[b]hi[/b]", selection = TextRange(9)),
            ),
        )
        viewModel.submit(PostEditorIntent.TogglePreview)
        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.isPreviewVisible)
            // FakePreviewParser returns a deterministic content keyed on the input text.
            assertEquals(previewParser.contentFor("[b]hi[/b]"), state.preview)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `subsequent content edits re-parse the preview while it stays visible`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(PostEditorIntent.TogglePreview)
        viewModel.submit(
            PostEditorIntent.ContentChanged(
                TextFieldValue(text = "first", selection = TextRange(5)),
            ),
        )
        viewModel.submit(
            PostEditorIntent.ContentChanged(
                TextFieldValue(text = "second", selection = TextRange(6)),
            ),
        )
        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.isPreviewVisible)
            assertEquals(previewParser.contentFor("second"), state.preview)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `preview hidden mode does not re-parse on every keystroke`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(
            PostEditorIntent.ContentChanged(
                TextFieldValue(text = "hello", selection = TextRange(5)),
            ),
        )
        // Preview is hidden — parser must not be hit.
        assertEquals(0, previewParser.callCount)
    }

    private fun newViewModel(): PostEditorViewModel = PostEditorViewModel(
        request = PostEditorRequest(
            mode = PostEditorMode.Reply,
            cat = 23,
            topicId = 35395,
            numreponse = null,
        ),
        previewParser = previewParser,
    )

    private class FakePreviewParser : BbcodePreviewParser {
        var callCount: Int = 0
            private set

        override fun parsePreview(bbcode: String): PostContent {
            callCount += 1
            return contentFor(bbcode)
        }

        /** Deterministic content keyed on the input for assertion convenience. */
        fun contentFor(bbcode: String): PostContent = PostContent(
            blocks = listOf(PostBlock.Paragraph(listOf(PostInline.Text(bbcode)))),
        )
    }
}
