package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.EditorSmileySource
import kotlin.math.roundToInt

/**
 * #989 — the smiley picker's geometry and scaling knobs, extracted from [SmileyPickerSheet] so a
 * device spike can drive the REAL grid instead of a copy of it (cadrage Sol : `SmileyGrid` and
 * `SmileyCell` were private with their constants inlined, so a debug-only Activity could only ever
 * have duplicated them — and then compared a copy against itself).
 *
 * [Current] reproduces the shipped picker **to the dp**: `Adaptive(48.dp)` columns, 16 dp of sheet
 * padding, 8 dp of spacing, a square 48 dp cell, a 44 dp per-axis image cap and the no-upscale
 * ceiling inherited from #871. Every spike preset is a deviation from it, and the preset XaTriX
 * retains becomes the fix by changing these defaults — no second implementation.
 *
 * IMPORTANT — this is the PICKER's policy, deliberately DISTINCT from the posts' one
 * ([fr.forumhfr.redface2.core.ui.post.intrinsicSmileyDisplaySize], which §9 of the images contract
 * declares « intouchable » and which #871 aligned on the posts). The two answer different
 * questions: a post reproduces HFR's own scale (web/RF1 fidelity), a picker makes a thumbnail
 * RECOGNISABLE at a glance. What the two DO share is the measured NATIVE size (the process-wide
 * `IntrinsicMediaSizeCache`) — never the display policy. Keeping the divergence on this side of the
 * seam is what lets the picker relax the no-upscale rule without re-opening the contract.
 */
data class SmileyPickerLayoutSpec(
    /** Minimum column width — the same role as `GridCells.Adaptive(minSize)`. */
    val minCellWidth: Dp = 48.dp,
    /** Cell width / height. `1f` is the shipped square cell; `70f / 50f` is the dominant perso format. */
    val cellAspectRatio: Float = 1f,
    /** Horizontal padding of the sheet content around the grid. */
    val gridPadding: Dp = 16.dp,
    /** Gap between cells, on both axes. */
    val cellSpacing: Dp = 8.dp,
    /** Breathing room subtracted from the cell to get the image cap, per axis. */
    val imageInset: Dp = 4.dp,
    /** Upscale ceiling of a MEASURED perso. `1f` = no upscale, the #871 policy. */
    val persoScaleCeiling: Float = 1f,
    /** Fixed size of a builtin sprite — never measured (known ~16 px HFR icons, #816). */
    val builtinImageSize: Dp = 20.dp,
    /** Material's minimum touch target: the cell height never goes below it, whatever the ratio. */
    val minCellHeight: Dp = 48.dp,
    /** Hairline around each cell — a candidate product option, not just a debug aid (#989). */
    val cellOutline: Boolean = false,
    /** Spike-only: overlay the cell box, the image box and the measured native size. */
    val debugOverlay: Boolean = false,
) {
    companion object {
        /** The shipped picker — the baseline every spike preset is measured against. */
        val Current = SmileyPickerLayoutSpec()
    }
}

/**
 * The grid's geometry resolved for one available width: how many columns fit, how big a cell is,
 * and the image cap that follows from it.
 */
data class SmileyGridGeometry(
    val columns: Int,
    val cellWidth: Dp,
    val cellHeight: Dp,
    val capWidth: Dp,
    val capHeight: Dp,
)

/**
 * Column solver replicating `GridCells.Adaptive`'s own rule, so that the cell width becomes KNOWN
 * and the image cap can follow the cell instead of being a hardcoded 44 dp.
 *
 * Why not keep `Adaptive` and be done: it never tells the cell its own resolved size. Why not
 * `Fixed(5)` either: it is right on an S10e in portrait and wrong everywhere else — in landscape or
 * on a tablet it stretches five cells across the whole width (cadrage Sol).
 *
 * **The arithmetic is in PHYSICAL PIXELS, and that is load-bearing** (gate Sol r2, bloquant).
 * `Adaptive` computes `max((availableSize + spacing) / (minSize.roundToPx() + spacing), 1)` with
 * every term an `Int` in pixels. A dp-space version diverges: at a real width of 327.5 dp under
 * density 2.0, dp-space arithmetic yields 6 columns of 47.92 dp — under the Material touch minimum —
 * where `Adaptive` correctly stays at 5. An earlier revision made it worse by adding an epsilon to
 * "absorb rounding": that epsilon was precisely what pushed the borderline case over. Matching
 * `Adaptive` in pixel space also makes `cellWidth >= minCellWidth` true by construction, so the
 * horizontal touch minimum needs no separate guard.
 *
 * The cell height derives from [SmileyPickerLayoutSpec.cellAspectRatio] and is floored at
 * [SmileyPickerLayoutSpec.minCellHeight] — a landscape cell must not drop under the touch minimum.
 *
 * Note on the returned [SmileyGridGeometry.cellWidth]: `Adaptive` distributes the leftover pixels by
 * giving one extra pixel to the first columns, so real cells can differ by 1 px. This returns the
 * BASE width (the integer division), i.e. the narrowest cell — the conservative choice for a cap.
 */
fun smileyGridGeometry(
    availableWidth: Dp,
    density: Density,
    spec: SmileyPickerLayoutSpec = SmileyPickerLayoutSpec.Current,
): SmileyGridGeometry = with(density) {
    val availablePx = availableWidth.roundToPx()
    val spacingPx = spec.cellSpacing.roundToPx()
    val minCellPx = spec.minCellWidth.roundToPx()
    val columns = ((availablePx + spacingPx) / (minCellPx + spacingPx)).coerceAtLeast(1)
    val cellWidthPx = (availablePx - spacingPx * (columns - 1)) / columns
    val cellWidth = cellWidthPx.toDp()
    val cellHeight = maxOf(cellWidth / spec.cellAspectRatio, spec.minCellHeight)
    SmileyGridGeometry(
        columns = columns,
        cellWidth = cellWidth,
        cellHeight = cellHeight,
        capWidth = (cellWidth - spec.imageInset).coerceAtLeast(MIN_IMAGE_EXTENT),
        capHeight = (cellHeight - spec.imageInset).coerceAtLeast(MIN_IMAGE_EXTENT),
    )
}

/**
 * #816/#871/#989 — the picker's per-source thumbnail size.
 *
 * Builtins keep their fixed near-native size (never measured). A MEASURED perso is scaled by
 * `min(capW/w, capH/h, ceiling)`: at the default ceiling of `1f` this is byte-for-byte the #871
 * no-upscale behaviour (a 15×15 sprite stays 15×15, a 70×50 shrinks to fit, ratio preserved); a
 * ceiling above `1f` lets a small sprite grow — the #989 lever for the long tail of tiny perso
 * (`[:rofl]` is 39×15 native, illegible in a 48 dp cell). An UNMEASURED perso (cold cache, or a
 * dead URL) keeps the cap-filling rectangle as a provisional size, which minimises reflow when the
 * probe lands.
 *
 * Rounded to whole dp like the posts' policy, so the shipped defaults keep producing the exact
 * same numbers as `smileyCellImageSize` did before the extraction (pinned by test).
 */
internal fun pickerSmileyImageSize(
    source: EditorSmileySource,
    measuredPx: IntSize?,
    geometry: SmileyGridGeometry,
    spec: SmileyPickerLayoutSpec = SmileyPickerLayoutSpec.Current,
): DpSize = when (source) {
    EditorSmileySource.BUILTIN -> DpSize(spec.builtinImageSize, spec.builtinImageSize)
    EditorSmileySource.WIKI -> {
        val native = measuredPx?.takeIf { it.width > 0 && it.height > 0 }
        if (native == null) {
            DpSize(geometry.capWidth, geometry.capHeight)
        } else {
            val scale = minOf(
                geometry.capWidth.value / native.width,
                geometry.capHeight.value / native.height,
                spec.persoScaleCeiling,
            )
            DpSize(
                width = (native.width * scale).roundToInt().coerceAtLeast(1).dp,
                height = (native.height * scale).roundToInt().coerceAtLeast(1).dp,
            )
        }
    }
}

/** Anti-collapse floor, same guard as the posts' policy: never hand Compose a 0 dp image slot. */
private val MIN_IMAGE_EXTENT = 1.dp
