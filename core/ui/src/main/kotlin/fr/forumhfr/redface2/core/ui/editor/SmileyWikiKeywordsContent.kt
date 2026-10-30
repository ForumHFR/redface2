package fr.forumhfr.redface2.core.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R

/** Informational chips have no click semantics; long lists wrap and scroll with the detail. */
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.keywords.forEach { SmileyWikiKeywordChip(it) }
                    }
                }
            }
        }
    }
}

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
