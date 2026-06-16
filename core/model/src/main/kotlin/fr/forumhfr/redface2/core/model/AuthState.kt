package fr.forumhfr.redface2.core.model

sealed interface AuthState {
    data object Anonymous : AuthState

    /**
     * @param pseudo the connected user's display pseudo (decoded from the `md_user` cookie).
     * @param userId the connected user's numeric HFR id (from the `md_id` cookie), or `null`
     *   when HFR did not set it (older sessions / partial cookie set). Used to fetch the
     *   user's public profile — e.g. the avatar shown in the top-bar account badge (#479) —
     *   keyed by `/hfr/profil-{userId}.htm`. Display-only: the auth verdict itself never
     *   depends on it (only `md_user` proves the session, cf. `AuthRemoteDataSource.classify`).
     */
    data class Authenticated(
        val pseudo: String,
        val userId: Int? = null,
    ) : AuthState
}
