package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyMutationResult

/** Host operations receive the owner captured at the gesture, before any coroutine or repository wait. */
data class SmileyFavoriteMutations(
    val addHfrFavorite: suspend (owner: String, token: String) -> HfrFavoriteSmileyMutationResult,
    val removeHfrFavorite: suspend (owner: String, token: String) -> HfrFavoriteSmileyMutationResult,
)

internal fun HfrFavoriteSmileyMutationResult.confirmedList(): List<EditorSmiley>? = when (this) {
    is HfrFavoriteSmileyMutationResult.Success -> favorites
    is HfrFavoriteSmileyMutationResult.AlreadyPresent -> favorites
    is HfrFavoriteSmileyMutationResult.NotInFavorites -> favorites
    is HfrFavoriteSmileyMutationResult.Full -> favorites
    is HfrFavoriteSmileyMutationResult.VerificationFailed -> favorites
    else -> null
}

internal fun HfrFavoriteSmileyMutationResult.feedback(action: SmileyFavoriteAction): SmileyFavoriteFeedback? =
    when (this) {
        is HfrFavoriteSmileyMutationResult.Success -> when (action) {
            SmileyFavoriteAction.ADD -> SmileyFavoriteFeedback.Added
            SmileyFavoriteAction.REMOVE -> SmileyFavoriteFeedback.Removed
        }
        is HfrFavoriteSmileyMutationResult.AlreadyPresent -> SmileyFavoriteFeedback.AlreadyPresent
        is HfrFavoriteSmileyMutationResult.NotInFavorites -> SmileyFavoriteFeedback.NotInFavorites
        is HfrFavoriteSmileyMutationResult.Full -> SmileyFavoriteFeedback.Full(limit)
        HfrFavoriteSmileyMutationResult.NotFound, HfrFavoriteSmileyMutationResult.InvalidToken ->
            SmileyFavoriteFeedback.NotFound
        is HfrFavoriteSmileyMutationResult.VerificationFailed -> SmileyFavoriteFeedback.ListChanged
        HfrFavoriteSmileyMutationResult.NotAuthenticated -> SmileyFavoriteFeedback.NotAuthenticated
        HfrFavoriteSmileyMutationResult.AccountChanged -> null
        HfrFavoriteSmileyMutationResult.UnknownResponse -> SmileyFavoriteFeedback.UnknownResponse
        HfrFavoriteSmileyMutationResult.TransportFailure -> SmileyFavoriteFeedback.TransportFailure
    }

internal fun LocalFavoriteSmileyMutationResult.localFeedback(action: SmileyFavoriteAction): SmileyFavoriteFeedback =
    when (this) {
        LocalFavoriteSmileyMutationResult.LimitReached -> SmileyFavoriteFeedback.LocalLimitReached
        is LocalFavoriteSmileyMutationResult.Failure -> SmileyFavoriteFeedback.LocalStorageFailure
        else -> when (action) {
            SmileyFavoriteAction.ADD -> SmileyFavoriteFeedback.LocalAdded
            SmileyFavoriteAction.REMOVE -> SmileyFavoriteFeedback.LocalRemoved
        }
    }
