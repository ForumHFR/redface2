package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.EditorSubmissionTrace
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptIdentity
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import fr.forumhfr.redface2.core.domain.error.HfrServerException
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
import kotlinx.coroutines.isActive
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
    /** Called synchronously when arming, or by launch without confirmation, before any persistence or network work. */
    val attemptPrepared: (AttemptIdentity) -> Unit = {},
)

/** ED-88: the feature supplies its existing navigation effect, after terminal state and cleanup. */
data class EditorDelivery<E, F>(
    val effect: E? = null,
    val error: F? = null,
    val afterSettled: (() -> Unit)? = null,
    val outcome: EditorSendOutcome = if (effect != null) {
        EditorSendOutcome.SUCCESS
    } else {
        EditorSendOutcome.CERTAIN_FAILURE
    },
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
    private var preparedAttempt: PreparedAttempt? = null
    private var preference: Boolean? = null
    private var initialized = false
    private var uncertainError = false
    private var attemptWriteFailed = false
    private var pendingDraftFailure = false
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

    suspend fun restore(
        key: String,
        onPublished: (AttemptToken) -> Unit = {},
    ): EditorDraftStore.DraftEntry? {
        val entry = try {
            drafts.load()
        } catch (_: IOException) {
            draftSaveFailed()
            null
        }
        if (drafts.invalidated) return null
        // PUBLISHED suppresses restoration, but a deliberate new composition is allowed by CAS.
        entry?.attempt?.takeIf { it.status == AttemptStatus.PUBLISHED }?.let { onPublished(it.token) }
        val attempt = entry?.attempt?.takeUnless { it.status == AttemptStatus.PUBLISHED }
        controller.openSession(session(drafts.owner, key), attempt)
        preparedAttempt = null
        initialized = true
        publish()
        if (attempt != null) showError(errors.uncertain)
        return entry?.takeUnless { it.attempt?.status == AttemptStatus.PUBLISHED }
    }

    fun accountChanged() {
        drafts.invalidate()
        autosave?.cancel()
        armExpiry?.cancel()
        initialized = false
        attemptWriteFailed = false
        pendingDraftFailure = false
        isExecuting = false
        controller.openSession(session(null, controller.session.key))
        preparedAttempt = null
        execution?.cancel()
        publish()
        showError(null)
    }

    fun disarm(): Boolean {
        val accepted = controller.disarm()
        armExpiry?.cancel()
        publish()
        return accepted
    }

    fun request(timeoutMillis: Long, canSubmit: Boolean): SubmissionRequest =
        controller.requestSubmit(timeoutMillis, preference, canSubmit && initialized && !drafts.invalidated)
            .also(::requested)

    fun confirm(token: ArmToken?): SubmissionRequest =
        if (token == null || drafts.invalidated) {
            SubmissionRequest.Ignored
        } else {
            val armedAttempt = preparedAttempt?.takeIf { it.permit == SubmissionRequest.Armed(token) }
            controller.confirm(token).also { request ->
                if (request is SubmissionRequest.Accepted) preparedAttempt = armedAttempt?.copy(permit = request)
                requested(request)
            }
        }

    /** ED-170: hiding uncertainty acknowledges its durable attempt before ordinary autosaves resume. */
    fun acknowledge(onAcknowledged: () -> Unit) {
        if (controller.state !is EditorSubmissionState.Uncertain) {
            attemptWriteFailed = false
            showPendingDraftFailure()
        } else {
            val opening = controller.session
            environment.scope.launch {
                val ports = ports(EditorDraftStore.Draft(body = "")) { EditorSendOutcome.CERTAIN_FAILURE }
                val acknowledged = controller.acknowledgeUncertain(ports)
                if (controller.session == opening) {
                    publish()
                    if (acknowledged) {
                        showPendingDraftFailure()
                        onAcknowledged()
                    } else {
                        showError(errors.uncertain)
                    }
                }
            }
        }
    }

    private fun requested(request: SubmissionRequest) {
        if (request is SubmissionRequest.Accepted) armExpiry?.cancel()
        if (request is SubmissionRequest.Armed) {
            armExpiry?.cancel()
            preparedAttempt = PreparedAttempt(request, prepareAttempt(request.token.session))
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
        resolveDraftFailure(result)
        if (result != null && result !is StoreWriteResult.Written && !drafts.invalidated) {
            draftSaveFailed()
        }
        return result
    }

    private fun resolveDraftFailure(result: StoreWriteResult?) {
        if (result is StoreWriteResult.Written) pendingDraftFailure = false
    }

    private fun draftSaveFailed() {
        pendingDraftFailure = true
        environment.diagnostics.record(DiagnosticsLog.Level.WARN, "EditorDraft", "draft_storage_failed")
        if (!uncertainError && controller.state !is EditorSubmissionState.Uncertain && !attemptWriteFailed) {
            showPendingDraftFailure()
        }
    }

    private fun showPendingDraftFailure() {
        showError(if (pendingDraftFailure) errors.draftSaveFailed else null)
        pendingDraftFailure = false
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
        val reserved = (controller.state as? EditorSubmissionState.PersistingAttempt)?.submission
        if (permit.token != reserved || isExecuting) return
        val opening = controller.session
        val attemptId = (preparedAttempt?.takeIf { it.permit == permit }?.identity ?: prepareAttempt(opening)).id
        preparedAttempt = null // Consumed by this permit even if begin later rejects it.
        attemptWriteFailed = false
        showError(null)
        isExecuting = true
        val trace = EditorSubmissionTrace(environment.diagnostics)
        execution = environment.scope.launch(trace) {
            var delivery: EditorDelivery<E, F>? = null
            val ports = ports(draft, trace, attemptId) {
                delivery = deliver(send)
                checkNotNull(delivery).outcome
            }
            try {
                controller.execute(permit.token, ports)
                if (controller.session == opening) {
                    publish()
                    complete(delivery)
                }
            } catch (cancelled: CancellationException) {
                if (controller.session == opening && controller.state is EditorSubmissionState.Uncertain) {
                    showError(errors.uncertain)
                }
                throw cancelled
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
            pendingDraftFailure = false
            delivery?.effect?.let(callbacks.success)
        } else if (current is EditorSubmissionState.Uncertain) {
            showError(errors.uncertain)
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
            EditorPreparationException.Reason.INVALID_FORM -> errors.preparationFailed
            EditorPreparationException.Reason.QUOTE_FETCH -> errors.quoteFetchFailed
        })
    } catch (error: SessionExpiredException) {
        // The login-page sniff alone does not prove that a POST was rejected before storage.
        transportFailure(error, errors.sessionExpired)
    } catch (error: HfrServerException) {
        // Every non-2xx POST remains ambiguous, including login redirects, 421 and 503.
        failedDelivery(error, errors.uncertain, EditorSendOutcome.UNCERTAIN)
    } catch (error: IOException) {
        transportFailure(error, errors.network)
    } catch (error: IllegalStateException) {
        failedDelivery(error, errors.uncertain, EditorSendOutcome.UNCERTAIN)
    }

    private suspend fun transportFailure(error: Exception, certainError: F): EditorDelivery<E, F> =
        if (currentCoroutineContext()[EditorWriteOwner]?.provesNoEmission == true) {
            failedDelivery(error, certainError)
        } else {
            failedDelivery(error, errors.uncertain, EditorSendOutcome.UNCERTAIN)
        }

    private suspend fun failedDelivery(
        error: Exception,
        fallback: F,
        outcome: EditorSendOutcome = EditorSendOutcome.CERTAIN_FAILURE,
    ): EditorDelivery<E, F> {
        // RealCall's callTimeout can replace the guard exception with an InterruptedIOException,
        // even without retaining its cause. The transport witness still proves non-emission.
        val rejected = currentCoroutineContext()[EditorWriteOwner]?.rejectedBeforeEmission == true ||
            generateSequence<Throwable>(error) { it.cause }.any { it is EditorAccountChangedBeforeSendException }
        return EditorDelivery(
            error = if (rejected) errors.sessionExpired else fallback,
            outcome = if (rejected) EditorSendOutcome.CERTAIN_FAILURE else outcome,
        )
    }

    private fun ports(
        draft: EditorDraftStore.Draft,
        trace: EditorSubmissionTrace = EditorSubmissionTrace(environment.diagnostics),
        attemptId: String = UUID.randomUUID().toString(),
        send: suspend () -> EditorSendOutcome,
    ): EditorSubmissionPorts {
        val opening = controller.session
        var writtenAttempt: AttemptToken? = null
        return object : EditorSubmissionPorts {
            override val isEditorScopeActive: Boolean get() = environment.scope.isActive
            override suspend fun beginAttempt(): EditorDraftStore.BeginAttemptResult {
                val started = environment.clock.elapsedRealtimeNanos()
                val result = drafts.begin(draft, attemptId)
                if (result is EditorDraftStore.BeginAttemptResult.Started) {
                    writtenAttempt = result.token
                    pendingDraftFailure = false
                    trace.durable((environment.clock.elapsedRealtimeNanos() - started) / NANOS_PER_MILLI)
                }
                return result
            }
            override suspend fun send(attempt: AttemptToken): EditorSendOutcome {
                if (!drafts.ownsCurrentAccount()) {
                    accountChanged()
                    return EditorSendOutcome.CERTAIN_FAILURE
                }
                return send()
                    .also { trace.result(EditorSubmissionTrace.Result.valueOf(it.name)) }
            }
            override suspend fun stopDraftWrites() {
                // ED-161: lock success before the first suspension of the marker path.
                if (controller.session == opening) publish()
                autosave?.cancelAndJoin()
            }
            override suspend fun finishAttempt(attempt: AttemptToken, outcome: AttemptOutcome): StoreWriteResult {
                if (controller.session == opening) publish()
                val result = if (outcome == AttemptOutcome.CERTAIN_FAILURE) {
                    withContext(NonCancellable) { drafts.finish(attempt, outcome) }
                } else {
                    drafts.finish(attempt, outcome)
                }
                // Acknowledging an older attempt only changes metadata; it cannot save newer edits.
                if (attempt == writtenAttempt) resolveDraftFailure(result)
                return result
            }
            override suspend fun erasePublishedContent(attempt: AttemptToken): StoreWriteResult {
                return drafts.erase(attempt).also(::resolveDraftFailure)
            }
            override fun recordFailure(stage: EditorSubmissionFailure, result: StoreWriteResult) {
                trace.storageFailure(stage, result)
                if (stage == EditorSubmissionFailure.ATTEMPT) trace.result(
                    EditorSubmissionTrace.Result.DRAFT_WRITE_FAILED,
                )
                if (stage == EditorSubmissionFailure.ATTEMPT && controller.session == opening) {
                    attemptWriteFailed = true
                    showError(errors.draftWriteFailed)
                }
            }
        }
    }

    private fun showError(value: F?) {
        // Arming a deliberate retry temporarily changes Uncertain to Armed; its warning still wins.
        uncertainError = value == errors.uncertain
        callbacks.error(value)
    }

    private fun prepareAttempt(opening: EditorSession): AttemptIdentity =
        AttemptIdentity(opening.owner.orEmpty(), opening.key, UUID.randomUUID().toString())
            .also(callbacks.attemptPrepared)

    private fun publish() {
        // Disarming (typing/back/expiry) discards the UUID without ever writing it to the draft store.
        if (controller.state !is EditorSubmissionState.Armed && preparedAttempt?.permit is SubmissionRequest.Armed) {
            preparedAttempt = null
        }
        callbacks.state(controller.state)
    }

    private fun session(owner: String?, key: String) = EditorSession(UUID.randomUUID().toString(), owner, key)

    private data class PreparedAttempt(val permit: SubmissionRequest, val identity: AttemptIdentity)

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val AUTOSAVE_MILLIS = 750L
    }
}
