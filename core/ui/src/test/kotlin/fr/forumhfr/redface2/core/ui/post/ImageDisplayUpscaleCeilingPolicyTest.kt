package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #973 (contrat images §8 [AMENDEMENT-v1.5-2]) — the `mEffectif` ceiling of the §3 equation:
 *
 *   scale = min(mEffectif, maxWidthPx/wNatif, maxHeightPx/hNatif)
 *
 * The ceiling only REPLACES the no-upscale `1` term; everything else of §3 is untouched (single
 * scale factor, width rounds, height derives from the ROUNDED width). ELIGIBILITY (block content
 * media + probe MIME `image/gif`) is decided by the RENDERER from the atomic cache metadata — the
 * policy stays a pure equation whose default ceiling (1f) is byte-identical to v1.5, so every
 * pre-#973 call site is untouched by construction. The DECODE (§7) is fed the already-multiplied
 * displayed width and its native clamp is terminal: the factor applies ONCE, before decodeSizePx.
 */
class ImageDisplayUpscaleCeilingPolicyTest {

    /** Representative sweep: small sprites, photos, portrait, at-cap, beyond-cap, degenerate. */
    private val nativeGrid = listOf(
        IntSize(16, 16),
        IntSize(80, 60),
        IntSize(333, 250),
        IntSize(400, 300),
        IntSize(800, 600),
        IntSize(999, 1000),
        IntSize(1600, 900),
        IntSize(2000, 1500),
        IntSize(500, 3000),
    )

    private val capsGrid = listOf(
        1026 to 1200,
        912 to 1374,
        100_000 to 100_000,
        598 to 350,
        0 to 350,
    )

    @Test
    fun `the default ceiling is byte-identical to the v15 equation - S changes nothing`() {
        // S (×1,0) ≡ v1.5: explicit 1f must reproduce the default-call result on the whole sweep…
        nativeGrid.forEach { native ->
            capsGrid.forEach { (maxW, maxH) ->
                assertEquals(
                    "S must be byte-identical to v1.5 for $native under ($maxW, $maxH)",
                    imageDisplaySizePx(native, maxW, maxH),
                    imageDisplaySizePx(native, maxW, maxH, scaleCeiling = 1.0f),
                )
            }
        }
        // …and the three v1.5 anchor values (ImageDisplaySizePolicyTest) hold verbatim under 1f.
        assertEquals(IntSize(250, 250), imageDisplaySizePx(IntSize(250, 250), 998, 5000, 1.0f))
        assertEquals(IntSize(998, 749), imageDisplaySizePx(IntSize(4000, 3000), 998, 100_000, 1.0f))
        assertEquals(IntSize(500, 501), imageDisplaySizePx(IntSize(999, 1000), 100_000, 500, 1.0f))
    }

    @Test
    fun `M enlarges a small native with preserved ratio - landscape and portrait`() {
        // Landscape 400×300 ×1,5 under generous caps → 600, h derived = round(600×300/400) = 450.
        assertEquals(
            IntSize(600, 450),
            imageDisplaySizePx(IntSize(400, 300), 100_000, 100_000, scaleCeiling = 1.5f),
        )
        // Portrait 300×400 ×1,5 → 450, h derived = round(450×400/300) = 600 (ratio preserved).
        assertEquals(
            IntSize(450, 600),
            imageDisplaySizePx(IntSize(300, 400), 100_000, 100_000, scaleCeiling = 1.5f),
        )
    }

    @Test
    fun `L enlarges a small native by two and a half`() {
        assertEquals(
            IntSize(1000, 750),
            imageDisplaySizePx(IntSize(400, 300), 100_000, 100_000, scaleCeiling = 2.5f),
        )
    }

    @Test
    fun `the width cap re-clamps a push beyond it`() {
        // 800×600 ×1,5 would reach 1200 px > cap 1026: scale = min(1.5, 1026/800) = 1.2825 →
        // w = 1026, h = round(1026×600/800) = round(769.5) = 770. The hard cap always wins (§8).
        assertEquals(
            IntSize(1026, 770),
            imageDisplaySizePx(IntSize(800, 600), 1026, 100_000, scaleCeiling = 1.5f),
        )
    }

    @Test
    fun `the height cap re-clamps a push beyond it`() {
        // 600×500 ×2,5 would reach h = 1250 px > cap 600: scale = min(2.5, …, 600/500) = 1.2 →
        // w = 720, h = round(720×500/600) = 600.
        assertEquals(
            IntSize(720, 600),
            imageDisplaySizePx(IntSize(600, 500), 100_000, 600, scaleCeiling = 2.5f),
        )
    }

    @Test
    fun `the height still derives from the rounded width under the ceiling`() {
        // §3 letter under mEffectif: 333×250 ×1,5 → w = round(499.5) = 500,
        // h = round(500×250/333) = round(375.375) = 375 — never an independent rounding.
        assertEquals(
            IntSize(500, 375),
            imageDisplaySizePx(IntSize(333, 250), 100_000, 100_000, scaleCeiling = 1.5f),
        )
    }

    @Test
    fun `a native dimension at its cap keeps scale at most 1`() {
        // §8: « dès qu'une dimension native atteint son cap, scale ≤ 1 » — at the cap the result
        // is the native size, m never pushes past…
        assertEquals(
            IntSize(1026, 770),
            imageDisplaySizePx(IntSize(1026, 770), 1026, 100_000, scaleCeiling = 1.5f),
        )
        // …and beyond the cap the eligible result equals the strict v1.5 downscale (scale < 1).
        assertEquals(
            imageDisplaySizePx(IntSize(2000, 1500), 1026, 100_000),
            imageDisplaySizePx(IntSize(2000, 1500), 1026, 100_000, scaleCeiling = 2.5f),
        )
    }

    @Test
    fun `profiles are monotonic - S at most M at most L on both axes`() {
        nativeGrid.forEach { native ->
            capsGrid.forEach { (maxW, maxH) ->
                val s = imageDisplaySizePx(native, maxW, maxH, scaleCeiling = 1.0f)
                val m = imageDisplaySizePx(native, maxW, maxH, scaleCeiling = 1.5f)
                val l = imageDisplaySizePx(native, maxW, maxH, scaleCeiling = 2.5f)
                val label = "$native under ($maxW, $maxH)"
                assertTrue("width S ≤ M ≤ L for $label", s.width <= m.width && m.width <= l.width)
                assertTrue("height S ≤ M ≤ L for $label", s.height <= m.height && m.height <= l.height)
            }
        }
    }

    @Test
    fun `a non-eligible media never exceeds its native size`() {
        // Invariant no-upscale v1.5 (default call = every non-eligible and inline path).
        nativeGrid.forEach { native ->
            capsGrid.forEach { (maxW, maxH) ->
                val out = imageDisplaySizePx(native, maxW, maxH)
                assertTrue("width ≤ native for $native under ($maxW, $maxH)", out.width <= native.width)
                assertTrue("height ≤ native for $native under ($maxW, $maxH)", out.height <= native.height)
            }
        }
    }

    @Test
    fun `an eligible media never exceeds mEffectif times its native size`() {
        // The +1 tolerance on the height is the §3 accepted derivation overshoot (the height
        // derives from the ROUNDED width, never re-clamped) — the width itself never exceeds
        // round(m × native).
        listOf(1.5f, 2.5f).forEach { m ->
            nativeGrid.forEach { native ->
                capsGrid.forEach { (maxW, maxH) ->
                    val out = imageDisplaySizePx(native, maxW, maxH, scaleCeiling = m)
                    assertTrue(
                        "width ≤ m×native for $native ×$m under ($maxW, $maxH)",
                        out.width <= (native.width * m).roundToInt(),
                    )
                    assertTrue(
                        "height ≤ m×native (+1 derivation) for $native ×$m under ($maxW, $maxH)",
                        out.height <= (native.height * m).roundToInt() + 1,
                    )
                }
            }
        }
    }

    @Test
    fun `the decode stays clamped to native when fed an upscaled displayed width - single factor`() {
        // §7 unchanged: the box is multiplied ONCE before decodeSizePx; the decode's native clamp
        // is terminal, so for m > 1 the decode stays at native and the enlargement happens at draw.
        // M: displayed 600 from a 400×300 native → decode 400×300, never 600×450…
        assertEquals(IntSize(400, 300), decodeSizePx(displayedWidthPx = 600, nativePx = IntSize(400, 300)))
        // …L: displayed 1000 → still 400×300…
        assertEquals(IntSize(400, 300), decodeSizePx(displayedWidthPx = 1000, nativePx = IntSize(400, 300)))
        // …and the decode of a profile-bound native is IDENTICAL between S and M (the request
        // remember key, derived from the decode size, has no reason to flip: no re-decode).
        assertEquals(
            decodeSizePx(displayedWidthPx = 400, nativePx = IntSize(400, 300)),
            decodeSizePx(displayedWidthPx = 600, nativePx = IntSize(400, 300)),
        )
    }

    @Test
    fun `full-width and profile levers cumulate without coupling - 2x3 matrix`() {
        // §14 by reference from §8: B (full-width posts) only moves the CONTAINER (hence the width
        // cap fImage × container), C (profile) only moves the CEILING. Each cell is the min of the
        // two terms — no cross term. Modeled at policy level: two container caps (912 px ≈ card,
        // 958 px ≈ full-width, both @d3) × S/M/L, on a profile-bound small GIF (B invisible) and a
        // cap-bound large GIF (C's push re-clamped identically for M and L).
        val small = IntSize(200, 150)
        val large = IntSize(800, 600)
        val capH = 1200
        val card = 912
        val full = 958

        // S: strict v1.5 everywhere — the container never binds these natives.
        assertEquals(IntSize(200, 150), imageDisplaySizePx(small, card, capH, 1.0f))
        assertEquals(IntSize(200, 150), imageDisplaySizePx(small, full, capH, 1.0f))
        assertEquals(IntSize(800, 600), imageDisplaySizePx(large, card, capH, 1.0f))
        assertEquals(IntSize(800, 600), imageDisplaySizePx(large, full, capH, 1.0f))

        // Small GIF: the PROFILE binds — identical across containers (B does not couple into C).
        assertEquals(IntSize(300, 225), imageDisplaySizePx(small, card, capH, 1.5f))
        assertEquals(IntSize(300, 225), imageDisplaySizePx(small, full, capH, 1.5f))
        assertEquals(IntSize(500, 375), imageDisplaySizePx(small, card, capH, 2.5f))
        assertEquals(IntSize(500, 375), imageDisplaySizePx(small, full, capH, 2.5f))

        // Large GIF: the CONTAINER cap binds — the cell follows B alone, identically for M and L
        // (C does not distort B's cap; it is re-clamped to it).
        assertEquals(IntSize(912, 684), imageDisplaySizePx(large, card, capH, 1.5f))
        assertEquals(IntSize(912, 684), imageDisplaySizePx(large, card, capH, 2.5f))
        assertEquals(IntSize(958, 719), imageDisplaySizePx(large, full, capH, 1.5f))
        assertEquals(IntSize(958, 719), imageDisplaySizePx(large, full, capH, 2.5f))
    }
}
