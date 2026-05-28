package fr.forumhfr.redface2.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.UserProfile
import fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar

/**
 * Phase 2 finish (#208) — ModalBottomSheet summary of a user's profile.
 *
 * Opened from `TopicScreen` via `onOpenProfile(userId, pseudo, avatarUrl)`, hoisted in
 * `:app`'s `RedfaceNavigation.kt`. The sheet shows a quick summary while the full profile
 * is loading, and a « Voir le profil complet » button that navigates to [ProfileRoute].
 *
 * Architecture constraint (brief § Section 3.4):
 * - `:feature:topic` does **not** depend on `:feature:profile`.
 * - `:app` owns the ModalBottomSheet state and calls this composable.
 * - The [ProfileViewModel] is created here with Hilt navigation compose.
 *
 * Avatar shape: square/rectangle with rounded corners — constraint from the brief.
 *
 * @param userId          The user id to load the profile for (canonical key).
 * @param pseudoHint      Display hint shown immediately before the profile is loaded.
 * @param avatarUrlHint   Avatar URL hint shown immediately as a placeholder.
 * @param onDismiss       Called when the sheet is dismissed by the user.
 * @param onOpenFullProfile  Called when the user taps « Voir le profil complet ».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // state-hoisted Composable: each parameter has a distinct call-site
// (userId = canonical key, hints = display placeholders, onDismiss, onOpenFullProfile, viewModel).
// Bundling onDismiss+onOpenFullProfile into a callbacks holder would obscure the navigation surface.
@Composable
fun ProfilePreviewSheet(
    userId: Int,
    pseudoHint: String,
    avatarUrlHint: String?,
    onDismiss: () -> Unit,
    onOpenFullProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit,
    viewModel: ProfileViewModel = hiltViewModel<ProfileViewModel, ProfileViewModel.Factory>(
        creationCallback = { factory ->
            factory.create(
                userId = userId,
                pseudoHint = pseudoHint,
                avatarUrlHint = avatarUrlHint,
            )
        },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        ProfilePreviewContent(
            state = state,
            pseudoHint = pseudoHint,
            avatarUrlHint = avatarUrlHint,
            onIntent = viewModel::onIntent,
            onOpenFullProfile = {
                val pseudo = (state.mode as? ProfileUiState.Mode.Loaded)
                    ?.profile
                    ?.pseudo
                    ?: pseudoHint
                val avatarUrl = (state.mode as? ProfileUiState.Mode.Loaded)
                    ?.profile
                    ?.avatarUrl
                    ?: avatarUrlHint
                onOpenFullProfile(userId, pseudo, avatarUrl)
            },
        )
    }
}

@Composable
private fun ProfilePreviewContent(
    state: ProfileUiState,
    pseudoHint: String,
    avatarUrlHint: String?,
    onIntent: (ProfileIntent) -> Unit,
    onOpenFullProfile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .fillMaxWidth(),
    ) {
        when (val mode = state.mode) {
            ProfileUiState.Mode.Loading -> {
                ProfilePreviewHero(pseudo = pseudoHint, avatarUrl = avatarUrlHint)
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            is ProfileUiState.Mode.Error -> {
                ProfilePreviewHero(pseudo = pseudoHint, avatarUrl = avatarUrlHint)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Impossible de charger le profil.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { onIntent(ProfileIntent.Retry) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("Réessayer")
                }
            }

            is ProfileUiState.Mode.Loaded -> {
                ProfilePreviewLoaded(profile = mode.profile)
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onOpenFullProfile,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Voir le profil complet")
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Hero row shown while loading or on error — displays the hint values from the topic page.
 * Avatar is square/rectangle with rounded corners (via [RedfaceUserAvatar]).
 */
@Composable
private fun ProfilePreviewHero(pseudo: String, avatarUrl: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RedfaceUserAvatar(
            avatarUrl = avatarUrl,
            author = pseudo,
            size = 56.dp,
        )
        Text(
            text = pseudo,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * Loaded content: avatar + pseudo + key stats.
 */
@Composable
private fun ProfilePreviewLoaded(profile: UserProfile) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        RedfaceUserAvatar(
            avatarUrl = profile.avatarUrl,
            author = profile.pseudo,
            size = 56.dp,
        )
        Column {
            Text(
                text = profile.pseudo,
                style = MaterialTheme.typography.titleMedium,
            )
            profile.location?.let { loc ->
                Text(
                    text = loc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    profile.registeredAt?.let { regAt ->
        ProfilePreviewRow(label = "Membre depuis", value = regAt)
    }
    profile.postCount?.let { count ->
        ProfilePreviewRow(label = "Messages", value = count.toString())
    }
}

@Composable
private fun ProfilePreviewRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
