package fr.forumhfr.redface2.core.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R

/** Informational chips have no click semantics; only measured overflow exposes the expansion button. */
@Composable
internal fun SmileyWikiKeywordsContent(state: SmileyWikiKeywordsState, onRetry: () -> Unit) {
    if (state == SmileyWikiKeywordsState.Hidden) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 72.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.editor_smiley_wiki_keywords_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        when (state) {
            SmileyWikiKeywordsState.Hidden -> Unit
            SmileyWikiKeywordsState.Loading -> SmileyWikiKeywordsStatus(R.string.editor_smiley_wiki_keywords_loading)
            SmileyWikiKeywordsState.Failed -> {
                SmileyWikiKeywordsStatus(R.string.editor_smiley_wiki_keywords_failed)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.editor_smiley_wiki_keywords_retry)) }
            }
            is SmileyWikiKeywordsState.Loaded -> {
                if (state.keywords.isEmpty()) {
                    SmileyWikiKeywordsStatus(R.string.editor_smiley_wiki_keywords_empty)
                } else {
                    SmileyWikiKeywordList(state.keywords)
                }
            }
        }
    }
}

@Composable
private fun SmileyWikiKeywordList(keywords: List<String>) {
    var expanded by remember(keywords) { mutableStateOf(false) }
    var overflowing by remember(keywords) { mutableStateOf(false) }
    SmileyKeywordRows(expanded, onOverflow = { overflowing = it }) {
        keywords.forEach { SmileyWikiKeywordChip(it) }
    }
    if (overflowing) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                if (expanded) {
                    stringResource(R.string.editor_smiley_wiki_keywords_collapse)
                } else {
                    stringResource(R.string.editor_smiley_wiki_keywords_expand, keywords.size)
                },
            )
        }
    }
}

/** Measure actual chips, including font scale and wrapping; no character-count approximation. */
@Composable
private fun SmileyKeywordRows(expanded: Boolean, onOverflow: (Boolean) -> Unit, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val rowGap = 4.dp.roundToPx()
        var x = 0
        var y = 0
        var row = 0
        var rowHeight = 0
        val placements = measurables.map { measurable ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            if (x > 0 && x + placeable.width > constraints.maxWidth) {
                y += rowHeight + rowGap
                x = 0
                row++
                rowHeight = 0
            }
            KeywordPlacement(placeable, x, y, row).also {
                x += placeable.width + gap
                rowHeight = maxOf(rowHeight, placeable.height)
            }
        }
        onOverflow(row >= 2)
        val visible = placements.filter { expanded || it.row < 2 }
        val width = visible.maxOfOrNull { it.x + it.placeable.width } ?: 0
        val height = visible.maxOfOrNull { it.y + it.placeable.height } ?: 0
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            visible.forEach { it.placeable.placeRelative(it.x, it.y) }
        }
    }
}

private data class KeywordPlacement(val placeable: Placeable, val x: Int, val y: Int, val row: Int)

@Composable
private fun SmileyWikiKeywordsStatus(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SmileyWikiKeywordChip(keyword: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            keyword, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
