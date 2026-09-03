package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.preferences.AccentPreset
import fr.forumhfr.redface2.core.domain.preferences.DarkSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.LightSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
import fr.forumhfr.redface2.core.domain.preferences.PostHeaderEmphasis
import fr.forumhfr.redface2.core.domain.preferences.PostImageMaxWidth
import fr.forumhfr.redface2.core.domain.preferences.SmileyPickerDecoration
import fr.forumhfr.redface2.core.domain.preferences.ThemeAccent
import fr.forumhfr.redface2.core.domain.preferences.ThemeColorPreferences
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.WritingSurfacePreset

data class SettingsState(
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val error: SettingsError? = null,
    // Topic cache maintenance — alpha-only "Vider le cache des topics" action. Each
    // value is owned by `SettingsViewModel` and reset to false/null when the user
    // dismisses the dialog or after a result is acknowledged by a new click.
    val showClearTopicCacheConfirm: Boolean = false,
    val isClearingTopicCache: Boolean = false,
    val topicCacheClearResult: TopicCacheClearResult? = null,
    // Image cache maintenance — « Vider le cache des images » (#314). DEDICATED trio,
    // strictly mirroring the topic-cache fields above: sharing them would make the two
    // Maintenance entries collide (a topic clear would flash its result under the image
    // button and vice versa).
    val showClearImageCacheConfirm: Boolean = false,
    val isClearingImageCache: Boolean = false,
    val imageCacheClearResult: ImageCacheClearResult? = null,
    // Alpha-only "Ignorer le cache topic" toggle (Phase 2 finish). Persisted in DataStore via
    // UserPreferencesRepository — the ViewModel hydrates this field from the persisted value
    // and writes back on user toggle. `isUpdatingIgnoreTopicCache` gates the switch while the
    // write is in flight so a rapid double-tap can't queue two DataStore edits.
    val ignoreTopicCache: Boolean = false,
    val isUpdatingIgnoreTopicCache: Boolean = false,
    val ignoreTopicCacheError: Boolean = false,
    /**
     * Legacy write marker, set to `true` the moment the user flips the toggle locally. It used
     * to gate the one-shot `init` hydration against a stale late snapshot; since the #788
     * continuous re-sync it is NO LONGER CONSULTED (the in-flight `isUpdating*` flag is the only
     * hydration guard) — every `*TouchedLocally` field below shares this status. The imgur
     * Client-ID is the one exception: its latch still opts the instance out of the re-sync while
     * the user types (persist-on-keystroke field). Never surfaced in the UI; removal deferred to
     * keep this diff mechanical.
     */
    val ignoreTopicCacheTouchedLocally: Boolean = false,
    // #445 — debug bounds overlay toggle (dev-channel only; the channel gate is in the screen, which
    // hides the row off dev). Same optimistic-flip + startup-race-guard machinery as ignoreTopicCache.
    // Default false (the overlay is opt-in even on dev).
    val debugBoundsOverlay: Boolean = false,
    val isUpdatingDebugBoundsOverlay: Boolean = false,
    val debugBoundsOverlayError: Boolean = false,
    val debugBoundsOverlayTouchedLocally: Boolean = false,
    // Drapeaux view preferences (#179 follow-up). Same optimistic-flip machinery as
    // ignoreTopicCache: the field is the displayed value, `isUpdating*` gates the switch while
    // DataStore writes, `*Error` surfaces a persist failure, and `*TouchedLocally` is a legacy
    // write marker (no longer consulted — #788, cf. ignoreTopicCacheTouchedLocally). Defaults
    // match the DataStore defaults (grouped on, hide-read off).
    val flagsGroupByCategory: Boolean = true,
    val isUpdatingFlagsGroupByCategory: Boolean = false,
    val flagsGroupByCategoryError: Boolean = false,
    val flagsGroupByCategoryTouchedLocally: Boolean = false,
    val flagsHideReadCategories: Boolean = false,
    val isUpdatingFlagsHideReadCategories: Boolean = false,
    val flagsHideReadCategoriesError: Boolean = false,
    val flagsHideReadCategoriesTouchedLocally: Boolean = false,
    // #309 — per-tab display override master switch. Same optimistic-flip machinery; when on, each
    // Drapeaux tab keeps its own view settings (tuned from the Drapeaux bottom sheet), the two
    // toggles above acting as the shared fallback. Default false (global view for every tab).
    val flagsPerTabOverride: Boolean = false,
    val isUpdatingFlagsPerTabOverride: Boolean = false,
    val flagsPerTabOverrideError: Boolean = false,
    val flagsPerTabOverrideTouchedLocally: Boolean = false,
    // Theme preferences (#286 / #595 / #883). `themeMode` stays independent from the colour bundle:
    // the mode decides light/dark, while `themeColorPreferences` carries accent presets/custom RGB,
    // light/dark surface tones and Android 12+ dynamic colours. The bundle is written atomically so
    // one UI action cannot leave the accent and surface keys half-updated.
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val isUpdatingThemeMode: Boolean = false,
    val themeModeError: Boolean = false,
    val themeModeTouchedLocally: Boolean = false,
    val themeColorPreferences: ThemeColorPreferences = ThemeColorPreferences(),
    val isUpdatingThemeColors: Boolean = false,
    val themeColorsError: Boolean = false,
    val themeColorsTouchedLocally: Boolean = false,
    val customAccentHexInput: String = "",
    val customAccentHexSyncedInput: String = "",
    val customAccentHexError: Boolean = false,
    // #1207 — force Android's « Ouvrir avec… » chooser for explicit external-link actions.
    // Default false preserves the direct-default-browser behaviour until the user opts in.
    val alwaysAskLinkApp: Boolean = false,
    val isUpdatingAlwaysAskLinkApp: Boolean = false,
    val alwaysAskLinkAppError: Boolean = false,
    val alwaysAskLinkAppTouchedLocally: Boolean = false,
    // Topic reading preferences (build 89 follow-up). Same optimistic-flip machinery:
    // `topicTopBarAutoHide` is the displayed value, `isUpdating*` gates the switch while
    // DataStore writes, `*Error` surfaces a persist failure, `*TouchedLocally` is a legacy write
    // marker (no longer consulted — #788). Default false (top bar pinned).
    val topicTopBarAutoHide: Boolean = false,
    val isUpdatingTopicTopBarAutoHide: Boolean = false,
    val topicTopBarAutoHideError: Boolean = false,
    val topicTopBarAutoHideTouchedLocally: Boolean = false,
    // Topic — #383 floating ‹/› page FABs (#283). Same machinery. Default TRUE (historical
    // cluster); the toggle is the opt-out for swipe-only readers. « Répondre » is not governed.
    val topicPageFabs: Boolean = true,
    val isUpdatingTopicPageFabs: Boolean = false,
    val topicPageFabsError: Boolean = false,
    val topicPageFabsTouchedLocally: Boolean = false,
    // #313 — badge MP non lus sur l'item Messages de la barre de navigation.
    val mpUnreadBadge: Boolean = true,
    val isUpdatingMpUnreadBadge: Boolean = false,
    val mpUnreadBadgeError: Boolean = false,
    val mpUnreadBadgeTouchedLocally: Boolean = false,
    // #1040 lot 7 — global application opt-in for private-message Room content. OFF is both the
    // default and the effective value while a failed purge is pending.
    val privateMessageContentCacheEnabled: Boolean = false,
    val isUpdatingPrivateMessageContentCache: Boolean = false,
    val showDisablePrivateMessageContentCacheConfirm: Boolean = false,
    val privateMessageContentCachePersistError: Boolean = false,
    val privateMessageContentCachePurgePending: Boolean = false,
    val privateMessageContentCachePurgeError: Boolean = false,
    // #456 — sondages dépliés par défaut dans la lecture de sujet. Default FALSE (repliés) :
    // la carte reste dépliable/repliable par sujet, le réglage ne sème que l'état initial.
    val topicPollsExpanded: Boolean = false,
    val isUpdatingTopicPollsExpanded: Boolean = false,
    val topicPollsExpandedError: Boolean = false,
    val topicPollsExpandedTouchedLocally: Boolean = false,
    // #1170 — déplier uniquement les sondages auxquels le lecteur peut encore répondre, même si
    // le réglage général ci-dessus reste désactivé. Préférence indépendante, opt-in par défaut.
    val topicUnansweredPollsExpanded: Boolean = false,
    val isUpdatingTopicUnansweredPollsExpanded: Boolean = false,
    val topicUnansweredPollsExpandedError: Boolean = false,
    val topicUnansweredPollsExpandedTouchedLocally: Boolean = false,
    // #330 — afficher les signatures sous les posts. Même machinerie optimistic-flip + garde de
    // course au démarrage. Default FALSE (masquées) : les signatures sont bruyantes, donc opt-in.
    val topicSignatures: Boolean = false,
    val isUpdatingTopicSignatures: Boolean = false,
    val topicSignaturesError: Boolean = false,
    val topicSignaturesTouchedLocally: Boolean = false,
    // #332 — replier les longues citations. Même machinerie optimistic-flip + garde de course au
    // démarrage. Default TRUE (repli historique) : le toggle est l'opt-out demandé par le retour
    // bêta « trop strict ».
    val foldLongQuotes: Boolean = true,
    val isUpdatingFoldLongQuotes: Boolean = false,
    val foldLongQuotesError: Boolean = false,
    val foldLongQuotesTouchedLocally: Boolean = false,
    // #884 — posts en pleine largeur dans la lecture de sujet. Même machinerie optimistic-flip +
    // garde de course au démarrage. Default FALSE (encart historique) : la pleine largeur est l'opt-in.
    val fullWidthPosts: Boolean = false,
    val isUpdatingFullWidthPosts: Boolean = false,
    val fullWidthPostsError: Boolean = false,
    val fullWidthPostsTouchedLocally: Boolean = false,
    // #874 — independent EgoQuote / EgoPost topic highlights. Both default TRUE; each follows the
    // same optimistic-flip and in-flight hydration guard as the neighbouring reading preferences.
    val egoQuoteEnabled: Boolean = true,
    val isUpdatingEgoQuote: Boolean = false,
    val egoQuoteError: Boolean = false,
    val egoPostEnabled: Boolean = true,
    val isUpdatingEgoPost: Boolean = false,
    val egoPostError: Boolean = false,
    // #105 — afficher l'ascenseur de lecture. Même machinerie optimistic-flip + garde de course au
    // démarrage. Default TRUE (ascenseur historique) : le toggle est l'opt-out (retour bêta styx42).
    val showScrollbar: Boolean = true,
    val isUpdatingShowScrollbar: Boolean = false,
    val showScrollbarError: Boolean = false,
    val showScrollbarTouchedLocally: Boolean = false,
    // #666 — afficher les libellés sous les icônes de la barre du bas. Même machinerie optimistic-flip
    // + garde de course. Default TRUE (libellés affichés = comportement M3 historique) : l'opt-out.
    val navBarLabels: Boolean = true,
    val isUpdatingNavBarLabels: Boolean = false,
    val navBarLabelsError: Boolean = false,
    val navBarLabelsTouchedLocally: Boolean = false,
    // #662 — états vides humoristiques de la vue Drapeaux (smiley perso au lieu de l'icône sobre).
    // Même machinerie optimistic-flip + garde de course. Default FALSE (opt-in).
    val funnyEmptyState: Boolean = false,
    val isUpdatingFunnyEmptyState: Boolean = false,
    val funnyEmptyStateError: Boolean = false,
    val funnyEmptyStateTouchedLocally: Boolean = false,
    // #518 — masquer la barre de navigation système Android (plein écran immersif). Même machinerie
    // optimistic-flip + garde de course. Default FALSE (opt-in).
    val hideSystemNavBar: Boolean = false,
    val isUpdatingHideSystemNavBar: Boolean = false,
    val hideSystemNavBarError: Boolean = false,
    val hideSystemNavBarTouchedLocally: Boolean = false,
    // #518 follow-up — bouton « retour » flottant affiché en mode plein écran (compagnon du toggle
    // ci-dessus). Même machinerie optimistic-flip + garde de course. Default TRUE (il ne s'affiche que
    // quand le plein écran est actif) ; l'option permet de le retirer pour les utilisateurs en gestes.
    val immersiveBackButton: Boolean = true,
    val isUpdatingImmersiveBackButton: Boolean = false,
    val immersiveBackButtonError: Boolean = false,
    val immersiveBackButtonTouchedLocally: Boolean = false,
    // #518 follow-up — quand la barre système masquée se révèle automatiquement (selon le scroll).
    // Default MANUAL (balayage seul, comportement #518 historique). Même machinerie que DisplayDensity.
    val immersiveNavBarReveal: ImmersiveNavBarReveal = ImmersiveNavBarReveal.MANUAL,
    val isUpdatingImmersiveNavBarReveal: Boolean = false,
    val immersiveNavBarRevealError: Boolean = false,
    val immersiveNavBarRevealTouchedLocally: Boolean = false,
    // Publishing preferences (#312). Same optimistic-flip machinery: `confirmBeforePosting` is
    // the displayed value, `isUpdating*` gates the switch while DataStore writes, `*Error`
    // surfaces a persist failure, `*TouchedLocally` is a legacy write marker (no longer
    // consulted — #788). Default false (publishing stays one-tap).
    val confirmBeforePosting: Boolean = false,
    val isUpdatingConfirmBeforePosting: Boolean = false,
    val confirmBeforePostingError: Boolean = false,
    val confirmBeforePostingTouchedLocally: Boolean = false,
    // #805 arbitrage — quote cards in the composer, opt-in. Same optimistic-flip + startup-race
    // machinery. Default false (citations = inline [quotemsg] BBCode in the field).
    val quoteCardsEnabled: Boolean = false,
    val isUpdatingQuoteCardsEnabled: Boolean = false,
    val quoteCardsEnabledError: Boolean = false,
    val quoteCardsEnabledTouchedLocally: Boolean = false,
    // #806 — which surface a write action in a topic opens (sheet / sheet-except-quotes / full
    // editor). Enum, so the same bespoke optimistic-flip shape as [editorImageInsert]. Default
    // FULL_EDITOR since the quick-reply sheet is experimental opt-in (#951). Distinct from
    // [quoteCardsEnabled], which governs the quote RENDERING inside whichever surface opens.
    val writingSurfacePreset: WritingSurfacePreset = WritingSurfacePreset.FULL_EDITOR,
    val isUpdatingWritingSurfacePreset: Boolean = false,
    val writingSurfacePresetError: Boolean = false,
    val writingSurfacePresetTouchedLocally: Boolean = false,
    // Drapeaux — opt-in « DT » placeholder tab (MPStorage sync #6 lands later). Same
    // optimistic-flip + startup-race-guard machinery. Default false (tab hidden).
    val showDtSection: Boolean = false,
    val isUpdatingShowDtSection: Boolean = false,
    val showDtSectionError: Boolean = false,
    val showDtSectionTouchedLocally: Boolean = false,
    // #6, ADR-014 §4 — EXPERIMENTAL opt-in for the MPStorage write-back (sync DT en écriture). Same
    // optimistic-flip + startup-race-guard machinery. Default FALSE (off = no write ever happens).
    val syncPrivateMessagesWriteEnabled: Boolean = false,
    val isUpdatingSyncPrivateMessagesWriteEnabled: Boolean = false,
    val syncPrivateMessagesWriteEnabledError: Boolean = false,
    val syncPrivateMessagesWriteEnabledTouchedLocally: Boolean = false,
    // Drapeaux — #378 auto-refresh on landing (app open / back from a topic). Same machinery.
    // Default TRUE: the staleness was the complaint, the toggle is the opt-out.
    val flagsAutoRefresh: Boolean = true,
    val isUpdatingFlagsAutoRefresh: Boolean = false,
    val flagsAutoRefreshError: Boolean = false,
    val flagsAutoRefreshTouchedLocally: Boolean = false,
    // Reading display presets (#287). Same optimistic-flip machinery as the theme controls (both
    // are enums, so the bespoke shape): the value is the displayed selection, `isUpdating*` gates
    // the control while DataStore writes, `*Error` surfaces a persist failure, `*TouchedLocally`
    // is a legacy write marker (no longer consulted — #788). Defaults match the DataStore
    // defaults (COMFORT density, M font scale).
    val displayDensity: DisplayDensity = DisplayDensity.COMFORT,
    val isUpdatingDisplayDensity: Boolean = false,
    val displayDensityError: Boolean = false,
    val displayDensityTouchedLocally: Boolean = false,
    val fontScale: FontScalePreference = FontScalePreference.M,
    val isUpdatingFontScale: Boolean = false,
    val fontScaleError: Boolean = false,
    val fontScaleTouchedLocally: Boolean = false,
    // #973 — block-GIF display profile ([AMENDEMENT-v1.5-2]). Same optimistic-flip machinery as
    // the reading display presets. Default matches the DataStore default (M ×1,5, choix XaTriX).
    val mediaDisplayProfile: MediaDisplayProfile = MediaDisplayProfile.M,
    val isUpdatingMediaDisplayProfile: Boolean = false,
    val mediaDisplayProfileError: Boolean = false,
    val mediaDisplayProfileTouchedLocally: Boolean = false,
    // #991 — largeur maximale fImage des images de contenu (P95 par défaut).
    val postImageMaxWidth: PostImageMaxWidth = PostImageMaxWidth.DEFAULT,
    val isUpdatingPostImageMaxWidth: Boolean = false,
    val postImageMaxWidthError: Boolean = false,
    val postImageMaxWidthTouchedLocally: Boolean = false,
    // #989 — délimiteur des cellules du picker de smileys (NONE par défaut).
    val smileyPickerDecoration: SmileyPickerDecoration = SmileyPickerDecoration.NONE,
    val isUpdatingSmileyPickerDecoration: Boolean = false,
    val smileyPickerDecorationError: Boolean = false,
    val smileyPickerDecorationTouchedLocally: Boolean = false,
    // #459 — Hébergeur d'images. The provider is an enum, so it uses the bespoke optimistic-flip
    // shape (like themeMode): `uploadProvider` is the displayed selection, `isUpdating*` gates the
    // control while DataStore writes, `*Error` surfaces a persist failure, `*TouchedLocally` is a
    // legacy write marker (no longer consulted — #788). Default DIBERIE (no Client-ID).
    val uploadProvider: UploadProviderId = UploadProviderId.DIBERIE,
    val isUpdatingUploadProvider: Boolean = false,
    val uploadProviderError: Boolean = false,
    val uploadProviderTouchedLocally: Boolean = false,
    // #459 — imgur Client-ID text field. `imgurClientId` is the displayed/edited value, persisted on
    // each change (no save button — same as the optimistic prefs). `*Error` surfaces a persist
    // failure; `*TouchedLocally` is STILL consulted for this pref only (#788 exception): it opts
    // the instance out of the continuous re-sync so an echoed emission never overwrites
    // in-progress typing. Default empty = imgur not configured.
    val imgurClientId: String = "",
    val imgurClientIdError: Boolean = false,
    val imgurClientIdTouchedLocally: Boolean = false,
    // #459 PR-images follow-up — how the editor wraps an inserted image (full / linked / reduced).
    // Same optimistic-flip shape as [uploadProvider]. Default REDUCED (the HFR "vignette cliquable").
    val editorImageInsert: EditorImageInsert = EditorImageInsert.REDUCED,
    val isUpdatingEditorImageInsert: Boolean = false,
    val editorImageInsertError: Boolean = false,
    val editorImageInsertTouchedLocally: Boolean = false,
) {
    val canSave: Boolean
        get() = !isSaving

    val canClearTopicCache: Boolean
        get() = !isClearingTopicCache

    val canClearImageCache: Boolean
        get() = !isClearingImageCache

    val canToggleIgnoreTopicCache: Boolean
        get() = !isUpdatingIgnoreTopicCache

    // #445 — the debug bounds overlay toggle is gated only by its own in-flight write.
    val canToggleDebugBoundsOverlay: Boolean
        get() = !isUpdatingDebugBoundsOverlay

    val canToggleFlagsGroupByCategory: Boolean
        get() = !flagsPerTabOverride && !isUpdatingFlagsGroupByCategory

    // When per-tab override is on, the readable editing point is the Drapeaux quick config sheet.
    // The global value remains a persisted fallback, but Settings no longer exposes it as editable.
    val canToggleFlagsHideReadCategories: Boolean
        get() = !flagsPerTabOverride && flagsGroupByCategory && !isUpdatingFlagsHideReadCategories

    val canToggleFlagsPerTabOverride: Boolean
        get() = !isUpdatingFlagsPerTabOverride

    // #286 — theme controls are gated only by their own in-flight write.
    val canChangeThemeMode: Boolean
        get() = !isUpdatingThemeMode

    val canChangeThemeColors: Boolean
        get() = !isUpdatingThemeColors

    val customAccentPreviewRgb: Int?
        get() = parseThemeAccentHexOrNull(customAccentHexInput)

    val customAccentHexPlaceholder: String?
        get() = when (val accent = themeColorPreferences.accent) {
            is ThemeAccent.Custom -> null
            is ThemeAccent.Preset -> accent.preset.seedRgb.toThemeAccentHex()
        }

    // Build 89 follow-up — the topic top-bar auto-hide toggle is gated only by its own write.
    val canToggleTopicTopBarAutoHide: Boolean
        get() = !isUpdatingTopicTopBarAutoHide

    // #383 — the page-FABs toggle is gated only by its own write.
    val canToggleTopicPageFabs: Boolean
        get() = !isUpdatingTopicPageFabs

    val canToggleMpUnreadBadge: Boolean
        get() = !isUpdatingMpUnreadBadge

    // #456 — the polls toggle is gated only by its own write.
    val canToggleTopicPollsExpanded: Boolean
        get() = !isUpdatingTopicPollsExpanded

    // #1170 — independent from the global poll-expansion write and value.
    val canToggleTopicUnansweredPollsExpanded: Boolean
        get() = !isUpdatingTopicUnansweredPollsExpanded

    // #330 — the signatures toggle is gated only by its own write.
    val canToggleTopicSignatures: Boolean
        get() = !isUpdatingTopicSignatures

    // #332 — the fold-long-quotes toggle is gated only by its own write.
    val canToggleFoldLongQuotes: Boolean
        get() = !isUpdatingFoldLongQuotes

    // #884 — the full-width-posts toggle is gated only by its own write.
    val canToggleFullWidthPosts: Boolean
        get() = !isUpdatingFullWidthPosts

    val canToggleEgoQuote: Boolean
        get() = !isUpdatingEgoQuote

    val canToggleEgoPost: Boolean
        get() = !isUpdatingEgoPost

    // #105 — the show-scrollbar toggle is gated only by its own write.
    val canToggleShowScrollbar: Boolean
        get() = !isUpdatingShowScrollbar

    // #666 — the nav-bar-labels toggle is gated only by its own write.
    val canToggleNavBarLabels: Boolean
        get() = !isUpdatingNavBarLabels

    // #662 — the funny-empty-state toggle is gated only by its own write.
    val canToggleFunnyEmptyState: Boolean
        get() = !isUpdatingFunnyEmptyState

    // #518 — the hide-system-nav-bar toggle is gated only by its own write.
    val canToggleHideSystemNavBar: Boolean
        get() = !isUpdatingHideSystemNavBar

    // #518 follow-up — the immersive back-button toggle is gated only by its own write.
    val canToggleImmersiveBackButton: Boolean
        get() = !isUpdatingImmersiveBackButton

    // #518 follow-up — the nav-bar reveal-mode selector is gated only by its own write.
    val canChangeImmersiveNavBarReveal: Boolean
        get() = !isUpdatingImmersiveNavBarReveal

    // #312 — the confirm-before-posting toggle is gated only by its own write.
    val canToggleConfirmBeforePosting: Boolean
        get() = !isUpdatingConfirmBeforePosting

    // #805 — the quote-cards toggle is gated only by its own write.
    val canToggleQuoteCardsEnabled: Boolean
        get() = !isUpdatingQuoteCardsEnabled

    // #806 — the writing-surface radio group is gated only by its own in-flight write.
    val canChangeWritingSurfacePreset: Boolean
        get() = !isUpdatingWritingSurfacePreset

    // DT tab — gated only by its own write.
    val canToggleShowDtSection: Boolean
        get() = !isUpdatingShowDtSection

    // #6 — the experimental MPStorage write-back toggle is gated only by its own write.
    val canToggleSyncPrivateMessagesWriteEnabled: Boolean
        get() = !isUpdatingSyncPrivateMessagesWriteEnabled

    val canTogglePrivateMessageContentCache: Boolean
        get() = !isUpdatingPrivateMessageContentCache

    val canRetryPrivateMessageContentCachePurge: Boolean
        get() = privateMessageContentCachePurgePending && !isUpdatingPrivateMessageContentCache

    // #378 — flags auto-refresh, gated only by its own write.
    val canToggleFlagsAutoRefresh: Boolean
        get() = !isUpdatingFlagsAutoRefresh

    // #287 — the reading-density / font-scale selectors are each gated only by their own write.
    val canChangeDisplayDensity: Boolean
        get() = !isUpdatingDisplayDensity

    val canChangeFontScale: Boolean
        get() = !isUpdatingFontScale

    // #973 — the block-GIF profile selector is gated only by its own write.
    val canChangeMediaDisplayProfile: Boolean
        get() = !isUpdatingMediaDisplayProfile

    val canChangePostImageMaxWidth: Boolean
        get() = !isUpdatingPostImageMaxWidth

    val canChangeSmileyPickerDecoration: Boolean
        get() = !isUpdatingSmileyPickerDecoration

    // #459 — the provider selector is gated only by its own in-flight write.
    val canChangeUploadProvider: Boolean
        get() = !isUpdatingUploadProvider
}

sealed interface SettingsError {
    data object InvalidProxy : SettingsError
    data object PersistFailed : SettingsError
}

/**
 * Outcome of the latest "Vider le cache des topics" click. Surfaced inline in the
 * Maintenance card (success message OR error message); we do NOT mix it with the proxy
 * `saved` / `error` fields since the two domains are unrelated and a proxy save must
 * not silently dismiss a still-visible topic cache error.
 */
sealed interface TopicCacheClearResult {
    data object Success : TopicCacheClearResult
    data object Failure : TopicCacheClearResult
}

/**
 * Outcome of the latest « Vider le cache des images » click (#314). Dedicated type for
 * the same reason [TopicCacheClearResult] is separate from the proxy fields: the two
 * Maintenance actions are unrelated flows and one must never dismiss or repaint the
 * other's still-visible feedback.
 */
sealed interface ImageCacheClearResult {
    data object Success : ImageCacheClearResult
    data object Failure : ImageCacheClearResult
}

sealed interface SettingsIntent {
    data class ProxyEnabledChanged(val enabled: Boolean) : SettingsIntent
    data class ProxyHostChanged(val host: String) : SettingsIntent
    data class ProxyPortChanged(val port: String) : SettingsIntent
    data class ProxyUsernameChanged(val username: String) : SettingsIntent
    data class ProxyPasswordChanged(val password: String) : SettingsIntent
    data object SaveProxyClicked : SettingsIntent

    // Topic cache maintenance flow — three intents so the screen can confirm before any
    // destructive action and the ViewModel stays the single source of truth for
    // `showClearTopicCacheConfirm` / `isClearingTopicCache`.
    data object ClearTopicCacheClicked : SettingsIntent
    data object ClearTopicCacheConfirmed : SettingsIntent
    data object ClearTopicCacheDismissed : SettingsIntent

    // Image cache maintenance flow (#314) — same three-intent confirm shape as the topic
    // cache above, operating on the dedicated image-cache state fields.
    data object ClearImageCacheClicked : SettingsIntent
    data object ClearImageCacheConfirmed : SettingsIntent
    data object ClearImageCacheDismissed : SettingsIntent

    // Alpha "Ignorer le cache topic" toggle. The boolean is the desired post-flip state; the
    // ViewModel applies it optimistically, then reverts on DataStore failure so the UI never
    // shows a value that doesn't match what's persisted.
    data class IgnoreTopicCacheChanged(val enabled: Boolean) : SettingsIntent

    // #445 — debug bounds overlay toggle (dev only). Same optimistic-flip contract: the boolean is
    // the desired post-flip state.
    data class DebugBoundsOverlayChanged(val enabled: Boolean) : SettingsIntent

    // Drapeaux view preferences (#179 follow-up). Same optimistic-flip contract as
    // IgnoreTopicCacheChanged: the boolean is the desired post-flip state.
    data class FlagsGroupByCategoryChanged(val enabled: Boolean) : SettingsIntent
    data class FlagsHideReadCategoriesChanged(val enabled: Boolean) : SettingsIntent

    // #309 — per-tab display override master switch.
    data class FlagsPerTabOverrideChanged(val enabled: Boolean) : SettingsIntent

    // #286 — theme mode. Colour details live in the complete bundle below.
    data class ThemeModeChanged(val mode: ThemeMode) : SettingsIntent
    data class ThemeAccentPresetChanged(val preset: AccentPreset) : SettingsIntent
    data class CustomAccentHexChanged(val text: String) : SettingsIntent
    data object CustomAccentHexCommitted : SettingsIntent
    data class PostHeaderEmphasisChanged(val emphasis: PostHeaderEmphasis) : SettingsIntent
    data class LightSurfaceToneChanged(val tone: LightSurfaceTone) : SettingsIntent
    data class DarkSurfaceToneChanged(val tone: DarkSurfaceTone) : SettingsIntent
    data class DynamicColorEnabledChanged(val enabled: Boolean) : SettingsIntent

    /** #1207 — use Android's app chooser for every explicit external-link opening. */
    data class AlwaysAskLinkAppChanged(val enabled: Boolean) : SettingsIntent

    // Build 89 follow-up — topic top-bar auto-hide toggle. Optimistic-flip contract, like the
    // flags toggles: the boolean is the desired post-flip state.
    data class TopicTopBarAutoHideChanged(val enabled: Boolean) : SettingsIntent

    // #383 — topic floating ‹/› page FABs toggle. Optimistic-flip contract, like the flags
    // toggles: the boolean is the desired post-flip state.
    data class TopicPageFabsChanged(val enabled: Boolean) : SettingsIntent

    /** #313 — badge MP non lus (barre de navigation). */
    data class MpUnreadBadgeChanged(val enabled: Boolean) : SettingsIntent

    /** #456 — sondages dépliés par défaut dans la lecture de sujet. */
    data class TopicPollsExpandedChanged(val enabled: Boolean) : SettingsIntent

    /** #1170 — déplier les sondages ouverts auxquels le lecteur peut encore répondre. */
    data class TopicUnansweredPollsExpandedChanged(val enabled: Boolean) : SettingsIntent

    /** #330 — afficher les signatures des auteurs sous les posts. */
    data class TopicSignaturesChanged(val enabled: Boolean) : SettingsIntent

    /** #332 — replier les longues citations sur une ligne. */
    data class FoldLongQuotesChanged(val enabled: Boolean) : SettingsIntent

    /** #884 — afficher les posts en pleine largeur (bord à bord, sans encart). */
    data class FullWidthPostsChanged(val enabled: Boolean) : SettingsIntent

    /** #874 — highlight quotes that reproduce a post authored by the connected user. */
    data class EgoQuoteChanged(val enabled: Boolean) : SettingsIntent

    /** #874 — highlight posts authored by the connected user. */
    data class EgoPostChanged(val enabled: Boolean) : SettingsIntent

    /** #105 — afficher l'ascenseur de lecture (sujets et MP). */
    data class ShowScrollbarChanged(val enabled: Boolean) : SettingsIntent

    /** #666 — afficher les libellés sous les icônes de la barre du bas. */
    data class NavBarLabelsChanged(val enabled: Boolean) : SettingsIntent

    /** #662 — états vides humoristiques de la vue Drapeaux (smiley perso). */
    data class FunnyEmptyStateChanged(val enabled: Boolean) : SettingsIntent

    /** #518 — masquer la barre de navigation système Android (plein écran immersif). */
    data class HideSystemNavBarChanged(val enabled: Boolean) : SettingsIntent

    /** #518 follow-up — afficher le bouton « retour » flottant en mode plein écran. */
    data class ImmersiveBackButtonChanged(val enabled: Boolean) : SettingsIntent

    /** #518 follow-up — comportement de révélation de la barre système masquée (plein écran). */
    data class ImmersiveNavBarRevealChanged(val mode: ImmersiveNavBarReveal) : SettingsIntent

    // #312 — confirm-before-posting toggle. Optimistic-flip contract, like the flags toggles:
    // the boolean is the desired post-flip state.
    data class ConfirmBeforePostingChanged(val enabled: Boolean) : SettingsIntent

    // #805 — quote-cards-in-composer toggle. Optimistic-flip contract, like the flags toggles.
    data class QuoteCardsEnabledChanged(val enabled: Boolean) : SettingsIntent

    /**
     * #806 — choose which surface a write action in a topic opens. `preset` is the desired
     * selection (one intent per radio pick), applied optimistically with revert-on-failure,
     * like [SetEditorImageInsert].
     */
    data class SetWritingSurfacePreset(val preset: WritingSurfacePreset) : SettingsIntent

    // Drapeaux — opt-in « DT » placeholder tab (MPStorage sync #6 lands later). Optimistic-flip
    // contract, like the flags toggles: the boolean is the desired post-flip state.
    data class ShowDtSectionChanged(val enabled: Boolean) : SettingsIntent

    /** #6 — experimental opt-in for the MPStorage write-back (sync DT en écriture). */
    data class SyncPrivateMessagesWriteEnabledChanged(val enabled: Boolean) : SettingsIntent

    /** #1040 lot 7 — ON activates directly; OFF first opens an explicit purge confirmation. */
    data class PrivateMessageContentCacheChanged(val enabled: Boolean) : SettingsIntent

    data object DisablePrivateMessageContentCacheConfirmed : SettingsIntent

    data object DisablePrivateMessageContentCacheDismissed : SettingsIntent

    data object RetryPrivateMessageContentCachePurge : SettingsIntent

    // Drapeaux — #378 auto-refresh on landing. Optimistic-flip contract, like the flags
    // toggles: the boolean is the desired post-flip state.
    data class FlagsAutoRefreshChanged(val enabled: Boolean) : SettingsIntent

    // #287 — reading display presets. `density` / `scale` are the desired selections, applied
    // optimistically with revert-on-failure, like ThemeModeChanged.
    data class DisplayDensityChanged(val density: DisplayDensity) : SettingsIntent
    data class FontScaleChanged(val scale: FontScalePreference) : SettingsIntent

    // #973 — block-GIF display profile. `profile` is the desired selection, applied optimistically
    // with revert-on-failure, like DisplayDensityChanged.
    data class MediaDisplayProfileChanged(val profile: MediaDisplayProfile) : SettingsIntent

    // #991 — maximum fImage width for content images, applied optimistically like the GIF profile.
    data class PostImageMaxWidthChanged(val width: PostImageMaxWidth) : SettingsIntent

    /** #989 — l'utilisateur choisit le délimiteur des cellules du picker de smileys. */
    data class SmileyPickerDecorationChanged(val decoration: SmileyPickerDecoration) : SettingsIntent

    // #459 — Hébergeur d'images. `provider` is the desired selection (applied optimistically with
    // revert-on-failure, like ThemeModeChanged); `text` is the desired imgur Client-ID (persisted on
    // each change). Selecting IMGUR reveals the Client-ID field ; an empty Client-ID is NOT blocked at
    // the UI layer — the upload then fails with a precise host error (cf. #474) instead of silently.
    data class SetUploadProvider(val provider: UploadProviderId) : SettingsIntent
    data class SetImgurClientId(val text: String) : SettingsIntent

    /** #459 PR-images follow-up — choose how the editor wraps an inserted image. */
    data class SetEditorImageInsert(val mode: EditorImageInsert) : SettingsIntent
}
