package fr.forumhfr.redface2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.cache.ImageCacheMaintenance
import fr.forumhfr.redface2.core.domain.cache.TopicCacheMaintenance
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageContentCache
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageContentCacheException
import fr.forumhfr.redface2.core.domain.preferences.AccentColor
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
import fr.forumhfr.redface2.core.domain.preferences.PostImageMaxWidth
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.SmileyPickerDecoration
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.WritingSurfacePreset
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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
    private val privateMessageContentCache: PrivateMessageContentCache,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        // #788 — continuous hydration: every persisted preference is COLLECTED for the VM's
        // whole life, not read once. Each Settings destination scopes its own SettingsViewModel
        // to its NavBackStackEntry, so a value written from a sub-page instance (or any other
        // writer) must also land in the retained root instance — the previous one-shot
        // `.first()` + touched-locally latch kept it stale forever. Each `.copy(...)` keeps the
        // other fields intact — never replace the whole state with a partial config, that would
        // wipe the maintenance fields.
        viewModelScope.launch {
            // The proxy stays a point-in-time read: it is an explicit-save FORM (host / port /
            // credentials typed locally), so a continuous re-sync would clobber in-progress edits.
            val config = userPreferencesRepository.observeProxyConfig().first()
            _state.update { it.copyFromProxy(config) }
        }
        observePreference(
            flow = userPreferencesRepository.observeIgnoreTopicCache(),
            isLocked = { it.isUpdatingIgnoreTopicCache },
            apply = { state, value -> state.copy(ignoreTopicCache = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeDebugBoundsOverlay(),
            isLocked = { it.isUpdatingDebugBoundsOverlay },
            apply = { state, value -> state.copy(debugBoundsOverlay = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeFlagsGroupByCategory(),
            isLocked = { it.isUpdatingFlagsGroupByCategory },
            apply = { state, value -> state.copy(flagsGroupByCategory = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeFlagsHideReadCategories(),
            isLocked = { it.isUpdatingFlagsHideReadCategories },
            apply = { state, value -> state.copy(flagsHideReadCategories = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeFlagsPerTabOverride(),
            isLocked = { it.isUpdatingFlagsPerTabOverride },
            apply = { state, value -> state.copy(flagsPerTabOverride = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeThemeMode(),
            isLocked = { it.isUpdatingThemeMode },
            apply = { state, value -> state.copy(themeMode = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeAmoledEnabled(),
            isLocked = { it.isUpdatingAmoled },
            apply = { state, value -> state.copy(amoledEnabled = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeTopicTopBarAutoHide(),
            isLocked = { it.isUpdatingTopicTopBarAutoHide },
            apply = { state, value -> state.copy(topicTopBarAutoHide = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeTopicPageFabs(),
            isLocked = { it.isUpdatingTopicPageFabs },
            apply = { state, value -> state.copy(topicPageFabs = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeMpUnreadBadge(),
            isLocked = { it.isUpdatingMpUnreadBadge },
            apply = { state, value -> state.copy(mpUnreadBadge = value) },
        )
        observePreference(
            flow = privateMessageContentCache.observeEnabled(),
            isLocked = { it.isUpdatingPrivateMessageContentCache },
            apply = { state, value ->
                state.copy(
                    privateMessageContentCacheEnabled = value,
                    showDisablePrivateMessageContentCacheConfirm =
                        state.showDisablePrivateMessageContentCacheConfirm && value,
                )
            },
        )
        observePreference(
            flow = privateMessageContentCache.observePurgePending(),
            isLocked = { it.isUpdatingPrivateMessageContentCache },
            apply = { state, pending ->
                state.copy(
                    privateMessageContentCachePurgePending = pending,
                    privateMessageContentCachePurgeError = pending,
                )
            },
        )
        observePreference(
            flow = userPreferencesRepository.observeTopicPollsExpanded(),
            isLocked = { it.isUpdatingTopicPollsExpanded },
            apply = { state, value -> state.copy(topicPollsExpanded = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeTopicUnansweredPollsExpanded(),
            isLocked = { it.isUpdatingTopicUnansweredPollsExpanded },
            apply = { state, value -> state.copy(topicUnansweredPollsExpanded = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeTopicSignatures(),
            isLocked = { it.isUpdatingTopicSignatures },
            apply = { state, value -> state.copy(topicSignatures = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeFoldLongQuotes(),
            isLocked = { it.isUpdatingFoldLongQuotes },
            apply = { state, value -> state.copy(foldLongQuotes = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeTopicFullWidthPosts(),
            isLocked = { it.isUpdatingFullWidthPosts },
            apply = { state, value -> state.copy(fullWidthPosts = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeTopicEgoQuoteEnabled(),
            isLocked = { it.isUpdatingEgoQuote },
            apply = { state, value -> state.copy(egoQuoteEnabled = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeTopicEgoPostEnabled(),
            isLocked = { it.isUpdatingEgoPost },
            apply = { state, value -> state.copy(egoPostEnabled = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeShowScrollbar(),
            isLocked = { it.isUpdatingShowScrollbar },
            apply = { state, value -> state.copy(showScrollbar = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeNavBarLabels(),
            isLocked = { it.isUpdatingNavBarLabels },
            apply = { state, value -> state.copy(navBarLabels = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeFunnyEmptyState(),
            isLocked = { it.isUpdatingFunnyEmptyState },
            apply = { state, value -> state.copy(funnyEmptyState = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeHideSystemNavBar(),
            isLocked = { it.isUpdatingHideSystemNavBar },
            apply = { state, value -> state.copy(hideSystemNavBar = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeImmersiveBackButton(),
            isLocked = { it.isUpdatingImmersiveBackButton },
            apply = { state, value -> state.copy(immersiveBackButton = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeImmersiveNavBarReveal(),
            isLocked = { it.isUpdatingImmersiveNavBarReveal },
            apply = { state, value -> state.copy(immersiveNavBarReveal = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeAccentColor(),
            isLocked = { it.isUpdatingAccentColor },
            apply = { state, value -> state.copy(accentColor = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeAlwaysAskLinkApp(),
            isLocked = { it.isUpdatingAlwaysAskLinkApp },
            apply = { state, value -> state.copy(alwaysAskLinkApp = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeConfirmBeforePosting(),
            isLocked = { it.isUpdatingConfirmBeforePosting },
            apply = { state, value -> state.copy(confirmBeforePosting = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeQuoteCardsEnabled(),
            isLocked = { it.isUpdatingQuoteCardsEnabled },
            apply = { state, value -> state.copy(quoteCardsEnabled = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeShowDtSection(),
            isLocked = { it.isUpdatingShowDtSection },
            apply = { state, value -> state.copy(showDtSection = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeSyncPrivateMessagesWriteEnabled(),
            isLocked = { it.isUpdatingSyncPrivateMessagesWriteEnabled },
            apply = { state, value -> state.copy(syncPrivateMessagesWriteEnabled = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeFlagsAutoRefresh(),
            isLocked = { it.isUpdatingFlagsAutoRefresh },
            apply = { state, value -> state.copy(flagsAutoRefresh = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeDisplayDensity(),
            isLocked = { it.isUpdatingDisplayDensity },
            apply = { state, value -> state.copy(displayDensity = value) },
        )
        observePreference(
            flow = userPreferencesRepository.observeFontScale(),
            isLocked = { it.isUpdatingFontScale },
            apply = { state, value -> state.copy(fontScale = value) },
        )
        // #973 — block-GIF display profile (enum), same collection shape as the display presets.
        observePreference(
            flow = userPreferencesRepository.observeMediaDisplayProfile(),
            isLocked = { it.isUpdatingMediaDisplayProfile },
            apply = { state, value -> state.copy(mediaDisplayProfile = value) },
        )
        // #991 — largeur maximale fImage des images de contenu (enum), même forme de collecte.
        observePreference(
            flow = userPreferencesRepository.observePostImageMaxWidth(),
            isLocked = { it.isUpdatingPostImageMaxWidth },
            apply = { state, value -> state.copy(postImageMaxWidth = value) },
        )
        // #989 — délimiteur du picker de smileys (enum), même forme de collecte.
        observePreference(
            flow = userPreferencesRepository.observeSmileyPickerDecoration(),
            isLocked = { it.isUpdatingSmileyPickerDecoration },
            apply = { state, value -> state.copy(smileyPickerDecoration = value) },
        )
        // #459 — Hébergeur d'images : provider (enum) + imgur Client-ID (text).
        observePreference(
            flow = userPreferencesRepository.observeUploadProvider(),
            isLocked = { it.isUpdatingUploadProvider },
            apply = { state, value -> state.copy(uploadProvider = value) },
        )
        // #459 / #788 — the imgur Client-ID persists on each keystroke, so its re-sync guard is
        // the TOUCH latch, not an in-flight flag: once the user typed in THIS instance, an echoed
        // (or concurrent) emission must never rewrite the field mid-typing. An untouched instance
        // still follows external writes like every other pref.
        observePreference(
            flow = userPreferencesRepository.observeImgurClientId(),
            isLocked = { it.imgurClientIdTouchedLocally },
            apply = { state, value -> state.copy(imgurClientId = value) },
        )
        // #459 PR-images follow-up — editor image insert mode (enum), same collection shape.
        observePreference(
            flow = userPreferencesRepository.observeEditorImageInsert(),
            isLocked = { it.isUpdatingEditorImageInsert },
            apply = { state, value -> state.copy(editorImageInsert = value) },
        )
        // #806 — writing-surface preset (enum), same collection shape.
        observePreference(
            flow = userPreferencesRepository.observeWritingSurfacePreset(),
            isLocked = { it.isUpdatingWritingSurfacePreset },
            apply = { state, value -> state.copy(writingSurfacePreset = value) },
        )
    }

    /**
     * Long-lived re-sync of a persisted preference into [_state] (#788 gate rule: a ViewModel
     * that caches persisted data re-syncs continuously, not once). The repository Flows are
     * `distinctUntilChanged`, so this collect only wakes up on real changes. [isLocked] guards
     * the only window where an emission must be ignored: an optimistic write in flight
     * (`isUpdating*`) — on success DataStore re-emits the desired value (no-op convergence),
     * on failure nothing is emitted and the local revert stands. The imgur Client-ID passes its
     * touch latch instead (see the init call site).
     */
    private fun <T> observePreference(
        flow: Flow<T>,
        isLocked: (SettingsState) -> Boolean,
        apply: (SettingsState, T) -> SettingsState,
    ) {
        viewModelScope.launch {
            flow.collect { value ->
                _state.update { current -> if (isLocked(current)) current else apply(current, value) }
            }
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
            is SettingsIntent.AccentColorChanged -> updateAccentColor(intent.color)
            is SettingsIntent.AlwaysAskLinkAppChanged -> updateAlwaysAskLinkApp(intent.enabled)
            is SettingsIntent.TopicTopBarAutoHideChanged -> updateTopicTopBarAutoHide(intent.enabled)
            is SettingsIntent.TopicPageFabsChanged -> updateTopicPageFabs(intent.enabled)
            is SettingsIntent.MpUnreadBadgeChanged -> updateMpUnreadBadge(intent.enabled)
            is SettingsIntent.TopicPollsExpandedChanged -> updateTopicPollsExpanded(intent.enabled)
            is SettingsIntent.TopicUnansweredPollsExpandedChanged ->
                updateTopicUnansweredPollsExpanded(intent.enabled)
            is SettingsIntent.TopicSignaturesChanged -> updateTopicSignatures(intent.enabled)
            is SettingsIntent.FoldLongQuotesChanged -> updateFoldLongQuotes(intent.enabled)
            is SettingsIntent.FullWidthPostsChanged -> updateFullWidthPosts(intent.enabled)
            is SettingsIntent.EgoQuoteChanged -> updateEgoQuote(intent.enabled)
            is SettingsIntent.EgoPostChanged -> updateEgoPost(intent.enabled)
            is SettingsIntent.ShowScrollbarChanged -> updateShowScrollbar(intent.enabled)
            is SettingsIntent.NavBarLabelsChanged -> updateNavBarLabels(intent.enabled)
            is SettingsIntent.FunnyEmptyStateChanged -> updateFunnyEmptyState(intent.enabled)
            is SettingsIntent.HideSystemNavBarChanged -> updateHideSystemNavBar(intent.enabled)
            is SettingsIntent.ImmersiveBackButtonChanged -> updateImmersiveBackButton(intent.enabled)
            is SettingsIntent.ImmersiveNavBarRevealChanged -> updateImmersiveNavBarReveal(intent.mode)
            is SettingsIntent.ShowDtSectionChanged -> updateShowDtSection(intent.enabled)
            is SettingsIntent.SyncPrivateMessagesWriteEnabledChanged ->
                updateSyncPrivateMessagesWriteEnabled(intent.enabled)
            is SettingsIntent.PrivateMessageContentCacheChanged ->
                updatePrivateMessageContentCache(intent.enabled)
            SettingsIntent.DisablePrivateMessageContentCacheConfirmed ->
                disablePrivateMessageContentCache()
            SettingsIntent.DisablePrivateMessageContentCacheDismissed ->
                _state.update { it.copy(showDisablePrivateMessageContentCacheConfirm = false) }
            SettingsIntent.RetryPrivateMessageContentCachePurge ->
                retryPrivateMessageContentCachePurge()
            is SettingsIntent.ConfirmBeforePostingChanged -> updateConfirmBeforePosting(intent.enabled)
            is SettingsIntent.QuoteCardsEnabledChanged -> updateQuoteCardsEnabled(intent.enabled)
            is SettingsIntent.FlagsAutoRefreshChanged -> updateFlagsAutoRefresh(intent.enabled)
            is SettingsIntent.DisplayDensityChanged -> updateDisplayDensity(intent.density)
            is SettingsIntent.FontScaleChanged -> updateFontScale(intent.scale)
            is SettingsIntent.MediaDisplayProfileChanged -> updateMediaDisplayProfile(intent.profile)
            is SettingsIntent.PostImageMaxWidthChanged -> updatePostImageMaxWidth(intent.width)
            is SettingsIntent.SmileyPickerDecorationChanged ->
                updateSmileyPickerDecoration(intent.decoration)
            is SettingsIntent.SetUploadProvider -> updateUploadProvider(intent.provider)
            is SettingsIntent.SetImgurClientId -> updateImgurClientId(intent.text)
            is SettingsIntent.SetEditorImageInsert -> updateEditorImageInsert(intent.mode)
            is SettingsIntent.SetWritingSurfacePreset -> updateWritingSurfacePreset(intent.preset)
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
        // Optimistic flip — the UI reflects the intent immediately and the gate flag locks the
        // switch while DataStore is writing. `isUpdatingIgnoreTopicCache = true` is also what
        // blanks the continuous #788 re-sync for the write's duration, so an emission of the
        // OLD value cannot undo the flip mid-flight. `ignoreTopicCacheTouchedLocally` stays a
        // write marker only — it is no longer consulted by the hydration guard (#788), so an
        // external write made later (e.g. from another Settings destination's VM) re-syncs here.
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
                    // Re-affirm `ignoreTopicCache = desired` explicitly. The #788 re-sync is
                    // gated on `isUpdating*` while the write is in flight, but a conflated
                    // emission of the OLD value could theoretically land right after the gate
                    // drops; reasserting here makes the final state self-consistent regardless
                    // of interleaving (DataStore then re-emits `desired` — a no-op).
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

    // #973 — block-GIF display profile is an enum too; same bespoke optimistic-flip shape as
    // updateDisplayDensity. previous is captured for revert.
    private fun updateMediaDisplayProfile(desired: MediaDisplayProfile) {
        val previous = _state.value.mediaDisplayProfile
        _state.update {
            it.copy(
                mediaDisplayProfile = desired,
                isUpdatingMediaDisplayProfile = true,
                mediaDisplayProfileError = false,
                mediaDisplayProfileTouchedLocally = true,
            )
        }
        viewModelScope.launch {
            runCatching { userPreferencesRepository.setMediaDisplayProfile(desired) }
                .onSuccess {
                    _state.update { it.copy(mediaDisplayProfile = desired, isUpdatingMediaDisplayProfile = false) }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            mediaDisplayProfile = previous,
                            isUpdatingMediaDisplayProfile = false,
                            mediaDisplayProfileError = true,
                        )
                    }
                }
        }
    }

    // #991 — content-image max width is an enum too; same optimistic-flip shape as GIF profile.
    private fun updatePostImageMaxWidth(desired: PostImageMaxWidth) {
        val previous = _state.value.postImageMaxWidth
        _state.update {
            it.copy(
                postImageMaxWidth = desired,
                isUpdatingPostImageMaxWidth = true,
                postImageMaxWidthError = false,
                postImageMaxWidthTouchedLocally = true,
            )
        }
        viewModelScope.launch {
            runCatching { userPreferencesRepository.setPostImageMaxWidth(desired) }
                .onSuccess {
                    _state.update {
                        it.copy(postImageMaxWidth = desired, isUpdatingPostImageMaxWidth = false)
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            postImageMaxWidth = previous,
                            isUpdatingPostImageMaxWidth = false,
                            postImageMaxWidthError = true,
                        )
                    }
                }
        }
    }

    /** #989 — délimiteur du picker : même forme optimiste + rollback que le profil GIF (#973). */
    private fun updateSmileyPickerDecoration(desired: SmileyPickerDecoration) {
        val previous = _state.value.smileyPickerDecoration
        _state.update {
            it.copy(
                smileyPickerDecoration = desired,
                isUpdatingSmileyPickerDecoration = true,
                smileyPickerDecorationError = false,
                smileyPickerDecorationTouchedLocally = true,
            )
        }
        viewModelScope.launch {
            runCatching { userPreferencesRepository.setSmileyPickerDecoration(desired) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            smileyPickerDecoration = desired,
                            isUpdatingSmileyPickerDecoration = false,
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            smileyPickerDecoration = previous,
                            isUpdatingSmileyPickerDecoration = false,
                            smileyPickerDecorationError = true,
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

    // #806 — writing-surface preset is an enum, same optimistic-flip shape as editorImageInsert.
    // The #788 continuous re-sync (observePreference in init) keeps every SettingsViewModel
    // instance aligned; `isUpdatingWritingSurfacePreset` gates the re-sync during the write.
    private fun updateWritingSurfacePreset(desired: WritingSurfacePreset) {
        val previous = _state.value.writingSurfacePreset
        _state.update {
            it.copy(
                writingSurfacePreset = desired,
                isUpdatingWritingSurfacePreset = true,
                writingSurfacePresetError = false,
                writingSurfacePresetTouchedLocally = true,
            )
        }
        viewModelScope.launch {
            runCatching { userPreferencesRepository.setWritingSurfacePreset(desired) }
                .onSuccess {
                    _state.update {
                        it.copy(writingSurfacePreset = desired, isUpdatingWritingSurfacePreset = false)
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            writingSurfacePreset = previous,
                            isUpdatingWritingSurfacePreset = false,
                            writingSurfacePresetError = true,
                        )
                    }
                }
        }
    }

    // #459 — imgur Client-ID is a free-text preference persisted on each change (no save button).
    // The optimistic value is shown immediately and the touched-locally latch permanently opts
    // this instance out of the #788 re-sync (an echoed emission must not rewrite the field while
    // the user is typing); a persist failure raises the error flag without reverting the typed
    // text (it would be hostile to wipe what the user just typed).
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

    private fun updateAlwaysAskLinkApp(desired: Boolean) {
        val previous = _state.value.alwaysAskLinkApp
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    alwaysAskLinkApp = desired,
                    isUpdatingAlwaysAskLinkApp = true,
                    alwaysAskLinkAppError = false,
                    alwaysAskLinkAppTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(alwaysAskLinkApp = desired, isUpdatingAlwaysAskLinkApp = false)
                } else {
                    state.copy(
                        alwaysAskLinkApp = previous,
                        isUpdatingAlwaysAskLinkApp = false,
                        alwaysAskLinkAppError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setAlwaysAskLinkApp,
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

    private fun updateTopicUnansweredPollsExpanded(desired: Boolean) {
        val previous = _state.value.topicUnansweredPollsExpanded
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    topicUnansweredPollsExpanded = desired,
                    isUpdatingTopicUnansweredPollsExpanded = true,
                    topicUnansweredPollsExpandedError = false,
                    topicUnansweredPollsExpandedTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(
                        topicUnansweredPollsExpanded = desired,
                        isUpdatingTopicUnansweredPollsExpanded = false,
                    )
                } else {
                    state.copy(
                        topicUnansweredPollsExpanded = previous,
                        isUpdatingTopicUnansweredPollsExpanded = false,
                        topicUnansweredPollsExpandedError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setTopicUnansweredPollsExpanded,
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

    private fun updateFullWidthPosts(desired: Boolean) {
        val previous = _state.value.fullWidthPosts
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    fullWidthPosts = desired,
                    isUpdatingFullWidthPosts = true,
                    fullWidthPostsError = false,
                    fullWidthPostsTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(fullWidthPosts = desired, isUpdatingFullWidthPosts = false)
                } else {
                    state.copy(
                        fullWidthPosts = previous,
                        isUpdatingFullWidthPosts = false,
                        fullWidthPostsError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setTopicFullWidthPosts,
        )
    }

    private fun updateEgoQuote(desired: Boolean) {
        val previous = _state.value.egoQuoteEnabled
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    egoQuoteEnabled = desired,
                    isUpdatingEgoQuote = true,
                    egoQuoteError = false,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(egoQuoteEnabled = desired, isUpdatingEgoQuote = false)
                } else {
                    state.copy(
                        egoQuoteEnabled = previous,
                        isUpdatingEgoQuote = false,
                        egoQuoteError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setTopicEgoQuoteEnabled,
        )
    }

    private fun updateEgoPost(desired: Boolean) {
        val previous = _state.value.egoPostEnabled
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    egoPostEnabled = desired,
                    isUpdatingEgoPost = true,
                    egoPostError = false,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(egoPostEnabled = desired, isUpdatingEgoPost = false)
                } else {
                    state.copy(
                        egoPostEnabled = previous,
                        isUpdatingEgoPost = false,
                        egoPostError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setTopicEgoPostEnabled,
        )
    }

    private fun updateShowScrollbar(desired: Boolean) {
        val previous = _state.value.showScrollbar
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    showScrollbar = desired,
                    isUpdatingShowScrollbar = true,
                    showScrollbarError = false,
                    showScrollbarTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(showScrollbar = desired, isUpdatingShowScrollbar = false)
                } else {
                    state.copy(
                        showScrollbar = previous,
                        isUpdatingShowScrollbar = false,
                        showScrollbarError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setShowScrollbar,
        )
    }

    private fun updateNavBarLabels(desired: Boolean) {
        val previous = _state.value.navBarLabels
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    navBarLabels = desired,
                    isUpdatingNavBarLabels = true,
                    navBarLabelsError = false,
                    navBarLabelsTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(navBarLabels = desired, isUpdatingNavBarLabels = false)
                } else {
                    state.copy(
                        navBarLabels = previous,
                        isUpdatingNavBarLabels = false,
                        navBarLabelsError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setNavBarLabels,
        )
    }

    private fun updateFunnyEmptyState(desired: Boolean) {
        val previous = _state.value.funnyEmptyState
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    funnyEmptyState = desired,
                    isUpdatingFunnyEmptyState = true,
                    funnyEmptyStateError = false,
                    funnyEmptyStateTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(funnyEmptyState = desired, isUpdatingFunnyEmptyState = false)
                } else {
                    state.copy(
                        funnyEmptyState = previous,
                        isUpdatingFunnyEmptyState = false,
                        funnyEmptyStateError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setFunnyEmptyState,
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

    // #518 follow-up — enum preference; same bespoke optimistic-flip shape as updateDisplayDensity.
    private fun updateImmersiveNavBarReveal(desired: ImmersiveNavBarReveal) {
        val previous = _state.value.immersiveNavBarReveal
        _state.update {
            it.copy(
                immersiveNavBarReveal = desired,
                isUpdatingImmersiveNavBarReveal = true,
                immersiveNavBarRevealError = false,
                immersiveNavBarRevealTouchedLocally = true,
            )
        }
        viewModelScope.launch {
            runCatching { userPreferencesRepository.setImmersiveNavBarReveal(desired) }
                .onSuccess {
                    _state.update {
                        it.copy(immersiveNavBarReveal = desired, isUpdatingImmersiveNavBarReveal = false)
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            immersiveNavBarReveal = previous,
                            isUpdatingImmersiveNavBarReveal = false,
                            immersiveNavBarRevealError = true,
                        )
                    }
                }
        }
    }

    // TU 2788511 — enum preference; same bespoke optimistic-flip shape as updateImmersiveNavBarReveal.
    private fun updateAccentColor(desired: AccentColor) {
        val previous = _state.value.accentColor
        _state.update {
            it.copy(
                accentColor = desired,
                isUpdatingAccentColor = true,
                accentColorError = false,
                accentColorTouchedLocally = true,
            )
        }
        viewModelScope.launch {
            runCatching { userPreferencesRepository.setAccentColor(desired) }
                .onSuccess {
                    _state.update {
                        it.copy(accentColor = desired, isUpdatingAccentColor = false)
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            accentColor = previous,
                            isUpdatingAccentColor = false,
                            accentColorError = true,
                        )
                    }
                }
        }
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

    private fun updateSyncPrivateMessagesWriteEnabled(desired: Boolean) {
        val previous = _state.value.syncPrivateMessagesWriteEnabled
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    syncPrivateMessagesWriteEnabled = desired,
                    isUpdatingSyncPrivateMessagesWriteEnabled = true,
                    syncPrivateMessagesWriteEnabledError = false,
                    syncPrivateMessagesWriteEnabledTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(
                        syncPrivateMessagesWriteEnabled = desired,
                        isUpdatingSyncPrivateMessagesWriteEnabled = false,
                    )
                } else {
                    state.copy(
                        syncPrivateMessagesWriteEnabled = previous,
                        isUpdatingSyncPrivateMessagesWriteEnabled = false,
                        syncPrivateMessagesWriteEnabledError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setSyncPrivateMessagesWriteEnabled,
        )
    }

    private fun updatePrivateMessageContentCache(desired: Boolean) {
        if (!desired) {
            _state.update { state ->
                state.copy(
                    showDisablePrivateMessageContentCacheConfirm =
                        state.privateMessageContentCacheEnabled,
                )
            }
            return
        }
        persistPrivateMessageContentCache(enabled = true)
    }

    private fun disablePrivateMessageContentCache() {
        _state.update {
            it.copy(
                showDisablePrivateMessageContentCacheConfirm = false,
                isUpdatingPrivateMessageContentCache = true,
                privateMessageContentCachePersistError = false,
                privateMessageContentCachePurgeError = false,
            )
        }
        viewModelScope.launch {
            val result = runCatching { privateMessageContentCache.setEnabled(false) }
            _state.update { state -> disablePrivateMessageContentCacheResult(state, result) }
        }
    }

    private fun persistPrivateMessageContentCache(enabled: Boolean) {
        _state.update {
            it.copy(
                isUpdatingPrivateMessageContentCache = true,
                privateMessageContentCachePersistError = false,
                privateMessageContentCachePurgeError = false,
            )
        }
        viewModelScope.launch {
            val result = runCatching { privateMessageContentCache.setEnabled(enabled) }
            _state.update { state ->
                when (result.exceptionOrNull()) {
                    null -> state.copy(
                        privateMessageContentCacheEnabled = enabled,
                        isUpdatingPrivateMessageContentCache = false,
                    )
                    is PrivateMessageContentCacheException.PurgeFailed -> state.copy(
                        privateMessageContentCacheEnabled = false,
                        isUpdatingPrivateMessageContentCache = false,
                        privateMessageContentCachePurgePending = true,
                        privateMessageContentCachePurgeError = true,
                    )
                    else -> state.copy(
                        isUpdatingPrivateMessageContentCache = false,
                        privateMessageContentCachePersistError = true,
                    )
                }
            }
        }
    }

    private fun disablePrivateMessageContentCacheResult(
        state: SettingsState,
        result: Result<Unit>,
    ): SettingsState = when (result.exceptionOrNull()) {
        null -> state.copy(
            privateMessageContentCacheEnabled = false,
            isUpdatingPrivateMessageContentCache = false,
            privateMessageContentCachePurgePending = false,
        )
        is PrivateMessageContentCacheException.PreferenceWriteFailed -> state.copy(
            privateMessageContentCacheEnabled = true,
            isUpdatingPrivateMessageContentCache = false,
            privateMessageContentCachePersistError = true,
        )
        else -> state.copy(
            privateMessageContentCacheEnabled = false,
            isUpdatingPrivateMessageContentCache = false,
            privateMessageContentCachePurgePending = true,
            privateMessageContentCachePurgeError = true,
        )
    }

    private fun retryPrivateMessageContentCachePurge() {
        _state.update {
            it.copy(
                isUpdatingPrivateMessageContentCache = true,
                privateMessageContentCachePurgeError = false,
            )
        }
        viewModelScope.launch {
            val result = runCatching { privateMessageContentCache.retryPendingPurge() }
            _state.update { state ->
                state.copy(
                    isUpdatingPrivateMessageContentCache = false,
                    privateMessageContentCachePurgePending = result.isFailure,
                    privateMessageContentCachePurgeError = result.isFailure,
                )
            }
        }
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

    private fun updateQuoteCardsEnabled(desired: Boolean) {
        val previous = _state.value.quoteCardsEnabled
        updateBooleanPreference(
            desired = desired,
            optimistic = {
                it.copy(
                    quoteCardsEnabled = desired,
                    isUpdatingQuoteCardsEnabled = true,
                    quoteCardsEnabledError = false,
                    quoteCardsEnabledTouchedLocally = true,
                )
            },
            onSettled = { state, result ->
                if (result.isSuccess) {
                    state.copy(quoteCardsEnabled = desired, isUpdatingQuoteCardsEnabled = false)
                } else {
                    state.copy(
                        quoteCardsEnabled = previous,
                        isUpdatingQuoteCardsEnabled = false,
                        quoteCardsEnabledError = true,
                    )
                }
            },
            persist = userPreferencesRepository::setQuoteCardsEnabled,
        )
    }

    /**
     * Shared optimistic-flip machinery for a persisted boolean preference (the Drapeaux view
     * toggles). Flips the field immediately via [optimistic] (whose `isUpdating*` flag also gates
     * the continuous #788 re-sync for the write's duration; the `*TouchedLocally` flag it sets is
     * a write marker only, no longer consulted), persists [desired] on a background coroutine,
     * then reconciles the final state from the persist [Result] via [onSettled] (success
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
