package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.SmileyPickerDecoration
import fr.forumhfr.redface2.core.model.EditorSmileySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #816 (thibw) — the picker mirrors HFR's real scale contrast : builtin sprites near-native
 * (small), perso smileys getting most of the cell. One uniform size cannot do both — this pins
 * the per-source contract.
 *
 * #871 (thibw) — a measured perso follows the posts' no-upscale + cap policy (#175) : native
 * size at the forum scale (1 px ≈ 1 dp) when it fits the cell, scaled down when it does not,
 * NEVER stretched past native (the 0.26.3 "small wiki smileys are zoomed and blurry" report).
 *
 * #989 — the sizing moved behind a [SmileyPickerLayoutSpec] so the device spike can drive the real
 * grid. The first group is the REGRESSION GUARD of that extraction: with the shipped spec on an
 * S10e-width grid the numbers must be identical to the pre-#989 `smileyCellImageSize`.
 *
 * Everything is asserted at an explicit [Density] because the solver works in physical pixels, like
 * `GridCells.Adaptive` itself — see `borderline width under density 2 stays at five columns`, the
 * case a dp-space solver got wrong (gate Sol).
 */
class SmileyCellImageSizeTest {

    /** The S10e is exactly xxhdpi. */
    private val s10e = Density(3f)

    /** S10e portrait with the SHIPPED spec (preset « E » since #989): 360 dp − 2 × 8 dp of padding. */
    private val shippedGeometry = smileyGridGeometry(344.dp, s10e)

    /**
     * The pre-#989 geometry, kept explicit so the historical numbers stay documented and the switch
     * to « E » is provably a deliberate change of DEFAULTS and not a change of the sizing rule.
     */
    private val preE = SmileyPickerLayoutSpec(
        minCellWidth = 48.dp,
        cellAspectRatio = 1f,
        gridPadding = 16.dp,
        cellSpacing = 8.dp,
    )

    /** Historical helper: the pre-#989 spec, on which the #816/#871 assertions below were written. */
    private fun size(
        source: EditorSmileySource,
        measuredPx: IntSize?,
        spec: SmileyPickerLayoutSpec = preE,
    ) = pickerSmileyImageSize(source, measuredPx, smileyGridGeometry(328.dp, s10e, spec), spec)

    /** Compact margins + a 56 dp minimum — the geometry of the #989 candidates. */
    private val compact = SmileyPickerLayoutSpec(
        minCellWidth = 56.dp,
        gridPadding = 8.dp,
        cellSpacing = 4.dp,
    )

    /** 360 − 2 × 8 dp of trimmed padding. */
    private val compactWidth = 344.dp

    // --- Iso-behaviour with the shipped spec (pre-#989 values, unchanged) ---

    @Test
    fun `shipped spec solves to five landscape cells — preset E (#989)`() {
        assertEquals(5, shippedGeometry.columns)
        assertEquals(65.33f, shippedGeometry.cellWidth.value, 0.02f)
        // Floored at the Material touch minimum: 65.33 / 1.4 = 46.7 dp would be under it.
        assertEquals(48.dp, shippedGeometry.cellHeight)
        assertEquals(61.33f, shippedGeometry.capWidth.value, 0.02f)
        assertEquals(44.dp, shippedGeometry.capHeight)
    }

    @Test
    fun `the shipped cap saturates both axes on the dominant 70x50 perso (#989)`() {
        // The whole point of the landscape cell: at this ratio the dominant format is limited by
        // BOTH axes at once, so no room is wasted. 44×31 before, 61×44 now — surface doubled.
        assertEquals(
            DpSize(61.dp, 44.dp),
            pickerSmileyImageSize(EditorSmileySource.WIKI, IntSize(70, 50), shippedGeometry),
        )
    }

    @Test
    fun `no-upscale survives the new defaults — a small sprite keeps its native size`() {
        // Preset « F » (ceiling 1.5) was REJECTED: a non-uniform factor makes the picker promise a
        // size the post will not honour (#1022). The shipped ceiling stays 1.
        assertEquals(1f, SmileyPickerLayoutSpec.Current.persoScaleCeiling)
        assertEquals(
            DpSize(39.dp, 15.dp),
            pickerSmileyImageSize(EditorSmileySource.WIKI, IntSize(39, 15), shippedGeometry),
        )
    }

    @Test
    fun `no delimiter is drawn by default (#989)`() {
        assertEquals(SmileyPickerDecoration.NONE, SmileyPickerLayoutSpec.Current.cellDecoration)
    }

    @Test
    fun `the pre-#989 geometry is unchanged when its spec is requested explicitly`() {
        // Guards the extraction itself: the SIZING RULE did not change, only the defaults did.
        val geometry = smileyGridGeometry(328.dp, s10e, preE)
        assertEquals(6, geometry.columns)
        assertEquals(48.dp, geometry.cellWidth)
        assertEquals(48.dp, geometry.cellHeight)
        assertEquals(44.dp, geometry.capWidth)
        assertEquals(44.dp, geometry.capHeight)
        // The historical numbers of the dominant perso, for the record.
        assertEquals(
            DpSize(44.dp, 31.dp),
            pickerSmileyImageSize(EditorSmileySource.WIKI, IntSize(70, 50), geometry, preE),
        )
    }

    @Test
    fun `builtin sprites render near their native scale`() {
        assertEquals(DpSize(20.dp, 20.dp), size(EditorSmileySource.BUILTIN, measuredPx = null))
    }

    @Test
    fun `unmeasured perso falls back to filling most of the cell`() {
        assertEquals(DpSize(44.dp, 44.dp), size(EditorSmileySource.WIKI, measuredPx = null))
    }

    @Test
    fun `small measured perso keeps its native size — NO upscale (#871)`() {
        assertEquals(DpSize(28.dp, 28.dp), size(EditorSmileySource.WIKI, IntSize(28, 28)))
        assertEquals(DpSize(15.dp, 15.dp), size(EditorSmileySource.WIKI, IntSize(15, 15)))
    }

    @Test
    fun `perso exactly at the cap passes through untouched`() {
        assertEquals(DpSize(44.dp, 44.dp), size(EditorSmileySource.WIKI, IntSize(44, 44)))
    }

    @Test
    fun `oversized perso is capped down to the cell, aspect ratio preserved`() {
        // Dominant 70×50 corpus size : scale 44/70 ≈ 0.629 → 44×31.
        assertEquals(DpSize(44.dp, 31.dp), size(EditorSmileySource.WIKI, IntSize(70, 50)))
    }

    @Test
    fun `degenerate measurement falls back to the cell-filling square`() {
        assertEquals(DpSize(44.dp, 44.dp), size(EditorSmileySource.WIKI, IntSize(0, 50)))
    }

    @Test
    fun `builtin ignores any measurement (never measured in production)`() {
        assertEquals(DpSize(20.dp, 20.dp), size(EditorSmileySource.BUILTIN, IntSize(70, 50)))
    }

    // --- The solver matches GridCells.Adaptive, in physical pixels ---

    @Test
    fun `borderline width under density 2 stays at five columns`() {
        // Gate Sol, bloquant : a dp-space solver (335.5 / 56 = 5.99, nudged over by an epsilon)
        // answered 6 columns of 47.92 dp here — under the touch minimum — where Adaptive, which
        // divides Ints in px, answers 5. (655 + 16) / (96 + 16) = 5 in integer arithmetic.
        val geometry = smileyGridGeometry(327.5.dp, Density(2f))
        assertEquals(5, geometry.columns)
        assertTrue(
            "a solved cell is never narrower than minCellWidth",
            geometry.cellWidth >= SmileyPickerLayoutSpec.Current.minCellWidth,
        )
    }

    @Test
    fun `a solved cell is never narrower than the minimum, across densities and widths`() {
        val spec = SmileyPickerLayoutSpec.Current
        for (densityValue in listOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f, 4f)) {
            for (widthDp in 200..1000 step 7) {
                val geometry = smileyGridGeometry(widthDp.dp, Density(densityValue), spec)
                assertTrue(
                    "density $densityValue, width $widthDp: cell ${geometry.cellWidth} < 48.dp",
                    // one pixel of slack: the cell width is the px division converted back to dp
                    geometry.cellWidth.value >= spec.minCellWidth.value - 1f / densityValue,
                )
            }
        }
    }

    // --- #989 levers ---

    @Test
    fun `compact margins alone keep six columns and widen the cell`() {
        // Preset « C » of the spike: trimmed margins on the pre-#989 48 dp minimum.
        val spec = preE.copy(gridPadding = 8.dp, cellSpacing = 4.dp)
        val geometry = smileyGridGeometry(compactWidth, s10e, spec)
        assertEquals(6, geometry.columns)
        assertEquals(54.dp, geometry.cellWidth)
        assertEquals(50.dp, geometry.capWidth)
    }

    @Test
    fun `a 56 dp minimum solves to five columns — responsive, unlike a hardcoded Fixed(5)`() {
        assertEquals(5, smileyGridGeometry(compactWidth, s10e, compact).columns)
        // Landscape must NOT stay at five stretched cells. 744 dp is the naive S10e landscape width;
        // on the real device the system insets bring it down to ~690 dp, which solves to 11.
        assertEquals(12, smileyGridGeometry(744.dp, s10e, compact).columns)
        assertEquals(11, smileyGridGeometry(690.dp, s10e, compact).columns)
    }

    @Test
    fun `a landscape cell never drops below the Material touch minimum`() {
        val spec = compact.copy(cellAspectRatio = 70f / 50f)
        val geometry = smileyGridGeometry(compactWidth, s10e, spec)
        assertEquals(5, geometry.columns)
        assertEquals(65.33f, geometry.cellWidth.value, 0.02f)
        // 65.33 / 1.4 = 46.7 dp, floored back up to the 48 dp minimum.
        assertEquals(48.dp, geometry.cellHeight)
    }

    @Test
    fun `the dominant 70x50 perso saturates both axes in a landscape cell`() {
        val spec = compact.copy(cellAspectRatio = 70f / 50f)
        val geometry = smileyGridGeometry(compactWidth, s10e, spec)
        // cap 61.33 × 44 : scale = min(61.33/70, 44/50) = 0.876 — both axes within a pixel of full.
        val displayed = pickerSmileyImageSize(EditorSmileySource.WIKI, IntSize(70, 50), geometry, spec)
        assertEquals(DpSize(61.dp, 44.dp), displayed)
    }

    @Test
    fun `a ceiling above 1 lifts the tiny sprites the no-upscale rule leaves illegible`() {
        val spec = compact.copy(cellAspectRatio = 70f / 50f, persoScaleCeiling = 1.5f)
        val geometry = smileyGridGeometry(compactWidth, s10e, spec)
        // `[:rofl]` is 39×15 native — the flattest common perso.
        assertEquals(
            DpSize(59.dp, 23.dp),
            pickerSmileyImageSize(EditorSmileySource.WIKI, IntSize(39, 15), geometry, spec),
        )
        // A 15×15 mini-sprite grows to 23 dp instead of staying lost in a 65 dp cell.
        assertEquals(
            DpSize(23.dp, 23.dp),
            pickerSmileyImageSize(EditorSmileySource.WIKI, IntSize(15, 15), geometry, spec),
        )
    }

    @Test
    fun `the ceiling never lets a perso overflow its cap`() {
        val spec = preE.copy(persoScaleCeiling = 4f)
        // Cap 44 wins over a ×4 ceiling: 15 × 4 = 60 would overflow the 48 dp cell.
        assertEquals(DpSize(44.dp, 44.dp), size(EditorSmileySource.WIKI, IntSize(15, 15), spec))
    }

    @Test
    fun `the upper-bound preset lets the dominant perso reach its native size`() {
        val spec = compact.copy(minCellWidth = 72.dp, cellAspectRatio = 70f / 50f)
        val geometry = smileyGridGeometry(compactWidth, s10e, spec)
        assertEquals(4, geometry.columns)
        assertEquals(83.dp, geometry.cellWidth)
        // cap 79 × 55.3 exceeds 70×50, so no-upscale keeps it exactly native.
        assertEquals(
            DpSize(70.dp, 50.dp),
            pickerSmileyImageSize(EditorSmileySource.WIKI, IntSize(70, 50), geometry, spec),
        )
    }
}
