package fr.forumhfr.redface2.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.editor.WritingSurfacePreset
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsListItem
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsSearchTopBar
import fr.forumhfr.redface2.core.ui.settings.shell.SettingsHomeScreen

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
@Suppress("LongParameterList") // racine v2 : 6 sous-pages + onOpenCategory + 2 VMs + slots, chacun distinct.
fun SettingsScreen(
    onOpenProxy: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenAccountAbout: () -> Unit,
    onOpenBlacklist: () -> Unit,
    onOpenCategory: (String) -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
    startScreenViewModel: StartScreenSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startScreenState by startScreenViewModel.state.collectAsStateWithLifecycle()
    SettingsRoot(
        state = state,
        onIntent = viewModel::submit,
        startScreenState = startScreenState,
        onStartScreenIntent = startScreenViewModel::submit,
        onOpenProxy = onOpenProxy,
        onOpenMaintenance = onOpenMaintenance,
        onOpenDisplay = onOpenDisplay,
        onOpenImages = onOpenImages,
        onOpenAccountAbout = onOpenAccountAbout,
        onOpenBlacklist = onOpenBlacklist,
        onOpenCategory = onOpenCategory,
        modifier = modifier,
        topBarActions = topBarActions,
    )
}

// 12 navigation/intent callbacks + state make the explicit surface; grouping them behind an object
// would hide the call site. The two VMs are observed in [SettingsScreen]; this content is the
// stateless catalogue body so it stays previewable/testable without Hilt.
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun SettingsRoot(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
    startScreenState: StartScreenSettingsState,
    onStartScreenIntent: (StartScreenSettingsIntent) -> Unit,
    onOpenProxy: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenAccountAbout: () -> Unit,
    onOpenBlacklist: () -> Unit,
    onOpenCategory: (String) -> Unit,
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

    // #494 v2 — idle : racine « catégories d'abord » (catégories regroupées en familles). Le pill de
    // recherche bascule en mode recherche qui réutilise le filtre/renderers du catalogue (ci-dessous).
    if (!searchActive) {
        SettingsHomeScreen(
            groups = rememberSettingsCategoryGroups(),
            searchPlaceholder = stringResource(R.string.settings_search_placeholder),
            menuContentDescription = stringResource(R.string.settings_menu_content_description),
            searchContentDescription = stringResource(R.string.settings_search_open),
            onMenuClick = null, // hamburger gardé mais désactivé : rôle à définir (#494 v2)
            onSearchClick = { searchActive = true },
            onCategoryClick = { id ->
                routeSettingsCategory(id, onOpenDisplay, onOpenImages, onOpenAccountAbout, onOpenCategory)
            },
            modifier = modifier,
            accountSlot = topBarActions,
        )
        return
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
        onOpenBlacklist = onOpenBlacklist,
    )
    val renderers: Map<String, @Composable () -> Unit> = sections
        .flatMap { it.items }
        .associate { it.searchable.id to it.render }
    val filtered = filterSettingsSections(sections.map { it.toSearchable() }, query)

    // #494 — effet « résultats sous la barre » (idiome M3) : pinnedScrollBehavior + nestedScroll sur le
    // Scaffold → la barre prend sa teinte surfaceContainer quand la liste de résultats défile dessous.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
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
                scrollBehavior = scrollBehavior,
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
                // #494 PR3 — résultats À PLAT : chaque résultat porte son origine (catégorie) en fil
                // d'Ariane, et garde son contrôle éditable inline. Plus de regroupement par en-tête de
                // section : l'origine vit sur chaque ligne (utile quand des résultats de catégories
                // différentes se suivent).
                val results = filtered.flatMap { section -> section.items.map { section.title to it } }
                items(results, key = { "res:${it.second.id}" }) { (origin, item) ->
                    SettingsSearchResult(origin = origin, render = renderers[item.id])
                }
            }
        }
    }
}

/**
 * A flattened search result (#494 PR3) : the row's own editable control kept inline, preceded by a
 * « fil d'Ariane » naming the [origin] category. Grouping by section header is dropped in search mode
 * so each result self-describes — useful when results from different categories interleave. [render]
 * is the catalogue renderer looked up by id; it is never expected to be null (every filtered item has
 * a renderer), but it stays nullable so a stale id degrades to just the breadcrumb instead of crashing.
 */
@Composable
private fun SettingsSearchResult(origin: String, render: (@Composable () -> Unit)?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = origin,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        render?.invoke()
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
internal fun buildSettingsCatalogue(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
    startScreenState: StartScreenSettingsState,
    onStartScreenIntent: (StartScreenSettingsIntent) -> Unit,
    onOpenProxy: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenAccountAbout: () -> Unit,
    onOpenBlacklist: () -> Unit,
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
            futureRow(
                id = "future_prefetch_wifi_only",
                title = stringResource(R.string.settings_future_prefetch_wifi_only),
            ),
            futureRow(
                id = "future_data_saver",
                title = stringResource(R.string.settings_future_data_saver),
            ),
            futureRow(
                id = "future_image_cache_size",
                title = stringResource(R.string.settings_future_image_cache_size),
            ),
            futureRow(
                id = "future_clear_cache_on_start",
                title = stringResource(R.string.settings_future_clear_cache_on_start),
            ),
            futureRow(
                id = "future_offline_mode",
                title = stringResource(R.string.settings_future_offline_mode),
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
            futureRow(
                id = "future_resume_last_topic",
                title = stringResource(R.string.settings_future_resume_last_topic),
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
            futureRow(
                id = "future_material_you",
                title = stringResource(R.string.settings_future_material_you),
            ),
            futureRow(
                id = "future_ui_colors",
                title = stringResource(R.string.settings_future_ui_colors),
            ),
            futureRow(
                id = "future_classic_theme",
                title = stringResource(R.string.settings_future_classic_theme),
            ),
            futureRow(
                id = "future_amoled_auto_night",
                title = stringResource(R.string.settings_future_amoled_auto_night),
            ),
            futureRow(
                id = "future_smiley_size",
                title = stringResource(R.string.settings_future_smiley_size),
            ),
            futureRow(
                id = "future_show_avatars",
                title = stringResource(R.string.settings_future_show_avatars),
            ),
            futureRow(
                id = "future_show_signatures",
                title = stringResource(R.string.settings_future_show_signatures),
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
            toggleRow(
                id = "flags_funny_empty_state",
                title = stringResource(R.string.settings_flags_funny_empty_state_title),
                description = stringResource(R.string.settings_flags_funny_empty_state_description),
                checked = state.funnyEmptyState,
                enabled = state.canToggleFunnyEmptyState,
                errorRes = R.string.settings_flags_funny_empty_state_persist_failed
                    .takeIf { state.funnyEmptyStateError },
                onCheckedChange = { onIntent(SettingsIntent.FunnyEmptyStateChanged(it)) },
            ),
            futureRow(
                id = "future_flags_sort",
                title = stringResource(R.string.settings_future_flags_sort),
            ),
            futureRow(
                id = "future_flags_on_listing",
                title = stringResource(R.string.settings_future_flags_on_listing),
            ),
            futureRow(
                id = "future_flags_mark_read_on_scroll",
                title = stringResource(R.string.settings_future_flags_mark_read_on_scroll),
            ),
            futureRow(
                id = "future_flags_by_subcategory",
                title = stringResource(R.string.settings_future_flags_by_subcategory),
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
            toggleRow(
                id = "topic_signatures",
                title = stringResource(R.string.settings_topic_signatures_title),
                description = stringResource(R.string.settings_topic_signatures_description),
                checked = state.topicSignatures,
                enabled = state.canToggleTopicSignatures,
                errorRes = R.string.settings_topic_signatures_persist_failed
                    .takeIf { state.topicSignaturesError },
                onCheckedChange = { onIntent(SettingsIntent.TopicSignaturesChanged(it)) },
            ),
            // #332 — replaces the former `future_collapse_quotes` placeholder, now shipped.
            toggleRow(
                id = "fold_long_quotes",
                title = stringResource(R.string.settings_fold_long_quotes_title),
                description = stringResource(R.string.settings_fold_long_quotes_description),
                checked = state.foldLongQuotes,
                enabled = state.canToggleFoldLongQuotes,
                errorRes = R.string.settings_fold_long_quotes_persist_failed
                    .takeIf { state.foldLongQuotesError },
                onCheckedChange = { onIntent(SettingsIntent.FoldLongQuotesChanged(it)) },
            ),
            // #105 — afficher l'ascenseur de lecture (sujets et MP), retour bêta styx42.
            toggleRow(
                id = "show_scrollbar",
                title = stringResource(R.string.settings_show_scrollbar_title),
                description = stringResource(R.string.settings_show_scrollbar_description),
                checked = state.showScrollbar,
                enabled = state.canToggleShowScrollbar,
                errorRes = R.string.settings_show_scrollbar_persist_failed
                    .takeIf { state.showScrollbarError },
                onCheckedChange = { onIntent(SettingsIntent.ShowScrollbarChanged(it)) },
            ),
            futureRow(
                id = "future_restore_read_position",
                title = stringResource(R.string.settings_future_restore_read_position),
            ),
            futureRow(
                id = "future_swipe_page",
                title = stringResource(R.string.settings_future_swipe_page),
            ),
            futureRow(
                id = "future_prefetch",
                title = stringResource(R.string.settings_future_prefetch),
            ),
            futureRow(
                id = "future_open_external_in_app",
                title = stringResource(R.string.settings_future_open_external_in_app),
            ),
            futureRow(
                id = "future_autoplay_animations",
                title = stringResource(R.string.settings_future_autoplay_animations),
            ),
            futureRow(
                id = "future_fullscreen_reading",
                title = stringResource(R.string.settings_future_fullscreen_reading),
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
            // #806 — writing-surface radio group (3 single-choice rows, one per preset). The
            // group picks WHICH surface a write action opens ; the quote-cards toggle below picks
            // how citations RENDER inside it — deliberately separate copy so the two don't blur.
            radioRow(
                id = "writing_surface_sheet",
                title = stringResource(R.string.settings_writing_surface_sheet),
                description = stringResource(R.string.settings_writing_surface_sheet_description),
                keywords = listOf(
                    stringResource(R.string.settings_writing_surface_title),
                    "feuille",
                    "réponse rapide",
                    "plein écran",
                    "éditeur",
                ),
                selected = state.writingSurfacePreset == WritingSurfacePreset.SHEET,
                enabled = state.canChangeWritingSurfacePreset,
                onSelect = { onIntent(SettingsIntent.SetWritingSurfacePreset(WritingSurfacePreset.SHEET)) },
                groupTitle = stringResource(R.string.settings_writing_surface_title),
            ),
            radioRow(
                id = "writing_surface_sheet_except_quotes",
                title = stringResource(R.string.settings_writing_surface_sheet_except_quotes),
                description = stringResource(R.string.settings_writing_surface_sheet_except_quotes_description),
                keywords = listOf(
                    stringResource(R.string.settings_writing_surface_title),
                    "feuille",
                    "citation",
                    "plein écran",
                    "éditeur",
                ),
                selected = state.writingSurfacePreset == WritingSurfacePreset.SHEET_EXCEPT_QUOTES,
                enabled = state.canChangeWritingSurfacePreset,
                onSelect = {
                    onIntent(SettingsIntent.SetWritingSurfacePreset(WritingSurfacePreset.SHEET_EXCEPT_QUOTES))
                },
            ),
            radioRow(
                id = "writing_surface_full_editor",
                title = stringResource(R.string.settings_writing_surface_full_editor),
                description = stringResource(R.string.settings_writing_surface_full_editor_description),
                keywords = listOf(
                    stringResource(R.string.settings_writing_surface_title),
                    "feuille",
                    "plein écran",
                    "éditeur",
                ),
                selected = state.writingSurfacePreset == WritingSurfacePreset.FULL_EDITOR,
                enabled = state.canChangeWritingSurfacePreset,
                onSelect = { onIntent(SettingsIntent.SetWritingSurfacePreset(WritingSurfacePreset.FULL_EDITOR)) },
                errorRes = R.string.settings_writing_surface_persist_failed
                    .takeIf { state.writingSurfacePresetError },
            ),
            toggleRow(
                id = "quote_cards_enabled",
                title = stringResource(R.string.settings_quote_cards_title),
                description = stringResource(R.string.settings_quote_cards_description),
                checked = state.quoteCardsEnabled,
                enabled = state.canToggleQuoteCardsEnabled,
                errorRes = R.string.settings_quote_cards_persist_failed
                    .takeIf { state.quoteCardsEnabledError },
                onCheckedChange = { onIntent(SettingsIntent.QuoteCardsEnabledChanged(it)) },
            ),
            futureRow(
                id = "future_auto_signature",
                title = stringResource(R.string.settings_future_auto_signature),
            ),
            futureRow(
                id = "future_auto_drafts",
                title = stringResource(R.string.settings_future_auto_drafts),
            ),
            futureRow(
                id = "future_recent_smileys",
                title = stringResource(R.string.settings_future_recent_smileys),
            ),
            futureRow(
                id = "future_spell_check",
                title = stringResource(R.string.settings_future_spell_check),
            ),
            futureRow(
                id = "future_custom_bbcode_toolbar",
                title = stringResource(R.string.settings_future_custom_bbcode_toolbar),
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
            futureRow(
                id = "future_compress_upload",
                title = stringResource(R.string.settings_future_compress_upload),
            ),
            futureRow(
                id = "future_strip_exif",
                title = stringResource(R.string.settings_future_strip_exif),
            ),
            futureRow(
                id = "future_upload_wifi_only",
                title = stringResource(R.string.settings_future_upload_wifi_only),
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
            // #6, ADR-014 §4 — experimental opt-in for the MPStorage write-back. OFF by default ; the
            // description warns it is experimental (the write contract was never observed live).
            toggleRow(
                id = "sync_private_messages_write",
                title = stringResource(R.string.settings_mp_sync_write_title),
                description = stringResource(R.string.settings_mp_sync_write_description),
                checked = state.syncPrivateMessagesWriteEnabled,
                enabled = state.canToggleSyncPrivateMessagesWriteEnabled,
                errorRes = R.string.settings_mp_sync_write_persist_failed
                    .takeIf { state.syncPrivateMessagesWriteEnabledError },
                onCheckedChange = { onIntent(SettingsIntent.SyncPrivateMessagesWriteEnabledChanged(it)) },
            ),
            futureRow(
                id = "future_mp_push",
                title = stringResource(R.string.settings_future_mp_push),
            ),
            futureRow(
                id = "future_mp_preview",
                title = stringResource(R.string.settings_future_mp_preview),
            ),
            futureRow(
                id = "future_mp_read_receipt",
                title = stringResource(R.string.settings_future_mp_read_receipt),
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
            futureRow(
                id = "future_hfr_profile_settings",
                title = stringResource(R.string.settings_future_hfr_profile_settings),
            ),
            futureRow(
                id = "future_multi_account",
                title = stringResource(R.string.settings_future_multi_account),
            ),
            futureRow(
                id = "future_auto_logout",
                title = stringResource(R.string.settings_future_auto_logout),
            ),
        ),
    ),
    // Notifications (future category, all rows disabled — searchable). Draft §10.
    SettingsCatalogueSection(
        id = "notifications",
        title = stringResource(R.string.settings_section_notifications),
        items = listOf(
            futureRow(
                id = "future_notif_push",
                title = stringResource(R.string.settings_future_notif_push),
            ),
            futureRow(
                id = "future_notif_mp",
                title = stringResource(R.string.settings_future_notif_mp),
            ),
            futureRow(
                id = "future_notif_mentions",
                title = stringResource(R.string.settings_future_notif_mentions),
            ),
            futureRow(
                id = "future_notif_polling",
                title = stringResource(R.string.settings_future_notif_polling),
            ),
            futureRow(
                id = "future_notif_dnd",
                title = stringResource(R.string.settings_future_notif_dnd),
            ),
            futureRow(
                id = "future_notif_sound_vibration",
                title = stringResource(R.string.settings_future_notif_sound_vibration),
            ),
        ),
    ),
    // Accessibilité (future category, all rows disabled — searchable). Draft §11.
    SettingsCatalogueSection(
        id = "accessibility",
        title = stringResource(R.string.settings_section_accessibility),
        items = listOf(
            futureRow(
                id = "future_a11y_force_system_text",
                title = stringResource(R.string.settings_future_a11y_force_system_text),
            ),
            futureRow(
                id = "future_a11y_high_contrast",
                title = stringResource(R.string.settings_future_a11y_high_contrast),
            ),
            futureRow(
                id = "future_a11y_reduce_motion",
                title = stringResource(R.string.settings_future_a11y_reduce_motion),
            ),
            futureRow(
                id = "future_a11y_timezone",
                title = stringResource(R.string.settings_future_a11y_timezone),
            ),
            futureRow(
                id = "future_a11y_language",
                title = stringResource(R.string.settings_future_a11y_language),
            ),
        ),
    ),
    // Extensions et filtrage (future category, all rows disabled — searchable). Draft §12.
    SettingsCatalogueSection(
        id = "extensions",
        title = stringResource(R.string.settings_section_extensions),
        items = listOf(
            futureRow(
                id = "future_ext_ignore_list",
                title = stringResource(R.string.settings_future_ext_ignore_list),
            ),
            futureRow(
                id = "future_ext_keyword_filters",
                title = stringResource(R.string.settings_future_ext_keyword_filters),
            ),
            // #509 — the blacklist is the first real extension: a nav row to the « Utilisateurs
            // masqués » sub-page (the other extension entries stay futureRow placeholders for now).
            navRow(
                id = "ext_blacklist",
                title = stringResource(R.string.settings_future_ext_blacklist),
                description = stringResource(R.string.settings_blacklist_description),
                keywords = listOf(
                    "blacklist", "ignoré", "ignorer", "masquer", "masqués", "utilisateurs", "liste noire",
                ),
                onClick = onOpenBlacklist,
            ),
            futureRow(
                id = "future_ext_color_tag",
                title = stringResource(R.string.settings_future_ext_color_tag),
            ),
            futureRow(
                id = "future_ext_qualitay",
                title = stringResource(R.string.settings_future_ext_qualitay),
            ),
            futureRow(
                id = "future_ext_redflag",
                title = stringResource(R.string.settings_future_ext_redflag),
            ),
            futureRow(
                id = "future_ext_community",
                title = stringResource(R.string.settings_future_ext_community),
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

/**
 * One option of a single-choice radio group (#806 writing surface) : a leading M3 [RadioButton] with
 * the whole row selectable (`Role.RadioButton` semantics — the radio is not a separate touch target,
 * so its own `onClick` is `null`). Each option is its own catalogue row so it stays individually
 * searchable ; the group is the set of rows firing the same intent family. [groupTitle] renders a
 * small header above the FIRST row of the group ; [errorRes] renders the group's shared persist
 * error under the LAST row.
 */
@Suppress("LongParameterList") // row descriptor: id + texts + keywords + selection + slots + callback.
private fun radioRow(
    id: String,
    title: String,
    description: String,
    keywords: List<String>,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    groupTitle: String? = null,
    errorRes: Int? = null,
): SettingsCatalogueRow = SettingsCatalogueRow(
    searchable = SettingsSearchableItem(id = id, title = title, description = description, keywords = keywords),
    render = {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (groupTitle != null) {
                Text(
                    text = groupTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            RedfaceSettingsListItem(
                title = title,
                description = description,
                enabled = enabled,
                modifier = Modifier.selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onSelect,
                ),
                leadingContent = {
                    RadioButton(selected = selected, onClick = null, enabled = enabled)
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

/**
 * A planned (not-yet-shipped) row: disabled but still searchable (`enabled = false`), carrying a small
 * « À venir » badge in its trailing slot to make the planned status explicit (cf. [FutureBadge]).
 */
private fun futureRow(
    id: String,
    title: String,
): SettingsCatalogueRow = SettingsCatalogueRow(
    searchable = SettingsSearchableItem(id = id, title = title, enabled = false),
    render = {
        RedfaceSettingsListItem(
            title = title,
            enabled = false,
            trailingContent = { FutureBadge() },
        )
    },
)

/**
 * The « À venir » pill shown in the trailing slot of a [futureRow]: a sober tonal `Surface` (rounded,
 * `secondaryContainer`) wrapping a `labelSmall` label. Signals the planned status without competing
 * with the disabled (greyed) row text.
 */
@Composable
private fun FutureBadge() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = stringResource(R.string.settings_future_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
