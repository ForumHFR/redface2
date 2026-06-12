package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.ui.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley

/**
 * Phase 2F-B (#11 partial) — bottom-sheet smiley picker. Promoted from `:feature:editor`
 * to `:core:ui` for the MP editors (#387) — same pattern as `EditorOptionsSheet` (#388).
 *
 * Two tabs :
 *  - **Standard** : the canonical `BUILTIN_HFR_SMILEYS` constant, ~25 entries, available
 *    synchronously without a network call.
 *  - **Wiki** : live search on HFR's perso corpus via `SmileyRepository.searchWiki`. Idle
 *    until the user types more than 2 characters (HFR's web composer threshold). Loading
 *    / Results / Error / Empty handled in [WikiTabContent].
 *
 * Tap on any smiley emits [onSmileyClicked] with the BBCode token, which the caller wraps
 * into the editor's `TextFieldValue` via the formatter helper. The sheet then dismisses
 * itself through [onDismiss] so the user can keep typing — chained insertions re-open it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmileyPickerSheet(
    state: SmileyPickerState.Open,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSmileyClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        // Local-only tab selection : the picker's tab state is not worth piping all the
        // way into the ViewModel. `rememberSaveable` so a configuration change (rotation,
        // dark mode toggle) does not reset the user back to the Standard tab while the
        // bottom-sheet is still open.
        var tabIndex by rememberSaveable { mutableStateOf(0) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.editor_smiley_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PrimaryTabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = { Text(stringResource(R.string.editor_smiley_tab_standard)) },
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = { Text(stringResource(R.string.editor_smiley_tab_wiki)) },
                )
            }
            when (tabIndex) {
                0 -> StandardTabContent(onSmileyClicked = onSmileyClicked)
                else -> WikiTabContent(
                    state = state,
                    onQueryChange = onQueryChange,
                    onSmileyClicked = onSmileyClicked,
                )
            }
        }
    }
}

@Composable
private fun StandardTabContent(onSmileyClicked: (String) -> Unit) {
    SmileyGrid(items = BUILTIN_HFR_SMILEYS, onSmileyClicked = onSmileyClicked)
}

@Composable
private fun WikiTabContent(
    state: SmileyPickerState.Open,
    onQueryChange: (String) -> Unit,
    onSmileyClicked: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text(stringResource(R.string.editor_smiley_search_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        when (val wiki = state.wiki) {
            WikiSearchState.Idle -> Text(
                text = stringResource(R.string.editor_smiley_search_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            WikiSearchState.Loading -> {
                val loadingDescription = stringResource(R.string.editor_smiley_search_loading)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                        // a11y : `mergeDescendants = true` ensures TalkBack treats this Box as a
                        // single node and reads the `contentDescription` ("Recherche…") instead
                        // of falling through to the child `CircularProgressIndicator` whose
                        // own ProgressBar role would otherwise win the announcement.
                        .semantics(mergeDescendants = true) { contentDescription = loadingDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            WikiSearchState.Error -> Text(
                text = stringResource(R.string.editor_smiley_search_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            is WikiSearchState.Results -> {
                if (wiki.items.isEmpty()) {
                    Text(
                        text = stringResource(R.string.editor_smiley_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    SmileyGrid(items = wiki.items, onSmileyClicked = onSmileyClicked)
                }
            }
        }
    }
}

@Composable
private fun SmileyGrid(items: List<EditorSmiley>, onSmileyClicked: (String) -> Unit) {
    LazyVerticalGrid(
        // #236 — denser grid: smaller min cell (was 64.dp) packs more smileys per row.
        columns = GridCells.Adaptive(minSize = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            // Cap the grid height inside the bottom sheet so it does not eat the whole
            // screen on small devices ; the sheet's own scrollable container takes over
            // beyond this point.
            .heightIn(max = 320.dp),
    ) {
        items(items = items, key = { it.token to it.imageUrl }) { smiley ->
            SmileyCell(smiley = smiley, onClick = { onSmileyClicked(smiley.token) })
        }
    }
}

@Composable
private fun SmileyCell(smiley: EditorSmiley, onClick: () -> Unit) {
    val description = stringResource(R.string.editor_smiley_insert_description, smiley.token)
    Box(
        modifier = Modifier
            // #236 — 48.dp keeps the Material minimum touch target while the grid gets denser.
            .size(48.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = smiley.imageUrl,
            // contentDescription is on the parent Box so the click target carries it ;
            // null here keeps TalkBack from announcing the image twice.
            contentDescription = null,
            // #236 — render at 30.dp (was 48.dp): HFR builtins are ~16 px sprites, so a smaller
            // box stops the ×3 upscale that looked huge and blurry. ContentScale.Fit preserves
            // the aspect ratio of taller perso smileys; FilterQuality.None keeps pixel-art crisp.
            modifier = Modifier.size(30.dp),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
    }
}
