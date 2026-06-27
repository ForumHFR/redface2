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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
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
            SheetHeader(flag = flag)
            // #676 (retour XaTriX) — TWO surfaces over the SAME action model ([sheetActionItems]) so the
            // row and the list can never drift: the quick-access icon ROW at the top (compact, fast) and
            // the full-label vertical LIST at the bottom ([FlagActionsList]). The v194 version had dropped
            // the list (the misunderstanding); both are restored. « Retirer » is last in each and still
            // routes through the removal confirmation dialog, never a one-tap destruction.
            val items = sheetActionItems(
                isSuperFavorite = isSuperFavorite,
                actions = actions,
                onCopyLink = { copyTopicLink(context, flagTopicUrl(flag), linkCopied) },
                onOpenBrowser = { openTopicInBrowser(context, flagTopicUrl(flag), browserFailed) },
            )
            QuickActionsBar(items = items)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            FlagMetadataBlock(flag = flag, categoryName = categoryName)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            FlagActionsList(items = items)
        }
    }
}

@Composable
private fun SheetHeader(flag: Flag) {
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
        // #676 (F2) — title only. The « catégorie · p.X/Y » subtitle was dropped: it duplicated the
        // metadata block right below, which already lists the category and the read position.
        Text(
            text = flag.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * #676 — single source of the five sheet actions (order + icon + callbacks + tint), consumed by BOTH
 * the quick-access [QuickActionsBar] (short [quickLabel]) and the full-label [FlagActionsList]
 * ([fullLabel]) so the two surfaces can never drift apart. [destructive] = « Retirer » (error tint, still
 * gated by the removal confirmation dialog).
 */
private class SheetActionItem(
    @DrawableRes val iconRes: Int,
    val quickLabel: String,
    val fullLabel: String,
    val onClick: () -> Unit,
    val iconTint: Color,
    val destructive: Boolean = false,
)

@Composable
private fun sheetActionItems(
    isSuperFavorite: Boolean,
    actions: FlagSheetActions,
    onCopyLink: () -> Unit,
    onOpenBrowser: () -> Unit,
): List<SheetActionItem> {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    return listOf(
        SheetActionItem(
            iconRes = CoreUiR.drawable.ic_ms_forum,
            quickLabel = stringResource(R.string.flags_sheet_quick_open),
            fullLabel = stringResource(R.string.flags_sheet_action_open),
            onClick = actions.onOpen,
            iconTint = variant,
        ),
        SheetActionItem(
            iconRes = CoreUiR.drawable.ic_ms_star,
            quickLabel = stringResource(R.string.flags_sheet_quick_super_favorite),
            fullLabel = stringResource(
                if (isSuperFavorite) {
                    R.string.flags_sheet_action_super_favorite_remove
                } else {
                    R.string.flags_sheet_action_super_favorite_add
                },
            ),
            onClick = actions.onToggleSuperFavorite,
            // Amber icon mirrors the « active » cue in both surfaces.
            iconTint = if (isSuperFavorite) FlagPalette.Favorite else variant,
        ),
        SheetActionItem(
            iconRes = CoreUiR.drawable.ic_ms_content_copy,
            quickLabel = stringResource(R.string.flags_sheet_quick_copy),
            fullLabel = stringResource(R.string.flags_sheet_action_copy),
            onClick = onCopyLink,
            iconTint = variant,
        ),
        SheetActionItem(
            iconRes = CoreUiR.drawable.ic_ms_open_in_new,
            quickLabel = stringResource(R.string.flags_sheet_quick_browser),
            fullLabel = stringResource(R.string.flags_sheet_action_browser),
            onClick = onOpenBrowser,
            iconTint = variant,
        ),
        SheetActionItem(
            iconRes = CoreUiR.drawable.ic_ms_delete,
            quickLabel = stringResource(R.string.flags_sheet_quick_remove),
            fullLabel = stringResource(R.string.flags_sheet_action_remove),
            onClick = actions.onRemove,
            iconTint = MaterialTheme.colorScheme.error,
            destructive = true,
        ),
    )
}

/**
 * #676 (mockup F2) — the quick-access ROW of icon buttons at the top of the sheet (short labels). The
 * full-label [FlagActionsList] coexists at the bottom (retour XaTriX), both driven by [sheetActionItems].
 */
@Composable
private fun QuickActionsBar(items: List<SheetActionItem>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Each button takes an equal share of the row (weight 1f) so the five fit edge-to-edge and never
        // clip on a narrow screen (~320dp), rather than a fixed width that could overflow.
        items.forEach { item ->
            QuickActionButton(
                modifier = Modifier.weight(1f),
                iconRes = item.iconRes,
                label = item.quickLabel,
                onClick = item.onClick,
                tint = item.iconTint,
            )
        }
    }
}

@Composable
private fun QuickActionButton(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The icon carries no contentDescription: the visible [label] below is the button's accessible
        // name (TalkBack reads the whole clickable Column, announced as a button via role), so a separate
        // icon description would double-read.
        RedfaceVectorIcon(resId = iconRes, contentDescription = null, tint = tint)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * #676 (retour XaTriX) — the full-label vertical action list at the bottom of the sheet, coexisting with
 * the quick-access [QuickActionsBar] row above (the row = fast icons, the list = explicit labels). Driven
 * by the shared [sheetActionItems]; « Retirer » ([SheetActionItem.destructive]) is error-tinted and still
 * routed through the removal confirmation dialog.
 */
@Composable
private fun FlagActionsList(items: List<SheetActionItem>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEach { item ->
            FlagActionRow(
                iconRes = item.iconRes,
                label = item.fullLabel,
                onClick = item.onClick,
                iconTint = item.iconTint,
                labelColor = if (item.destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun FlagActionRow(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // The icon carries no contentDescription: the visible [label] is the row's accessible name
        // (TalkBack reads the whole clickable Row, announced as a button via role).
        RedfaceVectorIcon(resId = iconRes, contentDescription = null, tint = iconTint)
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
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
