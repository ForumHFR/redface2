package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.browser.openAppDefaultLinkSettings
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsSection

/**
 * #494 v2 — détail générique d'une catégorie de la racine « catégories d'abord ». Rend la/les
 * section(s) du catalogue associée(s) à [categoryId] (cf. [sectionIdsForCategory]) en réutilisant
 * [buildSettingsCatalogue] : mêmes renderers et même câblage MVI que la recherche. Scaffold à barre
 * simple avec retour. Les catégories adossées à une sous-page dédiée (Affichage, Images, Compte) ne
 * passent PAS par ici — elles ouvrent directement leur écran (cf. [routeSettingsCategory]).
 */
@Composable
@Suppress("LongParameterList") // détail : id + back + 6 nav-rows de sous-pages + 2 VMs + slots.
fun SettingsCategoryDetailScreen(
    categoryId: String,
    onBack: () -> Unit,
    onOpenProxy: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenAccountAbout: () -> Unit,
    onOpenBlacklist: () -> Unit,
    onOpenAppIcon: () -> Unit = {},
    onOpenSanctions: () -> Unit = {},
    isAuthenticated: Boolean = false,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
    startScreenViewModel: StartScreenSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startScreenState by startScreenViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sections = buildSettingsCatalogue(
        state = state,
        onIntent = viewModel::submit,
        startScreenState = startScreenState,
        onStartScreenIntent = startScreenViewModel::submit,
        onOpenProxy = onOpenProxy,
        onOpenMaintenance = onOpenMaintenance,
        onOpenDisplay = onOpenDisplay,
        onOpenAppIcon = onOpenAppIcon,
        onOpenImages = onOpenImages,
        onOpenAccountAbout = onOpenAccountAbout,
        onOpenSanctions = onOpenSanctions,
        isAuthenticated = isAuthenticated,
        onOpenBlacklist = onOpenBlacklist,
        hfrLinkStatus = rememberHfrLinkHandlingStatus(),
        onOpenHfrLinkSettings = { openAppDefaultLinkSettings(context) },
    )
    val wanted = sectionIdsForCategory(categoryId)
    val shown = sections.filter { it.id in wanted }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsSubPageTopBar(
                title = stringResource(categoryTitleRes(categoryId)),
                onBack = onBack,
                topBarActions = topBarActions,
            )
        },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            shown.forEachIndexed { index, section ->
                // En-tête de section seulement quand plusieurs sections cohabitent (catégorie « À venir »).
                if (shown.size > 1) {
                    item(key = "section:${section.id}") {
                        if (index > 0) HorizontalDivider()
                        RedfaceSettingsSection(section.title)
                    }
                }
                items(section.items, key = { "item:${it.searchable.id}" }) { row ->
                    row.render()
                }
            }
        }
    }
}
