@file:Suppress("MatchingDeclarationName") // Surface bindings accompany the host projection.

package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState

/** Surface-owned eligibility: activating the button and authorizing a POST are separate guards (ED-35). */
data class EditorHostState(
    val scaffold: EditorScaffoldChrome,
    val submission: EditorSubmissionState,
    val sendActivatable: Boolean,
    val postAllowed: Boolean,
) {
    val canInsert: Boolean get() = !scaffold.bodyReadOnly && !scaffold.uploading
}

/**
 * Business intents stay in the surface; the host never owns a submission controller or its timer.
 * [onComplete] optionally handles CompleteHeader's cause to focus the relevant field/status.
 * This port also owns returning focus/selection to the body when its completion UI closes.
 * Completion requires enabled controls. Without this port the host opens the optional header;
 * without either route it presents Details or Dismiss, preserving visual acknowledgement.
 * [onAcknowledgeUncertain] durably acknowledges an uncertain attempt (ED-45/170). The host calls it only for an
 * explicit Masquer of the delivery bound by [EditorHostEvents.uncertainAttempt], while that exact attempt is
 * still the surface's Uncertain submission.
 * [onAcknowledgeLocal] synchronously acknowledges a failed attempt write, without durable storage. The host
 * calls it only for an explicit Masquer of [EditorCause.DraftWriteFailed]; the binding guards its real state.
 * Back and closing the detail never call either port. Without the relevant port, Masquer stays visual.
 */
@Suppress("LongParameterList") // Independent intent ports at the surface boundary.
data class EditorHostActions(
    val onArm: (Long) -> Unit,
    val onConfirm: (ArmToken?) -> Unit,
    val onDisarm: () -> Unit,
    val onFormat: (BbcodeAction) -> Unit,
    val onPreview: () -> Unit,
    val onClose: () -> Unit,
    val onComplete: ((EditorCause) -> Unit)? = null,
    val onAcknowledgeUncertain: (() -> Unit)? = null,
    val onAcknowledgeLocal: (() -> Unit)? = null,
)

/**
 * Surface identities and capabilities, without business-specific panels in core/ui.
 * [header] is optional (topic/new MP); reply MP supplies only a recipient status to scaffoldChrome.
 * [imageUrl] and [canWrapSelection] select the image actions. [header] restores focus through
 * EditorHeaderPanel; other panels doing the same return false from [returnsToBody]. Sheets/dialogs
 * use the host's deferred return generation.
 * [draftOffer] always uses that deferred return, even with a legacy [returnsToBody] policy.
 * [isDraftOfferDetail] identifies the event detail shown in the same sheet. It also permits the
 * first tap on [draftOffer] under that information; all other information keeps ED-44 consumption.
 */
@Suppress("LongParameterList") // Optional surface capabilities share one host, without surface variants.
data class EditorHostSurfaces<S : Any>(
    val smileys: S,
    val options: S,
    val picker: S,
    val imageUrl: S? = null,
    val header: S? = null,
    val canWrapSelection: Boolean = false,
    val returnsToBody: (S) -> Boolean = { it != header },
    val draftOffer: S? = null,
    val isDraftOfferDetail: (EventKey) -> Boolean = { false },
)

/** Synchronous VM-owned reducer port, supplied by EditorEventCollector or a surface-specific adapter. */
interface EditorHostEvents {
    val state: EditorEventState
    val visibleEvent: EditorEvent? get() = state.visibleEvent
    fun dispatch(input: EditorEventInput): EditorEventTransition
    /**
     * ED-45/170: the durable attempt bound to this exact uncertain delivery of the current session, including a
     * restored attempt without a submission token; null when Masquer can only acknowledge visually.
     */
    fun uncertainAttempt(key: EventKey): EditorDraftStore.AttemptToken? = null
}
