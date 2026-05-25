package fr.forumhfr.redface2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.cache.TopicCacheMaintenance
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val topicCacheMaintenance: TopicCacheMaintenance,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        // One-shot hydration of every persisted preference into `_state`. We deliberately
        // use `.first()` (point-in-time read) rather than a long-lived collect, so toggling
        // a preference from inside this screen does not race with itself via the observe
        // path. Each `.copy(...)` keeps the other fields intact — never replace the whole
        // state with a partial config, that would wipe the maintenance fields.
        viewModelScope.launch {
            val config = userPreferencesRepository.observeProxyConfig().first()
            _state.update { it.copyFromProxy(config) }
        }
        viewModelScope.launch {
            val ignore = userPreferencesRepository.observeIgnoreTopicCache().first()
            _state.update { current ->
                // Startup race guard: if the user already flipped the toggle (or a write is in
                // flight) while this hydration coroutine was suspended on `.first()`, do NOT
                // overwrite the local change with the stale snapshot we just collected. The
                // toggle is an alpha diagnostic — it must not lie about its own state.
                if (current.ignoreTopicCacheTouchedLocally || current.isUpdatingIgnoreTopicCache) {
                    current
                } else {
                    current.copy(ignoreTopicCache = ignore)
                }
            }
        }
    }

    fun submit(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.ProxyEnabledChanged ->
                _state.update { it.copy(proxyEnabled = intent.enabled, saved = false, error = null) }
            is SettingsIntent.ProxyHostChanged ->
                _state.update { it.copy(proxyHost = intent.host, saved = false, error = null) }
            is SettingsIntent.ProxyPortChanged ->
                _state.update { it.copy(proxyPort = intent.port.filter(Char::isDigit), saved = false, error = null) }
            is SettingsIntent.ProxyUsernameChanged ->
                _state.update { it.copy(proxyUsername = intent.username, saved = false, error = null) }
            is SettingsIntent.ProxyPasswordChanged ->
                _state.update { it.copy(proxyPassword = intent.password, saved = false, error = null) }
            SettingsIntent.SaveProxyClicked -> saveProxy()
            SettingsIntent.ClearTopicCacheClicked ->
                _state.update {
                    // Reset any previous result so the dialog opens on a clean slate; the user
                    // is about to re-confirm and shouldn't see a stale "succès" / "échec"
                    // label leaking into the new attempt.
                    it.copy(showClearTopicCacheConfirm = true, topicCacheClearResult = null)
                }
            SettingsIntent.ClearTopicCacheDismissed ->
                _state.update { it.copy(showClearTopicCacheConfirm = false) }
            SettingsIntent.ClearTopicCacheConfirmed -> clearTopicCache()
            is SettingsIntent.IgnoreTopicCacheChanged -> updateIgnoreTopicCache(intent.enabled)
        }
    }

    private fun saveProxy() {
        val snapshot = _state.value
        val port = snapshot.proxyPort.toIntOrNull()
        val invalidEnabledProxy =
            snapshot.proxyEnabled &&
                (snapshot.proxyHost.isBlank() || port !in ProxyConfig.MIN_PORT..ProxyConfig.MAX_PORT)
        if (invalidEnabledProxy) {
            _state.update { it.copy(error = SettingsError.InvalidProxy, saved = false) }
            return
        }
        _state.update { it.copy(isSaving = true, error = null, saved = false) }
        viewModelScope.launch {
            runCatching {
                userPreferencesRepository.saveProxyConfig(
                    ProxyConfig(
                        enabled = snapshot.proxyEnabled,
                        host = snapshot.proxyHost,
                        port = port,
                        username = snapshot.proxyUsername.ifBlank { null },
                        password = snapshot.proxyPassword.ifBlank { null },
                    ),
                )
            }.onSuccess {
                _state.update { it.copy(isSaving = false, saved = true, error = null) }
            }.onFailure {
                _state.update { it.copy(isSaving = false, saved = false, error = SettingsError.PersistFailed) }
            }
        }
    }

    private fun clearTopicCache() {
        // Close the confirmation dialog upfront so the user can't double-confirm, then flip
        // `isClearingTopicCache` so the button is disabled while Room runs the transaction.
        _state.update {
            it.copy(
                showClearTopicCacheConfirm = false,
                isClearingTopicCache = true,
                topicCacheClearResult = null,
            )
        }
        viewModelScope.launch {
            runCatching { topicCacheMaintenance.clearTopicCache() }
                .onSuccess {
                    _state.update {
                        it.copy(
                            isClearingTopicCache = false,
                            topicCacheClearResult = TopicCacheClearResult.Success,
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            isClearingTopicCache = false,
                            topicCacheClearResult = TopicCacheClearResult.Failure,
                        )
                    }
                }
        }
    }

    private fun updateIgnoreTopicCache(desired: Boolean) {
        val previous = _state.value.ignoreTopicCache
        // Optimistic flip — the UI reflects the intent immediately, the gate flag locks the
        // switch while DataStore is writing, and `ignoreTopicCacheTouchedLocally = true`
        // forbids the still-running startup hydration from overwriting this change with a
        // stale snapshot later. We keep the touched flag at `true` for the rest of the VM's
        // lifetime: even after a failure-revert the user has expressed an intent, so a late
        // hydration value would no longer be the source of truth.
        _state.update {
            it.copy(
                ignoreTopicCache = desired,
                isUpdatingIgnoreTopicCache = true,
                ignoreTopicCacheError = false,
                ignoreTopicCacheTouchedLocally = true,
            )
        }
        viewModelScope.launch {
            runCatching { userPreferencesRepository.setIgnoreTopicCache(desired) }
                .onSuccess {
                    // Re-affirm `ignoreTopicCache = desired` explicitly. Without this, a stale
                    // hydration that resumed *between* the optimistic flip and onSuccess could
                    // have left the field at a wrong value; reasserting here makes the final
                    // state self-consistent regardless of interleaving.
                    _state.update {
                        it.copy(
                            ignoreTopicCache = desired,
                            isUpdatingIgnoreTopicCache = false,
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            ignoreTopicCache = previous,
                            isUpdatingIgnoreTopicCache = false,
                            ignoreTopicCacheError = true,
                        )
                    }
                }
        }
    }

    private fun SettingsState.copyFromProxy(config: ProxyConfig): SettingsState = copy(
        proxyEnabled = config.enabled,
        proxyHost = config.host,
        proxyPort = config.port?.toString().orEmpty(),
        proxyUsername = config.username.orEmpty(),
        proxyPassword = config.password.orEmpty(),
    )
}
