package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmileyPickerHfrFavoritesTest {
    private val wiki = EditorSmiley("[:fictif_a:2]", "https://example.invalid/a.gif", EditorSmileySource.WIKI)
    private val favorite = wiki.copy(source = EditorSmileySource.HFR_FAVORITE)
    private val other = favorite.copy(token = "[:fictif b]", imageUrl = "https://example.invalid/b.gif")

    @Test
    fun `add appends optimistically then adopts the confirmed order and images`() = runTest {
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(this, { listOf(other) }, add = { gate.await() })
        controller.onFavoriteClicked(wiki)
        assertEquals(listOf(other, favorite), controller.openState().favoriteSmileys)
        assertEquals(wiki.token, controller.openState().pendingFavoriteToken)

        val confirmed = listOf(favorite.copy(imageUrl = "https://example.invalid/confirmed.gif"), other)
        gate.complete(HfrFavoriteSmileyMutationResult.Success(confirmed))
        advanceUntilIdle()
        assertEquals(confirmed, controller.openState().favoriteSmileys)
        assertNull(controller.openState().pendingFavoriteToken)
        assertEquals(SmileyFavoriteFeedback.Added, controller.openState().favoriteFeedback)
        controller.dismiss()
        controller.open()
        assertEquals(confirmed, controller.openState().favoriteSmileys)
    }

    @Test
    fun `remove marks its target optimistically then reconciles the confirmed list`() = runTest {
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(this, { listOf(favorite, other) }, remove = { gate.await() })
        controller.onFavoriteClicked(favorite)
        assertEquals(listOf(favorite, other), controller.openState().favoriteSmileys)
        assertEquals(favorite.token, controller.openState().pendingFavoriteToken)
        assertNull(controller.openState().favoriteAction(favorite))

        gate.complete(HfrFavoriteSmileyMutationResult.Success(listOf(other)))
        advanceUntilIdle()
        assertEquals(listOf(other), controller.openState().favoriteSmileys)
        assertEquals(SmileyFavoriteFeedback.Removed, controller.openState().favoriteFeedback)
    }

    @Test
    fun `failed add and removal roll back the preceding confirmed list`() = runTest {
        val controller = controller(
            this,
            { listOf(other) },
            add = { HfrFavoriteSmileyMutationResult.TransportFailure },
            remove = { HfrFavoriteSmileyMutationResult.UnknownResponse },
        )
        controller.onFavoriteClicked(wiki)
        advanceUntilIdle()
        assertEquals(listOf(other), controller.openState().favoriteSmileys)
        controller.onFavoriteClicked(other)
        advanceUntilIdle()
        assertEquals(listOf(other), controller.openState().favoriteSmileys)
        assertNull(controller.openState().pendingFavoriteToken)
    }

    @Test
    fun `each result selects its feedback and only confirmed lists replace favorites`() = runTest {
        val cases = listOf(
            Outcome(
                HfrFavoriteSmileyMutationResult.Success(listOf(favorite)),
                SmileyFavoriteFeedback.Added, listOf(favorite),
            ),
            Outcome(
                HfrFavoriteSmileyMutationResult.AlreadyPresent(listOf(favorite)),
                SmileyFavoriteFeedback.AlreadyPresent, listOf(favorite),
            ),
            Outcome(
                HfrFavoriteSmileyMutationResult.Full(7, listOf(favorite)),
                SmileyFavoriteFeedback.Full(7), listOf(favorite),
            ),
            Outcome(
                HfrFavoriteSmileyMutationResult.VerificationFailed(listOf(favorite)),
                SmileyFavoriteFeedback.ListChanged, listOf(favorite),
            ),
            Outcome(HfrFavoriteSmileyMutationResult.NotFound, SmileyFavoriteFeedback.NotFound, listOf(other)),
            Outcome(HfrFavoriteSmileyMutationResult.InvalidToken, SmileyFavoriteFeedback.NotFound, listOf(other)),
            Outcome(HfrFavoriteSmileyMutationResult.NotAuthenticated,
                SmileyFavoriteFeedback.NotAuthenticated, listOf(other)),
            Outcome(HfrFavoriteSmileyMutationResult.AccountChanged, null, listOf(other)),
            Outcome(HfrFavoriteSmileyMutationResult.UnknownResponse,
                SmileyFavoriteFeedback.UnknownResponse, listOf(other)),
            Outcome(HfrFavoriteSmileyMutationResult.TransportFailure,
                SmileyFavoriteFeedback.TransportFailure, listOf(other)),
        )
        cases.forEach { outcome ->
            val controller = controller(this, { listOf(other) }, add = { outcome.result })
            controller.onFavoriteClicked(wiki)
            advanceUntilIdle()
            val state = controller.openState()
            assertEquals(outcome.result.toString(), outcome.message, state.favoriteFeedback)
            assertEquals(outcome.result.toString(), outcome.favorites, state.favoriteSmileys)
        }
    }

    @Test
    fun `account change applies no result or rollback over the newly received form`() = runTest {
        var form = listOf(other)
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(this, { form }, add = { gate.await() })
        controller.onFavoriteClicked(wiki)
        form = listOf(favorite)
        controller.refreshFavorites()
        gate.complete(HfrFavoriteSmileyMutationResult.AccountChanged)
        advanceUntilIdle()
        assertEquals(form, controller.openState().favoriteSmileys)
        assertNull(controller.openState().favoriteFeedback)
        assertNull(controller.openState().pendingFavoriteToken)
    }

    @Test
    fun `verification failure adopts the actual list even when removal was requested`() = runTest {
        val actual = listOf(other, favorite)
        val controller = controller(
            this,
            { listOf(favorite) },
            remove = { HfrFavoriteSmileyMutationResult.VerificationFailed(actual) },
        )
        controller.onFavoriteClicked(favorite)
        advanceUntilIdle()
        assertEquals(actual, controller.openState().favoriteSmileys)
        assertEquals(SmileyFavoriteFeedback.ListChanged, controller.openState().favoriteFeedback)
    }

    @Test
    fun `a second gesture is ignored synchronously and until the first operation finishes`() = runTest {
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val calls = mutableListOf<String>()
        val removals = mutableListOf<String>()
        val controller = controller(
            this,
            { listOf(other) },
            add = { calls += it; gate.await() },
            remove = { removals += it; HfrFavoriteSmileyMutationResult.Success(emptyList()) },
        )
        controller.onFavoriteClicked(wiki)
        controller.onFavoriteClicked(wiki)
        runCurrent()
        controller.onFavoriteClicked(other)
        runCurrent()
        assertEquals(listOf(wiki.token), calls)
        assertTrue(removals.isEmpty())
        assertNull(controller.openState().favoriteAction(other))
        gate.complete(HfrFavoriteSmileyMutationResult.Success(listOf(other, favorite)))
        advanceUntilIdle()
        assertEquals(SmileyFavoriteAction.REMOVE, controller.openState().favoriteAction(wiki))
    }

    @Test
    fun `a form received during mutation cannot overwrite its result but a later form replaces it`() = runTest {
        var form = listOf(other)
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(this, { form }, add = { gate.await() })
        controller.onFavoriteClicked(wiki)
        form = emptyList()
        controller.refreshFavorites()
        controller.open()
        assertEquals(listOf(other, favorite), controller.openState().favoriteSmileys)
        gate.complete(HfrFavoriteSmileyMutationResult.Success(listOf(favorite)))
        advanceUntilIdle()
        controller.dismiss()
        controller.open()
        assertEquals(listOf(favorite), controller.openState().favoriteSmileys)

        form = listOf(other)
        controller.refreshFavorites()
        assertEquals(form, controller.openState().favoriteSmileys)
        controller.dismiss()
        form = emptyList()
        controller.refreshFavorites()
        controller.open()
        assertTrue(controller.openState().favoriteSmileys.isEmpty())
    }

    @Test
    fun `rollback preserves the preceding overlay despite a form received during the next mutation`() = runTest {
        var form = listOf(other)
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(
            this, { form },
            add = { HfrFavoriteSmileyMutationResult.Success(listOf(other, favorite)) },
            remove = { gate.await() },
        )
        controller.onFavoriteClicked(wiki)
        advanceUntilIdle()
        controller.onFavoriteClicked(other)
        form = emptyList()
        controller.refreshFavorites()
        gate.complete(HfrFavoriteSmileyMutationResult.TransportFailure)
        advanceUntilIdle()
        assertEquals(listOf(other, favorite), controller.openState().favoriteSmileys)
    }

    @Test
    fun `closing and reopening during mutation keeps its optimistic state and wiki query`() = runTest {
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(this, add = { gate.await() })
        controller.onQueryChanged("fi")
        controller.onFavoriteClicked(wiki)
        controller.dismiss()
        assertEquals(SmileyPickerState.Hidden, controller.state.value)
        controller.open()
        assertEquals("fi", controller.openState().query)
        assertEquals(listOf(favorite), controller.openState().favoriteSmileys)
        assertEquals(wiki.token, controller.openState().pendingFavoriteToken)
        gate.complete(HfrFavoriteSmileyMutationResult.Success(listOf(favorite)))
        advanceUntilIdle()
        assertNull(controller.openState().pendingFavoriteToken)
    }

    @Test
    fun `a failure arriving while hidden is displayed on reopening`() = runTest {
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(this, add = { gate.await() })
        controller.onFavoriteClicked(wiki)
        controller.dismiss()
        gate.complete(HfrFavoriteSmileyMutationResult.TransportFailure)
        advanceUntilIdle()
        assertEquals(SmileyPickerState.Hidden, controller.state.value)
        controller.open()
        assertTrue(controller.openState().favoriteSmileys.isEmpty())
        assertEquals(SmileyFavoriteFeedback.TransportFailure, controller.openState().favoriteFeedback)
    }

    @Test
    fun `success while hidden persists until a later form is received`() = runTest {
        var form = emptyList<EditorSmiley>()
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(this, { form }, add = { gate.await() })
        controller.onFavoriteClicked(wiki)
        controller.dismiss()
        form = listOf(other)
        controller.refreshFavorites()
        gate.complete(HfrFavoriteSmileyMutationResult.Success(listOf(favorite)))
        advanceUntilIdle()
        assertEquals(SmileyPickerState.Hidden, controller.state.value)
        controller.open()
        assertEquals(listOf(favorite), controller.openState().favoriteSmileys)
        controller.dismiss()
        controller.refreshFavorites()
        controller.open()
        assertEquals(form, controller.openState().favoriteSmileys)
    }

    @Test
    fun `exact token membership selects add or remove independently of the wiki image`() = runTest {
        val controller = controller(this, { listOf(favorite) })
        val open = controller.openState()
        assertEquals(SmileyFavoriteAction.REMOVE, open.favoriteAction(wiki.copy(imageUrl = "different.gif")))
        assertEquals(SmileyFavoriteAction.ADD, open.favoriteAction(wiki.copy(token = "[:fictif_a]")))
        assertEquals(SmileyFavoriteAction.REMOVE, open.favoriteAction(other))
        assertNull(open.favoriteAction(wiki.copy(source = EditorSmileySource.BUILTIN)))
    }

    @Test
    fun `builtins and gestures received after dismissal do not invoke a mutation`() = runTest {
        var calls = 0
        val controller = controller(this, add = {
            calls++
            HfrFavoriteSmileyMutationResult.Success(listOf(favorite))
        })
        controller.onFavoriteClicked(wiki.copy(source = EditorSmileySource.BUILTIN))
        assertNull(controller.openState().pendingFavoriteToken)
        val staleCommand = requireNotNull(controller.openState().onFavoriteChange)
        controller.dismiss()
        staleCommand(wiki)
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(SmileyPickerState.Hidden, controller.state.value)
    }

    private data class Outcome(
        val result: HfrFavoriteSmileyMutationResult,
        val message: SmileyFavoriteFeedback?,
        val favorites: List<EditorSmiley>,
    )

    private fun controller(
        scope: CoroutineScope,
        form: () -> List<EditorSmiley> = { emptyList() },
        add: suspend (String) -> HfrFavoriteSmileyMutationResult = { error("Unexpected add") },
        remove: suspend (String) -> HfrFavoriteSmileyMutationResult = { error("Unexpected removal") },
    ) = SmileyPickerController(
        scope = scope,
        searchWiki = { _, _ -> emptyList() },
        favoriteSmileys = form,
        hfrFavoriteMutations = SmileyFavoriteMutations(add, remove),
    ).also { it.open() }

    private fun SmileyPickerController.openState() = state.value as SmileyPickerState.Open
}
