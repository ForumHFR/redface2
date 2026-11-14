package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #387 — the picker state machine extracted from PostEditorViewModel : debounce, the ≤ 2-chars
 * idle gate, stale-result guards, the userId 0 fallback. #824 adds the restore-on-reopen
 * contract (dismiss snapshots the search, open restores it, transient branches normalise to
 * Idle). Pure JVM (virtual time).
 *
 * The controller takes the TestScope itself (NOT backgroundScope) : with coroutines 1.11
 * the test scheduler does not advance backgroundScope children from advanceUntilIdle in
 * this setup (probed empirically) ; every launched search either completes or is
 * explicitly cancelled by dismiss(), so runTest never hangs on a leftover child.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmileyPickerControllerTest {

    @Test
    fun `three typed characters stay Idle until debounce then start the search`() = runTest {
        val result = CompletableDeferred<List<EditorSmiley>>()
        var calls = 0
        val controller = SmileyPickerController(this, { _, _ -> calls++; result.await() })
        controller.open(SmileyPickerOpenIntent.SEARCH)
        listOf("j", "ja", "jap").forEach(controller::onQueryChanged)
        advanceTimeBy(SmileyPickerController.SEARCH_DEBOUNCE_MS - 1)
        val open = controller.state.value as SmileyPickerState.Open
        assertEquals("jap", open.query)
        assertEquals(WikiSearchState.Idle, open.wiki)
        assertEquals(0, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(WikiSearchState.Loading, (controller.state.value as SmileyPickerState.Open).wiki)
        assertEquals(1, calls)
        result.complete(emptyList())
        runCurrent()
    }

    @Test
    fun `an edited restored query stays neutral even when its original text is retyped`() = runTest {
        val controller = SmileyPickerController(this, { _, _ -> listOf(smiley) })
        controller.open(SmileyPickerOpenIntent.SEARCH)
        controller.onQueryChanged("jap")
        advanceUntilIdle()
        controller.dismiss()
        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        val restored = controller.state.value as SmileyPickerState.Open
        assertEquals("jap", restored.restoredQuery)
        controller.onQueryChanged("japo")
        val edited = controller.state.value as SmileyPickerState.Open
        assertNull(edited.restoredQuery)
        // Restored results stay on screen during the debounce instead of flashing empty.
        assertEquals(restored.wiki, edited.wiki)
        controller.onQueryChanged("jap")
        val open = controller.state.value as SmileyPickerState.Open
        assertEquals(restored.wiki, open.wiki)
        assertNull(open.restoredQuery)
        controller.dismiss()
    }

    @Test
    fun `search forces Wiki without a request and consultation restores results`() = runTest {
        var calls = 0
        val controller = SmileyPickerController(this, { _, _ -> calls++; listOf(smiley) })
        controller.open(SmileyPickerOpenIntent.SEARCH)
        var open = controller.state.value as SmileyPickerState.Open
        assertEquals(SmileyPickerTab.WIKI, open.initialTab)
        assertEquals(SmileyPickerOpenIntent.SEARCH, open.openingIntent)
        advanceUntilIdle()
        assertEquals(0, calls)
        controller.onQueryChanged("jap")
        advanceUntilIdle()
        controller.dismiss()
        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        open = controller.state.value as SmileyPickerState.Open
        assertEquals(SmileyPickerOpenIntent.CONSULTATION, open.openingIntent)
        assertEquals("jap", open.query)
        assertEquals(WikiSearchState.Results(listOf(smiley)), open.wiki)
        controller.dismiss()
        controller.open(SmileyPickerOpenIntent.SEARCH)
        open = controller.state.value as SmileyPickerState.Open
        assertEquals(SmileyPickerTab.WIKI, open.initialTab)
        assertEquals(WikiSearchState.Results(listOf(smiley)), open.wiki)
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    private val smiley = EditorSmiley(
        token = "[:rofl]",
        imageUrl = "https://x/rofl.gif",
        source = EditorSmileySource.WIKI,
    )

    @Test
    fun `open exposes HFR favorites in server order without a wiki search`() = runTest {
        val favorites = listOf("[:fictif_z:3]", "[:fictif_a]").map { token ->
            EditorSmiley(token, "https://example.invalid/$token.gif", EditorSmileySource.HFR_FAVORITE)
        }
        val controller = SmileyPickerController(
            scope = this,
            searchWiki = { _, _ -> error("Favorites must not trigger a search") },
            favoriteSources = SmileyPickerFavoriteSources(favoriteSmileys = { favorites }),
        )
        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        assertEquals(favorites, (controller.state.value as SmileyPickerState.Open).favoriteSmileys)
    }

    @Test
    fun `empty form favorites leave no favorites section in the state`() = runTest {
        val controller = SmileyPickerController(this, { _, _ -> emptyList() })
        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        assertTrue((controller.state.value as SmileyPickerState.Open).favoriteSmileys.isEmpty())
    }

    @Test
    fun `form refresh updates visible favorites and reopening never restores stale favorites`() = runTest {
        var favorites = listOf(
            EditorSmiley("[:fictif_a]", "https://example.invalid/a.gif", EditorSmileySource.HFR_FAVORITE),
        )
        val controller = SmileyPickerController(
            scope = this,
            searchWiki = { _, _ -> emptyList() },
            favoriteSources = SmileyPickerFavoriteSources(favoriteSmileys = { favorites }),
        )
        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.onQueryChanged("ab")
        favorites = listOf(
            EditorSmiley("[:fictif_b:3]", "https://example.invalid/b.gif", EditorSmileySource.HFR_FAVORITE),
        )
        controller.refreshFavorites(controller.favoriteFormStamp())
        val open = controller.state.value as SmileyPickerState.Open
        assertEquals(favorites, open.favoriteSmileys)
        assertEquals("ab", open.query)
        controller.dismiss()
        favorites = emptyList()
        controller.refreshFavorites(controller.favoriteFormStamp())
        assertEquals(SmileyPickerState.Hidden, controller.state.value)
        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        assertTrue((controller.state.value as SmileyPickerState.Open).favoriteSmileys.isEmpty())
        assertEquals("ab", (controller.state.value as SmileyPickerState.Open).query)
    }

    @Test
    fun `open is idempotent and dismiss resets to Hidden`() = runTest {
        val controller = SmileyPickerController(this, { _, _ -> emptyList() })

        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.onQueryChanged("ja")
        controller.open(SmileyPickerOpenIntent.CONSULTATION) // must NOT reset the typed query

        val open = controller.state.value as SmileyPickerState.Open
        assertEquals("ja", open.query)

        controller.dismiss()
        assertEquals(SmileyPickerState.Hidden, controller.state.value)
    }

    @Test
    fun `queries of 2 chars or fewer keep the wiki branch Idle without searching`() = runTest {
        var calls = 0
        val controller = SmileyPickerController(this, { _, _ -> calls++; emptyList() })

        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.onQueryChanged("ja")
        advanceUntilIdle()

        val open = controller.state.value as SmileyPickerState.Open
        assertEquals(WikiSearchState.Idle, open.wiki)
        assertEquals(0, calls)
    }

    @Test
    fun `a debounced query lands Results`() = runTest {
        val controller = SmileyPickerController(this, { _, query ->
            assertEquals("jap", query)
            listOf(smiley)
        })

        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.onQueryChanged("jap")
        advanceUntilIdle()

        val open = controller.state.value as SmileyPickerState.Open
        assertEquals(WikiSearchState.Results(listOf(smiley)), open.wiki)
    }

    @Test
    fun `typing within the debounce window coalesces to a single search`() = runTest {
        val queries = mutableListOf<String>()
        val controller = SmileyPickerController(this, { _, query ->
            queries += query
            emptyList()
        })

        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.onQueryChanged("jap")
        advanceTimeBy(SmileyPickerController.SEARCH_DEBOUNCE_MS / 2)
        controller.onQueryChanged("japo")
        advanceUntilIdle()

        assertEquals(listOf("japo"), queries)
    }

    @Test
    fun `a search failure lands Error and notifies the host`() = runTest {
        var reported: Throwable? = null
        val controller = SmileyPickerController(
            scope = this,
            searchWiki = { _, _ -> throw IOException("offline") },
            onSearchFailed = { reported = it },
        )

        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.onQueryChanged("jap")
        advanceUntilIdle()

        val open = controller.state.value as SmileyPickerState.Open
        assertEquals(WikiSearchState.Error, open.wiki)
        assertTrue(reported is IOException)
    }

    @Test
    fun `a result arriving after dismiss is dropped`() = runTest {
        val controller = SmileyPickerController(this, { _, _ -> listOf(smiley) })

        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.onQueryChanged("jap")
        advanceTimeBy(SmileyPickerController.SEARCH_DEBOUNCE_MS / 2)
        controller.dismiss()
        advanceUntilIdle()

        assertEquals(
            "a dismissal mid-debounce must leave the picker Hidden, not resurrect an Open state",
            SmileyPickerState.Hidden,
            controller.state.value,
        )
    }

    @Test
    fun `a query change landing after dismiss is ignored — no search while Hidden`() = runTest {
        var calls = 0
        val controller = SmileyPickerController(this, { _, _ -> calls++; emptyList() })

        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.dismiss()
        controller.onQueryChanged("jap") // late UI callback after the sheet closed
        advanceUntilIdle()

        assertEquals(SmileyPickerState.Hidden, controller.state.value)
        assertEquals("Hidden means no live work — the debounce must not arm", 0, calls)
    }

    @Test
    fun `a null user id falls back to 0 (PostEditorViewModel parity)`() = runTest {
        var seenUserId: Int? = null
        val controller = SmileyPickerController(
            scope = this,
            searchWiki = { userId, _ -> seenUserId = userId; emptyList() },
            userId = { null },
        )

        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.onQueryChanged("jap")
        advanceUntilIdle()

        assertEquals(0, seenUserId)
    }

    // ----- #824 : restore the search on reopen ------------------------------------

    @Test
    fun `dismiss then open restores the query and its Results without refetching`() = runTest {
        var calls = 0
        val controller = SmileyPickerController(this, { _, _ -> calls++; listOf(smiley) })

        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.onQueryChanged("jap")
        advanceUntilIdle()
        assertEquals(1, calls)

        val diagnostics = (controller.state.value as SmileyPickerState.Open).diagnostics
        controller.dismiss() // insertion and swipe-down both land here
        controller.open(SmileyPickerOpenIntent.CONSULTATION)

        assertEquals(
            "the dismissed search must come back verbatim — no refetch",
            SmileyPickerState.Open(query = "jap", wiki = WikiSearchState.Results(listOf(smiley)),
                openingIntent = SmileyPickerOpenIntent.CONSULTATION, restoredQuery = "jap",
                onDetailChange = controller::onDetailChanged,
                onWikiKeywordsRetry = controller::retryWikiKeywords, diagnostics = diagnostics),
            controller.state.value,
        )
        advanceUntilIdle()
        assertEquals("restoring Results must not arm a new search", 1, calls)
    }

    @Test
    fun `a dismiss during Loading reopens on Idle — no phantom spinner`() = runTest {
        val gate = CompletableDeferred<List<EditorSmiley>>()
        val controller = SmileyPickerController(this, { _, _ -> gate.await() })

        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.onQueryChanged("jap")
        // Cross the debounce so the state flips to Loading, with the fetch still hanging.
        advanceTimeBy(SmileyPickerController.SEARCH_DEBOUNCE_MS)
        runCurrent()
        assertEquals(
            WikiSearchState.Loading,
            (controller.state.value as SmileyPickerState.Open).wiki,
        )

        val diagnostics = (controller.state.value as SmileyPickerState.Open).diagnostics
        controller.dismiss() // cancels the hanging job
        controller.open(SmileyPickerOpenIntent.CONSULTATION)

        // A restored Loading would spin forever (its job is dead) : it must normalise to Idle.
        assertEquals(
            SmileyPickerState.Open(query = "jap", wiki = WikiSearchState.Idle,
                openingIntent = SmileyPickerOpenIntent.CONSULTATION, restoredQuery = "jap",
                onDetailChange = controller::onDetailChanged,
                onWikiKeywordsRetry = controller::retryWikiKeywords, diagnostics = diagnostics),
            controller.state.value,
        )
    }

    @Test
    fun `a dismiss on Error reopens on Idle — no stale failure banner`() = runTest {
        val controller = SmileyPickerController(this, { _, _ -> throw IOException("offline") })

        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.onQueryChanged("jap")
        advanceUntilIdle()
        assertEquals(
            WikiSearchState.Error,
            (controller.state.value as SmileyPickerState.Open).wiki,
        )

        val diagnostics = (controller.state.value as SmileyPickerState.Open).diagnostics
        controller.dismiss()
        controller.open(SmileyPickerOpenIntent.CONSULTATION)

        assertEquals(
            SmileyPickerState.Open(query = "jap", wiki = WikiSearchState.Idle,
                openingIntent = SmileyPickerOpenIntent.CONSULTATION, restoredQuery = "jap",
                onDetailChange = controller::onDetailChanged,
                onWikiKeywordsRetry = controller::retryWikiKeywords, diagnostics = diagnostics),
            controller.state.value,
        )
    }

    @Test
    fun `a restored short query stays Idle and never searches`() = runTest {
        var calls = 0
        val controller = SmileyPickerController(this, { _, _ -> calls++; emptyList() })

        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.onQueryChanged("ja") // below MIN_WIKI_QUERY_LENGTH → Idle, no search
        val diagnostics = (controller.state.value as SmileyPickerState.Open).diagnostics
        controller.dismiss()
        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        advanceUntilIdle()

        assertEquals(
            SmileyPickerState.Open(query = "ja", wiki = WikiSearchState.Idle,
                openingIntent = SmileyPickerOpenIntent.CONSULTATION, restoredQuery = "ja",
                onDetailChange = controller::onDetailChanged,
                onWikiKeywordsRetry = controller::retryWikiKeywords, diagnostics = diagnostics),
            controller.state.value,
        )
        assertEquals(0, calls)
    }

    @Test
    fun `open while already Open never clobbers the live search with the snapshot`() = runTest {
        val controller = SmileyPickerController(this, { _, _ -> listOf(smiley) })

        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.onQueryChanged("jap")
        advanceUntilIdle()
        controller.dismiss() // snapshot = ("jap", Results)

        controller.open(SmileyPickerOpenIntent.CONSULTATION) // restores the snapshot…
        controller.onQueryChanged("sm") // …then the user starts a different search
        // A redundant open (e.g. re-tapped trigger) must be a no-op.
        controller.open(SmileyPickerOpenIntent.CONSULTATION)

        assertEquals(
            "the idempotent open must keep the live query, not resurrect the snapshot",
            "sm",
            (controller.state.value as SmileyPickerState.Open).query,
        )
    }
}
