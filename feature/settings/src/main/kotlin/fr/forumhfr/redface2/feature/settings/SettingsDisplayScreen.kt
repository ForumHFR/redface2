package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsChoice
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsChoiceGroup

/**
 * #494 — « Affichage » sub-page. Extracts the theme (#286: 3-way Clair / Système / Sombre + AMOLED,
 * only meaningful when the effective theme is dark) and the reading presets (#287: density + font
 * scale) from the former root catalogue, using the shared [RedfaceSettingsChoiceGroup]. Binds its
 * own [SettingsViewModel] instance (DataStore source of truth — same trade-off as `SettingsProxyScreen`).
 */
@Composable
fun SettingsDisplayScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The AMOLED toggle is only meaningful when the app will actually render dark — forced DARK, or
    // SYSTEM while the OS is in dark mode. Computed here so the switch is disabled otherwise.
    val effectiveDark = when (state.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val themeOptions = listOf(
        RedfaceSettingsChoice(ThemeMode.LIGHT, stringResource(R.string.settings_theme_light)),
        RedfaceSettingsChoice(ThemeMode.SYSTEM, stringResource(R.string.settings_theme_system)),
        RedfaceSettingsChoice(ThemeMode.DARK, stringResource(R.string.settings_theme_dark)),
    )
    val densityOptions = listOf(
        RedfaceSettingsChoice(DisplayDensity.COMFORT, stringResource(R.string.settings_display_density_comfort)),
        RedfaceSettingsChoice(DisplayDensity.COMPACT, stringResource(R.string.settings_display_density_compact)),
    )
    val fontScaleOptions = listOf(
        RedfaceSettingsChoice(FontScalePreference.S, stringResource(R.string.settings_display_font_scale_small)),
        RedfaceSettingsChoice(FontScalePreference.M, stringResource(R.string.settings_display_font_scale_medium)),
        RedfaceSettingsChoice(FontScalePreference.L, stringResource(R.string.settings_display_font_scale_large)),
    )
    // #518 follow-up — immersive nav-bar reveal behaviours.
    val navBarRevealOptions = listOf(
        RedfaceSettingsChoice(
            ImmersiveNavBarReveal.MANUAL,
            stringResource(R.string.settings_display_nav_bar_reveal_manual),
        ),
        RedfaceSettingsChoice(
            ImmersiveNavBarReveal.AT_BOTTOM,
            stringResource(R.string.settings_display_nav_bar_reveal_at_bottom),
        ),
        RedfaceSettingsChoice(
            ImmersiveNavBarReveal.ON_SCROLL_UP,
            stringResource(R.string.settings_display_nav_bar_reveal_on_scroll_up),
        ),
    )
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsSubPageTopBar(
                title = stringResource(R.string.settings_nav_display),
                onBack = onBack,
                topBarActions = topBarActions,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Theme (#286).
            Text(
                text = stringResource(R.string.settings_theme_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_theme_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RedfaceSettingsChoiceGroup(
                options = themeOptions,
                selected = state.themeMode,
                onSelected = { viewModel.submit(SettingsIntent.ThemeModeChanged(it)) },
                enabled = state.canChangeThemeMode,
            )
            if (state.themeModeError) {
                PreferencePersistError(R.string.settings_theme_persist_failed)
            }
            DisplayToggleRow(
                title = stringResource(R.string.settings_theme_amoled_title),
                description = stringResource(R.string.settings_theme_amoled_description),
                checked = state.amoledEnabled,
                enabled = state.canToggleAmoled && effectiveDark,
                onCheckedChange = { viewModel.submit(SettingsIntent.AmoledEnabledChanged(it)) },
            )
            if (state.amoledError) {
                PreferencePersistError(R.string.settings_theme_amoled_persist_failed)
            }

            // Reading presets (#287).
            Text(
                text = stringResource(R.string.settings_display_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_display_density_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RedfaceSettingsChoiceGroup(
                options = densityOptions,
                selected = state.displayDensity,
                onSelected = { viewModel.submit(SettingsIntent.DisplayDensityChanged(it)) },
                enabled = state.canChangeDisplayDensity,
            )
            if (state.displayDensityError) {
                PreferencePersistError(R.string.settings_display_density_persist_failed)
            }
            Text(
                text = stringResource(R.string.settings_display_font_scale_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RedfaceSettingsChoiceGroup(
                options = fontScaleOptions,
                selected = state.fontScale,
                onSelected = { viewModel.submit(SettingsIntent.FontScaleChanged(it)) },
                enabled = state.canChangeFontScale,
            )
            if (state.fontScaleError) {
                PreferencePersistError(R.string.settings_display_font_scale_persist_failed)
            }

            // Immersive full-screen (#518).
            Text(
                text = stringResource(R.string.settings_display_fullscreen_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            DisplayToggleRow(
                title = stringResource(R.string.settings_display_hide_nav_bar_title),
                description = stringResource(R.string.settings_display_hide_nav_bar_description),
                checked = state.hideSystemNavBar,
                enabled = state.canToggleHideSystemNavBar,
                onCheckedChange = { viewModel.submit(SettingsIntent.HideSystemNavBarChanged(it)) },
            )
            if (state.hideSystemNavBarError) {
                PreferencePersistError(R.string.settings_display_hide_nav_bar_persist_failed)
            }
            // #518 follow-up — sous-option du plein écran : le bouton retour flottant ne sert que
            // lorsque la barre système est masquée, donc la ligne reste désactivée tant que le
            // plein écran est off (en plus de sa propre garde d'écriture optimiste).
            DisplayToggleRow(
                title = stringResource(R.string.settings_display_immersive_back_button_title),
                description = stringResource(R.string.settings_display_immersive_back_button_description),
                checked = state.immersiveBackButton,
                enabled = state.hideSystemNavBar && state.canToggleImmersiveBackButton,
                onCheckedChange = { viewModel.submit(SettingsIntent.ImmersiveBackButtonChanged(it)) },
            )
            if (state.immersiveBackButtonError) {
                PreferencePersistError(R.string.settings_display_immersive_back_button_persist_failed)
            }
            // #518 follow-up — sous-option du plein écran : quand révéler la barre système masquée selon
            // le défilement. Désactivée tant que le plein écran est off (en plus de sa propre garde).
            Text(
                text = stringResource(R.string.settings_display_nav_bar_reveal_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RedfaceSettingsChoiceGroup(
                options = navBarRevealOptions,
                selected = state.immersiveNavBarReveal,
                onSelected = { viewModel.submit(SettingsIntent.ImmersiveNavBarRevealChanged(it)) },
                enabled = state.hideSystemNavBar && state.canChangeImmersiveNavBarReveal,
            )
            if (state.immersiveNavBarRevealError) {
                PreferencePersistError(R.string.settings_display_nav_bar_reveal_persist_failed)
            }
        }
    }
}

@Composable
private fun DisplayToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
