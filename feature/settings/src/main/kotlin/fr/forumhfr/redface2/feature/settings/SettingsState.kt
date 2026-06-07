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
    // Alpha-only "Ignorer le cache topic" toggle (Phase 2 finish). Persisted in DataStore via
    // UserPreferencesRepository — the ViewModel hydrates this field from the persisted value
    // and writes back on user toggle. `isUpdatingIgnoreTopicCache` gates the switch while the
    // write is in flight so a rapid double-tap can't queue two DataStore edits.
    val ignoreTopicCache: Boolean = false,
    val isUpdatingIgnoreTopicCache: Boolean = false,
    val ignoreTopicCacheError: Boolean = false,
    /**
     * Internal startup-race guard. Set to `true` the moment the user flips the toggle locally,
     * so the (still-suspended) initial DataStore hydration coroutine in `init` cannot resume
     * later and overwrite the optimistic flip with a stale snapshot. Never surfaced in the UI.
     */
    val ignoreTopicCacheTouchedLocally: Boolean = false,
    // Drapeaux view preferences (#179 follow-up). Same optimistic-flip + startup-race-guard
    // machinery as ignoreTopicCache: the field is the displayed value, `isUpdating*` gates the
    // switch while DataStore writes, `*Error` surfaces a persist failure, and `*TouchedLocally`
    // forbids a late hydration from clobbering a fast user flip. Defaults match the DataStore
    // defaults (grouped on, hide-read off).
    val flagsGroupByCategory: Boolean = true,
    val isUpdatingFlagsGroupByCategory: Boolean = false,
    val flagsGroupByCategoryError: Boolean = false,
    val flagsGroupByCategoryTouchedLocally: Boolean = false,
    val flagsHideReadCategories: Boolean = false,
    val isUpdatingFlagsHideReadCategories: Boolean = false,
    val flagsHideReadCategoriesError: Boolean = false,
    val flagsHideReadCategoriesTouchedLocally: Boolean = false,
) {
    val canSave: Boolean
        get() = !isSaving

    val canClearTopicCache: Boolean
        get() = !isClearingTopicCache

    val canToggleIgnoreTopicCache: Boolean
        get() = !isUpdatingIgnoreTopicCache

    val canToggleFlagsGroupByCategory: Boolean
        get() = !isUpdatingFlagsGroupByCategory

    val canToggleFlagsHideReadCategories: Boolean
        get() = flagsGroupByCategory && !isUpdatingFlagsHideReadCategories
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

    // Alpha "Ignorer le cache topic" toggle. The boolean is the desired post-flip state; the
    // ViewModel applies it optimistically, then reverts on DataStore failure so the UI never
    // shows a value that doesn't match what's persisted.
    data class IgnoreTopicCacheChanged(val enabled: Boolean) : SettingsIntent

    // Drapeaux view preferences (#179 follow-up). Same optimistic-flip contract as
    // IgnoreTopicCacheChanged: the boolean is the desired post-flip state.
    data class FlagsGroupByCategoryChanged(val enabled: Boolean) : SettingsIntent
    data class FlagsHideReadCategoriesChanged(val enabled: Boolean) : SettingsIntent
}
