package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollections
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeSmileyLocalCollections : SmileyLocalCollections {
    val snapshot = MutableStateFlow(SmileyLocalCollectionSnapshot())
    val added = mutableListOf<Triple<String, String, String>>()
    val removed = mutableListOf<Pair<String, String>>()

    override fun observe() = snapshot

    override suspend fun add(owner: String, token: String, imageUrl: String): LocalFavoriteSmileyMutationResult {
        added += Triple(owner, token, imageUrl)
        return LocalFavoriteSmileyMutationResult.Added
    }

    override suspend fun remove(owner: String, token: String): LocalFavoriteSmileyMutationResult {
        removed += owner to token
        return LocalFavoriteSmileyMutationResult.Updated
    }
}
