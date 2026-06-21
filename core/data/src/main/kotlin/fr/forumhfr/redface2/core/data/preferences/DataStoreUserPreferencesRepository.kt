package fr.forumhfr.redface2.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.forumhfr.redface2.core.domain.coroutines.ApplicationScope
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.preferences.AccentColor
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.StartScreenBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.StartScreenChoice
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.FlagType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@Singleton
class DataStoreUserPreferencesRepository @Inject constructor(
    @param:UserPreferencesDataStore private val dataStore: DataStore<Preferences>,
    private val themeBootstrapStore: ThemeBootstrapStore,
    private val startScreenBootstrapStore: StartScreenBootstrapStore,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:ApplicationScope private val externalScope: CoroutineScope,
) : UserPreferencesRepository {

    /**
     * Runs a preference write on [externalScope] (process-lifetime) instead of the caller's job, then
     * awaits it. A settings sub-page popped mid-write cancels the caller's `viewModelScope` — and thus
     * this `await()` — but the `async` is parented to [externalScope], so the DataStore commit (and any
     * bootstrap-mirror write in the same block) still completes and the change is not silently lost
     * (#507). `await()` re-throws a write failure to the caller, preserving the ViewModels' optimistic
     * rollback; if the caller is already gone, the failure is held in the (un-awaited) Deferred and the
     * `SupervisorJob` keeps the scope alive for the next write.
     *
     * Use for DISCRETE writes (toggles, pickers, Save buttons). Do NOT use for write-on-keystroke
     * setters that rely on caller cancellation to serialise (last-write-wins) — see [setImgurClientId],
     * which deliberately stays on the caller's cancellable scope.
     */
    private suspend fun persist(block: suspend () -> Unit) {
        externalScope.async { block() }.await()
    }

    override fun observeProxyConfig(): Flow<ProxyConfig> =
        dataStore.data
            .map(::toProxyConfig)
            .catch { emit(ProxyConfig()) }

    override suspend fun saveProxyConfig(config: ProxyConfig) {
        val normalized = config.normalized()
        persist {
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
        persist {
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
        persist {
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
        persist {
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
        persist {
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
        persist {
            dataStore.edit { prefs ->
                prefs[flagsGroupByCategoryKey(type)] = enabled
            }
        }
    }

    override suspend fun setFlagsHideReadCategoriesForType(type: FlagType, enabled: Boolean) {
        persist {
            dataStore.edit { prefs ->
                prefs[flagsHideReadCategoriesKey(type)] = enabled
            }
        }
    }

    override suspend fun setFlagsUnreadOnlyForType(type: FlagType, enabled: Boolean) {
        persist {
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
            // #386 backfill : users who picked their theme BEFORE the mirror existed start with
            // an empty mirror — converge it from the observed truth (idempotent, write-on-diff).
            .onEach { mode ->
                if (themeBootstrapStore.read().themeMode != mode) {
                    themeBootstrapStore.writeThemeMode(mode)
                }
            }
            .catch { emit(ThemeMode.SYSTEM) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_THEME_MODE] = mode.name
            }
            // Mirror for the synchronous cold-start read (#386) — DataStore stays the source
            // of truth, the mirror only seeds the first frame.
            themeBootstrapStore.writeThemeMode(mode)
        }
    }

    override fun observeAmoledEnabled(): Flow<Boolean> =
        dataStore.data
            // Default `false`: AMOLED is opt-in and only meaningful in dark (#286).
            .map { prefs -> prefs[KEY_AMOLED_ENABLED] ?: false }
            .distinctUntilChanged()
            .onEach { enabled ->
                if (themeBootstrapStore.read().amoledEnabled != enabled) {
                    themeBootstrapStore.writeAmoledEnabled(enabled)
                }
            }
            .catch { emit(false) }

    override suspend fun setAmoledEnabled(enabled: Boolean) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_AMOLED_ENABLED] = enabled
            }
            themeBootstrapStore.writeAmoledEnabled(enabled)
        }
    }

    override fun observeAccentColor(): Flow<AccentColor> =
        dataStore.data
            // Default ROSE (TU 2788511): the historical maroon/rose scheme until the user opts into red.
            .map(::readAccentColor)
            // Keep the accent flow quiet unless it actually changes so RedfaceApp doesn't recompose
            // the whole tree on unrelated edits — same stance as observeThemeMode. No bootstrap mirror:
            // the accent does not paint the window background (cf. observeDisplayDensity).
            .distinctUntilChanged()
            .catch { emit(AccentColor.ROSE) }

    override suspend fun setAccentColor(color: AccentColor) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_ACCENT_COLOR] = color.name
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
        persist {
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
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_CONFIRM_BEFORE_POSTING] = enabled
            }
        }
    }

    override fun observeShowDtSection(): Flow<Boolean> =
        dataStore.data
            // Default `false`: the DT tab is a placeholder until the MPStorage sync (#6) — opt-in only.
            .map { prefs -> prefs[KEY_FLAGS_SHOW_DT_SECTION] ?: false }
            .distinctUntilChanged()
            .catch { emit(false) }

    override suspend fun setShowDtSection(enabled: Boolean) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_FLAGS_SHOW_DT_SECTION] = enabled
            }
        }
    }

    override fun observeSyncPrivateMessagesWriteEnabled(): Flow<Boolean> =
        dataStore.data
            // Default `false` (#6, ADR-014 §4): the MPStorage write-back is experimental and opt-in —
            // the write contract was never observed live, so it stays OFF until the user explicitly enables it.
            .map { prefs -> prefs[KEY_SYNC_PRIVATE_MESSAGES_WRITE_ENABLED] ?: false }
            .distinctUntilChanged()
            .catch { emit(false) }

    override suspend fun setSyncPrivateMessagesWriteEnabled(enabled: Boolean) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_SYNC_PRIVATE_MESSAGES_WRITE_ENABLED] = enabled
            }
        }
    }

    override fun observeFlagsAutoRefresh(): Flow<Boolean> =
        dataStore.data
            // Default `true`: the lists going stale is the #378 complaint — the toggle is an
            // opt-out for users who prefer pull-to-refresh only.
            .map { prefs -> prefs[KEY_FLAGS_AUTO_REFRESH] ?: true }
            .distinctUntilChanged()
            .catch { emit(true) }

    override suspend fun setFlagsAutoRefresh(enabled: Boolean) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_FLAGS_AUTO_REFRESH] = enabled
            }
        }
    }

    override fun observeTopicPageFabs(): Flow<Boolean> =
        dataStore.data
            // Default `true`: the ‹/› cluster (#283) predates the swipe (#282); hiding it is the
            // #383 opt-out for readers who navigate by swipe only.
            .map { prefs -> prefs[KEY_TOPIC_PAGE_FABS] ?: true }
            .distinctUntilChanged()
            .catch { emit(true) }

    override suspend fun setTopicPageFabs(enabled: Boolean) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_TOPIC_PAGE_FABS] = enabled
            }
        }
    }

    override fun observeTopicPollsExpanded(): Flow<Boolean> =
        dataStore.data
            // Default `false` (#456): polls start collapsed — the in-card toggle still reveals
            // them per topic; this preference only seeds the initial state.
            .map { prefs -> prefs[KEY_TOPIC_POLLS_EXPANDED] ?: false }
            .distinctUntilChanged()
            .catch { emit(false) }

    override suspend fun setTopicPollsExpanded(enabled: Boolean) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_TOPIC_POLLS_EXPANDED] = enabled
            }
        }
    }

    override fun observeTopicSignatures(): Flow<Boolean> =
        dataStore.data
            // Default `false` (#330): signatures are noisy; opt-in so the feed stays compact.
            .map { prefs -> prefs[KEY_TOPIC_SIGNATURES] ?: false }
            .distinctUntilChanged()
            .catch { emit(false) }

    override suspend fun setTopicSignatures(enabled: Boolean) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_TOPIC_SIGNATURES] = enabled
            }
        }
    }

    override fun observeFoldLongQuotes(): Flow<Boolean> =
        dataStore.data
            // Default `true` (#332): the long-quote fold is the historical behaviour; turning it
            // off is the opt-out for readers who found the auto-fold « trop strict ».
            .map { prefs -> prefs[KEY_FOLD_LONG_QUOTES] ?: true }
            .distinctUntilChanged()
            .catch { emit(true) }

    override suspend fun setFoldLongQuotes(enabled: Boolean) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_FOLD_LONG_QUOTES] = enabled
            }
        }
    }

    override fun observeShowScrollbar(): Flow<Boolean> =
        dataStore.data
            // Default `true` (#105): the reading scrollbar is the historical behaviour; hiding it is
            // the opt-out requested in the beta thread (styx42).
            .map { prefs -> prefs[KEY_SHOW_SCROLLBAR] ?: true }
            .distinctUntilChanged()
            .catch { emit(true) }

    override suspend fun setShowScrollbar(enabled: Boolean) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_SHOW_SCROLLBAR] = enabled
            }
        }
    }

    override fun observeStartScreen(): Flow<StartScreenPreference> =
        dataStore.data
            .map(::readStartScreen)
            .distinctUntilChanged()
            // #458 backfill, same contract as the theme mirror (#386): converge the synchronous
            // bootstrap copy from the observed truth (idempotent, write-on-diff). Hops to IO
            // because the mirror write is a synchronous commit() (cf. its KDoc) and this flow
            // is collected on Main by the Settings ViewModel.
            .onEach { preference ->
                withContext(ioDispatcher) {
                    if (startScreenBootstrapStore.read() != preference) {
                        startScreenBootstrapStore.write(preference)
                    }
                }
            }
            .catch { emit(StartScreenPreference()) }

    override suspend fun setStartScreen(preference: StartScreenPreference) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_START_SCREEN] = preference.screen.name
                val catId = preference.forumCatId
                if (preference.screen == StartScreenChoice.FORUM && catId != null) {
                    prefs[KEY_START_FORUM_CAT] = catId
                } else {
                    prefs.remove(KEY_START_FORUM_CAT)
                }
            }
            // Mirror for the synchronous cold-start read (#458) — DataStore stays the source
            // of truth, the mirror only seeds the first frame.
            startScreenBootstrapStore.write(preference)
        }
    }

    private fun readStartScreen(prefs: Preferences): StartScreenPreference {
        // Defensive read: an unknown stored value (downgrade, manual edit) falls back to the
        // FLAGS default instead of crashing, same stance as readThemeMode.
        val screen = prefs[KEY_START_SCREEN]
            ?.let { stored -> StartScreenChoice.entries.firstOrNull { it.name == stored } }
            ?: StartScreenChoice.FLAGS
        val catId = prefs[KEY_START_FORUM_CAT]
            ?.takeIf { screen == StartScreenChoice.FORUM && it > 0 }
        return StartScreenPreference(screen = screen, forumCatId = catId)
    }

    override fun observeMpUnreadBadge(): Flow<Boolean> =
        dataStore.data
            // Default `true` (#313): the badge is the feature; opting OUT is the preference.
            .map { prefs -> prefs[KEY_MP_UNREAD_BADGE] ?: true }
            .distinctUntilChanged()
            .catch { emit(true) }

    override suspend fun setMpUnreadBadge(enabled: Boolean) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_MP_UNREAD_BADGE] = enabled
            }
        }
    }

    override fun observeUploadProvider(): Flow<UploadProviderId> =
        dataStore.data
            // Default DIBERIE (#459): no auth, no Client-ID required.
            .map(::readUploadProvider)
            .distinctUntilChanged()
            .catch { emit(UploadProviderId.DIBERIE) }

    override suspend fun setUploadProvider(provider: UploadProviderId) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_UPLOAD_PROVIDER] = provider.name
            }
        }
    }

    override fun observeDisplayDensity(): Flow<DisplayDensity> =
        dataStore.data
            // Default COMFORT (#287): the historical structural rhythm unless the user opts into
            // the denser COMPACT preset. No bootstrap mirror — this preset does not paint the
            // pre-first-frame window, so a SYSTEM-style cold-start flash is not a concern here.
            .map(::readDisplayDensity)
            .distinctUntilChanged()
            .catch { emit(DisplayDensity.COMFORT) }

    override suspend fun setDisplayDensity(density: DisplayDensity) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_DISPLAY_DENSITY] = density.name
            }
        }
    }

    override fun observeImgurClientId(): Flow<String> =
        dataStore.data
            // Default empty (#459): imgur is unconfigured until the user pastes their Client-ID.
            .map { prefs -> prefs[KEY_IMGUR_CLIENT_ID].orEmpty() }
            .distinctUntilChanged()
            .catch { emit("") }

    // NOT routed through persist(): unlike the discrete toggles/pickers, the Client-ID is written on
    // EVERY keystroke and SettingsViewModel cancels the previous in-flight write so only the latest text
    // commits (last-write-wins, #459). Detaching it onto the application scope would let a cancelled
    // older write survive and land after a newer one, stranding a stale value (#508 Codex review). Here
    // cancellation IS the intended behaviour, so this write stays on the caller's (cancellable) scope.
    override suspend fun setImgurClientId(clientId: String) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[KEY_IMGUR_CLIENT_ID] = clientId.trim()
            }
        }
    }

    override fun observeEditorImageInsert(): Flow<EditorImageInsert> =
        dataStore.data
            // Default REDUCED (#459 PR-images follow-up): the classic HFR "vignette cliquable".
            .map(::readEditorImageInsert)
            .distinctUntilChanged()
            .catch { emit(EditorImageInsert.REDUCED) }

    override suspend fun setEditorImageInsert(mode: EditorImageInsert) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_EDITOR_IMAGE_INSERT] = mode.name
            }
        }
    }

    override fun observeFontScale(): Flow<FontScalePreference> =
        dataStore.data
            // Default M (#287): the M3 reference sizes unless the user picks S / L.
            .map(::readFontScale)
            .distinctUntilChanged()
            .catch { emit(FontScalePreference.M) }

    override suspend fun setFontScale(scale: FontScalePreference) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_FONT_SCALE] = scale.name
            }
        }
    }

    override fun observeDebugBoundsOverlay(): Flow<Boolean> =
        dataStore.data
            // Default `false` (#445): the debug bounds overlay is opt-in and dev-channel only.
            .map { prefs -> prefs[KEY_DEBUG_BOUNDS_OVERLAY] ?: false }
            .distinctUntilChanged()
            .catch { emit(false) }

    override suspend fun setDebugBoundsOverlay(enabled: Boolean) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_DEBUG_BOUNDS_OVERLAY] = enabled
            }
        }
    }

    override fun observeHideSystemNavBar(): Flow<Boolean> =
        dataStore.data
            // Default `false` (#518): immersive mode is opt-in — most users expect the 3 buttons.
            .map { prefs -> prefs[KEY_HIDE_SYSTEM_NAV_BAR] ?: false }
            .distinctUntilChanged()
            .catch { emit(false) }

    override suspend fun setHideSystemNavBar(enabled: Boolean) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_HIDE_SYSTEM_NAV_BAR] = enabled
            }
        }
    }

    override fun observeImmersiveBackButton(): Flow<Boolean> =
        dataStore.data
            // Default `true` (#518 follow-up): only ever shown WHILE immersive mode is on, so the
            // default-on keeps 3-button users able to go back; the toggle is the opt-out.
            .map { prefs -> prefs[KEY_IMMERSIVE_BACK_BUTTON] ?: true }
            .distinctUntilChanged()
            .catch { emit(true) }

    override suspend fun setImmersiveBackButton(enabled: Boolean) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_IMMERSIVE_BACK_BUTTON] = enabled
            }
        }
    }

    override fun observeImmersiveNavBarReveal(): Flow<ImmersiveNavBarReveal> =
        dataStore.data
            // Default MANUAL (#518 follow-up): swipe-from-bottom only, the historical immersive behaviour.
            .map(::readImmersiveNavBarReveal)
            .distinctUntilChanged()
            .catch { emit(ImmersiveNavBarReveal.MANUAL) }

    override suspend fun setImmersiveNavBarReveal(mode: ImmersiveNavBarReveal) {
        persist {
            dataStore.edit { prefs ->
                prefs[KEY_IMMERSIVE_NAV_BAR_REVEAL] = mode.name
            }
        }
    }

    /**
     * Reads [KEY_UPLOAD_PROVIDER] defensively: an unknown / corrupt stored value (older build with a
     * renamed enum, manual edit) falls back to [UploadProviderId.DIBERIE] instead of crashing on
     * `UploadProviderId.valueOf`, same stance as [readThemeMode].
     */
    private fun readUploadProvider(prefs: Preferences): UploadProviderId =
        prefs[KEY_UPLOAD_PROVIDER]
            ?.let { stored -> runCatching { UploadProviderId.valueOf(stored) }.getOrNull() }
            ?: UploadProviderId.DIBERIE

    /** Reads [KEY_EDITOR_IMAGE_INSERT] defensively; unknown / corrupt value → [EditorImageInsert.REDUCED]. */
    private fun readEditorImageInsert(prefs: Preferences): EditorImageInsert =
        prefs[KEY_EDITOR_IMAGE_INSERT]
            ?.let { stored -> runCatching { EditorImageInsert.valueOf(stored) }.getOrNull() }
            ?: EditorImageInsert.REDUCED

    /**
     * Reads [KEY_DISPLAY_DENSITY] defensively (#287): an unknown / corrupt stored value (older
     * build, manual edit) falls back to [DisplayDensity.COMFORT] instead of crashing on
     * `DisplayDensity.valueOf`, same stance as [readThemeMode].
     */
    private fun readDisplayDensity(prefs: Preferences): DisplayDensity =
        prefs[KEY_DISPLAY_DENSITY]
            ?.let { stored -> runCatching { DisplayDensity.valueOf(stored) }.getOrNull() }
            ?: DisplayDensity.COMFORT

    /**
     * Reads [KEY_IMMERSIVE_NAV_BAR_REVEAL] defensively (#518 follow-up): an unknown / corrupt stored
     * value falls back to [ImmersiveNavBarReveal.MANUAL] instead of crashing on
     * `ImmersiveNavBarReveal.valueOf`, same stance as [readDisplayDensity].
     */
    private fun readImmersiveNavBarReveal(prefs: Preferences): ImmersiveNavBarReveal =
        prefs[KEY_IMMERSIVE_NAV_BAR_REVEAL]
            ?.let { stored -> runCatching { ImmersiveNavBarReveal.valueOf(stored) }.getOrNull() }
            ?: ImmersiveNavBarReveal.MANUAL

    /**
     * Reads [KEY_ACCENT_COLOR] defensively (TU 2788511): an unknown / corrupt stored value falls back
     * to [AccentColor.ROSE] instead of crashing on `AccentColor.valueOf`, same stance as [readThemeMode].
     */
    private fun readAccentColor(prefs: Preferences): AccentColor =
        prefs[KEY_ACCENT_COLOR]
            ?.let { stored -> runCatching { AccentColor.valueOf(stored) }.getOrNull() }
            ?: AccentColor.ROSE

    /**
     * Reads [KEY_FONT_SCALE] defensively (#287): an unknown / corrupt stored value falls back to
     * [FontScalePreference.M] instead of crashing on `FontScalePreference.valueOf`.
     */
    private fun readFontScale(prefs: Preferences): FontScalePreference =
        prefs[KEY_FONT_SCALE]
            ?.let { stored -> runCatching { FontScalePreference.valueOf(stored) }.getOrNull() }
            ?: FontScalePreference.M

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
        val KEY_ACCENT_COLOR = stringPreferencesKey("accent_color")

        // build 89 follow-up — topic top app bar auto-hide on scroll.
        val KEY_TOPIC_TOPBAR_AUTO_HIDE = booleanPreferencesKey("topic_topbar_auto_hide")

        // #312 — confirmation dialog before any publish action (reply / edit / new topic / MP).
        val KEY_CONFIRM_BEFORE_POSTING = booleanPreferencesKey("confirm_before_posting")

        // Opt-in « DT » placeholder tab on the Drapeaux screen (MPStorage sync lands later, #6).
        val KEY_FLAGS_SHOW_DT_SECTION = booleanPreferencesKey("flags_show_dt_section")

        // #6, ADR-014 §4 — experimental opt-in for the MPStorage WRITE-BACK (default false; off = no write).
        val KEY_SYNC_PRIVATE_MESSAGES_WRITE_ENABLED =
            booleanPreferencesKey("sync_private_messages_write_enabled")

        // #378 — auto-refresh of the flags lists on landing (default ON; Settings opt-out).
        val KEY_FLAGS_AUTO_REFRESH = booleanPreferencesKey("flags_auto_refresh")
        val KEY_TOPIC_PAGE_FABS = booleanPreferencesKey("topic_page_fabs")
        val KEY_MP_UNREAD_BADGE = booleanPreferencesKey("mp_unread_badge")

        // #456 — polls expanded by default in topic reading (default false = collapsed).
        val KEY_TOPIC_POLLS_EXPANDED = booleanPreferencesKey("topic_polls_expanded")

        // #330 — render author signatures beneath posts (default false = hidden, signatures are noisy).
        val KEY_TOPIC_SIGNATURES = booleanPreferencesKey("topic_signatures")

        // #332 — fold long top-level citations by default (default true = historical fold; opt-out).
        val KEY_FOLD_LONG_QUOTES = booleanPreferencesKey("fold_long_quotes")

        // #105 — show the intra-page reading scrollbar (default true = historical; opt-out).
        val KEY_SHOW_SCROLLBAR = booleanPreferencesKey("show_scrollbar")

        // #458 — cold-start tab (StartScreenChoice.name, defensively parsed) + optional Forum
        // category id (absent unless screen == FORUM and a category was picked).
        val KEY_START_SCREEN = stringPreferencesKey("start_screen")
        val KEY_START_FORUM_CAT = intPreferencesKey("start_forum_cat")

        // #459 — default image host (UploadProviderId.name, defensively parsed) + the user's own
        // imgur Client-ID (empty = imgur unconfigured, option B: never committed).
        val KEY_UPLOAD_PROVIDER = stringPreferencesKey("upload_provider")
        val KEY_IMGUR_CLIENT_ID = stringPreferencesKey("imgur_client_id")
        // #459 PR-images follow-up — editor image insert mode (EditorImageInsert.name, defensively parsed).
        val KEY_EDITOR_IMAGE_INSERT = stringPreferencesKey("editor_image_insert")

        // #287 — reading display presets: density (DisplayDensity.name) + font scale
        // (FontScalePreference.name), both defensively parsed. No bootstrap mirror (cf. observers).
        val KEY_DISPLAY_DENSITY = stringPreferencesKey("display_density")
        val KEY_FONT_SCALE = stringPreferencesKey("font_scale")

        // #445 — debug bounds overlay toggle (default false; exposed on the dev channel only).
        val KEY_DEBUG_BOUNDS_OVERLAY = booleanPreferencesKey("debug_bounds_overlay")
        val KEY_HIDE_SYSTEM_NAV_BAR = booleanPreferencesKey("hide_system_nav_bar")
        val KEY_IMMERSIVE_BACK_BUTTON = booleanPreferencesKey("immersive_back_button")
        val KEY_IMMERSIVE_NAV_BAR_REVEAL = stringPreferencesKey("immersive_nav_bar_reveal")
    }
}
