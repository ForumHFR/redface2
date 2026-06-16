package fr.forumhfr.redface2.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
 * #452 — the opt-out must cut the NETWORK, not just the display. The preference is the OUTER flow
 * (`flatMapLatest`) : while the badge is disabled the upstream `observeUnreadMpCount` is NOT
 * collected, so its auth-flip fetch / foreground ticks / piggyback chain never run — flipping the
 * setting off tears the fetch chain down, flipping it on re-subscribes (and re-fetches). A
 * foreground tick or thread-read signal raised while disabled is inert : it only enqueues onto the
 * uncollected count flow, so no fetch happens.
 *
 * `WhileSubscribed` (not Eagerly) : the only consumer is the navigation bar — when nothing shows
 * it, nothing should hold the upstream fetch chain alive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MpUnreadBadgeViewModel @Inject constructor(
    private val messagesRepository: MessagesRepository,
    userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val badgeEnabled = userPreferencesRepository.observeMpUnreadBadge()
        .distinctUntilChanged()

    val unreadCount: StateFlow<Int?> =
        badgeEnabled
            .flatMapLatest { enabled ->
                if (enabled) {
                    messagesRepository.observeUnreadMpCount().map { count -> count?.takeIf { it > 0 } }
                } else {
                    // Disabled : emit nothing-to-show AND do not collect the network flow at all.
                    flowOf(null)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * `ON_START` hook (app brought back to the foreground) : MPs received while backgrounded
     * must surface without waiting for a tab visit. The FIRST start is skipped — the cold-start
     * fetch already runs when the auth state resolves, a tick there would only double the call.
     *
     * #452 — the tick is INERT while the badge is disabled : `requestUnreadRefresh` only enqueues
     * onto the count flow, which `flatMapLatest` above does not collect when the preference is off,
     * so no fetch runs. No extra gate needed here — the disabled state is enforced upstream by not
     * subscribing to the network flow at all.
     */
    fun onAppForegrounded() {
        if (firstStart) {
            firstStart = false
            return
        }
        messagesRepository.requestUnreadRefresh()
    }

    /**
     * #453 — a private-message conversation was opened/read. Optimistically decrements the badge
     * count so reading the LAST unread conversation clears the badge immediately, without waiting
     * for the next page-1 fetch. Inert while the badge is disabled (#452) for the same reason as
     * [onAppForegrounded] : the count flow is not collected, so the signal serves no collector.
     */
    fun onThreadRead(threadId: Int) {
        messagesRepository.markThreadRead(threadId)
    }

    private var firstStart = true

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
