package fr.forumhfr.redface2.feature.settings

import android.widget.ImageView
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.preferences.AppLauncherIcon

private const val ICON_COLUMNS = 2
private const val ICON_PREVIEW_SIZE = 72

@Composable
fun SettingsAppIconScreen(
    onBack: () -> Unit,
    iconResource: (AppLauncherIcon) -> Int,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsAppIconContent(
        state = state,
        callbacks = SettingsAppIconCallbacks(onBack, viewModel::submit),
        iconResource = iconResource,
        modifier = modifier,
        topBarActions = topBarActions,
    )
}

internal data class SettingsAppIconCallbacks(
    val onBack: () -> Unit,
    val onIntent: (SettingsIntent) -> Unit,
)

@Composable
internal fun SettingsAppIconContent(
    state: SettingsState,
    callbacks: SettingsAppIconCallbacks,
    iconResource: (AppLauncherIcon) -> Int,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val persistError = stringResource(R.string.settings_display_launcher_icon_persist_failed)
    LaunchedEffect(state.appLauncherIconError) {
        if (state.appLauncherIconError) snackbar.showSnackbar(persistError)
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsSubPageTopBar(
                title = stringResource(R.string.settings_display_launcher_icon_title),
                onBack = callbacks.onBack,
                topBarActions = topBarActions,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppIconGallery(
                state = state,
                iconResource = iconResource,
                onIntent = callbacks.onIntent,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { callbacks.onIntent(SettingsIntent.ApplyAppLauncherIcon) },
                enabled = state.canChangeAppLauncherIcon && state.pendingAppLauncherIcon != state.appLauncherIcon,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_display_launcher_icon_apply))
            }
        }
    }
}

@Composable
private fun AppIconGallery(
    state: SettingsState,
    iconResource: (AppLauncherIcon) -> Int,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(ICON_COLUMNS),
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(AppLauncherIcon.selectable, key = { it.name }) { icon ->
            AppIconCard(
                icon = icon,
                state = state,
                resource = iconResource(icon),
                onSelect = { onIntent(SettingsIntent.AppLauncherIconChanged(icon)) },
            )
        }
        item(span = { GridItemSpan(ICON_COLUMNS) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_display_launcher_icon_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_display_launcher_icon_personal_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppIconCard(icon: AppLauncherIcon, state: SettingsState, resource: Int, onSelect: () -> Unit) {
    val name = stringResource(launcherIconNameRes(icon))
    val selected = state.pendingAppLauncherIcon == icon
    Card(
        modifier = Modifier.fillMaxWidth().testTag("app_icon_${icon.name}").selectable(
            selected = selected,
            enabled = state.canChangeAppLauncherIcon,
            role = Role.RadioButton,
            onClick = onSelect,
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AndroidView(
                factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { view ->
                    view.contentDescription = name
                    view.setImageDrawable(ResourcesCompat.getDrawable(view.resources, resource, view.context.theme))
                },
                modifier = Modifier.size(ICON_PREVIEW_SIZE.dp),
            )
            Text(name, style = MaterialTheme.typography.titleMedium)
            if (state.appLauncherIcon == icon) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.settings_display_launcher_icon_current),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@StringRes
internal fun launcherIconNameRes(icon: AppLauncherIcon): Int = when (icon) {
    AppLauncherIcon.RF1 -> R.string.settings_display_launcher_icon_rf1
    AppLauncherIcon.MONOGRAM -> R.string.settings_display_launcher_icon_monogram
    AppLauncherIcon.BUBBLES -> R.string.settings_display_launcher_icon_bubbles
    AppLauncherIcon.CHIP -> R.string.settings_display_launcher_icon_chip
    else -> R.string.settings_display_launcher_icon_classic
}
