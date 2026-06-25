package fr.forumhfr.redface2.feature.flags

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.pagesToRead
import fr.forumhfr.redface2.core.ui.FlagMarker
import fr.forumhfr.redface2.core.ui.formatLastReplyTimestamp
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.icon.categoryIcon
import fr.forumhfr.redface2.core.ui.theme.FlagPalette
import fr.forumhfr.redface2.core.ui.R as CoreUiR

/**
 * Long-press action sheet of a Drapeaux row (#603 PR5, ADR-017 decision 6) — replaces the direct
 * removal dialog as the long-press entry point. Shows the topic's API metadata (already in [Flag]),
 * quick actions, the local « super favori » toggle, and the (destructive) flag removal. **No color
 * picker** — the flag color reflects the server bucket and is not editable.
 *
 * « Copier le lien » / « Ouvrir dans le navigateur » are handled in-sheet via [LocalContext]
 * ([flagTopicUrl]); the rest routes through [actions] (removal still goes through the existing
 * confirmation dialog).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlagActionsSheet(
    flag: Flag,
    categoryName: String,
    isSuperFavorite: Boolean,
    actions: FlagSheetActions,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val linkCopied = stringResource(R.string.flags_sheet_link_copied)
    val browserFailed = stringResource(R.string.flags_sheet_browser_failed)

    ModalBottomSheet(onDismissRequest = actions.onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            SheetHeader(flag = flag, categoryName = categoryName)
            FlagMetadataBlock(flag = flag, categoryName = categoryName)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SheetActionRow(
                iconRes = CoreUiR.drawable.ic_ms_forum,
                label = stringResource(R.string.flags_sheet_open),
                onClick = actions.onOpen,
            )
            SheetActionRow(
                iconRes = CoreUiR.drawable.ic_ms_star,
                label = stringResource(
                    if (isSuperFavorite) {
                        R.string.flags_sheet_super_favorite_remove
                    } else {
                        R.string.flags_sheet_super_favorite_add
                    },
                ),
                onClick = actions.onToggleSuperFavorite,
                contentColor = if (isSuperFavorite) FlagPalette.Favorite else MaterialTheme.colorScheme.onSurface,
            )
            SheetActionRow(
                iconRes = CoreUiR.drawable.ic_ms_content_copy,
                label = stringResource(R.string.flags_sheet_copy_link),
                onClick = { copyTopicLink(context, flagTopicUrl(flag), linkCopied) },
            )
            SheetActionRow(
                iconRes = CoreUiR.drawable.ic_ms_open_in_new,
                label = stringResource(R.string.flags_sheet_open_browser),
                onClick = { openTopicInBrowser(context, flagTopicUrl(flag), browserFailed) },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SheetActionRow(
                iconRes = CoreUiR.drawable.ic_ms_delete,
                label = stringResource(R.string.flags_sheet_remove),
                onClick = actions.onRemove,
                contentColor = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SheetHeader(flag: Flag, categoryName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlagMarker(
            style = MarkerStyle.DOT,
            type = flag.type,
            isFavorite = flag.isFavorite,
            hasUnread = flag.hasUnread,
            categoryIconRes = categoryIcon(flag.cat),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = flag.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val position = stringResource(
                R.string.flags_sheet_meta_position_value,
                flag.lastReadPage,
                flag.totalPages,
            )
            Text(
                text = "$categoryName · $position",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FlagMetadataBlock(flag: Flag, categoryName: String) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MetaRow(stringResource(R.string.flags_sheet_meta_category), categoryName)
        MetaRow(stringResource(R.string.flags_sheet_meta_author), flag.firstPostAuthor)
        if (flag.lastReplyAuthor.isNotBlank()) {
            MetaRow(
                stringResource(R.string.flags_sheet_meta_last_reply),
                stringResource(
                    R.string.flags_sheet_meta_last_reply_value,
                    flag.lastReplyAuthor,
                    formatLastReplyTimestamp(flag.lastReplyAt),
                ),
            )
        }
        val pages = flag.pagesToRead()
        val position = stringResource(R.string.flags_sheet_meta_position_value, flag.lastReadPage, flag.totalPages)
        MetaRow(
            stringResource(R.string.flags_sheet_meta_position),
            if (flag.hasUnread && pages > 0) {
                "$position · " + stringResource(R.string.flags_sheet_meta_pages_to_read, pages)
            } else {
                position
            },
        )
        MetaRow(stringResource(R.string.flags_sheet_meta_replies), flag.replyCount.toString())
    }
}

@Composable
private fun MetaRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(116.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SheetActionRow(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        RedfaceVectorIcon(resId = iconRes, tint = contentColor)
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = contentColor)
    }
}

/** Copies [url] to the clipboard; on pre-Android-13 (no system confirmation) shows a [feedback] toast. */
private fun copyTopicLink(context: Context, url: String, feedback: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("HFR", url))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
    }
}

/** Opens [url] in the browser; toasts [failureFeedback] when no app can handle the intent. */
private fun openTopicInBrowser(context: Context, url: String, failureFeedback: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { Toast.makeText(context, failureFeedback, Toast.LENGTH_SHORT).show() }
}
