package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.post.PostRenderer

/**
 * Renders an editor preview from an already-parsed [PostContent].
 *
 * This composable intentionally does **not** parse BBCode itself — keeping the
 * `:core:ui` module free of parsing logic (and of any `:core:parser` dependency)
 * is the boundary that Konsist enforces and that ADR-011 documents. Callers feed
 * the already-parsed AST in; the actual BBCode parsing lives in `:core:parser`,
 * exposed to features via `BbcodePreviewParser` in `:core:domain`.
 */
@Composable
fun BbcodePreview(
    content: PostContent,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (content.blocks.isEmpty()) {
                Text(
                    text = stringResource(R.string.bbcode_preview_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                PostRenderer(content = content)
            }
        }
    }
}
