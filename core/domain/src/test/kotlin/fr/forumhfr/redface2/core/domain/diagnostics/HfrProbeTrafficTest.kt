package fr.forumhfr.redface2.core.domain.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HfrProbeTrafficTest {
    @Test
    fun `calls already active or entirely contained in a probe disturb it`() {
        val traffic = HfrProbeTraffic()
        val clean = traffic.snapshot()
        assertFalse(traffic.disturbed(clean))
        traffic.started()
        val active = traffic.snapshot()
        traffic.finished()
        assertTrue(traffic.disturbed(active))
        assertTrue(traffic.disturbed(clean))
        val after = traffic.snapshot()
        assertFalse(traffic.disturbed(after))
        traffic.started()
        traffic.finished()
        assertTrue(traffic.disturbed(after))
    }
}
