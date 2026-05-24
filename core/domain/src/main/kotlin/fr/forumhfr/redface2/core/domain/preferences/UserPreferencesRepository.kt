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

    suspend fun setIgnoreTopicCache(enabled: Boolean)
}
