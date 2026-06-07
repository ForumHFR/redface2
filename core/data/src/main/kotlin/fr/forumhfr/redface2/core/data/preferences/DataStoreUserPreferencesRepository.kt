package fr.forumhfr.redface2.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
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
                prefs[KEY_PROXY_HOST] = normalized.host
                normalized.port?.let { prefs[KEY_PROXY_PORT] = it } ?: prefs.remove(KEY_PROXY_PORT)
                // SECURITY: proxy credentials follow ADR-012 and must never be written to DiagnosticsLog.
                normalized.username?.let { prefs[KEY_PROXY_USERNAME] = it } ?: prefs.remove(KEY_PROXY_USERNAME)
                normalized.password?.let { prefs[KEY_PROXY_PASSWORD] = it } ?: prefs.remove(KEY_PROXY_PASSWORD)
            }
        }
    }

    // PERF: intentionally synchronous because OkHttp/Coil clients are created during app bootstrap.
    // The MVP accepts app restart after proxy changes; async hot-swap is tracked by #195.
    override fun readProxyConfigForNetworkBootstrap(): ProxyConfig =
        runBlocking(ioDispatcher) { observeProxyConfig().first() }

    override fun observeIgnoreTopicCache(): Flow<Boolean> =
        dataStore.data
            .map { prefs -> prefs[KEY_IGNORE_TOPIC_CACHE] ?: false }
            .catch { emit(false) }

    override suspend fun setIgnoreTopicCache(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[KEY_IGNORE_TOPIC_CACHE] = enabled
            }
        }
    }

    override fun observeFlagsGroupByCategory(): Flow<Boolean> =
        dataStore.data
            // Default `true`: grouped view is the #179 default (cf. UserPreferencesRepository KDoc).
            .map { prefs -> prefs[KEY_FLAGS_GROUP_BY_CATEGORY] ?: true }
            .catch { emit(true) }

    override suspend fun setFlagsGroupByCategory(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[KEY_FLAGS_GROUP_BY_CATEGORY] = enabled
            }
        }
    }

    override fun observeFlagsHideReadCategories(): Flow<Boolean> =
        dataStore.data
            // Default `false`: HFR web parity (every category band shown).
            .map { prefs -> prefs[KEY_FLAGS_HIDE_READ_CATEGORIES] ?: false }
            .catch { emit(false) }

    override suspend fun setFlagsHideReadCategories(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[KEY_FLAGS_HIDE_READ_CATEGORIES] = enabled
            }
        }
    }

    private fun toProxyConfig(prefs: Preferences): ProxyConfig =
        ProxyConfig(
            enabled = prefs[KEY_PROXY_ENABLED] ?: false,
            host = prefs[KEY_PROXY_HOST].orEmpty(),
            port = prefs[KEY_PROXY_PORT],
            username = prefs[KEY_PROXY_USERNAME],
            password = prefs[KEY_PROXY_PASSWORD],
        ).normalized()

    private companion object {
        val KEY_PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val KEY_PROXY_HOST = stringPreferencesKey("proxy_host")
        val KEY_PROXY_PORT = intPreferencesKey("proxy_port")
        val KEY_PROXY_USERNAME = stringPreferencesKey("proxy_username")
        val KEY_PROXY_PASSWORD = stringPreferencesKey("proxy_password")
        val KEY_IGNORE_TOPIC_CACHE = booleanPreferencesKey("ignore_topic_cache")
        val KEY_FLAGS_GROUP_BY_CATEGORY = booleanPreferencesKey("flags_group_by_category")
        val KEY_FLAGS_HIDE_READ_CATEGORIES = booleanPreferencesKey("flags_hide_read_categories")
    }
}
