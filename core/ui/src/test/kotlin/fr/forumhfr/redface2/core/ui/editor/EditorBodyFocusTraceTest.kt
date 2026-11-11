package fr.forumhfr.redface2.core.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorBodyFocusTraceTest {
    @Before fun clearLogs() { ShadowLog.clear() }

    @Test fun disabledTraceNeverReadsTheClockOrWritesToLogcat() {
        var clockReads = 0
        val trace = EditorBodyFocusTrace(enabled = false) { clockReads++; 100L }
        trace.availability(EditorBodyFocusAvailability.Pending)
        trace.availability(EditorBodyFocusAvailability.Ready)
        trace.outcome("restored")
        assertEquals(0, clockReads)
        assertTrue(ShadowLog.getLogsForTag("RF2-H").isEmpty())
    }

    @Test fun debugTraceRecordsTransitionsFirstReadyDelayAndOutcomeWithoutRepeatingStableAvailability() {
        var time = 100L
        val trace = EditorBodyFocusTrace(enabled = true) { time }
        trace.availability(EditorBodyFocusAvailability.Pending)
        trace.availability(EditorBodyFocusAvailability.Pending)
        time += 37L
        trace.availability(EditorBodyFocusAvailability.Ready)
        time += 10L
        trace.availability(EditorBodyFocusAvailability.Blocked)
        trace.outcome("preview-blocked")
        val messages = ShadowLog.getLogsForTag("RF2-H").map { it.msg }
        assertEquals(4, messages.size)
        assertTrue(messages[0].contains("availability=null->Pending"))
        assertTrue(messages[1].contains("availability=Pending->Ready readyAfterMs=37"))
        assertTrue(messages[2].contains("availability=Ready->Blocked"))
        assertTrue(messages[3].contains("elapsedMs=47 outcome=preview-blocked"))
    }
}
