package fr.forumhfr.redface2.core.data.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response shape of `rehost.diberie.com/Host/UploadFiles` (#459). Lenient: parsed with the
 * `@UploadJson` profile (`ignoreUnknownKeys`), so server-side keys we do not model stay harmless.
 *
 * Contract NOT verified against a live capture in this environment — the field set is taken from the
 * MesDiscussions / diberie rehost host documentation and the issue #459 description; the user
 * confirms it end-to-end with `dib91`. The picture URLs follow `Picture/Get/{f|r|t}/{picID}`
 * (full / resized / thumbnail).
 */
@Serializable
internal data class DiberieResponse(
    @SerialName("picID") val picId: String? = null,
    @SerialName("picURL") val picUrl: String? = null,
    @SerialName("resizedURL") val resizedUrl: String? = null,
    @SerialName("thumbURL") val thumbUrl: String? = null,
    @SerialName("picBB") val picBb: String? = null,
    @SerialName("isGIF") val isGif: Boolean = false,
    // Server-side typo (`Widht`) kept verbatim: renaming the @SerialName would silently drop the
    // field. Not consumed in v1 — captured only so the lenient parse stays faithful to the wire.
    @SerialName("previewWidht") val previewWidth: Int? = null,
    @SerialName("previewHeight") val previewHeight: Int? = null,
    // Pipe-delimited multi-file result; not exposed in v1 (one file at a time on the UI side).
    @SerialName("multipleResults") val multipleResults: String? = null,
)
