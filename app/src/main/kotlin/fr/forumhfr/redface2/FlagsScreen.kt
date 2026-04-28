package fr.forumhfr.redface2

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
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
    val unreadMpCount by viewModel.unreadMpCount.collectAsStateWithLifecycle()
    val context = LocalContext.current

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

        // Render nothing while authState is null (cookie jar still warming up the cache from
        // DataStore). Defaulting to "Se connecter à HFR" here would reintroduce the cold-start
        // flicker the upstream layers are explicitly designed to avoid.
        authState?.let { state ->
            AuthFooter(
                state = state,
                unreadMpCount = unreadMpCount,
                onLoginRequested = onLoginRequested,
                onLogoutRequested = viewModel::logout,
            )
        }

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

        // In-app reporting channel required by the Google Play Child Safety Standards
        // policy. The CSAE page (docs/legal/csae/index.html) advertises this affordance, so
        // the app must expose it visibly to users. ACTION_SENDTO with a mailto: URI hands
        // off to the user's email client without leaving the app's context.
        val reportSubject = stringResource(R.string.report_email_subject)
        val reportNoClientMessage = stringResource(R.string.report_no_email_client)
        TextButton(
            onClick = {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = "mailto:$REPORT_EMAIL".toUri()
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
                    putExtra(Intent.EXTRA_SUBJECT, reportSubject)
                }
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, reportNoClientMessage, Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.report_content_cta))
        }
    }
}

private const val REPORT_EMAIL = "xat@azora.fr"

@Composable
private fun AuthFooter(
    state: AuthState,
    unreadMpCount: Int?,
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
            // The unread MP count is fetched from forum1.php?cat=prive — only resolves
            // when the session is actually valid HFR-side (HFR redirects to login
            // otherwise), so showing it doubles as a "really logged in" proof beyond
            // what the cookie alone tells us.
            unreadMpCount?.let { count ->
                Text(
                    text = stringResource(R.string.flags_unread_mps, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onLogoutRequested,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.flags_logout_cta))
            }
        }
    }
}
