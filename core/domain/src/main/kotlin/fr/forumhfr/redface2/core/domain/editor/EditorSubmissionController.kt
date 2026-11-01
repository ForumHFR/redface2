package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * ED-160/161/164/166/169/170/172: session-local submission arbiter, confined to the caller's thread.
 * The ViewModel schedules [expireArm] at the supplied accessibility timeout; every tap also checks time.
 * Certain failure collapses to Ready (the adapter retains its error); accepted success remains terminal.
 */
class EditorSubmissionController(private val clock: DiagnosticsClock, initialSession: EditorSession) {
    var session: EditorSession = initialSession
        private set
    var state: EditorSubmissionState = EditorSubmissionState.Ready
        private set
    private var sequence = 0L
    private var upload: EditorUploadToken? = null
    private var executing: SubmissionToken? = null
    private var activeSubmission: SubmissionToken? = null
    private var retryAttempt: AttemptToken? = null
    private var successAvailable: AttemptToken? = null
    private var publishedAttempt: AttemptToken? = null

    val blocksBodyMutation: Boolean get() = upload != null || state.blocksBodyMutation
    val blocksClose: Boolean get() = state.blocksBodyMutation

    /** Explicit opening/account change; old async completions cannot mutate the new session. */
    fun openSession(next: EditorSession, restored: EditorDraftStore.Attempt? = null) {
        require(next.id != session.id) { "A new editor session requires a new identity" }
        session = next
        upload = null
        executing = null
        activeSubmission = null
        retryAttempt = null
        successAvailable = null
        publishedAttempt = null
        state = restoredState(next, restored)
    }

    fun requestSubmit(
        timeoutMillis: Long,
        confirmationRequired: Boolean? = null,
        canSubmit: Boolean = true,
    ): SubmissionRequest {
        expireArm()
        return when {
            !canSubmit || blocksBodyMutation -> SubmissionRequest.Ignored
            state is EditorSubmissionState.Armed -> SubmissionRequest.Ignored
            confirmationRequired != false -> arm(timeoutMillis)
            else -> reserve()
        }
    }

    fun confirm(token: ArmToken): SubmissionRequest {
        expireArm()
        val armed = state as? EditorSubmissionState.Armed
        val elapsed = clock.elapsedRealtimeNanos() - token.armedAtNanos
        return if (armed?.token == token && token.session == session && elapsed >= MIN_CONFIRM_NANOS) {
            reserve()
        } else {
            SubmissionRequest.Ignored
        }
    }

    fun expireArm() {
        val token = (state as? EditorSubmissionState.Armed)?.token ?: return
        val elapsed = clock.elapsedRealtimeNanos() - token.armedAtNanos
        if (elapsed >= token.timeoutMillis * NANOS_PER_MILLI) disarm()
    }

    /** Typing and back disarm. The caller must reject its mutation/close when this returns false. */
    fun disarm(): Boolean {
        if (blocksBodyMutation) return false
        if (state is EditorSubmissionState.Armed) state = EditorSubmissionState.Ready
        return true
    }

    fun beginUpload(): EditorUploadToken? = if (blocksBodyMutation) {
        null
    } else {
        disarm()
        EditorUploadToken(++sequence, session).also { upload = it }
    }

    /** Returns true only if this callback may insert its result into the current body. */
    fun finishUpload(token: EditorUploadToken): Boolean {
        val current = upload == token && token.session == session
        if (current) upload = null
        return current && !state.blocksBodyMutation
    }

    /** No suspension precedes the reservation; duplicate execution of a permit is ignored. */
    suspend fun execute(token: SubmissionToken, ports: EditorSubmissionPorts) {
        val reserved = (state as? EditorSubmissionState.PersistingAttempt)?.submission
        if (reserved != token || executing != null) return
        executing = token
        try {
            executeReserved(token, ports)
        } catch (cancelled: CancellationException) {
            if (isCurrent(token) && publishedAttempt == null) {
                state = EditorSubmissionState.Uncertain(currentAttempt(), state is EditorSubmissionState.Succeeded)
            }
            throw cancelled
        } finally {
            if (executing == token) executing = null
        }
    }

    /** ED-170: acknowledge uncertainty without deleting text; accepted success cannot be unlocked. */
    suspend fun acknowledgeUncertain(ports: EditorSubmissionPorts): Boolean {
        val uncertain = state as? EditorSubmissionState.Uncertain
        val attempt = uncertain?.attempt
        return if (uncertain != null && !uncertain.accepted && attempt != null) {
            acknowledge(attempt, uncertain, ports)
        } else {
            false
        }
    }

    /** ED-161: consumed once, by attempt identity, even if the collector is replaced. */
    fun consumeSuccess(attempt: AttemptToken): Boolean {
        val available = successAvailable == attempt
        if (available) successAvailable = null
        return available
    }

    private suspend fun acknowledge(
        attempt: AttemptToken,
        uncertain: EditorSubmissionState.Uncertain,
        ports: EditorSubmissionPorts,
    ): Boolean {
        val token = reserve().token
        return try {
            val result = finishOutcome(attempt, AttemptOutcome.CERTAIN_FAILURE, ports)
            if (result !is StoreWriteResult.Written) ports.recordFailure(EditorSubmissionFailure.OUTCOME, result)
            val acknowledged = result is StoreWriteResult.Written && isCurrent(token)
            if (isCurrent(token)) state = if (acknowledged) EditorSubmissionState.Ready else uncertain
            acknowledged
        } catch (cancelled: CancellationException) {
            if (isCurrent(token)) state = uncertain
            throw cancelled
        } catch (error: IOException) {
            ports.recordFailure(EditorSubmissionFailure.OUTCOME, StoreWriteResult.Failed(error))
            if (isCurrent(token)) state = uncertain
            false
        }
    }

    private fun arm(timeoutMillis: Long): SubmissionRequest.Armed {
        require(timeoutMillis in 1..(Long.MAX_VALUE / NANOS_PER_MILLI))
        val token = ArmToken(++sequence, session, clock.elapsedRealtimeNanos(), timeoutMillis)
        state = EditorSubmissionState.Armed(token)
        return SubmissionRequest.Armed(token)
    }

    private fun reserve(): SubmissionRequest.Accepted {
        val token = SubmissionToken(++sequence, session)
        activeSubmission = token
        retryAttempt = (state as? EditorSubmissionState.Uncertain)?.attempt
        state = EditorSubmissionState.PersistingAttempt(token)
        return SubmissionRequest.Accepted(token)
    }

    private suspend fun executeReserved(token: SubmissionToken, ports: EditorSubmissionPorts) {
        if (!prepareRetry(token, ports) || !isCurrent(token)) return
        val result = try {
            ports.beginAttempt()
        } catch (error: IOException) {
            BeginAttemptResult.Rejected(StoreWriteResult.Failed(error))
        }
        when (result) {
            is BeginAttemptResult.Rejected -> {
                ports.recordFailure(EditorSubmissionFailure.ATTEMPT, result.reason)
                if (isCurrent(token)) state = EditorSubmissionState.Ready
            }
            is BeginAttemptResult.Started -> {
                val attempt = result.token
                if (isCurrent(token) && matches(token, attempt)) {
                    state = EditorSubmissionState.Sending(attempt)
                    send(token, attempt, ports)
                } else if (isCurrent(token)) {
                    ports.recordFailure(EditorSubmissionFailure.ATTEMPT, StoreWriteResult.Conflict)
                    state = EditorSubmissionState.Uncertain(null)
                }
            }
        }
    }

    private suspend fun prepareRetry(token: SubmissionToken, ports: EditorSubmissionPorts): Boolean {
        val previous = retryAttempt ?: return true
        val result = finishOutcome(previous, AttemptOutcome.CERTAIN_FAILURE, ports)
        val cleared = result is StoreWriteResult.Written
        if (!cleared) {
            ports.recordFailure(EditorSubmissionFailure.OUTCOME, result)
            if (isCurrent(token)) state = EditorSubmissionState.Uncertain(previous)
        }
        return cleared
    }

    private suspend fun send(token: SubmissionToken, attempt: AttemptToken, ports: EditorSubmissionPorts) {
        val outcome = try {
            ports.send(attempt)
        } catch (_: IOException) {
            EditorSendOutcome.UNCERTAIN
        }
        if (outcome == EditorSendOutcome.SUCCESS) {
            completeSuccess(token, attempt, ports)
        } else {
            val durable = if (outcome == EditorSendOutcome.CERTAIN_FAILURE) {
                AttemptOutcome.CERTAIN_FAILURE
            } else {
                AttemptOutcome.UNCERTAIN
            }
            val result = finishOutcome(attempt, durable, ports)
            if (result !is StoreWriteResult.Written) ports.recordFailure(EditorSubmissionFailure.OUTCOME, result)
            if (isCurrent(token)) {
                state = if (durable == AttemptOutcome.CERTAIN_FAILURE && result is StoreWriteResult.Written) {
                    EditorSubmissionState.Ready
                } else {
                    EditorSubmissionState.Uncertain(attempt)
                }
            }
        }
    }

    private suspend fun completeSuccess(
        token: SubmissionToken,
        attempt: AttemptToken,
        ports: EditorSubmissionPorts,
    ) {
        if (isCurrent(token)) state = EditorSubmissionState.Succeeded(attempt)
        val marker = try {
            ports.stopDraftWrites()
            finishOutcome(attempt, AttemptOutcome.PUBLISHED, ports)
        } catch (error: IOException) {
            ports.recordFailure(EditorSubmissionFailure.QUIESCE, StoreWriteResult.Failed(error))
            StoreWriteResult.Failed(error)
        }
        if (marker is StoreWriteResult.Written) {
            if (isCurrent(token)) publishedAttempt = attempt
            cleanup(token, attempt, ports)
        } else {
            ports.recordFailure(EditorSubmissionFailure.OUTCOME, marker)
            if (isCurrent(token)) {
                state = EditorSubmissionState.Uncertain(attempt, accepted = true)
                // HFR accepted: retain the unresolved durable row, but still allow the one success pop.
                successAvailable = attempt
            }
        }
    }

    private suspend fun finishOutcome(
        attempt: AttemptToken,
        outcome: AttemptOutcome,
        ports: EditorSubmissionPorts,
    ): StoreWriteResult = try {
        ports.finishAttempt(attempt, outcome)
    } catch (error: IOException) {
        StoreWriteResult.Failed(error)
    }

    private suspend fun cleanup(token: SubmissionToken, attempt: AttemptToken, ports: EditorSubmissionPorts) {
        try {
            val result = ports.erasePublishedContent(attempt)
            if (result !is StoreWriteResult.Written) ports.recordFailure(EditorSubmissionFailure.CLEANUP, result)
        } catch (cancelled: CancellationException) {
            val failure = StoreWriteResult.Failed(IOException("Published content cleanup cancelled", cancelled))
            ports.recordFailure(EditorSubmissionFailure.CLEANUP, failure)
            throw cancelled
        } catch (error: IOException) {
            ports.recordFailure(EditorSubmissionFailure.CLEANUP, StoreWriteResult.Failed(error))
        } finally {
            if (isCurrent(token)) successAvailable = attempt
        }
    }

    private fun isCurrent(token: SubmissionToken): Boolean =
        session == token.session && activeSubmission == token

    private fun matches(token: SubmissionToken, attempt: AttemptToken): Boolean =
        token.session.owner == attempt.owner && token.session.key == attempt.key

    private fun currentAttempt(): AttemptToken? = when (val current = state) {
        is EditorSubmissionState.Sending -> current.attempt
        is EditorSubmissionState.Succeeded -> current.attempt
        is EditorSubmissionState.Uncertain -> current.attempt
        else -> null
    }

    private fun restoredState(next: EditorSession, restored: EditorDraftStore.Attempt?): EditorSubmissionState =
        when {
            restored == null -> EditorSubmissionState.Ready
            next.owner != restored.token.owner || next.key != restored.token.key -> EditorSubmissionState.Ready
            restored.status == EditorDraftStore.AttemptStatus.PUBLISHED ->
                EditorSubmissionState.Succeeded(restored.token)
            else -> EditorSubmissionState.Uncertain(restored.token)
        }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MIN_CONFIRM_NANOS = 400 * NANOS_PER_MILLI
    }
}
