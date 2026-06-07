package fr.forumhfr.redface2.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.FlagType
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

    override fun observeFlagsPerTabOverride(): Flow<Boolean> =
        dataStore.data
            // Default `false`: every tab shares the global toggles unless the user opts in (#309).
            .map { prefs -> prefs[KEY_FLAGS_PER_TAB_OVERRIDE] ?: false }
            .catch { emit(false) }

    override suspend fun setFlagsPerTabOverride(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[KEY_FLAGS_PER_TAB_OVERRIDE] = enabled
            }
        }
    }

    override fun observeFlagsViewSettings(type: FlagType): Flow<FlagsViewSettings> =
        dataStore.data
            .map { prefs -> resolveFlagsViewSettings(prefs, type) }
            // Fall back to the #179 global defaults (grouped on, hide-read off) on a read error.
            .catch { emit(FlagsViewSettings()) }

    override suspend fun setFlagsGroupByCategoryForType(type: FlagType, enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[flagsGroupByCategoryKey(type)] = enabled
            }
        }
    }

    override suspend fun setFlagsHideReadCategoriesForType(type: FlagType, enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[flagsHideReadCategoriesKey(type)] = enabled
            }
        }
    }

    /**
     * Resolves the per-tab view settings (#309). With the override off, the global pair is
     * returned verbatim; with it on, each toggle reads the per-type key and falls back to the
     * matching global value when that tab key is unset. The global defaults (grouped on,
     * hide-read off) are applied here so an empty DataStore yields the #179 behaviour.
     */
    private fun resolveFlagsViewSettings(prefs: Preferences, type: FlagType): FlagsViewSettings {
        val globalGroup = prefs[KEY_FLAGS_GROUP_BY_CATEGORY] ?: true
        val globalHide = prefs[KEY_FLAGS_HIDE_READ_CATEGORIES] ?: false
        if (prefs[KEY_FLAGS_PER_TAB_OVERRIDE] != true) {
            return FlagsViewSettings(groupByCategory = globalGroup, hideReadCategories = globalHide)
        }
        return FlagsViewSettings(
            groupByCategory = prefs[flagsGroupByCategoryKey(type)] ?: globalGroup,
            hideReadCategories = prefs[flagsHideReadCategoriesKey(type)] ?: globalHide,
        )
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
        // #309 — per-tab display override. The master switch plus one nullable key per FlagType for
        // each toggle; absence of a per-type key means « fall back to the global value ». Keys are
        // derived from the stable enum name (cyan/red/favorite), e.g. `flags_group_by_category_cyan`.
        val KEY_FLAGS_PER_TAB_OVERRIDE = booleanPreferencesKey("flags_per_tab_override")

        fun flagsGroupByCategoryKey(type: FlagType) =
            booleanPreferencesKey("flags_group_by_category_${type.name.lowercase()}")

        fun flagsHideReadCategoriesKey(type: FlagType) =
            booleanPreferencesKey("flags_hide_read_categories_${type.name.lowercase()}")
    }
}
