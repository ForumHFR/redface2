package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.ui.editor.BbcodePreview
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.core.ui.editor.BbcodeToolbar

/**
 * Post-level editor screen. Phase 2C (#145) adds a Submit button that posts the
 * reply via [PostEditorViewModel.submit]. Successful submissions raise a one-shot
 * [PostEditorEffect.SubmitSucceeded] which the navigation host translates into a
 * back navigation + topic refresh.
 */
@Composable
fun PostEditorScreen(
    request: PostEditorRequest,
    onSubmitSucceeded: (targetPage: Int?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PostEditorViewModel = hiltViewModel<PostEditorViewModel, PostEditorViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PostEditorEffect.SubmitSucceeded -> onSubmitSucceeded(effect.targetPage)
            }
        }
    }
    PostEditorContent(
        state = state,
        onIntent = remember(viewModel) { { intent: PostEditorIntent -> viewModel.submit(intent) } },
        modifier = modifier,
    )
}

@Composable
private fun PostEditorContent(
    state: PostEditorState,
    onIntent: (PostEditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(state.mode.titleResId),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            BbcodeToolbar(
                onAction = { action -> onIntent(PostEditorIntent.ToolbarActionClicked(action)) },
            )

            BbcodeTextField(
                value = state.draft,
                onValueChange = { value -> onIntent(PostEditorIntent.ContentChanged(value)) },
                label = stringResource(R.string.editor_field_label),
                placeholder = stringResource(R.string.editor_field_placeholder),
            )

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                TextButton(onClick = { onIntent(PostEditorIntent.TogglePreview) }) {
                    Text(
                        text = stringResource(
                            if (state.isPreviewVisible) R.string.editor_preview_hide else R.string.editor_preview_show,
                        ),
                    )
                }
            }

            if (state.isPreviewVisible) {
                HorizontalDivider()
                BbcodePreview(content = state.preview)
            }

            state.submitError?.let { error ->
                Text(
                    text = stringResource(error.bannerResId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { onIntent(PostEditorIntent.ErrorDismissed) }) {
                    Text(text = stringResource(R.string.editor_error_dismiss))
                }
            }

            if (state.mode == PostEditorMode.Reply) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.isLoadingForm) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                    Button(
                        enabled = state.canSubmit,
                        onClick = { onIntent(PostEditorIntent.SubmitClicked) },
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(text = stringResource(R.string.editor_submit))
                        }
                    }
                }
            } else {
                // Edit / Create flows arrive later (#147 / #149). Quote is already
                // wired through `PostEditorMode.Reply` (it's the same submit path
                // with a non-null `quotedNumreponse`), so the `else` branch only
                // covers Edit today — keep the placeholder until #147 lands.
                Text(
                    text = stringResource(R.string.editor_submit_disabled),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val PostEditorMode.titleResId: Int
    get() = when (this) {
        PostEditorMode.Reply -> R.string.editor_post_reply_title
        PostEditorMode.Edit -> R.string.editor_post_edit_title
    }

private val SubmitError.bannerResId: Int
    get() = when (this) {
        is SubmitError.Hfr -> when (reason) {
            ReplyFailureReason.EmptyMessage -> R.string.editor_error_empty
            ReplyFailureReason.InvalidHashCheck -> R.string.editor_error_invalid_hash
            ReplyFailureReason.AntiFlood -> R.string.editor_error_anti_flood
            ReplyFailureReason.TopicLocked -> R.string.editor_error_topic_locked
            ReplyFailureReason.LoginRequired -> R.string.editor_error_login_required
            ReplyFailureReason.Unknown -> R.string.editor_error_unknown
        }
        SubmitError.Network -> R.string.editor_error_network
        SubmitError.SessionExpired -> R.string.editor_error_session_expired
        SubmitError.MissingSubcat -> R.string.editor_error_missing_subcat
    }
