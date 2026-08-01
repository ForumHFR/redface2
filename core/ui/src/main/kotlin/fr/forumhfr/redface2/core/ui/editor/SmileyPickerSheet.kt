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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.ui.post.LocalIntrinsicMediaSizeCache
import fr.forumhfr.redface2.core.ui.post.PixelSize
import fr.forumhfr.redface2.core.ui.post.intrinsicSmileyDisplaySize
import fr.forumhfr.redface2.core.ui.post.measureAndCacheIntrinsicMediaSize

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
        // #824 — land on the Wiki tab when a restored search materialises. An initial-value
        // capture is NOT enough: the controller may deliver the restored Open state a frame
        // after the sheet first composes, and the saveable registry can also replay a stale
        // Standard selection over the computed initial (both observed at dogfood). Keying the
        // effect on "has a query" keeps it one-shot per restore and inert during typing —
        // the search field only exists on the Wiki tab, so forcing index 1 there is a no-op.
        LaunchedEffect(state.query.isNotEmpty()) {
            if (state.query.isNotEmpty()) tabIndex = 1
        }
        val sheetTitle = stringResource(R.string.editor_smiley_title)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // #900 — header density pass (tinc, DEV #2790993) : the VISIBLE « Smileys » title
                // line is gone (the Standard/Wiki tabs already name the surface visually), spacing
                // tightened 12 → 8 dp. The sheet keeps an ACCESSIBLE name through paneTitle —
                // TalkBack still announces the surface (gate Sol r1). Touch targets keep 48 dp.
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { paneTitle = sheetTitle },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
    // #250 — reveal search on tab switch: focus + IME as soon as the Wiki tab composes, instead of
    // waiting for an extra tap on the field. `LaunchedEffect(Unit)` fires on each ENTRY into the
    // tab (the `when (tabIndex)` swaps this content in) and never again while the user stays on it.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        searchFocus.requestFocus()
    }
    // #901 — the field owns a TextFieldValue seeded from the (possibly #824-restored) query
    // with the caret at the END, so a restored word can be completed or erased right away.
    // The String overload of OutlinedTextField builds its internal TextFieldValue with
    // selection = TextRange(0), which put the restored caret at the START of the word.
    // Seeded ONCE (no resync from state.query) : while this tab is composed the only query
    // writer is this very field (the #824 restore happens in open(), atomically with the
    // Hidden→Open transition that composes the sheet), and a composition-time resync would
    // race the collectAsStateWithLifecycle echo of onQueryChange, clobbering fast typing.
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text = state.query, selection = TextRange(state.query.length)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { newValue ->
                // The TextFieldValue overload also fires on caret/selection moves : only a
                // text change may reach the controller, whose onQueryChanged cancels the
                // in-flight search — a mid-word tap must not kill a pending debounce.
                val textChanged = newValue.text != fieldValue.text
                fieldValue = newValue
                if (textChanged) onQueryChange(newValue.text)
            },
            singleLine = true,
            // #900 r1 (gate Sol) — the label STAYS a label : a placeholder disappears once the
            // user typed, losing the field's persistent name (a11y + context). The header gain
            // comes from the removed title line alone ; the placeholder/grid-cap leg of the
            // density pass is deferred to a short-screen/fontScale visual check (émulateur).
            label = { Text(stringResource(R.string.editor_smiley_search_label)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFocus),
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
            // beyond this point. #900 keeps this cap UNCHANGED for now (gate Sol r1 : raising
            // it needs a short-screen + fontScale proof, émulateur requis) — the density gain
            // ships as the removed title line.
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
    // #871 — persos reuse the posts' intrinsic-measurement pipeline (#175) : a bounded Coil probe
    // shares the SingletonImageLoader caches with the rendering AsyncImage below (no double
    // fetch), and the process-wide URL cache means a smiley measured in the picker is already
    // sized when it later renders inside a post — and vice versa. Builtins are never measured
    // (known ~16 px sprites, same contract as the posts). The cache read is a tracked snapshot
    // read : the cell recomposes at the measured size the moment the probe lands.
    val sizeCache = LocalIntrinsicMediaSizeCache.current
    val platformContext = LocalPlatformContext.current
    if (smiley.source == EditorSmileySource.WIKI) {
        LaunchedEffect(smiley.imageUrl) {
            measureAndCacheIntrinsicMediaSize(
                url = smiley.imageUrl,
                cache = sizeCache,
                context = platformContext,
                imageLoader = SingletonImageLoader.get(platformContext),
            )
        }
    }
    val measuredPx = if (smiley.source == EditorSmileySource.WIKI) sizeCache.get(smiley.imageUrl) else null
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
            // #816 (thibw) — restore the HFR scale contrast inside the uniform grid : builtins
            // are ~15 px sprites rendered near-native, persos are richer images that deserve
            // most of the cell. The single 30.dp of #236 made builtins big+blurry and persos
            // cramped at once. ContentScale.Fit preserves aspect ratios in both cases.
            // #871 — the perso box is the measured no-upscale size, so Fit never stretches a
            // small sprite past its native scale ; the parent Box centres it in the cell.
            modifier = Modifier.size(smileyCellImageSize(smiley.source, measuredPx)),
            contentScale = ContentScale.Fit,
            // Pixel-art builtins stay crisp unfiltered ; persos (photos, rich art, GIF frames)
            // look better bilinear-filtered when the cell cap scales them down.
            filterQuality = when (smiley.source) {
                EditorSmileySource.BUILTIN -> FilterQuality.None
                EditorSmileySource.WIKI -> FilterQuality.Low
            },
        )
    }
}

/**
 * #816 — the picker's per-source thumbnail size : builtins near their ~15 px native scale,
 * persos mirroring their relative size in a rendered post.
 *
 * #871 (thibw) — a MEASURED perso applies the posts' no-upscale + cap policy (#175,
 * [intrinsicSmileyDisplaySize]) at the forum scale (1 native px ≈ 1 dp, the picker analogue of
 * the renderer's `px → sp`) : a sprite smaller than the [WIKI_CELL_IMAGE_SIZE_DP] cell renders
 * at its crisp native size (centred by the cell), a larger one shrinks to fit, aspect ratio
 * preserved. An UNMEASURED perso (cold cache, or measurement failure — dead URL) keeps the
 * pre-#871 cell-filling square as provisional fallback. Pure — pinned by unit test.
 */
internal fun smileyCellImageSize(source: EditorSmileySource, measuredPx: IntSize?): DpSize = when (source) {
    EditorSmileySource.BUILTIN -> DpSize(20.dp, 20.dp)
    EditorSmileySource.WIKI -> {
        val nativePx = measuredPx?.takeIf { it.width > 0 && it.height > 0 }
        if (nativePx == null) {
            DpSize(WIKI_CELL_IMAGE_SIZE_DP.dp, WIKI_CELL_IMAGE_SIZE_DP.dp)
        } else {
            val display = intrinsicSmileyDisplaySize(
                nativePx = PixelSize(nativePx.width, nativePx.height),
                maxWidthSp = WIKI_CELL_IMAGE_SIZE_DP,
                maxHeightSp = WIKI_CELL_IMAGE_SIZE_DP,
            )
            DpSize(display.width.dp, display.height.dp)
        }
    }
}

/**
 * #816/#871 — the perso thumbnail slot inside the 48.dp cell : the fixed size of an unmeasured
 * perso, and the per-axis cap of a measured one.
 */
internal const val WIKI_CELL_IMAGE_SIZE_DP = 44
