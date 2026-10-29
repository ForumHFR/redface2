package fr.forumhfr.redface2.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Account-free, serializable local document. [favorites] carries the display order; all dates are
 * epoch millis. Required fields prevent a malformed document from being decoded as an empty store.
 * Tombstones are retained unless removal needs space under the document byte limit or the owner clears them.
 * General collection belongs to a future sync design.
 */
@Serializable
data class LocalFavoriteSmileySnapshot(
    @SerialName("version") val version: Int,
    @SerialName("favorites") val favorites: List<LocalFavoriteSmiley>,
    @SerialName("tombstones") val tombstones: List<Tombstone>,
    @SerialName("orderUpdatedAt") val orderUpdatedAt: Long,
) {
    @Serializable
    data class Tombstone(
        @SerialName("token") val token: String,
        @SerialName("removedAt") val removedAt: Long,
    )

    companion object {
        const val CURRENT_VERSION = 1
    }
}
