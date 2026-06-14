package fr.forumhfr.redface2.core.domain.upload

import java.time.Instant

/**
 * Stable identity of an image host (#459). The enum [name] is serialised verbatim into DataStore
 * (selected-provider preference) and Room (the `uploaded_images.provider` column) — renaming an
 * entry is a schema-affecting change and would need a migration plus a defensive read. `reho.st` /
 * `super-h` are reserved for a post-v1 iteration (see `docs/specs/extensions.md`).
 */
enum class UploadProviderId(
    /**
     * Whether the host CONFIRMS a server-side deletion. imgur returns an authenticated delete
     * confirmation; diberie's delete is best-effort (no server-side authorisation is confirmed),
     * so the UI must not promise the image is gone from the host. Drives the « Mes images » delete
     * confirmation wording (Codex beta review). Does not affect the serialised [name].
     */
    val confirmsHostDeletion: Boolean,
) {
    DIBERIE(confirmsHostDeletion = false),
    IMGUR(confirmsHostDeletion = true),
}

/**
 * Raw bytes to upload, already resolved from the picked `Uri` by the Android layer (via
 * `ContentResolver`) so the domain stays platform-free and unit-testable without Android.
 */
data class ImageUpload(
    val bytes: ByteArray,
    /** MIME type, e.g. `image/jpeg` — read from `ContentResolver.getType`. */
    val mimeType: String,
    /** Original file name when known, otherwise `null` (the providers fall back to a constant). */
    val displayName: String?,
) {
    // `bytes` is a ByteArray, so the generated equals/hashCode would compare by reference. Override
    // both to compare by content — two ImageUpload instances built from the same picked file must be
    // equal (tests, dedup). detekt's ArrayPrimitive/EqualsHashCode would otherwise flag the array.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageUpload) return false
        return bytes.contentEquals(other.bytes) &&
            mimeType == other.mimeType &&
            displayName == other.displayName
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + (displayName?.hashCode() ?: 0)
        return result
    }
}

/**
 * Result of a successful upload. [deleteHandle] is the ONLY handle for a later deletion:
 * - imgur: the `deletehash` — deletion is GUARANTEED via `DELETE /3/image/{deletehash}`.
 * - diberie: the `picID` — deletion is BEST-EFFORT (server-side authorisation is not confirmed,
 *   cf. issue #459 NB) and may silently no-op.
 * A `null` handle means no deletion is possible for that image.
 */
data class UploadedImage(
    val provider: UploadProviderId,
    /** Full-resolution URL inserted into the `[img]...[/img]` BBCode token. */
    val imageUrl: String,
    /** Thumbnail URL when the host exposes one (diberie `.../Get/t/{id}`), otherwise `null`. */
    val thumbnailUrl: String?,
    /**
     * A REDUCED (but not thumbnail-tiny) URL when the host exposes one — diberie `.../Get/r/{id}`,
     * ~300px. Used by the editor's "vignette cliquable" insert mode; `null` when the host has no
     * such variant (imgur), so the editor falls back to [imageUrl].
     */
    val resizedUrl: String?,
    /** `deletehash` (imgur) | `picID` (diberie) | `null` when no deletion handle is available. */
    val deleteHandle: String?,
    /** Expiry instant when the host advertises one; `null` means no known expiration. */
    val expiresAt: Instant?,
)

/**
 * An image host that accepts a binary upload. One implementation per provider, selected at runtime
 * by the [UploadProviderId] preference (cf. `UserPreferencesRepository.observeUploadProvider`).
 */
interface UploadProvider {
    val id: UploadProviderId

    /** Uploads the binary. Throws [UploadException] (typed) on a network / limit / parse failure. */
    suspend fun upload(image: ImageUpload): UploadedImage

    /**
     * Best-effort or guaranteed deletion depending on the provider. Returns `true` when the host
     * confirmed the deletion, `false` when it is not confirmable (diberie) or the handle is absent.
     * Never throws for a plain failure — a failed deletion is reported as `false`, not as an
     * exception (the local trace can still be evicted).
     */
    suspend fun delete(deleteHandle: String): Boolean
}

/**
 * Separate interface (cf. `docs/specs/extensions.md`): a provider that only rehosts an existing URL
 * (URL in, URL out) rather than uploading a local binary. No v1 implementation — declared so that
 * `reho.st` / `super-h` can plug in later without refactoring [UploadProvider].
 */
interface RehostProvider {
    val id: UploadProviderId

    suspend fun rehost(sourceUrl: String): UploadedImage
}
