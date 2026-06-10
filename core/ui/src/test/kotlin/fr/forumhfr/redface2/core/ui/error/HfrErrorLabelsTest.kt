package fr.forumhfr.redface2.core.ui.error

import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #324 — locks the [sharedLabelResOrNull] contract: ServerDown / Network resolve to the
 * shared `:core:ui` labels, Other resolves to nothing so each screen keeps its own
 * generic fallback. Pure JVM test — only R-class constants are compared, no resource
 * resolution is needed.
 */
class HfrErrorLabelsTest {

    @Test
    fun `ServerDown maps to the shared HFR outage label`() {
        assertEquals(
            R.string.error_hfr_server_down,
            HfrErrorKind.ServerDown.sharedLabelResOrNull(),
        )
    }

    @Test
    fun `Network maps to the shared no-connection label`() {
        assertEquals(
            R.string.error_no_connection,
            HfrErrorKind.Network.sharedLabelResOrNull(),
        )
    }

    @Test
    fun `Other maps to null so each screen keeps its local fallback`() {
        assertNull(HfrErrorKind.Other.sharedLabelResOrNull())
    }
}
