package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.EditorSession

sealed interface EditorEventInput {
    /** Explicit opening with a fresh session identity; same session is a no-op, never a render hook. */
    data class OpenSession(val session: EditorSession) : EditorEventInput
    /** Register work without removing events; only an effective launch applies ED-44 cleanup. */
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
     * A discrete gesture, excluding typing, selection, and activation of the event itself.
     * Pending maintainer decision for ED-44: arm taps are excluded, preserving even information until
     * another gesture or effective launch; ED-44 does not explicitly exclude them for information.
     */
    data class NextGesture(val session: EditorSession) : EditorEventInput
    data class Activate(val key: EventKey) : EditorEventInput
    /** Visual only: the host must not resolve a durable attempt or abandon a pending quote. */
    data class Acknowledge(val key: EventKey) : EditorEventInput
    /** Close the sole open panel and return to the body; acknowledge only if its detail key matches. */
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
    is EditorEventInput.NextGesture -> EditorEventTransition(nextGesture(state, input.session))
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
        )
        else -> null
    }
}

/**
 * Delivery replaces events of the same kind; observation alone preserves them until resolution or launch.
 * Ambiguity requires an acknowledgement or effective submission launch.
 * New deliveries keep their own FIFO arrival order.
 */
internal fun replaceEditorEventsByKind(
    events: List<EditorEvent>,
    ref: OperationRef,
    replacement: EditorEvent,
): List<EditorEvent> = events.filterNot {
    it.key.operation.sameKind(ref) && !it.isAmbiguous
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
    val ref = event.key.operation
    val updated = operation.copy(
        ref = if (ref is OperationRef.Submission && ref.attempt != null) ref else operation.ref,
        lastOccurrence = event.key.occurrence,
        finished = event.content !is EditorEventContent.InProgress,
    )
    return registered.copy(
        currentOperations = registered.currentOperations.map { if (it == operation) updated else it },
        events = replaceEditorEventsByKind(registered.events, ref, event),
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
    val event = state.events.find {
        it.key == input.key && input.key.session == state.session && input.key.operation is OperationRef.Upload
    }
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

private fun nextGesture(state: EditorEventState, session: EditorSession): EditorEventState {
    val visible = state.visibleEvent?.takeIf { session == state.session } ?: return state
    val isInformation = visible.content is EditorEventContent.Information ||
        (visible.content as? EditorEventContent.Problem)?.error?.errorClass == EditorErrorClass.Information
    return if (isInformation) acknowledgeEvent(state, visible.key) else state
}

private fun activateEvent(state: EditorEventState, key: EventKey): EditorEventTransition {
    val event = state.visibleEvent?.takeIf { it.key == key } ?: return EditorEventTransition(state)
    return when (event.action) {
        EditorEventAction.Complete -> {
            val error = (event.content as EditorEventContent.Problem).error
            EditorEventTransition(
                acknowledgeEvent(state, key), listOf(EditorEventCommand.CompleteHeader(key, error.cause)),
            )
        }
        EditorEventAction.Details -> EditorEventTransition(
            state.copy(detailKey = key), listOf(EditorEventCommand.OpenDetails(event)),
        )
        EditorEventAction.Dismiss -> EditorEventTransition(acknowledgeEvent(state, key))
        null -> EditorEventTransition(state)
    }
}

private fun acknowledgeEvent(state: EditorEventState, key: EventKey): EditorEventState {
    val event = state.events.find { it.key == key }
        ?.takeUnless { it.content is EditorEventContent.InProgress } ?: return state
    return state.copy(events = state.events - event)
}

private fun closeDetails(state: EditorEventState, key: EventKey): EditorEventTransition {
    if (state.detailKey == null) return EditorEventTransition(state)
    // A stale closure still returns focus, but cannot acknowledge a newer detail's event.
    val acknowledged = if (state.detailKey == key) acknowledgeEvent(state, key) else state
    return EditorEventTransition(
        acknowledged.copy(detailKey = null),
        listOf(EditorEventCommand.ReturnToBody),
    )
}
