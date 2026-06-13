package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.upload.UploadedImageRecord

/**
 * UI state of the « Mes images uploadées » screen (#459 PR3). Consolidated state + intents while it
 * stays short (project convention, cf. `TopicUiState`). The list is observed from
 * [fr.forumhfr.redface2.core.domain.upload.UploadRepository.observeUploads] for the active HFR
 * pseudo ; an anonymous session surfaces [Mode.RequiresLogin] (never a crash).
 */
data class MesImagesUiState(
    val mode: Mode = Mode.Loading,
    /** The record awaiting deletion confirmation ; non-null drives the confirm dialog. */
    val pendingDeletion: UploadedImageRecord? = null,
    /** One-shot deletion outcome reflected in a snackbar, then cleared via [MesImagesIntent.MessageShown]. */
    val deletionMessage: DeletionMessage? = null,
) {
    sealed interface Mode {
        /** Initial state before the first auth/list emission. */
        data object Loading : Mode

        /** No active HFR session : nothing to scope the history to. */
        data object RequiresLogin : Mode

        /** Authenticated ; [images] may be empty (the screen then shows its empty state). */
        data class Content(val images: List<UploadedImageRecord>) : Mode
    }

    /**
     * Result of a deletion surfaced to the user. The local trace is ALWAYS evicted by the
     * repository ; the distinction here is the host outcome the repository returned:
     * [Confirmed] when the host confirmed the deletion, [BestEffort] when it could not be
     * confirmed (best-effort host, missing handle) — the image may linger on the host.
     */
    enum class DeletionMessage { Confirmed, BestEffort }
}

sealed interface MesImagesIntent {
    /** Open the confirm dialog for [record] (deferred delete). */
    data class RequestDelete(val record: UploadedImageRecord) : MesImagesIntent

    /** Confirm the pending deletion → calls the repository. */
    data object ConfirmDelete : MesImagesIntent

    /** Dismiss the confirm dialog without deleting. */
    data object CancelDelete : MesImagesIntent

    /** Acknowledge the one-shot deletion snackbar so it is not shown again. */
    data object MessageShown : MesImagesIntent
}
