package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
 * S10e-width grid the numbers must be identical to the pre-follow-up #989 picker.
 *
 * The solver now targets the dominant 70×50 perso format, floors common phones at five columns,
 * floors tiny widths at four, and keeps the former `GridCells.Adaptive(minCellWidth)` arithmetic as
 * the tactile upper bound. Everything is asserted at an explicit [Density] because those thresholds
 * are resolved in physical pixels — see `borderline width under density 2 stays at five columns`,
 * the case a dp-space solver got wrong (gate Sol).
 */
class SmileyCellImageSizeTest {

    /** The S10e is exactly xxhdpi. */
    private val s10e = Density(3f)

    /** S10e portrait with the SHIPPED spec: 360 dp − 2 × 8 dp of padding. */
    private val shippedGeometry = smileyGridGeometry(344.dp, s10e)

    /**
     * The pre-#989 geometry, kept explicit so the historical spec stays documented while the
     * follow-up deliberately changes the RULE used to solve it.
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

    // --- Iso-behaviour with the shipped spec (pre-follow-up #989 values, unchanged) ---

    @Test
    fun `shipped spec solves to five portrait cells — preset E (#989)`() {
        assertEquals(5, shippedGeometry.columns)
        assertDpClose("cell width", 65.33f, shippedGeometry.cellWidth)
        // The 360 dp calibration stays unchanged: the floor replaces the old minimum-driven result.
        assertEquals(48.dp, shippedGeometry.cellHeight)
        assertEquals(4.dp, shippedGeometry.cellSpacing)
        assertDpClose("cap width", 61.33f, shippedGeometry.capWidth)
        assertEquals(44.dp, shippedGeometry.capHeight)
    }

    @Test
    fun `the shipped cap saturates both axes on the dominant 70x50 perso (#989)`() {
        // The whole point of the landscape-ratio cell: the dominant format is limited by BOTH axes
        // at once, so no room is wasted. 44×31 before #989, 61×44 under the shipped S10e geometry.
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
    fun `the legacy spec now solves under the target policy — the follow-up changed the rule`() {
        // The extraction once guaranteed iso-behaviour when the old spec was requested explicitly.
        // That guarantee is intentionally over: the follow-up changes the solver, not just defaults.
        val geometry = smileyGridGeometry(328.dp, s10e, preE)
        assertEquals(5, geometry.columns)
        assertEquals(59.dp, geometry.cellWidth)
        assertEquals(59.dp, geometry.cellHeight)
        assertEquals(8.dp, geometry.cellSpacing)
        assertEquals(55.dp, geometry.capWidth)
        assertEquals(55.dp, geometry.capHeight)
        assertEquals(
            DpSize(55.dp, 39.dp),
            pickerSmileyImageSize(EditorSmileySource.WIKI, IntSize(70, 50), geometry, preE),
        )
    }

    @Test
    fun `target-cell matrix keeps phone portrait dense and lets wider phones reach native`() {
        data class MatrixCase(
            val label: String,
            val availableWidth: Dp,
            val density: Float,
            val columns: Int,
            val cellWidth: Float,
            val cellHeight: Float,
            val capWidth: Float,
            val capHeight: Float,
            val dominantRendered: DpSize,
        )

        listOf(
            MatrixCase("320 dp screen", 304.dp, 2f, 4, 73f, 52.14f, 69f, 48.14f, DpSize(67.dp, 48.dp)),
            MatrixCase("360 dp S10e", 344.dp, 3f, 5, 65.33f, 48f, 61.33f, 44f, DpSize(61.dp, 44.dp)),
            MatrixCase("384 dp screen", 368.dp, 2.8125f, 5, 70.40f, 50.29f, 66.40f, 46.29f, DpSize(65.dp, 46.dp)),
            MatrixCase("393 dp Pixel", 377.dp, 2.75f, 5, 72f, 51.43f, 68f, 47.43f, DpSize(66.dp, 47.dp)),
            MatrixCase("411 dp screen", 395.dp, 2.625f, 5, 75.43f, 53.88f, 71.43f, 49.88f, DpSize(70.dp, 50.dp)),
            MatrixCase("412 dp screen", 396.dp, 3.5f, 5, 76f, 54.29f, 72f, 50.29f, DpSize(70.dp, 50.dp)),
            MatrixCase("430 dp screen", 414.dp, 3.5f, 5, 79.43f, 56.73f, 75.43f, 52.73f, DpSize(70.dp, 50.dp)),
            MatrixCase("760 dp S10e landscape", 744.dp, 3f, 9, 79f, 56.43f, 75f, 52.43f, DpSize(70.dp, 50.dp)),
        ).forEach { case ->
            val geometry = smileyGridGeometry(case.availableWidth, Density(case.density))
            val rendered = pickerSmileyImageSize(EditorSmileySource.WIKI, IntSize(70, 50), geometry)
            assertEquals("${case.label}: columns", case.columns, geometry.columns)
            assertDpClose("${case.label}: cell width", case.cellWidth, geometry.cellWidth)
            assertDpClose("${case.label}: cell height", case.cellHeight, geometry.cellHeight)
            assertDpClose("${case.label}: cap width", case.capWidth, geometry.capWidth)
            assertDpClose("${case.label}: cap height", case.capHeight, geometry.capHeight)
            assertEquals("${case.label}: dominant 70x50 rendering", case.dominantRendered, rendered)
        }
    }

    @Test
    fun `sheet cap width reaches ten columns without going unbounded`() {
        // Tablet windows wider than 856 dp hit the 840 dp sheet cap, leaving 824 dp for the grid.
        val geometry = smileyGridGeometry(824.dp, s10e)
        assertEquals(10, geometry.columns)
        assertDpClose("cell width", 78.67f, geometry.cellWidth)
        assertDpClose("cell height", 56.19f, geometry.cellHeight)
        assertEquals(
            DpSize(70.dp, 50.dp),
            pickerSmileyImageSize(EditorSmileySource.WIKI, IntSize(70, 50), geometry),
        )
    }

    @Test
    fun `five-column floor switches on at the available-width threshold in pixels`() {
        // The threshold is the AVAILABLE grid width, not screenWidthDp: 323 dp at density 2 is
        // 646 px and stays at four columns; 324 dp is 648 px and enables the five-column floor.
        val below = smileyGridGeometry(323.dp, Density(2f))
        val at = smileyGridGeometry(324.dp, Density(2f))
        assertEquals(4, below.columns)
        assertEquals(77.5f, below.cellWidth.value, 0.02f)
        assertEquals(5, at.columns)
        assertEquals(61.5f, at.cellWidth.value, 0.02f)
    }

    @Test
    fun `tactile ceiling wins over the floor in a narrow multi-window grid`() {
        val geometry = smileyGridGeometry(200.dp, Density(2f))
        assertEquals(3, geometry.columns)
        assertEquals(64.dp, geometry.cellWidth)
        assertTrue("cell width stays at or above the tactile minimum", geometry.cellWidth.value >= 56f)
    }

    @Test
    fun `the separators mode keeps the column count while dropping only render spacing (#989)`() {
        val plain = smileyGridGeometry(344.dp, s10e, SmileyPickerLayoutSpec.Current)
        val ruled = smileyGridGeometry(
            344.dp,
            s10e,
            SmileyPickerLayoutSpec.Current.copy(cellDecoration = SmileyPickerDecoration.SEPARATORS),
        )
        assertEquals(5, plain.columns)
        assertEquals("same column count in both modes", plain.columns, ruled.columns)
        assertEquals(4.dp, plain.cellSpacing)
        assertEquals(0.dp, ruled.cellSpacing)
        assertDpClose("plain cell width", 65.33f, plain.cellWidth)
        assertDpClose("ruled cell width", 68.67f, ruled.cellWidth, tolerance = 0.05f)

        val narrowPlain = smileyGridGeometry(304.dp, Density(2f), SmileyPickerLayoutSpec.Current)
        val narrowRuled = smileyGridGeometry(
            304.dp,
            Density(2f),
            SmileyPickerLayoutSpec.Current.copy(cellDecoration = SmileyPickerDecoration.SEPARATORS),
        )
        assertEquals("same narrow column count in both modes", narrowPlain.columns, narrowRuled.columns)
        assertEquals(4, narrowRuled.columns)
        assertEquals(73.dp, narrowPlain.cellWidth)
        assertEquals(76.dp, narrowRuled.cellWidth)
    }

    @Test
    fun `target cell width is derived from the requested spec`() {
        assertEquals(9, smileyGridGeometry(744.dp, s10e, SmileyPickerLayoutSpec.Current).columns)
        assertEquals(
            10,
            smileyGridGeometry(744.dp, s10e, SmileyPickerLayoutSpec.Current.copy(cellAspectRatio = 1f)).columns,
        )
        assertEquals(
            8,
            smileyGridGeometry(744.dp, s10e, SmileyPickerLayoutSpec.Current.copy(imageInset = 12.dp)).columns,
        )
    }

    @Test
    fun `builtin sprites render near their native scale`() {
        assertEquals(DpSize(20.dp, 20.dp), size(EditorSmileySource.BUILTIN, measuredPx = null))
    }

    @Test
    fun `unmeasured perso falls back to filling most of the cell`() {
        assertEquals(DpSize(55.dp, 55.dp), size(EditorSmileySource.WIKI, measuredPx = null))
    }

    @Test
    fun `small measured perso keeps its native size — NO upscale (#871)`() {
        assertEquals(DpSize(28.dp, 28.dp), size(EditorSmileySource.WIKI, IntSize(28, 28)))
        assertEquals(DpSize(15.dp, 15.dp), size(EditorSmileySource.WIKI, IntSize(15, 15)))
    }

    @Test
    fun `perso exactly at the cap passes through untouched`() {
        assertEquals(DpSize(55.dp, 55.dp), size(EditorSmileySource.WIKI, IntSize(55, 55)))
    }

    @Test
    fun `oversized perso is capped down to the cell, aspect ratio preserved`() {
        // Dominant 70×50 corpus size under the legacy spec: scale 55/70 ≈ 0.786 → 55×39.
        assertEquals(DpSize(55.dp, 39.dp), size(EditorSmileySource.WIKI, IntSize(70, 50)))
    }

    @Test
    fun `degenerate measurement falls back to the cell-filling square`() {
        assertEquals(DpSize(55.dp, 55.dp), size(EditorSmileySource.WIKI, IntSize(0, 50)))
    }

    @Test
    fun `builtin ignores any measurement (never measured in production)`() {
        assertEquals(DpSize(20.dp, 20.dp), size(EditorSmileySource.BUILTIN, IntSize(70, 50)))
    }

    // --- Target solver: pixel arithmetic, floor, and tactile ceiling ---

    @Test
    fun `borderline width under density 2 stays at five columns`() {
        // Gate Sol, bloquant: the tactile ceiling is still computed in physical pixels, so the
        // borderline case that a dp-space Adaptive clone got wrong remains protected.
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
                    // One pixel of slack: the cell width is the px division converted back to dp.
                    geometry.cellWidth.value >= spec.minCellWidth.value - 1f / densityValue,
                )
            }
        }
    }

    // --- #989 levers ---

    @Test
    fun `trimmed margins now land on the five-column target too`() {
        // Preset « C » of the spike: trimmed margins on the pre-#989 48 dp minimum.
        val spec = preE.copy(gridPadding = 8.dp, cellSpacing = 4.dp)
        val geometry = smileyGridGeometry(compactWidth, s10e, spec)
        assertEquals(5, geometry.columns)
        assertDpClose("cell width", 65.33f, geometry.cellWidth)
        assertDpClose("cap width", 61.33f, geometry.capWidth)
    }

    @Test
    fun `the target policy stays responsive in landscape without chasing every possible column`() {
        assertEquals(5, smileyGridGeometry(compactWidth, s10e, compact).columns)
        // Landscape remains responsive (more than five columns) but is now governed by the 70×50
        // target cell, not by the old minimum-cell race toward 11-12 columns.
        assertEquals(9, smileyGridGeometry(744.dp, s10e, compact).columns)
        assertEquals(9, smileyGridGeometry(690.dp, s10e, compact).columns)
    }

    @Test
    fun `a landscape cell never drops below the Material touch minimum`() {
        val spec = compact.copy(cellAspectRatio = 70f / 50f)
        val geometry = smileyGridGeometry(compactWidth, s10e, spec)
        assertEquals(5, geometry.columns)
        assertDpClose("cell width", 65.33f, geometry.cellWidth)
        // 65.33 / 1.4 = 46.7 dp, floored back up to the 48 dp minimum.
        assertEquals(48.dp, geometry.cellHeight)
    }

    @Test
    fun `the dominant 70x50 perso saturates both axes in a landscape cell`() {
        val spec = compact.copy(cellAspectRatio = 70f / 50f)
        val geometry = smileyGridGeometry(compactWidth, s10e, spec)
        // Cap 61.33 × 44: scale = min(61.33/70, 44/50) = 0.876 — both axes within a pixel of full.
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
        // Cap 55 wins over a ×4 ceiling: 15 × 4 = 60 would overflow the solved cell.
        assertEquals(DpSize(55.dp, 55.dp), size(EditorSmileySource.WIKI, IntSize(15, 15), spec))
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

    private fun assertDpClose(message: String, expected: Float, actual: Dp, tolerance: Float = 0.02f) {
        assertEquals(message, expected, actual.value, tolerance)
    }
}
