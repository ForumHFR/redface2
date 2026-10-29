package fr.forumhfr.redface2.core.domain.smiley

import fr.forumhfr.redface2.core.model.LocalFavoriteSmiley
import fr.forumhfr.redface2.core.model.SmileyUsageEntry
import kotlinx.coroutines.flow.Flow

/** Account switching and local storage shared by every editor host. No network operations. */
interface SmileyLocalCollections {
    /** Emits an empty snapshot at each owner switch, before reading that owner's collections. */
    fun observe(): Flow<SmileyLocalCollectionSnapshot>

    /** The caller captures [owner] at the gesture, before launching the mutation. */
    suspend fun add(owner: String, token: String, imageUrl: String): LocalFavoriteSmileyMutationResult

    suspend fun remove(owner: String, token: String): LocalFavoriteSmileyMutationResult
}

data class SmileyLocalCollectionSnapshot(
    val owner: String? = null,
    val favorites: List<LocalFavoriteSmiley> = emptyList(),
    val top: List<SmileyUsageEntry> = emptyList(),
)
