package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
import fr.forumhfr.redface2.core.domain.preferences.PostImageMaxWidth
import fr.forumhfr.redface2.core.domain.preferences.SmileyPickerDecoration
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsChoice
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsChoiceGroup
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsListItem
import fr.forumhfr.redface2.core.ui.theme.LauncherIconClassicBackground
import fr.forumhfr.redface2.core.ui.theme.LauncherIconDarkBackground
import fr.forumhfr.redface2.core.ui.theme.LauncherIconRedBackground
import fr.forumhfr.redface2.core.ui.theme.LauncherIconRoseBackground

/**
 * #494 — « Affichage » sub-page. Extracts the theme mode (#286), the colours sub-page (#595), and
 * the reading presets (#287: density + font scale) from the former root catalogue, using the shared
 * [RedfaceSettingsChoiceGroup]. Binds its own [SettingsViewModel] instance (DataStore source of truth
 * — same trade-off as `SettingsProxyScreen`).
 */
@Composable
fun SettingsDisplayScreen(
    onBack: () -> Unit,
    onOpenColors: () -> Unit = {},
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingLauncherIcon by remember { mutableStateOf<AppLauncherIcon?>(null) }
    ApplyLauncherIconAfterPersistence(
        pending = pendingLauncherIcon,
        persisted = state.appLauncherIcon,
        isUpdating = state.isUpdatingAppLauncherIcon,
        hasError = state.appLauncherIconError,
        onConsumed = { pendingLauncherIcon = null },
    )
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
            RedfaceSettingsListItem(
                title = stringResource(R.string.settings_nav_colors),
                description = stringResource(R.string.settings_nav_colors_description),
                onClick = onOpenColors,
                trailingContent = { ChevronTrailing() },
            )

            AppLauncherIconSetting(
                selected = state.appLauncherIcon,
                enabled = state.canChangeAppLauncherIcon,
                error = state.appLauncherIconError,
                onSelected = { icon ->
                    pendingLauncherIcon = icon
                    viewModel.submit(SettingsIntent.AppLauncherIconChanged(icon))
                },
            )

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

            // #973 — block-GIF display profile ([AMENDEMENT-v1.5-2]), next to the density presets.
            MediaDisplayProfileSetting(
                selected = state.mediaDisplayProfile,
                enabled = state.canChangeMediaDisplayProfile,
                error = state.mediaDisplayProfileError,
                onSelected = { viewModel.submit(SettingsIntent.MediaDisplayProfileChanged(it)) },
            )

            PostImageMaxWidthSetting(
                selected = state.postImageMaxWidth,
                enabled = state.canChangePostImageMaxWidth,
                error = state.postImageMaxWidthError,
                onSelected = { viewModel.submit(SettingsIntent.PostImageMaxWidthChanged(it)) },
            )

            SmileyPickerDecorationSetting(
                selected = state.smileyPickerDecoration,
                enabled = state.canChangeSmileyPickerDecoration,
                error = state.smileyPickerDecorationError,
                onSelected = { viewModel.submit(SettingsIntent.SmileyPickerDecorationChanged(it)) },
            )

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

            // #666 — bottom navigation bar labels. Extracted to keep SettingsDisplayScreen under
            // detekt's cyclomatic-complexity budget.
            NavBarLabelsSetting(
                checked = state.navBarLabels,
                enabled = state.canToggleNavBarLabels,
                error = state.navBarLabelsError,
                onCheckedChange = { viewModel.submit(SettingsIntent.NavBarLabelsChanged(it)) },
            )
        }
    }
}

/** Keeps PackageManager work out of the ViewModel and only runs it after the DataStore write settles. */
@Composable
private fun ApplyLauncherIconAfterPersistence(
    pending: AppLauncherIcon?,
    persisted: AppLauncherIcon,
    isUpdating: Boolean,
    hasError: Boolean,
    onConsumed: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(pending, persisted, isUpdating, hasError) {
        val desired = pending ?: return@LaunchedEffect
        // Stay pending while the write is in flight, and also while it has settled on a value that
        // is not yet the desired one: only an error or the matching persisted value releases it.
        if (isUpdating) return@LaunchedEffect
        if (!hasError && persisted != desired) return@LaunchedEffect
        if (!hasError) {
            // A PackageManager refusal must not take the settings screen down with it.
            runCatching { applyLauncherIcon(context, desired) }
        }
        onConsumed()
    }
}

/** #326 — four activity-alias choices, with a stable colour-only preview for each background. */
@Composable
private fun AppLauncherIconSetting(
    selected: AppLauncherIcon,
    enabled: Boolean,
    error: Boolean,
    onSelected: (AppLauncherIcon) -> Unit,
) {
    val options = listOf(
        RedfaceSettingsChoice(
            AppLauncherIcon.CLASSIC,
            stringResource(R.string.settings_display_launcher_icon_classic),
        ) { LauncherIconPreview(LauncherIconClassicBackground) },
        RedfaceSettingsChoice(
            AppLauncherIcon.DARK,
            stringResource(R.string.settings_display_launcher_icon_dark),
        ) { LauncherIconPreview(LauncherIconDarkBackground) },
        RedfaceSettingsChoice(
            AppLauncherIcon.ROSE,
            stringResource(R.string.settings_display_launcher_icon_rose),
        ) { LauncherIconPreview(LauncherIconRoseBackground) },
        RedfaceSettingsChoice(
            AppLauncherIcon.RED,
            stringResource(R.string.settings_display_launcher_icon_red),
        ) { LauncherIconPreview(LauncherIconRedBackground) },
    )
    Text(
        text = stringResource(R.string.settings_display_launcher_icon_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.settings_display_launcher_icon_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RedfaceSettingsChoiceGroup(
        options = options,
        selected = selected,
        onSelected = onSelected,
        enabled = enabled,
    )
    if (error) {
        PreferencePersistError(R.string.settings_display_launcher_icon_persist_failed)
    }
}

@Composable
private fun LauncherIconPreview(background: Color) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {}
}

/**
 * #666 — bottom navigation bar labels toggle. Extracted from [SettingsDisplayScreen] so the host stays
 * under detekt's cyclomatic-complexity budget; emits a section title, the toggle and the persist-error
 * line into the caller's Column.
 */
@Composable
private fun NavBarLabelsSetting(
    checked: Boolean,
    enabled: Boolean,
    error: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_display_nav_bar_section_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    DisplayToggleRow(
        title = stringResource(R.string.settings_display_nav_bar_labels_title),
        description = stringResource(R.string.settings_display_nav_bar_labels_description),
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange,
    )
    if (error) {
        PreferencePersistError(R.string.settings_display_nav_bar_labels_persist_failed)
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

/**
 * #991 — largeur maximale fImage des images de contenu. Extrait pour garder l'écran hôte sous les
 * seuils de complexité et conserver ce levier indépendant de l'agrandissement GIF et du mode
 * pleine largeur des posts.
 */
@Composable
private fun PostImageMaxWidthSetting(
    selected: PostImageMaxWidth,
    enabled: Boolean,
    error: Boolean,
    onSelected: (PostImageMaxWidth) -> Unit,
) {
    val intro = stringResource(R.string.settings_img_width_intro_start) +
        " " +
        stringResource(R.string.settings_img_width_b)
    val options = listOf(
        RedfaceSettingsChoice(
            PostImageMaxWidth.P90,
            stringResource(R.string.settings_display_post_image_max_width_90),
        ),
        RedfaceSettingsChoice(
            PostImageMaxWidth.P95,
            stringResource(R.string.settings_display_post_image_max_width_95),
        ),
        RedfaceSettingsChoice(
            PostImageMaxWidth.P99,
            stringResource(R.string.settings_display_post_image_max_width_99),
        ),
        RedfaceSettingsChoice(
            PostImageMaxWidth.P100,
            stringResource(R.string.settings_display_post_image_max_width_100),
        ),
    )
    Text(
        text = stringResource(R.string.settings_display_post_image_max_width_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = intro,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RedfaceSettingsChoiceGroup(
        options = options,
        selected = selected,
        onSelected = onSelected,
        enabled = enabled,
    )
    if (error) {
        PreferencePersistError(R.string.settings_img_width_persist_failed)
    }
}

/**
 * #989 — délimiteur des cellules du picker de smileys. Extrait comme ses voisins pour garder l'écran
 * hôte sous le budget de complexité de detekt. « Aucun » est le défaut : le délimiteur aide à
 * repérer une vignette sur un corpus très hétérogène, il ne change PAS la taille des smileys — le
 * preset qui agrandissait les petits a été rejeté parce qu'il faisait promettre au picker une taille
 * que le message ne respecte pas (#1022).
 */
@Composable
private fun SmileyPickerDecorationSetting(
    selected: SmileyPickerDecoration,
    enabled: Boolean,
    error: Boolean,
    onSelected: (SmileyPickerDecoration) -> Unit,
) {
    val options = listOf(
        RedfaceSettingsChoice(
            SmileyPickerDecoration.NONE,
            stringResource(R.string.settings_display_smiley_decoration_none),
        ),
        RedfaceSettingsChoice(
            SmileyPickerDecoration.OUTLINE,
            stringResource(R.string.settings_display_smiley_decoration_outline),
        ),
        RedfaceSettingsChoice(
            SmileyPickerDecoration.SEPARATORS,
            stringResource(R.string.settings_display_smiley_decoration_separators),
        ),
    )
    Text(
        text = stringResource(R.string.settings_display_smiley_decoration_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.settings_display_smiley_decoration_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RedfaceSettingsChoiceGroup(
        options = options,
        selected = selected,
        onSelected = onSelected,
        enabled = enabled,
    )
    if (error) {
        PreferencePersistError(R.string.settings_display_smiley_decoration_persist_failed)
    }
}

/**
 * #973 ([AMENDEMENT-v1.5-2], exigence XaTriX) — block-GIF display profile setting. The S/M/L
 * labels carry the NUMERIC factors visibly (« S (×1, net) », « M (×1,5) », « L (×2,5) »).
 * Extracted from [SettingsDisplayScreen] so the host stays under detekt's cyclomatic-complexity
 * budget; emits a section title, the intro text, the
 * three-choice group and the persist-error line into the caller's Column.
 */
@Composable
private fun MediaDisplayProfileSetting(
    selected: MediaDisplayProfile,
    enabled: Boolean,
    error: Boolean,
    onSelected: (MediaDisplayProfile) -> Unit,
) {
    val options = listOf(
        RedfaceSettingsChoice(MediaDisplayProfile.S, stringResource(R.string.settings_display_media_profile_small)),
        RedfaceSettingsChoice(MediaDisplayProfile.M, stringResource(R.string.settings_display_media_profile_medium)),
        RedfaceSettingsChoice(MediaDisplayProfile.L, stringResource(R.string.settings_display_media_profile_large)),
    )
    Text(
        text = stringResource(R.string.settings_display_media_profile_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.settings_display_media_profile_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RedfaceSettingsChoiceGroup(
        options = options,
        selected = selected,
        onSelected = onSelected,
        enabled = enabled,
    )
    if (error) {
        PreferencePersistError(R.string.settings_display_media_profile_persist_failed)
    }
}
