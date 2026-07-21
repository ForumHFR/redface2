package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #959 (Lot 3, contrat v1.5 §3) — the DEDICATED content-image sizing equation, all in PHYSICAL
 * pixels (cadrage Sol r1/r2: no nativePx ever compared to sp/dp — the hosts convert caps to px
 * BEFORE calling, and convert the result back at the Compose boundary):
 *
 *   scale    = min(1, maxWidthPx/wNative, maxHeightPx/hNative)
 *   wDisplay = round(wNative × scale)
 *   hDisplay = round(wDisplay × hNative / wNative)   // derived from the ROUNDED width
 *
 * The height is NEVER rounded independently (§3 letter — pinned by the discriminating case
 * below). No-upscale comes from the `1` term. Smileys keep [intrinsicSmileyDisplaySize]
 * strictly unchanged (§9: 240/70/0.9 untouchable) — this equation is image-only.
 */
class ImageDisplaySizePolicyTest {

    @Test
    fun `no physical upscale - a source smaller than both caps stays at native pixels`() {
        assertEquals(
            IntSize(250, 250),
            imageDisplaySizePx(nativePx = IntSize(250, 250), maxWidthPx = 998, maxHeightPx = 5000),
        )
    }

    @Test
    fun `width cap binds - width rounds and height derives from the rounded width`() {
        // 4000×3000 into maxW=998: scale=0.2495, w=round(998.0)=998, h=round(998×3000/4000)=749.
        assertEquals(
            IntSize(998, 749),
            imageDisplaySizePx(nativePx = IntSize(4000, 3000), maxWidthPx = 998, maxHeightPx = 100_000),
        )
    }

    @Test
    fun `height cap binds - the derived height comes from the rounded width not the scaled height`() {
        // THE discriminating case (§3 letter): 999×1000 into maxH=500 → scale=0.5,
        // w = round(499.5) = 500, h = round(500 × 1000/999) = round(500.5) = 501.
        // An independent rounding would return 500 — the contract derivation returns 501,
        // one px past the cap by construction (accepted by §3: the cap constrains the SCALE,
        // the height is then derived from the rounded width, never re-clamped).
        assertEquals(
            IntSize(500, 501),
            imageDisplaySizePx(nativePx = IntSize(999, 1000), maxWidthPx = 100_000, maxHeightPx = 500),
        )
    }

    @Test
    fun `both caps compete - the most binding one drives the single scale factor`() {
        // 1000×2000, maxW=800 (scale .8), maxH=500 (scale .25) → scale=.25 → 250×500.
        assertEquals(
            IntSize(250, 500),
            imageDisplaySizePx(nativePx = IntSize(1000, 2000), maxWidthPx = 800, maxHeightPx = 500),
        )
    }

    @Test
    fun `ratio is preserved through the derivation`() {
        // 1600×900 into maxW=1024: w=1024, h=round(1024×900/1600)=576 — exact 16:9.
        assertEquals(
            IntSize(1024, 576),
            imageDisplaySizePx(nativePx = IntSize(1600, 900), maxWidthPx = 1024, maxHeightPx = 100_000),
        )
    }

    @Test
    fun `degenerate scale never collapses below one pixel per axis`() {
        // 1×10000 into maxH=100: scale=0.01, w=round(0.01)=0 → derived h=round(0×10000/1)=0 —
        // both axes floor to 1 (a degenerate 1×1 slot, never a 1×10000 layout bomb).
        assertEquals(
            IntSize(1, 1),
            imageDisplaySizePx(nativePx = IntSize(1, 10_000), maxWidthPx = 100_000, maxHeightPx = 100),
        )
    }

    @Test
    fun `non-positive width cap means no width cap`() {
        // Defensive parity with imageParityDisplaySize: a zero-width container must not
        // collapse the image (only the height cap binds).
        assertEquals(
            IntSize(500, 250),
            imageDisplaySizePx(nativePx = IntSize(1000, 500), maxWidthPx = 0, maxHeightPx = 250),
        )
    }

    @Test
    fun `the dedicated image width fraction is 95 percent and the cold alias is locked on it`() {
        // [AMENDEMENT-v1.5-1] — D1 approved: fImage = 0.95, DISTINCT from the smiley 0.9 cap;
        // the cold-slot fraction is a locked alias (any drift between the two breaks here).
        assertEquals(0.95f, IMAGE_RELATIVE_MAX_WIDTH_FRACTION)
        assertEquals(IMAGE_RELATIVE_MAX_WIDTH_FRACTION, COLD_BLOCK_WIDTH_FRACTION)
        assertEquals(0.9f, SMILEY_RELATIVE_MAX_WIDTH_FRACTION)
    }
}
