package fr.forumhfr.redface2.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Device-local usage of an exact token. [lastUsedAt] is an epoch-millis timestamp. */
@Serializable
data class SmileyUsageEntry(
    @SerialName("token") val token: String,
    @SerialName("count") val count: Long,
    @SerialName("lastUsedAt") val lastUsedAt: Long,
)
