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
    fun `immediate send and retry publish Send reservations`() = runTest {
        assertSendReservations(confirmation = false)
    }

    @Test
    fun `confirmed send and retry publish Send reservations`() = runTest {
        assertSendReservations(confirmation = true)
    }

    @Test
    fun `acknowledgement publishes Acknowledge until the durable write completes`() = runTest {
        val store = VersionedEditorDraftStore()
        val states = mutableListOf<EditorSubmissionState>()
        val failures = mutableListOf<String?>()
        val binding = binding(store, states = states, failures = failures)
        binding.restore("key")
        binding.setPreference(false)
        val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("sent body")) { EditorDelivery(outcome = EditorSendOutcome.UNCERTAIN) }
        runCurrent()
        val attempt = (binding.state as EditorSubmissionState.Uncertain).attempt
        store.finishGate = CompletableDeferred()
        var acknowledged = false
        binding.acknowledge { acknowledged = true }
        runCurrent()
        val reservation = binding.state as EditorSubmissionState.PersistingAttempt
        assertEquals(EditorSubmissionPurpose.Acknowledge, reservation.purpose)
        assertEquals(reservation, states.last())
        assertEquals(binding.session, reservation.submission.session)
        assertTrue(reservation.submission.id > permit.token.id)
        assertTrue(binding.blocksMutation)
        assertTrue(binding.blocksClose)
        assertFalse(binding.tryDisarm())
        assertEquals(SubmissionRequest.Ignored, binding.request(4_000, true))
        assertFalse(acknowledged)
        assertEquals("uncertain", failures.last())
        assertEquals(attempt, store.entries["key"]?.attempt?.token)
        store.finishGate?.complete(Unit)
        runCurrent()
        assertTrue(acknowledged)
        assertEquals(EditorSubmissionState.Ready, binding.state)
        assertFalse(binding.blocksMutation)
        assertNull(store.entries["key"]?.attempt)
        assertEquals("sent body", store.saved["key"]?.body)
        assertEquals(1, store.beginCalls)
    }

    @Test
    fun flushProviderCapturesAfterAnInflightAutosaveReturns() = runTest {
        val store = VersionedEditorDraftStore()
        val binding = binding(store)
        binding.restore("key")
        var live = "autosaved body"
        store.saveReturnGate = CompletableDeferred()
        binding.autosave { Draft(live) }
        advanceTimeBy(750)
        runCurrent()
        assertEquals("autosaved body", store.saved["key"]?.body)
        val flushing = launch { binding.flush { Draft(live) } }
        runCurrent()
        assertFalse(flushing.isCompleted)
        live = "quote delivered while flush joins"
        store.saveReturnGate?.complete(Unit)
        runCurrent()
        assertTrue(flushing.isCompleted)
        assertEquals(live, store.saved["key"]?.body)
        assertEquals(2L, store.entries["key"]?.version)
    }

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
        val binding = binding(VersionedEditorDraftStore(), states = states)
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

    @Test
    fun `ordinary load errors still initialize autosave and publish a visible storage error`() = runTest {
        // The shared binding must retain the same contract for topic/new MP/reply MP surfaces.
        listOf(
            EditorDraftKey.reply(23, 35421), EditorDraftKey.mpCompose(),
            EditorDraftKey.mpReply(42), EditorDraftKey.newTopic(23),
        ).forEach { key ->
            val store = VersionedEditorDraftStore().apply { loadFailure = IOException("disk") }
            val states = mutableListOf<EditorSubmissionState>()
            val failures = mutableListOf<String?>()
            val binding = binding(store, key, states, failures)
            assertNull(binding.restore(key))
            assertTrue(binding.isInitialized)
            assertEquals(listOf(EditorSubmissionState.Ready), states)
            assertEquals(listOf("save"), failures)
            store.loadFailure = null
            binding.autosave { Draft("continued composition") }
            advanceTimeBy(751)
            runCurrent()
            assertEquals("continued composition", store.saved[key]?.body)
            binding.setPreference(false)
            val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
            binding.launch(permit, Draft("continued composition")) { EditorDelivery(effect = Unit) }
            runCurrent()
            assertEquals(1, store.beginCalls)
        }
    }

    @Test
    fun `an unreadable inline request remains sealed and cannot overwrite or send`() = runTest {
        val store = VersionedEditorDraftStore().apply { loadFailure = UnreadableInlineQuoteRequestException() }
        val states = mutableListOf<EditorSubmissionState>()
        val failures = mutableListOf<String?>()
        val binding = binding(store, states = states, failures = failures)
        assertNull(binding.restore("key"))
        assertFalse(binding.isInitialized)
        assertEquals(listOf("save"), failures)
        assertTrue(states.isEmpty())
        binding.autosave { Draft("must not replace the unreadable row") }
        binding.setPreference(false)
        assertEquals(SubmissionRequest.Ignored, binding.request(4_000, true))
        advanceTimeBy(751)
        runCurrent()
        assertEquals(0, store.writes)
        assertEquals(0, store.beginCalls)
        store.loadFailure = null
        binding.restore("key")
        assertTrue(binding.isInitialized)
    }

    @Test
    fun `flush failure waits for durable uncertainty acknowledgement before showing draft information`() = runTest {
        val store = VersionedEditorDraftStore()
        val failures = mutableListOf<String?>()
        val binding = binding(store, failures = failures)
        binding.restore("key")
        binding.setPreference(false)
        val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("sent body")) { EditorDelivery(outcome = EditorSendOutcome.UNCERTAIN) }
        runCurrent()
        val attempt = store.entries["key"]?.attempt
        assertEquals("uncertain", failures.last())

        store.saveFailure = IOException("disk full")
        binding.flush(Draft("edited after uncertainty"))
        assertEquals("uncertain", failures.last())
        assertTrue(binding.state is EditorSubmissionState.Uncertain)
        assertEquals(attempt, store.entries["key"]?.attempt)
        assertEquals(1, store.finishCalls) // Only the send result, no automatic acknowledgement.

        var acknowledged = false
        store.finishFailure = IOException("cannot acknowledge")
        binding.acknowledge { acknowledged = true }
        runCurrent()
        assertFalse(acknowledged)
        assertEquals("uncertain", failures.last())

        store.finishFailure = null
        binding.acknowledge { acknowledged = true }
        runCurrent()
        assertTrue(acknowledged)
        assertEquals(EditorSubmissionState.Ready, binding.state)
        assertEquals("save", failures.last())
        assertNull(store.entries["key"]?.attempt)
        assertEquals("sent body", store.saved["key"]?.body)

        store.saveFailure = null
        binding.flush(Draft("edited after uncertainty"))
        assertNull(failures.last())
        assertEquals("edited after uncertainty", store.saved["key"]?.body)
    }

    @Test
    fun `successful unresolved flush purges deferred draft information without hiding uncertainty`() = runTest {
        val store = VersionedEditorDraftStore()
        val failures = mutableListOf<String?>()
        val binding = binding(store, failures = failures)
        binding.restore("key")
        binding.setPreference(false)
        val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("sent body")) { EditorDelivery(outcome = EditorSendOutcome.UNCERTAIN) }
        runCurrent()
        val attempt = (binding.state as EditorSubmissionState.Uncertain).attempt
        store.saveFailure = IOException("disk full")
        binding.flush(Draft("edited body"))
        binding.flush(null) // Cancelling a debounce does not prove a durable write.
        assertEquals("uncertain", failures.last())
        store.saveFailure = null
        binding.flush(Draft("edited body"))
        assertEquals("uncertain", failures.last())
        assertEquals(attempt, store.entries["key"]?.attempt?.token)
        binding.acknowledge {}
        runCurrent()
        assertNull(failures.last())
    }

    @Test
    fun `close after a title-only change fails to save asks - ED10`() = runTest {
        val store = VersionedEditorDraftStore()
        val binding = binding(store)
        binding.restore("key")
        assertTrue(binding.flushForClose { Draft("body", subject = "first title") })
        store.saveFailure = IOException("disk full")
        // Unchanged content: the refused rewrite loses nothing, whatever the null/empty header form.
        assertTrue(binding.flushForClose { Draft("body", subject = "first title", recipients = "") })
        // Only the title changed: it is durable nowhere, the close must ask.
        assertFalse(binding.flushForClose { Draft("body", subject = "second title") })
        assertFalse(binding.flushForClose { Draft("body") })
        assertEquals("first title", store.saved["key"]?.subject)
        store.saveFailure = null
        assertTrue(binding.flushForClose { Draft("body", subject = "second title") })
        assertEquals("second title", store.saved["key"]?.subject)
    }

    @Test
    fun `close after a recipients-only change fails to save asks - ED10`() = runTest {
        val store = VersionedEditorDraftStore()
        val binding = binding(store)
        binding.restore("key")
        assertTrue(binding.flushForClose { Draft("body", recipients = "alice", isPrivate = true) })
        store.saveFailure = IOException("disk full")
        assertTrue(binding.flushForClose { Draft("body", recipients = "alice", isPrivate = true) })
        assertFalse(binding.flushForClose { Draft("body", recipients = "alice;bob", isPrivate = true) })
        assertEquals("alice", store.saved["key"]?.recipients)
    }

    @Test
    fun `close over content loaded from the row never asks when storage fails - ED10`() = runTest {
        val store = VersionedEditorDraftStore()
        store.preload("key", Draft("body", subject = "loaded title"))
        val binding = binding(store)
        binding.restore("key")
        store.saveFailure = IOException("disk full")
        assertTrue(binding.flushForClose { Draft("body", subject = "loaded title") })
        assertFalse(binding.flushForClose { Draft("body", subject = "edited title") })
        assertTrue(binding.flushForClose { null })
    }

    @Test
    fun `successful autosave clears visible draft information`() = runTest {
        val store = VersionedEditorDraftStore()
        val failures = mutableListOf<String?>()
        val binding = binding(store, failures = failures)
        binding.restore("key")
        store.saveFailure = IOException("disk full")
        binding.flush(Draft("unsaved body"))
        assertEquals("save", failures.last())
        binding.flush(null)
        assertEquals("save", failures.last())
        store.saveFailure = null
        binding.autosave { Draft("saved body") }
        advanceTimeBy(751)
        runCurrent()
        assertNull(failures.last())
        assertEquals("saved body", store.saved["key"]?.body)
    }

    @Test
    fun `flush failure waits behind attempt write failure until explicit acknowledgement`() = runTest {
        val store = VersionedEditorDraftStore().apply { beginFailure = IOException("disk full") }
        val failures = mutableListOf<String?>()
        val binding = binding(store, failures = failures)
        binding.restore("key")
        binding.setPreference(false)
        val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("body")) { error("no POST after failed attempt write") }
        runCurrent()
        store.saveFailure = IOException("still full")
        binding.flush(Draft("edited body"))
        assertEquals("draft", failures.last())
        binding.acknowledge {}
        assertEquals("save", failures.last())
        store.saveFailure = null
        binding.flush(Draft("edited body"))
        assertNull(failures.last())
    }

    @Test
    fun `local acknowledgement in Ready releases deferred draft failure without storage`() = runTest {
        assertLocalAttemptAcknowledgement(rearm = false, deferFailure = true)
    }

    @Test
    fun `local acknowledgement in Armed releases deferred draft failure without storage`() = runTest {
        assertLocalAttemptAcknowledgement(rearm = true, deferFailure = true)
    }

    @Test
    fun `local acknowledgement clears the attempt error when no draft failure is deferred`() = runTest {
        assertLocalAttemptAcknowledgement(rearm = false, deferFailure = false)
    }

    @Test
    fun `local acknowledgement clears the attempt error without disarming`() = runTest {
        assertLocalAttemptAcknowledgement(rearm = true, deferFailure = false)
    }

    @Test
    fun localAcknowledgementPreservesNewerError() = runTest {
        val store = VersionedEditorDraftStore()
        val failures = mutableListOf<String?>()
        val binding = failedAttemptBinding(store, failures)
        // Match a feature-local submitError replacing the attempt failure before Masquer is handled.
        failures += "quote"
        val displayed = failures.toList()
        val activity = store.storageActivity()
        binding.acknowledgeLocal()
        runCurrent()
        assertEquals(displayed, failures)
        assertEquals(EditorSubmissionState.Ready, binding.state)
        assertEquals(activity, store.storageActivity())
        binding.acknowledgeLocal()
        assertEquals(displayed, failures)
        // The local flag was still released: a later draft failure is delivered immediately.
        binding.resolveDraftWrite(EditorDraftStore.StoreWriteResult.Failed(IOException("disk full")))
        assertEquals(displayed + "save", failures)
    }

    @Test
    fun `local acknowledgement ignores a reserved retry with a previous attempt write failure`() = runTest {
        val store = VersionedEditorDraftStore()
        val failures = mutableListOf<String?>()
        val binding = failedAttemptBinding(store, failures)
        binding.resolveDraftWrite(EditorDraftStore.StoreWriteResult.Failed(IOException("still full")))
        assertTrue(binding.request(4_000, true) is SubmissionRequest.Accepted)
        assertTrue(binding.state is EditorSubmissionState.PersistingAttempt)
        assertEquals("draft", failures.last())
        assertLocalAcknowledgementIgnored(binding, store, failures)
    }

    @Test
    fun `local acknowledgement ignores Sending and preserves its draft information`() = runTest {
        val store = VersionedEditorDraftStore()
        val failures = mutableListOf<String?>()
        val binding = binding(store, failures = failures)
        binding.restore("key")
        binding.setPreference(false)
        val send = CompletableDeferred<Unit>()
        val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("body")) {
            send.await()
            EditorDelivery(outcome = EditorSendOutcome.UNCERTAIN)
        }
        runCurrent()
        assertTrue(binding.state is EditorSubmissionState.Sending)
        binding.resolveDraftWrite(EditorDraftStore.StoreWriteResult.Failed(IOException("disk full")))
        assertEquals("save", failures.last())
        assertLocalAcknowledgementIgnored(binding, store, failures)
        send.complete(Unit)
        runCurrent()
    }

    @Test
    fun `local acknowledgement ignores restored uncertainty even after an attempt write failure`() = runTest {
        val store = VersionedEditorDraftStore()
        val failures = mutableListOf<String?>()
        val binding = failedAttemptBinding(store, failures)
        val attempt = EditorDraftStore.AttemptToken("tester", "key", "restored", 1)
        store.entries["key"] = EditorDraftStore.DraftEntry(
            Draft("restored body"), 1, EditorDraftStore.Attempt(attempt, AttemptStatus.UNCERTAIN),
        )
        binding.restore("key")
        binding.resolveDraftWrite(EditorDraftStore.StoreWriteResult.Failed(IOException("disk full")))
        assertEquals(EditorSubmissionState.Uncertain(attempt), binding.state)
        assertEquals("uncertain", failures.last())
        assertLocalAcknowledgementIgnored(binding, store, failures)

        // Arming does not replace the displayed uncertainty with a local failure.
        binding.setPreference(true)
        assertTrue(binding.request(4_000, true) is SubmissionRequest.Armed)
        assertLocalAcknowledgementIgnored(binding, store, failures)
    }

    @Test
    fun `local acknowledgement ignores uncertainty without a matching durable token`() = runTest {
        val store = object : VersionedEditorDraftStore() {
            override suspend fun beginAttempt(
                owner: String?, key: String, snapshot: Draft, expectedVersion: Long, attemptId: String,
            ): EditorDraftStore.BeginAttemptResult {
                val started = super.beginAttempt(owner, key, snapshot, expectedVersion, attemptId)
                    as EditorDraftStore.BeginAttemptResult.Started
                return started.copy(token = started.token.copy(key = "another editor"))
            }
        }
        val failures = mutableListOf<String?>()
        val binding = binding(store, failures = failures)
        binding.restore("key")
        binding.setPreference(false)
        val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("body")) { error("a mismatched token cannot send") }
        runCurrent()
        assertEquals(EditorSubmissionState.Uncertain(null), binding.state)
        assertTrue("draft" in failures) // recordFailure(ATTEMPT) did set the local flag.
        binding.resolveDraftWrite(EditorDraftStore.StoreWriteResult.Failed(IOException("disk full")))
        assertLocalAcknowledgementIgnored(binding, store, failures)
    }

    @Test
    fun `local acknowledgement without an attempt write failure preserves the displayed error`() = runTest {
        val store = VersionedEditorDraftStore()
        val failures = mutableListOf<String?>()
        val binding = binding(store, failures = failures)
        binding.restore("key")
        binding.resolveDraftWrite(EditorDraftStore.StoreWriteResult.Failed(IOException("disk full")))
        assertEquals("save", failures.last())
        assertLocalAcknowledgementIgnored(binding, store, failures)
        binding.setPreference(true)
        assertTrue(binding.request(4_000, true) is SubmissionRequest.Armed)
        assertLocalAcknowledgementIgnored(binding, store, failures)
    }

    @Test
    fun `successful fresh attempt outcome purges draft information without hiding uncertainty`() = runTest {
        val store = VersionedEditorDraftStore().apply { finishGate = CompletableDeferred() }
        val failures = mutableListOf<String?>()
        val binding = binding(store, failures = failures)
        binding.restore("key")
        binding.setPreference(false)
        val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("body")) { EditorDelivery(outcome = EditorSendOutcome.UNCERTAIN) }
        runCurrent()
        binding.resolveDraftWrite(EditorDraftStore.StoreWriteResult.Failed(IOException("disk")))
        assertEquals("save", failures.last())
        store.finishGate?.complete(Unit)
        runCurrent()
        assertEquals(listOf<String?>(null, "uncertain"), failures.takeLast(2))
        binding.acknowledge {}
        runCurrent()
        assertNull(failures.last())
    }

    @Test
    fun `successful published cleanup purges draft information through the same resolver`() = runTest {
        val store = VersionedEditorDraftStore().apply { cleanupGate = CompletableDeferred() }
        val failures = mutableListOf<String?>()
        val binding = binding(store, failures = failures)
        binding.restore("key")
        binding.setPreference(false)
        val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("body")) { EditorDelivery(effect = Unit) }
        runCurrent()
        binding.resolveDraftWrite(EditorDraftStore.StoreWriteResult.Failed(IOException("disk")))
        assertEquals("save", failures.last())
        store.cleanupGate?.complete(Unit)
        runCurrent()
        assertNull(failures.last())
        assertEquals("", store.saved["key"]?.body)
    }

    @Test
    fun `failed outcome keeps uncertainty without inventing a draft save failure`() = runTest {
        val store = VersionedEditorDraftStore().apply { finishFailure = IOException("disk") }
        val failures = mutableListOf<String?>()
        val binding = binding(store, failures = failures)
        binding.restore("key")
        binding.setPreference(false)
        val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("body")) { EditorDelivery(outcome = EditorSendOutcome.UNCERTAIN) }
        runCurrent()
        assertEquals("uncertain", failures.last())
        assertFalse(failures.contains("save"))
        store.finishFailure = null
        binding.acknowledge {}
        runCurrent()
        assertNull(failures.last())
    }

    private suspend fun TestScope.assertLocalAttemptAcknowledgement(rearm: Boolean, deferFailure: Boolean) {
        val store = VersionedEditorDraftStore()
        val failures = mutableListOf<String?>()
        val binding = failedAttemptBinding(store, failures)
        store.saveFailure = IOException("still full")
        if (deferFailure) binding.flush(Draft("edited body"))
        if (rearm) {
            binding.setPreference(true)
            assertTrue(binding.request(4_000, true) is SubmissionRequest.Armed)
        }
        assertEquals("draft", failures.last())
        val state = binding.state
        val activity = store.storageActivity()
        binding.acknowledgeLocal()
        runCurrent()
        assertEquals(state, binding.state)
        assertEquals(activity, store.storageActivity())
        assertEquals(if (deferFailure) "save" else null, failures.last())
        // A second Masquer cannot clear the newly delivered information.
        val delivered = failures.toList()
        binding.acknowledgeLocal()
        assertEquals(delivered, failures)
        // A later failed write is immediately visible: attemptWriteFailed was reset.
        binding.flush(Draft("next edit"))
        assertEquals(delivered + "save", failures)
        binding.disarm()
    }

    private suspend fun TestScope.failedAttemptBinding(
        store: VersionedEditorDraftStore,
        failures: MutableList<String?>,
    ): EditorSubmissionBinding<Unit, String> {
        store.beginFailure = IOException("disk full")
        val binding = binding(store, failures = failures)
        binding.restore("key")
        binding.setPreference(false)
        val permit = binding.request(4_000, true) as SubmissionRequest.Accepted
        binding.launch(permit, Draft("body")) { error("no POST after failed attempt write") }
        runCurrent()
        assertEquals(EditorSubmissionState.Ready, binding.state)
        assertEquals("draft", failures.last())
        return binding
    }

    private fun TestScope.assertLocalAcknowledgementIgnored(
        binding: EditorSubmissionBinding<Unit, String>,
        store: VersionedEditorDraftStore,
        failures: MutableList<String?>,
    ) {
        val state = binding.state
        val activity = store.storageActivity()
        val entries = store.entries.toMap()
        val displayed = failures.toList()
        binding.acknowledgeLocal()
        runCurrent()
        assertEquals(state, binding.state)
        assertEquals(activity, store.storageActivity())
        assertEquals(entries, store.entries)
        assertEquals(displayed, failures)
    }

    private suspend fun TestScope.assertSendReservations(confirmation: Boolean) {
        val store = VersionedEditorDraftStore()
        val states = mutableListOf<EditorSubmissionState>()
        val binding = binding(store, states = states)
        binding.restore("key")
        binding.setPreference(confirmation)
        repeat(2) {
            val request = binding.request(4_000, true)
            val permit = if (confirmation) {
                advanceTimeBy(400)
                binding.confirm((request as SubmissionRequest.Armed).token) as SubmissionRequest.Accepted
            } else {
                request as SubmissionRequest.Accepted
            }
            val reservation = EditorSubmissionState.PersistingAttempt(permit.token, EditorSubmissionPurpose.Send)
            assertEquals(reservation, binding.state)
            assertEquals(reservation, states.last())
            binding.launch(permit, Draft("body")) { EditorDelivery(outcome = EditorSendOutcome.UNCERTAIN) }
            runCurrent()
            assertTrue(binding.state is EditorSubmissionState.Uncertain)
        }
        assertEquals(2, store.beginCalls)
        assertNotEquals(store.attemptedIds[0], store.attemptedIds[1])
    }

    private fun VersionedEditorDraftStore.storageActivity() = listOf(writes, beginCalls, finishCalls, saveCount)

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
        key: String = "key",
        states: MutableList<EditorSubmissionState> = mutableListOf(),
        failures: MutableList<String?> = mutableListOf(),
    ) = EditorSubmissionBinding(
        EditorSubmissionEnvironment(
            this,
            DiagnosticsClock { testScheduler.currentTime * 1_000_000L },
            DiagnosticsLog(),
        ),
        EditorDraftSession(store, key),
        EditorSubmissionCallbacks<Unit, String>(
            state = { states += it }, error = { failures += it }, success = {},
            clearError = { if (failures.lastOrNull() == it) failures += null },
        ),
        EditorSubmissionErrors("uncertain", "draft", "session", "unknown", draftSaveFailed = "save"),
    )
}
