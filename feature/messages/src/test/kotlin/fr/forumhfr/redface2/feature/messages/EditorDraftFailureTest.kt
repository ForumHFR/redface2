package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorDelivery
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSendOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionBinding
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionCallbacks
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionEnvironment
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionErrors
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionRequest
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The five UI adapters use this binding; the report keeps its separate storage indicator. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorDraftFailureTest {
    @Test
    fun `arming a retry cannot let a draft failure hide the uncertain warning`() = runTest {
        val store = VersionedEditorDraftStore()
        val harness = Harness(this, store)
        harness.binding.restore("draft")
        harness.binding.setPreference(false)
        val permit = harness.binding.request(4_000, true) as SubmissionRequest.Accepted
        harness.binding.launch(permit, EditorDraftStore.Draft(body = "sent")) {
            EditorDelivery(outcome = EditorSendOutcome.UNCERTAIN)
        }
        runCurrent()
        store.saveFailure = IOException("full")
        harness.binding.autosave { EditorDraftStore.Draft(body = "edited") }
        harness.binding.setPreference(true)
        assertTrue(harness.binding.request(4_000, true) is SubmissionRequest.Armed)
        advanceTimeBy(751)
        runCurrent()
        assertEquals(EditorErrors.uncertain, harness.error)
        harness.binding.disarm()
    }

    @Test
    fun `autosave and flush use the neutral draft detail`() = runTest {
        val store = VersionedEditorDraftStore().apply { saveFailure = IOException("full") }
        val harness = Harness(this, store)
        harness.binding.restore("draft")
        harness.binding.autosave { EditorDraftStore.Draft(body = "typed") }
        advanceTimeBy(751)
        runCurrent()
        assertEquals(EditorErrors.draftSaveFailed, harness.error)
        harness.error = null
        harness.binding.flush(EditorDraftStore.Draft(body = "typed"))
        assertEquals(EditorErrors.draftSaveFailed, harness.error)
    }

    @Test
    fun `unreadable draft uses the neutral detail without claiming a send outcome`() = runTest {
        val store = object : EditorDraftStore by VersionedEditorDraftStore() {
            override suspend fun loadEntry(owner: String?, key: String): EditorDraftStore.DraftEntry? =
                throw IOException("unreadable")
        }
        val harness = Harness(this, store)
        harness.binding.restore("draft")
        assertEquals(EditorErrors.draftSaveFailed, harness.error)
    }

    @Test
    fun `uncertainty wins and deferred storage failure is shown after explicit acknowledgement`() = runTest {
        val store = VersionedEditorDraftStore()
        val harness = Harness(this, store)
        harness.binding.restore("draft")
        harness.binding.setPreference(false)
        val permit = harness.binding.request(4_000, true) as SubmissionRequest.Accepted
        harness.binding.launch(permit, EditorDraftStore.Draft(body = "sent")) {
            EditorDelivery(outcome = EditorSendOutcome.UNCERTAIN)
        }
        runCurrent()
        store.saveFailure = IOException("SecretBody")
        harness.binding.disarm()
        harness.binding.autosave { EditorDraftStore.Draft(body = "edited") }
        advanceTimeBy(751)
        runCurrent()
        assertTrue(harness.binding.state is EditorSubmissionState.Uncertain)
        assertEquals(EditorErrors.uncertain, harness.error)
        assertEquals(listOf("draft_storage_failed"), harness.log.entries.value
            .filter { it.tag == "EditorDraft" }.map { it.message })
        harness.binding.acknowledge {}
        runCurrent()
        assertEquals(EditorErrors.draftSaveFailed, harness.error)
    }

    @Test
    fun `successful retry write resolves deferred failure before anti flood dismissal`() = runTest {
        val store = VersionedEditorDraftStore()
        val harness = deferDraftFailure(store)
        store.saveFailure = null
        val permit = harness.binding.request(4_000, true) as SubmissionRequest.Accepted
        harness.binding.launch(permit, EditorDraftStore.Draft(body = "edited")) {
            EditorDelivery(error = EditorErrors.antiFlood)
        }
        runCurrent()
        assertEquals("edited", store.saved["draft"]?.body)
        assertEquals(EditorSubmissionState.Ready, harness.binding.state)
        assertEquals(EditorErrors.antiFlood, harness.error)
        harness.binding.acknowledge {}
        assertNull(harness.error)
        assertEquals(1, harness.log.entries.value.count { it.tag == "EditorDraft" })
    }

    @Test
    fun `terminal success cannot surface a previously deferred draft failure`() = runTest {
        val store = VersionedEditorDraftStore()
        val harness = deferDraftFailure(store)
        store.saveFailure = null
        val permit = harness.binding.request(4_000, true) as SubmissionRequest.Accepted
        harness.binding.launch(permit, EditorDraftStore.Draft(body = "edited")) { EditorDelivery(effect = Unit) }
        runCurrent()
        assertTrue(harness.binding.state is EditorSubmissionState.Succeeded)
        assertEquals(1, harness.successes)
        assertEquals("", store.saved["draft"]?.body)
        assertEquals(EditorDraftStore.AttemptStatus.PUBLISHED, store.entries["draft"]?.attempt?.status)
        harness.binding.acknowledge {}
        runCurrent()
        assertNull(harness.error)
        assertEquals(1, harness.successes)
    }

    private suspend fun TestScope.deferDraftFailure(store: VersionedEditorDraftStore): Harness {
        val harness = Harness(this, store)
        harness.binding.restore("draft")
        harness.binding.setPreference(false)
        val permit = harness.binding.request(4_000, true) as SubmissionRequest.Accepted
        harness.binding.launch(permit, EditorDraftStore.Draft(body = "sent")) {
            EditorDelivery(outcome = EditorSendOutcome.UNCERTAIN)
        }
        runCurrent()
        store.saveFailure = IOException("full")
        harness.binding.autosave { EditorDraftStore.Draft(body = "edited") }
        advanceTimeBy(751)
        runCurrent()
        assertEquals("sent", store.saved["draft"]?.body)
        assertEquals(EditorErrors.uncertain, harness.error)
        return harness
    }

    @Test
    fun `attempt failure keeps its certain detail until acknowledged without any POST`() = runTest {
        val store = VersionedEditorDraftStore().apply { beginFailure = IOException("full") }
        val harness = Harness(this, store)
        harness.binding.restore("draft")
        harness.binding.setPreference(false)
        val permit = harness.binding.request(4_000, true) as SubmissionRequest.Accepted
        var posts = 0
        harness.binding.launch(permit, EditorDraftStore.Draft(body = "body")) {
            posts++
            EditorDelivery(effect = Unit)
        }
        runCurrent()
        assertEquals(EditorErrors.draftWriteFailed, harness.error)
        store.saveFailure = IOException("still full")
        harness.binding.flush(EditorDraftStore.Draft(body = "edited"))
        assertEquals(EditorErrors.draftWriteFailed, harness.error)
        assertEquals(0, posts)
        harness.binding.acknowledge {}
        assertEquals(EditorErrors.draftSaveFailed, harness.error)
    }

    private class Harness(scope: CoroutineScope, store: EditorDraftStore) {
        var error: EditorError? = null
        var successes = 0
        val log = DiagnosticsLog()
        val binding = EditorSubmissionBinding(
            EditorSubmissionEnvironment(scope, DiagnosticsClock { 0L }, log),
            EditorDraftSession(store, "draft"),
            EditorSubmissionCallbacks<Unit, EditorError>(state = {}, error = { error = it }, success = { successes++ }),
            EditorSubmissionErrors(
                EditorErrors.uncertain, EditorErrors.draftWriteFailed, EditorErrors.sessionExpired,
                EditorErrors.uncertain, draftSaveFailed = EditorErrors.draftSaveFailed,
            ),
        )
    }
}
