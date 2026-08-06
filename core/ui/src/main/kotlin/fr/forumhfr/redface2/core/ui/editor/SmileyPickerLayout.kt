package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.SmileyPickerDecoration
import fr.forumhfr.redface2.core.model.EditorSmileySource
import kotlin.math.roundToInt

/** The dominant perso format of the HFR corpus (31 % of the measured top 100) drives the target cell width. */
private val DOMINANT_PERSO_WIDTH = 70.dp

/** Companion of [DOMINANT_PERSO_WIDTH]: the height of that same dominant 70×50 format. */
private val DOMINANT_PERSO_HEIGHT = 50.dp

/** Below this AVAILABLE width the five-column floor would degrade the dominant perso too far: floor at four. */
private val FIVE_COLUMN_MIN_AVAILABLE_WIDTH = 324.dp

/**
 * #989 — the smiley picker's geometry and scaling knobs, extracted from [SmileyPickerSheet] so a
 * device spike can drive the REAL grid instead of a copy of it (cadrage Sol : `SmileyGrid` and
 * `SmileyCell` were private with their constants inlined, so a debug-only Activity could only ever
 * have duplicated them — and then compared a copy against itself).
 *
 * [Current] IS the shipped geometry. Since the #989 follow-up it solves toward the smallest cell
 * that can render the dominant 70×50 perso at native size on both axes, then applies a 5-column
 * phone floor (4 below the available-width threshold) and keeps the historical `Adaptive(56.dp)`
 * rule as the tactile upper bound. On a 360 dp phone the floor deliberately preserves the shipped
 * #989 result: 5 cells of 65,33×48 dp with a 61,33×44 cap. On wider portrait phones the 7:5 ratio
 * starts doing real work and the dominant perso reaches native size once the cap allows it.
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
    /** Minimum tactile column width, used as the upper bound on column count. */
    val minCellWidth: Dp = 56.dp,
    /** Cell width / height. The default follows the dominant HFR perso format. */
    val cellAspectRatio: Float = DOMINANT_PERSO_WIDTH.value / DOMINANT_PERSO_HEIGHT.value,
    /** Horizontal padding of the sheet content around the grid. */
    val gridPadding: Dp = 8.dp,
    /** Gap between cells, on both axes. */
    val cellSpacing: Dp = 4.dp,
    /** Breathing room subtracted from the cell to get the image cap, per axis. */
    val imageInset: Dp = 4.dp,
    /** Upscale ceiling of a MEASURED perso. `1f` = no upscale, the #871 policy. */
    val persoScaleCeiling: Float = 1f,
    /** Fixed size of a builtin sprite — never measured (known ~16 px HFR icons, #816). */
    val builtinImageSize: Dp = 20.dp,
    /** Material's minimum touch target: the cell height never goes below it, whatever the ratio. */
    val minCellHeight: Dp = 48.dp,
    /**
     * #989 — how a cell is visually delimited. A candidate PRODUCT option (XaTriX wants it
     * user-settable), not a debug aid: on a corpus this heterogeneous, a delimiter is what makes a
     * tiny sprite read as a target rather than as a stray pixel — the « affordance » route, as
     * opposed to upscaling the thumbnail and lying about its published size (preset « F », rejected).
     */
    val cellDecoration: SmileyPickerDecoration = SmileyPickerDecoration.NONE,
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
    /**
     * The rendered gap between cells: 0 dp for continuous separators,
     * [SmileyPickerLayoutSpec.cellSpacing] otherwise.
     */
    val cellSpacing: Dp,
    val capWidth: Dp,
    val capHeight: Dp,
)

/**
 * Column solver for the picker. It does NOT pick "as many columns as fit" anymore: it targets the
 * smallest cell that can render the dominant 70×50 perso at native size on both axes, then applies
 * the density-first column policy chosen by the maintainer.
 *
 * Why not keep `Adaptive` and be done: it never tells the cell its own resolved size. Why not
 * `Fixed(5)` either: it is right on an S10e in portrait and wrong everywhere else — in landscape or
 * on a tablet it stretches five cells across the whole width (cadrage Sol).
 *
 * The target cell width is derived from the passed [spec], not from literals: [SmileyPickerLayoutSpec.imageInset],
 * [SmileyPickerLayoutSpec.cellAspectRatio], [SmileyPickerLayoutSpec.minCellWidth] and
 * [SmileyPickerLayoutSpec.cellSpacing] are all spike and separator knobs. Hardcoding the defaults
 * here would silently make those modes lie about the geometry they are comparing.
 *
 * The nominal count uses `round`, not `floor`: this is the "density first" trade-off. Combined with
 * the floor below it guarantees five columns on current phones, but it can shrink the dominant
 * 70×50 perso by roughly 10 % below native (roughly 12 % on 360 dp, where the floor forces a fifth
 * column over a nominal four). `floor` would protect native rendering more often, at the cost of
 * four columns on a 360 dp phone. The maintainer chose the five-column density.
 *
 * The floor is based on the AVAILABLE width, in pixels: five columns once that width reaches the
 * threshold, four below it. It intentionally does not read `screenWidthDp`, because the bottom
 * sheet width cap and its padding decouple screen width from grid width.
 *
 * The historical `Adaptive(minCellWidth)` rule is still present, but only as a tactile upper bound:
 * it prevents the floor from forcing cells under [SmileyPickerLayoutSpec.minCellWidth] in narrow
 * multi-window layouts.
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
 * Separators keep the NOMINAL spacing while solving columns, then drop only the RENDER spacing to
 * 0 dp so the rules become continuous without changing the column count.
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
    val targetCellWidth = maxOf(
        DOMINANT_PERSO_WIDTH + spec.imageInset,
        (DOMINANT_PERSO_HEIGHT + spec.imageInset) * spec.cellAspectRatio,
    )
    val targetPx = targetCellWidth.roundToPx()
    val nominal = ((availablePx + spacingPx).toFloat() / (targetPx + spacingPx)).roundToInt()
    val minColumns = if (availablePx >= FIVE_COLUMN_MIN_AVAILABLE_WIDTH.roundToPx()) 5 else 4
    val maxColumns = ((availablePx + spacingPx) / (minCellPx + spacingPx)).coerceAtLeast(1)
    val columns = maxOf(nominal, minColumns).coerceAtMost(maxColumns).coerceAtLeast(1)
    val renderSpacing = if (spec.cellDecoration == SmileyPickerDecoration.SEPARATORS) 0.dp else spec.cellSpacing
    val renderSpacingPx = renderSpacing.roundToPx()
    val cellWidthPx = (availablePx - renderSpacingPx * (columns - 1)) / columns
    val cellWidth = cellWidthPx.toDp()
    val cellHeight = maxOf(cellWidth / spec.cellAspectRatio, spec.minCellHeight)
    SmileyGridGeometry(
        columns = columns,
        cellWidth = cellWidth,
        cellHeight = cellHeight,
        cellSpacing = renderSpacing,
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
 * Rounded to whole dp like the posts' policy. NB : l'iso-comportement avec le `smileyCellImageSize`
 * d'avant l'extraction a été ABANDONNÉ au follow-up #989 — c'était l'objet même du chantier. Le
 * format dominant 70×50 rend désormais 61×44 là où l'ancien calcul donnait 44×31. Ce que le test
 * pinne, c'est le picker post-follow-up (`SmileyCellImageSizeTest`), plus l'égalité avec le legacy.
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
