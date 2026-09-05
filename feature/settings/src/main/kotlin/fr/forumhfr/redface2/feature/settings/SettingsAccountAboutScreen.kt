package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsListItem
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsSection

/**
 * #494 — « Compte HFR et à propos » sub-page. Shows the HFR-account note, sanctions (#294), planned
 * (disabled) profile prefs, the app version, Diagnostics and the report-content flow. There is NO
 * local login/logout: account actions stay in the global account menu surfaced via [topBarActions].
 *
 * The app version is passed in ([versionName] / [versionCode]) rather than read from `BuildConfig`,
 * because `:feature:settings` does not own the app `BuildConfig` (it is `:app`'s) — the same approach
 * as `RedfaceAccountMenu`, wired from `RedfaceNavigation`.
 *
 * This screen is stateless (no `SettingsViewModel`): nothing here reads or mutates a preference.
 */
@Composable
@Suppress("LongParameterList") // state-hoisted Composable: each nav/version param has a distinct call-site.
fun SettingsAccountAboutScreen(
    onBack: () -> Unit,
    versionName: String,
    versionCode: Int,
    onOpenDiagnostics: () -> Unit,
    onReportContent: () -> Unit,
    isAuthenticated: Boolean,
    onOpenSanctions: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsSubPageTopBar(
                title = stringResource(R.string.settings_account_title),
                onBack = onBack,
                topBarActions = topBarActions,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            RedfaceSettingsSection(stringResource(R.string.settings_section_hfr_account))
            Text(
                text = stringResource(R.string.settings_hfr_account_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            SanctionsSettingsItem(isAuthenticated = isAuthenticated, onClick = onOpenSanctions)
            // #311 — planned HFR-profile prefs, shown disabled (still searchable via the root catalogue).
            RedfaceSettingsListItem(
                title = stringResource(R.string.settings_future_hfr_profile),
                enabled = false,
            )

            HorizontalDivider()
            RedfaceSettingsSection(stringResource(R.string.settings_about_version))
            Text(
                // Sideloaded debug builds (versionName stamped `+debug.<sha>`) have NO ship build
                // number — the app-v ledger stamps releases only, and the literal versionCode is a
                // CD safety floor (72), not a build identity. Showing « (build 72) » there misled
                // testers ; the SHA-stamped versionName alone is the honest identity.
                text = if (versionName.contains("+debug.")) {
                    versionName
                } else {
                    stringResource(R.string.settings_about_version_value, versionName, versionCode)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            RedfaceSettingsListItem(
                title = stringResource(R.string.settings_about_diagnostics),
                description = stringResource(R.string.settings_about_diagnostics_description),
                onClick = onOpenDiagnostics,
                trailingContent = { ChevronTrailing() },
            )
            RedfaceSettingsListItem(
                title = stringResource(R.string.settings_about_report),
                onClick = onReportContent,
                trailingContent = { ChevronTrailing() },
            )
        }
    }
}

/** Shared by the account page and search so the anonymous gate and its explanation stay identical. */
@Composable
internal fun SanctionsSettingsItem(isAuthenticated: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(
            enabled = isAuthenticated,
            role = Role.Button,
            onClick = onClick,
        ),
        headlineContent = {
            Text(
                text = stringResource(R.string.sanctions_title),
                color = if (isAuthenticated) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        supportingContent = {
            Text(
                text = stringResource(sanctionsDescriptionRes(isAuthenticated)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { ChevronTrailing() },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

internal fun sanctionsDescriptionRes(isAuthenticated: Boolean): Int = if (isAuthenticated) {
    R.string.settings_sanctions_description
} else {
    R.string.sanctions_sign_in_required
}
