@file:Suppress("MatchingDeclarationName") // The shared catalogue includes its presentation descriptor.

package fr.forumhfr.redface2.core.ui.editor

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.ui.R

/** One presentation catalogue for the toolbar and the settings picker (ED-36). */
data class EditorShortcutEntry(
    val choice: EditorShortcut,
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val iconRes: Int? = null,
    val bbcode: BbcodeAction? = null,
)

internal val editorShortcutFormats = listOf(
    EditorShortcutEntry(EditorShortcut.BOLD, R.string.bbcode_action_bold, R.drawable.ic_editor_bold, BbcodeAction.Bold),
    EditorShortcutEntry(EditorShortcut.ITALIC, R.string.bbcode_action_italic,
        R.drawable.ic_editor_italic, BbcodeAction.Italic),
    EditorShortcutEntry(EditorShortcut.UNDERLINE, R.string.bbcode_action_underline,
        R.drawable.ic_editor_underline, BbcodeAction.Underline),
    EditorShortcutEntry(EditorShortcut.STRIKE, R.string.bbcode_action_strike,
        R.drawable.ic_editor_strike, BbcodeAction.Strike),
    EditorShortcutEntry(EditorShortcut.QUOTE, R.string.bbcode_action_quote,
        R.drawable.ic_editor_quote_text, BbcodeAction.Quote),
    EditorShortcutEntry(EditorShortcut.CODE, R.string.bbcode_action_cpp, R.drawable.ic_editor_code, BbcodeAction.Cpp),
    EditorShortcutEntry(EditorShortcut.FIXED, R.string.bbcode_action_fixed,
        R.drawable.ic_editor_fixed, BbcodeAction.Fixed),
    EditorShortcutEntry(EditorShortcut.SPOILER, R.string.bbcode_action_spoiler,
        R.drawable.ic_editor_spoiler, BbcodeAction.Spoiler),
    EditorShortcutEntry(EditorShortcut.LINK, R.string.bbcode_action_url, R.drawable.ic_editor_link, BbcodeAction.Url),
)

internal val editorShortcutColors = listOf(
    EditorShortcutEntry(EditorShortcut.RED, R.string.bbcode_color_red, bbcode = BbcodeAction.Color("#FF0000")),
    EditorShortcutEntry(EditorShortcut.BLUE, R.string.bbcode_color_blue, bbcode = BbcodeAction.Color("#0000FF")),
    EditorShortcutEntry(EditorShortcut.GREEN, R.string.bbcode_color_green, bbcode = BbcodeAction.Color("#008000")),
    EditorShortcutEntry(EditorShortcut.ORANGE, R.string.bbcode_color_orange, bbcode = BbcodeAction.Color("#FF6600")),
    EditorShortcutEntry(EditorShortcut.GREY, R.string.bbcode_color_grey, bbcode = BbcodeAction.Color("#808080")),
)

val editorShortcutCatalog: List<EditorShortcutEntry> =
    listOf(EditorShortcutEntry(EditorShortcut.NONE, R.string.editor_shortcut_none)) +
        editorShortcutFormats + editorShortcutColors + listOf(
            EditorShortcutEntry(EditorShortcut.UPLOAD_IMAGE, R.string.editor_bar_upload, R.drawable.ic_image_upload),
            EditorShortcutEntry(EditorShortcut.IMAGE_URL, R.string.editor_bar_image_url,
                R.drawable.ic_editor_image_url),
            EditorShortcutEntry(EditorShortcut.IMAGE_SELECTION, R.string.editor_bar_image_selection,
                R.drawable.ic_editor_image_selection, BbcodeAction.Image),
            EditorShortcutEntry(EditorShortcut.SEARCH_SMILEYS, R.string.editor_shortcut_search_smileys,
                R.drawable.ic_editor_search_smileys),
            EditorShortcutEntry(EditorShortcut.PERSONAL_SMILEY, R.string.editor_shortcut_personal_smiley,
                R.drawable.ic_editor_personal_smiley, BbcodeAction.PersonalSmiley),
        )

/** All routes reuse the bar's insertion or external-surface envelope, including scaffold IME capture. */
@Composable
@Suppress("LongParameterList") // Explicit rendering inputs and host insertion/surface envelopes.
internal fun EditorShortcutControl(
    shortcut: EditorBarShortcut,
    actions: EditorActionBarActions,
    images: EditorBarImageActions,
    enabled: Boolean,
    compactHeight: Boolean,
    insert: (BbcodeAction) -> Unit,
    external: (() -> Unit) -> Unit,
) {
    if (!shortcut.choice.isAvailableOn(shortcut.surface)) return
    val entry = editorShortcutCatalog.first { it.choice == shortcut.choice }
    val click = editorShortcutClick(entry, actions, images, insert, external) ?: return
    val label = stringResource(entry.labelRes)
    val color = entry.bbcode as? BbcodeAction.Color
    if (color != null) EditorColorIcon(color.colorHex, label, click, enabled && shortcut.enabled, compactHeight)
    else EditorBarIcon(checkNotNull(entry.iconRes), label, click,
        enabled = enabled && shortcut.enabled, compactHeight = compactHeight)
}

private fun editorShortcutClick(
    entry: EditorShortcutEntry,
    actions: EditorActionBarActions,
    images: EditorBarImageActions,
    insert: (BbcodeAction) -> Unit,
    external: (() -> Unit) -> Unit,
): (() -> Unit)? = when (entry.choice) {
    EditorShortcut.UPLOAD_IMAGE -> { { external(images.onUpload) } }
    EditorShortcut.IMAGE_URL -> images.onInsertUrl?.let { callback -> { external(callback) } }
    EditorShortcut.SEARCH_SMILEYS -> actions.onSmileySearch?.let { callback -> { external(callback) } }
    EditorShortcut.IMAGE_SELECTION -> if (images.canWrapSelection) ({ insert(BbcodeAction.Image) }) else null
    else -> entry.bbcode?.let { action -> { insert(action) } }
}
