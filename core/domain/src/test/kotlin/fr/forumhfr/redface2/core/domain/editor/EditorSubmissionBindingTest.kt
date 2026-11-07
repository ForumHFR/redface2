package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptIdentity
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorSubmissionBindingTest {
    @Test
    fun `identity precedes scheduling and survives a pending autosave version change`() = runTest {
        val store = VersionedEditorDraftStore()
        val prepared = mutableListOf<AttemptIdentity>()
        val binding = binding(store, prepared)
        binding.restore("key")
        binding.setPreference(false)
        val saveGate = CompletableDeferred<Unit>()
        store.saveReturnGate = saveGate
        val saving = launch { binding.persist(Draft("older body")) }
        runCurrent()
        assertEquals(1L, store.entries["key"]?.version)

        val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("sent body")) { EditorDelivery(effect = Unit) }
        assertEquals(1, prepared.size) // The submission coroutine has not even started yet.
        assertEquals(0, store.beginCalls)
        binding.launch(permit, Draft("duplicate body")) { error("duplicate permit") }
        assertEquals(1, prepared.size)
        runCurrent()
        assertEquals(0, store.beginCalls) // The earlier autosave still holds the draft lock.

        saveGate.complete(Unit)
        runCurrent()
        assertTrue(saving.isCompleted)
        val published = checkNotNull(store.entries["key"]?.attempt)
        assertEquals(AttemptStatus.PUBLISHED, published.status)
        assertEquals(prepared.single(), published.token.identity)
        assertEquals(2L, published.token.version)
        assertEquals(1, store.beginCalls)
    }

    @Test
    fun `retry after a rejected begin synchronously reserves a fresh identity`() = runTest {
        val store = VersionedEditorDraftStore().apply { beginFailure = IOException("disk failure") }
        val prepared = mutableListOf<AttemptIdentity>()
        val binding = binding(store, prepared)
        binding.restore("key")
        binding.setPreference(false)
        val first = binding.request(4_000, true) as SubmissionRequest.Accepted
        binding.launch(first, Draft("body")) { error("rejected begin cannot send") }
        assertEquals(1, prepared.size)
        runCurrent()
        assertEquals(EditorSubmissionState.Ready, binding.state)

        store.beginFailure = null
        val retry = binding.request(4_000, true) as SubmissionRequest.Accepted
        binding.launch(retry, Draft("body")) { EditorDelivery(effect = Unit) }
        assertEquals(2, prepared.size)
        assertNotEquals(prepared[0].id, prepared[1].id)
        runCurrent()
        val published = checkNotNull(store.entries["key"]?.attempt)
        assertEquals(AttemptStatus.PUBLISHED, published.status)
        assertEquals(prepared[1], published.token.identity)
        assertEquals(2, store.beginCalls)
    }

    private fun CoroutineScope.binding(
        store: VersionedEditorDraftStore,
        prepared: MutableList<AttemptIdentity>,
    ) = EditorSubmissionBinding(
        EditorSubmissionEnvironment(this, DiagnosticsClock { 0L }, DiagnosticsLog()),
        EditorDraftSession(store, "key"),
        EditorSubmissionCallbacks<Unit, String>(
            state = {}, error = {}, success = {}, attemptPrepared = { prepared += it },
        ),
        EditorSubmissionErrors("uncertain", "draft", "session", "unknown"),
    )
}
