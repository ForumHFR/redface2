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
}
