package fr.forumhfr.redface2.core.data.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response shape of `rehost.diberie.com/Host/UploadFiles` (#459). Parsed with the `@UploadJson`
 * profile (`ignoreUnknownKeys`), so server-side keys we do not model stay harmless.
 *
 * **Captured live** (2026-06-13) against the real host — see
 * `core/data/src/test/resources/fixtures/diberie_upload_response.json`. Two field types matter and
 * had been guessed wrong before the capture:
 * - `picID` is a JSON **number** (e.g. `521196`), NOT a quoted string. Modelling it as `String?`
 *   made the (non-lenient) parser throw on every real upload → [UploadException.Malformed]
 *   (« réponse illisible de l'hébergeur »). It is a [Long] here.
 * - `previewWidht` / `previewHeight` come as quoted **strings** (`"300"`), not numbers — they are
 *   not consumed, so they are simply left unmodelled and dropped by `ignoreUnknownKeys`.
 *
 * Picture URLs follow `Picture/Get/{f|r|t}/{picID}` (full / resized / thumbnail). The host's own
 * `picBB` BBCode points at the `/f/` (full) variant, which is what [DiberieProvider] surfaces.
 */
@Serializable
internal data class DiberieResponse(
    @SerialName("picID") val picId: Long? = null,
    @SerialName("picURL") val picUrl: String? = null,
    @SerialName("resizedURL") val resizedUrl: String? = null,
    @SerialName("thumbURL") val thumbUrl: String? = null,
    @SerialName("picBB") val picBb: String? = null,
    @SerialName("isGIF") val isGif: Boolean = false,
)
