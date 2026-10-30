package fr.forumhfr.redface2.core.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class SmileyPickerDiagnosticsTest {
    @Test
    fun `keyword results include monotonic duration and cache status`() {
        val log = DiagnosticsLog()
        var now = 100L
        val trace = SmileyPickerDiagnostics(log) { now }
        val started = trace.keywordsStarted()
        now = 350L
        trace.keywordsFinished(SmileyKeywordsOutcome.LOADED, started)
        trace.keywordsFinished(SmileyKeywordsOutcome.EMPTY, now, cached = true)
        trace.keywordsFinished(SmileyKeywordsOutcome.FAILED, started)
        assertEquals(listOf(
            "keywords outcome=loaded durationMs=250 cached=false",
            "keywords outcome=empty durationMs=0 cached=true",
            "keywords outcome=failed durationMs=250 cached=false",
        ), log.entries.value.map { it.message })
    }

    @Test
    fun `every close cause is exported as a closed value and never reused on next opening`() {
        SmileyPickerCloseCause.entries.forEach { cause ->
            val log = DiagnosticsLog()
            val trace = SmileyPickerDiagnostics(log)
            trace.sheetOpened()
            trace.dismissRequested(cause)
            trace.sheetClosed(SmileyPickerCloseCause.CONTROLLER)
            trace.sheetOpened()
            trace.sheetClosed(SmileyPickerCloseCause.CONTROLLER)
            assertEquals(listOf(
                "sheet_open", "sheet_close cause=${cause.name.lowercase()}",
                "sheet_open", "sheet_close cause=controller",
            ), log.entries.value.map { it.message })
            assertEquals(setOf("SmileyPicker"), log.entries.value.map { it.tag }.toSet())
        }
    }
}
