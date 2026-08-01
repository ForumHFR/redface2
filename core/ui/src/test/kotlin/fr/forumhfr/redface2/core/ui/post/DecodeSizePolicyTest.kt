package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #959 (Lot 3, contrat v1.5 §7) — the density-aware DECODE size calculator, common to the inline
 * and block paths (replaces the flat 1024 bound). Exact order (Sol r1, Q4):
 *
 *  1. start from the §3 displayed box in physical px (the host ceils before calling — Int in);
 *  2. extend the WIDTH to the next INCLUSIVE 256 bucket (512 → 512, 513 → 768);
 *  3. if either the bucketed width or its derived height exceeds 2048 px or the native size,
 *     shrink by ONE COMMON factor until both fit;
 *  4. final rounding is CAP-SAFE (floor on the width): never past 2048 nor the native pair;
 *  5. the height derives from the FINAL width by the native ratio (clamped to the caps if its
 *     own rounding would overshoot by one — this is a decode size, the caps win).
 */
class DecodeSizePolicyTest {

    @Test
    fun `a small source never decodes past its native size`() {
        // displayed 60 px, native 100×80: the 256 bucket exceeds the native width → common
        // factor brings it back to the exact native pair (decoding a 100 px source at 256 would
        // be a pure waste).
        assertEquals(
            IntSize(100, 80),
            decodeSizePx(displayedWidthPx = 60, nativePx = IntSize(100, 80)),
        )
    }

    @Test
    fun `the width extends to the next 256 bucket and the height derives from it`() {
        // displayed 500 px of a 4000×3000: next 256 multiple = 512, height 512×3000/4000 = 384;
        // displayed 513 px crosses into the 768 bucket.
        assertEquals(
            IntSize(512, 384),
            decodeSizePx(displayedWidthPx = 500, nativePx = IntSize(4000, 3000)),
        )
        assertEquals(
            IntSize(768, 576),
            decodeSizePx(displayedWidthPx = 513, nativePx = IntSize(4000, 3000)),
        )
    }

    @Test
    fun `an exact bucket multiple stays on its bucket - the upper bucket is inclusive`() {
        // displayed 512 px → bucket 512 (NOT 768): "étendre au bucket de 256 supérieur" is
        // inclusive of an exact multiple (Sol r1: bucket supérieur INCLUSIF).
        assertEquals(
            IntSize(512, 384),
            decodeSizePx(displayedWidthPx = 512, nativePx = IntSize(4000, 3000)),
        )
    }

    @Test
    fun `the 2048 width cap shrinks by one common factor - cap-safe rounding`() {
        // displayed 2500 of an 8000×2000: bucket 2560 > 2048 → factor 0.8 → 2048 wide,
        // height derived from the final width = round(2048×2000/8000) = 512.
        assertEquals(
            IntSize(2048, 512),
            decodeSizePx(displayedWidthPx = 2500, nativePx = IntSize(8000, 2000)),
        )
    }

    @Test
    fun `a derived height past 2048 drives the common factor`() {
        // displayed 1000 of a 1000×4000 (1:4): bucket 1024, derived height 4096 > 2048 →
        // common factor 0.5 → 512×2048. ONE factor shrinks both axes (ratio preserved).
        assertEquals(
            IntSize(512, 2048),
            decodeSizePx(displayedWidthPx = 1000, nativePx = IntSize(1000, 4000)),
        )
    }

    @Test
    fun `the native height also bounds the common factor`() {
        // displayed 900 of a 1200×600: bucket 1024 ≤ native width, but the derived height
        // 1024×600/1200 = 512 ≤ 600 fits too → no shrink, 1024×512.
        assertEquals(
            IntSize(1024, 512),
            decodeSizePx(displayedWidthPx = 900, nativePx = IntSize(1200, 600)),
        )
        // displayed 1100 of a 1200×600: bucket 1280 > native 1200 → factor 1200/1280 →
        // floor(1280×0.9375) = 1200 wide, height = round(1200×600/1200) = 600.
        assertEquals(
            IntSize(1200, 600),
            decodeSizePx(displayedWidthPx = 1100, nativePx = IntSize(1200, 600)),
        )
    }

    @Test
    fun `degenerate inputs floor to one pixel per axis`() {
        assertEquals(
            IntSize(1, 1),
            decodeSizePx(displayedWidthPx = 1, nativePx = IntSize(1, 1)),
        )
    }
}
