package fr.forumhfr.redface2.core.domain.preferences

import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
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

    /**
     * Per-tab display override master switch (#309): when `true`, each Drapeaux tab
     * ([FlagType.CYAN]/[FlagType.RED]/[FlagType.FAVORITE]) resolves its own stored view settings
     * (falling back to the global toggles for anything that tab never customised); when `false`
     * (default), every tab shares the single global pair. Observed so flipping it from the bottom
     * sheet or Settings re-renders the list without a refetch.
     */
    fun observeFlagsPerTabOverride(): Flow<Boolean>

    /** Persists [observeFlagsPerTabOverride]. Default `false` until the first call. */
    suspend fun setFlagsPerTabOverride(enabled: Boolean)

    /**
     * Resolved Drapeaux view settings for [type], combining the #309 layout toggles and the #317
     * unread filter:
     * - layout ([FlagsViewSettings.groupByCategory] / [FlagsViewSettings.hideReadCategories]),
     *   honouring [observeFlagsPerTabOverride]: override off → the global
     *   [observeFlagsGroupByCategory] / [observeFlagsHideReadCategories]; override on → this
     *   [type]'s stored values, each falling back to the matching global value when unset.
     * - [FlagsViewSettings.unreadOnly] (#317): ALWAYS per-type, with a type-aware default
     *   (CYAN → true, RED/FAVORITE → false) until [setFlagsUnreadOnlyForType] is called.
     *
     * This is the single source the Flags screen list rendering reads; the global layout observers
     * stay the editable defaults (and the per-tab fallback) the Settings mirror writes.
     */
    fun observeFlagsViewSettings(type: FlagType): Flow<FlagsViewSettings>

    /**
     * Persists the per-tab « grouper par catégorie » value for [type] (#309). Only consulted by
     * [observeFlagsViewSettings] when [observeFlagsPerTabOverride] is `true`; until set, that tab
     * falls back to the global [setFlagsGroupByCategory] value.
     */
    suspend fun setFlagsGroupByCategoryForType(type: FlagType, enabled: Boolean)

    /**
     * Persists the per-tab « masquer les catégories sans non-lu » value for [type] (#309). Only
     * consulted by [observeFlagsViewSettings] when [observeFlagsPerTabOverride] is `true`; until
     * set, that tab falls back to the global [setFlagsHideReadCategories] value.
     */
    suspend fun setFlagsHideReadCategoriesForType(type: FlagType, enabled: Boolean)

    /**
     * Persists the « non-lus uniquement » value for [type] (#317). Unlike the layout toggles this
     * is ALWAYS per-type (no global key, not subject to [observeFlagsPerTabOverride]). Until set,
     * [observeFlagsViewSettings] applies a type-aware default: `true` for [FlagType.CYAN] (the
     * actionable « Mes sujets » subset), `false` for [FlagType.RED] / [FlagType.FAVORITE].
     */
    suspend fun setFlagsUnreadOnlyForType(type: FlagType, enabled: Boolean)

    /**
     * Persists the GLOBAL Drapeaux marker shape (#603 PR6). Unlike the #309 layout toggles this is
     * NOT subject to [observeFlagsPerTabOverride] — one shape for every tab. Surfaced through
     * [observeFlagsViewSettings] ([FlagsViewSettings.markerStyle]) so the list re-renders without a
     * refetch. Defaults to [MarkerStyle.STRIPE] until the first call.
     */
    suspend fun setFlagsMarkerStyle(style: MarkerStyle)

    /**
     * Persists the GLOBAL « single-line topic titles » toggle (#603). Like [setFlagsMarkerStyle] it is
     * NOT subject to [observeFlagsPerTabOverride]; surfaced through [observeFlagsViewSettings]
     * ([FlagsViewSettings.singleLineTitle]). Defaults to `false` (2-line wrap) until the first call.
     */
    suspend fun setFlagsSingleLineTitle(enabled: Boolean)

    /**
     * Persists the GLOBAL grouped-view category band style (#603). Like [setFlagsMarkerStyle] it is
     * NOT subject to [observeFlagsPerTabOverride]; surfaced through [observeFlagsViewSettings]
     * ([FlagsViewSettings.categoryBandStyle]). Defaults to [CategoryBandStyle.MINIMAL] until the
     * first call.
     */
    suspend fun setFlagsCategoryBandStyle(style: CategoryBandStyle)

    /**
     * App theme selection (#286): [ThemeMode.SYSTEM] (default) follows the OS dark-mode setting;
     * [ThemeMode.LIGHT] / [ThemeMode.DARK] force the app theme regardless of the OS. Observed at the
     * app root ([fr.forumhfr.redface2.navigation.RedfaceApp]) to compute the effective dark theme
     * passed to `RedfaceTheme`, and mirrored in Settings.
     */
    fun observeThemeMode(): Flow<ThemeMode>

    /** Persists [observeThemeMode]. Default [ThemeMode.SYSTEM] until the first call. */
    suspend fun setThemeMode(mode: ThemeMode)

    /**
     * AMOLED (true-black) theme toggle (#286): only takes effect when the effective theme is dark
     * (forced [ThemeMode.DARK] or [ThemeMode.SYSTEM] while the OS is in dark mode). Default `false`.
     */
    fun observeAmoledEnabled(): Flow<Boolean>

    /** Persists [observeAmoledEnabled]. Default `false` until the first call. */
    suspend fun setAmoledEnabled(enabled: Boolean)

    /**
     * Accent colour family (TU 2788511): [AccentColor.ROSE] (default) keeps the historical muted
     * maroon/rose scheme; [AccentColor.ROUGE_REDFACE1] switches to the vivid Redface 1 red. Observed
     * at the app root and passed to `RedfaceTheme`, and mirrored in Settings. Compose-only (does not
     * paint the window background), so no cold-start mirror — same stance as the display density.
     */
    fun observeAccentColor(): Flow<AccentColor>

    /** Persists [observeAccentColor]. Default [AccentColor.ROSE] until the first call. */
    suspend fun setAccentColor(color: AccentColor)

    /**
     * Topic top app bar auto-hide (build 89 follow-up): when `true`, the topic top bar (title +
     * page counter) collapses while the user scrolls down through the posts and re-appears as soon
     * as they scroll back toward the top — Material3 `enterAlways` behaviour — freeing reading
     * space. Default `false` (the bar stays pinned). Observed by `:feature:topic`, toggled in
     * Settings.
     */
    fun observeTopicTopBarAutoHide(): Flow<Boolean>

    /** Persists [observeTopicTopBarAutoHide]. Default `false` until the first call. */
    suspend fun setTopicTopBarAutoHide(enabled: Boolean)

    /**
     * Confirmation before posting (#312): when `true`, every publish action (topic reply / post
     * edit, new topic / first-post edit, private-message reply) first shows a confirmation dialog
     * before the real submission is executed. Default `false` — submission stays one-tap unless the
     * user opts in. Observed by the editor ViewModels (`:feature:editor`, `:feature:messages`),
     * toggled in Settings.
     */
    fun observeConfirmBeforePosting(): Flow<Boolean>

    /** Persists [observeConfirmBeforePosting]. Default `false` until the first call. */
    suspend fun setConfirmBeforePosting(enabled: Boolean)

    /**
     * Opt-in « DT » section on the Drapeaux screen: when `true`, a « DT » tab appears next
     * to the flag-type tabs. Placeholder for now — the content (the followed-discussions
     * list whose flags sync through the MPStorage document, #6) lands later. Default
     * `false`. Observed by `:feature:flags`, toggled in Settings.
     */
    fun observeShowDtSection(): Flow<Boolean>

    /** Persists [observeShowDtSection]. Default `false` until the first call. */
    suspend fun setShowDtSection(enabled: Boolean)

    /**
     * EXPERIMENTAL opt-in (#6, ADR-014 §4) — whether Redface 2 may WRITE BACK DT reading positions
     * into the cross-userscript MPStorage document (a full-overwrite `bdd.php cat=prive` POST,
     * verify-after-write). Default `false`, and DELIBERATELY so : the write contract was never
     * observed live and a bad write touches the shared storage of EVERY userscript. When OFF (the
     * default) the write path returns immediately without any network access — the absence of a
     * write trigger is therefore harmless. Observed by the MPStorage write path, toggled in
     * Settings > Messages privés (with an experimental warning).
     */
    fun observeSyncPrivateMessagesWriteEnabled(): Flow<Boolean>

    /** Persists [observeSyncPrivateMessagesWriteEnabled]. Default `false` until the first call. */
    suspend fun setSyncPrivateMessagesWriteEnabled(enabled: Boolean)

    /**
     * Auto-refresh of the Drapeaux lists (#378): when `true`, landing on the flags screen
     * (app open, back from a topic, return from another tab) silently re-fetches the current
     * tab — throttled by the ViewModel so rapid back-and-forth does not hammer the REST
     * fan-out — with the pull-to-refresh indicator as the visual cue. Default `true` (the
     * feature exists because the lists went stale without it); the toggle is the opt-OUT
     * requested in the beta thread. Observed by `:feature:flags`, toggled in Settings.
     */
    fun observeFlagsAutoRefresh(): Flow<Boolean>

    /** Persists [observeFlagsAutoRefresh]. Default `true` until the first call. */
    suspend fun setFlagsAutoRefresh(enabled: Boolean)

    /**
     * Floating previous/next page buttons at the bottom of a topic (#283): when `false`, the
     * ‹/› mini-FABs are hidden — the page swipe (#282) and the header pager already cover
     * page-change, and some readers find the cluster intrusive (#383). The « Répondre » FAB
     * is NOT governed by this preference and stays visible. Default `true` (historical
     * behaviour). Observed by `:feature:topic`, toggled in Settings.
     */
    fun observeTopicPageFabs(): Flow<Boolean>

    /** Persists [observeTopicPageFabs]. Default `true` until the first call. */
    suspend fun setTopicPageFabs(enabled: Boolean)

    /**
     * #456 — whether topic polls render EXPANDED by default. Default `false` (collapsed to the
     * one-line « Sondage — afficher » card): most readers scroll past the poll, and a long
     * option list pushes the first post below the fold. The per-topic reveal toggle in the
     * poll card keeps working either way — this only seeds its initial state. Observed by
     * `:feature:topic`, toggled in Settings.
     */
    fun observeTopicPollsExpanded(): Flow<Boolean>

    /** Persists [observeTopicPollsExpanded]. Default `false` until the first call. */
    suspend fun setTopicPollsExpanded(enabled: Boolean)

    /**
     * #330 — whether each post's author signature (`<span class="signature">`, web parity) is
     * rendered beneath the post body, in a subdued style separated by a divider. Default `false`:
     * signatures are noisy and most readers scroll past them, so they are opt-in. Toggling is a
     * pure render-time switch (the signature is always parsed and cached, no refetch). Observed by
     * `:feature:topic`, toggled in Settings.
     */
    fun observeTopicSignatures(): Flow<Boolean>

    /** Persists [observeTopicSignatures]. Default `false` until the first call. */
    suspend fun setTopicSignatures(enabled: Boolean)

    /**
     * #332 — whether a "long" top-level citation folds to a one-line header by default (the
     * `isLongQuote` behaviour: a wall-of-text quote is collapsed and revealed on tap). Default
     * `true` = the historical fold; `false` disables it entirely so a long quote always renders
     * expanded inline like a short one (the beta feedback that the auto-fold is « trop strict »).
     * Pure render-time switch (no refetch), provided to the post renderer through a CompositionLocal
     * at the app root ([fr.forumhfr.redface2.navigation.RedfaceApp]) and mirrored in Settings.
     */
    fun observeFoldLongQuotes(): Flow<Boolean>

    /** Persists [observeFoldLongQuotes]. Default `true` until the first call. */
    suspend fun setFoldLongQuotes(enabled: Boolean)

    /**
     * #105 — whether the intra-page reading scrollbar ([LazyListScrollbar], the thin auto-hiding
     * fast-scroll thumb on the right edge of a topic page / private-message thread) is shown. Default
     * `true` (the historical behaviour); `false` hides it entirely (sujets AND MP) — the beta feedback
     * from styx42 that the ascenseur is unwanted. Pure render-time switch (no refetch), provided to the
     * scrollbar through a CompositionLocal at the app root ([fr.forumhfr.redface2.navigation.RedfaceApp])
     * and mirrored in Settings.
     */
    fun observeShowScrollbar(): Flow<Boolean>

    /** Persists [observeShowScrollbar]. Default `true` until the first call. */
    suspend fun setShowScrollbar(enabled: Boolean)

    /**
     * #458 — which top-level tab (and optional Forum category) a cold start opens on. Default
     * [StartScreenChoice.FLAGS] (historical behaviour). The navigation reads the SYNCHRONOUS
     * [StartScreenBootstrapStore] mirror at cold start; this flow is the source of truth and
     * feeds the Settings screen.
     */
    fun observeStartScreen(): Flow<StartScreenPreference>

    /** Persists [observeStartScreen] (both fields atomically) and refreshes the mirror. */
    suspend fun setStartScreen(preference: StartScreenPreference)

    /**
     * #313 — unread-MP badge on the « Messages » destination of the navigation bar. When
     * `true` (default) the badge shows the count of unread conversations for the authenticated
     * session ; `false` hides it entirely (no fetch is saved — the underlying count flow is
     * shared with other consumers). Observed by `:app`, toggled in Settings.
     */
    fun observeMpUnreadBadge(): Flow<Boolean>

    /** Persists [observeMpUnreadBadge]. Default `true` until the first call. */
    suspend fun setMpUnreadBadge(enabled: Boolean)

    /**
     * Default image host for editor uploads (#459). Default [UploadProviderId.DIBERIE] (no auth, no
     * Client-ID required). Observed by the editor (which provider to upload through) and the
     * Settings screen. A corrupt / unknown stored value degrades to the DIBERIE default.
     */
    fun observeUploadProvider(): Flow<UploadProviderId>

    /** Persists [observeUploadProvider]. Default [UploadProviderId.DIBERIE] until the first call. */
    suspend fun setUploadProvider(provider: UploadProviderId)

    /**
     * The user's own imgur Client-ID (#459, option B): imgur uploads require a public Client-ID, and
     * the app commits none — the user pastes their own in Settings. Empty (the default) means imgur
     * is NOT configured, so the provider selector hides IMGUR (DIBERIE works without any Client-ID).
     */
    fun observeImgurClientId(): Flow<String>

    /** Persists [observeImgurClientId]. Default empty string until the first call. */
    suspend fun setImgurClientId(clientId: String)

    /**
     * How the editor wraps an inserted image (#459 PR-images follow-up). Default
     * [EditorImageInsert.REDUCED] — the classic HFR "vignette cliquable" (a reduced image linking to
     * the original), matching Redface v1's default for diberie. A corrupt / unknown stored value
     * degrades to the default.
     */
    fun observeEditorImageInsert(): Flow<EditorImageInsert>

    /** Persists [observeEditorImageInsert]. Default [EditorImageInsert.REDUCED] until the first call. */
    suspend fun setEditorImageInsert(mode: EditorImageInsert)

    /**
     * Reading-density preset (#287): [DisplayDensity.COMFORT] (default) keeps the historical
     * structural rhythm; [DisplayDensity.COMPACT] tightens the listing-row and post-body paddings
     * for a denser feed. Observed at the app root ([fr.forumhfr.redface2.navigation.RedfaceApp]) to
     * provide `LocalDisplayMetrics` to the whole tree, and mirrored in Settings.
     */
    fun observeDisplayDensity(): Flow<DisplayDensity>

    /** Persists [observeDisplayDensity]. Default [DisplayDensity.COMFORT] until the first call. */
    suspend fun setDisplayDensity(density: DisplayDensity)

    /**
     * Reading font-size preset (#287): [FontScalePreference.M] (default) is the M3 reference size;
     * [FontScalePreference.S] / [FontScalePreference.L] scale the reading typography by the preset
     * [FontScalePreference.factor], applied ON TOP of the OS font zoom (never replacing it).
     * Observed at the app root to scale the theme `Typography`, and mirrored in Settings.
     */
    fun observeFontScale(): Flow<FontScalePreference>

    /** Persists [observeFontScale]. Default [FontScalePreference.M] until the first call. */
    suspend fun setFontScale(scale: FontScalePreference)

    /**
     * Debug bounds overlay (#445): when `true`, the app draws a full-screen overlay outlining the
     * bounds of every laid-out component (walked from the Compose semantics tree) so a developer can
     * eyeball layout/spacing while dogfooding. Default `false`. The toggle is exposed in Settings on
     * the DEV channel ONLY (gated by the `:app` build flavor), and the overlay itself early-returns
     * when disabled so it costs nothing in production. Observed at the app root
     * ([fr.forumhfr.redface2.navigation.RedfaceApp]).
     */
    fun observeDebugBoundsOverlay(): Flow<Boolean>

    /** Persists [observeDebugBoundsOverlay]. Default `false` until the first call. */
    suspend fun setDebugBoundsOverlay(enabled: Boolean)

    /**
     * Immersive full-screen (#518): when `true`, the app hides the bottom Android system navigation
     * bar (the 3 buttons, or the gesture pill depending on the device) — the top status bar and the
     * in-app tab bar stay visible. A swipe from the
     * bottom edge re-reveals the bar transiently (Android `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`,
     * documented behaviour, not a bug), then it re-hides. Default `false`. Applied at the app root
     * ([fr.forumhfr.redface2.navigation.RedfaceApp]) on the host window, toggled in Settings > Affichage.
     */
    fun observeHideSystemNavBar(): Flow<Boolean>

    /** Persists [observeHideSystemNavBar]. Default `false` until the first call. */
    suspend fun setHideSystemNavBar(enabled: Boolean)

    /**
     * Immersive full-screen companion (#518 follow-up): when `true`, an in-app « back » button is shown
     * while [observeHideSystemNavBar] is active, so the user can go back without swiping the hidden
     * Android nav bar back into view (a real need in 3-button mode, where there is no system back
     * gesture). Default `true` — it only ever renders WHEN immersive mode is on, so off-by-default would
     * leave immersive 3-button users stranded; the toggle lets gesture-nav users (who keep the edge
     * back-swipe) turn it off. Observed at the app root, toggled in Settings > Affichage.
     */
    fun observeImmersiveBackButton(): Flow<Boolean>

    /** Persists [observeImmersiveBackButton]. Default `true` until the first call. */
    suspend fun setImmersiveBackButton(enabled: Boolean)

    /**
     * Immersive full-screen companion (#518 follow-up): WHEN the hidden Android navigation bar should be
     * revealed again from inside the app, based on the reading scroll position (cf. [ImmersiveNavBarReveal]).
     * Only meaningful while [observeHideSystemNavBar] is active. Default [ImmersiveNavBarReveal.MANUAL]
     * (the historical #518 behaviour: swipe-from-bottom only). Observed at the app root, set in
     * Settings > Affichage.
     */
    fun observeImmersiveNavBarReveal(): Flow<ImmersiveNavBarReveal>

    /** Persists [observeImmersiveNavBarReveal]. Default [ImmersiveNavBarReveal.MANUAL] until the first call. */
    suspend fun setImmersiveNavBarReveal(mode: ImmersiveNavBarReveal)
}
