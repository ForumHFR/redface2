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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.UserProfile
import fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull

/**
 * Phase 2 finish (#208) — full profile screen.
 *
 * Displayed when the user taps « Voir le profil complet » from the [ProfilePreviewSheet].
 * Occupies the full screen and shows all available profile fields.
 *
 * [userId], [pseudoHint], [avatarUrlHint] are passed from the [ProfileFullRoute] nav entry.
 * A [ProfileViewModel] is created via Hilt AssistedInject with a custom factory that
 * receives these arguments at construction time.
 *
 * TODO(profile): the sheet ↔ full-page transition currently builds two ProfileViewModel
 *  instances and fires two network calls — see KDoc on [fr.forumhfr.redface2.navigation
 *  .ProfileFullRoute]. A caching follow-up will land after this work (no issue opened yet
 *  to keep this PR focused).
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
    // `key = "profile-$userId"` derives a distinct VM per profile in the nav entry's
    // ViewModelStore. Even though each `ProfileFullRoute` navigation creates its own back
    // stack entry, navigating Profile(A) → back → Profile(B) within the same composable
    // host could otherwise return the cached A-instance because `hiltViewModel` keys by
    // class, not by `creationCallback` arguments.
    val viewModel = hiltViewModel<ProfileViewModel, ProfileViewModel.Factory>(
        key = "profile-$userId",
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (val mode = state.mode) {
                            is ProfileUiState.Mode.Loaded -> mode.profile.pseudo
                            else -> state.pseudoHint.ifEmpty {
                                stringResource(R.string.profile_title_fallback)
                            }
                        },
                    )
                },
                navigationIcon = {
                    // detekt ForbiddenImport blocks `androidx.compose.material.*` (incl.
                    // `material-icons-core`), so `Icons.AutoMirrored.Filled.ArrowBack` is off-limits.
                    // A text « ← » glyph proved unstable as an icon (size depends on the system font,
                    // baseline and font-scale — cf. Codex review), so use a local dp-sized vector
                    // drawable rendered with material3 `Icon` (allowed: material3, not material). The
                    // a11y label stays on the IconButton; the icon is decorative (contentDescription
                    // = null) to avoid duplicate semantics. TalkBack still announces « Retour, bouton ».
                    val backLabel = stringResource(R.string.profile_back)
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backLabel },
                    ) {
                        Icon(
                            painter = painterResource(fr.forumhfr.redface2.core.ui.R.drawable.ic_arrow_back),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
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
                    // Review feedback I7: the ViewModel surfaces an error kind,
                    // not a String — the UI maps it to the localised resource. #324 —
                    // ServerDown / Network resolve to the shared :core:ui labels,
                    // Other keeps the feature's generic message.
                    Text(
                        text = stringResource(
                            mode.kind.sharedLabelResOrNull() ?: R.string.profile_error_load_failed,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onIntent(ProfileIntent.Retry) }) {
                        Text(stringResource(R.string.profile_action_retry))
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

    ProfileField(
        label = stringResource(R.string.profile_field_post_count),
        value = profile.postCount?.toString(),
    )
    ProfileField(
        label = stringResource(R.string.profile_field_registered_at),
        value = profile.registeredAt,
    )

    // Review feedback C1: the signature is plain text (Jsoup.text() at parse time),
    // not HTML — rendering it directly through Text(...) is correct and no longer
    // shows literal `<br>` / `<div>` tags to the user. See ProfileParser
    // .parseSignatureText() and UserProfile.signatureText KDoc.
    profile.signatureText?.takeIf { it.isNotBlank() }?.let { sig ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.profile_field_signature),
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
        Text(stringResource(R.string.profile_action_recent_posts_coming_soon))
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
