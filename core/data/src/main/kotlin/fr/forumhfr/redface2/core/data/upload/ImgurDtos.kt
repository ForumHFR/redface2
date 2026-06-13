package fr.forumhfr.redface2.core.data.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response envelope of `POST https://api.imgur.com/3/image` (#459). The imgur API wraps every
 * payload in `{ data, success, status }`; the upload result lives in [data]. Parsed with the
 * `@UploadJson` lenient profile (`ignoreUnknownKeys`) — imgur returns many fields we don't model.
 */
@Serializable
internal data class ImgurEnvelope(
    @SerialName("data") val data: ImgurData? = null,
    @SerialName("success") val success: Boolean = false,
    @SerialName("status") val status: Int = 0,
)

@Serializable
internal data class ImgurData(
    @SerialName("link") val link: String? = null,
    @SerialName("deletehash") val deleteHash: String? = null,
    @SerialName("type") val type: String? = null,
)
