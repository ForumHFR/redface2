package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorSubmissionBindingTest {
    @Test
    fun `duplicate permit emits once after the pending autosave changes the version`() = runTest {
        val store = VersionedEditorDraftStore()
        val binding = binding(store)
        binding.restore("key")
        binding.setPreference(false)
        val saveGate = CompletableDeferred<Unit>()
        store.saveReturnGate = saveGate
        val saving = launch { binding.persist(Draft("older body")) }
        runCurrent()
        val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("sent body")) { EditorDelivery(effect = Unit) }
        binding.launch(permit, Draft("duplicate")) { error("duplicate permit") }
        runCurrent()
        assertEquals(0, store.beginCalls)
        saveGate.complete(Unit)
        runCurrent()
        assertTrue(saving.isCompleted)
        val published = checkNotNull(store.entries["key"]?.attempt)
        assertEquals(AttemptStatus.PUBLISHED, published.status)
        assertEquals(2L, published.token.version)
        assertEquals(listOf(published.token.id), store.attemptedIds)
    }

    @Test
    fun `arming and rejected confirmation never allocate a durable attempt`() = runTest {
        val store = VersionedEditorDraftStore()
        val binding = binding(store)
        binding.restore("key")
        binding.setPreference(true)
        val arm = binding.request(4_000, true) as SubmissionRequest.Armed
        assertEquals(SubmissionRequest.Ignored, binding.confirm(arm.token))
        assertEquals(SubmissionRequest.Ignored, binding.request(4_000, true))
        assertTrue(store.attemptedIds.isEmpty())
        assertEquals(0, store.writes)
        advanceTimeBy(400)
        val permit = binding.confirm(arm.token) as SubmissionRequest.Accepted
        assertEquals(SubmissionRequest.Ignored, binding.confirm(arm.token))
        val wrong = permit.copy(token = permit.token.copy(id = permit.token.id + 1))
        binding.launch(wrong, Draft("wrong")) { error("unreserved permit") }
        binding.disarm() // The reserved permit remains owned by the controller.
        binding.launch(permit, Draft("sent")) { EditorDelivery(effect = Unit) }
        binding.launch(permit, Draft("duplicate")) { error("duplicate permit") }
        runCurrent()
        assertEquals(1, store.beginCalls)
        assertEquals(AttemptStatus.PUBLISHED, store.entries["key"]?.attempt?.status)
    }

    @Test
    fun `expired and disarmed permits cannot emit after rearming`() = runTest {
        val store = VersionedEditorDraftStore()
        val binding = binding(store)
        binding.restore("key")
        binding.setPreference(true)
        val expired = binding.request(4_000, true) as SubmissionRequest.Armed
        advanceTimeBy(4_000)
        runCurrent()
        val abandoned = binding.request(4_000, true) as SubmissionRequest.Armed
        binding.disarm()
        val next = binding.request(4_000, true) as SubmissionRequest.Armed
        assertTrue(store.attemptedIds.isEmpty())
        advanceTimeBy(400)
        assertEquals(SubmissionRequest.Ignored, binding.confirm(expired.token))
        assertEquals(SubmissionRequest.Ignored, binding.confirm(abandoned.token))
        val permit = binding.confirm(next.token) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("body")) { EditorDelivery(effect = Unit) }
        runCurrent()
        assertEquals(1, store.beginCalls)
    }

    @Test
    fun `a failed confirmed begin gets a fresh UUID and the old permit stays rejected`() = runTest {
        retryRejectedBegin(confirmation = true)
    }

    @Test
    fun `a failed immediate begin gets a fresh UUID and the old permit stays rejected`() = runTest {
        retryRejectedBegin(confirmation = false)
    }

    @Test
    fun `tryDisarm reports acceptance and publishes even when mutation is blocked`() = runTest {
        val states = mutableListOf<EditorSubmissionState>()
        val binding = binding(VersionedEditorDraftStore(), state = { states += it })
        binding.restore("key")
        binding.setPreference(true)
        assertTrue(binding.request(4_000, true) is SubmissionRequest.Armed)

        states.clear()
        assertTrue(binding.tryDisarm())
        assertEquals(EditorSubmissionState.Ready, binding.state)
        assertEquals(listOf(EditorSubmissionState.Ready), states)

        binding.setPreference(false)
        assertTrue(binding.request(4_000, true) is SubmissionRequest.Accepted)
        assertTrue(binding.blocksMutation)
        val blockedState = binding.state

        states.clear()
        assertFalse(binding.tryDisarm())
        assertEquals(blockedState, binding.state)
        assertEquals(listOf(blockedState), states)
    }

    private suspend fun TestScope.retryRejectedBegin(confirmation: Boolean) {
        val store = VersionedEditorDraftStore().apply { beginFailure = IOException("disk") }
        val binding = binding(store)
        binding.restore("key")
        binding.setPreference(confirmation)
        suspend fun permit(): SubmissionRequest.Accepted {
            val request = binding.request(4_000, true)
            if (request is SubmissionRequest.Accepted) return request
            advanceTimeBy(400)
            return binding.confirm((request as SubmissionRequest.Armed).token) as SubmissionRequest.Accepted
        }
        val first = permit()
        binding.launch(first, Draft("body")) { error("failed begin cannot send") }
        runCurrent()
        assertEquals(EditorSubmissionState.Ready, binding.state)
        assertNull(store.entries["key"]?.attempt)
        store.beginFailure = null
        val retry = permit()
        binding.launch(first, Draft("stale")) { error("stale permit") }
        binding.launch(retry, Draft("body")) { EditorDelivery(effect = Unit) }
        runCurrent()
        assertEquals(2, store.beginCalls)
        assertNotEquals(store.attemptedIds[0], store.attemptedIds[1])
        assertEquals(store.attemptedIds[1], store.entries["key"]?.attempt?.token?.id)
    }

    private fun TestScope.binding(
        store: VersionedEditorDraftStore,
        state: (EditorSubmissionState) -> Unit = {},
    ) = EditorSubmissionBinding(
        EditorSubmissionEnvironment(this, DiagnosticsClock { testScheduler.currentTime * 1_000_000L }, DiagnosticsLog()),
        EditorDraftSession(store, "key"),
        EditorSubmissionCallbacks<Unit, String>(state = state, error = {}, success = {}),
        EditorSubmissionErrors("uncertain", "draft", "session", "unknown"),
    )
}
