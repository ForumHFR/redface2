package fr.forumhfr.redface2.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * #313 — unread-MP badge on the « Messages » destination of the navigation bar.
 *
 * [unreadCount] is `null` whenever the badge must NOT show : anonymous session, fetch failure,
 * count not resolved yet, or the preference turned off. `0` is mapped to `null` too — an « empty »
 * badge would only add noise. The count itself comes from `MessagesRepository.observeUnreadMpCount`
 * (page-1 inbox proxy, cf. its KDoc) and refreshes via three channels : the auth flip (built-in),
 * the page-1 inbox piggyback (free, fires when the user opens the Messages tab or comes back from
 * a conversation), and the explicit [onAppForegrounded] tick below.
 *
 * `WhileSubscribed` (not Eagerly) : the only consumer is the navigation bar — when nothing shows
 * it, nothing should hold the upstream fetch chain alive.
 */
@HiltViewModel
class MpUnreadBadgeViewModel @Inject constructor(
    private val messagesRepository: MessagesRepository,
    userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val unreadCount: StateFlow<Int?> =
        combine(
            userPreferencesRepository.observeMpUnreadBadge(),
            messagesRepository.observeUnreadMpCount(),
        ) { enabled, count ->
            count?.takeIf { enabled && it > 0 }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * `ON_START` hook (app brought back to the foreground) : MPs received while backgrounded
     * must surface without waiting for a tab visit. The FIRST start is skipped — the cold-start
     * fetch already runs when the auth state resolves, a tick there would only double the call.
     */
    fun onAppForegrounded() {
        if (firstStart) {
            firstStart = false
            return
        }
        messagesRepository.requestUnreadRefresh()
    }

    private var firstStart = true

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
