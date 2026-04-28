package fr.forumhfr.redface2.feature.flags

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.ui.FlagItem
import fr.forumhfr.redface2.core.ui.FlagItemDivider

/**
 * Home tab entry point. The [versionName] / [versionCode] params are passed in by `:app`
 * because BuildConfig lives in the application module; surfacing them here keeps the
 * dogfood "what build am I running" affordance on screen without needing `:feature:flags`
 * to depend on the app's BuildConfig.
 */
@Composable
fun FlagsRoute(
    versionName: String,
    versionCode: Int,
    onOpenFlag: (Flag) -> Unit,
    onLoginRequested: () -> Unit,
) {
    val viewModel: FlagsViewModel = hiltViewModel()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val unreadMpCount by viewModel.unreadMpCount.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val flagsState by viewModel.flagsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val reportEmailSubject = stringResource(R.string.flags_report_email_subject)
    val reportNoEmailClient = stringResource(R.string.flags_report_no_email_client)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.flags_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )

            // Render nothing while authState is null (cookie jar warming up). Same
            // anti-flicker convention as PR #91; defaulting to "Anonymous" here would
            // bring the cold-start "Se connecter" flash back.
            authState?.let { state ->
                when (state) {
                    AuthState.Anonymous -> AnonymousBody(onLoginRequested)
                    is AuthState.Authenticated -> AuthenticatedBody(
                        selectedTab = selectedTab,
                        flagsState = flagsState,
                        onSelectTab = viewModel::selectTab,
                        onOpenFlag = onOpenFlag,
                        onRetry = viewModel::refresh,
                    )
                }

                FooterSlot(
                    state = state,
                    unreadMpCount = unreadMpCount,
                    versionLabel = stringResource(
                        R.string.flags_app_version_footer,
                        versionName,
                        versionCode,
                    ),
                    onLogout = viewModel::logout,
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
            }
        }
    }
}

@Composable
private fun AnonymousBody(onLoginRequested: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.flags_login_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onLoginRequested,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.flags_login_cta))
        }
    }
}

@Composable
private fun ColumnScope.AuthenticatedBody(
    selectedTab: FlagType,
    flagsState: FlagsResult?,
    onSelectTab: (FlagType) -> Unit,
    onOpenFlag: (Flag) -> Unit,
    onRetry: () -> Unit,
) {
    val tabs = listOf(
        FlagType.CYAN to stringResource(R.string.flags_tab_my_topics),
        FlagType.RED to stringResource(R.string.flags_tab_read_only),
        FlagType.FAVORITE to stringResource(R.string.flags_tab_favorite),
    )
    val selectedIndex = tabs.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0)

    PrimaryTabRow(selectedTabIndex = selectedIndex) {
        tabs.forEachIndexed { index, (type, label) ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelectTab(type) },
                text = { Text(label, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }

    when (val current = flagsState) {
        null, FlagsResult.Loading -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        is FlagsResult.Failure -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.flags_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.flags_retry))
            }
        }

        is FlagsResult.Success -> {
            if (current.flags.isEmpty()) {
                Text(
                    text = stringResource(R.string.flags_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Without weight(1f), this LazyColumn would consume all remaining
                        // vertical space inside the parent Column (FlagsRoute) and push
                        // FooterSlot off-screen — invariably reproducible on the cyan tab
                        // (127 rows in the captured fixture). Weight is the canonical
                        // Compose pattern for "header + scrollable list + footer".
                        .weight(1f)
                        .clip(RoundedCornerShape(0.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    // Compose `key` rejects duplicates with IllegalArgumentException, but
                    // HFR's `post=` topic id is only guaranteed unique within a category
                    // (cf. AGENTS.md), not globally. Using "cat-topicId" eliminates the
                    // latent crash if the listing ever returns the same topicId in two cats.
                    items(items = current.flags, key = { "${it.cat}-${it.topicId}" }) { flag ->
                        FlagItem(
                            flag = flag,
                            metadata = flagMetadata(flag),
                            onClick = { onOpenFlag(flag) },
                        )
                        FlagItemDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun FooterSlot(
    state: AuthState,
    unreadMpCount: Int?,
    versionLabel: String,
    onLogout: () -> Unit,
    onReportContent: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state is AuthState.Authenticated) {
            Text(
                text = stringResource(R.string.flags_logged_in_as, state.pseudo),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            unreadMpCount?.let { count ->
                Text(
                    text = stringResource(R.string.flags_unread_mps, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.flags_logout_cta))
            }
        }

        Text(
            text = versionLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onReportContent, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.flags_report_content_cta))
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

private const val REPORT_EMAIL = "xat@azora.fr"

@Composable
private fun flagMetadata(flag: Flag): String =
    if (flag.lastReplyAuthor.isNotBlank()) {
        stringResource(
            R.string.flags_item_metadata_with_author,
            flag.lastReplyAuthor,
            flag.replyCount,
            flag.lastReadPage,
            flag.totalPages,
        )
    } else {
        stringResource(
            R.string.flags_item_metadata_no_author,
            flag.replyCount,
            flag.lastReadPage,
            flag.totalPages,
        )
    }
