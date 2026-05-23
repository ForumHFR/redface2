package fr.forumhfr.redface2.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.ProxyScheme
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@Singleton
class DataStoreUserPreferencesRepository @Inject constructor(
    @param:UserPreferencesDataStore private val dataStore: DataStore<Preferences>,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : UserPreferencesRepository {

    override fun observeProxyConfig(): Flow<ProxyConfig> =
        dataStore.data
            .map(::toProxyConfig)
            .catch { emit(ProxyConfig()) }

    override suspend fun saveProxyConfig(config: ProxyConfig) {
        val normalized = config.normalized()
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[KEY_PROXY_ENABLED] = normalized.enabled
                prefs[KEY_PROXY_SCHEME] = normalized.scheme.name
                prefs[KEY_PROXY_HOST] = normalized.host
                normalized.port?.let { prefs[KEY_PROXY_PORT] = it } ?: prefs.remove(KEY_PROXY_PORT)
                normalized.username?.let { prefs[KEY_PROXY_USERNAME] = it } ?: prefs.remove(KEY_PROXY_USERNAME)
                normalized.password?.let { prefs[KEY_PROXY_PASSWORD] = it } ?: prefs.remove(KEY_PROXY_PASSWORD)
            }
        }
    }

    override fun readProxyConfigForNetworkBootstrap(): ProxyConfig =
        runBlocking(ioDispatcher) { observeProxyConfig().first() }

    private fun toProxyConfig(prefs: Preferences): ProxyConfig {
        val scheme = prefs[KEY_PROXY_SCHEME]
            ?.let { raw -> runCatching { ProxyScheme.valueOf(raw) }.getOrNull() }
            ?: ProxyScheme.HTTP
        return ProxyConfig(
            enabled = prefs[KEY_PROXY_ENABLED] ?: false,
            scheme = scheme,
            host = prefs[KEY_PROXY_HOST].orEmpty(),
            port = prefs[KEY_PROXY_PORT],
            username = prefs[KEY_PROXY_USERNAME],
            password = prefs[KEY_PROXY_PASSWORD],
        ).normalized()
    }

    private companion object {
        val KEY_PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val KEY_PROXY_SCHEME = stringPreferencesKey("proxy_scheme")
        val KEY_PROXY_HOST = stringPreferencesKey("proxy_host")
        val KEY_PROXY_PORT = intPreferencesKey("proxy_port")
        val KEY_PROXY_USERNAME = stringPreferencesKey("proxy_username")
        val KEY_PROXY_PASSWORD = stringPreferencesKey("proxy_password")
    }
}
