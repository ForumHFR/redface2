package fr.forumhfr.redface2.feature.topic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.error.classifyHfrError
import fr.forumhfr.redface2.core.domain.write.ModerationRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.write.ModerationAlertState
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** #1287 — reads modo.php before showing the sheet, preserving the current topic page and its HFR flag. */
@HiltViewModel
class ModerationAlertLinkViewModel @Inject constructor(
    private val moderationRepository: ModerationRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<ModerationAlertLinkState>(ModerationAlertLinkState.Idle)
    val state = _state.asStateFlow()
    private var loadJob: Job? = null

    fun onIntent(intent: ModerationAlertLinkIntent) {
        when (intent) {
            is ModerationAlertLinkIntent.Open -> open(intent.target)
            ModerationAlertLinkIntent.Retry -> (_state.value as? ModerationAlertLinkState.Error)?.let {
                open(it.target, keepSheetOpen = true)
            }
            ModerationAlertLinkIntent.Dismiss -> {
                cancelLoad()
                _state.value = ModerationAlertLinkState.Idle
            }
            ModerationAlertLinkIntent.ViewPost -> _state.value.target?.let { target ->
                cancelLoad()
                _state.value = ModerationAlertLinkState.NavigateToPost(target, withAlertSheet = false)
            }
        }
    }

    private fun open(target: ModerationAlertLinkTarget, keepSheetOpen: Boolean = false) {
        val current = _state.value
        // The job also observes account changes after loading; its activity is not a double-tap guard.
        if (current is ModerationAlertLinkState.Loading && current.target == target) return
        cancelLoad()
        _state.value = ModerationAlertLinkState.Loading(target, keepSheetOpen)
        loadJob = viewModelScope.launch {
            // Keep observing while the sheet is open: an account change invalidates the previous result.
            authRepository.observeAuthState().distinctUntilChanged().collectLatest { auth ->
                val keepOpen = when (val previous = _state.value) {
                    is ModerationAlertLinkState.Loading -> previous.keepSheetOpen
                    is ModerationAlertLinkState.Info, is ModerationAlertLinkState.SignInRequired,
                    is ModerationAlertLinkState.Error -> true
                    else -> false
                }
                _state.value = ModerationAlertLinkState.Loading(target, keepOpen)
                val result = if (auth is AuthState.Authenticated) {
                    loadAlert(target)
                } else {
                    ModerationAlertLinkState.SignInRequired(target)
                }
                // Also rejects a repository response that completed in a NonCancellable section.
                currentCoroutineContext().ensureActive()
                _state.value = result
            }
        }
    }

    private suspend fun loadAlert(target: ModerationAlertLinkTarget): ModerationAlertLinkState = try {
        moderationRepository.loadAlert(target.cat, target.post, target.numreponse, target.page).toLinkState(target)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SessionExpiredException) {
        ModerationAlertLinkState.SignInRequired(target)
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        ModerationAlertLinkState.Error(target, classifyHfrError(error))
    }

    private fun cancelLoad() {
        loadJob?.cancel()
        loadJob = null
    }
}

private fun ModerationAlertState.toLinkState(
    target: ModerationAlertLinkTarget,
): ModerationAlertLinkState = when (this) {
    is ModerationAlertState.Form, is ModerationAlertState.JoinPrompt ->
        ModerationAlertLinkState.NavigateToPost(target, withAlertSheet = true)
    is ModerationAlertState.PendingMine -> ModerationAlertLinkState.Info(target, message)
    is ModerationAlertState.PendingJoined -> ModerationAlertLinkState.Info(target, message)
    is ModerationAlertState.TreatedMine -> ModerationAlertLinkState.Info(target, message, treatedAt)
    is ModerationAlertState.TreatedJoined -> ModerationAlertLinkState.Info(target, message, treatedAt)
    is ModerationAlertState.Unknown -> ModerationAlertLinkState.Info(target, excerpt)
}
