package fr.forumhfr.redface2.core.model.editor

/** Activity Result contract actually used by the shared editor image picker (#988, #1128). */
enum class ImagePickerContract(val diagnosticName: String) {
    PICK_MULTIPLE_VISUAL_MEDIA("PickMultipleVisualMedia"),
    OPEN_MULTIPLE_DOCUMENTS("OpenMultipleDocuments"),
    GET_MULTIPLE_CONTENTS("GetMultipleContents"),
}

/**
 * Platform-free events emitted by the shared picker before editor-side filtering. In particular,
 * [Result] carries an empty [Result.uris] list when Android returns no selection, so diagnostics
 * can locate the empty picker boundary. The callback carries no result code, so an empty result
 * cannot distinguish deliberate cancellation from picker failure.
 */
sealed interface ImagePickerEvent {
    data class Launched(
        val mode: ImagePickerMode,
        val contract: ImagePickerContract,
    ) : ImagePickerEvent

    data class Result(
        val contract: ImagePickerContract,
        val uris: List<String>,
    ) : ImagePickerEvent
}
