package fr.forumhfr.redface2.feature.auth

data class LoginUiState(
    val pseudo: String = "",
    val password: String = "",
    val mode: Mode = Mode.Idle,
) {
    sealed interface Mode {
        data object Idle : Mode

        data object Submitting : Mode

        /**
         * Error UI mode. [detail] carries the technical message attached to the underlying
         * `LoginError` (when any) — surfaced in the alpha as-is below the localized
         * description so contributors can self-diagnose without grepping logcat.
         */
        data class Error(val type: ErrorType, val detail: String? = null) : Mode

        data class Authenticated(val pseudo: String) : Mode
    }

    enum class ErrorType {
        InvalidCredentials,
        RateLimited,
        Network,
        Unknown,
    }
}

sealed interface LoginIntent {
    data class UpdatePseudo(val value: String) : LoginIntent

    data class UpdatePassword(val value: String) : LoginIntent

    data object Submit : LoginIntent

    data object DismissError : LoginIntent
}
