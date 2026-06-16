package fr.forumhfr.redface2.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Footer line of a topic row, split in two segments (#325, generalised to all three topic
 * lists in #376). [start] is the only segment allowed to truncate (ellipsis); [end] —
 * typically the last-reply timestamp — keeps its intrinsic width, pinned to the row's end.
 * Either side may be empty. Bundled as one value so callers stay under the detekt
 * parameter-count threshold.
 */
data class FlagMetadata(val start: String = "", val end: String = "")

/**
 * #376 — shared two-segment metadata line for every topic-list row (drapeaux, catégorie,
 * recherche). Extracted from [FlagItem] so the three lists render the SAME template:
 *
 *  - [FlagMetadata.start] (e.g. `auteur · p.X/Y`) takes the remaining width and ellipsises;
 *  - [FlagMetadata.end] (the last-reply timestamp) keeps its intrinsic width, end-aligned,
 *    and is NEVER truncated — the date survives on narrow screens, the left segment clips
 *    first (dogfooding feedback on v102, where the date was last in a single string and was
 *    the part being cut off).
 *
 * Renders nothing when both segments are blank. [style] / [color] are passed in so each list
 * keeps its own typography role (drapeaux/catégorie use `labelSmall`, recherche uses
 * `bodySmall`) and its own highlight tint — only the LAYOUT is harmonised, not the chrome.
 */
@Composable
fun TopicMetadataLine(
    metadata: FlagMetadata,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (metadata.start.isEmpty() && metadata.end.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = metadata.start,
            style = style,
            color = color,
            maxLines = 1,
            // Only the START segment may truncate; the end-aligned timestamp keeps its
            // intrinsic width (weight measures this text in the remaining space).
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (metadata.end.isNotEmpty()) {
            Text(
                text = metadata.end,
                style = style,
                color = color,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
