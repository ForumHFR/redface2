package fr.forumhfr.redface2.core.ui.post

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #224 (option E) — pure unit coverage for [imageDisplayBox], the relative width cap applied to inline
 * `[img]`. The base bucket is 240×180 (4:3); the renderer feeds it ≈0.9× the content width and the box
 * must shrink preserving the aspect, never overflow a narrow quote line.
 */
class InlineImageDisplayBoxTest {

    private val baseWidth = PostMediaDisplayPolicy.inlineImage.placeholderWidth.value
    private val baseHeight = PostMediaDisplayPolicy.inlineImage.placeholderHeight.value

    @Test
    fun `wide container keeps the full 240x180 bucket`() {
        val box = imageDisplayBox(maxWidthSp = 400)
        assertEquals(baseWidth, box.placeholderWidth.value, TOLERANCE)
        assertEquals(baseHeight, box.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `cap equal to the bucket width is a no-op`() {
        val box = imageDisplayBox(maxWidthSp = baseWidth.toInt())
        assertEquals(baseWidth, box.placeholderWidth.value, TOLERANCE)
        assertEquals(baseHeight, box.placeholderHeight.value, TOLERANCE)
    }

    @Test
    fun `narrow container caps width and preserves the 4_3 aspect`() {
        val box = imageDisplayBox(maxWidthSp = 180)
        assertEquals(180f, box.placeholderWidth.value, TOLERANCE)
        // 4:3 → height = 180 * (180 / 240) = 135
        assertEquals(135f, box.placeholderHeight.value, TOLERANCE)
        assertEquals(
            "aspect must stay 4:3",
            baseWidth / baseHeight,
            box.placeholderWidth.value / box.placeholderHeight.value,
            ASPECT_TOLERANCE,
        )
    }

    private companion object {
        const val TOLERANCE = 0.5f
        const val ASPECT_TOLERANCE = 0.02f
    }
}
