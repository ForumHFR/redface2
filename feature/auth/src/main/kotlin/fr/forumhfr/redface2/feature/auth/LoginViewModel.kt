package fr.forumhfr.redface2.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.LoginError
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun send(intent: LoginIntent) {
        when (intent) {
            is LoginIntent.UpdatePseudo -> _state.update { current ->
                current.copy(pseudo = intent.value, mode = clearErrorIfPresent(current.mode))
            }
            is LoginIntent.UpdatePassword -> _state.update { current ->
                current.copy(password = intent.value, mode = clearErrorIfPresent(current.mode))
            }
            LoginIntent.Submit -> submit()
            LoginIntent.DismissError -> _state.update { it.copy(mode = LoginUiState.Mode.Idle) }
        }
    }

    private fun clearErrorIfPresent(current: LoginUiState.Mode): LoginUiState.Mode =
        if (current is LoginUiState.Mode.Error) LoginUiState.Mode.Idle else current

    private fun submit() {
        val current = _state.value
        if (current.mode is LoginUiState.Mode.Submitting) return
        if (current.pseudo.isBlank() || current.password.isBlank()) return

        _state.update { it.copy(mode = LoginUiState.Mode.Submitting) }
        viewModelScope.launch {
            val result = authRepository.login(current.pseudo, current.password)
            _state.update { latest ->
                latest.copy(
                    mode = result.fold(
                        onSuccess = { authenticated ->
                            LoginUiState.Mode.Authenticated(authenticated.pseudo)
                        },
                        onFailure = { error ->
                            LoginUiState.Mode.Error(error.toErrorType(), error.toErrorDetail())
                        },
                    ),
                )
            }
        }
    }

    private fun Throwable.toErrorType(): LoginUiState.ErrorType = when (this) {
        is LoginError.InvalidCredentials -> LoginUiState.ErrorType.InvalidCredentials
        is LoginError.RateLimited -> LoginUiState.ErrorType.RateLimited
        is LoginError.Network -> LoginUiState.ErrorType.Network
        is LoginError.Unknown -> LoginUiState.ErrorType.Unknown
        else -> LoginUiState.ErrorType.Unknown
    }

    /**
     * Surface the technical error message in the alpha so contributors can read it
     * directly on screen instead of digging through logcat. We expose:
     * - LoginError.Unknown.detail as-is (carries the classify() diagnostic)
     * - LoginError.Network root cause class+message (e.g. "UnknownHostException: ...")
     * - other classes: just the simple class name as a hint
     */
    private fun Throwable.toErrorDetail(): String? = when (this) {
        is LoginError.Unknown -> detail
        is LoginError.Network -> "${cause.javaClass.simpleName}: ${cause.message ?: "I/O failure"}"
        is LoginError.InvalidCredentials, is LoginError.RateLimited -> null
        else -> "${this::class.simpleName}: ${message ?: "(no message)"}"
    }
}
