package fr.forumhfr.redface2.core.domain.upload

import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * A persisted trace of one previously uploaded image (#459), read by the « Mes images uploadées »
 * screen (PR3) and the deletion flow. Distinct from [UploadedImage] (the fresh upload result): a
 * record always carries the stable [picId] handle and the [uploadedAt] instant the trace was
 * written, so the list can be ordered and a deletion can be attempted later.
 */
data class UploadedImageRecord(
    val provider: UploadProviderId,
    /** Stable per-provider handle: `picID` (diberie) | `deletehash` (imgur). Identifies the row. */
    val picId: String,
    val imageUrl: String,
    val thumbnailUrl: String?,
    /** Deletion handle; `null` when the image cannot be deleted from the host anymore. */
    val deleteHandle: String?,
    val uploadedAt: Instant,
    val expiresAt: Instant?,
) {
    /** `true` when a deletion can be attempted (imgur: guaranteed; diberie: best-effort). */
    val canDelete: Boolean get() = deleteHandle != null
}

/**
 * Orchestrates the upload providers (#459): picks the host from the current preference, uploads,
 * persists a local trace, and exposes the history for the « Mes images » screen. The selected
 * [UploadProvider] is resolved per call from the preference, so flipping the host in Settings takes
 * effect on the next upload without re-wiring anything.
 */
interface UploadRepository {

    /**
     * Uploads [image] through the provider of the current preference, persists the trace for
     * [userId] (lowercased HFR pseudo), and returns the result. Throws [UploadException] on failure
     * (nothing is persisted then).
     */
    suspend fun uploadWithCurrentProvider(image: ImageUpload, userId: String): UploadedImage

    /** History of images uploaded by [userId], most recent first — for the « Mes images » screen. */
    fun observeUploads(userId: String): Flow<List<UploadedImageRecord>>

    /**
     * Deletes [record] on the host (guaranteed for imgur, best-effort for diberie) then evicts the
     * local trace for [userId] REGARDLESS of the host outcome (a dead row helps no one — the image
     * may linger on the host but the app can do nothing more). Returns the host's confirmation:
     * `true` when the host confirmed, `false` when best-effort / unconfirmable / no handle.
     */
    suspend fun delete(record: UploadedImageRecord, userId: String): Boolean
}
