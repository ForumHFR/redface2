package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.write.TopicFormSubcategoryChoice
import fr.forumhfr.redface2.core.ui.editor.EditorBackAction
import fr.forumhfr.redface2.core.ui.editor.EditorBackState
import fr.forumhfr.redface2.core.ui.editor.EditorBarGesture
import fr.forumhfr.redface2.core.ui.editor.EditorBarMode
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TopicFormChromeFixture(mode: TopicFormMode = TopicFormMode.New) {
    val session = EditorSession("opening", "alice", "topic:23:42")
    val events = TopicFormEvents().apply { open(session) }
    val memory = TopicFormChromeMemory()
    var state by mutableStateOf(TopicFormState(mode, 23, 1, 42, 1, 101,
        subject = TextFieldValue("Titre"), bodyText = "Bonjour", selectedSubcat = 1,
        subcategoryChoices = listOf(TopicFormSubcategoryChoice(1, "Divers", true)),
        hasLoadedForm = true, draftHydratedFromServer = true, subjectHydratedFromServer = true))
    val body = TextFieldState("Bonjour", TextRange(6, 1))
    val calls = mutableListOf<TopicFormIntent>()
    val adapter = TopicFormChromeAdapter({ state }, body, events, ::dispatch, {}, {}, memory)
    private fun dispatch(intent: TopicFormIntent) {
        calls += intent
        when (intent) {
            TopicFormIntent.SubmitConfirmationDismissed -> state = state.copy(submission = EditorSubmissionState.Ready)
            is TopicFormIntent.SubjectChanged -> state = state.copy(subject = intent.value)
            is TopicFormIntent.SubcatSelected -> state = state.copy(selectedSubcat = intent.id)
            else -> Unit
        }
    }
    fun error(error: EditorError) {
        val previous = state
        events.observe(previous, previous.copy(submitError = null))
        state = state.copy(submitError = error)
        events.observe(previous.copy(submitError = null), state)
    }
}

class TopicFormChromeAdapterTest {
    @Test fun `ED35 activation and POST authorization differ in creation and first post editing`() {
        TopicFormMode.entries.forEach { mode ->
            val f = TopicFormChromeFixture(mode)
            assertEquals(23, f.adapter.state.categoryId)
            assertTrue(f.adapter.host.submitState.enabled)
            assertTrue(f.adapter.state.postAllowed)
            val base = f.state
            listOf(base.copy(subject = TextFieldValue()), base.copy(selectedSubcat = null),
                base.copy(isAnonymous = true)).forEach {
                f.state = it
                assertTrue(f.adapter.state.sendActivatable)
                assertFalse(f.adapter.state.postAllowed)
            }
            listOf(base.copy(hasLoadedForm = false), base.copy(isLoadingForm = true),
                base.copy(isUploading = true), base.copy(isSubmitting = true),
                base.copy(bodyText = " "), base.copy(isInitializing = true)).forEach {
                f.state = it
                assertFalse(f.adapter.state.sendActivatable)
            }
        }
    }
    @Test fun `ED35 missing first post route disables the send button and retained send callbacks`() {
        val f = TopicFormChromeFixture(TopicFormMode.EditFirstPost)
        val ready = f.state
        val retained = f.adapter.host.submitActions
        listOf(ready.copy(cat = null), ready.copy(subcat = null), ready.copy(topicId = null),
            ready.copy(numreponse = null), ready.copy(page = null)).forEach { incomplete ->
            f.state = incomplete
            assertFalse(f.adapter.host.submitState.enabled)
            retained.onSubmit()
            assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun `ED100 no select allows creation only and never invents a subcategory`() {
        TopicFormMode.entries.forEach { mode ->
            val f = TopicFormChromeFixture(mode)
            f.state = f.state.copy(hasSubcategorySelect = false,
                selectedSubcat = if (mode == TopicFormMode.New) null else 1)
            assertNull(f.adapter.state.selectedSubcategory)
            assertEquals(mode == TopicFormMode.New, f.adapter.state.postAllowed)
            assertEquals(mode == TopicFormMode.EditFirstPost, f.adapter.state.unsupportedFirstPost)
            f.adapter.subcategory(1)
            assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun `ED101 initial blank title opens once only in creation and survives adapter recreation`() {
        TopicFormMode.entries.forEach { mode ->
            val f = TopicFormChromeFixture(mode)
            f.state = f.state.copy(subject = TextFieldValue(), isInitializing = true)
            f.adapter.openInitialHeader()
            assertFalse(f.memory.headerOpen)
            f.state = f.state.copy(isInitializing = false)
            f.adapter.openInitialHeader()
            assertEquals(mode == TopicFormMode.New, f.memory.headerOpen)
            assertEquals(TopicFormHeaderField.Title, f.memory.headerField)
            f.adapter.closeHeader()
            val recreated = TopicFormChromeAdapter({ f.state }, f.body, f.events, {}, {}, {}, f.memory)
            recreated.openInitialHeader()
            assertFalse(f.memory.headerOpen)
        }
    }
    @Test fun `ED101 a refused initial opening is retried once controls become available`() {
        val f = TopicFormChromeFixture()
        f.state = f.state.copy(subject = TextFieldValue(), isSubmitting = true)
        f.adapter.openInitialHeader()
        assertFalse(f.memory.headerOpen)
        f.state = f.state.copy(isSubmitting = false)
        f.adapter.openInitialHeader()
        assertTrue(f.memory.headerOpen)
        f.adapter.closeHeader()
        f.adapter.openInitialHeader()
        assertFalse(f.memory.headerOpen)
    }
    @Test fun `restoring a session keeps its header enabled and read only without accepting callbacks`() {
        val f = TopicFormChromeFixture()
        f.adapter.openHeader()
        f.state = f.state.copy(isInitializing = true, isRestoringSession = true)
        assertTrue(f.adapter.state.headerEnabled)
        assertTrue(f.adapter.state.headerReadOnly)
        assertFalse(f.adapter.state.subcategoryEnabled)
        f.adapter.subject(TextFieldValue("Refusé"))
        f.adapter.subcategory(1)
        f.adapter.restoreDraft()
        assertTrue(f.calls.isEmpty())
        f.state = f.state.copy(isInitializing = false, isRestoringSession = false)
        f.adapter.subject(TextFieldValue("Accepté"))
        assertEquals(listOf(TopicFormIntent.SubjectChanged(TextFieldValue("Accepté"))), f.calls)
    }
    @Test fun `ED45 CompleteHeader forwards the exact cause and acknowledges only that delivery`() {
        TopicFormMode.entries.forEach { mode ->
            val f = TopicFormChromeFixture(mode)
            listOf(EditorErrors.missingTitle to TopicFormHeaderField.Title,
                EditorErrors.missingSubcategory to TopicFormHeaderField.Subcategory).forEach { (error, field) ->
                f.error(error)
                val event = f.events.visibleEvent!!
                f.adapter.host.barActions.onEvent(event.key, EditorEventAction.Complete)
                assertTrue(f.memory.headerOpen)
                assertEquals(field, f.memory.headerField)
                assertNull(f.events.visibleEvent)
                assertNull(f.state.armToken)
                f.adapter.subject(TextFieldValue("Corrigé"))
                f.adapter.subcategory(1)
                f.adapter.closeHeader()
                var returned = false
                f.adapter.host.returnToBody { returned = true }
                assertTrue(returned)
                assertEquals(TextRange(6, 1), f.body.selection)
                assertFalse(f.adapter.host.consumeReturn()) // Header owns its synchronous focus return.
                f.adapter.host.barActions.onEvent(event.key, EditorEventAction.Complete)
                assertFalse(f.memory.headerOpen)
            }
        }
    }
    @Test fun `ED87 retained header callbacks cannot mutate a sending first post or creation`() {
        TopicFormMode.entries.forEach { mode ->
            val f = TopicFormChromeFixture(mode)
            f.adapter.openHeader()
            f.state = f.state.copy(isSubmitting = true)
            f.adapter.subject(TextFieldValue("Refusé"))
            f.adapter.subcategory(1)
            assertFalse(f.adapter.state.headerEnabled)
            assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun `ED85 displayed event skips a retained mode and does not acknowledge on Back`() {
        TopicFormMode.entries.forEach { mode ->
            val f = TopicFormChromeFixture(mode)
            f.state = f.state.copy(isPreviewVisible = true)
            f.adapter.host.gesture(EditorBarGesture.ToggleFormat)
            f.error(EditorErrors.network)
            val event = f.events.visibleEvent!!
            f.adapter.back()
            assertEquals(listOf(TopicFormIntent.TogglePreview), f.calls)
            assertEquals(EditorBarMode.Format, f.memory.host.bar.mode)
            assertEquals(event, f.events.visibleEvent)
        }
    }
    @Test fun `ED85 auto header closes before leaving and tooltip remains first`() {
        val f = TopicFormChromeFixture()
        f.state = f.state.copy(subject = TextFieldValue())
        f.adapter.openInitialHeader()
        val layers = mutableListOf<EditorBackAction>()
        f.adapter.back(EditorBackState(tooltipOpen = true), layers::add)
        assertEquals(listOf(EditorBackAction.CloseTooltip), layers)
        assertTrue(f.memory.headerOpen)
        f.adapter.back()
        assertFalse(f.memory.headerOpen)
        assertTrue(f.calls.isEmpty())
        f.adapter.back()
        assertEquals(listOf(TopicFormIntent.CloseRequested), f.calls)
    }
    @Test fun `ED100 reload is available only to the current detail`() {
        TopicFormMode.entries.forEach { mode ->
            val f = TopicFormChromeFixture(mode)
            f.error(EditorErrors.formNetwork)
            val a = f.events.visibleEvent!!
            f.adapter.host.barActions.onEvent(a.key, EditorEventAction.Details)
            f.adapter.reload(a.key)
            assertEquals(listOf(TopicFormIntent.ReloadRequested), f.calls)
            f.error(EditorErrors.formNetwork)
            val b = f.events.visibleEvent!!
            assertNotEquals(a.key, b.key)
            f.adapter.host.barActions.onEvent(b.key, EditorEventAction.Details)
            f.adapter.reload(a.key)
            assertEquals(b, f.adapter.host.detail)
            assertEquals(1, f.calls.size)
            assertTrue((b.content as EditorEventContent.Problem).error == EditorErrors.formNetwork)
            f.adapter.host.closeDetails(b.key)
            assertNull(f.events.visibleEvent)
        }
    }
    @Test fun `ED44 information consumes the send gesture before validation and session reset drops the header`() {
        val f = TopicFormChromeFixture()
        f.error(EditorErrors.noImageReceived)
        f.adapter.host.submitActions.onSubmit()
        assertTrue(f.calls.isEmpty())
        assertNull(f.events.visibleEvent)
        f.adapter.host.submitActions.onSubmit()
        assertEquals(1, f.calls.size)
        f.adapter.openHeader()
        f.memory.reset()
        f.events.open(f.session.copy(id = "other", owner = "bob"))
        assertFalse(f.memory.headerOpen)
        assertNull(f.adapter.host.surface)
    }
    @Test fun `Complete does not consume the next hidden information while opening the header`() {
        val f = TopicFormChromeFixture()
        f.error(EditorErrors.missingSubcategory)
        val validation = f.events.visibleEvent!!
        f.error(EditorErrors.noImageReceived)
        f.adapter.host.barActions.onEvent(validation.key, EditorEventAction.Complete)
        assertEquals(TopicFormChromeSurface.Header, f.adapter.host.surface)
        assertEquals(TopicFormHeaderField.Subcategory, f.memory.headerField)
        assertEquals(EditorErrors.noImageReceived,
            (f.events.visibleEvent!!.content as EditorEventContent.Problem).error)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun `ED170 visual closing and a first post reload never resolve the durable uncertain state`() {
        TopicFormMode.entries.forEach { mode ->
            val f = TopicFormChromeFixture(mode)
            val uncertain = EditorSubmissionState.Uncertain(null)
            f.state = f.state.copy(submission = uncertain)
            f.error(EditorErrors.uncertain)
            val event = f.events.visibleEvent!!
            f.adapter.host.barActions.onEvent(event.key, EditorEventAction.Details)
            f.adapter.reload(event.key)
            assertTrue(f.calls.isEmpty()) // A known body has no missing form remedy.
            if (mode == TopicFormMode.EditFirstPost) {
                f.state = f.state.copy(draftHydratedFromServer = false)
                f.adapter.reload(event.key)
                assertEquals(listOf(TopicFormIntent.ReloadRequested), f.calls)
            } else {
                f.adapter.back()
            }
            assertNull(f.adapter.host.detail)
            assertEquals(uncertain, f.state.submission)
        }
    }

    @Test fun `ED85 Back closes preview without consuming displayed information or the masked mode`() {
        val f = TopicFormChromeFixture()
        f.state = f.state.copy(isPreviewVisible = true)
        f.adapter.host.gesture(EditorBarGesture.ToggleFormat)
        f.error(EditorErrors.noImageReceived)
        val information = f.events.visibleEvent
        f.adapter.back()
        assertEquals(listOf(TopicFormIntent.TogglePreview), f.calls)
        assertEquals(information, f.events.visibleEvent)
        assertEquals(EditorBarMode.Format, f.memory.host.bar.mode)
    }

}
