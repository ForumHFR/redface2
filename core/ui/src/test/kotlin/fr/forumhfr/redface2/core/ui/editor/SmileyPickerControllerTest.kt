package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #387 — the picker state machine extracted from PostEditorViewModel : debounce, the ≤ 2-chars
 * idle gate, stale-result guards, the userId 0 fallback. Pure JVM (virtual time).
 *
 * The controller takes the TestScope itself (NOT backgroundScope) : with coroutines 1.11
 * the test scheduler does not advance backgroundScope children from advanceUntilIdle in
 * this setup (probed empirically) ; every launched search either completes or is
 * explicitly cancelled by dismiss(), so runTest never hangs on a leftover child.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmileyPickerControllerTest {

    private val smiley = EditorSmiley(
        token = "[:rofl]",
        imageUrl = "https://x/rofl.gif",
        source = EditorSmileySource.WIKI,
    )

    @Test
    fun `open is idempotent and dismiss resets to Hidden`() = runTest {
        val controller = SmileyPickerController(this, { _, _ -> emptyList() })

        controller.open()
        controller.onQueryChanged("ja")
        controller.open() // must NOT reset the typed query

        val open = controller.state.value as SmileyPickerState.Open
        assertEquals("ja", open.query)

        controller.dismiss()
        assertEquals(SmileyPickerState.Hidden, controller.state.value)
    }

    @Test
    fun `queries of 2 chars or fewer keep the wiki branch Idle without searching`() = runTest {
        var calls = 0
        val controller = SmileyPickerController(this, { _, _ -> calls++; emptyList() })

        controller.open()
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

        controller.open()
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

        controller.open()
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

        controller.open()
        controller.onQueryChanged("jap")
        advanceUntilIdle()

        val open = controller.state.value as SmileyPickerState.Open
        assertEquals(WikiSearchState.Error, open.wiki)
        assertTrue(reported is IOException)
    }

    @Test
    fun `a result arriving after dismiss is dropped`() = runTest {
        val controller = SmileyPickerController(this, { _, _ -> listOf(smiley) })

        controller.open()
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
    fun `a null user id falls back to 0 (PostEditorViewModel parity)`() = runTest {
        var seenUserId: Int? = null
        val controller = SmileyPickerController(
            scope = this,
            searchWiki = { userId, _ -> seenUserId = userId; emptyList() },
            userId = { null },
        )

        controller.open()
        controller.onQueryChanged("jap")
        advanceUntilIdle()

        assertEquals(0, seenUserId)
    }
}
