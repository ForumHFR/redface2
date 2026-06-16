package fr.forumhfr.redface2.core.data.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Response envelope of `POST https://api.imgur.com/3/image` (#459). The imgur API wraps every
 * payload in `{ data, success, status }`; the upload result lives in [data]. Parsed with the
 * `@UploadJson` lenient profile (`ignoreUnknownKeys`) — imgur returns many fields we don't model.
 */
@Serializable
internal data class ImgurEnvelope(
    @SerialName("data") val data: ImgurData? = null,
    // Nullable so the three states are distinct (#474): `true` = success, `false` = explicit
    // application-level refusal (→ Server), `null` = field omitted (hand-built fixtures), in which
    // case the outcome is decided by the HTTP code and the presence of `data.link`.
    @SerialName("success") val success: Boolean? = null,
    @SerialName("status") val status: Int = 0,
)

@Serializable
internal data class ImgurData(
    @SerialName("link") val link: String? = null,
    @SerialName("deletehash") val deleteHash: String? = null,
    @SerialName("type") val type: String? = null,
    // On a `success:false` envelope imgur returns the failure reason here (#474). imgur uses BOTH
    // shapes depending on the error: a plain string OR a `{code,message,type,...}` object. Modelled
    // as a raw JsonElement so neither shape fails the whole envelope decode — a `String?` field
    // would throw on the object form, leaving `envelope == null` and masking a 2xx `success:false`
    // refusal as Malformed instead of the typed Server path (Codex review #474). Read it through
    // [errorMessage]. `null`/absent simply means « no detail to surface » and the status carries.
    @SerialName("error") val error: JsonElement? = null,
)

/**
 * Human-readable message extracted from [ImgurData.error], which imgur returns as either a plain
 * string or a `{code,message,...}` object (#474). Returns the string itself, the object's
 * `message`, or `null` when there is no usable detail — the HTTP/application status carries then.
 */
internal val ImgurData.errorMessage: String?
    get() = when (val raw = error) {
        is JsonPrimitive -> raw.contentOrNull
        is JsonObject -> (raw["message"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }
