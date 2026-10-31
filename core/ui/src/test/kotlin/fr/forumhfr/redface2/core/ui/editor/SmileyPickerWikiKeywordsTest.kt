package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.SmileyWikiKeywordsResult
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmileyPickerWikiKeywordsTest {
    private val perso = EditorSmiley("[:ticento:6]", "https://example.invalid/ticento.gif", EditorSmileySource.WIKI)
    private val loaded = SmileyWikiKeywordsResult.Loaded(listOf("feu", "chaud"))

    @Test
    fun `personal detail loads immediately then publishes the result for its exact token`() = runTest {
        val gate = CompletableDeferred<SmileyWikiKeywordsResult>()
        val calls = mutableListOf<String>()
        val controller = SmileyPickerController(
            backgroundScope, { _, _ -> emptyList() },
            wikiKeywords = SmileyWikiKeywordsSource(load = { calls += it; gate.await() }),
        )
        controller.open()
        controller.onDetailChanged(perso)
        assertEquals(SmileyWikiKeywordsState.Loading, controller.openState().wikiKeywords)
        runCurrent()
        assertEquals(listOf(perso.token), calls)
        gate.complete(loaded)
        runCurrent()
        assertEquals(SmileyWikiKeywordsState.Loaded(loaded.keywords), controller.openState().wikiKeywords)
    }

    @Test
    fun `builtin detail stays hidden and never loads Wiki metadata`() = runTest {
        var calls = 0
        val controller = SmileyPickerController(
            backgroundScope, { _, _ -> emptyList() },
            wikiKeywords = SmileyWikiKeywordsSource(load = { calls++; loaded }),
        )
        controller.open()
        controller.onDetailChanged(BUILTIN_HFR_SMILEYS.first())
        runCurrent()
        assertEquals(SmileyWikiKeywordsState.Hidden, controller.openState().wikiKeywords)
        assertEquals(0, calls)
    }

    @Test
    fun `leaving detail cancels the request and a stale retry cannot restart it`() = runTest {
        var cancelled = false
        var calls = 0
        val controller = SmileyPickerController(
            backgroundScope, { _, _ -> emptyList() },
            wikiKeywords = SmileyWikiKeywordsSource(load = {
                calls++
                try { awaitCancellation() } finally { cancelled = true }
            }),
        )
        controller.open()
        controller.onDetailChanged(perso)
        runCurrent()
        val retry = controller.openState().onWikiKeywordsRetry
        controller.onDetailChanged(null)
        retry?.invoke()
        runCurrent()
        assertTrue(cancelled)
        assertNull(controller.openState().detailSmiley)
        assertEquals(SmileyWikiKeywordsState.Hidden, controller.openState().wikiKeywords)
        assertEquals(1, calls)
    }

    @Test
    fun `dismissal, redundant opening and account switch all cancel detail loading`() = runTest {
        val locals = FakePickerLocalCollections()
        var cancellations = 0
        val controller = SmileyPickerController(
            backgroundScope, { _, _ -> emptyList() },
            favoriteSources = SmileyPickerFavoriteSources(localCollections = locals),
            wikiKeywords = SmileyWikiKeywordsSource(load = {
                try { awaitCancellation() } finally { cancellations++ }
            }),
        )
        runCurrent()
        controller.open()
        controller.onDetailChanged(perso)
        runCurrent()
        controller.dismiss()
        runCurrent()
        assertEquals(1, cancellations)
        assertEquals(SmileyPickerState.Hidden, controller.state.value)
        controller.open()
        controller.onDetailChanged(perso)
        runCurrent()
        controller.open()
        runCurrent()
        assertEquals(2, cancellations)
        controller.onDetailChanged(perso)
        runCurrent()
        locals.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        runCurrent()
        assertEquals(3, cancellations)
        assertNull(controller.openState().detailSmiley)
        assertEquals(SmileyWikiKeywordsState.Hidden, controller.openState().wikiKeywords)
    }

    @Test
    fun `retry after failure loads again while duplicate taps during loading do nothing`() = runTest {
        var calls = 0
        val controller = SmileyPickerController(
            backgroundScope, { _, _ -> emptyList() },
            wikiKeywords = SmileyWikiKeywordsSource(load = {
                calls++
                if (calls == 1) throw IOException("offline")
                loaded
            }),
        )
        controller.open()
        controller.onDetailChanged(perso)
        runCurrent()
        assertEquals(SmileyWikiKeywordsState.Failed, controller.openState().wikiKeywords)
        controller.openState().onWikiKeywordsRetry?.invoke()
        controller.openState().onWikiKeywordsRetry?.invoke()
        assertEquals(SmileyWikiKeywordsState.Loading, controller.openState().wikiKeywords)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(SmileyWikiKeywordsState.Loaded(loaded.keywords), controller.openState().wikiKeywords)
    }

    @Test
    fun `cached keywords are visible synchronously even in a new controller after an account switch`() = runTest {
        val locals = FakePickerLocalCollections()
        var calls = 0
        val source = SmileyWikiKeywordsSource(cached = { loaded }, load = { calls++; loaded })
        val controller = SmileyPickerController(
            backgroundScope, { _, _ -> emptyList() },
            favoriteSources = SmileyPickerFavoriteSources(localCollections = locals), wikiKeywords = source,
        )
        controller.open()
        controller.onDetailChanged(perso)
        assertEquals(SmileyWikiKeywordsState.Loaded(loaded.keywords), controller.openState().wikiKeywords)
        locals.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        runCurrent()
        controller.onDetailChanged(perso)
        assertEquals(SmileyWikiKeywordsState.Loaded(loaded.keywords), controller.openState().wikiKeywords)
        val recreated = SmileyPickerController(backgroundScope, { _, _ -> emptyList() }, wikiKeywords = source)
        recreated.open()
        recreated.onDetailChanged(perso)
        assertEquals(SmileyWikiKeywordsState.Loaded(loaded.keywords), recreated.openState().wikiKeywords)
        runCurrent()
        assertEquals(0, calls)
    }

    @Test
    fun `a non cooperative response from a previous visit cannot replace the next visit to the same token`() = runTest {
        val continuations = mutableListOf<Continuation<SmileyWikiKeywordsResult>>()
        val controller = SmileyPickerController(
            backgroundScope, { _, _ -> emptyList() },
            wikiKeywords = SmileyWikiKeywordsSource(load = { suspendCoroutine { continuations += it } }),
        )
        controller.open()
        controller.onDetailChanged(perso)
        runCurrent()
        controller.onDetailChanged(null)
        controller.onDetailChanged(perso)
        runCurrent()
        continuations[1].resume(loaded)
        runCurrent()
        continuations[0].resume(SmileyWikiKeywordsResult.Failed)
        runCurrent()
        assertEquals(SmileyWikiKeywordsState.Loaded(loaded.keywords), controller.openState().wikiKeywords)
    }

    private fun SmileyPickerController.openState() = state.value as SmileyPickerState.Open
}
