package fr.forumhfr.redface2.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.UserProfile
import fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar

/**
 * Phase 2 finish (#208) — full profile screen.
 *
 * Displayed when the user taps « Voir le profil complet » from the [ProfilePreviewSheet].
 * Occupies the full screen and shows all available profile fields.
 *
 * [userId], [pseudoHint], [avatarUrlHint] are passed from the [ProfileFullRoute] nav entry.
 * A [ProfileViewModel] is created via Hilt with a custom factory that injects these
 * arguments into the [androidx.lifecycle.SavedStateHandle] at construction time.
 *
 * @param onBack  Navigation callback — pops back to the previous screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileRoute(
    userId: Int,
    pseudoHint: String,
    avatarUrlHint: String?,
    onBack: () -> Unit,
) {
    val viewModel = hiltViewModel<ProfileViewModel, ProfileViewModel.Factory>(
        creationCallback = { factory ->
            factory.create(
                userId = userId,
                pseudoHint = pseudoHint,
                avatarUrlHint = avatarUrlHint,
            )
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProfileScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileScreen(
    state: ProfileUiState,
    onIntent: (ProfileIntent) -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (val mode = state.mode) {
                            is ProfileUiState.Mode.Loaded -> mode.profile.pseudo
                            else -> state.pseudoHint.ifEmpty { "Profil" }
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when (val mode = state.mode) {
                ProfileUiState.Mode.Loading -> {
                    Spacer(Modifier.height(32.dp))
                    ProfileLoadingHero(
                        pseudo = state.pseudoHint,
                        avatarUrl = state.avatarUrlHint,
                    )
                    Spacer(Modifier.height(32.dp))
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                is ProfileUiState.Mode.Error -> {
                    Spacer(Modifier.height(32.dp))
                    ProfileLoadingHero(
                        pseudo = state.pseudoHint,
                        avatarUrl = state.avatarUrlHint,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Impossible de charger le profil.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onIntent(ProfileIntent.Retry) }) {
                        Text("Réessayer")
                    }
                }

                is ProfileUiState.Mode.Loaded -> {
                    Spacer(Modifier.height(16.dp))
                    ProfileFullContent(profile = mode.profile)
                }
            }
        }
    }
}

@Composable
private fun ProfileLoadingHero(pseudo: String, avatarUrl: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RedfaceUserAvatar(
            avatarUrl = avatarUrl,
            author = pseudo,
            size = 72.dp,
        )
        Text(
            text = pseudo,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

/**
 * Full profile content once loaded.
 * Avatars are square/rounded as per brief constraint — [RedfaceUserAvatar] enforces this.
 */
@Composable
private fun ProfileFullContent(profile: UserProfile) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        RedfaceUserAvatar(
            avatarUrl = profile.avatarUrl,
            author = profile.pseudo,
            size = 72.dp,
        )
        Column {
            Text(
                text = profile.pseudo,
                style = MaterialTheme.typography.headlineSmall,
            )
            profile.location?.let { loc ->
                Text(
                    text = loc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    ProfileField(label = "Messages postés", value = profile.postCount?.toString())
    ProfileField(label = "Inscription", value = profile.registeredAt)

    profile.signatureHtml?.takeIf { it.isNotBlank() }?.let { sig ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Signature",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = sig,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Spacer(Modifier.height(24.dp))

    // « Derniers messages » is disabled — no stable route exists yet.
    OutlinedButton(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Derniers messages (à venir)")
    }

    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ProfileField(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
