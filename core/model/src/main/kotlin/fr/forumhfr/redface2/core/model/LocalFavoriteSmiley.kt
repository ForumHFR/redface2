package fr.forumhfr.redface2.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A local favorite, identified by its exact token. [addedAt] is an epoch-millis timestamp. */
@Serializable
data class LocalFavoriteSmiley(
    @SerialName("token") val token: String,
    @SerialName("imageUrl") val imageUrl: String? = null,
    @SerialName("addedAt") val addedAt: Long,
)
