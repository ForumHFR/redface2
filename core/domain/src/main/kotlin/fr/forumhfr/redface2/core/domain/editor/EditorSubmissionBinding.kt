package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.EditorSubmissionTrace
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorSubmissionEnvironment(
    val scope: CoroutineScope,
    val clock: DiagnosticsClock,
    val diagnostics: DiagnosticsLog,
)

data class EditorSubmissionCallbacks<E, F>(
    val state: (EditorSubmissionState) -> Unit,
    val error: (F?) -> Unit,
    val success: (E) -> Unit,
)

/** ED-88: the feature supplies its existing navigation effect, after terminal state and cleanup. */
data class EditorDelivery<E, F>(
    val effect: E? = null,
    val error: F? = null,
    val afterSettled: (() -> Unit)? = null,
)

/** ED-161/164/166/170/171: shared bridge; the controller owns every submission permit. */
class EditorSubmissionBinding<E, F>(
    private val environment: EditorSubmissionEnvironment,
    val drafts: EditorDraftSession,
    private val callbacks: EditorSubmissionCallbacks<E, F>,
    private val errors: EditorSubmissionErrors<F>,
) {
    private val controller = EditorSubmissionController(environment.clock, session(null, "unloaded"))
    private var armExpiry: Job? = null
    private var autosave: Job? = null
    private var execution: Job? = null
    private var preference: Boolean? = null
    private var initialized = false
    var isExecuting = false
        private set
    val isInitialized: Boolean get() = initialized && !drafts.invalidated
    val state: EditorSubmissionState get() = controller.state
    val blocksMutation: Boolean get() = drafts.invalidated || controller.blocksBodyMutation
    val blocksClose: Boolean get() = controller.blocksClose

    fun setPreference(value: Boolean?) { preference = value }

    fun observePreference(preferences: Flow<Boolean>) {
        environment.scope.launch {
            preferences.catch { emit(true) }.collect { preference = it }
        }
    }

    suspend fun restore(key: String): EditorDraftStore.DraftEntry? {
        val entry = try {
            drafts.load()
        } catch (_: IOException) {
            callbacks.error(errors.draftWriteFailed)
            null
        }
        if (drafts.invalidated) return null
        // PUBLISHED suppresses restoration, but a deliberate new composition is allowed by CAS.
        val attempt = entry?.attempt?.takeUnless { it.status == AttemptStatus.PUBLISHED }
        controller.openSession(session(drafts.owner, key), attempt)
        initialized = true
        publish()
        if (attempt != null) callbacks.error(errors.uncertain)
        return entry?.takeUnless { it.attempt?.status == AttemptStatus.PUBLISHED }
    }

    fun accountChanged() {
        drafts.invalidate()
        autosave?.cancel()
        armExpiry?.cancel()
        initialized = false
        isExecuting = false
        controller.openSession(session(null, controller.session.key))
        execution?.cancel()
        publish()
        callbacks.error(null)
    }

    fun disarm() {
        controller.disarm()
        armExpiry?.cancel()
        publish()
    }

    fun request(timeoutMillis: Long, canSubmit: Boolean): SubmissionRequest =
        controller.requestSubmit(timeoutMillis, preference, canSubmit && initialized && !drafts.invalidated)
            .also(::requested)

    fun confirm(token: ArmToken?): SubmissionRequest =
        if (token == null || drafts.invalidated) {
            SubmissionRequest.Ignored
        } else {
            controller.confirm(token).also(::requested)
        }

    /** ED-170: hiding uncertainty acknowledges its durable attempt before ordinary autosaves resume. */
    fun acknowledge(onAcknowledged: () -> Unit) {
        if (controller.state !is EditorSubmissionState.Uncertain) {
            callbacks.error(null)
        } else {
            val opening = controller.session
            environment.scope.launch {
                val ports = ports(EditorDraftStore.Draft(body = "")) { EditorSendOutcome.CERTAIN_FAILURE }
                val acknowledged = controller.acknowledgeUncertain(ports)
                if (controller.session == opening) {
                    publish()
                    callbacks.error(if (acknowledged) null else errors.uncertain)
                    if (acknowledged) onAcknowledged()
                }
            }
        }
    }

    private fun requested(request: SubmissionRequest) {
        if (request is SubmissionRequest.Accepted) armExpiry?.cancel()
        if (request is SubmissionRequest.Armed) {
            armExpiry = environment.scope.launch {
                val token = request.token
                val elapsed = (environment.clock.elapsedRealtimeNanos() - token.armedAtNanos) / NANOS_PER_MILLI
                delay((token.timeoutMillis - elapsed).coerceAtLeast(0L))
                controller.expireArm()
                publish()
            }
        }
        publish()
    }

    fun autosave(snapshot: () -> EditorDraftStore.Draft?) {
        if (blocksMutation || !initialized) return
        autosave?.cancel()
        autosave = environment.scope.launch {
            delay(AUTOSAVE_MILLIS)
            persist(snapshot())
        }
    }

    suspend fun persist(draft: EditorDraftStore.Draft?): StoreWriteResult? {
        val result = if (draft != null && initialized && !blocksClose) drafts.save(draft) else null
        if (result != null && result !is StoreWriteResult.Written && !drafts.invalidated) {
            callbacks.error(errors.draftWriteFailed)
        }
        return result
    }

    suspend fun flush(draft: EditorDraftStore.Draft?): StoreWriteResult? {
        autosave?.cancelAndJoin()
        return persist(draft)
    }

    fun launch(
        permit: SubmissionRequest.Accepted,
        draft: EditorDraftStore.Draft,
        send: suspend () -> EditorDelivery<E, F>,
    ) {
        callbacks.error(null)
        isExecuting = true
        val opening = controller.session
        val trace = EditorSubmissionTrace(environment.diagnostics)
        execution = environment.scope.launch(trace) {
            var delivery: EditorDelivery<E, F>? = null
            val ports = ports(draft, trace) {
                delivery = deliver(send)
                if (delivery?.effect != null) EditorSendOutcome.SUCCESS else outcome(delivery?.error)
            }
            try {
                controller.execute(permit.token, ports)
                if (controller.session == opening) {
                    publish()
                    complete(delivery)
                }
            } finally {
                if (controller.session == opening) {
                    isExecuting = false
                    publish()
                }
            }
        }
    }

    private fun complete(delivery: EditorDelivery<E, F>?) {
        if (!blocksMutation) delivery?.afterSettled?.invoke()
        val current = controller.state
        val attempt = when (current) {
            is EditorSubmissionState.Succeeded -> current.attempt
            is EditorSubmissionState.Uncertain -> current.attempt.takeIf { current.accepted }
            else -> null
        }
        if (attempt != null && controller.consumeSuccess(attempt)) {
            delivery?.effect?.let(callbacks.success)
        } else if (current is EditorSubmissionState.Uncertain) {
            callbacks.error(errors.uncertain)
        } else {
            delivery?.error?.let(callbacks.error)
        }
    }

    private suspend fun deliver(send: suspend () -> EditorDelivery<E, F>): EditorDelivery<E, F> = try {
        publish() // Sending is visible before entering the write repository, including captureOwner().
        send()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: EditorPreparationException) {
        failedDelivery(error, when (error.reason) {
            EditorPreparationException.Reason.NETWORK -> errors.network
            EditorPreparationException.Reason.SESSION_EXPIRED -> errors.sessionExpired
        })
    } catch (error: SessionExpiredException) {
        failedDelivery(error, errors.sessionExpiredAfterEntry)
    } catch (error: IOException) {
        failedDelivery(error, errors.uncertain)
    } catch (error: IllegalStateException) {
        failedDelivery(error, errors.unknown)
    }

    private suspend fun failedDelivery(error: Exception, fallback: F): EditorDelivery<E, F> {
        // RealCall's callTimeout can replace the guard exception with an InterruptedIOException,
        // even without retaining its cause. The transport witness still proves non-emission.
        val rejected = currentCoroutineContext()[EditorWriteOwner]?.rejectedBeforeEmission == true ||
            generateSequence<Throwable>(error) { it.cause }.any { it is EditorAccountChangedBeforeSendException }
        return EditorDelivery(error = if (rejected) errors.sessionExpired else fallback)
    }

    private fun ports(
        draft: EditorDraftStore.Draft,
        trace: EditorSubmissionTrace = EditorSubmissionTrace(environment.diagnostics),
        send: suspend () -> EditorSendOutcome,
    ): EditorSubmissionPorts {
        val opening = controller.session
        return object : EditorSubmissionPorts {
            override suspend fun beginAttempt(): EditorDraftStore.BeginAttemptResult {
                val started = environment.clock.elapsedRealtimeNanos()
                val result = drafts.begin(draft)
                if (result is EditorDraftStore.BeginAttemptResult.Started) {
                    trace.durable((environment.clock.elapsedRealtimeNanos() - started) / NANOS_PER_MILLI)
                }
                return result
            }
            override suspend fun send(attempt: AttemptToken): EditorSendOutcome {
                if (!drafts.ownsCurrentAccount()) {
                    accountChanged()
                    return EditorSendOutcome.CERTAIN_FAILURE
                }
                return sendOwned(attempt, trace, send)
                    .also { trace.result(EditorSubmissionTrace.Result.valueOf(it.name)) }
            }
            override suspend fun stopDraftWrites() {
                // ED-161: lock success before the first suspension of the marker path.
                if (controller.session == opening) publish()
                autosave?.cancelAndJoin()
            }
            override suspend fun finishAttempt(attempt: AttemptToken, outcome: AttemptOutcome): StoreWriteResult {
                if (controller.session == opening) publish()
                return if (outcome == AttemptOutcome.CERTAIN_FAILURE) {
                    withContext(NonCancellable) { drafts.finish(attempt, outcome) }
                } else {
                    drafts.finish(attempt, outcome)
                }
            }
            override suspend fun erasePublishedContent(attempt: AttemptToken) = drafts.erase(attempt)
            override fun recordFailure(stage: EditorSubmissionFailure, result: StoreWriteResult) {
                trace.storageFailure(stage, result)
                if (stage == EditorSubmissionFailure.ATTEMPT) trace.result(
                    EditorSubmissionTrace.Result.DRAFT_WRITE_FAILED,
                )
                if (stage == EditorSubmissionFailure.ATTEMPT && controller.session == opening) {
                    callbacks.error(errors.draftWriteFailed)
                }
            }
        }
    }

    private suspend fun sendOwned(
        attempt: AttemptToken,
        trace: EditorSubmissionTrace,
        send: suspend () -> EditorSendOutcome,
    ): EditorSendOutcome {
        val owner = EditorWriteOwner(attempt.owner)
        return try {
            withContext(owner) { send() }
        } catch (cancelled: CancellationException) {
            // The guard ran without emitting HTTP bytes, but accountChanged may have cancelled the
            // continuation first. Resolve this known refusal without reviving the invalidated UI.
            if (owner.rejectedBeforeEmission) {
                val result = withContext(NonCancellable) { drafts.finish(attempt, AttemptOutcome.CERTAIN_FAILURE) }
                if (result !is StoreWriteResult.Written) trace.storageFailure(EditorSubmissionFailure.OUTCOME, result)
            }
            throw cancelled
        }
    }

    private fun publish() = callbacks.state(controller.state)
    private fun session(owner: String?, key: String) = EditorSession(UUID.randomUUID().toString(), owner, key)
    private fun outcome(error: F?): EditorSendOutcome =
        if (error == errors.uncertain) EditorSendOutcome.UNCERTAIN else EditorSendOutcome.CERTAIN_FAILURE

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val AUTOSAVE_MILLIS = 750L
    }
}
