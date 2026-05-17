package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import fr.forumhfr.redface2.core.ui.editor.BBCodePreview
import fr.forumhfr.redface2.core.ui.editor.BBCodeTextField
import fr.forumhfr.redface2.core.ui.editor.BBCodeToolbar

/**
 * Phase 2B-A post-level editor screen. Local-only: text field + toolbar + preview.
 * No HFR POST, no `apercu.php` round-trip — those land in #145 / #146 / #147.
 */
@Composable
fun PostEditorScreen(
    request: PostEditorRequest,
    modifier: Modifier = Modifier,
    viewModel: PostEditorViewModel = hiltViewModel<PostEditorViewModel, PostEditorViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

            BBCodeToolbar(
                onAction = { action -> onIntent(PostEditorIntent.ToolbarActionClicked(action)) },
            )

            BBCodeTextField(
                value = state.draft,
                onValueChange = { value -> onIntent(PostEditorIntent.ContentChanged(value)) },
                label = stringResource(R.string.editor_field_label),
                placeholder = stringResource(R.string.editor_field_placeholder),
            )

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
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
                BBCodePreview(content = state.preview)
            }

            Text(
                text = stringResource(R.string.editor_submit_disabled),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val PostEditorMode.titleResId: Int
    get() = when (this) {
        PostEditorMode.Reply -> R.string.editor_post_reply_title
        PostEditorMode.Edit -> R.string.editor_post_edit_title
    }
