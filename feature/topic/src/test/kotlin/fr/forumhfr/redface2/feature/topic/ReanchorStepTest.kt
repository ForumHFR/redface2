package fr.forumhfr.redface2.feature.topic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #197 — pure JVM coverage of [reanchorStep], the per-frame state machine driving
 * `reanchorWhileMediaSettles`. The suspend loop itself (frame clock + Coil decode timing) is not
 * JVM-testable, but its decision is, so the tricky cases the #197 review raised are pinned here:
 * a tail post that can never reach `offset == 0`, staggered image growth, and the user-scroll bail
 * (the bail lives in the loop; here we pin everything else).
 */
class ReanchorStepTest {

    private val target = 5
    private val threshold = 3

    @Test
    fun `first frame resets the stable counter and never stops`() {
        val step = reanchorStep(
            current = ReanchorFrame(target, 0),
            previous = null,
            target = target,
            stableFrames = 99,
            stableThreshold = threshold,
        )
        assertTrue(step is ReanchorStep.Continue)
        step as ReanchorStep.Continue
        assertEquals(0, step.stableFrames)
        assertFalse("already at the top → no re-pin", step.repin)
    }

    @Test
    fun `holding still for the threshold stops`() {
        val frame = ReanchorFrame(target, 0)
        val step = reanchorStep(
            frame,
            previous = frame,
            target = target,
            stableFrames = threshold - 1,
            stableThreshold = threshold,
        )
        assertEquals(ReanchorStep.Stop, step)
    }

    @Test
    fun `a position change resets the stable counter and asks for a re-pin`() {
        // An image above the target decoded and grew: the target drifted from offset 0 to 60.
        val step = reanchorStep(
            current = ReanchorFrame(target, 60),
            previous = ReanchorFrame(target, 0),
            target = target,
            stableFrames = threshold - 1,
            stableThreshold = threshold,
        )
        assertTrue(step is ReanchorStep.Continue)
        step as ReanchorStep.Continue
        assertEquals(0, step.stableFrames)
        assertTrue("drifted off the top → re-pin", step.repin)
    }

    @Test
    fun `a tail post resting at a non-zero offset still settles once it stops moving`() {
        // The list cannot scroll the target all the way up. Keying the stop on stillness (not
        // offset==0) is what prevents churning the whole frame budget on no-op re-pins.
        val resting = ReanchorFrame(target, 300)
        val step = reanchorStep(
            resting,
            previous = resting,
            target = target,
            stableFrames = threshold - 1,
            stableThreshold = threshold,
        )
        assertEquals("settled at a non-zero offset = Stop", ReanchorStep.Stop, step)
    }

    @Test
    fun `a still-but-not-yet-settled tail post keeps re-pinning`() {
        val resting = ReanchorFrame(target, 300)
        val step = reanchorStep(
            resting,
            previous = resting,
            target = target,
            stableFrames = 0,
            stableThreshold = threshold,
        )
        assertTrue(step is ReanchorStep.Continue)
        step as ReanchorStep.Continue
        assertEquals(1, step.stableFrames)
        assertTrue("offset != 0 → re-pin (a harmless no-op when already at max scroll)", step.repin)
    }

    @Test
    fun `staggered image growth resets stability so it never stops between two decodes`() {
        val a = ReanchorFrame(target, 0)
        // Two still frames accrue while image A holds.
        var stable = (reanchorStep(a, a, target, 0, threshold) as ReanchorStep.Continue).stableFrames
        stable = (reanchorStep(a, a, target, stable, threshold) as ReanchorStep.Continue).stableFrames
        assertEquals(2, stable)
        // Image B now decodes and grows → the position moves → stability must reset, not stop.
        val b = ReanchorFrame(target, 80)
        val step = reanchorStep(b, previous = a, target = target, stableFrames = stable, stableThreshold = threshold)
        assertTrue(step is ReanchorStep.Continue)
        assertEquals("staggered growth resets stability", 0, (step as ReanchorStep.Continue).stableFrames)
    }

    @Test
    fun `index drift (an earlier item peeks at the top) asks for a re-pin`() {
        val step = reanchorStep(
            current = ReanchorFrame(target - 1, 10),
            previous = ReanchorFrame(target, 0),
            target = target,
            stableFrames = 0,
            stableThreshold = threshold,
        )
        assertTrue(step is ReanchorStep.Continue)
        assertTrue((step as ReanchorStep.Continue).repin)
    }
}
