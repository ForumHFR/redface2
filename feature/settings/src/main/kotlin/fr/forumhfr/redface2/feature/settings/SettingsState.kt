package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.preferences.ThemeMode

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
    // Image cache maintenance — « Vider le cache des images » (#314). DEDICATED trio,
    // strictly mirroring the topic-cache fields above: sharing them would make the two
    // Maintenance entries collide (a topic clear would flash its result under the image
    // button and vice versa).
    val showClearImageCacheConfirm: Boolean = false,
    val isClearingImageCache: Boolean = false,
    val imageCacheClearResult: ImageCacheClearResult? = null,
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
    // #309 — per-tab display override master switch. Same optimistic-flip machinery; when on, each
    // Drapeaux tab keeps its own view settings (tuned from the Drapeaux bottom sheet), the two
    // toggles above acting as the shared fallback. Default false (global view for every tab).
    val flagsPerTabOverride: Boolean = false,
    val isUpdatingFlagsPerTabOverride: Boolean = false,
    val flagsPerTabOverrideError: Boolean = false,
    val flagsPerTabOverrideTouchedLocally: Boolean = false,
    // Theme preferences (#286). Same optimistic-flip + startup-race-guard machinery as the flags
    // toggles: `themeMode`/`amoledEnabled` are the displayed values, `isUpdating*` gates the control
    // while DataStore writes, `*Error` surfaces a persist failure, and `*TouchedLocally` forbids a
    // late `init` hydration from clobbering a fast user change. Defaults match the DataStore defaults
    // (SYSTEM, amoled off).
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val isUpdatingThemeMode: Boolean = false,
    val themeModeError: Boolean = false,
    val themeModeTouchedLocally: Boolean = false,
    val amoledEnabled: Boolean = false,
    val isUpdatingAmoled: Boolean = false,
    val amoledError: Boolean = false,
    val amoledTouchedLocally: Boolean = false,
    // Topic reading preferences (build 89 follow-up). Same optimistic-flip + startup-race-guard
    // machinery: `topicTopBarAutoHide` is the displayed value, `isUpdating*` gates the switch while
    // DataStore writes, `*Error` surfaces a persist failure, `*TouchedLocally` forbids a late `init`
    // hydration from clobbering a fast user flip. Default false (top bar pinned).
    val topicTopBarAutoHide: Boolean = false,
    val isUpdatingTopicTopBarAutoHide: Boolean = false,
    val topicTopBarAutoHideError: Boolean = false,
    val topicTopBarAutoHideTouchedLocally: Boolean = false,
) {
    val canSave: Boolean
        get() = !isSaving

    val canClearTopicCache: Boolean
        get() = !isClearingTopicCache

    val canClearImageCache: Boolean
        get() = !isClearingImageCache

    val canToggleIgnoreTopicCache: Boolean
        get() = !isUpdatingIgnoreTopicCache

    val canToggleFlagsGroupByCategory: Boolean
        get() = !isUpdatingFlagsGroupByCategory

    // The global hide-read toggle is meaningful when the global grouped view is on, OR when the
    // per-tab override is on (it still serves as the fallback for a tab that is grouped per-type but
    // has no per-type hide-read value). #309 Codex review.
    val canToggleFlagsHideReadCategories: Boolean
        get() = (flagsGroupByCategory || flagsPerTabOverride) && !isUpdatingFlagsHideReadCategories

    val canToggleFlagsPerTabOverride: Boolean
        get() = !isUpdatingFlagsPerTabOverride

    // #286 — theme controls are gated only by their own in-flight write.
    val canChangeThemeMode: Boolean
        get() = !isUpdatingThemeMode

    val canToggleAmoled: Boolean
        get() = !isUpdatingAmoled

    // Build 89 follow-up — the topic top-bar auto-hide toggle is gated only by its own write.
    val canToggleTopicTopBarAutoHide: Boolean
        get() = !isUpdatingTopicTopBarAutoHide
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

/**
 * Outcome of the latest « Vider le cache des images » click (#314). Dedicated type for
 * the same reason [TopicCacheClearResult] is separate from the proxy fields: the two
 * Maintenance actions are unrelated flows and one must never dismiss or repaint the
 * other's still-visible feedback.
 */
sealed interface ImageCacheClearResult {
    data object Success : ImageCacheClearResult
    data object Failure : ImageCacheClearResult
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

    // Image cache maintenance flow (#314) — same three-intent confirm shape as the topic
    // cache above, operating on the dedicated image-cache state fields.
    data object ClearImageCacheClicked : SettingsIntent
    data object ClearImageCacheConfirmed : SettingsIntent
    data object ClearImageCacheDismissed : SettingsIntent

    // Alpha "Ignorer le cache topic" toggle. The boolean is the desired post-flip state; the
    // ViewModel applies it optimistically, then reverts on DataStore failure so the UI never
    // shows a value that doesn't match what's persisted.
    data class IgnoreTopicCacheChanged(val enabled: Boolean) : SettingsIntent

    // Drapeaux view preferences (#179 follow-up). Same optimistic-flip contract as
    // IgnoreTopicCacheChanged: the boolean is the desired post-flip state.
    data class FlagsGroupByCategoryChanged(val enabled: Boolean) : SettingsIntent
    data class FlagsHideReadCategoriesChanged(val enabled: Boolean) : SettingsIntent

    // #309 — per-tab display override master switch.
    data class FlagsPerTabOverrideChanged(val enabled: Boolean) : SettingsIntent

    // #286 — theme preferences. `mode` is the desired selection, `enabled` the desired AMOLED state;
    // both applied optimistically with revert-on-failure, like the flags toggles.
    data class ThemeModeChanged(val mode: ThemeMode) : SettingsIntent
    data class AmoledEnabledChanged(val enabled: Boolean) : SettingsIntent

    // Build 89 follow-up — topic top-bar auto-hide toggle. Optimistic-flip contract, like the
    // flags toggles: the boolean is the desired post-flip state.
    data class TopicTopBarAutoHideChanged(val enabled: Boolean) : SettingsIntent
}
