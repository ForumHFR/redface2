package fr.forumhfr.redface2.core.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar

/**
 * Account / alpha-tools menu shown in the top-right of every main screen (#198).
 *
 * Pure UI component — does not own any state. The host (typically `RedfaceNavHost`) collects
 * `authState` from an `AppAccountViewModel` and routes the callbacks to the active back stack,
 * keeping the account-menu logic out of every feature ViewModel.
 *
 * Badge shape is a **circle** (#603/#665, XaTriX top-bar redesign): the account « PP » reads as a
 * round avatar. Post-header avatars keep the HFR-web rounded square via
 * [fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar]'s default shape.
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
    onOpenDiagnostics: () -> Unit,
    onReportContent: () -> Unit,
    modifier: Modifier = Modifier,
    // #479 — real HFR avatar of the connected user. Null (anonymous, loading, or no avatar set)
    // falls back to the pseudo-initial badge. The host (RedfaceNavHost) resolves it from the
    // connected user's profile via AppAccountViewModel.
    avatarUrl: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        AccountBadge(
            authState = authState,
            avatarUrl = avatarUrl,
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

            // #494 v2 — « Réglages » a quitté ce menu : c'est désormais la 5e destination de la barre du bas.
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
    avatarUrl: String?,
    onClick: () -> Unit,
) {
    // #479 — when the connected user has a resolved avatar, show it instead of the initial. The
    // text-badge branch below is kept verbatim for anonymous / loading / no-avatar so the
    // anti-flicker contract ("…" never "?") and the existing colour scheme are unchanged.
    val authenticated = authState as? AuthState.Authenticated
    if (authenticated != null && !avatarUrl.isNullOrBlank()) {
        AvatarBadge(
            avatarUrl = avatarUrl,
            pseudo = authenticated.pseudo,
            onClick = onClick,
        )
        return
    }

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
    val accessibilityLabel = stringResource(R.string.account_menu_open_description)

    // Round-2 review (PR #207): migrated from `Surface(modifier = ...clickable(onClick))` to the
    // Material 3 `Surface(onClick = ...)` overload, which (1) clips the ripple to the rounded
    // shape (the previous form rippled a square inside our 8dp rounded corners), (2) injects
    // `Role.Button` for TalkBack so the badge announces as a button rather than a generic
    // "Surface".
    //
    // Round-3 (post review F2): `Surface(onClick=)` does NOT automatically opt the visual into
    // `LocalMinimumInteractiveComponentSize` — the M3 docs are explicit that
    // `Modifier.minimumInteractiveComponentSize()` must be applied *explicitly* and BEFORE
    // `.size(...)` to expand the touch target up to 48dp around the 40dp visual without
    // resizing the painted Surface. Also adds `mergeDescendants = true` so TalkBack reads
    // "Ouvrir le menu compte, bouton" instead of also announcing the decorative Text label
    // (`X` / `?` / `…`) as a separate node.
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        // Subtle hairline so the badge reads as a distinct element against the app-bar surface.
        border = BorderStroke(BADGE_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(BADGE_SIZE)
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel },
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
private fun AvatarBadge(
    avatarUrl: String,
    pseudo: String,
    onClick: () -> Unit,
) {
    // #479 — same interaction contract as the text [AccountBadge]: a clickable M3 `Surface`
    // (ripple clipped to the rounded shape, `Role.Button` for TalkBack) with the 40dp visual and
    // the explicit `minimumInteractiveComponentSize()` (BEFORE `.size`) expanding the touch
    // target to 48dp. The Surface owns the "Ouvrir le menu compte" semantics
    // (`mergeDescendants = true`) so the avatar's own "Avatar de <pseudo>" description does not
    // leak as a second TalkBack node — the badge announces as the menu button, not the avatar.
    val accessibilityLabel = stringResource(R.string.account_menu_open_description)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        // Subtle hairline so the avatar reads as a distinct element against the app-bar surface.
        border = BorderStroke(BADGE_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(BADGE_SIZE)
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel },
    ) {
        RedfaceUserAvatar(
            avatarUrl = avatarUrl,
            author = pseudo,
            size = BADGE_SIZE,
            // Round « PP » to match the circular badge (the post-header default stays rounded-square).
            shape = CircleShape,
        )
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

// 40dp visual size. The explicit `Modifier.minimumInteractiveComponentSize()` applied on
// `AccountBadge` (before `.size(BADGE_SIZE)`) expands the touch target to the Material 3
// 48dp minimum without changing the painted badge — `Surface(onClick = ...)` does NOT
// inject that minimum on its own (cf. M3 docs, Codex rereview on PR #207).
private val BADGE_SIZE = 40.dp
private val BADGE_BORDER_WIDTH = 1.dp
private val HEADER_PADDING = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
