package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.EditorSession

sealed interface EditorEventInput {
    /** Explicit opening with a fresh session identity; same session is a no-op, never a render hook. */
    data class OpenSession(val session: EditorSession) : EditorEventInput
    /** Register work, removing superseded progress; only an effective launch applies ED-44 cleanup. */
    data class ObserveOperation(val session: EditorSession, val operation: OperationRef) : EditorEventInput
    /**
     * Only an accepted, effective launch (send/upload/quote insertion), never the arm tap.
     * Observation is optional; an observed operation may start only before its first occurrence.
     */
    data class OperationStarted(val event: EditorEvent) : EditorEventInput
    /**
     * First delivery may register a current operation at lastOccurrence = -1, without launch cleanup.
     * Stale sessions/sequences and repeated or finished occurrences remain rejected.
     */
    data class Received(val event: EditorEvent) : EditorEventInput
    /** Progress mutates an existing occurrence; it does not create another event/announcement. */
    data class UploadAdvanced(val key: EventKey, val progress: UploadProgress) : EditorEventInput
    data class FormLoaded(
        val session: EditorSession,
        val operation: OperationRef.Local,
        val quotesResolved: Boolean = false,
    ) : EditorEventInput
    data class DurableWriteSucceeded(val session: EditorSession, val operation: OperationRef) : EditorEventInput
    /**
     * Before any tool, arm or send gesture, excluding typing, selection and event activation.
     * The host must stop dispatching that gesture when the transition consumes it (ED-44).
     */
    data class NextGesture(val session: EditorSession) : EditorEventInput
    data class Activate(val key: EventKey) : EditorEventInput
    /** Programmatic visual acknowledgement; gestures use Activate/NextGesture/CloseDetails. No durable write. */
    data class Acknowledge(val key: EventKey) : EditorEventInput
    /** Close this session's open panel and return to the body; acknowledge only if its detail key matches. */
    data class CloseDetails(val key: EventKey) : EditorEventInput
}

/** Interface commands only. In particular there is no durable acknowledgement or quote abandonment. */
sealed interface EditorEventCommand {
    data class OpenDetails(val event: EditorEvent) : EditorEventCommand
    data class CompleteHeader(val key: EventKey, val cause: EditorCause) : EditorEventCommand
    data object ReturnToBody : EditorEventCommand
}

data class EditorEventTransition(
    val state: EditorEventState,
    val commands: List<EditorEventCommand> = emptyList(),
    /** Consume through gesture end, even if tools reappear under the pointer (ED-40/44/45). */
    val gestureConsumed: Boolean = false,
)

/** Pure ED-41/43–45 transitions. No text/selection mapping, clock, persistence, or generated identity. */
fun reduceEditorEvents(state: EditorEventState, input: EditorEventInput): EditorEventTransition = when (input) {
    is EditorEventInput.OpenSession -> EditorEventTransition(
        if (input.session == state.session) state else EditorEventState(input.session),
    )
    is EditorEventInput.ObserveOperation -> EditorEventTransition(
        registerOperation(state, input.session, input.operation) ?: state,
    )
    is EditorEventInput.OperationStarted -> EditorEventTransition(startOperation(state, input.event))
    is EditorEventInput.Received -> EditorEventTransition(receiveEvent(state, input.event))
    is EditorEventInput.UploadAdvanced -> EditorEventTransition(advanceUpload(state, input))
    is EditorEventInput.FormLoaded -> EditorEventTransition(formLoaded(state, input))
    is EditorEventInput.DurableWriteSucceeded -> EditorEventTransition(durableWriteSucceeded(state, input))
    is EditorEventInput.NextGesture -> nextGesture(state, input.session)
    is EditorEventInput.Activate -> activateEvent(state, input.key)
    is EditorEventInput.Acknowledge -> EditorEventTransition(acknowledgeEvent(state, input.key))
    is EditorEventInput.CloseDetails -> closeDetails(state, input.key)
}

private fun registerOperation(
    state: EditorEventState,
    session: EditorSession,
    ref: OperationRef,
): EditorEventState? {
    if (session != state.session || !ref.belongsTo(session)) return null
    val previous = state.currentOperations.find { it.ref.sameKind(ref) }
    return when {
        previous?.ref?.sameOperation(ref) == true -> state
        previous == null || ref.sequence > previous.ref.sequence -> state.copy(
            currentOperations = state.currentOperations.filterNot { it.ref.sameKind(ref) } + EditorEventOperation(ref),
            events = state.events.filterNot {
                it.content is EditorEventContent.InProgress && it.key.operation.sameKind(ref)
            },
        )
        else -> null
    }
}

/**
 * Delivery replaces only the same operation; distinct operations of one kind retain their terminals.
 * Launch cleanup and uncertainty's own lifecycle are handled separately.
 * New deliveries keep their own FIFO arrival order.
 */
internal fun replaceEditorEventsForOperation(
    events: List<EditorEvent>,
    replacement: EditorEvent,
): List<EditorEvent> = events.filterNot {
    it.key.session == replacement.key.session && it.key.operation.sameOperation(replacement.key.operation)
} + replacement

private fun startOperation(state: EditorEventState, event: EditorEvent): EditorEventState {
    val registered = when {
        event.content !is EditorEventContent.InProgress -> null
        event.key.operation.isRead -> null
        else -> registerOperation(state, event.key.session, event.key.operation)
    } ?: return state
    val operation = currentOperation(registered, event.key.session, event.key.operation)
    return if (operation == null || operation.finished || operation.lastOccurrence != -1L) state else {
        val cleared = registered.copy(events = registered.events.filter {
            it.content is EditorEventContent.InProgress ||
                it.isAmbiguous && event.key.operation !is OperationRef.Submission
        })
        receiveEvent(cleared, event)
    }
}

private fun receiveEvent(state: EditorEventState, event: EditorEvent): EditorEventState {
    val registered = registerOperation(state, event.key.session, event.key.operation) ?: state
    val operation = currentOperation(registered, event.key.session, event.key.operation)
        ?.takeUnless { it.finished }
        ?.takeIf { event.key.occurrence > it.lastOccurrence }
        ?: return state
    val updated = operation.copy(
        lastOccurrence = event.key.occurrence,
        finished = event.content !is EditorEventContent.InProgress,
    )
    return registered.copy(
        currentOperations = registered.currentOperations.map { if (it == operation) updated else it },
        events = replaceEditorEventsForOperation(registered.events, event),
    )
}

private fun currentOperation(
    state: EditorEventState,
    session: EditorSession,
    ref: OperationRef,
): EditorEventOperation? {
    if (session != state.session || !ref.belongsTo(session)) return null
    return state.currentOperations.find { it.ref.sameOperation(ref) }
}

private fun advanceUpload(state: EditorEventState, input: EditorEventInput.UploadAdvanced): EditorEventState {
    val operation = currentOperation(state, input.key.session, input.key.operation) ?: return state
    if (operation.finished || operation.ref !is OperationRef.Upload) return state
    val event = state.events.find { it.key == input.key }
    val content = event?.content as? EditorEventContent.InProgress ?: return state
    return state.copy(events = state.events.map {
        if (it.key == input.key) it.copy(content = content.copy(uploadProgress = input.progress)) else it
    })
}

private fun formLoaded(state: EditorEventState, input: EditorEventInput.FormLoaded): EditorEventState {
    val operation = currentOperation(state, input.session, input.operation)
        ?.takeIf { it.ref.isRead && !it.finished } ?: return state
    val quotesResolved = input.quotesResolved || input.operation.kind == EditorLocalOperation.QuoteFetch
    return state.copy(
        currentOperations = state.currentOperations.map { if (it == operation) it.copy(finished = true) else it },
        events = state.events.filterNot {
            val error = (it.content as? EditorEventContent.Problem)?.error
            it.key.operation.sameKind(input.operation) && error != null &&
                error.afterFormLoaded(quotesResolved) == null
        },
    )
}

private fun durableWriteSucceeded(
    state: EditorEventState,
    input: EditorEventInput.DurableWriteSucceeded,
): EditorEventState {
    if (currentOperation(state, input.session, input.operation) == null) return state
    val finishedSave = (input.operation as? OperationRef.Local)?.kind == EditorLocalOperation.DraftSave
    return state.copy(
        currentOperations = state.currentOperations.map {
            if (it.ref.sameOperation(input.operation) && finishedSave) it.copy(finished = true) else it
        },
        events = state.events.filterNot {
            (it.content as? EditorEventContent.Problem)?.error?.cause == EditorCause.DraftSaveFailed
        },
    )
}

private fun nextGesture(state: EditorEventState, session: EditorSession): EditorEventTransition {
    val visible = state.visibleEvent?.takeIf { session == state.session } ?: return EditorEventTransition(state)
    val isInformation = visible.content is EditorEventContent.Information ||
        (visible.content as? EditorEventContent.Problem)?.error?.errorClass == EditorErrorClass.Information
    return if (isInformation) {
        EditorEventTransition(acknowledgeEvent(state, visible.key), gestureConsumed = true)
    } else {
        EditorEventTransition(state)
    }
}

private fun activateEvent(state: EditorEventState, key: EventKey): EditorEventTransition {
    val event = state.visibleEvent?.takeIf { it.key == key } ?: return EditorEventTransition(state)
    return when (event.action) {
        EditorEventAction.Complete -> {
            val error = (event.content as EditorEventContent.Problem).error
            EditorEventTransition(
                acknowledgeEvent(state, key), listOf(EditorEventCommand.CompleteHeader(key, error.cause)),
                gestureConsumed = true,
            )
        }
        EditorEventAction.Details -> EditorEventTransition(
            state.copy(detailKey = key), listOf(EditorEventCommand.OpenDetails(event)), gestureConsumed = true,
        )
        EditorEventAction.Dismiss -> EditorEventTransition(acknowledgeEvent(state, key), gestureConsumed = true)
        null -> EditorEventTransition(state)
    }
}

private fun acknowledgeEvent(state: EditorEventState, key: EventKey): EditorEventState {
    val event = state.events.find { it.key == key }
        ?.takeUnless { it.content is EditorEventContent.InProgress } ?: return state
    return state.copy(events = state.events - event)
}

private fun closeDetails(state: EditorEventState, key: EventKey): EditorEventTransition {
    if (key.session != state.session || state.detailKey == null) return EditorEventTransition(state)
    // Within this session, a stale closure returns focus without acknowledging a newer detail's event.
    val acknowledged = if (state.detailKey == key) acknowledgeEvent(state, key) else state
    return EditorEventTransition(
        acknowledged.copy(detailKey = null),
        listOf(EditorEventCommand.ReturnToBody),
        gestureConsumed = true,
    )
}
