package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.SmileyPickerCloseCause
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.SmileyWikiKeywordsResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmileyPickerDiagnosticsTest {
    private val perso = EditorSmiley("[:private token]", "https://example.invalid/private.gif", EditorSmileySource.WIKI)

    @Test
    fun `controller records only actual opening and closing transitions and insertion cause`() = runTest {
        val log = DiagnosticsLog()
        val controller = SmileyPickerController(backgroundScope, { _, _ -> emptyList() }, diagnostics = log)
        controller.dismiss()
        controller.onDetailChanged(perso)
        controller.open()
        controller.open()
        controller.onDetailChanged(BUILTIN_HFR_SMILEYS.first())
        controller.onDetailChanged(null)
        controller.dismiss(SmileyPickerCloseCause.INSERTION)
        controller.dismiss()
        assertEquals(listOf(
            "sheet_open", "detail_open kind=standard", "detail_close cause=back", "sheet_close cause=insertion",
        ), log.entries.value.map { it.message })
    }

    @Test
    fun `loaded empty failed and cached keyword results log neither token nor exception`() = runTest {
        val log = DiagnosticsLog()
        var result: SmileyWikiKeywordsResult = SmileyWikiKeywordsResult.Loaded(listOf("secret keyword"))
        var cached: SmileyWikiKeywordsResult.Loaded? = null
        val controller = SmileyPickerController(
            backgroundScope, { _, _ -> emptyList() }, diagnostics = log,
            wikiKeywords = SmileyWikiKeywordsSource(
                cached = { cached }, load = {
                    if (result == SmileyWikiKeywordsResult.Failed) error("secret exception")
                    result
                },
            ),
        )
        controller.open()
        controller.onDetailChanged(perso)
        runCurrent()
        result = SmileyWikiKeywordsResult.Loaded(emptyList())
        controller.onDetailChanged(perso)
        runCurrent()
        result = SmileyWikiKeywordsResult.Failed
        controller.onDetailChanged(perso)
        runCurrent()
        cached = SmileyWikiKeywordsResult.Loaded(listOf("secret keyword"))
        controller.onDetailChanged(perso)
        val messages = log.entries.value.map { it.message }
        val outcomes = messages.filter { it.startsWith("keywords ") }
        assertEquals(listOf("loaded", "empty", "failed", "loaded"), outcomes.map {
            it.substringAfter("outcome=").substringBefore(' ')
        })
        assertTrue(outcomes.all { it.contains(Regex("durationMs=\\d+")) })
        assertTrue(outcomes.last().endsWith("cached=true"))
        assertFalse(messages.any { it.contains("secret") || it.contains("private") || it.contains("https://") })
    }

    @Test
    fun `cancelled keyword request emits no result after leaving the detail`() = runTest {
        val log = DiagnosticsLog()
        val gate = CompletableDeferred<SmileyWikiKeywordsResult>()
        val controller = SmileyPickerController(
            backgroundScope, { _, _ -> emptyList() }, diagnostics = log,
            wikiKeywords = SmileyWikiKeywordsSource(load = { gate.await() }),
        )
        controller.open()
        controller.onDetailChanged(perso)
        runCurrent()
        controller.onDetailChanged(null)
        gate.complete(SmileyWikiKeywordsResult.Loaded(listOf("secret keyword")))
        runCurrent()
        assertFalse(log.entries.value.any { it.message.startsWith("keywords ") })
    }
}
