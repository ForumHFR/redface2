package fr.forumhfr.redface2.core.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import kotlin.math.roundToInt

/** One page in the existing sheet; insertion uses the very same host callback as a grid tap. */
@Composable
internal fun SmileyDetailContent(
    smiley: EditorSmiley,
    state: SmileyPickerState.Open,
    onSmileyClicked: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().heightIn(max = smileyPickerHeightCap())) {
        SmileyDetailTopBar { state.onDetailChange?.invoke(null) }
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmileyDetailHeader(smiley, state)
            // Future Wiki metadata belongs between the header and the insertion action (#1154 lot 2).
            Button(onClick = { onSmileyClicked(smiley.token) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.editor_smiley_detail_insert))
            }
            HorizontalDivider()
            SmileyDetailActions(smiley, state)
        }
        state.favoriteFeedback?.let { feedback ->
            // Fixed below the scrolling actions: mutation results stay visible on small screens.
            SmileyFavoriteStatus(feedback, Modifier.fillMaxWidth().padding(8.dp))
        }
    }
}

@Composable
private fun SmileyDetailTopBar(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            RedfaceVectorIcon(
                R.drawable.ic_arrow_back,
                contentDescription = stringResource(R.string.editor_smiley_detail_back),
            )
        }
        Text(
            text = stringResource(R.string.editor_smiley_detail_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun SmileyDetailHeader(smiley: EditorSmiley, state: SmileyPickerState.Open) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val measured = rememberSmileyIntrinsicSize(smiley)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(Modifier.size(112.dp, 88.dp), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = smiley.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.size(smileyDetailImageSize(smiley.source, measured)),
                    contentScale = ContentScale.Fit,
                    filterQuality = if (smiley.source == EditorSmileySource.BUILTIN) {
                        FilterQuality.None
                    } else {
                        FilterQuality.Low
                    },
                )
            }
        }
        Text(smiley.token, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        val count = state.usageCounts[smiley.token]
        Text(
            text = if (count == null) {
                stringResource(R.string.editor_smiley_detail_unused)
            } else {
                // The stored counter is Long; only the plural selector is bounded to Int.
                pluralStringResource(
                    R.plurals.editor_smiley_detail_usage,
                    count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    count,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SmileyDetailMembership(smiley, state)
    }
}

@Composable
private fun SmileyDetailMembership(smiley: EditorSmiley, state: SmileyPickerState.Open) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (smiley.token in state.localFavoriteTokens) {
            SmileyMembershipChip(stringResource(R.string.editor_smiley_detail_local_favorite))
        }
        if (state.favoriteSmileys.any { it.token == smiley.token } &&
            smiley.token !in state.accountSmileyTokens && smiley.source != EditorSmileySource.BUILTIN
        ) {
            SmileyMembershipChip(stringResource(R.string.editor_smiley_detail_hfr_favorite))
        }
    }
}

/** Informational membership, without a no-op click action in the accessibility tree. */
@Composable
private fun SmileyMembershipChip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SmileyDetailActions(smiley: EditorSmiley, state: SmileyPickerState.Open) {
    val context = LocalContext.current
    val copied = stringResource(R.string.editor_smiley_detail_copied)
    Column {
        SmileyDetailAction(stringResource(R.string.editor_smiley_detail_copy), R.drawable.ic_ms_content_copy) {
            copySmileyCode(context, smiley.token, copied)
        }
        state.localFavoriteAction(smiley)?.let { action ->
            val label = if (action == SmileyFavoriteAction.ADD) {
                R.string.editor_smiley_local_favorite_add
            } else {
                R.string.editor_smiley_local_favorite_remove
            }
            SmileyDetailAction(stringResource(label), R.drawable.ic_ms_smartphone) {
                state.onLocalFavoriteChange?.invoke(smiley)
            }
        }
        SmileyDetailHfrAction(smiley, state)
    }
}

@Composable
private fun SmileyDetailHfrAction(smiley: EditorSmiley, state: SmileyPickerState.Open) {
    val action = state.favoriteAction(smiley, includePending = true) ?: return
    val pending = state.pendingFavoriteToken != null
    val label = when {
        pending -> R.string.editor_smiley_favorite_pending
        action == SmileyFavoriteAction.ADD -> R.string.editor_smiley_favorite_add
        else -> R.string.editor_smiley_favorite_remove
    }
    SmileyDetailAction(stringResource(label), R.drawable.ic_ms_star, pending = pending) {
        state.onFavoriteChange?.invoke(smiley)
    }
}

@Composable
private fun SmileyDetailAction(
    label: String,
    @DrawableRes icon: Int,
    pending: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RedfaceVectorIcon(icon) },
        trailingContent = if (pending) {
            { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        } else {
            null
        },
        modifier = Modifier.clickable(enabled = !pending, role = Role.Button, onClick = onClick),
    )
}

/** Android 13+ supplies its own clipboard overlay, matching PostMenuSheet's feedback policy. */
private fun copySmileyCode(context: Context, token: String, feedback: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("redface2 smiley code", token))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
    }
}

/** Modest enlargement of the original image: 96×72 dp cap, at most 1.5× measured perso size. */
internal fun smileyDetailImageSize(source: EditorSmileySource, measured: IntSize?): DpSize {
    val native = measured?.takeIf { it.width > 0 && it.height > 0 }
    return when {
        source == EditorSmileySource.BUILTIN -> DpSize(32.dp, 32.dp)
        native == null -> DpSize(96.dp, 72.dp)
        else -> {
            val scale = minOf(96f / native.width, 72f / native.height, 1.5f)
            DpSize(
                (native.width * scale).roundToInt().coerceAtLeast(1).dp,
                (native.height * scale).roundToInt().coerceAtLeast(1).dp,
            )
        }
    }
}
