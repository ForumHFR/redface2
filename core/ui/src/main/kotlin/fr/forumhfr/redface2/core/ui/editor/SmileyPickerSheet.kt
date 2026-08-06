package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.ui.R

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import fr.forumhfr.redface2.core.domain.preferences.SmileyPickerDecoration
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.ui.post.LocalIntrinsicMediaSizeCache
import fr.forumhfr.redface2.core.ui.theme.LocalSmileyPickerDecoration
import fr.forumhfr.redface2.core.ui.post.LocalMediaAttemptLedger
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
// LongParameterList: the sheet's four callbacks + modifier are each a distinct call-site concern,
// and #989 adds the layout spec as a defaulted sixth. Bundling them would only move the parameter
// list into a wrapper type nobody else needs — same stance as RedfaceTheme.
@Suppress("LongParameterList")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmileyPickerSheet(
    state: SmileyPickerState.Open,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSmileyClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    layout: SmileyPickerLayoutSpec = SmileyPickerLayoutSpec.Current,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetMaxWidth = SMILEY_SHEET_MAX_WIDTH,
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
                // #989 — the horizontal padding is a spike knob (rogner les marges = one of the
                // levers on the cell width); the vertical one stays at the #900 value.
                .padding(horizontal = layout.gridPadding, vertical = 8.dp)
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
                0 -> StandardTabContent(onSmileyClicked = onSmileyClicked, layout = layout)
                else -> WikiTabContent(
                    state = state,
                    onQueryChange = onQueryChange,
                    onSmileyClicked = onSmileyClicked,
                    layout = layout,
                )
            }
        }
    }
}

@Composable
private fun StandardTabContent(onSmileyClicked: (String) -> Unit, layout: SmileyPickerLayoutSpec) {
    SmileyPickerGrid(items = BUILTIN_HFR_SMILEYS, onSmileyClicked = onSmileyClicked, layout = layout)
}

@Composable
private fun WikiTabContent(
    state: SmileyPickerState.Open,
    onQueryChange: (String) -> Unit,
    onSmileyClicked: (String) -> Unit,
    layout: SmileyPickerLayoutSpec,
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
                    SmileyPickerGrid(items = wiki.items, onSmileyClicked = onSmileyClicked, layout = layout)
                }
            }
        }
    }
}

/**
 * The picker's grid of smileys, driven by a [SmileyPickerLayoutSpec].
 *
 * Public since #989 so the device spike can render the REAL grid under alternative specs instead of
 * comparing a copy against itself. Callers inside the sheet pass the spec down from
 * [SmileyPickerSheet]; the default is the shipped geometry.
 */
@Composable
fun SmileyPickerGrid(
    items: List<EditorSmiley>,
    onSmileyClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    layout: SmileyPickerLayoutSpec = SmileyPickerLayoutSpec.Current,
) {
    // #900 volet 2 (CharLee, TU #2791061) — the grid cap scales with the screen so the sheet
    // reaches ~3/4 of a phone display instead of the fixed 320 dp, which wasted the bottom half
    // on tall screens. The 320 dp FLOOR keeps short screens (landscape, split-screen) exactly at
    // the previous behaviour — the night gate's condition (Sol r1) : never LESS room than before,
    // and the M3 sheet still clamps itself to the window insets beyond that. The fraction leaves
    // headroom for the sheet chrome (tabs + search field), which grows with fontScale on its own.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val gridHeightCap = maxOf(SMILEY_GRID_MIN_HEIGHT_CAP, screenHeight * SMILEY_GRID_SCREEN_FRACTION)
    // #989 — the constraints give the grid its own width, so the target-cell solver can resolve a
    // KNOWN column count and cell width ([smileyGridGeometry]) — and therefore an image cap that
    // follows the cell instead of the hardcoded 44 dp.
    // #989 — the delimiter comes from the user setting, not from the call site: the picker is opened
    // from four screens and none of them should have to know about it. The spec still wins when it
    // explicitly asks for a decoration (the debug bench does), so the bench can compare styles.
    val settingDecoration = LocalSmileyPickerDecoration.current
    val resolved = if (layout.cellDecoration == SmileyPickerDecoration.NONE) {
        settingDecoration
    } else {
        layout.cellDecoration
    }
    // #989 follow-up — the solver always resolves columns with the NOMINAL spacing, then the
    // geometry exposes the RENDER spacing. SEPARATORS therefore keep the same column count but pass
    // 0 dp to the grid arrangement, making the per-cell right/bottom rules join continuously.
    val layout = layout.copy(cellDecoration = resolved)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val geometry = smileyGridGeometry(
            availableWidth = maxWidth,
            density = LocalDensity.current,
            spec = layout,
        )
        LazyVerticalGrid(
            // #236 — denser grid: smaller min cell (was 64.dp) packs more smileys per row. #989 —
            // `Fixed` over the SOLVED count, not a hardcoded 5: portrait stays dense, landscape
            // stays responsive, and the cell knows its size.
            columns = GridCells.Fixed(geometry.columns),
            horizontalArrangement = Arrangement.spacedBy(geometry.cellSpacing),
            verticalArrangement = Arrangement.spacedBy(geometry.cellSpacing),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = gridHeightCap),
        ) {
            items(items = items, key = { it.token to it.imageUrl }) { smiley ->
                SmileyCell(
                    smiley = smiley,
                    geometry = geometry,
                    layout = layout,
                    onClick = { onSmileyClicked(smiley.token) },
                )
            }
        }
    }
}

@Composable
private fun SmileyCell(
    smiley: EditorSmiley,
    geometry: SmileyGridGeometry,
    layout: SmileyPickerLayoutSpec,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.editor_smiley_insert_description, smiley.token)
    // #871 — persos reuse the posts' intrinsic-measurement pipeline (#175) : a bounded Coil probe
    // shares the SingletonImageLoader caches with the rendering AsyncImage below (no double
    // fetch), and the process-wide URL cache means a smiley measured in the picker is already
    // sized when it later renders inside a post — and vice versa. Builtins are never measured
    // (known ~16 px sprites, same contract as the posts). The cache read is a tracked snapshot
    // read : the cell recomposes at the measured size the moment the probe lands.
    val sizeCache = LocalIntrinsicMediaSizeCache.current
    // #960 — the shared measurement seam settles probe outcomes on the ambient attempt ledger
    // (single source of truth for failures) ; the picker keeps its fire-once effect, the ledger's
    // TTL/generations only matter to the posts' retry pipeline.
    val ledger = LocalMediaAttemptLedger.current
    val platformContext = LocalPlatformContext.current
    if (smiley.source == EditorSmileySource.WIKI) {
        LaunchedEffect(smiley.imageUrl) {
            measureAndCacheIntrinsicMediaSize(
                url = smiley.imageUrl,
                cache = sizeCache,
                ledger = ledger,
                context = platformContext,
                imageLoader = SingletonImageLoader.get(platformContext),
            )
        }
    }
    val measuredPx = if (smiley.source == EditorSmileySource.WIKI) sizeCache.get(smiley.imageUrl)?.size else null
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            // #236 — 48.dp kept the Material minimum touch target while the grid got denser. #989 —
            // the cell now fills its solved column and takes its height from the spec's ratio,
            // floored at that same 48 dp minimum ([smileyGridGeometry]).
            .fillMaxWidth()
            .height(geometry.cellHeight)
            .then(
                when (layout.cellDecoration) {
                    SmileyPickerDecoration.OUTLINE ->
                        Modifier.border(1.dp, outlineColor, RoundedCornerShape(CELL_OUTLINE_RADIUS))
                    // #989 — right + bottom edges only: adjacent cells share their rules, so the
                    // grid reads as a continuous table instead of a set of boxes. The geometry
                    // drops only the render spacing to 0 dp for this mode, so the edges meet while
                    // the nominal spacing still drives the column count.
                    SmileyPickerDecoration.SEPARATORS -> Modifier.drawBehind {
                        val stroke = 1.dp.toPx()
                        drawLine(
                            color = outlineColor,
                            start = Offset(size.width - stroke / 2f, 0f),
                            end = Offset(size.width - stroke / 2f, size.height),
                            strokeWidth = stroke,
                        )
                        drawLine(
                            color = outlineColor,
                            start = Offset(0f, size.height - stroke / 2f),
                            end = Offset(size.width, size.height - stroke / 2f),
                            strokeWidth = stroke,
                        )
                    }
                    SmileyPickerDecoration.NONE -> Modifier
                }
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (layout.debugOverlay) {
            SmileyCellDebugOverlay(smiley = smiley, measuredPx = measuredPx)
        }
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
            // #989 — the cap now follows the cell and the ceiling comes from the spec.
            modifier = Modifier
                .size(pickerSmileyImageSize(smiley.source, measuredPx, geometry, layout))
                .then(
                    if (layout.debugOverlay) {
                        Modifier.border(1.dp, DEBUG_IMAGE_BOX_COLOR)
                    } else {
                        Modifier
                    }
                ),
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
 * #989 spike aid (XaTriX's explicit ask: « affiche les containers pour debug et aider au choix ») —
 * paints the cell box and states the smiley's MEASURED native size, so a screenshot shows whether a
 * thumbnail looks small because of its source file, because the cap bit into it, or because the
 * intrinsic probe has not landed yet. The border colour carries the measurement state: an
 * unmeasured perso is the case where the cap-filling fallback is what you are looking at, not the
 * policy (cadrage Sol, risque nº4 — capturing a cold cache and judging the size from it).
 */
@Composable
private fun SmileyCellDebugOverlay(smiley: EditorSmiley, measuredPx: IntSize?) {
    val isPerso = smiley.source == EditorSmileySource.WIKI
    val stateColor = when {
        !isPerso -> DEBUG_BUILTIN_COLOR
        measuredPx != null -> DEBUG_MEASURED_COLOR
        else -> DEBUG_UNMEASURED_COLOR
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, stateColor)
            // The measurement label is a developer aid, not content: TalkBack must keep announcing
            // the cell's own contentDescription, not "70×50" (gate Sol r2).
            .clearAndSetSemantics {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            text = measuredPx?.let { "${it.width}×${it.height}" } ?: "?",
            style = MaterialTheme.typography.labelSmall,
            fontSize = DEBUG_LABEL_SIZE,
            color = stateColor,
        )
    }
}

/**
 * #989 follow-up — 840 dp, not `Dp.Unspecified`: on a 1280 dp tablet, lifting the cap entirely
 * would create roughly 15-16 thumbnails per row and push the centre out of the thumb zone. 840 dp
 * keeps the picker capped around 10 columns while still freeing phone landscape from M3's 640 dp
 * default.
 */
private val SMILEY_SHEET_MAX_WIDTH = 840.dp

/**
 * #900 volet 2 — the grid's height budget : [SMILEY_GRID_SCREEN_FRACTION] of the screen height,
 * floored at the historical 320 dp so short screens (landscape, split-screen) never get LESS grid
 * than before. 0.62 of the height plus the sheet chrome (tabs, search field, paddings) lands the
 * whole sheet around three quarters of a typical phone display (CharLee's ask, TU #2791061).
 */
private val SMILEY_GRID_MIN_HEIGHT_CAP = 320.dp
private const val SMILEY_GRID_SCREEN_FRACTION = 0.62f

/** #989 — corner radius of the optional cell hairline (candidate product option). */
private val CELL_OUTLINE_RADIUS = 8.dp

/** #989 — debug overlay palette: image box, then the per-cell measurement state. */
private val DEBUG_IMAGE_BOX_COLOR = Color(0xFFE23A4E)
private val DEBUG_MEASURED_COLOR = Color(0xFF34C0CE)
private val DEBUG_UNMEASURED_COLOR = Color(0xFFFFB020)
private val DEBUG_BUILTIN_COLOR = Color(0xFF8E8E93)
private val DEBUG_LABEL_SIZE = 7.sp
