package fr.forumhfr.redface2.core.domain.media

/**
 * #831 — saves a post image (the « Enregistrer l'image » entry of the image contextual menu) into
 * the device's shared image collection.
 *
 * The seam exists so `:feature:topic` stays platform-free and Konsist-compliant: the write needs
 * `MediaStore` + the Coil disk cache + an OkHttp fallback, all of which live in the Android layer
 * (`:core:data`, cf. `AndroidPostImageSaver`). The ViewModel only knows this interface and is
 * tested with a fake — same architecture as [fr.forumhfr.redface2.core.domain.upload.ImageUploadReader].
 */
interface PostImageSaver {

    /**
     * Persists the image behind [url] (an http(s) post-image URL) into the shared Pictures
     * collection, preserving the ORIGINAL bytes (an animated GIF stays animated — no re-encode of
     * a decoded bitmap). Returns the [SavedPostImage] describing the created entry. Throws
     * [ImageSaveException] on failure. Runs its I/O off the main thread (the implementation hops
     * to the IO dispatcher, per the project rule that data sources own their dispatcher).
     */
    suspend fun save(url: String): SavedPostImage
}

/** #831 — the MediaStore entry created by [PostImageSaver.save]. */
data class SavedPostImage(
    /** Final display name of the saved file (sanitized URL-derived base + sniffed extension). */
    val displayName: String,
)

/**
 * Typed failure surface of a post-image save (#831), modelled on
 * [fr.forumhfr.redface2.core.domain.upload.UploadException] so the UI renders an actionable
 * message (download vs. storage vs. size) instead of a generic « save failed ».
 */
sealed class ImageSaveException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The image bytes could not be obtained (disk-cache miss AND the network re-fetch failed). */
    class Fetch(cause: Throwable? = null) : ImageSaveException("Could not fetch image bytes", cause)

    /** The MediaStore insert/write failed (row insert refused, stream unwritable). */
    class Storage(cause: Throwable? = null) : ImageSaveException("Could not write image to MediaStore", cause)

    /** The image exceeds the anti-OOM ceiling ([maxBytes] is the cap that was hit). */
    class TooLarge(val maxBytes: Long) : ImageSaveException("Image exceeds $maxBytes bytes")
}
