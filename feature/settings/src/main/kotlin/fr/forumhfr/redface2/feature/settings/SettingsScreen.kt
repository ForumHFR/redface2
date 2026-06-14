package fr.forumhfr.redface2.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsListItem
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsSection
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsSearchTopBar

/**
 * #494 — root of the redesigned settings catalogue.
 *
 * A `Scaffold` + [RedfaceSettingsSearchTopBar] + `LazyColumn`, replacing the former
 * `Surface` / `verticalScroll` + stack of `Card`s. The catalogue is structured in sections; rows are
 * either toggles (a trailing `Switch`, wired straight to [SettingsViewModel.submit]) or navigation
 * rows (a trailing chevron, routing to a sub-page). The « Démarrage » block is rendered inline at the
 * root (it is a category picker, not a sub-page). Heavy areas — Proxy, Maintenance, Affichage, Images,
 * Compte/À propos — are sub-pages reached from navigation rows.
 *
 * Search is a local `rememberSaveable` state (no DataStore persistence): the catalogue is mapped to a
 * pure [SettingsSearchableSection] list and filtered via [filterSettingsSections]. Each kept item is
 * rendered by id from a `renderers` map, so the pure (testable) filter and the Compose rendering stay
 * decoupled. Future (disabled) rows stay searchable; gated rows (e.g. the DT inspector while the DT
 * section is off) are `visible = false` and excluded.
 *
 * Two distinct ViewModels coexist in this nav entry: [viewModel] (preferences) and
 * [startScreenViewModel] (#458 « Démarrage ») — both `hiltViewModel()` in the same `ViewModelStore`.
 */
@Composable
@Suppress("LongParameterList") // state-hoisted Composable: 5 sub-page nav callbacks + back + 2 VMs, each distinct.
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenProxy: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenAccountAbout: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
    startScreenViewModel: StartScreenSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startScreenState by startScreenViewModel.state.collectAsStateWithLifecycle()
    SettingsCatalogue(
        state = state,
        onIntent = viewModel::submit,
        startScreenState = startScreenState,
        onStartScreenIntent = startScreenViewModel::submit,
        onBack = onBack,
        onOpenProxy = onOpenProxy,
        onOpenMaintenance = onOpenMaintenance,
        onOpenDisplay = onOpenDisplay,
        onOpenImages = onOpenImages,
        onOpenAccountAbout = onOpenAccountAbout,
        modifier = modifier,
        topBarActions = topBarActions,
    )
}

// 12 navigation/intent callbacks + state make the explicit surface; grouping them behind an object
// would hide the call site. The two VMs are observed in [SettingsScreen]; this content is the
// stateless catalogue body so it stays previewable/testable without Hilt.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun SettingsCatalogue(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
    startScreenState: StartScreenSettingsState,
    onStartScreenIntent: (StartScreenSettingsIntent) -> Unit,
    onBack: () -> Unit,
    onOpenProxy: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenAccountAbout: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
) {
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    // System/gesture back closes the active search (without popping the Settings route, which is what
    // nav3 would otherwise do). Disabled when search is closed so normal back navigation proceeds.
    BackHandler(enabled = searchActive) {
        searchActive = false
        query = ""
    }

    // The renderable catalogue: each row carries its search metadata AND its Compose renderer. The
    // pure model fed to [filterSettingsSections] is derived from it (so the filter stays Android-free).
    val sections = buildSettingsCatalogue(
        state = state,
        onIntent = onIntent,
        startScreenState = startScreenState,
        onStartScreenIntent = onStartScreenIntent,
        onOpenProxy = onOpenProxy,
        onOpenMaintenance = onOpenMaintenance,
        onOpenDisplay = onOpenDisplay,
        onOpenImages = onOpenImages,
        onOpenAccountAbout = onOpenAccountAbout,
    )
    val renderers: Map<String, @Composable () -> Unit> = sections
        .flatMap { it.items }
        .associate { it.searchable.id to it.render }
    val filtered = filterSettingsSections(sections.map { it.toSearchable() }, query)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            RedfaceSettingsSearchTopBar(
                labels = rememberSettingsSearchTopBarLabels(),
                searchActive = searchActive,
                query = query,
                onQueryChange = { query = it },
                onSearchActiveChange = { active ->
                    searchActive = active
                    if (!active) query = ""
                },
                onBack = onBack,
                actions = { topBarActions?.invoke() },
            )
        },
    ) { innerPadding ->
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_search_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                filtered.forEachIndexed { sectionIndex, section ->
                    item(key = "section:${section.id}") {
                        if (sectionIndex > 0) HorizontalDivider()
                        RedfaceSettingsSection(section.title)
                    }
                    items(section.items, key = { "item:${it.id}" }) { item ->
                        renderers[item.id]?.invoke()
                    }
                }
            }
        }
    }
}

/** A catalogue row: its pure search metadata plus the Compose renderer that draws it. */
internal data class SettingsCatalogueRow(
    val searchable: SettingsSearchableItem,
    val render: @Composable () -> Unit,
)

/** A catalogue section paired with its renderable rows. */
internal data class SettingsCatalogueSection(
    val id: String,
    val title: String,
    val items: List<SettingsCatalogueRow>,
) {
    fun toSearchable(): SettingsSearchableSection =
        SettingsSearchableSection(id = id, title = title, items = items.map { it.searchable })
}

/**
 * Builds the root catalogue from resolved strings + [state]. Keeps the search metadata and the
 * renderer side by side so a row is described once. Toggle rows wire straight to [onIntent]; nav rows
 * route to a sub-page.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
private fun buildSettingsCatalogue(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
    startScreenState: StartScreenSettingsState,
    onStartScreenIntent: (StartScreenSettingsIntent) -> Unit,
    onOpenProxy: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenAccountAbout: () -> Unit,
): List<SettingsCatalogueSection> = listOf(
    // Réseau et cache.
    SettingsCatalogueSection(
        id = "network",
        title = stringResource(R.string.settings_section_network),
        items = listOf(
            navRow(
                id = "proxy",
                title = stringResource(R.string.settings_nav_proxy),
                description = stringResource(R.string.settings_nav_proxy_description),
                // A nav row is opaque to search: it must carry the labels actually shown inside the
                // sub-page (e.g. « Hôte »), or searching them hits the empty state (#494 Codex P2).
                keywords = listOf(
                    "proxy",
                    "réseau",
                    "host",
                    "port",
                    stringResource(R.string.settings_proxy_host),
                    stringResource(R.string.settings_proxy_port),
                    stringResource(R.string.settings_proxy_username),
                    stringResource(R.string.settings_proxy_password),
                ),
                onClick = onOpenProxy,
            ),
            navRow(
                id = "maintenance",
                title = stringResource(R.string.settings_nav_maintenance),
                description = stringResource(R.string.settings_nav_maintenance_description),
                keywords = buildList {
                    addAll(listOf("cache", "vider", "images", "topics", "diagnostic", "debug"))
                    add(stringResource(R.string.settings_clear_topic_cache_button))
                    add(stringResource(R.string.settings_clear_image_cache_button))
                    add(stringResource(R.string.settings_ignore_topic_cache_title))
                    // The MPStorage inspector only exists in this page when the DT section is on,
                    // so only surface its label to search in that case (avoids a dead-end result).
                    if (state.showDtSection) add(stringResource(R.string.settings_mpstorage_inspector_title))
                },
                onClick = onOpenMaintenance,
            ),
        ),
    ),
    // Démarrage (rendered inline — category picker, not a sub-page).
    SettingsCatalogueSection(
        id = "start",
        title = stringResource(R.string.settings_section_start),
        items = listOf(
            SettingsCatalogueRow(
                searchable = SettingsSearchableItem(
                    id = "start_screen",
                    title = stringResource(R.string.settings_start_screen_title),
                    description = stringResource(R.string.settings_start_screen_intro),
                    keywords = listOf("démarrage", "écran", "onglet", "lancement", "catégorie"),
                ),
                render = { StartScreenPreferencesCard(state = startScreenState, onIntent = onStartScreenIntent) },
            ),
        ),
    ),
    // Affichage (sub-page).
    SettingsCatalogueSection(
        id = "display",
        title = stringResource(R.string.settings_section_display),
        items = listOf(
            navRow(
                id = "display_nav",
                title = stringResource(R.string.settings_nav_display),
                description = stringResource(R.string.settings_nav_display_description),
                keywords = listOf(
                    "thème",
                    "amoled",
                    "sombre",
                    "clair",
                    "densité",
                    "police",
                    "taille",
                    stringResource(R.string.settings_theme_title),
                    stringResource(R.string.settings_theme_amoled_title),
                    stringResource(R.string.settings_display_title),
                    // Visible choice labels (theme / density / font scale) so e.g. « Système »,
                    // « Confort », « Compact » route to this page instead of the empty state.
                    stringResource(R.string.settings_theme_light),
                    stringResource(R.string.settings_theme_system),
                    stringResource(R.string.settings_theme_dark),
                    stringResource(R.string.settings_display_density_comfort),
                    stringResource(R.string.settings_display_density_compact),
                    stringResource(R.string.settings_display_font_scale_small),
                    stringResource(R.string.settings_display_font_scale_medium),
                    stringResource(R.string.settings_display_font_scale_large),
                ),
                onClick = onOpenDisplay,
            ),
        ),
    ),
    // Drapeaux (inline toggles).
    SettingsCatalogueSection(
        id = "flags",
        title = stringResource(R.string.settings_section_flags),
        items = listOf(
            toggleRow(
                id = "flags_group_by_category",
                title = stringResource(R.string.settings_flags_group_by_category_title),
                description = stringResource(R.string.settings_flags_group_by_category_description),
                checked = state.flagsGroupByCategory,
                enabled = state.canToggleFlagsGroupByCategory,
                errorRes = R.string.settings_flags_group_by_category_persist_failed
                    .takeIf { state.flagsGroupByCategoryError },
                onCheckedChange = { onIntent(SettingsIntent.FlagsGroupByCategoryChanged(it)) },
            ),
            toggleRow(
                id = "flags_hide_read",
                title = stringResource(R.string.settings_flags_hide_read_categories_title),
                description = stringResource(R.string.settings_flags_hide_read_categories_description),
                checked = state.flagsHideReadCategories,
                enabled = state.canToggleFlagsHideReadCategories,
                errorRes = R.string.settings_flags_hide_read_categories_persist_failed
                    .takeIf { state.flagsHideReadCategoriesError },
                onCheckedChange = { onIntent(SettingsIntent.FlagsHideReadCategoriesChanged(it)) },
            ),
            toggleRow(
                id = "flags_per_tab_override",
                title = stringResource(R.string.settings_flags_per_tab_override_title),
                description = stringResource(R.string.settings_flags_per_tab_override_description),
                checked = state.flagsPerTabOverride,
                enabled = state.canToggleFlagsPerTabOverride,
                errorRes = R.string.settings_flags_per_tab_override_persist_failed
                    .takeIf { state.flagsPerTabOverrideError },
                onCheckedChange = { onIntent(SettingsIntent.FlagsPerTabOverrideChanged(it)) },
            ),
            toggleRow(
                id = "flags_show_dt_section",
                title = stringResource(R.string.settings_flags_show_dt_section_title),
                description = stringResource(R.string.settings_flags_show_dt_section_description),
                checked = state.showDtSection,
                enabled = state.canToggleShowDtSection,
                errorRes = R.string.settings_flags_show_dt_section_persist_failed
                    .takeIf { state.showDtSectionError },
                onCheckedChange = { onIntent(SettingsIntent.ShowDtSectionChanged(it)) },
            ),
            toggleRow(
                id = "flags_auto_refresh",
                title = stringResource(R.string.settings_flags_auto_refresh_title),
                description = stringResource(R.string.settings_flags_auto_refresh_description),
                checked = state.flagsAutoRefresh,
                enabled = state.canToggleFlagsAutoRefresh,
                errorRes = R.string.settings_flags_auto_refresh_persist_failed
                    .takeIf { state.flagsAutoRefreshError },
                onCheckedChange = { onIntent(SettingsIntent.FlagsAutoRefreshChanged(it)) },
            ),
        ),
    ),
    // Sujet et lecture (inline toggles).
    SettingsCatalogueSection(
        id = "topic",
        title = stringResource(R.string.settings_section_topic),
        items = listOf(
            toggleRow(
                id = "topic_topbar_auto_hide",
                title = stringResource(R.string.settings_topic_topbar_auto_hide_title),
                description = stringResource(R.string.settings_topic_topbar_auto_hide_description),
                checked = state.topicTopBarAutoHide,
                enabled = state.canToggleTopicTopBarAutoHide,
                errorRes = R.string.settings_topic_topbar_auto_hide_persist_failed
                    .takeIf { state.topicTopBarAutoHideError },
                onCheckedChange = { onIntent(SettingsIntent.TopicTopBarAutoHideChanged(it)) },
            ),
            toggleRow(
                id = "topic_page_fabs",
                title = stringResource(R.string.settings_topic_page_fabs_title),
                description = stringResource(R.string.settings_topic_page_fabs_description),
                checked = state.topicPageFabs,
                enabled = state.canToggleTopicPageFabs,
                errorRes = R.string.settings_topic_page_fabs_persist_failed.takeIf { state.topicPageFabsError },
                onCheckedChange = { onIntent(SettingsIntent.TopicPageFabsChanged(it)) },
            ),
            toggleRow(
                id = "topic_polls_expanded",
                title = stringResource(R.string.settings_topic_polls_expanded_title),
                description = stringResource(R.string.settings_topic_polls_expanded_description),
                checked = state.topicPollsExpanded,
                enabled = state.canToggleTopicPollsExpanded,
                errorRes = R.string.settings_topic_polls_expanded_persist_failed
                    .takeIf { state.topicPollsExpandedError },
                onCheckedChange = { onIntent(SettingsIntent.TopicPollsExpandedChanged(it)) },
            ),
        ),
    ),
    // Édition et publication (inline toggle).
    SettingsCatalogueSection(
        id = "editing",
        title = stringResource(R.string.settings_section_editing),
        items = listOf(
            toggleRow(
                id = "confirm_before_posting",
                title = stringResource(R.string.settings_confirm_before_posting_title),
                description = stringResource(R.string.settings_confirm_before_posting_description),
                checked = state.confirmBeforePosting,
                enabled = state.canToggleConfirmBeforePosting,
                errorRes = R.string.settings_confirm_before_posting_persist_failed
                    .takeIf { state.confirmBeforePostingError },
                onCheckedChange = { onIntent(SettingsIntent.ConfirmBeforePostingChanged(it)) },
            ),
        ),
    ),
    // Images (sub-page).
    SettingsCatalogueSection(
        id = "images",
        title = stringResource(R.string.settings_section_images),
        items = listOf(
            navRow(
                id = "images_nav",
                title = stringResource(R.string.settings_nav_images),
                description = stringResource(R.string.settings_nav_images_description),
                keywords = listOf(
                    "images",
                    "hébergeur",
                    "imgur",
                    "diberie",
                    "upload",
                    "insertion",
                    stringResource(R.string.settings_upload_provider_title),
                    stringResource(R.string.settings_upload_provider_diberie),
                    stringResource(R.string.settings_upload_provider_imgur),
                    stringResource(R.string.settings_upload_imgur_client_id_label),
                    stringResource(R.string.settings_image_insert_title),
                    // Visible image-insert choice labels so they route here, not to the empty state.
                    stringResource(R.string.settings_image_insert_full),
                    stringResource(R.string.settings_image_insert_linked),
                    stringResource(R.string.settings_image_insert_reduced),
                    stringResource(R.string.settings_my_images_title),
                ),
                onClick = onOpenImages,
            ),
        ),
    ),
    // Messages privés (inline toggle).
    SettingsCatalogueSection(
        id = "mp",
        title = stringResource(R.string.settings_section_mp),
        items = listOf(
            toggleRow(
                id = "mp_unread_badge",
                title = stringResource(R.string.settings_mp_unread_badge_title),
                description = stringResource(R.string.settings_mp_unread_badge_description),
                checked = state.mpUnreadBadge,
                enabled = state.canToggleMpUnreadBadge,
                errorRes = R.string.settings_mp_unread_badge_persist_failed.takeIf { state.mpUnreadBadgeError },
                onCheckedChange = { onIntent(SettingsIntent.MpUnreadBadgeChanged(it)) },
            ),
        ),
    ),
    // Compte HFR (sub-page).
    SettingsCatalogueSection(
        id = "hfr_account",
        title = stringResource(R.string.settings_section_hfr_account),
        items = listOf(
            navRow(
                id = "account_nav",
                title = stringResource(R.string.settings_nav_account),
                description = stringResource(R.string.settings_nav_account_description),
                keywords = listOf(
                    "compte",
                    "hfr",
                    "profil",
                    "version",
                    "à propos",
                    "diagnostic",
                    stringResource(R.string.settings_about_diagnostics),
                    stringResource(R.string.settings_about_report),
                ),
                onClick = onOpenAccountAbout,
            ),
        ),
    ),
    // Notifications (future, disabled — searchable).
    SettingsCatalogueSection(
        id = "notifications",
        title = stringResource(R.string.settings_section_notifications),
        items = listOf(
            futureRow(
                id = "future_notifications",
                title = stringResource(R.string.settings_future_notifications),
            ),
        ),
    ),
    // Accessibilité (future, disabled — searchable).
    SettingsCatalogueSection(
        id = "accessibility",
        title = stringResource(R.string.settings_section_accessibility),
        items = listOf(
            futureRow(
                id = "future_timezone",
                title = stringResource(R.string.settings_future_timezone),
            ),
            futureRow(
                id = "future_multilang",
                title = stringResource(R.string.settings_future_multilang),
            ),
        ),
    ),
    // Extensions et filtrage (future, disabled — searchable).
    SettingsCatalogueSection(
        id = "extensions",
        title = stringResource(R.string.settings_section_extensions),
        items = listOf(
            futureRow(
                id = "future_extensions",
                title = stringResource(R.string.settings_future_extensions),
            ),
        ),
    ),
)

/** A navigation row (chevron trailing) — opaque to search via [keywords]. */
private fun navRow(
    id: String,
    title: String,
    description: String,
    keywords: List<String>,
    onClick: () -> Unit,
): SettingsCatalogueRow = SettingsCatalogueRow(
    searchable = SettingsSearchableItem(id = id, title = title, description = description, keywords = keywords),
    render = {
        RedfaceSettingsListItem(
            title = title,
            description = description,
            onClick = onClick,
            trailingContent = { ChevronTrailing() },
        )
    },
)

/** A toggle row (trailing `Switch`) with an optional inline persist-error message below it. */
@Suppress("LongParameterList") // row descriptor: id + title/description + checked/enabled/error + callback.
private fun toggleRow(
    id: String,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    errorRes: Int?,
    onCheckedChange: (Boolean) -> Unit,
): SettingsCatalogueRow = SettingsCatalogueRow(
    searchable = SettingsSearchableItem(id = id, title = title, description = description),
    render = {
        Column(modifier = Modifier.fillMaxWidth()) {
            RedfaceSettingsListItem(
                title = title,
                description = description,
                trailingContent = {
                    Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
                },
            )
            if (errorRes != null) {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    PreferencePersistError(errorRes)
                }
            }
        }
    },
)

/** A planned (not-yet-shipped) row: disabled but still searchable (`enabled = false`). */
private fun futureRow(
    id: String,
    title: String,
): SettingsCatalogueRow = SettingsCatalogueRow(
    searchable = SettingsSearchableItem(id = id, title = title, enabled = false),
    render = {
        RedfaceSettingsListItem(title = title, enabled = false)
    },
)
