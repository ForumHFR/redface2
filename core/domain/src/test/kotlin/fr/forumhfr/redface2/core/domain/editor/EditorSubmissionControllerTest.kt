package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Attempt
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ED-160..172: pure clock, suspended writes and explicit outcomes; no Android or network harness. */
class EditorSubmissionControllerTest {
    private val session = EditorSession("first-opening", "alice", "reply:23:1")
    private var nanos = 0L
    private val controller = EditorSubmissionController(DiagnosticsClock { nanos }, session)
    private val attempt = AttemptToken("alice", session.key, "attempt-1", 1)
    private val ports = Ports(attempt)

    @Test
    fun `unknown preference arms without writing or sending`() {
        assertTrue(controller.requestSubmit(4_000) is SubmissionRequest.Armed)
        assertTrue(ports.events.isEmpty())
    }

    @Test
    fun `enabled preference also arms and invalid header does nothing`() {
        assertEquals(SubmissionRequest.Ignored, controller.requestSubmit(4_000, true, canSubmit = false))
        assertTrue(controller.requestSubmit(4_000, true) is SubmissionRequest.Armed)
    }

    @Test
    fun `second tap retains original identity and deadline`() {
        val arm = arm()
        nanos = 300_000_000
        assertEquals(SubmissionRequest.Ignored, controller.requestSubmit(8_000))
        assertEquals(arm, (controller.state as EditorSubmissionState.Armed).token)
        nanos = 4_000_000_000
        controller.expireArm()
        assertEquals(EditorSubmissionState.Ready, controller.state)
    }

    @Test
    fun `confirmation at 399 ms is refused`() {
        val arm = arm()
        nanos = 399_000_000
        assertEquals(SubmissionRequest.Ignored, controller.confirm(arm))
        assertTrue(controller.state is EditorSubmissionState.Armed)
    }

    @Test
    fun `confirmation at 400 ms reserves exactly once`() {
        val arm = arm()
        nanos = 400_000_000
        assertTrue(controller.confirm(arm) is SubmissionRequest.Accepted)
        assertEquals(SubmissionRequest.Ignored, controller.confirm(arm))
        assertTrue(controller.blocksBodyMutation)
    }

    @Test
    fun `caller accessibility timeout is honored just before and at deadline`() {
        val arm = arm(9_000)
        nanos = 8_999_000_000
        controller.expireArm()
        assertEquals(EditorSubmissionState.Armed(arm), controller.state)
        nanos = 9_000_000_000
        assertEquals(SubmissionRequest.Ignored, controller.confirm(arm))
        assertEquals(EditorSubmissionState.Ready, controller.state)
    }

    @Test
    fun `typing and back disarm and old confirmation is inert`() {
        val first = arm()
        assertTrue(controller.disarm())
        val second = arm()
        nanos = 500_000_000
        assertEquals(SubmissionRequest.Ignored, controller.confirm(first))
        assertTrue(controller.disarm())
        assertEquals(SubmissionRequest.Ignored, controller.confirm(second))
    }

    @Test
    fun `wrong identity owner or editor session cannot confirm`() {
        val arm = arm()
        nanos = 500_000_000
        val stale = listOf(
            arm.copy(id = arm.id + 1),
            arm.copy(session = session.copy(owner = "bob")),
            arm.copy(session = session.copy(id = "other-opening")),
        )
        stale.forEach { assertEquals(SubmissionRequest.Ignored, controller.confirm(it)) }
        assertEquals(EditorSubmissionState.Armed(arm), controller.state)
    }

    @Test
    fun `new sheet opening and account change invalidate old arm`() {
        val old = arm()
        controller.openSession(session.copy(id = "second-opening", owner = "bob"))
        nanos = 500_000_000
        assertEquals(SubmissionRequest.Ignored, controller.confirm(old))
        assertEquals(EditorSubmissionState.Ready, controller.state)
    }

    @Test
    fun `upload blocks submit until matching completion and disarms`() {
        val oldArm = arm()
        val upload = checkNotNull(controller.beginUpload())
        assertEquals(EditorSubmissionState.Ready, controller.state)
        assertTrue(controller.blocksBodyMutation)
        assertEquals(SubmissionRequest.Ignored, controller.requestSubmit(4_000, false))
        assertFalse(controller.finishUpload(upload.copy(id = upload.id + 1)))
        assertTrue(controller.finishUpload(upload))
        assertEquals(SubmissionRequest.Ignored, controller.confirm(oldArm))
        assertTrue(controller.requestSubmit(4_000, false) is SubmissionRequest.Accepted)
    }

    @Test
    fun `stale upload completion cannot mutate or release the next upload`() {
        val old = checkNotNull(controller.beginUpload())
        controller.openSession(session.copy(id = "second-opening"))
        val current = checkNotNull(controller.beginUpload())
        assertFalse(controller.finishUpload(old))
        assertTrue(controller.blocksBodyMutation)
        assertTrue(controller.finishUpload(current))
    }

    @Test
    fun `attempt write is awaited and reservation blocks mutations upload and duplicate send`() = runTest {
        ports.beginGate = CompletableDeferred()
        val token = reserve()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { controller.execute(token, ports) }
        assertEquals(listOf("begin"), ports.events)
        assertTrue(controller.blocksBodyMutation)
        assertTrue(controller.blocksClose)
        assertFalse(controller.disarm())
        assertNull(controller.beginUpload())
        assertEquals(SubmissionRequest.Ignored, controller.requestSubmit(4_000, false))
        controller.execute(token, ports)
        assertEquals(listOf("begin"), ports.events)
        ports.beginGate?.complete(Unit)
        job.join()
        assertEquals(listOf("begin", "send", "stop", "PUBLISHED", "erase"), ports.events)
    }

    @Test
    fun `all rejected attempt writes prevent any network emission`() = runTest {
        val failures = listOf(StoreWriteResult.OwnerChanged, StoreWriteResult.Conflict, failed())
        for (failure in failures) {
            ports.begin = BeginAttemptResult.Rejected(failure)
            ports.events.clear()
            controller.execute(reserve(), ports)
            assertEquals(listOf("begin"), ports.events)
            assertEquals(EditorSubmissionState.Ready, controller.state)
            assertEquals(EditorSubmissionFailure.ATTEMPT, ports.failures.last())
        }
    }

    @Test
    fun `wrong persisted owner does not authorize send`() = runTest {
        ports.begin = BeginAttemptResult.Started(attempt.copy(owner = "bob"))
        controller.execute(reserve(), ports)
        assertEquals(listOf("begin"), ports.events)
        assertTrue(controller.state is EditorSubmissionState.Uncertain)
    }

    @Test
    fun `sending blocks body uploads back and repeated tap`() = runTest {
        ports.sendGate = CompletableDeferred()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { controller.execute(reserve(), ports) }
        assertEquals(EditorSubmissionState.Sending(attempt), controller.state)
        assertTrue(controller.blocksBodyMutation)
        assertFalse(controller.disarm())
        assertNull(controller.beginUpload())
        assertEquals(SubmissionRequest.Ignored, controller.requestSubmit(4_000, false))
        ports.sendGate?.complete(Unit)
        job.join()
    }

    @Test
    fun `success is terminal before stopping writes marker and cleanup`() = runTest {
        ports.onStop = { assertEquals(EditorSubmissionState.Succeeded(attempt), controller.state) }
        controller.execute(reserve(), ports)
        assertEquals(listOf("begin", "send", "stop", "PUBLISHED", "erase"), ports.events)
        assertEquals(EditorSubmissionState.Succeeded(attempt), controller.state)
        assertFalse(controller.disarm())
        assertNull(controller.beginUpload())
        assertEquals(SubmissionRequest.Ignored, controller.requestSubmit(4_000, false))
    }

    @Test
    fun `success consumption is once per attempt even with another collector`() = runTest {
        controller.execute(reserve(), ports)
        assertFalse(controller.consumeSuccess(attempt.copy(id = "stale")))
        assertTrue(controller.consumeSuccess(attempt))
        assertFalse(controller.consumeSuccess(attempt))
    }

    @Test
    fun `effect is unavailable until cleanup finishes`() = runTest {
        ports.eraseGate = CompletableDeferred()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { controller.execute(reserve(), ports) }
        assertFalse(controller.consumeSuccess(attempt))
        ports.eraseGate?.complete(Unit)
        job.join()
        assertTrue(controller.consumeSuccess(attempt))
    }

    @Test
    fun `failed published marker keeps text and locks accepted attempt as uncertain`() = runTest {
        ports.finish = failed()
        controller.execute(reserve(), ports)
        assertEquals(listOf("begin", "send", "stop", "PUBLISHED"), ports.events)
        assertEquals(EditorSubmissionState.Uncertain(attempt, accepted = true), controller.state)
        assertEquals(EditorSubmissionFailure.OUTCOME, ports.failures.single())
        assertTrue(controller.blocksBodyMutation)
        assertTrue(controller.consumeSuccess(attempt))
        assertFalse(controller.consumeSuccess(attempt))
        assertEquals(SubmissionRequest.Ignored, controller.requestSubmit(4_000, false))
    }

    @Test
    fun `failed write quiescence never erases text`() = runTest {
        ports.stopError = IOException("autosave failed")
        controller.execute(reserve(), ports)
        assertEquals(listOf("begin", "send", "stop"), ports.events)
        assertEquals(EditorSubmissionState.Uncertain(attempt, accepted = true), controller.state)
        assertTrue(EditorSubmissionFailure.QUIESCE in ports.failures)
        assertTrue(controller.consumeSuccess(attempt))
    }

    @Test
    fun `cleanup failure is journaled and preserves successful effect`() = runTest {
        ports.erase = failed()
        controller.execute(reserve(), ports)
        assertEquals(EditorSubmissionState.Succeeded(attempt), controller.state)
        assertEquals(listOf(EditorSubmissionFailure.CLEANUP), ports.failures)
        assertTrue(controller.consumeSuccess(attempt))
    }

    @Test
    fun `cleanup IOException also preserves success`() = runTest {
        ports.eraseError = IOException("disk full")
        controller.execute(reserve(), ports)
        assertEquals(EditorSubmissionState.Succeeded(attempt), controller.state)
        assertTrue(controller.consumeSuccess(attempt))
        assertEquals(listOf(EditorSubmissionFailure.CLEANUP), ports.failures)
    }

    @Test
    fun `certain failure clears attempt and permits another gesture`() = runTest {
        ports.outcome = EditorSendOutcome.CERTAIN_FAILURE
        controller.execute(reserve(), ports)
        assertEquals(listOf("begin", "send", "CERTAIN_FAILURE"), ports.events)
        assertEquals(EditorSubmissionState.Ready, controller.state)
        assertFalse(controller.consumeSuccess(attempt))
        assertTrue(controller.requestSubmit(4_000, false) is SubmissionRequest.Accepted)
    }

    @Test
    fun `failed certain outcome write remains uncertain without erasing content`() = runTest {
        ports.outcome = EditorSendOutcome.CERTAIN_FAILURE
        ports.finish = failed()
        controller.execute(reserve(), ports)
        assertEquals(EditorSubmissionState.Uncertain(attempt), controller.state)
        assertFalse("erase" in ports.events)
    }

    @Test
    fun `ambiguous outcome is persisted and retains editable text`() = runTest {
        ports.outcome = EditorSendOutcome.UNCERTAIN
        controller.execute(reserve(), ports)
        assertEquals(listOf("begin", "send", "UNCERTAIN"), ports.events)
        assertEquals(EditorSubmissionState.Uncertain(attempt), controller.state)
        assertFalse(controller.blocksBodyMutation)
        assertFalse(controller.consumeSuccess(attempt))
    }

    @Test
    fun `unclassified IOException is conservatively uncertain`() = runTest {
        ports.sendError = IOException("connection reset")
        controller.execute(reserve(), ports)
        assertEquals(EditorSubmissionState.Uncertain(attempt), controller.state)
        assertEquals(listOf("begin", "send", "UNCERTAIN"), ports.events)
    }

    @Test
    fun `cancelled send leaves durable attempt unresolved`() = runTest {
        ports.sendGate = CompletableDeferred()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { controller.execute(reserve(), ports) }
        job.cancel()
        job.join()
        assertEquals(EditorSubmissionState.Uncertain(attempt), controller.state)
        assertEquals(listOf("begin", "send"), ports.events)
    }

    @Test
    fun `cancelled cleanup keeps published success`() = runTest {
        ports.eraseGate = CompletableDeferred()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { controller.execute(reserve(), ports) }
        job.cancel()
        job.join()
        assertEquals(EditorSubmissionState.Succeeded(attempt), controller.state)
        assertTrue(controller.consumeSuccess(attempt))
    }

    @Test
    fun `opening while attempt persists ignores its stale return without sending`() = runTest {
        ports.beginGate = CompletableDeferred()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { controller.execute(reserve(), ports) }
        controller.openSession(session.copy(id = "next"))
        ports.beginGate?.complete(Unit)
        job.join()
        assertEquals(EditorSubmissionState.Ready, controller.state)
        assertEquals(listOf("begin"), ports.events)
    }

    @Test
    fun `stale accepted response records old marker without a success in the new session`() = runTest {
        ports.sendGate = CompletableDeferred()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { controller.execute(reserve(), ports) }
        controller.openSession(session.copy(id = "next"))
        ports.sendGate?.complete(Unit)
        job.join()
        assertEquals(EditorSubmissionState.Ready, controller.state)
        assertEquals(listOf("begin", "send", "stop", "PUBLISHED", "erase"), ports.events)
        assertFalse(controller.consumeSuccess(attempt))
    }

    @Test
    fun `restoration is uncertain for unresolved attempts and terminal for published rows`() {
        listOf(AttemptStatus.IN_PROGRESS, AttemptStatus.UNCERTAIN).forEachIndexed { index, status ->
            controller.openSession(session.copy(id = "restored-$index"), Attempt(attempt, status))
            assertEquals(EditorSubmissionState.Uncertain(attempt), controller.state)
        }
        controller.openSession(session.copy(id = "published"), Attempt(attempt, AttemptStatus.PUBLISHED))
        assertEquals(EditorSubmissionState.Succeeded(attempt), controller.state)
        assertFalse(controller.consumeSuccess(attempt))
        assertTrue(controller.blocksBodyMutation)
    }

    @Test
    fun `restoration from another account or key is ignored`() {
        val foreign = attempt.copy(owner = "bob")
        controller.openSession(session.copy(id = "new"), Attempt(foreign, AttemptStatus.IN_PROGRESS))
        assertEquals(EditorSubmissionState.Ready, controller.state)
    }

    @Test
    fun `acknowledgement clears only uncertainty and keeps text`() = runTest {
        ports.outcome = EditorSendOutcome.UNCERTAIN
        controller.execute(reserve(), ports)
        assertTrue(controller.acknowledgeUncertain(ports))
        assertEquals(EditorSubmissionState.Ready, controller.state)
        assertEquals(listOf("begin", "send", "UNCERTAIN", "CERTAIN_FAILURE"), ports.events)
    }

    @Test
    fun `failed acknowledgement keeps uncertainty and accepted success cannot be acknowledged`() = runTest {
        ports.outcome = EditorSendOutcome.UNCERTAIN
        controller.execute(reserve(), ports)
        ports.finish = failed()
        assertFalse(controller.acknowledgeUncertain(ports))
        assertEquals(EditorSubmissionState.Uncertain(attempt), controller.state)
        controller.openSession(session.copy(id = "accepted-marker-failure"))
        ports.outcome = EditorSendOutcome.SUCCESS
        controller.execute(reserve(), ports)
        assertEquals(EditorSubmissionState.Uncertain(attempt, accepted = true), controller.state)
        assertFalse(controller.acknowledgeUncertain(ports))
    }

    @Test
    fun `throwing attempt storage fails certainly without sending`() = runTest {
        ports.beginError = IOException("disk full")
        controller.execute(reserve(), ports)
        assertEquals(listOf("begin"), ports.events)
        assertEquals(EditorSubmissionState.Ready, controller.state)
        assertEquals(listOf(EditorSubmissionFailure.ATTEMPT), ports.failures)
    }

    @Test
    fun `wrong reservation identity is ignored`() = runTest {
        val token = reserve()
        controller.execute(token.copy(id = token.id + 1), ports)
        assertTrue(ports.events.isEmpty())
        controller.execute(token, ports)
        assertEquals(EditorSubmissionState.Succeeded(attempt), controller.state)
    }

    @Test
    fun `explicit new sheet session permits another deliberate composition`() = runTest {
        controller.execute(reserve(), ports)
        assertTrue(controller.consumeSuccess(attempt))
        controller.openSession(session.copy(id = "second-opening"))
        val second = attempt.copy(id = "attempt-2", version = 2)
        ports.begin = BeginAttemptResult.Started(second)
        controller.execute(reserve(), ports)
        assertEquals(EditorSubmissionState.Succeeded(second), controller.state)
        assertFalse(controller.consumeSuccess(attempt))
        assertTrue(controller.consumeSuccess(second))
        assertEquals(2, ports.events.count { it == "send" })
    }

    @Test
    fun `cancelled attempt persistence restores uncertainty and never sends`() = runTest {
        ports.beginGate = CompletableDeferred()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { controller.execute(reserve(), ports) }
        job.cancel()
        job.join()
        assertEquals(EditorSubmissionState.Uncertain(null), controller.state)
        assertEquals(listOf("begin"), ports.events)
    }

    @Test
    fun `cancelled published marker locks accepted response as uncertain`() = runTest {
        ports.finishGate = CompletableDeferred()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { controller.execute(reserve(), ports) }
        job.cancel()
        job.join()
        assertEquals(EditorSubmissionState.Uncertain(attempt, accepted = true), controller.state)
        assertFalse(controller.consumeSuccess(attempt))
        assertTrue(controller.blocksBodyMutation)
        assertFalse("erase" in ports.events)
    }

    @Test
    fun `account round trip cannot adopt an old response even if caller reuses session value`() = runTest {
        ports.sendGate = CompletableDeferred()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { controller.execute(reserve(), ports) }
        controller.openSession(session.copy(id = "bob-opening", owner = "bob"))
        controller.openSession(session)
        ports.sendGate?.complete(Unit)
        job.join()
        assertEquals(EditorSubmissionState.Ready, controller.state)
        assertFalse(controller.consumeSuccess(attempt))
    }

    @Test
    fun `new gesture clears prior uncertainty before persisting a new attempt`() = runTest {
        ports.outcome = EditorSendOutcome.UNCERTAIN
        controller.execute(reserve(), ports)
        ports.outcome = EditorSendOutcome.SUCCESS
        controller.execute(reserve(), ports)
        assertEquals(
            listOf("begin", "send", "UNCERTAIN", "CERTAIN_FAILURE", "begin", "send", "stop", "PUBLISHED", "erase"),
            ports.events,
        )
        assertEquals(EditorSubmissionState.Succeeded(attempt), controller.state)
    }

    @Test
    fun `restored in progress attempt is cleared only by a new deliberate gesture`() = runTest {
        controller.openSession(session.copy(id = "restored"), Attempt(attempt, AttemptStatus.IN_PROGRESS))
        assertTrue(ports.events.isEmpty())
        controller.execute(reserve(), ports)
        assertEquals(listOf("CERTAIN_FAILURE", "begin", "send", "stop", "PUBLISHED", "erase"), ports.events)
    }

    @Test
    fun `failed uncertainty clearing cannot authorize a new POST`() = runTest {
        controller.openSession(session.copy(id = "restored"), Attempt(attempt, AttemptStatus.IN_PROGRESS))
        ports.finish = failed()
        controller.execute(reserve(), ports)
        assertEquals(listOf("CERTAIN_FAILURE"), ports.events)
        assertEquals(EditorSubmissionState.Uncertain(attempt), controller.state)
        assertFalse(controller.blocksBodyMutation)
    }

    @Test
    fun `restored attempt survives arming and is acknowledged before confirmed retry`() = runTest {
        controller.openSession(session.copy(id = "restored"), Attempt(attempt, AttemptStatus.IN_PROGRESS))
        val confirmation = arm()
        nanos = 400_000_000
        val accepted = controller.confirm(confirmation) as SubmissionRequest.Accepted
        controller.execute(accepted.token, ports)
        assertEquals(listOf("CERTAIN_FAILURE", "begin", "send", "stop", "PUBLISHED", "erase"), ports.events)
    }

    @Test
    fun `disarming a restored attempt keeps uncertainty and allows acknowledgement`() = runTest {
        controller.openSession(session.copy(id = "restored"), Attempt(attempt, AttemptStatus.IN_PROGRESS))
        arm()
        assertTrue(controller.disarm())
        assertEquals(EditorSubmissionState.Uncertain(attempt), controller.state)
        assertTrue(controller.acknowledgeUncertain(ports))
        assertEquals(listOf("CERTAIN_FAILURE"), ports.events)
    }

    @Test
    fun `arm expiration keeps restored identity for the next confirmed retry`() = runTest {
        controller.openSession(session.copy(id = "restored"), Attempt(attempt, AttemptStatus.IN_PROGRESS))
        val expired = arm()
        nanos = 4_000_000_000
        controller.expireArm()
        assertEquals(EditorSubmissionState.Uncertain(attempt), controller.state)
        assertEquals(SubmissionRequest.Ignored, controller.confirm(expired))
        val next = arm()
        nanos += 400_000_000
        controller.execute((controller.confirm(next) as SubmissionRequest.Accepted).token, ports)
        assertEquals("CERTAIN_FAILURE", ports.events.first())
        assertEquals(1, ports.events.count { it == "send" })
    }

    private fun arm(timeout: Long = 4_000): ArmToken =
        (controller.requestSubmit(timeout) as SubmissionRequest.Armed).token
    private fun reserve(): SubmissionToken =
        (controller.requestSubmit(4_000, false) as SubmissionRequest.Accepted).token
    private fun failed(): StoreWriteResult = StoreWriteResult.Failed(IOException("storage failed"))

    private class Ports(private val attempt: AttemptToken) : EditorSubmissionPorts {
        val events = mutableListOf<String>()
        val failures = mutableListOf<EditorSubmissionFailure>()
        var begin: BeginAttemptResult = BeginAttemptResult.Started(attempt)
        var finish: StoreWriteResult = StoreWriteResult.Written(attempt.version)
        var erase: StoreWriteResult = StoreWriteResult.Written(attempt.version)
        var outcome = EditorSendOutcome.SUCCESS
        var beginGate: CompletableDeferred<Unit>? = null
        var sendGate: CompletableDeferred<Unit>? = null
        var eraseGate: CompletableDeferred<Unit>? = null
        var finishGate: CompletableDeferred<Unit>? = null
        var beginError: IOException? = null
        var sendError: IOException? = null
        var stopError: IOException? = null
        var eraseError: IOException? = null
        var onStop: () -> Unit = {}

        override suspend fun beginAttempt(): BeginAttemptResult {
            events += "begin"
            beginGate?.await()
            beginError?.let { throw it }
            return begin
        }
        override suspend fun send(attempt: AttemptToken): EditorSendOutcome {
            events += "send"
            sendGate?.await()
            sendError?.let { throw it }
            return outcome
        }
        override suspend fun stopDraftWrites() {
            events += "stop"
            onStop()
            stopError?.let { throw it }
        }
        override suspend fun finishAttempt(attempt: AttemptToken, outcome: AttemptOutcome): StoreWriteResult {
            events += outcome.name
            finishGate?.await()
            return finish
        }
        override suspend fun erasePublishedContent(attempt: AttemptToken): StoreWriteResult {
            events += "erase"
            eraseGate?.await()
            eraseError?.let { throw it }
            return erase
        }
        override fun recordFailure(stage: EditorSubmissionFailure, result: StoreWriteResult) {
            failures += stage
        }
    }
}
