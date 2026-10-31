package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollections
import fr.forumhfr.redface2.core.model.LocalFavoriteSmiley
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class FakePickerLocalCollections(owner: String? = null) : SmileyLocalCollections {
    val snapshot = MutableStateFlow(SmileyLocalCollectionSnapshot(owner))
    val calls = mutableListOf<Triple<String, String, String?>>()
    var addResult: suspend () -> LocalFavoriteSmileyMutationResult = { LocalFavoriteSmileyMutationResult.Added }
    var removeResult: suspend () -> LocalFavoriteSmileyMutationResult = { LocalFavoriteSmileyMutationResult.Updated }

    override fun observe() = snapshot

    override suspend fun add(owner: String, token: String, imageUrl: String): LocalFavoriteSmileyMutationResult {
        calls += Triple(owner, token, imageUrl)
        val result = addResult()
        if (result == LocalFavoriteSmileyMutationResult.Added) {
            snapshot.update { current ->
                if (current.owner == owner && current.favorites.none { it.token == token }) {
                    current.copy(favorites = current.favorites + LocalFavoriteSmiley(token, imageUrl, 1))
                } else {
                    current
                }
            }
        }
        return result
    }

    override suspend fun remove(owner: String, token: String): LocalFavoriteSmileyMutationResult {
        calls += Triple(owner, token, null)
        val result = removeResult()
        if (result == LocalFavoriteSmileyMutationResult.Updated) {
            snapshot.update { current ->
                if (current.owner == owner) current.copy(favorites = current.favorites.filterNot { it.token == token })
                else current
            }
        }
        return result
    }
}
