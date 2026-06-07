package fr.forumhfr.redface2.core.domain.preferences

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
}
