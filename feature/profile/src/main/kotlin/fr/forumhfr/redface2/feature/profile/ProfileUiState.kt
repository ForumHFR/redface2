package fr.forumhfr.redface2.feature.profile

import fr.forumhfr.redface2.core.model.UserProfile

/**
 * Phase 2 finish (#208) — MVI state / intent / effect for the profile feature.
 *
 * Navigation is always userId-first: [pseudo] and [avatarUrl] may be pre-populated
 * from the topic page as display hints before the full profile loads. The canonical
 * key is always [userId].
 */
data class ProfileUiState(
    val userId: Int,
    /**
     * Hint populated immediately from the topic page tap site. Shown while [mode]
     * is [Mode.Loading] so the sheet / page renders a readable placeholder.
     */
    val pseudoHint: String,
    /**
     * Hint populated immediately from the topic page avatar. Shown as the avatar
     * placeholder image while [mode] is [Mode.Loading].
     */
    val avatarUrlHint: String?,
    val mode: Mode,
) {
    sealed interface Mode {
        data object Loading : Mode

        data class Loaded(
            val profile: UserProfile,
        ) : Mode

        /**
         * Review feedback I7: the ViewModel must not own the user-visible string.
         * It surfaces an [ErrorKind] (and optionally the originating [Throwable] for
         * diagnostics) and the UI maps it to a localised `stringResource(...)`. This
         * keeps `:feature:profile` layer-clean and i18n-ready.
         */
        data class Error(
            val kind: ErrorKind,
            val cause: Throwable? = null,
        ) : Mode
    }

    /** Classification of profile-load failures surfaced by the ViewModel to the UI. */
    enum class ErrorKind {
        /** Generic / unknown failure — UI shows the « load failed » string. */
        Unknown,
    }

    companion object {
        fun initial(userId: Int, pseudoHint: String, avatarUrlHint: String?): ProfileUiState =
            ProfileUiState(
                userId = userId,
                pseudoHint = pseudoHint,
                avatarUrlHint = avatarUrlHint,
                mode = Mode.Loading,
            )
    }
}

sealed interface ProfileIntent {
    data object Retry : ProfileIntent
}

/**
 * One-shot side effects produced by [ProfileViewModel].
 * Currently no effects are needed for the MVP — navigation to the full profile
 * page is handled by the bottom sheet's « Voir le profil complet » button callback
 * hoisted to `:app`.
 */
sealed interface ProfileEffect
