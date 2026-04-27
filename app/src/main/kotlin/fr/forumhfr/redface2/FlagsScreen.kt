package fr.forumhfr.redface2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.ui.RedfacePlaceholderScreen

@Composable
fun FlagsScreen(
    onOpenUnreadTopic: () -> Unit,
    onOpenTrackedCategory: () -> Unit,
    onLoginRequested: () -> Unit,
) {
    val viewModel = hiltViewModel<FlagsHomeViewModel>()
    val authState by viewModel.authState.collectAsStateWithLifecycle()

    RedfacePlaceholderScreen(
        title = stringResource(R.string.flags_title),
        body = stringResource(R.string.flags_body),
    ) {
        Button(onClick = onOpenUnreadTopic) {
            Text(text = stringResource(R.string.flags_open_unread_topic))
        }
        OutlinedButton(onClick = onOpenTrackedCategory) {
            Text(text = stringResource(R.string.flags_open_category))
        }

        AuthFooter(
            state = authState,
            onLoginRequested = onLoginRequested,
            onLogoutRequested = viewModel::logout,
        )

        // Phase 1A — version surfaced on the home placeholder so dogfood builds advertise
        // their lineage. Will move to :feature:settings (About screen) once that module
        // gets real content.
        Text(
            text = stringResource(
                R.string.app_version_footer,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AuthFooter(
    state: AuthState,
    onLoginRequested: () -> Unit,
    onLogoutRequested: () -> Unit,
) {
    when (state) {
        AuthState.Anonymous -> TextButton(
            onClick = onLoginRequested,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.flags_login_cta))
        }

        is AuthState.Authenticated -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.flags_logged_in_as, state.pseudo),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onLogoutRequested,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.flags_logout_cta))
            }
        }
    }
}
