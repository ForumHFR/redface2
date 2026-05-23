package fr.forumhfr.redface2.core.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R

/**
 * Horizontal Material 3 toolbar exposing the Phase 2B BBCode actions plus the
 * Phase 2B-B colour palette. Each button stays typed via [BbcodeAction] so the
 * caller never deals with raw tag strings.
 *
 * Layout : a horizontally-scrollable [Row] of [AssistChip]s for the fixed-tag
 * actions, followed by a single colour chip that opens a [DropdownMenu] with
 * the MVP palette (#FF0000 / #0000FF / #008000 / #FF6600 / #808080). The
 * palette is small on purpose — a free-text hex picker is deferred.
 */
@Composable
fun BbcodeToolbar(
    onAction: (BbcodeAction) -> Unit,
    modifier: Modifier = Modifier,
    onImageUrlRequested: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ToolbarOrder.forEach { action ->
            ActionChip(
                action = action,
                onAction = onAction,
                onImageUrlRequested = onImageUrlRequested,
            )
        }
        ColorChip(onAction = onAction)
    }
}

/**
 * Ordered list of fixed-tag actions rendered as plain chips. [BbcodeAction.Color]
 * is intentionally excluded — its UI is the [ColorChip] dropdown.
 */
private val ToolbarOrder: List<BbcodeAction> = listOf(
    BbcodeAction.Bold,
    BbcodeAction.Italic,
    BbcodeAction.Underline,
    BbcodeAction.Strike,
    BbcodeAction.Quote,
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
    onImageUrlRequested: (() -> Unit)?,
) {
    AssistChip(
        onClick = {
            if (action == BbcodeAction.Image && onImageUrlRequested != null) {
                onImageUrlRequested()
            } else {
                onAction(action)
            }
        },
        label = { Text(stringResource(action.labelResId)) },
        border = AssistChipDefaults.assistChipBorder(enabled = true),
    )
}

@Composable
private fun ColorChip(
    onAction: (BbcodeAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Attached to the trigger chip (not the menu container) so TalkBack reads
    // the « opens the colour picker » hint on focus, before the user opens it.
    val triggerDescription = stringResource(R.string.bbcode_action_color_menu_description)
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(stringResource(R.string.bbcode_action_color)) },
            border = AssistChipDefaults.assistChipBorder(enabled = true),
            modifier = Modifier.semantics { contentDescription = triggerDescription },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ColorPalette.forEach { swatch ->
                DropdownMenuItem(
                    text = { Text(stringResource(swatch.labelResId)) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color(swatch.previewArgb)),
                        )
                    },
                    onClick = {
                        expanded = false
                        onAction(BbcodeAction.Color(swatch.hex))
                    },
                )
            }
        }
    }
}

/**
 * MVP palette. The `hex` is what travels to HFR (`[#RRGGBB]…[/#RRGGBB]`) ;
 * the `previewArgb` is the same colour reinterpreted as `0xFFRRGGBB` for the
 * Compose swatch.
 */
private data class ColorSwatch(
    val hex: String,
    val previewArgb: Long,
    @field:StringRes val labelResId: Int,
)

private val ColorPalette: List<ColorSwatch> = listOf(
    ColorSwatch(hex = "#FF0000", previewArgb = 0xFFFF0000, labelResId = R.string.bbcode_color_red),
    ColorSwatch(hex = "#0000FF", previewArgb = 0xFF0000FF, labelResId = R.string.bbcode_color_blue),
    ColorSwatch(hex = "#008000", previewArgb = 0xFF008000, labelResId = R.string.bbcode_color_green),
    ColorSwatch(hex = "#FF6600", previewArgb = 0xFFFF6600, labelResId = R.string.bbcode_color_orange),
    ColorSwatch(hex = "#808080", previewArgb = 0xFF808080, labelResId = R.string.bbcode_color_grey),
)

@get:StringRes
private val BbcodeAction.labelResId: Int
    get() = when (this) {
        BbcodeAction.Bold -> R.string.bbcode_action_bold
        BbcodeAction.Italic -> R.string.bbcode_action_italic
        BbcodeAction.Underline -> R.string.bbcode_action_underline
        BbcodeAction.Strike -> R.string.bbcode_action_strike
        BbcodeAction.Quote -> R.string.bbcode_action_quote
        BbcodeAction.Cpp -> R.string.bbcode_action_cpp
        BbcodeAction.Fixed -> R.string.bbcode_action_fixed
        BbcodeAction.Spoiler -> R.string.bbcode_action_spoiler
        BbcodeAction.Url -> R.string.bbcode_action_url
        BbcodeAction.Image -> R.string.bbcode_action_image
        // Color is rendered via [ColorChip], never the [ActionChip] path —
        // fail-fast rather than silently return a label, so a future refactor
        // that routes a Color through ActionChip surfaces the contract break
        // immediately instead of shipping the wrong label.
        is BbcodeAction.Color -> error("BbcodeAction.Color is rendered via ColorChip, not ActionChip")
    }
