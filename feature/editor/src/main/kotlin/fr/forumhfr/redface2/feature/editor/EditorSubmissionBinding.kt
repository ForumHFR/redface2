package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import fr.forumhfr.redface2.core.domain.editor.EditorSendOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionController
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionFailure
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionPorts
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.SubmissionRequest
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

internal data class EditorSubmissionEnvironment(
    val scope: CoroutineScope,
    val clock: DiagnosticsClock,
    val diagnostics: DiagnosticsLog,
)

internal data class EditorSubmissionCallbacks<E>(
    val state: (EditorSubmissionState) -> Unit,
    val error: (SubmitError?) -> Unit,
    val success: (E) -> Unit,
)

/** ED-88: the feature supplies its existing navigation effect, after terminal state and cleanup. */
internal data class EditorDelivery<E>(val effect: E? = null, val error: SubmitError? = null)

/** ED-161/164/166/170/171: shared full-screen bridge; the controller owns every submission permit. */
internal class EditorSubmissionBinding<E>(
    private val environment: EditorSubmissionEnvironment,
    val drafts: EditorDraftSession,
    private val callbacks: EditorSubmissionCallbacks<E>,
) {
    private val controller = EditorSubmissionController(environment.clock, session(null, "unloaded"))
    private var armExpiry: Job? = null
    private var autosave: Job? = null
    private var preference: Boolean? = null
    private var initialized = false
    val state: EditorSubmissionState get() = controller.state
    val blocksMutation: Boolean get() = drafts.invalidated || controller.blocksBodyMutation
    val blocksClose: Boolean get() = controller.blocksClose

    fun observePreference(preferences: Flow<Boolean>) {
        environment.scope.launch {
            preferences.catch { emit(true) }.collect { preference = it }
        }
    }

    suspend fun restore(key: String): EditorDraftStore.DraftEntry? {
        val entry = try {
            drafts.load()
        } catch (_: IOException) {
            callbacks.error(SubmitError.DraftWriteFailed)
            null
        }
        if (drafts.invalidated) return null
        // PUBLISHED suppresses restoration, but a deliberate new composition is allowed by CAS.
        val attempt = entry?.attempt?.takeUnless { it.status == AttemptStatus.PUBLISHED }
        controller.openSession(session(drafts.owner, key), attempt)
        initialized = true
        publish()
        if (attempt != null) callbacks.error(SubmitError.Uncertain)
        return entry?.takeUnless { it.attempt?.status == AttemptStatus.PUBLISHED }
    }

    fun accountChanged() {
        drafts.invalidate()
        autosave?.cancel()
        disarm()
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
            environment.scope.launch {
                val ports = ports(EditorDraftStore.Draft(body = "")) { EditorSendOutcome.CERTAIN_FAILURE }
                val acknowledged = controller.acknowledgeUncertain(ports)
                publish()
                callbacks.error(if (acknowledged) null else SubmitError.Uncertain)
                if (acknowledged) onAcknowledged()
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

    suspend fun persist(draft: EditorDraftStore.Draft?) {
        if (draft != null && initialized && !blocksClose) drafts.save(draft)
    }

    suspend fun flush(draft: EditorDraftStore.Draft?) {
        autosave?.cancelAndJoin()
        persist(draft)
    }

    fun launch(
        permit: SubmissionRequest.Accepted,
        draft: EditorDraftStore.Draft,
        send: suspend () -> EditorDelivery<E>,
    ) {
        callbacks.error(null)
        environment.scope.launch {
            var delivery: EditorDelivery<E>? = null
            val ports = ports(draft) {
                delivery = deliver(send)
                if (delivery?.effect != null) EditorSendOutcome.SUCCESS else outcome(delivery?.error)
            }
            try {
                controller.execute(permit.token, ports)
            } finally {
                publish()
            }
            complete(delivery)
        }
    }

    private fun complete(delivery: EditorDelivery<E>?) {
        val current = controller.state
        val attempt = when (current) {
            is EditorSubmissionState.Succeeded -> current.attempt
            is EditorSubmissionState.Uncertain -> current.attempt.takeIf { current.accepted }
            else -> null
        }
        if (attempt != null && controller.consumeSuccess(attempt)) {
            delivery?.effect?.let(callbacks.success)
        } else if (current is EditorSubmissionState.Uncertain) {
            callbacks.error(SubmitError.Uncertain)
        } else {
            delivery?.error?.let(callbacks.error)
        }
    }

    private suspend fun deliver(send: suspend () -> EditorDelivery<E>): EditorDelivery<E> = try {
        publish() // Sending is visible before entering the write repository, including captureOwner().
        send()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: EditorPreparationException) {
        EditorDelivery(error = error.error)
    } catch (_: IOException) {
        EditorDelivery(error = SubmitError.Uncertain)
    } catch (_: IllegalStateException) {
        EditorDelivery(error = SubmitError.Hfr(ReplyFailureReason.Unknown))
    }

    private fun ports(draft: EditorDraftStore.Draft, send: suspend () -> EditorSendOutcome): EditorSubmissionPorts =
        object : EditorSubmissionPorts {
            override suspend fun beginAttempt() = drafts.begin(draft)
            override suspend fun send(attempt: AttemptToken) = send()
            override suspend fun stopDraftWrites() {
                publish() // ED-161: lock success before the first suspension of the marker path.
                autosave?.cancelAndJoin()
            }
            override suspend fun finishAttempt(attempt: AttemptToken, outcome: AttemptOutcome): StoreWriteResult {
                publish()
                return drafts.finish(attempt, outcome)
            }
            override suspend fun erasePublishedContent(attempt: AttemptToken) = drafts.erase(attempt)
            override fun recordFailure(stage: EditorSubmissionFailure, result: StoreWriteResult) {
                environment.diagnostics.record(
                    DiagnosticsLog.Level.WARN, "EditorSubmission", "${stage.name}: ${result::class.simpleName}",
                )
                if (stage == EditorSubmissionFailure.ATTEMPT) callbacks.error(SubmitError.DraftWriteFailed)
            }
        }

    private fun publish() = callbacks.state(controller.state)
    private fun session(owner: String?, key: String) = EditorSession(UUID.randomUUID().toString(), owner, key)
    private fun outcome(error: SubmitError?): EditorSendOutcome =
        if (error == SubmitError.Uncertain) EditorSendOutcome.UNCERTAIN else EditorSendOutcome.CERTAIN_FAILURE

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val AUTOSAVE_MILLIS = 750L
    }
}
