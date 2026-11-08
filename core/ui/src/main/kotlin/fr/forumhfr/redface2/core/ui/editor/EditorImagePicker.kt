package fr.forumhfr.redface2.core.ui.editor

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import fr.forumhfr.redface2.core.model.editor.ImagePickerContract
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode

/** #1128 — the explicit preference selects one contract; no path retries through another. */
fun imagePickRequestFor(mode: ImagePickerMode): ImagePickRequest = when (mode) {
    ImagePickerMode.PHOTO_PICKER -> ImagePickRequest.PhotoPicker
    ImagePickerMode.PHOTO_PICKER_GET_CONTENT -> ImagePickRequest.GetContent("image/*")
    ImagePickerMode.DOCUMENT_PICKER -> ImagePickRequest.Documents(listOf("image/*"))
}

/** The concrete Activity Result contract paired with the persisted #1128 mode. */
fun imagePickerContractFor(mode: ImagePickerMode): ImagePickerContract = when (imagePickRequestFor(mode)) {
    ImagePickRequest.PhotoPicker -> ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA
    is ImagePickRequest.GetContent -> ImagePickerContract.GET_MULTIPLE_CONTENTS
    is ImagePickRequest.Documents -> ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS
}

/** #1128 — GET_CONTENT and document selection have no native ceiling; keep the first images in result order. */
fun <T> capPickedImages(uris: List<T>): List<T> = uris.take(MAX_IMAGES_PER_UPLOAD)

/**
 * Restores the pre-#988 per-contract limit after diagnostics have observed the raw callback. The
 * photo-picker callback is trusted as-is; only the two contracts without a native ceiling are
 * capped in application code.
 */
fun <T> pickedImagesForUpload(contract: ImagePickerContract, uris: List<T>): List<T> = when (contract) {
    ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA -> uris
    ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS,
    ImagePickerContract.GET_MULTIPLE_CONTENTS,
    -> capPickedImages(uris)
}

/**
 * #1128 — shared selector for every editor. PickMultipleVisualMedia, OpenMultipleDocuments and
 * GetMultipleContents launchers all stay registered across preference changes, so a pending result
 * still reaches its callback. URI access is used immediately for upload; it does not need a
 * persistable grant. Callbacks distinguish [ImagePickerEvent.Cancelled] from a successful
 * [ImagePickerEvent.Result], including an empty success; the caller owns diagnostics and uploads.
 */
@Composable
fun rememberEditorImagePicker(mode: ImagePickerMode, onEvent: (ImagePickerEvent) -> Unit): () -> Unit {
    val photoPicker = rememberLauncherForActivityResult(
        contract = ImagePickerResultContract(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_UPLOAD)),
    ) { result ->
        emitImagePickerResult(ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA, result.resultCode, result.uris, onEvent)
    }
    val documentPicker = rememberLauncherForActivityResult(
        contract = ImagePickerResultContract(ActivityResultContracts.OpenMultipleDocuments()),
    ) { result ->
        emitImagePickerResult(ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS, result.resultCode, result.uris, onEvent)
    }
    val getContent = rememberLauncherForActivityResult(
        contract = ImagePickerResultContract(ActivityResultContracts.GetMultipleContents()),
    ) { result ->
        emitImagePickerResult(ImagePickerContract.GET_MULTIPLE_CONTENTS, result.resultCode, result.uris, onEvent)
    }
    return {
        val request = imagePickRequestFor(mode)
        onEvent(ImagePickerEvent.Launched(mode = mode, contract = imagePickerContractFor(mode)))
        launchEditorImagePicker(mode, onEvent) {
            when (request) {
                ImagePickRequest.PhotoPicker -> photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
                is ImagePickRequest.GetContent -> getContent.launch(request.mimeType)
                is ImagePickRequest.Documents -> documentPicker.launch(request.mimeTypes.toTypedArray())
            }
        }
    }
}

/** Keeps AndroidX's intent, native fallbacks and URI parsing while retaining the activity result code. */
internal class ImagePickerResultContract<I>(
    private val delegate: ActivityResultContract<I, List<Uri>>,
) : ActivityResultContract<I, ImagePickerActivityResult>() {
    override fun createIntent(context: Context, input: I): Intent = delegate.createIntent(context, input)

    // A synchronous value is a successful selection; null still asks the registry to launch the activity.
    override fun getSynchronousResult(context: Context, input: I): SynchronousResult<ImagePickerActivityResult>? =
        delegate.getSynchronousResult(context, input)?.let {
            SynchronousResult(ImagePickerActivityResult(Activity.RESULT_OK, it.value))
        }

    override fun parseResult(resultCode: Int, intent: Intent?): ImagePickerActivityResult =
        ImagePickerActivityResult(resultCode, delegate.parseResult(resultCode, intent))
}

internal data class ImagePickerActivityResult(val resultCode: Int, val uris: List<Uri>)

/** Only the launch boundary may emit Unavailable; cancellation/result stays a different event. */
internal fun launchEditorImagePicker(mode: ImagePickerMode, onEvent: (ImagePickerEvent) -> Unit, launch: () -> Unit) {
    try {
        launch()
    } catch (_: ActivityNotFoundException) {
        onEvent(ImagePickerEvent.Unavailable(mode, imagePickerContractFor(mode)))
    }
}

/** Emits the Activity Result callback before any empty-result filtering in the editor layer. */
internal fun <T> emitImagePickerResult(
    contract: ImagePickerContract,
    resultCode: Int,
    uris: List<T>,
    onEvent: (ImagePickerEvent) -> Unit,
) {
    onEvent(
        if (resultCode == Activity.RESULT_OK) {
            ImagePickerEvent.Result(contract = contract, uris = uris.map { it.toString() })
        } else {
            ImagePickerEvent.Cancelled(contract = contract, resultCode = resultCode)
        },
    )
}
