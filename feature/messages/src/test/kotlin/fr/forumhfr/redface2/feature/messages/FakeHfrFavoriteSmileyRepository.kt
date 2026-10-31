package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.smiley.HfrFavoriteSmileyRepository
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult

internal class FakeHfrFavoriteSmileyRepository : HfrFavoriteSmileyRepository {
    val owners = mutableListOf<String>()
    val added = mutableListOf<String>()
    val removed = mutableListOf<String>()
    var addResult: HfrFavoriteSmileyMutationResult = HfrFavoriteSmileyMutationResult.Success(emptyList())
    var removeResult: HfrFavoriteSmileyMutationResult = HfrFavoriteSmileyMutationResult.Success(emptyList())

    override suspend fun add(owner: String, token: String): HfrFavoriteSmileyMutationResult {
        owners += owner
        added += token
        return addResult
    }

    override suspend fun remove(owner: String, token: String): HfrFavoriteSmileyMutationResult {
        owners += owner
        removed += token
        return removeResult
    }
}
