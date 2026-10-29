package fr.forumhfr.redface2.core.model

/** #1154 — account favorites only. No HTML, credentials or transport exceptions cross this boundary. */
sealed interface HfrFavoriteSmileyMutationResult {
    /** Server order and images, reloaded after a mutation (or freshly read when removal is already done). */
    data class Success(val favorites: List<EditorSmiley>) : HfrFavoriteSmileyMutationResult
    data class AlreadyPresent(val favorites: List<EditorSmiley>) : HfrFavoriteSmileyMutationResult
    data class Full(val limit: Int, val favorites: List<EditorSmiley>) : HfrFavoriteSmileyMutationResult
    data object NotFound : HfrFavoriteSmileyMutationResult
    data object InvalidToken : HfrFavoriteSmileyMutationResult
    /** Actual server list when the added token is absent or removal did not leave the expected ordered tokens. */
    data class VerificationFailed(val favorites: List<EditorSmiley>) : HfrFavoriteSmileyMutationResult
    data object NotAuthenticated : HfrFavoriteSmileyMutationResult
    data object AccountChanged : HfrFavoriteSmileyMutationResult
    data object UnknownResponse : HfrFavoriteSmileyMutationResult
    data object TransportFailure : HfrFavoriteSmileyMutationResult
}
