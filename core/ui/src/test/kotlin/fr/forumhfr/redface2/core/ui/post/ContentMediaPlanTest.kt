package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.6-10: caps belong to the occurrence; a ready request never changes target. */
class ContentMediaPlanTest {
    private val caps = ContentMediaConstraints(350, 900.5f, 3f)

    @Test
    fun `known geometry allows the painter immediately even while probe is pending`() {
        assertEquals(
            decodeSizePx(350, IntSize(800, 600)),
            contentMediaDecodeTarget(IntSize(800, 600), false, PostMediaDiskCachePolicy.ENABLED, caps),
        )
    }

    @Test
    fun `cold disk-backed media withholds the painter until probe termination`() {
        assertNull(contentMediaDecodeTarget(null, false, PostMediaDiskCachePolicy.ENABLED, caps))
        assertEquals(
            IntSize(512, 1024),
            contentMediaDecodeTarget(null, true, PostMediaDiskCachePolicy.ENABLED, caps),
        )
    }

    @Test
    fun `private media immediately uses G2 without a probe`() {
        assertEquals(
            IntSize(512, 1024),
            contentMediaDecodeTarget(null, false, PostMediaDiskCachePolicy.DISABLED, caps),
        )
    }

    @Test
    fun `G2 uses independent inclusive ceil256 buckets and caps each axis at 2048`() {
        listOf(
            ContentMediaConstraints(256, 256f) to IntSize(256, 256),
            ContentMediaConstraints(257, 256.01f) to IntSize(512, 512),
            ContentMediaConstraints(1, 2000f) to IntSize(256, 2048),
            ContentMediaConstraints(2000, 1f) to IntSize(2048, 256),
            ContentMediaConstraints(Int.MAX_VALUE, Float.MAX_VALUE) to IntSize(2048, 2048),
            ContentMediaConstraints(0, 0f) to IntSize(256, 256),
        ).forEach { (constraints, expected) ->
            assertEquals(expected, g2DecodeSizePx(constraints))
        }
    }

    @Test
    fun `G2 target stays frozen when geometry arrives later`() {
        val plan = ContentMediaPlan(IntSize(512, 1024))
        plan.resolve(IntSize(80, 60))
        plan.resolve(null)
        assertEquals(IntSize(512, 1024), plan.decodeSize)
    }

    @Test
    fun `waiting plan takes exactly the first ready target`() {
        val plan = ContentMediaPlan(null)
        plan.resolve(null)
        assertNull(plan.decodeSize)
        plan.resolve(IntSize(256, 192))
        plan.resolve(IntSize(512, 384))
        assertEquals(IntSize(256, 192), plan.decodeSize)
    }
}
