package fr.forumhfr.redface2.feature.messages

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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

/**
 * Messages tab — currently a temporary sober landing for the alpha (#154).
 *
 * Until real MPs land in Phase 3 this screen carries the « compte + outils alpha »
 * affordances that were previously cramped into the Flags footer: pseudo display,
 * logout, app version, content report (mailto), and a diagnostics shortcut. Each
 * block is sectioned so the Phase 3 work can rip out the alpha tools cleanly when
 * the real MP list/threads ship.
 */
@Composable
fun MessagesScreen(
    versionName: String,
    versionCode: Int,
    onLoginRequested: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: MessagesViewModel = hiltViewModel()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val reportEmailSubject = stringResource(R.string.messages_report_email_subject)
    val reportNoEmailClient = stringResource(R.string.messages_report_no_email_client)
    val versionLabel = stringResource(R.string.messages_app_version_footer, versionName, versionCode)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.messages_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            Text(
                text = stringResource(R.string.messages_phase3_notice),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader(stringResource(R.string.messages_section_account))
            AccountBlock(
                authState = authState,
                onLogin = onLoginRequested,
                onLogout = viewModel::logout,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionHeader(stringResource(R.string.messages_section_alpha_tools))
            AlphaToolsBlock(
                versionLabel = versionLabel,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenSettings = onOpenSettings,
                onReportContent = {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = "mailto:$REPORT_EMAIL".toUri()
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
                        putExtra(Intent.EXTRA_SUBJECT, reportEmailSubject)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, reportNoEmailClient, Toast.LENGTH_LONG).show()
                    }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AccountBlock(
    authState: AuthState?,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (authState) {
            null -> Text(
                text = stringResource(R.string.messages_auth_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AuthState.Anonymous -> {
                Text(
                    text = stringResource(R.string.messages_anonymous_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.messages_login_cta))
                }
            }

            is AuthState.Authenticated -> {
                Text(
                    text = stringResource(R.string.messages_logged_in_as, authState.pseudo),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.messages_logout_cta))
                }
            }
        }
    }
}

@Composable
private fun AlphaToolsBlock(
    versionLabel: String,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onReportContent: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = versionLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.messages_diagnostics_cta))
        }
        TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.messages_settings_cta))
        }
        TextButton(onClick = onReportContent, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.messages_report_content_cta))
        }
    }
}

private const val REPORT_EMAIL = "xat@azora.fr"
