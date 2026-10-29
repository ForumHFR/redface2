package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.domain.smiley.HfrFavoriteSmileyRepository
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult

internal class FakeHfrFavoriteSmileyRepository : HfrFavoriteSmileyRepository {
    val added = mutableListOf<String>()
    val removed = mutableListOf<String>()
    var addResult: HfrFavoriteSmileyMutationResult = HfrFavoriteSmileyMutationResult.Success(emptyList())
    var removeResult: HfrFavoriteSmileyMutationResult = HfrFavoriteSmileyMutationResult.Success(emptyList())

    override suspend fun add(token: String): HfrFavoriteSmileyMutationResult {
        added += token
        return addResult
    }

    override suspend fun remove(token: String): HfrFavoriteSmileyMutationResult {
        removed += token
        return removeResult
    }
}
