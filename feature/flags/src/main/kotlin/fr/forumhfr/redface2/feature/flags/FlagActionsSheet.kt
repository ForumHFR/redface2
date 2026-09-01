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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.pageToOpen
import fr.forumhfr.redface2.core.model.pagesToRead
import fr.forumhfr.redface2.core.ui.FlagMarker
import fr.forumhfr.redface2.core.ui.browser.LocalAlwaysAskLinkApp
import fr.forumhfr.redface2.core.ui.browser.openUrlInExternalBrowser
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
    val alwaysAskLinkApp = LocalAlwaysAskLinkApp.current
    val linkCopied = stringResource(R.string.flags_sheet_link_copied)
    val browserFailed = stringResource(R.string.flags_sheet_browser_failed)
    val shareFailed = stringResource(R.string.flags_sheet_share_failed)
    // #15 — the « Aller à une page » dialog state is hoisted here, above the sheet content (Codex), so
    // Back closes the dialog before the sheet and the input survives recompositions.
    var showPageDialog by remember { mutableStateOf(false) }

    if (showPageDialog) {
        PageInputDialog(
            totalPages = flag.totalPages,
            onConfirm = { page ->
                showPageDialog = false
                // onOpen closes the sheet (host clears sheetFlag) then navigates — no double-modal.
                actions.onOpen(page)
            },
            onDismiss = { showPageDialog = false },
        )
    }

    ModalBottomSheet(onDismissRequest = actions.onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            SheetHeader(flag = flag)
            // #676 v2 (mockup F2 v2) — the two surfaces carry DISTINCT, complementary actions (NOT the
            // same set, the previous misunderstanding): the quick icon ROW = the frequent primary
            // actions (open / first-unread / super-favorite / share), the full-label LIST = secondary,
            // navigational and destructive ones. « Lu » and « Marquer la catégorie comme lue » from the
            // mockup are intentionally ABSENT: HFR exposes no endpoint to mark a topic/category read
            // without opening it (le réel prime — pas de faux bouton). « Mettre en sourdine » is a
            // separate feature (own chantier), not bolted on here.
            QuickActionsBar(
                items = quickActions(
                    flag = flag,
                    isSuperFavorite = isSuperFavorite,
                    actions = actions,
                    onShare = { shareTopic(context, flagTopicUrl(flag), flag.title, shareFailed) },
                ),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            FlagMetadataBlock(flag = flag, categoryName = categoryName)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            FlagActionsList(
                items = menuActions(
                    flag = flag,
                    actions = actions,
                    onGoToPage = { showPageDialog = true },
                    onCopyLink = { copyTopicLink(context, flagTopicUrl(flag), linkCopied) },
                    onOpenBrowser = {
                        openTopicInBrowser(context, flagTopicUrl(flag), browserFailed, alwaysAskLinkApp)
                    },
                ),
            )
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
 * #676 v2 — one sheet action (icon + label + callback + tint). Each action now lives in EXACTLY ONE
 * surface (quick row OR menu list), so a single [label] suffices. [enabled] greys-out + disables an
 * action that has nothing to do (« 1er non-lu » when there is no unread); [destructive] = « Retirer »
 * (error tint, still gated by the removal confirmation dialog).
 */
private class SheetActionItem(
    @DrawableRes val iconRes: Int,
    val label: String,
    val onClick: () -> Unit,
    val iconTint: Color,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

/** Resume page: where the user last read (never below 1). Pure → unit-tested. */
internal fun flagLastReadPage(lastReadPage: Int): Int = lastReadPage.coerceAtLeast(1)

/** Last page of the topic (never below 1). Pure → unit-tested. */
internal fun flagLastPage(totalPages: Int): Int = totalPages.coerceAtLeast(1)

/**
 * First-unread page (#676 v2) — now delegating to [pageToOpen] (#638).
 *
 * The previous implementation was `lastReadPage + 1` whenever anything was unread, which SKIPPED
 * posts when the user had stopped mid-page: on the real fixture `rest_cat23_participated.json`
 * (`last_position = 479`, page 12, 541 posts) it answered page 13 while post 480 — the last of
 * page 12 — was still unread. `pageToOpen` only advances when `last_position` proves the last-read
 * post sat at a page boundary, so both this action and the row tap now share ONE rule.
 * Pure → unit-tested.
 */
internal fun flagFirstUnreadPage(flag: Flag): Int = flag.pageToOpen()

/**
 * #676 v2 — the QUICK icon row: the frequent primary actions. Distinct from [menuActions]: open
 * (resume), jump to the first unread, toggle the local super-favorite, share the topic link.
 */
@Composable
private fun quickActions(
    flag: Flag,
    isSuperFavorite: Boolean,
    actions: FlagSheetActions,
    onShare: () -> Unit,
): List<SheetActionItem> {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    return listOf(
        SheetActionItem(
            iconRes = CoreUiR.drawable.ic_ms_forum,
            label = stringResource(R.string.flags_sheet_quick_open),
            onClick = { actions.onOpen(flagLastReadPage(flag.lastReadPage)) },
            iconTint = variant,
        ),
        SheetActionItem(
            iconRes = CoreUiR.drawable.ic_ms_arrow_downward,
            label = stringResource(R.string.flags_sheet_quick_first_unread),
            onClick = { actions.onOpen(flagFirstUnreadPage(flag)) },
            iconTint = variant,
            // Codex (#676) : désactivé quand il n'y a rien de non lu. NB depuis #638 : l'action peut
            // désormais rendre la MÊME page que « Ouvrir » — c'est le cas quand la lecture s'est
            // arrêtée en milieu de page, où le premier non-lu est bien sur la page du repère. La
            // garantie « ne duplique jamais Ouvrir » de #676 ne tient donc plus, et c'est voulu :
            // les deux chemins partagent une règle unique (`pageToOpen`).
            enabled = flag.hasUnread,
        ),
        SheetActionItem(
            iconRes = CoreUiR.drawable.ic_ms_star,
            label = stringResource(R.string.flags_sheet_quick_super_favorite),
            onClick = actions.onToggleSuperFavorite,
            // Amber icon when the local super-favorite is active.
            iconTint = if (isSuperFavorite) FlagPalette.Favorite else variant,
        ),
        SheetActionItem(
            iconRes = CoreUiR.drawable.ic_ms_share,
            label = stringResource(R.string.flags_sheet_quick_share),
            onClick = onShare,
            iconTint = variant,
        ),
    )
}

/**
 * #676 v2 / #15 — the full-label MENU list: secondary, navigational and destructive actions. Distinct
 * from [quickActions]: open the last page, jump to a specific page (dialog), reply, copy the link, open
 * in the browser, remove the flag. Order follows Codex: navigation → reply → utilities → destructive.
 *
 * [onGoToPage] opens the in-sheet page-number dialog ([PageInputDialog]); the « Aller à une page » row
 * is omitted for single-page topics (nothing to choose). [actions.onReply] opens the reply editor.
 */
@Composable
private fun menuActions(
    flag: Flag,
    actions: FlagSheetActions,
    onGoToPage: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenBrowser: () -> Unit,
): List<SheetActionItem> {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    return buildList {
        add(
            SheetActionItem(
                iconRes = CoreUiR.drawable.ic_ms_last_page,
                label = stringResource(R.string.flags_sheet_action_last_page),
                onClick = { actions.onOpen(flagLastPage(flag.totalPages)) },
                iconTint = variant,
            ),
        )
        // « Aller à une page » only makes sense when there is more than one page (Codex).
        if (flag.totalPages > 1) {
            add(
                SheetActionItem(
                    iconRes = CoreUiR.drawable.ic_ms_article,
                    label = stringResource(R.string.flags_sheet_action_goto_page),
                    onClick = onGoToPage,
                    iconTint = variant,
                ),
            )
        }
        add(
            SheetActionItem(
                iconRes = CoreUiR.drawable.ic_ms_edit_square,
                label = stringResource(R.string.flags_sheet_action_reply),
                onClick = actions.onReply,
                iconTint = variant,
            ),
        )
        add(
            SheetActionItem(
                iconRes = CoreUiR.drawable.ic_ms_content_copy,
                label = stringResource(R.string.flags_sheet_action_copy),
                onClick = onCopyLink,
                iconTint = variant,
            ),
        )
        add(
            SheetActionItem(
                iconRes = CoreUiR.drawable.ic_ms_open_in_new,
                label = stringResource(R.string.flags_sheet_action_browser),
                onClick = onOpenBrowser,
                iconTint = variant,
            ),
        )
        add(
            SheetActionItem(
                iconRes = CoreUiR.drawable.ic_ms_delete,
                label = stringResource(R.string.flags_sheet_action_remove),
                onClick = actions.onRemove,
                iconTint = MaterialTheme.colorScheme.error,
                destructive = true,
            ),
        )
    }
}

/**
 * #15 — clamps a raw page-number input to a valid topic page, or `null` when it is not a usable page.
 * Trims, rejects non-digits / empty / out-of-range (`1..totalPages`). Pure → unit-tested.
 */
internal fun parseTopicPageInput(input: String, totalPages: Int): Int? {
    val page = input.trim().toIntOrNull() ?: return null
    return page.takeIf { it in 1..flagLastPage(totalPages) }
}

/**
 * #676 v2 (mockup F2 v2) — the quick-access ROW of icon buttons at the top of the sheet ([quickActions]):
 * the frequent primary actions, DISTINCT from the full-label [FlagActionsList] ([menuActions]) below.
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
        // Each button takes an equal share of the row (weight 1f) so they fit edge-to-edge and never
        // clip on a narrow screen (~320dp), rather than a fixed width that could overflow.
        items.forEach { item ->
            QuickActionButton(
                modifier = Modifier.weight(1f),
                iconRes = item.iconRes,
                label = item.label,
                onClick = item.onClick,
                tint = item.iconTint,
                enabled = item.enabled,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList") // small private button: icon + label + click + modifier + tint + enabled.
private fun QuickActionButton(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
) {
    // Disabled (#676 v2, « 1er non-lu » with nothing unread): dim the content and drop the click so the
    // action reads as unavailable rather than a no-op.
    val effectiveTint = if (enabled) tint else tint.copy(alpha = DISABLED_ALPHA)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The icon carries no contentDescription: the visible [label] below is the button's accessible
        // name (TalkBack reads the whole clickable Column, announced as a button via role), so a separate
        // icon description would double-read.
        RedfaceVectorIcon(resId = iconRes, contentDescription = null, tint = effectiveTint)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = effectiveTint,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * #676 v2 — the full-label vertical action list at the bottom of the sheet ([menuActions]): secondary,
 * navigational and destructive actions, DISTINCT from the quick-access [QuickActionsBar] row above (the
 * row = fast primary icons, the list = explicit secondary labels). « Retirer »
 * ([SheetActionItem.destructive]) is error-tinted and still routed through the removal confirmation dialog.
 */
@Composable
private fun FlagActionsList(items: List<SheetActionItem>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEach { item ->
            FlagActionRow(
                iconRes = item.iconRes,
                label = item.label,
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

/**
 * #15 — « Aller à une page » dialog (Codex reco: AlertDialog + numeric field, no slider). Validates the
 * input against `1..totalPages` ([parseTopicPageInput]); « Aller » is disabled while invalid and the
 * IME action fires only on a valid value — the dialog never silently corrects an out-of-range entry.
 */
@Composable
private fun PageInputDialog(
    totalPages: Int,
    onConfirm: (page: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val page = parseTopicPageInput(input, totalPages)
    val confirm = { page?.let(onConfirm) ?: Unit }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flags_sheet_goto_page_title)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { new -> input = new.filter(Char::isDigit).take(MAX_PAGE_DIGITS) },
                singleLine = true,
                // Codex polish: flag a non-empty out-of-range entry as an error (TalkBack + visual) rather
                // than only greying « Aller ». Empty stays neutral (nothing typed yet).
                isError = input.isNotBlank() && page == null,
                label = { Text(stringResource(R.string.flags_sheet_goto_page_label)) },
                supportingText = { Text(stringResource(R.string.flags_sheet_goto_page_supporting, totalPages)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { confirm() }),
            )
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = page != null) {
                Text(stringResource(R.string.flags_sheet_goto_page_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.flags_sheet_goto_page_cancel))
            }
        },
    )
}

/** Copies [url] to the clipboard; on pre-Android-13 (no system confirmation) shows a [feedback] toast. */
private fun copyTopicLink(context: Context, url: String, feedback: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("HFR", url))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
    }
}

/** Opens [url] outside Redface 2; toasts [failureFeedback] when no browser can handle it. */
private fun openTopicInBrowser(
    context: Context,
    url: String,
    failureFeedback: String,
    alwaysAsk: Boolean,
) {
    if (!openUrlInExternalBrowser(context, url.toUri(), alwaysAsk)) {
        Toast.makeText(context, failureFeedback, Toast.LENGTH_SHORT).show()
    }
}

/** #676 v2 — shares the topic [url] via the system share sheet; toasts [failureFeedback] if none. */
private fun shareTopic(context: Context, url: String, title: String, failureFeedback: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, url)
    }
    runCatching { context.startActivity(Intent.createChooser(send, title)) }
        .onFailure { Toast.makeText(context, failureFeedback, Toast.LENGTH_SHORT).show() }
}

/** Dimming applied to a disabled quick action (#676 v2). */
private const val DISABLED_ALPHA = 0.38f

/** #15 — cap on the page-number field length (HFR topics stay well under 100k pages). */
private const val MAX_PAGE_DIGITS = 6
