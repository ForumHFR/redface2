package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsSearchTopBarLabels

/**
 * #494 — resolves the localized labels for the settings shell search top bar from feature strings,
 * keeping `:core:ui` free of settings-specific resources.
 */
@Composable
internal fun rememberSettingsSearchTopBarLabels(): RedfaceSettingsSearchTopBarLabels =
    RedfaceSettingsSearchTopBarLabels(
        title = stringResource(R.string.settings_title),
        searchPlaceholder = stringResource(R.string.settings_search_placeholder),
        backContentDescription = stringResource(R.string.settings_back),
        openSearchContentDescription = stringResource(R.string.settings_search_open),
        closeSearchContentDescription = stringResource(R.string.settings_search_close),
        clearSearchContentDescription = stringResource(R.string.settings_search_clear),
    )

/**
 * #494 — plain Material 3 [TopAppBar] for the settings sub-pages (no search). A back navigation icon
 * (local `ic_arrow_back` rendered with material3 [Icon] — material-icons are forbidden), the page
 * [title], and the optional [topBarActions] slot (the global account menu, wired from navigation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSubPageTopBar(
    title: String,
    onBack: () -> Unit,
    topBarActions: @Composable (() -> Unit)? = null,
    subtitle: String? = null,
) {
    val backLabel = stringResource(R.string.settings_back)
    TopAppBar(
        title = {
            Column {
                Text(title)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = backLabel },
            ) {
                Icon(
                    painter = painterResource(fr.forumhfr.redface2.core.ui.R.drawable.ic_arrow_back),
                    contentDescription = null,
                )
            }
        },
        actions = { topBarActions?.invoke() },
    )
}

/** Settings sub-page shell with an optional account subtitle and account menu. */
@Suppress("LongParameterList") // Shell API: title + back + modifier + subtitle + account slot + content.
@Composable
internal fun RedfaceSettingsScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    topBarActions: @Composable (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsSubPageTopBar(
                title = title,
                onBack = onBack,
                subtitle = subtitle,
                topBarActions = topBarActions,
            )
        },
        content = content,
    )
}

/** Inline persist-failure message for a settings row, shown below the row. */
@Composable
internal fun PreferencePersistError(messageRes: Int) {
    Text(
        text = stringResource(messageRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
