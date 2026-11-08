package fr.forumhfr.redface2.core.model.editor

/** Activity Result contract actually used by the shared editor image picker (#988, #1128). */
enum class ImagePickerContract(val diagnosticName: String) {
    PICK_MULTIPLE_VISUAL_MEDIA("PickMultipleVisualMedia"),
    OPEN_MULTIPLE_DOCUMENTS("OpenMultipleDocuments"),
    GET_MULTIPLE_CONTENTS("GetMultipleContents"),
}

/**
 * Platform-free events emitted by the shared picker before editor-side filtering. In particular,
 * [Result] represents RESULT_OK, with or without images; [Cancelled] preserves every other result code.
 */
sealed interface ImagePickerEvent {
    data class Launched(
        val mode: ImagePickerMode,
        val contract: ImagePickerContract,
    ) : ImagePickerEvent

    data class Unavailable(val mode: ImagePickerMode, val contract: ImagePickerContract) : ImagePickerEvent

    /** Any non-RESULT_OK code is cancellation: only an explicit success may trigger editor feedback or upload. */
    data class Cancelled(val contract: ImagePickerContract, val resultCode: Int) : ImagePickerEvent

    /** A successful (RESULT_OK) callback; an empty list retains the #1128 missing-image feedback. */
    data class Result(
        val contract: ImagePickerContract,
        val uris: List<String>,
    ) : ImagePickerEvent
}
