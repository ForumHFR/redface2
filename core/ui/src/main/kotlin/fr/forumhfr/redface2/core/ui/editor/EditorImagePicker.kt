package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode

/** #1128 — the explicit preference selects one contract; no path retries through another. */
fun imagePickRequestFor(mode: ImagePickerMode): ImagePickRequest = when (mode) {
    ImagePickerMode.PHOTO_PICKER -> ImagePickRequest.PhotoPicker
    ImagePickerMode.PHOTO_PICKER_GET_CONTENT -> ImagePickRequest.GetContent("image/*")
    ImagePickerMode.DOCUMENT_PICKER -> ImagePickRequest.Documents(listOf("image/*"))
}

/** #1128 — GET_CONTENT and document selection have no native ceiling; keep the first images in result order. */
fun <T> capPickedImages(uris: List<T>): List<T> = uris.take(MAX_IMAGES_PER_UPLOAD)

/**
 * #1128 — shared selector for every editor. PickMultipleVisualMedia, OpenMultipleDocuments and
 * GetMultipleContents launchers all stay registered across preference changes, so a pending result
 * still reaches its callback. URI access is used immediately for upload; it does not need a
 * persistable grant.
 */
@Composable
fun rememberEditorImagePicker(mode: ImagePickerMode, onPicked: (List<String>) -> Unit): () -> Unit {
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_UPLOAD),
    ) { uris ->
        if (uris.isNotEmpty()) onPicked(uris.map { it.toString() })
    }
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val picked = capPickedImages(uris)
        if (picked.isNotEmpty()) onPicked(picked.map { it.toString() })
    }
    val getContent = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        val picked = capPickedImages(uris)
        if (picked.isNotEmpty()) onPicked(picked.map { it.toString() })
    }
    return {
        when (val request = imagePickRequestFor(mode)) {
            ImagePickRequest.PhotoPicker -> photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
            is ImagePickRequest.GetContent -> getContent.launch(request.mimeType)
            is ImagePickRequest.Documents -> documentPicker.launch(request.mimeTypes.toTypedArray())
        }
    }
}
