package fr.forumhfr.redface2.core.domain.upload

/**
 * Resolves a picked image reference (an opaque `Uri` string handed over by the Android photo picker)
 * into the platform-free [ImageUpload] the upload providers consume (#459 PR2).
 *
 * The seam exists so the editor ViewModel stays platform-free and unit-testable : reading the bytes
 * needs a `ContentResolver` (`openInputStream` + `getType`), which lives in the Android layer
 * (`:core:data`). The ViewModel only knows this interface and is tested with a fake. The Screen owns
 * the picker, gets a `Uri`, and dispatches its `toString()` to the ViewModel ; the ViewModel calls
 * [read] to obtain the bytes before delegating to [UploadRepository.uploadWithCurrentProvider].
 */
interface ImageUploadReader {

    /**
     * Reads the content behind [uri] (a `Uri.toString()` from the photo picker) into an
     * [ImageUpload]. Throws [UploadException] on failure — typically [UploadException.Network] for an
     * I/O error (stream unreadable, content gone) so the ViewModel surfaces it through the same typed
     * error path as the upload itself. Runs its I/O off the main thread (the implementation hops to
     * the IO dispatcher, per the project rule that data sources own their dispatcher).
     */
    suspend fun read(uri: String): ImageUpload
}
