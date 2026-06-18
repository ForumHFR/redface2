package fr.forumhfr.redface2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.cache.ImageCacheMaintenance
import fr.forumhfr.redface2.core.domain.cache.TopicCacheMaintenance
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Flat MVI dispatcher: one small, near-identical optimistic-flip handler per user preference.
// The size comes from breadth (many independent settings live here cohesively), not from deep
// logic — same reason `submit()` carries @Suppress("CyclomaticComplexMethod"). A generic
// `updateEnumPreference` helper could fold the enum handlers together later (see PR notes).
@Suppress("LargeClass")
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val topicCacheMaintenance: TopicCacheMaintenance,
    private val imageCacheMaintenance: ImageCacheMaintenance,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        // One-shot hydration of every persisted preference into `_state`. We deliberately
        // use `.first()` (point-in-time read) rather than a long-lived collect, so toggling
        // a preference from inside this screen does not race with itself via the observe
        // path. Each `.copy(...)` keeps the other fields intact — never replace the whole
        // state with a partial config, that would wipe the maintenance fields.
        viewModelScope.launch {
            val config = userPreferencesRepository.observeProxyConfig().first()
            _state.update { it.copyFromProxy(config) }
        }
        hydratePreference(
            read = { userPreferencesRepository.observeIgnoreTopicCache().first() },
            isLocked = { it.ignoreTopicCacheTouchedLocally || it.isUpdatingIgnoreTopicCache },
            apply = { state, value -> state.copy(ignoreTopicCache = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeDebugBoundsOverlay().first() },
            isLocked = { it.debugBoundsOverlayTouchedLocally || it.isUpdatingDebugBoundsOverlay },
            apply = { state, value -> state.copy(debugBoundsOverlay = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeFlagsGroupByCategory().first() },
            isLocked = { it.flagsGroupByCategoryTouchedLocally || it.isUpdatingFlagsGroupByCategory },
            apply = { state, value -> state.copy(flagsGroupByCategory = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeFlagsHideReadCategories().first() },
            isLocked = { it.flagsHideReadCategoriesTouchedLocally || it.isUpdatingFlagsHideReadCategories },
            apply = { state, value -> state.copy(flagsHideReadCategories = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeFlagsPerTabOverride().first() },
            isLocked = { it.flagsPerTabOverrideTouchedLocally || it.isUpdatingFlagsPerTabOverride },
            apply = { state, value -> state.copy(flagsPerTabOverride = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeThemeMode().first() },
            isLocked = { it.themeModeTouchedLocally || it.isUpdatingThemeMode },
            apply = { state, value -> state.copy(themeMode = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeAmoledEnabled().first() },
            isLocked = { it.amoledTouchedLocally || it.isUpdatingAmoled },
            apply = { state, value -> state.copy(amoledEnabled = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeTopicTopBarAutoHide().first() },
            isLocked = { it.topicTopBarAutoHideTouchedLocally || it.isUpdatingTopicTopBarAutoHide },
            apply = { state, value -> state.copy(topicTopBarAutoHide = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeTopicPageFabs().first() },
            isLocked = { it.topicPageFabsTouchedLocally || it.isUpdatingTopicPageFabs },
            apply = { state, value -> state.copy(topicPageFabs = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeMpUnreadBadge().first() },
            isLocked = { it.mpUnreadBadgeTouchedLocally || it.isUpdatingMpUnreadBadge },
            apply = { state, value -> state.copy(mpUnreadBadge = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeTopicPollsExpanded().first() },
            isLocked = { it.topicPollsExpandedTouchedLocally || it.isUpdatingTopicPollsExpanded },
            apply = { state, value -> state.copy(topicPollsExpanded = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeTopicSignatures().first() },
            isLocked = { it.topicSignaturesTouchedLocally || it.isUpdatingTopicSignatures },
            apply = { state, value -> state.copy(topicSignatures = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeFoldLongQuotes().first() },
            isLocked = { it.foldLongQuotesTouchedLocally || it.isUpdatingFoldLongQuotes },
            apply = { state, value -> state.copy(foldLongQuotes = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeHideSystemNavBar().first() },
            isLocked = { it.hideSystemNavBarTouchedLocally || it.isUpdatingHideSystemNavBar },
            apply = { state, value -> state.copy(hideSystemNavBar = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeImmersiveBackButton().first() },
            isLocked = { it.immersiveBackButtonTouchedLocally || it.isUpdatingImmersiveBackButton },
            apply = { state, value -> state.copy(immersiveBackButton = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeConfirmBeforePosting().first() },
            isLocked = { it.confirmBeforePostingTouchedLocally || it.isUpdatingConfirmBeforePosting },
            apply = { state, value -> state.copy(confirmBeforePosting = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeShowDtSection().first() },
            isLocked = { it.showDtSectionTouchedLocally || it.isUpdatingShowDtSection },
            apply = { state, value -> state.copy(showDtSection = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeFlagsAutoRefresh().first() },
            isLocked = { it.flagsAutoRefreshTouchedLocally || it.isUpdatingFlagsAutoRefresh },
            apply = { state, value -> state.copy(flagsAutoRefresh = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeDisplayDensity().first() },
            isLocked = { it.displayDensityTouchedLocally || it.isUpdatingDisplayDensity },
            apply = { state, value -> state.copy(displayDensity = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeFontScale().first() },
            isLocked = { it.fontScaleTouchedLocally || it.isUpdatingFontScale },
            apply = { state, value -> state.copy(fontScale = value) },
        )
        // #459 — Hébergeur d'images : provider (enum) + imgur Client-ID (text). Same one-shot
        // hydration + touched-locally guard as the other prefs.
        hydratePreference(
            read = { userPreferencesRepository.observeUploadProvider().first() },
            isLocked = { it.uploadProviderTouchedLocally || it.isUpdatingUploadProvider },
            apply = { state, value -> state.copy(uploadProvider = value) },
        )
        hydratePreference(
            read = { userPreferencesRepository.observeImgurClientId().first() },
            isLocked = { it.imgurClientIdTouchedLocally },
            apply = { state, value -> state.copy(imgurClientId = value) },
        )
        // #459 PR-images follow-up — editor image insert mode (enum), same hydration shape.
        hydratePreference(
            read = { userPreferencesRepository.observeEditorImageInsert().first() },
            isLocked = { it.editorImageInsertTouchedLocally || it.isUpdatingEditorImageInsert },
            apply = { state, value -> state.copy(editorImageInsert = value) },
        )
    }

    /**
     * One-shot hydration of a persisted preference into [_state] (point-in-time `first()`,
     * cf. the init comment). [isLocked] is the startup-race guard: if the user already
     * flipped the toggle (or a write is in flight) while this coroutine was suspended on
     * the read, the stale snapshot must NOT overwrite the local change.
     */
    private fun <T> hydratePreference(
        read: suspend () -> T,
        isLocked: (SettingsState) -> Boolean,
        apply: (SettingsState, T) -> SettingsState,
    ) {
        viewModelScope.launch {
            val value = read()
            _state.update { current -> if (isLocked(current)) current else apply(current, value) }
        }
    }

    @Suppress("CyclomaticComplexMethod") // MVI when-dispatch over the SettingsIntent variants ; flat by design.
    fun submit(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.ProxyEnabledChanged ->
                _state.update { it.copy(proxyEnabled = intent.enabled, saved = false, error = null) }
            is SettingsIntent.ProxyHostChanged ->
                _state.update { it.copy(proxyHost = intent.host, saved = false, error = null) }
            is SettingsIntent.ProxyPortChanged ->
                _state.update { it.copy(proxyPort = intent.port.filter(Char::isDigit), saved = false, error = null) }
            is SettingsIntent.ProxyUsernameChanged ->
                _state.update { it.copy(proxyUsername = intent.username, saved = false, error = null) }
            is SettingsIntent.ProxyPasswordChanged ->
                _state.update { it.copy(proxyPassword = intent.password, saved = false, error = null) }
            SettingsIntent.SaveProxyClicked -> saveProxy()
            SettingsIntent.ClearTopicCacheClicked ->
                _state.update {
                    // Reset any previous result so the dialog opens on a clean slate; the user
                    // is about to re-confirm and shouldn't see a stale "succès" / "échec"
                    // label leaking into the new attempt.
                    it.copy(showClearTopicCacheConfirm = true, topicCacheClearResult = null)
                }
            SettingsIntent.ClearTopicCacheDismissed ->
                _state.update { it.copy(showClearTopicCacheConfirm = false) }
            SettingsIntent.ClearTopicCacheConfirmed -> clearTopicCache()
            SettingsIntent.ClearImageCacheClicked ->
                _state.update {
                    // Same clean-slate reset as the topic mirror: the dialog must not open
                    // over a stale "succès" / "échec" label from a previous attempt.
                    it.copy(showClearImageCacheConfirm = true, imageCacheClearResult = null)
                }
            SettingsIntent.ClearImageCacheDismissed ->
                _state.update { it.copy(showClearImageCacheConfirm = false) }
            SettingsIntent.ClearImageCacheConfirmed -> clearImageCache()
            is SettingsIntent.IgnoreTopicCacheChanged -> updateIgnoreTopicCache(intent.enabled)
            is SettingsIntent.DebugBoundsOverlayChanged -> updateDebugBoundsOverlay(intent.enabled)
            is SettingsIntent.FlagsGroupByCategoryChanged -> updateFlagsGroupByCategory(intent.enabled)
            is SettingsIntent.FlagsHideReadCategoriesChanged -> updateFlagsHideReadCategories(intent.enabled)
            is SettingsIntent.FlagsPerTabOverrideChanged -> updateFlagsPerTabOverride(intent.enabled)
            is SettingsIntent.ThemeModeChanged -> updateThemeMode(intent.mode)
            is SettingsIntent.AmoledEnabledChanged -> updateAmoled(intent.enabled)
            is SettingsIntent.TopicTopBarAutoHideChanged -> updateTopicTopBarAutoHide(intent.enabled)
            is SettingsIntent.TopicPageFabsChanged -> updateTopicPageFabs(intent.enabled)
            is SettingsIntent.MpUnreadBadgeChanged -> updateMpUnreadBadge(intent.enabled)
            is SettingsIntent.TopicPollsExpandedChanged -> updateTopicPollsExpanded(intent.enabled)
            is SettingsIntent.TopicSignaturesChanged -> updateTopicSignatures(intent.enabled)
            is SettingsIntent.FoldLongQuotesChanged -> updateFoldLongQuotes(intent.enabled)
            is SettingsIntent.HideSystemNavBarChanged -> updateHideSystemNavBar(intent.enabled)
            is SettingsIntent.ImmersiveBackButtonChanged -> updateImmersiveBackButton(intent.enabled)
            is SettingsIntent.ShowDtSectionChanged -> updateShowDtSection(intent.enabled)
            is SettingsIntent.ConfirmBeforePostingChanged -> updateConfirmBeforePosting(intent.enabled)
            is SettingsIntent.FlagsAutoRefreshChanged -> updateFlagsAutoRefresh(intent.enabled)
            is SettingsIntent.DisplayDensityChanged -> updateDisplayDensity(intent.density)
            is SettingsIntent.FontScaleChanged -> updateFontScale(intent.scale)
            is SettingsIntent.SetUploadProvider -> updateUploadProvider(intent.provider)
            is SettingsIntent.SetImgurClientId -> updateImgurClientId(intent.text)
            is SettingsIntent.SetEditorImageInsert -> updateEditorImageInsert(intent.mode)
        }
    }

    private fun saveProxy() {
        val snapshot = _state.value
        val port = snapshot.proxyPort.toIntOrNull()
        val invalidEnabledProxy =
            snapshot.proxyEnabled &&
                (snapshot.proxyHost.isBlank() || port !in ProxyConfig.MIN_PORT..ProxyConfig.MAX_PORT)
        if (invalidEnabledProxy) {
            _state.update { it.copy(error = SettingsError.InvalidProxy, saved = false) }
            return
        }
        _state.update { it.copy(isSaving = true, error = null, saved = false) }
        viewModelScope.launch {
            runCatching {
                userPreferencesRepository.saveProxyConfig(
                    ProxyConfig(
                        enabled = snapshot.proxyEnabled,
                        host = snapshot.proxyHost,
                        port = port,
                        username = snapshot.proxyUsername.ifBlank { null },
                        password = snapshot.proxyPassword.ifBlank { null },
                    ),
                )
            }.onSuccess {
                _state.update { it.copy(isSaving = false, saved = true, error = null) }
            }.onFailure {
                _state.update { it.copy(isSaving = false, saved = false, error = SettingsError.PersistFailed) }
            }
        }
    }

    private fun clearTopicCache() {
        // Re-entrance guard: the dialog's confirm button is not gated by state, so a
        // double-tap before recomposition would otherwise launch two concurrent clears.
        if (_state.value.isClearingTopicCache) return
        // Close the confirmation dialog upfront so the user can't double-confirm, then flip
        // `isClearingTopicCache` so the button is disabled while Room runs the transaction.
        _state.update {
            it.copy(
                showClearTopicCacheConfirm = false,
                isClearingTopicCache = true,
                topicCacheClearResult = null,
            )
        }
        viewModelScope.launch {
            runCatching { topicCacheMaintenance.clearTopicCache() }
                .onSuccess {
                    _state.update {
                        it.copy(
                            isClearingTopicCache = false,
                            topicCacheClearResult = TopicCacheClearResult.Success,
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            isClearingTopicCache = false,
                            topicCacheClearResult = TopicCacheClearResult.Failure,
                        )
                    }
                }
        }
    }

    private fun clearImageCache() {
        // Re-entrance guard, mirror of clearTopicCache() above.
        if (_state.value.isClearingImageCache) return
        // Mirror of clearTopicCache(): close the dialog upfront so the user can't
        // double-confirm, then gate the button on `isClearingImageCache` while Coil
        // wipes the memory + disk caches.
        _state.update {
            it.copy(
                showClearImageCacheConfirm = false,
                isClearingImageCache = true,
                imageCacheClearResult = null,
            )
        }
        viewModelScope.launch {
            runCatching { imageCacheMaintenance.clearImageCache() }
                .onSuccess {
                    _state.update {
                        it.copy(
                            isClearingImageCache = false,
                            imageCacheClearResult = ImageCacheClearResult.Success,
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            isClearingImageCache = false,
                            imageCacheClearResult = ImageCacheClearResult.Failure,
                        )
                    }
                }
        }
    }

    private fun updateIgnoreTopicCache(desired: Boolean) {
        val previous = _state.value.ignoreTopicCache
        // Optimistic flip — the UI reflects the intent immediately, the gate flag locks the
        // switch while DataStore is writing, and `ignoreTopicCacheTouchedLocally = true`
        // forbids the still-running startup hydration from overwriting this change with a
        // stale snapshot later. We keep the touched flag at `true` for the rest of the VM's
        // lifetime: even after a failure-revert the user has expressed an intent, so a late
        // hydration value would no longer be the source of truth.
        _state.update {
            it.copy(
                ignoreTopicCache = desired,
                isUpdatingIgnoreTopicCache = true,
                ignoreTopicCacheError = false,
                ignoreTopicCacheTouchedLocally = true,
            )
        }
        viewModelScope.launch {
            runCatching { userPreferencesRepository.setIgnoreTopicCache(desired) }
                .onSuccess {
                    // Re-affirm `ignoreTopicCache = desired` explicitly. Without this, a stale
                    // hydration that resumed *between* the optimistic flip and onSuccess could
                    // have left the field at a wrong value; reasserting here makes the final
                    // state self-consistent regardless of interleaving.
                    _state.update {
                        it.copy(
                            ignoreTopicCache = desired,
                            isUpdatingIgnoreTopicCache = false,
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            ignoreTopicCache = previous,
                            isUpdatingIgnoreTopicCache = false,
                            ignoreTopicCacheError = true,
                        )
                    }
                }
        }
    }

    // #445 — debug bounds overlay (dev only). Plain boolean, so it reuses the shared optimistic-flip
    // helper rather than the bespoke shape, like the flags toggles.
    private fun updateDebugBoundsOverlay(desired: Boolean) {
        val previous = _state.value.debugBoundsOverlay
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    debugBoundsOverlay = desired,
                    isUpdatingDebugBoundsOverlay = true,
                    debugBoundsOverlayError = false,
                    debugBoundsOverlayTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(debugBoundsOverlay = desired, isUpdatingDebugBoundsOverlay = false)
                } else {
                    state.copy(
                        debugBoundsOverlay = previous,
                        isUpdatingDebugBoundsOverlay = false,
                        debugBoundsOverlayError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setDebugBoundsOverlay,
        )
    }

    private fun updateFlagsGroupByCategory(desired: Boolean) {
        val previous = _state.value.flagsGroupByCategory
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    flagsGroupByCategory = desired,
                    isUpdatingFlagsGroupByCategory = true,
                    flagsGroupByCategoryError = false,
                    flagsGroupByCategoryTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(flagsGroupByCategory = desired, isUpdatingFlagsGroupByCategory = false)
                } else {
                    state.copy(
                        flagsGroupByCategory = previous,
                        isUpdatingFlagsGroupByCategory = false,
                        flagsGroupByCategoryError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setFlagsGroupByCategory,
        )
    }

    private fun updateFlagsHideReadCategories(desired: Boolean) {
        val previous = _state.value.flagsHideReadCategories
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    flagsHideReadCategories = desired,
                    isUpdatingFlagsHideReadCategories = true,
                    flagsHideReadCategoriesError = false,
                    flagsHideReadCategoriesTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(flagsHideReadCategories = desired, isUpdatingFlagsHideReadCategories = false)
                } else {
                    state.copy(
                        flagsHideReadCategories = previous,
                        isUpdatingFlagsHideReadCategories = false,
                        flagsHideReadCategoriesError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setFlagsHideReadCategories,
        )
    }

    private fun updateFlagsPerTabOverride(desired: Boolean) {
        val previous = _state.value.flagsPerTabOverride
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    flagsPerTabOverride = desired,
                    isUpdatingFlagsPerTabOverride = true,
                    flagsPerTabOverrideError = false,
                    flagsPerTabOverrideTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(flagsPerTabOverride = desired, isUpdatingFlagsPerTabOverride = false)
                } else {
                    state.copy(
                        flagsPerTabOverride = previous,
                        isUpdatingFlagsPerTabOverride = false,
                        flagsPerTabOverrideError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setFlagsPerTabOverride,
        )
    }

    // #286 — theme mode is an enum, so it uses the bespoke optimistic-flip shape (like
    // updateIgnoreTopicCache) rather than updateBooleanPreference. previous is captured for revert.
    private fun updateThemeMode(desired: ThemeMode) {
        val previous = _state.value.themeMode
        _state.update {
            it.copy(
                themeMode = desired,
                isUpdatingThemeMode = true,
                themeModeError = false,
                themeModeTouchedLocally = true,
            )
        }
        viewModelScope.launch {
            runCatching { userPreferencesRepository.setThemeMode(desired) }
                .onSuccess {
                    _state.update { it.copy(themeMode = desired, isUpdatingThemeMode = false) }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            themeMode = previous,
                            isUpdatingThemeMode = false,
                            themeModeError = true,
                        )
                    }
                }
        }
    }

    // #287 — display density is an enum, so it uses the bespoke optimistic-flip shape (like
    // updateThemeMode) rather than updateBooleanPreference. previous is captured for revert.
    private fun updateDisplayDensity(desired: DisplayDensity) {
        val previous = _state.value.displayDensity
        _state.update {
            it.copy(
                displayDensity = desired,
                isUpdatingDisplayDensity = true,
                displayDensityError = false,
                displayDensityTouchedLocally = true,
            )
        }
        viewModelScope.launch {
            runCatching { userPreferencesRepository.setDisplayDensity(desired) }
                .onSuccess {
                    _state.update { it.copy(displayDensity = desired, isUpdatingDisplayDensity = false) }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            displayDensity = previous,
                            isUpdatingDisplayDensity = false,
                            displayDensityError = true,
                        )
                    }
                }
        }
    }

    // #287 — font scale is an enum too; same bespoke optimistic-flip shape as updateDisplayDensity.
    private fun updateFontScale(desired: FontScalePreference) {
        val previous = _state.value.fontScale
        _state.update {
            it.copy(
                fontScale = desired,
                isUpdatingFontScale = true,
                fontScaleError = false,
                fontScaleTouchedLocally = true,
            )
        }
        viewModelScope.launch {
            runCatching { userPreferencesRepository.setFontScale(desired) }
                .onSuccess {
                    _state.update { it.copy(fontScale = desired, isUpdatingFontScale = false) }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            fontScale = previous,
                            isUpdatingFontScale = false,
                            fontScaleError = true,
                        )
                    }
                }
        }
    }

    // #459 — upload provider is an enum, same bespoke optimistic-flip shape as updateThemeMode.
    private fun updateUploadProvider(desired: UploadProviderId) {
        val previous = _state.value.uploadProvider
        _state.update {
            it.copy(
                uploadProvider = desired,
                isUpdatingUploadProvider = true,
                uploadProviderError = false,
                uploadProviderTouchedLocally = true,
            )
        }
        viewModelScope.launch {
            runCatching { userPreferencesRepository.setUploadProvider(desired) }
                .onSuccess {
                    _state.update { it.copy(uploadProvider = desired, isUpdatingUploadProvider = false) }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            uploadProvider = previous,
                            isUpdatingUploadProvider = false,
                            uploadProviderError = true,
                        )
                    }
                }
        }
    }

    // #459 PR-images follow-up — editor image insert mode is an enum, same optimistic-flip shape.
    private fun updateEditorImageInsert(desired: EditorImageInsert) {
        val previous = _state.value.editorImageInsert
        _state.update {
            it.copy(
                editorImageInsert = desired,
                isUpdatingEditorImageInsert = true,
                editorImageInsertError = false,
                editorImageInsertTouchedLocally = true,
            )
        }
        viewModelScope.launch {
            runCatching { userPreferencesRepository.setEditorImageInsert(desired) }
                .onSuccess {
                    _state.update { it.copy(editorImageInsert = desired, isUpdatingEditorImageInsert = false) }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            editorImageInsert = previous,
                            isUpdatingEditorImageInsert = false,
                            editorImageInsertError = true,
                        )
                    }
                }
        }
    }

    // #459 — imgur Client-ID is a free-text preference persisted on each change (no save button).
    // The optimistic value is shown immediately and the touched-locally guard blocks a late
    // hydration from clobbering in-progress typing; a persist failure raises the error flag without
    // reverting the typed text (it would be hostile to wipe what the user just typed).
    // Persist-on-keystroke writes are serialised : each keystroke cancels the previous in-flight write
    // so a slower/older write (or its failure) can never land after a newer one and strand a stale
    // Client-ID or a spurious error flag (review #459). CancellationException is rethrown, not treated
    // as a persistence failure.
    private var imgurClientIdJob: Job? = null

    private fun updateImgurClientId(text: String) {
        _state.update {
            it.copy(
                imgurClientId = text,
                imgurClientIdError = false,
                imgurClientIdTouchedLocally = true,
            )
        }
        imgurClientIdJob?.cancel()
        imgurClientIdJob = viewModelScope.launch {
            runCatching { userPreferencesRepository.setImgurClientId(text) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _state.update { it.copy(imgurClientIdError = true) }
                }
        }
    }

    private fun updateAmoled(desired: Boolean) {
        val previous = _state.value.amoledEnabled
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    amoledEnabled = desired,
                    isUpdatingAmoled = true,
                    amoledError = false,
                    amoledTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(amoledEnabled = desired, isUpdatingAmoled = false)
                } else {
                    state.copy(amoledEnabled = previous, isUpdatingAmoled = false, amoledError = true)
                }
            },
            persist = userPreferencesRepository::setAmoledEnabled,
        )
    }

    private fun updateTopicTopBarAutoHide(desired: Boolean) {
        val previous = _state.value.topicTopBarAutoHide
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    topicTopBarAutoHide = desired,
                    isUpdatingTopicTopBarAutoHide = true,
                    topicTopBarAutoHideError = false,
                    topicTopBarAutoHideTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(topicTopBarAutoHide = desired, isUpdatingTopicTopBarAutoHide = false)
                } else {
                    state.copy(
                        topicTopBarAutoHide = previous,
                        isUpdatingTopicTopBarAutoHide = false,
                        topicTopBarAutoHideError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setTopicTopBarAutoHide,
        )
    }

    private fun updateTopicPageFabs(desired: Boolean) {
        val previous = _state.value.topicPageFabs
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    topicPageFabs = desired,
                    isUpdatingTopicPageFabs = true,
                    topicPageFabsError = false,
                    topicPageFabsTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(topicPageFabs = desired, isUpdatingTopicPageFabs = false)
                } else {
                    state.copy(
                        topicPageFabs = previous,
                        isUpdatingTopicPageFabs = false,
                        topicPageFabsError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setTopicPageFabs,
        )
    }

    private fun updateTopicPollsExpanded(desired: Boolean) {
        val previous = _state.value.topicPollsExpanded
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    topicPollsExpanded = desired,
                    isUpdatingTopicPollsExpanded = true,
                    topicPollsExpandedError = false,
                    topicPollsExpandedTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(topicPollsExpanded = desired, isUpdatingTopicPollsExpanded = false)
                } else {
                    state.copy(
                        topicPollsExpanded = previous,
                        isUpdatingTopicPollsExpanded = false,
                        topicPollsExpandedError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setTopicPollsExpanded,
        )
    }

    private fun updateTopicSignatures(desired: Boolean) {
        val previous = _state.value.topicSignatures
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    topicSignatures = desired,
                    isUpdatingTopicSignatures = true,
                    topicSignaturesError = false,
                    topicSignaturesTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(topicSignatures = desired, isUpdatingTopicSignatures = false)
                } else {
                    state.copy(
                        topicSignatures = previous,
                        isUpdatingTopicSignatures = false,
                        topicSignaturesError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setTopicSignatures,
        )
    }

    private fun updateFoldLongQuotes(desired: Boolean) {
        val previous = _state.value.foldLongQuotes
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    foldLongQuotes = desired,
                    isUpdatingFoldLongQuotes = true,
                    foldLongQuotesError = false,
                    foldLongQuotesTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(foldLongQuotes = desired, isUpdatingFoldLongQuotes = false)
                } else {
                    state.copy(
                        foldLongQuotes = previous,
                        isUpdatingFoldLongQuotes = false,
                        foldLongQuotesError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setFoldLongQuotes,
        )
    }

    private fun updateHideSystemNavBar(desired: Boolean) {
        val previous = _state.value.hideSystemNavBar
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    hideSystemNavBar = desired,
                    isUpdatingHideSystemNavBar = true,
                    hideSystemNavBarError = false,
                    hideSystemNavBarTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(hideSystemNavBar = desired, isUpdatingHideSystemNavBar = false)
                } else {
                    state.copy(
                        hideSystemNavBar = previous,
                        isUpdatingHideSystemNavBar = false,
                        hideSystemNavBarError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setHideSystemNavBar,
        )
    }

    private fun updateImmersiveBackButton(desired: Boolean) {
        val previous = _state.value.immersiveBackButton
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    immersiveBackButton = desired,
                    isUpdatingImmersiveBackButton = true,
                    immersiveBackButtonError = false,
                    immersiveBackButtonTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(immersiveBackButton = desired, isUpdatingImmersiveBackButton = false)
                } else {
                    state.copy(
                        immersiveBackButton = previous,
                        isUpdatingImmersiveBackButton = false,
                        immersiveBackButtonError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setImmersiveBackButton,
        )
    }

    private fun updateMpUnreadBadge(desired: Boolean) {
        val previous = _state.value.mpUnreadBadge
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    mpUnreadBadge = desired,
                    isUpdatingMpUnreadBadge = true,
                    mpUnreadBadgeError = false,
                    mpUnreadBadgeTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(mpUnreadBadge = desired, isUpdatingMpUnreadBadge = false)
                } else {
                    state.copy(
                        mpUnreadBadge = previous,
                        isUpdatingMpUnreadBadge = false,
                        mpUnreadBadgeError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setMpUnreadBadge,
        )
    }

    private fun updateShowDtSection(desired: Boolean) {
        val previous = _state.value.showDtSection
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    showDtSection = desired,
                    isUpdatingShowDtSection = true,
                    showDtSectionError = false,
                    showDtSectionTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(showDtSection = desired, isUpdatingShowDtSection = false)
                } else {
                    state.copy(
                        showDtSection = previous,
                        isUpdatingShowDtSection = false,
                        showDtSectionError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setShowDtSection,
        )
    }

    private fun updateFlagsAutoRefresh(desired: Boolean) {
        val previous = _state.value.flagsAutoRefresh
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    flagsAutoRefresh = desired,
                    isUpdatingFlagsAutoRefresh = true,
                    flagsAutoRefreshError = false,
                    flagsAutoRefreshTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(flagsAutoRefresh = desired, isUpdatingFlagsAutoRefresh = false)
                } else {
                    state.copy(
                        flagsAutoRefresh = previous,
                        isUpdatingFlagsAutoRefresh = false,
                        flagsAutoRefreshError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setFlagsAutoRefresh,
        )
    }

    private fun updateConfirmBeforePosting(desired: Boolean) {
        val previous = _state.value.confirmBeforePosting
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    confirmBeforePosting = desired,
                    isUpdatingConfirmBeforePosting = true,
                    confirmBeforePostingError = false,
                    confirmBeforePostingTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(confirmBeforePosting = desired, isUpdatingConfirmBeforePosting = false)
                } else {
                    state.copy(
                        confirmBeforePosting = previous,
                        isUpdatingConfirmBeforePosting = false,
                        confirmBeforePostingError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setConfirmBeforePosting,
        )
    }

    /**
     * Shared optimistic-flip machinery for a persisted boolean preference (the Drapeaux view
     * toggles). Flips the field immediately via [optimistic] (which also sets the `*TouchedLocally`
     * guard so a late `init` hydration can't clobber it), persists [desired] on a background
     * coroutine, then reconciles the final state from the persist [Result] via [onSettled] (success
     * re-affirms the value, failure reverts and raises the error flag — both captured in the
     * caller's closures). Mirrors the bespoke `updateIgnoreTopicCache` contract, factored because
     * the two new toggles are identical bar their state fields.
     */
    private fun updateBooleanPreference(
        desired: Boolean,
        optimistic: (SettingsState) -> SettingsState,
        onSettled: (SettingsState, Result<Unit>) -> SettingsState,
        persist: suspend (Boolean) -> Unit,
    ) {
        _state.update(optimistic)
        viewModelScope.launch {
            val result = runCatching { persist(desired) }
            _state.update { onSettled(it, result) }
        }
    }

    private fun SettingsState.copyFromProxy(config: ProxyConfig): SettingsState = copy(
        proxyEnabled = config.enabled,
        proxyHost = config.host,
        proxyPort = config.port?.toString().orEmpty(),
        proxyUsername = config.username.orEmpty(),
        proxyPassword = config.password.orEmpty(),
    )
}
