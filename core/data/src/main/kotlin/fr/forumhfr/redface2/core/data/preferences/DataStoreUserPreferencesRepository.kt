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
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.FlagType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
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
            // `dataStore.data` re-emits the whole snapshot on ANY write (proxy, another tab's
            // per-type key, …); distinctUntilChanged keeps this flow quiet unless THIS type's
            // resolved settings actually change, so the Flags combine doesn't churn on unrelated edits.
            .distinctUntilChanged()
            // Fall back to the defaults on a read error: #179 layout (grouped on, hide-read off) and
            // the #317 type-aware unreadOnly — so CYAN still degrades to its actionable subset, not
            // « show all », keeping the error path faithful to resolveFlagsViewSettings.
            .catch { emit(FlagsViewSettings(unreadOnly = defaultUnreadOnly(type))) }

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

    override suspend fun setFlagsUnreadOnlyForType(type: FlagType, enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[flagsUnreadOnlyKey(type)] = enabled
            }
        }
    }

    override fun observeThemeMode(): Flow<ThemeMode> =
        dataStore.data
            // Default SYSTEM: follow the OS dark-mode setting unless the user picked otherwise (#286).
            .map(::readThemeMode)
            // `dataStore.data` re-emits on ANY write; keep the theme flow quiet unless the mode
            // actually changes so RedfaceApp doesn't recompose the whole tree on unrelated edits.
            .distinctUntilChanged()
            .catch { emit(ThemeMode.SYSTEM) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[KEY_THEME_MODE] = mode.name
            }
        }
    }

    override fun observeAmoledEnabled(): Flow<Boolean> =
        dataStore.data
            // Default `false`: AMOLED is opt-in and only meaningful in dark (#286).
            .map { prefs -> prefs[KEY_AMOLED_ENABLED] ?: false }
            .distinctUntilChanged()
            .catch { emit(false) }

    override suspend fun setAmoledEnabled(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[KEY_AMOLED_ENABLED] = enabled
            }
        }
    }

    override fun observeTopicTopBarAutoHide(): Flow<Boolean> =
        dataStore.data
            // Default `false`: the topic top bar stays pinned unless the user opts into auto-hide.
            .map { prefs -> prefs[KEY_TOPIC_TOPBAR_AUTO_HIDE] ?: false }
            .distinctUntilChanged()
            .catch { emit(false) }

    override suspend fun setTopicTopBarAutoHide(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[KEY_TOPIC_TOPBAR_AUTO_HIDE] = enabled
            }
        }
    }

    override fun observeConfirmBeforePosting(): Flow<Boolean> =
        dataStore.data
            // Default `false`: publishing stays one-tap unless the user opts into the #312 guard.
            .map { prefs -> prefs[KEY_CONFIRM_BEFORE_POSTING] ?: false }
            .distinctUntilChanged()
            .catch { emit(false) }

    override suspend fun setConfirmBeforePosting(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[KEY_CONFIRM_BEFORE_POSTING] = enabled
            }
        }
    }

    /**
     * Reads [KEY_THEME_MODE] defensively: an unknown / corrupt stored value (older build with a
     * renamed enum, manual edit) falls back to [ThemeMode.SYSTEM] instead of crashing on
     * `ThemeMode.valueOf`.
     */
    private fun readThemeMode(prefs: Preferences): ThemeMode =
        prefs[KEY_THEME_MODE]
            ?.let { stored -> runCatching { ThemeMode.valueOf(stored) }.getOrNull() }
            ?: ThemeMode.SYSTEM

    /**
     * Resolves the per-tab view settings (#309 layout) plus the per-type unreadOnly (#317). For the
     * LAYOUT pair: with the override off, the global pair is returned verbatim; with it on, each
     * toggle reads the per-type key and falls back to the matching global value when that tab key is
     * unset. The global defaults (grouped on, hide-read off) are applied here so an empty DataStore
     * yields the #179 behaviour. The #317 [FlagsViewSettings.unreadOnly] is resolved independently of
     * the override (always per-type, type-aware default via [defaultUnreadOnly]) and added to BOTH
     * return paths.
     *
     * Per-type keys are intentionally **sticky**: turning the override off does not clear them, so
     * re-enabling it later restores each tab's previously customised values (rather than silently
     * re-inheriting the global pair). This is the "remember my per-tab tuning" contract; the keys
     * simply sit dormant while the override is off.
     */
    private fun resolveFlagsViewSettings(prefs: Preferences, type: FlagType): FlagsViewSettings {
        val globalGroup = prefs[KEY_FLAGS_GROUP_BY_CATEGORY] ?: true
        val globalHide = prefs[KEY_FLAGS_HIDE_READ_CATEGORIES] ?: false
        // #317 — unreadOnly is always per-type with a type-aware default (CYAN actionable by default).
        val unreadOnly = prefs[flagsUnreadOnlyKey(type)] ?: defaultUnreadOnly(type)
        if (prefs[KEY_FLAGS_PER_TAB_OVERRIDE] != true) {
            return FlagsViewSettings(
                groupByCategory = globalGroup,
                hideReadCategories = globalHide,
                unreadOnly = unreadOnly,
            )
        }
        return FlagsViewSettings(
            groupByCategory = prefs[flagsGroupByCategoryKey(type)] ?: globalGroup,
            hideReadCategories = prefs[flagsHideReadCategoriesKey(type)] ?: globalHide,
            unreadOnly = unreadOnly,
        )
    }

    /**
     * Type-aware default for the #317 « non-lus uniquement » filter: CYAN (« Mes sujets ») shows the
     * actionable unread subset by default (legacy behaviour); RED / FAVORITE show everything.
     */
    private fun defaultUnreadOnly(type: FlagType): Boolean = type == FlagType.CYAN

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

        // #317 — per-type « non-lus uniquement » key (no global counterpart; type-aware default).
        fun flagsUnreadOnlyKey(type: FlagType) =
            booleanPreferencesKey("flags_unread_only_${type.name.lowercase()}")

        // #286 — app theme selection (ThemeMode.name, defensively parsed) + AMOLED toggle.
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_AMOLED_ENABLED = booleanPreferencesKey("amoled_enabled")

        // build 89 follow-up — topic top app bar auto-hide on scroll.
        val KEY_TOPIC_TOPBAR_AUTO_HIDE = booleanPreferencesKey("topic_topbar_auto_hide")

        // #312 — confirmation dialog before any publish action (reply / edit / new topic / MP).
        val KEY_CONFIRM_BEFORE_POSTING = booleanPreferencesKey("confirm_before_posting")
    }
}
