package fr.forumhfr.redface2.feature.topic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickReplyFieldHeightTest {

    @Test
    fun `small phone with keyboard caps the field below the RF1 target`() {
        val lines = quickReplyFieldMaxLines(
            windowHeightDp = 640f,
            imeHeightDp = 300f,
            lineHeightDp = QUICK_REPLY_FIELD_FALLBACK_LINE_HEIGHT_DP,
        )

        assertEquals(10, lines)
        assertTrue(lines < QUICK_REPLY_FIELD_TARGET_MAX_LINES)
    }

    @Test
    fun `large useful window keeps the fifteen line target`() {
        val lines = quickReplyFieldMaxLines(
            windowHeightDp = 900f,
            imeHeightDp = 0f,
            lineHeightDp = QUICK_REPLY_FIELD_FALLBACK_LINE_HEIGHT_DP,
        )

        assertEquals(QUICK_REPLY_FIELD_TARGET_MAX_LINES, lines)
    }

    @Test
    fun `landscape with keyboard reduces the cap without hitting the floor`() {
        val lines = quickReplyFieldMaxLines(
            windowHeightDp = 360f,
            imeHeightDp = 180f,
            lineHeightDp = QUICK_REPLY_FIELD_FALLBACK_LINE_HEIGHT_DP,
        )

        assertEquals(5, lines)
    }

    @Test
    fun `tiny useful window never goes below three lines`() {
        val lines = quickReplyFieldMaxLines(
            windowHeightDp = 320f,
            imeHeightDp = 300f,
            lineHeightDp = QUICK_REPLY_FIELD_FALLBACK_LINE_HEIGHT_DP,
        )

        assertEquals(QUICK_REPLY_FIELD_MIN_LINES, lines)
    }
}
