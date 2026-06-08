package fr.forumhfr.redface2.core.domain.preferences

import fr.forumhfr.redface2.core.model.FlagType
import kotlinx.coroutines.flow.Flow

interface UserPreferencesRepository {
    fun observeProxyConfig(): Flow<ProxyConfig>

    suspend fun saveProxyConfig(config: ProxyConfig)

    /**
     * Synchronous bridge used only while building the process-wide OkHttp client.
     * Changing the proxy in the UI can require an app restart in this MVP.
     */
    fun readProxyConfigForNetworkBootstrap(): ProxyConfig

    /**
     * Alpha-only "Ignorer le cache topic" toggle (Phase 2 finish — dogfood loop).
     *
     * When `true`, [fr.forumhfr.redface2.core.domain.topic.TopicRepository.observeTopicPage]
     * skips the Room cache read and goes straight to the network (then persists the result so
     * the cache stays coherent with the current parser), and `prefetch()` becomes a no-op.
     * Default `false` — production behaviour is unchanged unless the user flips the switch.
     *
     * Scope is intentionally narrow: only the topic Room cache (`posts` + `topic_pages`) is
     * bypassed. Flags, session cookies, proxy preferences are untouched.
     */
    fun observeIgnoreTopicCache(): Flow<Boolean>

    /**
     * Persists the alpha "Ignorer le cache topic" toggle. The default `false` stays in effect
     * until the first call. Writes are dispatched on the IO dispatcher inside the DataStore
     * implementation; callers should not wrap this in another `withContext(ioDispatcher)`.
     */
    suspend fun setIgnoreTopicCache(enabled: Boolean)

    /**
     * Drapeaux screen layout (#179 follow-up): `true` (default) groups the flags by forum
     * category with a sticky band per category; `false` renders the legacy flat list ordered
     * by last reply (the pre-#179 behaviour the user must always be able to fall back to).
     * Observed by the Flags screen so flipping it from Settings re-renders without a refetch.
     */
    fun observeFlagsGroupByCategory(): Flow<Boolean>

    /** Persists [observeFlagsGroupByCategory]. Default `true` until the first call. */
    suspend fun setFlagsGroupByCategory(enabled: Boolean)

    /**
     * Drapeaux category filter (#179 follow-up): when `true`, categories that have no UNREAD
     * flag are hidden from the grouped view (along with empty ones). Default `false` = HFR web
     * parity (every category band shown). Only meaningful in the grouped view. The cyan « +lus »
     * toggle takes precedence: when the user opts to show already-read participated topics, their
     * categories stay visible so this filter never makes them unreachable.
     */
    fun observeFlagsHideReadCategories(): Flow<Boolean>

    /** Persists [observeFlagsHideReadCategories]. Default `false` until the first call. */
    suspend fun setFlagsHideReadCategories(enabled: Boolean)

    /**
     * Per-tab display override master switch (#309): when `true`, each Drapeaux tab
     * ([FlagType.CYAN]/[FlagType.RED]/[FlagType.FAVORITE]) resolves its own stored view settings
     * (falling back to the global toggles for anything that tab never customised); when `false`
     * (default), every tab shares the single global pair. Observed so flipping it from the bottom
     * sheet or Settings re-renders the list without a refetch.
     */
    fun observeFlagsPerTabOverride(): Flow<Boolean>

    /** Persists [observeFlagsPerTabOverride]. Default `false` until the first call. */
    suspend fun setFlagsPerTabOverride(enabled: Boolean)

    /**
     * Resolved Drapeaux view settings for [type], combining the #309 layout toggles and the #317
     * unread filter:
     * - layout ([FlagsViewSettings.groupByCategory] / [FlagsViewSettings.hideReadCategories]),
     *   honouring [observeFlagsPerTabOverride]: override off → the global
     *   [observeFlagsGroupByCategory] / [observeFlagsHideReadCategories]; override on → this
     *   [type]'s stored values, each falling back to the matching global value when unset.
     * - [FlagsViewSettings.unreadOnly] (#317): ALWAYS per-type, with a type-aware default
     *   (CYAN → true, RED/FAVORITE → false) until [setFlagsUnreadOnlyForType] is called.
     *
     * This is the single source the Flags screen list rendering reads; the global layout observers
     * stay the editable defaults (and the per-tab fallback) the Settings mirror writes.
     */
    fun observeFlagsViewSettings(type: FlagType): Flow<FlagsViewSettings>

    /**
     * Persists the per-tab « grouper par catégorie » value for [type] (#309). Only consulted by
     * [observeFlagsViewSettings] when [observeFlagsPerTabOverride] is `true`; until set, that tab
     * falls back to the global [setFlagsGroupByCategory] value.
     */
    suspend fun setFlagsGroupByCategoryForType(type: FlagType, enabled: Boolean)

    /**
     * Persists the per-tab « masquer les catégories sans non-lu » value for [type] (#309). Only
     * consulted by [observeFlagsViewSettings] when [observeFlagsPerTabOverride] is `true`; until
     * set, that tab falls back to the global [setFlagsHideReadCategories] value.
     */
    suspend fun setFlagsHideReadCategoriesForType(type: FlagType, enabled: Boolean)

    /**
     * Persists the « non-lus uniquement » value for [type] (#317). Unlike the layout toggles this
     * is ALWAYS per-type (no global key, not subject to [observeFlagsPerTabOverride]). Until set,
     * [observeFlagsViewSettings] applies a type-aware default: `true` for [FlagType.CYAN] (the
     * actionable « Mes sujets » subset), `false` for [FlagType.RED] / [FlagType.FAVORITE].
     */
    suspend fun setFlagsUnreadOnlyForType(type: FlagType, enabled: Boolean)

    /**
     * App theme selection (#286): [ThemeMode.SYSTEM] (default) follows the OS dark-mode setting;
     * [ThemeMode.LIGHT] / [ThemeMode.DARK] force the app theme regardless of the OS. Observed at the
     * app root ([fr.forumhfr.redface2.navigation.RedfaceApp]) to compute the effective dark theme
     * passed to `RedfaceTheme`, and mirrored in Settings.
     */
    fun observeThemeMode(): Flow<ThemeMode>

    /** Persists [observeThemeMode]. Default [ThemeMode.SYSTEM] until the first call. */
    suspend fun setThemeMode(mode: ThemeMode)

    /**
     * AMOLED (true-black) theme toggle (#286): only takes effect when the effective theme is dark
     * (forced [ThemeMode.DARK] or [ThemeMode.SYSTEM] while the OS is in dark mode). Default `false`.
     */
    fun observeAmoledEnabled(): Flow<Boolean>

    /** Persists [observeAmoledEnabled]. Default `false` until the first call. */
    suspend fun setAmoledEnabled(enabled: Boolean)

    /**
     * Topic top app bar auto-hide (build 89 follow-up): when `true`, the topic top bar (title +
     * page counter) collapses while the user scrolls down through the posts and re-appears as soon
     * as they scroll back toward the top — Material3 `enterAlways` behaviour — freeing reading
     * space. Default `false` (the bar stays pinned). Observed by `:feature:topic`, toggled in
     * Settings.
     */
    fun observeTopicTopBarAutoHide(): Flow<Boolean>

    /** Persists [observeTopicTopBarAutoHide]. Default `false` until the first call. */
    suspend fun setTopicTopBarAutoHide(enabled: Boolean)
}
