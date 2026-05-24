package fr.forumhfr.redface2.feature.settings

data class SettingsState(
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val error: SettingsError? = null,
    // Topic cache maintenance — alpha-only "Vider le cache des topics" action. Each
    // value is owned by `SettingsViewModel` and reset to false/null when the user
    // dismisses the dialog or after a result is acknowledged by a new click.
    val showClearTopicCacheConfirm: Boolean = false,
    val isClearingTopicCache: Boolean = false,
    val topicCacheClearResult: TopicCacheClearResult? = null,
) {
    val canSave: Boolean
        get() = !isSaving

    val canClearTopicCache: Boolean
        get() = !isClearingTopicCache
}

sealed interface SettingsError {
    data object InvalidProxy : SettingsError
    data object PersistFailed : SettingsError
}

/**
 * Outcome of the latest "Vider le cache des topics" click. Surfaced inline in the
 * Maintenance card (success message OR error message); we do NOT mix it with the proxy
 * `saved` / `error` fields since the two domains are unrelated and a proxy save must
 * not silently dismiss a still-visible topic cache error.
 */
sealed interface TopicCacheClearResult {
    data object Success : TopicCacheClearResult
    data object Failure : TopicCacheClearResult
}

sealed interface SettingsIntent {
    data class ProxyEnabledChanged(val enabled: Boolean) : SettingsIntent
    data class ProxyHostChanged(val host: String) : SettingsIntent
    data class ProxyPortChanged(val port: String) : SettingsIntent
    data class ProxyUsernameChanged(val username: String) : SettingsIntent
    data class ProxyPasswordChanged(val password: String) : SettingsIntent
    data object SaveProxyClicked : SettingsIntent

    // Topic cache maintenance flow — three intents so the screen can confirm before any
    // destructive action and the ViewModel stays the single source of truth for
    // `showClearTopicCacheConfirm` / `isClearingTopicCache`.
    data object ClearTopicCacheClicked : SettingsIntent
    data object ClearTopicCacheConfirmed : SettingsIntent
    data object ClearTopicCacheDismissed : SettingsIntent
}
