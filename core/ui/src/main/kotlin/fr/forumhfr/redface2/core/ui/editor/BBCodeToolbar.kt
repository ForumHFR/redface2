package fr.forumhfr.redface2.core.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R

/**
 * Horizontal Material 3 toolbar exposing the Phase 2B BBCode actions.
 *
 * Buttons stay typed via [BbcodeAction] — passing strings around would re-introduce
 * the kind of stringly-typed editor API the placeholder used to have. Each action is
 * shown as an [AssistChip] (Material 3 chips, not custom buttons) so the toolbar
 * inherits the elevation, focus and pressed states from the theme.
 */
@Composable
fun BBCodeToolbar(
    onAction: (BbcodeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ToolbarOrder.forEach { action ->
            ActionChip(action = action, onAction = onAction)
        }
    }
}

private val ToolbarOrder: List<BbcodeAction> = listOf(
    BbcodeAction.Bold,
    BbcodeAction.Italic,
    BbcodeAction.Underline,
    BbcodeAction.Strike,
    BbcodeAction.Quote,
    BbcodeAction.Code,
    BbcodeAction.Cpp,
    BbcodeAction.Fixed,
    BbcodeAction.Spoiler,
    BbcodeAction.Url,
    BbcodeAction.Image,
)

@Composable
private fun ActionChip(
    action: BbcodeAction,
    onAction: (BbcodeAction) -> Unit,
) {
    AssistChip(
        onClick = { onAction(action) },
        label = { Text(stringResource(action.labelResId)) },
        border = AssistChipDefaults.assistChipBorder(enabled = true),
    )
}

@get:StringRes
private val BbcodeAction.labelResId: Int
    get() = when (this) {
        BbcodeAction.Bold -> R.string.bbcode_action_bold
        BbcodeAction.Italic -> R.string.bbcode_action_italic
        BbcodeAction.Underline -> R.string.bbcode_action_underline
        BbcodeAction.Strike -> R.string.bbcode_action_strike
        BbcodeAction.Quote -> R.string.bbcode_action_quote
        BbcodeAction.Code -> R.string.bbcode_action_code
        BbcodeAction.Cpp -> R.string.bbcode_action_cpp
        BbcodeAction.Fixed -> R.string.bbcode_action_fixed
        BbcodeAction.Spoiler -> R.string.bbcode_action_spoiler
        BbcodeAction.Url -> R.string.bbcode_action_url
        BbcodeAction.Image -> R.string.bbcode_action_image
    }
