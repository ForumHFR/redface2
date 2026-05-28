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
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.UserProfile
import fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar
import kotlinx.coroutines.launch

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
 * Review feedback I4: tapping « Voir le profil complet » plays the sheet's hide
 * animation (`sheetState.hide()` then `onOpenFullProfile(...)` on completion) instead
 * of snapping the sheet away abruptly. Same pattern as Material 3's reference Sheet
 * sample.
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
    // `key = "profile-$userId"` is critical: this composable is hosted inside `RedfaceApp`,
    // whose `ViewModelStoreOwner` is the Activity (shared across every profile sheet open).
    // Without an explicit key, Hilt caches the first instance and skips `creationCallback`,
    // so tapping a different profile would show the first profile. With the key, each userId
    // gets a distinct Activity-scoped VM; reopening the same user reuses its last loaded state
    // until Activity destruction. That is an accepted MVP trade-off until a profile cache /
    // shared sheet-page owner is introduced.
    viewModel: ProfileViewModel = hiltViewModel<ProfileViewModel, ProfileViewModel.Factory>(
        key = "profile-$userId",
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
    // Review feedback M5: `skipPartiallyExpanded = false` was redundant — that's the
    // default for `rememberModalBottomSheetState`. Removed the explicit argument.
    val sheetState = rememberModalBottomSheetState()
    val coroutineScope = rememberCoroutineScope()

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
                // Review feedback I4: animate `hide()` first, then fire the navigation
                // callback once the sheet is actually off-screen. The previous code
                // nulled `profileSheetRequest` synchronously inside the host, snapping
                // the sheet away without the slide-down animation Material 3 ships.
                hideThenNavigate(coroutineScope, sheetState) {
                    onOpenFullProfile(userId, pseudo, avatarUrl)
                }
            },
        )
    }
}

/**
 * Plays the sheet's hide animation, then invokes [action] once the sheet is no longer
 * visible. Encapsulates the standard Material 3 « animated dismiss before navigation »
 * idiom so the caller does not need to thread the coroutine scope / sheetState manually.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun hideThenNavigate(
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    sheetState: SheetState,
    action: () -> Unit,
) {
    coroutineScope.launch { sheetState.hide() }
        .invokeOnCompletion {
            if (!sheetState.isVisible) {
                action()
            }
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
                    // Review feedback I7: localised via stringResource ; the ViewModel
                    // surfaces an ErrorKind enum, not a String.
                    text = stringResource(R.string.profile_error_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { onIntent(ProfileIntent.Retry) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(R.string.profile_action_retry))
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
            Text(stringResource(R.string.profile_action_open_full))
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
        ProfilePreviewRow(
            label = stringResource(R.string.profile_field_membership),
            value = regAt,
        )
    }
    profile.postCount?.let { count ->
        ProfilePreviewRow(
            label = stringResource(R.string.profile_field_messages_short),
            value = count.toString(),
        )
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
