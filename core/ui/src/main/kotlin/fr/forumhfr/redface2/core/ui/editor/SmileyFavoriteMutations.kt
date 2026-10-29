package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult

/** Host-supplied operations; the picker reconciles the returned list without reloading the editor form. */
data class SmileyFavoriteMutations(
    val addHfrFavorite: suspend (String) -> HfrFavoriteSmileyMutationResult,
    val removeHfrFavorite: suspend (String) -> HfrFavoriteSmileyMutationResult,
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
