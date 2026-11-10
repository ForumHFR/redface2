package fr.forumhfr.redface2.feature.editor

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionPurpose
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionToken
import fr.forumhfr.redface2.core.model.write.TopicFormSubcategoryChoice
import fr.forumhfr.redface2.core.ui.editor.EditorBackAction
import fr.forumhfr.redface2.core.ui.editor.EditorBackState
import fr.forumhfr.redface2.core.ui.editor.EditorBarGesture
import fr.forumhfr.redface2.core.ui.editor.EditorBarMode
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.EventKey
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.applyBbcodeAction
import fr.forumhfr.redface2.core.ui.editor.editBbcode
import fr.forumhfr.redface2.core.ui.editor.insertBbcodeToken
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
    fun dispatch(intent: TopicFormIntent) {
        calls += intent
        when (intent) {
            TopicFormIntent.SubmitConfirmationDismissed -> state = state.copy(submission = EditorSubmissionState.Ready)
            is TopicFormIntent.SubjectChanged -> state = state.copy(subject = intent.value)
            is TopicFormIntent.SubcatSelected -> state = state.copy(selectedSubcat = intent.id)
            TopicFormIntent.TogglePreview -> state = state.copy(isPreviewVisible = !state.isPreviewVisible)
            is TopicFormIntent.ToolbarActionClicked -> body.editBbcode { text, start, end ->
                applyBbcodeAction(intent.action, text, start, end)
            }
            is TopicFormIntent.SmileySelected -> body.editBbcode { text, start, end ->
                insertBbcodeToken(intent.token, text, start, end)
            }
            else -> Unit
        }
    }
    fun error(error: EditorError) {
        val previous = state
        events.observe(previous, previous.copy(submitError = null))
        state = state.copy(submitError = error)
        events.observe(previous.copy(submitError = null), state)
    }
    /** VM-like synchronous projection of one state transition, as TopicFormViewModel.updateState delivers it. */
    fun transition(next: TopicFormState) { events.observe(state, next); state = next }
}

/** Render the production topic chrome with deterministic window metrics and real picker registration. */
@Composable
internal fun TopicFormTestContent(
    state: TopicFormState,
    onIntent: (TopicFormIntent) -> Unit = {},
    body: TextFieldState = remember { TextFieldState(state.bodyText) },
    events: TopicFormEvents? = null,
    memory: TopicFormChromeMemory = remember { TopicFormChromeMemory() },
) {
    val scope = rememberCoroutineScope()
    val smileys = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
    val localEvents = remember { TopicFormEvents() }
    val eventStore = events ?: localEvents
    // VM-like fixtures already deliver occurrences synchronously; project only standalone UI state here.
    if (events == null) {
        val previous = remember { arrayOf(state.copy(submitError = null, uploadError = null)) }
        SideEffect { eventStore.observe(previous[0], state); previous[0] = state }
    }
    val height = LocalConfiguration.current.screenHeightDp
    val fallback = remember { PostEditorTestActivityResultOwner() }
    val owner = LocalActivityResultRegistryOwner.current ?: fallback
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
        TopicFormContent(state, body, null, onIntent, smileys, chromeEvents = eventStore, chromeMemory = memory,
            windowMetrics = EditorWindowMetrics(height.dp, 0.dp, 0.dp))
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
    @Test fun `ED100 creation without a subcategory stays supported and never invents a choice`() {
        val f = TopicFormChromeFixture()
        f.state = f.state.copy(hasSubcategorySelect = false, selectedSubcat = null)
        assertNull(f.adapter.state.selectedSubcategory)
        assertFalse(f.adapter.state.showSubcategory)
        assertTrue(f.adapter.state.postAllowed)
        assertTrue(f.adapter.state.sendActivatable)
        assertFalse(f.adapter.state.unsupportedFirstPost)
        assertNull(f.state.headerError)
        f.adapter.subcategory(1)
        assertTrue(f.calls.isEmpty())
    }
    @Test fun `ED100 first post refusal hides a retained selector and rejects its callbacks`() {
        val f = TopicFormChromeFixture(TopicFormMode.EditFirstPost)
        f.state = f.state.copy(hasSubcategorySelect = false)
        assertFalse(f.adapter.state.unsupportedFirstPost) // Absence alone is not a parser refusal.
        assertFalse(f.adapter.state.postAllowed) // But the strict first post POST guard still needs the select.
        f.state = f.state.copy(hasSubcategorySelect = true, firstPostSubcategoryRefused = true)
        assertTrue(f.state.hasSubcategorySelect)
        assertTrue(f.adapter.state.unsupportedFirstPost)
        assertNull(f.adapter.state.selectedSubcategory)
        assertFalse(f.adapter.state.showSubcategory)
        assertFalse(f.adapter.state.postAllowed)
        assertFalse(f.adapter.state.sendActivatable)
        f.adapter.subcategory(1)
        assertTrue(f.calls.isEmpty())
        f.state = f.state.copy(selectedSubcat = null)
        assertNull(f.state.headerError)
        f.state = f.state.copy(subject = TextFieldValue())
        assertEquals(EditorErrors.missingTitle, f.state.headerError)
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
                assertTrue(f.calls.isEmpty()) // No acknowledgement nor disarm reaches the ViewModel.
            }
            assertNull(f.adapter.host.detail)
        }
    }

    @Test fun `ED45 Masquer acknowledges only the exact uncertain delivery once`() {
        TopicFormMode.entries.forEach { mode ->
            // Every uncertain error carries details (ED-112): Masquer is reached from the detail, never the bar.
            val f = TopicFormChromeFixture(mode)
            val attempt = EditorDraftStore.AttemptToken("alice", f.session.key, "attempt", 1)
            f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt),
                submitError = EditorErrors.uncertain))
            val key = f.events.visibleEvent!!.key
            f.adapter.host.barActions.onEvent(key, EditorEventAction.Details)
            f.adapter.host.dismissDetails(key)
            assertEquals(listOf(TopicFormIntent.ErrorDismissed), f.calls)
            assertNull(f.adapter.host.detail)
            assertNull(f.events.visibleEvent)
            f.adapter.host.dismissDetails(key)
            assertEquals(listOf(TopicFormIntent.ErrorDismissed), f.calls)
        }
    }

    @Test fun `ED45 an old uncertainty detail cannot acknowledge a replacement or close its detail`() {
        val f = TopicFormChromeFixture()
        val attempt = EditorDraftStore.AttemptToken("alice", f.session.key, "old", 1)
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt),
            submitError = EditorErrors.uncertain))
        val old = f.events.visibleEvent!!
        f.adapter.host.barActions.onEvent(old.key, EditorEventAction.Details)
        f.transition(f.state.copy(submission = EditorSubmissionState.PersistingAttempt(
            SubmissionToken(3, f.session)), submitError = null))
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt.copy(id = "new")),
            submitError = EditorErrors.uncertain))
        val replacement = f.events.visibleEvent!!
        assertNotEquals(old.key, replacement.key)
        f.adapter.host.dismissDetails(old.key)
        assertTrue(f.calls.isEmpty())
        assertEquals(replacement, f.events.visibleEvent)
        f.adapter.host.barActions.onEvent(replacement.key, EditorEventAction.Details)
        f.adapter.host.dismissDetails(old.key)
        assertEquals(replacement, f.adapter.host.detail)
        assertTrue(f.calls.isEmpty())
        f.adapter.host.dismissDetails(replacement.key)
        assertEquals(listOf(TopicFormIntent.ErrorDismissed), f.calls)
    }

    @Test fun `ED170 cancellation can deliver uncertainty before its submission state`() {
        val f = TopicFormChromeFixture(TopicFormMode.EditFirstPost)
        val attempt = EditorDraftStore.AttemptToken("alice", f.session.key, "cancelled", 1)
        f.transition(f.state.copy(submission = EditorSubmissionState.Sending(attempt),
            submitError = EditorErrors.uncertain))
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt)))
        val key = f.events.visibleEvent!!.key
        f.adapter.host.barActions.onEvent(key, EditorEventAction.Details)
        f.adapter.host.dismissDetails(key)
        assertEquals(listOf(TopicFormIntent.ErrorDismissed), f.calls)
    }

    @Test fun `ED170 a retained delivery cannot borrow a different or accepted attempt`() {
        val f = TopicFormChromeFixture()
        val attempt = EditorDraftStore.AttemptToken("alice", f.session.key, "old", 1)
        listOf(EditorSubmissionState.Uncertain(attempt.copy(id = "new")),
            EditorSubmissionState.Uncertain(attempt, accepted = true)).forEachIndexed { index, newer ->
            f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt), submitError = null))
            f.transition(f.state.copy(submitError = EditorErrors.uncertain))
            val key = f.events.visibleEvent!!.key
            f.adapter.host.barActions.onEvent(key, EditorEventAction.Details)
            f.transition(f.state.copy(submission = newer))
            f.adapter.host.dismissDetails(key)
            assertTrue("attempt guard $index", f.calls.isEmpty())
        }
    }

    @Test fun `ED170 a retained detail cannot acknowledge a new session`() {
        val f = TopicFormChromeFixture()
        val attempt = EditorDraftStore.AttemptToken("alice", f.session.key, "attempt", 1)
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt),
            submitError = EditorErrors.uncertain))
        val key = f.events.visibleEvent!!.key
        f.adapter.host.barActions.onEvent(key, EditorEventAction.Details)
        f.events.open(f.session.copy(id = "new-opening"))
        f.adapter.host.dismissDetails(key)
        assertTrue(f.calls.isEmpty())
        assertNull(f.events.visibleEvent)
    }

    @Test fun `ED170 Back detail closing and reload never acknowledge a bound uncertain attempt`() {
        TopicFormMode.entries.forEach { mode ->
            detailClosings(mode).forEachIndexed { index, (close, expected) ->
                val f = TopicFormChromeFixture(mode)
                val attempt = EditorDraftStore.AttemptToken("alice", f.session.key, "attempt", 1)
                val uncertain = EditorSubmissionState.Uncertain(attempt)
                f.transition(f.state.copy(submission = uncertain, submitError = EditorErrors.uncertain))
                val key = f.events.visibleEvent!!.key
                assertEquals(attempt, f.events.uncertainAttempt(key)) // Masquer could acknowledge it.
                f.adapter.host.barActions.onEvent(key, EditorEventAction.Details)
                close(f, key)
                assertNull("$mode closing $index", f.adapter.host.detail)
                // Neither ErrorDismissed nor a disarm: the exact intents, not a filter (gate R1b minors r1 m1).
                assertEquals("$mode closing $index", expected, f.calls.toList())
                f.adapter.host.dismissDetails(key) // A closed detail has no Masquer left.
                assertEquals("$mode closing $index", expected, f.calls.toList())
            }
        }
    }

    @Test fun `ED170 the topic collector retires the unsent reservation and rebinds a kept uncertainty`() {
        TopicFormMode.entries.forEach { mode ->
            val f = TopicFormChromeFixture(mode)
            val attempt = EditorDraftStore.AttemptToken("alice", f.session.key, "attempt", 1)
            val uncertain = EditorSubmissionState.Uncertain(attempt)
            f.transition(f.state.copy(submission = uncertain, submitError = EditorErrors.uncertain))
            val first = f.events.visibleEvent!!.key
            f.adapter.host.barActions.onEvent(first, EditorEventAction.Details)
            f.adapter.host.dismissDetails(first)
            // The ViewModel reserves an Acknowledge without sending; the failed write keeps the attempt.
            f.transition(f.state.copy(submission = EditorSubmissionState.PersistingAttempt(
                SubmissionToken(3, f.session), EditorSubmissionPurpose.Acknowledge)))
            f.transition(f.state.copy(submission = uncertain))
            assertTrue(f.events.state.events.none { it.content is EditorEventContent.InProgress })
            val kept = f.events.visibleEvent!!
            assertNotEquals(first, kept.key)
            assertEquals(EditorEventContent.Problem(EditorErrors.uncertain), kept.content)
            assertEquals(attempt, f.events.uncertainAttempt(kept.key))
            // Once storage recovers, Masquer on the signalled-again warning acknowledges the same attempt.
            f.adapter.host.barActions.onEvent(kept.key, EditorEventAction.Details)
            f.adapter.host.dismissDetails(kept.key)
            f.transition(f.state.copy(submission = EditorSubmissionState.PersistingAttempt(
                SubmissionToken(4, f.session), EditorSubmissionPurpose.Acknowledge)))
            f.transition(f.state.copy(submission = EditorSubmissionState.Ready))
            f.transition(f.state.copy(submitError = null))
            assertEquals(listOf(TopicFormIntent.ErrorDismissed, TopicFormIntent.ErrorDismissed), f.calls)
            assertTrue(f.events.state.events.isEmpty())
            assertNull(f.events.visibleEvent)
        }
    }

    @Test fun `ED45 local attempt failure Masquer reaches only DraftWriteFailureDismissed once in Ready and Armed`() {
        TopicFormMode.entries.forEach { mode ->
            val f = TopicFormChromeFixture(mode)
            val armed = EditorSubmissionState.Armed(ArmToken(1, f.session, 0, 4_000L))
            listOf(EditorSubmissionState.Ready, armed).forEachIndexed { index, submission ->
                f.transition(f.state.copy(submission = submission))
                f.error(EditorErrors.draftWriteFailed)
                val key = f.events.visibleEvent!!.key
                f.adapter.host.barActions.onEvent(key, EditorEventAction.Details)
                assertEquals(index, f.calls.size) // Opening the detail never acknowledges.
                f.adapter.host.dismissDetails(key)
                f.adapter.host.dismissDetails(key)
                assertEquals("$mode $index", List(index + 1) { TopicFormIntent.DraftWriteFailureDismissed }, f.calls)
                assertNull(f.adapter.host.detail)
                assertNull(f.events.visibleEvent)
            }
        }
    }

    @Test fun `ED85 Back detail closing and reload never acknowledge a local attempt write failure`() {
        TopicFormMode.entries.forEach { mode ->
            detailClosings(mode).forEachIndexed { index, (close, expected) ->
                val f = TopicFormChromeFixture(mode)
                f.error(EditorErrors.draftWriteFailed)
                val key = f.events.visibleEvent!!.key
                f.adapter.host.barActions.onEvent(key, EditorEventAction.Details)
                close(f, key)
                assertNull("$mode closing $index", f.adapter.host.detail)
                f.adapter.host.dismissDetails(key) // A closed detail has no Masquer left.
                // Neither DraftWriteFailureDismissed nor ErrorDismissed: the exact intents (gate R1b minors r1 m1).
                assertEquals("$mode closing $index", expected, f.calls.toList())
            }
        }
    }

    @Test fun `ED45 Masquer of a draft save or network failure never reaches the local acknowledgement`() {
        // The deferred save information shares the « Brouillon non écrit » summary, not the attempt cause.
        val f = TopicFormChromeFixture()
        listOf(EditorErrors.draftSaveFailed, EditorErrors.network).forEach { error ->
            f.error(error)
            val key = f.events.visibleEvent!!.key
            f.adapter.host.barActions.onEvent(key, EditorEventAction.Details)
            f.adapter.host.dismissDetails(key)
            assertNull(f.adapter.host.detail)
            assertNull(f.events.visibleEvent)
        }
        assertTrue(f.calls.isEmpty())
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

    @Test fun `ED85 event detail precedes disarm which precedes the open header`() {
        val f = TopicFormChromeFixture()
        f.adapter.openHeader()
        f.error(EditorErrors.network)
        val key = f.events.visibleEvent!!.key
        f.adapter.host.barActions.onEvent(key, EditorEventAction.Details)
        f.state = f.state.copy(submission = EditorSubmissionState.Armed(
            ArmToken(1, f.session, 0, 4_000L)))
        f.adapter.back()
        assertNull(f.adapter.host.detail)
        assertTrue(f.state.armToken != null)
        assertTrue(f.memory.headerOpen)
        f.adapter.back()
        assertNull(f.state.armToken)
        assertTrue(f.memory.headerOpen)
        f.adapter.back()
        assertFalse(f.memory.headerOpen)
        assertEquals(listOf(TopicFormIntent.SubmitConfirmationDismissed), f.calls)
    }

    @Test fun `image URL confirmation closes the dialog whether the insertion is accepted or refused`() {
        // ImageUrlDialog leaves closing to its adapter (gate T3 r2 C2): both outcomes must close here.
        val f = TopicFormChromeFixture()
        f.adapter.host.open(TopicFormChromeSurface.Url)
        assertEquals(TopicFormChromeSurface.Url, f.adapter.host.surface)
        f.adapter.imageUrl("https://example.test/a.png")
        assertEquals(listOf(TopicFormIntent.ImageUrlInserted("https://example.test/a.png")), f.calls)
        assertNull(f.adapter.host.surface)

        f.adapter.host.open(TopicFormChromeSurface.Url)
        assertEquals(TopicFormChromeSurface.Url, f.adapter.host.surface)
        f.state = f.state.copy(isUploading = true)
        assertFalse(f.adapter.state.host.canInsert)
        f.adapter.imageUrl("https://example.test/b.png")
        assertEquals(listOf(TopicFormIntent.ImageUrlInserted("https://example.test/a.png")), f.calls)
        assertNull(f.adapter.host.surface)
    }
}

/**
 * Back, the detail's closing and, on the first post, its reload: none acknowledges. Each comes with the exact intents
 * it sends; only the first post reload reaches the ViewModel.
 */
private fun detailClosings(mode: TopicFormMode): List<Pair<DetailClosing, List<TopicFormIntent>>> {
    val back: DetailClosing = { f, _ -> f.adapter.back() }
    val close: DetailClosing = { f, key -> f.adapter.host.closeDetails(key) }
    val reload: DetailClosing = { f, key ->
        f.state = f.state.copy(draftHydratedFromServer = false)
        f.adapter.reload(key)
    }
    val reloads = if (mode == TopicFormMode.EditFirstPost) listOf(reload to listOf(TopicFormIntent.ReloadRequested))
        else emptyList()
    return listOf(back to emptyList<TopicFormIntent>(), close to emptyList()) + reloads
}

private typealias DetailClosing = (TopicFormChromeFixture, EventKey) -> Unit
