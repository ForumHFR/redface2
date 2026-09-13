package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #876 v1.6-1: one density ceiling for all content, independent of MIME and links. */
class ContentDensityPolicyTest {

    private val densities = listOf(0.75f, 1f, 2f, 2.625f, 3f, 3.5f)
    private val ceilings = listOf(1f, 1f, 2f, 2.625f, 3f, 3f)

    @Test
    fun `density ceiling has a one floor and a three ceiling`() {
        assertEquals(ceilings, densities.map(::contentUpscaleCeiling))
    }

    @Test
    fun `small content follows density up to three with the assumed dp residual above three`() {
        val widths = densities.map { density ->
            imageDisplaySizePx(IntSize(100, 60), 10_000, 10_000, contentUpscaleCeiling(density)).width
        }
        assertEquals(listOf(100, 100, 200, 263, 300, 300), widths)
        assertEquals(100f, widths[4] / densities[4], 0.01f)
        assertEquals(85.714f, widths[5] / densities[5], 0.01f)
    }

    @Test
    fun `density growth is monotone under fixed caps including extreme ratios`() {
        val natives = listOf(IntSize(80, 60), IntSize(999, 1000), IntSize(1, 10_000), IntSize(10_000, 1))
        natives.forEach { native ->
            listOf(1026 to 1487, 80 to 200, 0 to 400).forEach { (maxW, maxH) ->
                val sizes = densities.map { density ->
                    imageDisplaySizePx(native, maxW, maxH, contentUpscaleCeiling(density))
                }
                sizes.zipWithNext().forEach { (before, after) ->
                    assertTrue("width $native under $maxW/$maxH", before.width <= after.width)
                    assertTrue("height $native under $maxW/$maxH", before.height <= after.height)
                }
                sizes.forEach { size -> assertTrue(size.width >= 1 && size.height >= 1) }
            }
        }
    }

    @Test
    fun `caps and native ceiling bind both axes for exactly representable ratios`() {
        // The separate rounding test pins the historical width-derived height, including overshoot.
        listOf(IntSize(80, 80), IntSize(800, 800), IntSize(4000, 4000)).forEach { native ->
            densities.zip(ceilings).forEach { (density, ceiling) ->
                val size = imageDisplaySizePx(native, 1026, 600, contentUpscaleCeiling(density))
                assertTrue(size.width <= 1026 && size.height <= 600)
                assertTrue(size.width <= (native.width * ceiling).roundToInt())
                assertTrue(size.height <= (native.height * ceiling).roundToInt())
            }
        }
    }

    @Test
    fun `height is derived from rounded width and extreme ratios never collapse`() {
        assertEquals(IntSize(263, 158), imageDisplaySizePx(IntSize(100, 60), 10_000, 10_000, 2.625f))
        // Independent height rounding would give 263 rather than 262.
        assertEquals(IntSize(874, 262), imageDisplaySizePx(IntSize(333, 100), 10_000, 10_000, 2.625f))
        // Existing §3 semantics: a derived height can exceed the cap by one pixel.
        assertEquals(IntSize(113, 201), imageDisplaySizePx(IntSize(360, 640), 324, 200, 3f))
        assertEquals(IntSize(1, 1), imageDisplaySizePx(IntSize(1, 10_000), 100, 200, 3f))
        assertEquals(IntSize(100, 1), imageDisplaySizePx(IntSize(10_000, 1), 100, 200, 3f))
    }

    @Test
    fun `enlarged display decodes at native and the 2048 budget stays terminal`() {
        densities.forEach { density ->
            listOf(IntSize(80, 60), IntSize(400, 300)).forEach { native ->
                val display = imageDisplaySizePx(native, 10_000, 10_000, contentUpscaleCeiling(density))
                assertEquals(native, decodeSizePx(display.width, native))
            }
        }
        assertEquals(IntSize(2048, 1536), decodeSizePx(6000, IntSize(4000, 3000)))
    }

    @Test
    fun `container width stays independent of the content ceiling`() {
        val small = IntSize(200, 150)
        val large = IntSize(800, 600)
        assertEquals(IntSize(600, 450), imageDisplaySizePx(small, 912, 1487, 3f))
        assertEquals(IntSize(600, 450), imageDisplaySizePx(small, 958, 1487, 3f))
        assertEquals(IntSize(912, 684), imageDisplaySizePx(large, 912, 1487, 3f))
        assertEquals(IntSize(958, 719), imageDisplaySizePx(large, 958, 1487, 3f))
    }
}
