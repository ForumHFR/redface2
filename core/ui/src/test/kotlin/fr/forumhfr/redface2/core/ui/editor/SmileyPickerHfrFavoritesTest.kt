package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollections
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
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
    private val accountOne = favorite.copy(token = "[:compte_fictif:1]")
    private val accountTwo = favorite.copy(token = "[:compte_fictif:2]")

    @Test
    fun `add appends optimistically then merges confirmed images in base order`() = runTest {
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(this, { listOf(other) }, add = { gate.await() })
        controller.onFavoriteClicked(wiki)
        assertEquals(listOf(other, favorite), controller.openState().favoriteSmileys)
        assertEquals(wiki.token, controller.openState().pendingFavoriteToken)

        val confirmed = listOf(favorite.copy(imageUrl = "https://example.invalid/confirmed.gif"), other)
        gate.complete(HfrFavoriteSmileyMutationResult.Success(confirmed))
        advanceUntilIdle()
        val merged = listOf(other, confirmed.first())
        assertEquals(merged, controller.openState().favoriteSmileys)
        assertNull(controller.openState().pendingFavoriteToken)
        assertEquals(SmileyFavoriteFeedback.Added, controller.openState().favoriteFeedback)
        controller.dismiss()
        controller.open()
        assertEquals(merged, controller.openState().favoriteSmileys)
        assertNull(controller.openState().favoriteFeedback)
    }

    @Test
    fun `account smileys survive addition and removal wherever they appear in the base`() = runTest {
        val bases = listOf(
            listOf(accountOne, accountTwo, favorite),
            listOf(favorite, accountOne, accountTwo),
            listOf(accountOne, favorite, accountTwo),
        )
        bases.forEach { base ->
            val controller = controller(
                this, { base },
                add = { HfrFavoriteSmileyMutationResult.Success(listOf(favorite, other)) },
                remove = { HfrFavoriteSmileyMutationResult.Success(listOf(other)) },
            )
            controller.onFavoriteClicked(other.copy(source = EditorSmileySource.WIKI))
            advanceUntilIdle()
            assertEquals(base + other, controller.openState().favoriteSmileys)
            assertNull(controller.openState().favoriteAction(accountOne))
            assertNull(controller.openState().favoriteAction(accountTwo))
            assertEquals(SmileyFavoriteAction.REMOVE, controller.openState().favoriteAction(favorite))

            controller.onFavoriteClicked(favorite)
            advanceUntilIdle()
            assertEquals(listOf(accountOne, accountTwo, other), controller.openState().favoriteSmileys)
            assertEquals(SmileyFavoriteAction.ADD, controller.openState().favoriteAction(wiki))
            controller.dismiss()
            controller.open()
            assertEquals(listOf(accountOne, accountTwo, other), controller.openState().favoriteSmileys)
        }
    }

    @Test
    fun `absent removal keeps the account smiley and disables removal until a new form generation`() = runTest {
        val base = listOf(accountOne, accountTwo, favorite)
        val calls = mutableListOf<String>()
        val controller = controller(this, { base }, remove = {
            calls += it
            HfrFavoriteSmileyMutationResult.NotInFavorites(listOf(favorite))
        })
        assertEquals(SmileyFavoriteAction.REMOVE, controller.openState().favoriteAction(accountOne))
        controller.onFavoriteClicked(accountOne)
        advanceUntilIdle()
        val open = controller.openState()
        assertEquals(base, open.favoriteSmileys)
        assertEquals(SmileyFavoriteFeedback.NotInFavorites, open.favoriteFeedback)
        assertNull(open.pendingFavoriteToken)
        assertNull(open.favoriteAction(accountOne))
        assertNull(open.favoriteAction(accountTwo.copy(source = EditorSmileySource.WIKI)))
        assertEquals(SmileyFavoriteAction.REMOVE, open.favoriteAction(favorite))
        controller.onFavoriteClicked(accountOne)
        advanceUntilIdle()
        assertEquals(listOf(accountOne.token), calls)

        controller.dismiss()
        controller.open()
        assertNull(controller.openState().favoriteAction(accountOne))
        controller.refreshFavorites(controller.favoriteFormStamp())
        assertEquals(base, controller.openState().favoriteSmileys)
        assertEquals(SmileyFavoriteAction.REMOVE, controller.openState().favoriteAction(accountOne))
        assertEquals(SmileyFavoriteAction.REMOVE, controller.openState().favoriteAction(accountTwo))
    }

    @Test
    fun `successive confirmed removals stay hidden and a readded token returns at its base position`() = runTest {
        val base = listOf(accountOne, favorite, other, accountTwo)
        var confirmed = listOf(favorite, other)
        val controller = controller(
            this, { base },
            add = { token ->
                confirmed = confirmed + base.single { it.token == token }
                HfrFavoriteSmileyMutationResult.Success(confirmed)
            },
            remove = { token ->
                confirmed = confirmed.filterNot { it.token == token }
                HfrFavoriteSmileyMutationResult.Success(confirmed)
            },
        )
        controller.onFavoriteClicked(favorite)
        advanceUntilIdle()
        controller.onFavoriteClicked(other)
        advanceUntilIdle()
        assertEquals(listOf(accountOne, accountTwo), controller.openState().favoriteSmileys)
        assertEquals(SmileyFavoriteAction.ADD, controller.openState().favoriteAction(wiki))
        controller.onFavoriteClicked(wiki)
        advanceUntilIdle()
        assertEquals(listOf(accountOne, favorite, accountTwo), controller.openState().favoriteSmileys)
        assertEquals(SmileyFavoriteAction.REMOVE, controller.openState().favoriteAction(wiki))
        controller.refreshFavorites(controller.favoriteFormStamp())
        assertEquals(base, controller.openState().favoriteSmileys)
        assertEquals(SmileyFavoriteAction.REMOVE, controller.openState().favoriteAction(accountOne))
    }

    @Test
    fun `a favorite removed elsewhere stays as an account entry until the form reloads`() = runTest {
        var base = listOf(accountOne, favorite)
        val controller = controller(
            this, { base },
            add = { HfrFavoriteSmileyMutationResult.Success(listOf(other)) },
        )
        controller.onFavoriteClicked(other.copy(source = EditorSmileySource.WIKI))
        advanceUntilIdle()
        assertEquals(base + other, controller.openState().favoriteSmileys)
        assertNull(controller.openState().favoriteAction(favorite))
        assertEquals(SmileyFavoriteAction.REMOVE, controller.openState().favoriteAction(other))
        base = listOf(accountOne, other)
        controller.refreshFavorites(controller.favoriteFormStamp())
        assertEquals(base, controller.openState().favoriteSmileys)
        assertEquals(SmileyFavoriteAction.ADD, controller.openState().favoriteAction(wiki))
    }

    @Test
    fun `confirmed lists merge with the base read at each opening`() = runTest {
        var base = listOf(accountOne, favorite)
        val controller = controller(
            this, { base },
            add = { HfrFavoriteSmileyMutationResult.Success(listOf(favorite, other)) },
        )
        controller.onFavoriteClicked(other.copy(source = EditorSmileySource.WIKI))
        advanceUntilIdle()
        base = listOf(favorite, accountTwo)
        controller.open()
        assertEquals(listOf(favorite, accountTwo, other), controller.openState().favoriteSmileys)
        assertEquals(setOf(accountTwo.token), controller.openState().accountSmileyTokens)
    }

    @Test
    fun `confirmed favorites keep base positions and append new tokens in confirmed order`() = runTest {
        val added = favorite.copy(token = "[:ajout_fictif]", source = EditorSmileySource.WIKI)
        val external = favorite.copy(token = "[:externe_fictif]")
        val updatedFavorite = favorite.copy(imageUrl = "https://example.invalid/updated-a.gif")
        val updatedOther = other.copy(imageUrl = "https://example.invalid/updated-b.gif")
        val addedFavorite = added.copy(source = EditorSmileySource.HFR_FAVORITE)
        val confirmed = listOf(external, updatedOther, addedFavorite, updatedFavorite)
        val controller = controller(
            this, { listOf(accountOne, favorite, accountTwo, other) },
            add = { HfrFavoriteSmileyMutationResult.Success(confirmed) },
        )
        controller.onFavoriteClicked(added)
        advanceUntilIdle()
        assertEquals(
            listOf(accountOne, updatedFavorite, accountTwo, updatedOther, external, addedFavorite),
            controller.openState().favoriteSmileys,
        )
    }

    @Test
    fun `verification failure hides the removed token while preserving account smileys`() = runTest {
        val base = listOf(accountOne, favorite, other, accountTwo)
        val controller = controller(
            this, { base },
            remove = { HfrFavoriteSmileyMutationResult.VerificationFailed(listOf(other)) },
        )
        controller.onFavoriteClicked(favorite)
        advanceUntilIdle()
        assertEquals(listOf(accountOne, other, accountTwo), controller.openState().favoriteSmileys)
        assertEquals(SmileyFavoriteFeedback.ListChanged, controller.openState().favoriteFeedback)
        assertEquals(SmileyFavoriteAction.ADD, controller.openState().favoriteAction(wiki))
        assertNull(controller.openState().favoriteAction(accountOne))
        assertNull(controller.openState().favoriteAction(accountTwo))
        assertEquals(SmileyFavoriteAction.REMOVE, controller.openState().favoriteAction(other))
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
        controller.dismiss()
        controller.open()
        assertNull(controller.openState().favoriteFeedback)
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
    fun `each result selects its feedback and only confirmed lists merge with favorites`() = runTest {
        val cases = listOf(
            Outcome(
                HfrFavoriteSmileyMutationResult.Success(listOf(favorite)),
                SmileyFavoriteFeedback.Added, listOf(other, favorite),
            ),
            Outcome(HfrFavoriteSmileyMutationResult.AccountChanged, null, listOf(other)),
        ) + failureCases
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
        controller.refreshFavorites(controller.favoriteFormStamp())
        gate.complete(HfrFavoriteSmileyMutationResult.AccountChanged)
        advanceUntilIdle()
        assertEquals(form, controller.openState().favoriteSmileys)
        assertNull(controller.openState().favoriteFeedback)
        assertNull(controller.openState().pendingFavoriteToken)
    }

    @Test
    fun `verification failure merges the actual list even when removal was requested`() = runTest {
        val actual = listOf(other, favorite)
        val controller = controller(
            this,
            { listOf(favorite) },
            remove = { HfrFavoriteSmileyMutationResult.VerificationFailed(actual) },
        )
        controller.onFavoriteClicked(favorite)
        advanceUntilIdle()
        assertEquals(listOf(favorite, other), controller.openState().favoriteSmileys)
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
        controller.refreshFavorites(controller.favoriteFormStamp())
        controller.open()
        assertEquals(listOf(other, favorite), controller.openState().favoriteSmileys)
        gate.complete(HfrFavoriteSmileyMutationResult.Success(listOf(favorite)))
        advanceUntilIdle()
        controller.dismiss()
        controller.open()
        assertEquals(listOf(favorite), controller.openState().favoriteSmileys)

        form = listOf(other)
        controller.refreshFavorites(controller.favoriteFormStamp())
        assertEquals(form, controller.openState().favoriteSmileys)
        controller.dismiss()
        form = emptyList()
        controller.refreshFavorites(controller.favoriteFormStamp())
        controller.open()
        assertTrue(controller.openState().favoriteSmileys.isEmpty())
    }

    @Test
    fun `unconfirmed failures reveal a newer form`() = runTest {
        unconfirmedFailures.forEach { result ->
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
            controller.refreshFavorites(controller.favoriteFormStamp())
            assertEquals(listOf(other, favorite), controller.openState().favoriteSmileys)
            gate.complete(result)
            advanceUntilIdle()
            assertEquals(result.toString(), form, controller.openState().favoriteSmileys)
            assertNull(controller.openState().pendingFavoriteToken)
        }
    }

    @Test
    fun `unconfirmed failures preserve a valid snapshot`() = runTest {
        unconfirmedFailures.forEach { result ->
            val confirmed = listOf(other, favorite)
            val controller = controller(
                this, { listOf(other) },
                add = { HfrFavoriteSmileyMutationResult.Success(confirmed) },
                remove = { result },
            )
            controller.onFavoriteClicked(wiki)
            advanceUntilIdle()
            controller.onFavoriteClicked(other)
            advanceUntilIdle()
            assertEquals(result.toString(), confirmed, controller.openState().favoriteSmileys)
            controller.refreshFavorites(controller.favoriteFormStamp())
            assertEquals(result.toString(), listOf(other), controller.openState().favoriteSmileys)
        }
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
    fun `hidden failures expire after their first opening`() = runTest {
        failureCases.forEach { outcome ->
            val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
            val controller = controller(this, { listOf(other) }, add = { gate.await() })
            controller.onFavoriteClicked(wiki)
            controller.dismiss()
            gate.complete(outcome.result)
            advanceUntilIdle()
            assertEquals(SmileyPickerState.Hidden, controller.state.value)
            controller.dismiss()
            controller.open()
            assertEquals(outcome.favorites, controller.openState().favoriteSmileys)
            assertEquals(outcome.message, controller.openState().favoriteFeedback)
            controller.open()
            assertEquals(outcome.message, controller.openState().favoriteFeedback)
            controller.dismiss()
            controller.open()
            assertNull(controller.openState().favoriteFeedback)
        }
    }

    @Test
    fun `visible failures expire on dismissal`() = runTest {
        failureCases.forEach { outcome ->
            val controller = controller(this, { listOf(other) }, add = { outcome.result })
            controller.onFavoriteClicked(wiki)
            advanceUntilIdle()
            assertEquals(outcome.message, controller.openState().favoriteFeedback)
            controller.dismiss()
            controller.open()
            assertNull(controller.openState().favoriteFeedback)
        }
    }

    @Test
    fun `hidden successes stay silent`() = runTest {
        listOf(emptyList(), listOf(favorite)).forEach { initial ->
            val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
            val controller = controller(this, { initial }, add = { gate.await() }, remove = { gate.await() })
            controller.onFavoriteClicked(wiki)
            controller.dismiss()
            gate.complete(HfrFavoriteSmileyMutationResult.Success(listOf(other)))
            advanceUntilIdle()
            controller.open()
            assertEquals(listOf(other), controller.openState().favoriteSmileys)
            assertNull(controller.openState().favoriteFeedback)
        }
    }

    @Test
    fun `success while hidden persists until a later form is received`() = runTest {
        var form = emptyList<EditorSmiley>()
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(this, { form }, add = { gate.await() })
        controller.onFavoriteClicked(wiki)
        controller.dismiss()
        form = listOf(other)
        controller.refreshFavorites(controller.favoriteFormStamp())
        gate.complete(HfrFavoriteSmileyMutationResult.Success(listOf(favorite)))
        advanceUntilIdle()
        assertEquals(SmileyPickerState.Hidden, controller.state.value)
        controller.open()
        assertEquals(listOf(other, favorite), controller.openState().favoriteSmileys)
        controller.dismiss()
        controller.refreshFavorites(controller.favoriteFormStamp())
        controller.open()
        assertEquals(form, controller.openState().favoriteSmileys)
    }

    @Test
    fun `exact token membership selects add or remove independently of the wiki image`() = runTest {
        val controller = controller(this, { listOf(favorite) })
        val open = controller.openState()
        assertEquals(SmileyFavoriteAction.REMOVE, open.favoriteAction(wiki.copy(imageUrl = "different.gif")))
        assertEquals(SmileyFavoriteAction.ADD, open.favoriteAction(wiki.copy(token = "[:fictif_a]")))
        assertEquals(SmileyFavoriteAction.ADD, open.favoriteAction(other))
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

    private val unconfirmedFailures = listOf(
        HfrFavoriteSmileyMutationResult.TransportFailure,
        HfrFavoriteSmileyMutationResult.UnknownResponse,
        HfrFavoriteSmileyMutationResult.NotFound,
        HfrFavoriteSmileyMutationResult.InvalidToken,
        HfrFavoriteSmileyMutationResult.NotAuthenticated,
    )

    private val failureCases = listOf(
        Outcome(
            HfrFavoriteSmileyMutationResult.AlreadyPresent(listOf(favorite)),
            SmileyFavoriteFeedback.AlreadyPresent, listOf(other, favorite),
        ),
        Outcome(
            HfrFavoriteSmileyMutationResult.NotInFavorites(listOf(favorite)),
            SmileyFavoriteFeedback.NotInFavorites, listOf(other, favorite),
        ),
        Outcome(
            HfrFavoriteSmileyMutationResult.Full(7, listOf(favorite)),
            SmileyFavoriteFeedback.Full(7), listOf(other, favorite),
        ),
        Outcome(
            HfrFavoriteSmileyMutationResult.VerificationFailed(listOf(favorite)),
            SmileyFavoriteFeedback.ListChanged, listOf(other, favorite),
        ),
        Outcome(HfrFavoriteSmileyMutationResult.NotFound, SmileyFavoriteFeedback.NotFound, listOf(other)),
        Outcome(HfrFavoriteSmileyMutationResult.InvalidToken, SmileyFavoriteFeedback.NotFound, listOf(other)),
        Outcome(HfrFavoriteSmileyMutationResult.NotAuthenticated,
            SmileyFavoriteFeedback.NotAuthenticated, listOf(other)),
        Outcome(HfrFavoriteSmileyMutationResult.UnknownResponse,
            SmileyFavoriteFeedback.UnknownResponse, listOf(other)),
        Outcome(HfrFavoriteSmileyMutationResult.TransportFailure,
            SmileyFavoriteFeedback.TransportFailure, listOf(other)),
    )

    private fun controller(
        scope: TestScope,
        form: () -> List<EditorSmiley> = { emptyList() },
        add: suspend (String) -> HfrFavoriteSmileyMutationResult = { error("Unexpected add") },
        remove: suspend (String) -> HfrFavoriteSmileyMutationResult = { error("Unexpected removal") },
    ) = SmileyPickerController(
        scope = scope,
        searchWiki = { _, _ -> emptyList() },
        favoriteSources = SmileyPickerFavoriteSources(
            favoriteSmileys = form,
            hfrFavoriteMutations = SmileyFavoriteMutations({ _, token -> add(token) }, { _, token -> remove(token) }),
            // This suite keeps one owner: a finite observation leaves runTest's scope free of collectors.
            localCollections = object : SmileyLocalCollections by FakePickerLocalCollections("tester") {
                override fun observe() = flowOf(SmileyLocalCollectionSnapshot("tester"))
            },
        ),
    ).also { scope.runCurrent(); it.open() }

    private fun SmileyPickerController.openState() = state.value as SmileyPickerState.Open
}
