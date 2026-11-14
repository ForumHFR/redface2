package fr.forumhfr.redface2.core.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorRecipientFieldTest {
    @get:Rule val compose = createComposeRule()
    private val recipients = mutableStateOf(EditorRecipientState())
    private val subject = mutableStateOf("")
    private val enabled = mutableStateOf(true)
    private var accept = true
    private var subjectChanges = 0
    private val recipientInput = EditorRecipientInput({ recipients.value }, { if (accept) recipients.value = it })
    private val subjectInput = EditorSubjectInput(70, { subject.value }, {
        subjectChanges += 1
        if (accept) subject.value = it
    })
    private var connection: InputConnection? = null
    private lateinit var info: EditorInfo
    private val interceptor = PlatformTextInputInterceptor { request, next ->
        info = EditorInfo()
        val opened = request.createInputConnection(info)
        connection = opened
        try { next.startInputMethod(request) } finally {
            connection = null
            opened.closeConnection()
        }
    }

    @Test fun `IME commitText validates only commas even in successive transactions before recomposition`() {
        show()
        field().requestFocus()
        compose.runOnIdle {
            assertEquals(EditorInfo.IME_ACTION_NEXT, info.imeOptions and EditorInfo.IME_MASK_ACTION)
            assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
            val capitalization = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            assertEquals(0, info.inputType and capitalization)
            checkNotNull(connection).commitText("Bob with spaces", 1)
            assertTrue(recipients.value.chips.isEmpty())
            assertEquals("Bob with spaces", recipients.value.buffer)
            checkNotNull(connection).commitText(",", 1)
            checkNotNull(connection).commitText("Eve,", 1)
            assertEquals(EditorRecipientState(listOf("Bob with spaces", "Eve")), recipients.value)
        }
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun `real clipboard trims chip edges merges exact duplicates and preserves inner spaces and tail`() {
        show()
        paste(" Alice , Bob  with spaces,Alice, unfinished ")
        compose.runOnIdle {
            assertEquals(listOf("Alice", "Bob  with spaces"), recipients.value.chips)
            assertEquals(" unfinished ", recipients.value.buffer)
            assertEquals("Alice,Bob  with spaces", recipients.value.csv)
            assertEquals(recipients.value, EditorRecipientState.decode(recipients.value.encode()))
        }
        field().performTextInput(",")
        compose.runOnIdle {
            assertEquals("unfinished", recipients.value.chips.last())
            assertEquals("", recipients.value.buffer)
        }
    }

    @Test fun `clipboard with a final comma commits every nonblank segment without empty chips`() {
        show()
        paste(", ,Alice,, Bob,")
        compose.runOnIdle {
            assertEquals(EditorRecipientState(listOf("Alice", "Bob")), recipients.value)
            assertEquals("Alice,Bob", recipients.value.csv)
        }
    }

    @Test fun `IME composition is replaced by committed text with a comma and preserves the remaining caret`() {
        show()
        field().requestFocus()
        compose.runOnIdle {
            val ime = checkNotNull(connection)
            ime.setComposingText(" Bob  with spaces ", 1)
            assertEquals(EditorRecipientState(buffer = " Bob  with spaces "), recipients.value)
            // commitText replaces the whole composing region, including the final comma.
            ime.commitText(" Bob  with spaces ,", 1)
            assertEquals(EditorRecipientState(listOf("Bob  with spaces")), recipients.value)
            ime.setComposingText("Eve", 1)
            ime.commitText("Eve,tail", 1)
            assertEquals(EditorRecipientState(listOf("Bob  with spaces", "Eve"), "tail"), recipients.value)
            assertEquals("tail", recipientInput.buffer.text.toString())
            assertEquals(TextRange(4), recipientInput.buffer.selection)
        }
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("tail")))
    }

    @Test fun `IME comma inside a composing region or after finishing it commits the chip exactly once`() {
        show()
        field().requestFocus()
        compose.runOnIdle {
            val ime = checkNotNull(connection)
            // Some keyboards keep punctuation inside the composing region.
            ime.setComposingText("Bob,", 1)
            assertEquals(EditorRecipientState(listOf("Bob")), recipients.value)
            assertEquals("", recipientInput.buffer.text.toString())
            ime.finishComposingText()
            assertEquals(EditorRecipientState(listOf("Bob")), recipients.value)
            // Others finish the word first, then commit the comma on its own.
            ime.setComposingText("Eve", 1)
            assertEquals(EditorRecipientState(listOf("Bob"), "Eve"), recipients.value)
            ime.finishComposingText()
            assertEquals(EditorRecipientState(listOf("Bob"), "Eve"), recipients.value)
            ime.commitText(",", 1)
            assertEquals(EditorRecipientState(listOf("Bob", "Eve")), recipients.value)
            assertEquals("", recipientInput.buffer.text.toString())
            assertEquals(TextRange.Zero, recipientInput.buffer.selection)
        }
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun `duplicate already committed in an earlier transaction is merged without changing case`() {
        show()
        field().performTextInput("Alice,")
        field().performTextInput(" Alice ,alice,")
        compose.runOnIdle {
            assertEquals(EditorRecipientState(listOf("Alice", "alice")), recipients.value)
        }
    }

    @Test fun `empty recipient list adds no space above the editable field`() {
        show()
        val top = compose.onNodeWithTag("headers").fetchSemanticsNode().positionInRoot.y
        assertEquals(top, field().fetchSemanticsNode().positionInRoot.y)
        field().performTextInput("Alice,")
        assertTrue(field().fetchSemanticsNode().positionInRoot.y > top)
        compose.onNodeWithContentDescription("Retirer Alice")
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        assertEquals(top, field().fetchSemanticsNode().positionInRoot.y)
    }

    @Test fun `accepted addition and removal announce in French on the same live node`() {
        show()
        field().performTextInput("Alice,")
        val added = compose.onNodeWithContentDescription("Destinataire ajouté : Alice.", useUnmergedTree = true)
        added.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        val liveNode = added.fetchSemanticsNode().id
        // Duplicate validation consumes the buffer without emitting another addition.
        val change = recipientInput.change
        field().performTextInput(" Alice ,")
        compose.runOnIdle { assertEquals(change, recipientInput.change) }
        compose.onNodeWithContentDescription("Retirer Alice")
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        val removed = compose.onNodeWithContentDescription("Destinataire retiré : Alice.", useUnmergedTree = true)
        removed.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertEquals(liveNode, removed.fetchSemanticsNode().id)
        paste("Alice,Bob,")
        compose.onNodeWithContentDescription("Destinataires ajoutés : Alice, Bob.", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test fun `next focuses subject without validating and empty backspace preserves chips`() {
        recipients.value = EditorRecipientState(listOf("Alice"))
        show()
        field().requestFocus()
        field().performKeyInput { pressKey(Key.Backspace) }
        compose.runOnIdle { checkNotNull(connection).deleteSurroundingText(1, 0) }
        compose.runOnIdle { assertEquals(listOf("Alice"), recipients.value.chips) }
        field().performTextInput("half a pseudo")
        field().performImeAction()
        compose.onNodeWithTag("subject").assertIsFocused()
        compose.runOnIdle {
            assertEquals(EditorRecipientState(listOf("Alice"), "half a pseudo"), recipients.value)
        }
    }

    @Test fun `comma inserted at caret commits prefix while preserving the tail selection`() {
        show()
        field().performTextInput("AliceBob")
        field().performTextInputSelection(TextRange(5))
        field().performTextInput(",")
        compose.runOnIdle {
            assertEquals(EditorRecipientState(listOf("Alice"), "Bob"), recipients.value)
            assertEquals(TextRange.Zero, recipientInput.buffer.selection)
        }
    }

    @Test fun `chip exposes a labelled removal action and disabled chips cannot remove`() {
        recipients.value = EditorRecipientState(listOf("Alice", "Bob"), "tail")
        show()
        val chip = compose.onNodeWithContentDescription("Retirer Alice")
        chip.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals("Retirer Alice", chip.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.runOnIdle { accept = false }
        chip.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.runOnIdle {
            assertEquals(EditorRecipientState(listOf("Alice", "Bob"), "tail"), recipients.value)
            assertEquals(null, recipientInput.change)
            accept = true
        }
        chip.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.runOnIdle { assertEquals(EditorRecipientState(listOf("Bob"), "tail"), recipients.value) }
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithContentDescription("Retirer Bob").assertIsNotEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { assertFalse(it()) }
        compose.runOnIdle { assertEquals(listOf("Bob"), recipients.value.chips) }
    }

    @Test fun `subject caps typing and real paste at 70 and only truncation is a live region`() {
        show()
        val field = compose.onNodeWithTag("subject")
        field.performTextInput("é".repeat(70))
        val counter = compose.onNodeWithText("70 / 70 caractères", useUnmergedTree = true)
        counter.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        field.performTextInput("x")
        val announcement = "Sujet tronqué à 70 caractères."
        val notice = compose.onNodeWithContentDescription(announcement, useUnmergedTree = true)
        notice
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        counter.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        field.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.MaxTextLength, 70))
        val changes = subjectChanges
        field.performTextInputSelection(TextRange(0, 70))
        compose.runOnIdle {
            assertEquals(changes, subjectChanges)
            assertTrue(subjectInput.truncated)
        }
        paste("new subject".repeat(10), "subject")
        compose.runOnIdle { assertEquals("new subject".repeat(10).take(70), subject.value) }
        notice.assertExists()
    }

    @Test fun `untruncated subject adds no space below its field and only a truncation shows the notice`() {
        show()
        val headers = compose.onNodeWithTag("headers")
        val field = compose.onNodeWithTag("subject")
        // The live node overlays the field (gate C2 r2, §2): no 1 dp strip below the subject.
        assertEquals(field.getUnclippedBoundsInRoot().bottom.value, headers.getUnclippedBoundsInRoot().bottom.value,
            0.01f)
        field.performTextInput("x".repeat(71))
        compose.runOnIdle { assertTrue(subjectInput.truncated) }
        assertTrue(headers.getUnclippedBoundsInRoot().bottom > field.getUnclippedBoundsInRoot().bottom)
        compose.onNodeWithContentDescription("Sujet tronqué à 70 caractères.", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test fun `subject selection alone never calls the owner even when edits would be refused`() {
        subject.value = "subject"
        show()
        val field = compose.onNodeWithTag("subject")
        field.requestFocus()
        for ((acceptEdits, selection) in listOf(true to TextRange(2), false to TextRange(1, 4))) {
            compose.runOnIdle { accept = acceptEdits }
            field.performTextInputSelection(selection)
            compose.runOnIdle {
                assertEquals(0, subjectChanges)
                assertEquals("subject", subject.value)
                assertEquals(selection, subjectInput.buffer.selection)
            }
        }
    }

    @Test fun `owner refusal and replacement before recomposition never diverge from the editable buffer`() {
        show()
        field().requestFocus()
        compose.runOnIdle {
            accept = false
            checkNotNull(connection).commitText("rejected,tail", 1)
            assertEquals(EditorRecipientState(), recipients.value)
            assertEquals("", recipientInput.buffer.text.toString())
            assertEquals(null, recipientInput.change)
            accept = true
            recipients.value = EditorRecipientState(listOf("restored"), "saved tail")
            checkNotNull(connection).commitText("stale,", 1)
            assertEquals(listOf("restored"), recipients.value.chips)
        }
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("saved tail")))
    }

    private fun field() = compose.onNodeWithTag("recipients")

    private fun paste(text: String, tag: String = "recipients") {
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", text))
        val field = compose.onNodeWithTag(tag)
        field.requestFocus()
        field.performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.waitForIdle()
    }

    private fun show() {
        compose.setContent {
            InterceptPlatformTextInput(interceptor) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    val focus = remember { FocusRequester() }
                    Column(Modifier.testTag("headers")) {
                        EditorRecipientField(recipients.value, recipientInput, { focus.requestFocus() },
                            Modifier.testTag("recipients"), enabled = enabled.value)
                        EditorSubjectField(subject.value, subjectInput,
                            Modifier.testTag("subject").focusRequester(focus), enabled = enabled.value)
                    }
                }
            }
        }
    }
}
