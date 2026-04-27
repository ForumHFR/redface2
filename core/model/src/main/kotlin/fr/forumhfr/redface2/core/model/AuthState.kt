package fr.forumhfr.redface2.core.model

sealed interface AuthState {
    data object Anonymous : AuthState

    data class Authenticated(val pseudo: String) : AuthState
}
