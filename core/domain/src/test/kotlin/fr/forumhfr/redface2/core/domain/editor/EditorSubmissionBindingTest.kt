package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptIdentity
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
        assertTrue(prepared.isEmpty()) // Without confirmation, preparation still happens in launch.
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
    fun `arming prepares synchronously and confirmation keeps the identity for its reserved permit`() = runTest {
        val store = VersionedEditorDraftStore()
        val prepared = mutableListOf<AttemptIdentity>()
        val binding = binding(store, prepared)
        binding.restore("key")
        binding.setPreference(true)

        val arm = binding.request(4_000, true) as SubmissionRequest.Armed
        assertEquals(1, prepared.size)
        assertEquals(0, store.beginCalls)
        assertEquals(0, store.writes)
        assertEquals(SubmissionRequest.Ignored, binding.confirm(arm.token))
        assertEquals(SubmissionRequest.Ignored, binding.request(4_000, true))
        assertEquals(1, prepared.size)

        advanceTimeBy(400)
        val permit = binding.confirm(arm.token) as SubmissionRequest.Accepted
        assertEquals(SubmissionRequest.Ignored, binding.confirm(arm.token))
        assertEquals(1, prepared.size)
        val wrongPermit = permit.copy(token = permit.token.copy(id = permit.token.id + 1))
        binding.launch(wrongPermit, Draft("wrong body")) { error("unreserved permit") }
        binding.disarm() // ED-131: a blocked mutation cannot discard a reserved identity.
        binding.launch(permit, Draft("sent body")) { EditorDelivery(effect = Unit) }
        binding.launch(permit, Draft("duplicate body")) { error("duplicate permit") }
        assertEquals(1, prepared.size)
        assertEquals(0, store.beginCalls)
        runCurrent()
        val published = checkNotNull(store.entries["key"]?.attempt)
        assertEquals(AttemptStatus.PUBLISHED, published.status)
        assertEquals(prepared.single(), published.token.identity)
        assertEquals(1, store.beginCalls)
    }

    @Test
    fun `expiration followed by a new arm prepares a fresh identity without writing the expired one`() = runTest {
        val store = VersionedEditorDraftStore()
        val prepared = mutableListOf<AttemptIdentity>()
        val binding = binding(store, prepared)
        binding.restore("key")
        binding.setPreference(true)
        val expired = binding.request(4_000, true) as SubmissionRequest.Armed
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(EditorSubmissionState.Ready, binding.state)
        assertEquals(0, store.beginCalls)
        assertEquals(0, store.writes)

        val next = binding.request(4_000, true) as SubmissionRequest.Armed
        assertEquals(2, prepared.size)
        assertNotEquals(prepared[0].id, prepared[1].id)
        advanceTimeBy(400)
        assertEquals(SubmissionRequest.Ignored, binding.confirm(expired.token))
        val permit = binding.confirm(next.token) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("body")) { EditorDelivery(effect = Unit) }
        runCurrent()
        val published = checkNotNull(store.entries["key"]?.attempt)
        assertEquals(AttemptStatus.PUBLISHED, published.status)
        assertEquals(prepared[1], published.token.identity)
        assertEquals(2, prepared.size)
        assertEquals(1, store.beginCalls)
    }

    @Test
    fun `disarming then rearming without sending never reuses the abandoned identity`() = runTest {
        val store = VersionedEditorDraftStore()
        val prepared = mutableListOf<AttemptIdentity>()
        val binding = binding(store, prepared)
        binding.restore("key")
        binding.setPreference(true)
        val abandoned = binding.request(4_000, true) as SubmissionRequest.Armed
        binding.disarm() // Dismissal, back and typing all use this same path.
        val next = binding.request(4_000, true) as SubmissionRequest.Armed
        assertEquals(2, prepared.size)
        assertNotEquals(prepared[0].id, prepared[1].id)
        assertEquals(0, store.beginCalls)
        assertEquals(0, store.writes)

        advanceTimeBy(400)
        assertEquals(SubmissionRequest.Ignored, binding.confirm(abandoned.token))
        val permit = binding.confirm(next.token) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("body")) { EditorDelivery(effect = Unit) }
        runCurrent()
        val published = checkNotNull(store.entries["key"]?.attempt)
        assertEquals(AttemptStatus.PUBLISHED, published.status)
        assertEquals(prepared[1], published.token.identity)
        assertEquals(2, prepared.size)
        assertEquals(1, store.beginCalls)
    }

    @Test
    fun `rearming after a rejected confirmed begin renews identity and ignores the previous permit`() = runTest {
        val store = VersionedEditorDraftStore().apply { beginFailure = IOException("disk failure") }
        val prepared = mutableListOf<AttemptIdentity>()
        val binding = binding(store, prepared)
        binding.restore("key")
        binding.setPreference(true)
        val firstArm = binding.request(4_000, true) as SubmissionRequest.Armed
        advanceTimeBy(400)
        val firstPermit = binding.confirm(firstArm.token) as SubmissionRequest.Accepted
        binding.launch(firstPermit, Draft("body")) { error("rejected begin cannot send") }
        runCurrent()
        assertEquals(EditorSubmissionState.Ready, binding.state)
        assertEquals(1, prepared.size)
        assertNull(store.entries["key"]?.attempt)

        store.beginFailure = null
        val nextArm = binding.request(4_000, true) as SubmissionRequest.Armed
        assertEquals(2, prepared.size)
        assertNotEquals(prepared[0].id, prepared[1].id)
        binding.launch(firstPermit, Draft("stale body")) { error("stale permit while armed") }
        advanceTimeBy(400)
        val nextPermit = binding.confirm(nextArm.token) as SubmissionRequest.Accepted
        binding.launch(firstPermit, Draft("stale body")) { error("stale permit after confirmation") }
        binding.launch(nextPermit, Draft("body")) { EditorDelivery(effect = Unit) }
        runCurrent()
        val published = checkNotNull(store.entries["key"]?.attempt)
        assertEquals(AttemptStatus.PUBLISHED, published.status)
        assertEquals(prepared[1], published.token.identity)
        assertEquals(2, prepared.size)
        assertEquals(2, store.beginCalls)
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

    @Test
    fun `tryDisarm reports acceptance and publishes even when mutation is blocked`() = runTest {
        val states = mutableListOf<EditorSubmissionState>()
        val binding = binding(VersionedEditorDraftStore(), mutableListOf(), state = { states += it })
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

    private fun TestScope.binding(
        store: VersionedEditorDraftStore,
        prepared: MutableList<AttemptIdentity>,
        state: (EditorSubmissionState) -> Unit = {},
    ) = EditorSubmissionBinding(
        EditorSubmissionEnvironment(
            this, DiagnosticsClock { testScheduler.currentTime * 1_000_000L }, DiagnosticsLog(),
        ),
        EditorDraftSession(store, "key"),
        EditorSubmissionCallbacks<Unit, String>(
            state = state, error = {}, success = {}, attemptPrepared = { prepared += it },
        ),
        EditorSubmissionErrors("uncertain", "draft", "session", "unknown"),
    )
}
