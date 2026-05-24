package fr.forumhfr.redface2.core.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.ui.R

/**
 * Account / alpha-tools menu shown in the top-right of every main screen (#198).
 *
 * Pure UI component — does not own any state. The host (typically `RedfaceNavHost`) collects
 * `authState` from an `AppAccountViewModel` and routes the callbacks to the active back stack,
 * keeping the account-menu logic out of every feature ViewModel.
 *
 * Badge shape is **square with rounded corners** (not a circle), aligned with the convention
 * picked for [fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar].
 *
 * Anti-flicker contract: when [authState] is `null` (cookie jar still warming up from
 * DataStore) we render a neutral badge with "…" — never an "Anonymous" state — so a cold start
 * does not surface a fake "Se connecter" affordance for a frame.
 */
@Composable
@Suppress("LongParameterList") // composant exposé partagé : chaque callback a un call-site distinct côté host.
fun RedfaceAccountMenu(
    authState: AuthState?,
    versionName: String,
    versionCode: Int,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onReportContent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        AccountBadge(
            authState = authState,
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            // Header line — never an action, just the current account status.
            AccountStatusHeader(authState = authState)
            HorizontalDivider()

            when (authState) {
                null -> Unit
                AuthState.Anonymous -> DropdownMenuItem(
                    text = { Text(stringResource(R.string.account_menu_login)) },
                    onClick = {
                        expanded = false
                        onLogin()
                    },
                )

                is AuthState.Authenticated -> DropdownMenuItem(
                    text = { Text(stringResource(R.string.account_menu_logout)) },
                    onClick = {
                        expanded = false
                        onLogout()
                    },
                )
            }

            DropdownMenuItem(
                text = { Text(stringResource(R.string.account_menu_settings)) },
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.account_menu_diagnostics)) },
                onClick = {
                    expanded = false
                    onOpenDiagnostics()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.account_menu_report_content)) },
                onClick = {
                    expanded = false
                    onReportContent()
                },
            )

            HorizontalDivider()
            // Version stays inert — informational, not an action. Padded as a non-clickable
            // item so the menu stays Material 3-shaped without inviting a tap.
            Text(
                text = stringResource(R.string.account_menu_version_footer, versionName, versionCode),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AccountBadge(
    authState: AuthState?,
    onClick: () -> Unit,
) {
    val label = when (authState) {
        null -> "…"
        AuthState.Anonymous -> "?"
        is AuthState.Authenticated -> authState.pseudo.firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    }
    val containerColor = when (authState) {
        is AuthState.Authenticated -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when (authState) {
        is AuthState.Authenticated -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(BADGE_CORNER_RADIUS),
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier
            .size(BADGE_SIZE)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AccountStatusHeader(authState: AuthState?) {
    val text = when (authState) {
        null -> stringResource(R.string.account_menu_status_loading)
        AuthState.Anonymous -> stringResource(R.string.account_menu_status_anonymous)
        is AuthState.Authenticated -> stringResource(R.string.account_menu_status_authenticated, authState.pseudo)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(HEADER_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val BADGE_SIZE = 36.dp
private val BADGE_CORNER_RADIUS = 8.dp
private val HEADER_PADDING = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
