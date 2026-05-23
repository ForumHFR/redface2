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
) {
    val canSave: Boolean
        get() = !isSaving
}

sealed interface SettingsError {
    data object InvalidProxy : SettingsError
}

sealed interface SettingsIntent {
    data class ProxyEnabledChanged(val enabled: Boolean) : SettingsIntent
    data class ProxyHostChanged(val host: String) : SettingsIntent
    data class ProxyPortChanged(val port: String) : SettingsIntent
    data class ProxyUsernameChanged(val username: String) : SettingsIntent
    data class ProxyPasswordChanged(val password: String) : SettingsIntent
    data object SaveProxyClicked : SettingsIntent
}
