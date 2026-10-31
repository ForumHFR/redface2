package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.model.LocalFavoriteSmiley
import fr.forumhfr.redface2.core.model.SmileyUsageEntry
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmileyPickerAccountIsolationTest {
    private val alice = EditorSmiley(
        "[:alice_fictif]", "https://example.invalid/a.gif", EditorSmileySource.HFR_FAVORITE,
    )
    private val bob = alice.copy(token = "[:bob_fictif]", imageUrl = "https://example.invalid/b.gif")
    private val wiki = alice.copy(token = "[:wiki_fictif]", source = EditorSmileySource.WIKI)
    private val favorite = wiki.copy(source = EditorSmileySource.HFR_FAVORITE)
    private val builtin = BUILTIN_HFR_SMILEYS.first()
    private val ownerPaths = listOf(listOf<String?>("bob"), listOf(null, "bob"), listOf("bob", "alice"))
    private val lateResults = listOf(
        HfrFavoriteSmileyMutationResult.Success(listOf(alice, favorite)),
        HfrFavoriteSmileyMutationResult.Full(10, listOf(alice)),
        HfrFavoriteSmileyMutationResult.TransportFailure,
    )

    @Test
    fun `initial form can load before or after the first owner snapshot`() = runTest {
        val locals = aliceCollections()
        val controller = controller(backgroundScope, locals, { listOf(alice) }) {
            HfrFavoriteSmileyMutationResult.Success(listOf(alice, favorite))
        }
        controller.refreshFavorites(controller.favoriteFormStamp())
        runCurrent()
        assertEquals(listOf(alice), controller.openState().favoriteSmileys)
        controller.refreshFavorites(controller.favoriteFormStamp())
        assertEquals(SmileyFavoriteAction.REMOVE, controller.openState().favoriteAction(alice))
        controller.onFavoriteClicked(wiki)
        runCurrent()
        assertEquals(listOf(alice, favorite), controller.openState().favoriteSmileys)
        assertEquals(SmileyFavoriteFeedback.Added, controller.openState().favoriteFeedback)
        assertNull(controller.openState().pendingFavoriteToken)
    }

    @Test
    fun `form started before an owner change cannot authorize its late favorites or commands`() = runTest {
        ownerPaths.forEach { owners ->
            val locals = aliceCollections()
            var form = emptyList<EditorSmiley>()
            val calls = mutableListOf<String>()
            val controller = controller(backgroundScope, locals, { form }) {
                calls += it
                HfrFavoriteSmileyMutationResult.Success(emptyList())
            }
            runCurrent()
            val stamp = controller.favoriteFormStamp()
            val gate = CompletableDeferred<List<EditorSmiley>>()
            backgroundScope.launch {
                form = gate.await()
                controller.refreshFavorites(stamp)
            }
            runCurrent()
            owners.forEach { owner ->
                locals.snapshot.value = ownerSnapshot(owner)
                runCurrent()
            }
            gate.complete(listOf(alice))
            runCurrent()
            controller.assertHfrUnavailable()
            controller.onFavoriteClicked(alice)
            controller.onFavoriteClicked(wiki)
            controller.dismiss()
            controller.open()
            controller.assertHfrUnavailable()
            runCurrent()
            assertEquals(emptyList<String>(), calls)

            form = listOf(bob)
            controller.refreshFavorites(controller.favoriteFormStamp())
            assertEquals(listOf(bob), controller.openState().favoriteSmileys)
            assertNotNull(controller.openState().onFavoriteChange)
        }
    }

    @Test
    fun `without account observation HFR commands are unavailable even with a form and callbacks`() = runTest {
        val calls = mutableListOf<String>()
        val mutation: suspend (String, String) -> HfrFavoriteSmileyMutationResult = { _, token ->
            calls += token
            HfrFavoriteSmileyMutationResult.Success(emptyList())
        }
        val controller = SmileyPickerController(
            backgroundScope, { _, _ -> emptyList() },
            favoriteSources = SmileyPickerFavoriteSources(
                favoriteSmileys = { listOf(alice) },
                hfrFavoriteMutations = SmileyFavoriteMutations(mutation, mutation),
            ),
        )
        controller.refreshFavorites(controller.favoriteFormStamp())
        controller.open()
        assertNull(controller.openState().onFavoriteChange)
        assertNull(controller.openState().favoriteAction(alice))
        assertNull(controller.openState().favoriteAction(wiki))
        controller.onFavoriteClicked(alice)
        controller.onFavoriteClicked(wiki)
        runCurrent()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `HFR callbacks receive the owner captured at the gesture`() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val mutation: suspend (String, String) -> HfrFavoriteSmileyMutationResult = { owner, token ->
            calls += owner to token
            HfrFavoriteSmileyMutationResult.AccountChanged
        }
        val controller = SmileyPickerController(
            backgroundScope, { _, _ -> emptyList() },
            favoriteSources = SmileyPickerFavoriteSources(
                favoriteSmileys = { listOf(alice) },
                hfrFavoriteMutations = SmileyFavoriteMutations(mutation, mutation),
                localCollections = aliceCollections(),
            ),
        )
        runCurrent()
        controller.open()
        controller.onFavoriteClicked(alice)
        runCurrent()
        controller.onFavoriteClicked(wiki)
        runCurrent()
        assertEquals(listOf("alice" to alice.token, "alice" to wiki.token), calls)
        assertNull(controller.openState().favoriteFeedback)
    }

    @Test
    fun `retained form and confirmed snapshot stay unavailable until a new owner form arrives`() = runTest {
        ownerPaths.forEach { owners ->
            val locals = aliceCollections()
            var form = listOf(alice)
            val calls = mutableListOf<String>()
            val controller = controller(backgroundScope, locals, { form }) {
                calls += it
                HfrFavoriteSmileyMutationResult.Success(listOf(alice, favorite))
            }
            runCurrent()
            controller.onFavoriteClicked(wiki)
            runCurrent()
            assertEquals(listOf(alice, favorite), controller.openState().favoriteSmileys)
            assertEquals(SmileyFavoriteFeedback.Added, controller.openState().favoriteFeedback)
            val staleCommand = requireNotNull(controller.openState().onFavoriteChange)

            owners.forEach { owner ->
                controller.onDetailChanged(alice)
                locals.snapshot.value = SmileyLocalCollectionSnapshot(owner)
                runCurrent()
                controller.assertHfrUnavailable()
                locals.snapshot.value = ownerSnapshot(owner)
                runCurrent()
                assertNull(controller.openState().detailSmiley)
                controller.assertHfrUnavailable()
                assertNull(controller.openState().favoriteFeedback)
                assertNull(controller.openState().favoriteFeedbackToken)
                val expectedLocal = if (owner == null) emptyList() else listOf(builtin)
                assertEquals(expectedLocal, controller.openState().localFavoriteSmileys)
                assertEquals(expectedLocal, controller.openState().mostUsedSmileys)
                controller.onDetailChanged(wiki)
                assertNull(controller.openState().favoriteAction(wiki, includePending = true))
                staleCommand(alice)
                controller.onFavoriteClicked(wiki)
                controller.dismiss()
                controller.open()
                controller.assertHfrUnavailable()
                if (owner == null) {
                    controller.refreshFavorites(controller.favoriteFormStamp())
                    controller.assertHfrUnavailable()
                }
            }
            runCurrent()
            assertEquals(listOf(wiki.token), calls)
            assertEquals(listOf(builtin), controller.openState().localFavoriteSmileys)
            assertEquals(listOf(builtin), controller.openState().mostUsedSmileys)
            assertNotNull(controller.openState().onLocalFavoriteChange)

            form = listOf(bob)
            controller.open()
            controller.assertHfrUnavailable()
            controller.refreshFavorites(controller.favoriteFormStamp())
            assertEquals(listOf(bob), controller.openState().favoriteSmileys)
            assertEquals(SmileyFavoriteAction.REMOVE, controller.openState().favoriteAction(bob))
            assertEquals(SmileyFavoriteAction.ADD, controller.openState().favoriteAction(wiki))
        }
    }

    @Test
    fun `late mutation lists and failures stay silent across account changes and returns`() = runTest {
        ownerPaths.forEach { owners ->
            lateResults.forEach { result ->
                val locals = aliceCollections()
                var form = listOf(alice)
                val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
                val controller = controller(backgroundScope, locals, { form }) { gate.await() }
                runCurrent()
                controller.onFavoriteClicked(wiki)
                runCurrent()
                assertEquals(wiki.token, controller.openState().pendingFavoriteToken)
                owners.forEach { owner ->
                    locals.snapshot.value = ownerSnapshot(owner)
                    runCurrent()
                }
                controller.assertHfrUnavailable()
                controller.openState().onLocalFavoriteChange?.invoke(builtin)
                runCurrent()
                val before = controller.openState()
                assertEquals(SmileyFavoriteFeedback.LocalRemoved, before.favoriteFeedback)

                gate.complete(result)
                runCurrent()
                assertEquals(result.toString(), before, controller.openState())
                controller.dismiss()
                controller.open()
                controller.assertHfrUnavailable()
                assertNull(controller.openState().favoriteFeedback)

                form = listOf(bob)
                controller.refreshFavorites(controller.favoriteFormStamp())
                assertEquals(listOf(bob), controller.openState().favoriteSmileys)
            }
        }
    }

    @Test
    fun `late add and removal results cannot overwrite a new owner form`() = runTest {
        listOf(wiki, alice).forEach { smiley ->
            lateResults.forEach { result ->
                val locals = aliceCollections()
                var form = listOf(alice)
                val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
                val controller = controller(backgroundScope, locals, { form }) { gate.await() }
                runCurrent()
                controller.onFavoriteClicked(smiley)
                runCurrent()
                locals.snapshot.value = ownerSnapshot("bob")
                runCurrent()
                form = listOf(bob)
                controller.refreshFavorites(controller.favoriteFormStamp())
                val before = controller.openState()

                gate.complete(result)
                runCurrent()
                assertEquals(result.toString(), before, controller.openState())
                controller.dismiss()
                controller.open()
                assertEquals(listOf(bob), controller.openState().favoriteSmileys)
                assertNull(controller.openState().favoriteFeedback)
            }
        }
    }

    @Test
    fun `old completion cannot replace the new form or clear its pending mutation`() = runTest {
        lateResults.forEach { result ->
            val locals = aliceCollections()
            var form = listOf(alice)
            val aliceGate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
            val bobGate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
            var gate = aliceGate
            val controller = controller(backgroundScope, locals, { form }) { gate.await() }
            runCurrent()
            controller.onFavoriteClicked(wiki)
            runCurrent()
            locals.snapshot.value = ownerSnapshot("bob")
            runCurrent()
            form = listOf(bob)
            controller.refreshFavorites(controller.favoriteFormStamp())
            gate = bobGate
            controller.onFavoriteClicked(wiki)
            runCurrent()
            val before = controller.openState()
            assertEquals(listOf(bob, favorite), before.favoriteSmileys)
            assertEquals(wiki.token, before.pendingFavoriteToken)

            aliceGate.complete(result)
            runCurrent()
            assertEquals(result.toString(), before, controller.openState())
            controller.dismiss()
            controller.open()
            assertEquals(listOf(bob, favorite), controller.openState().favoriteSmileys)
            assertEquals(wiki.token, controller.openState().pendingFavoriteToken)
            assertNull(controller.openState().favoriteFeedback)

            bobGate.complete(HfrFavoriteSmileyMutationResult.Success(listOf(bob, favorite)))
            runCurrent()
            assertEquals(listOf(bob, favorite), controller.openState().favoriteSmileys)
            assertEquals(SmileyFavoriteFeedback.Added, controller.openState().favoriteFeedback)
            assertNull(controller.openState().pendingFavoriteToken)
        }
    }

    @Test
    fun `a late transport exception cannot publish feedback on the next account`() = runTest {
        val locals = aliceCollections()
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(backgroundScope, locals, { listOf(alice) }) { gate.await() }
        runCurrent()
        controller.onFavoriteClicked(alice)
        runCurrent()
        controller.dismiss()
        locals.snapshot.value = ownerSnapshot("bob")
        runCurrent()
        gate.completeExceptionally(IOException("transport unavailable"))
        runCurrent()
        assertEquals(SmileyPickerState.Hidden, controller.state.value)
        controller.open()
        controller.assertHfrUnavailable()
        assertNull(controller.openState().favoriteFeedback)
        assertNull(controller.openState().favoriteFeedbackToken)
    }

    @Test
    fun `queued add and removal cannot start after the account changes`() = runTest {
        listOf(wiki, alice).forEach { smiley ->
            val locals = aliceCollections()
            val calls = mutableListOf<String>()
            val controller = controller(backgroundScope, locals, { listOf(alice) }) {
                calls += it
                HfrFavoriteSmileyMutationResult.Success(emptyList())
            }
            runCurrent()
            // Schedule the owner emission first, without letting the picker collect it yet.
            locals.snapshot.value = ownerSnapshot("bob")
            controller.onFavoriteClicked(smiley)
            assertEquals(smiley.token, controller.openState().pendingFavoriteToken)
            runCurrent()
            assertEquals(emptyList<String>(), calls)
            controller.assertHfrUnavailable()
            assertNull(controller.openState().favoriteFeedback)
        }
    }

    @Test
    fun `new empty form reenables normal add and removal for the next owner`() = runTest {
        val locals = aliceCollections()
        var form = listOf(alice)
        var confirmed = listOf(favorite)
        val calls = mutableListOf<String>()
        val controller = controller(backgroundScope, locals, { form }) {
            calls += it
            HfrFavoriteSmileyMutationResult.Success(confirmed)
        }
        runCurrent()
        locals.snapshot.value = ownerSnapshot("bob")
        runCurrent()
        controller.assertHfrUnavailable()
        form = emptyList()
        controller.refreshFavorites(controller.favoriteFormStamp())
        assertEquals(SmileyFavoriteAction.ADD, controller.openState().favoriteAction(wiki))
        controller.onFavoriteClicked(wiki)
        runCurrent()
        assertEquals(listOf(favorite), controller.openState().favoriteSmileys)
        assertEquals(SmileyFavoriteFeedback.Added, controller.openState().favoriteFeedback)
        assertEquals(SmileyFavoriteAction.REMOVE, controller.openState().favoriteAction(wiki))
        confirmed = emptyList()
        controller.onFavoriteClicked(wiki)
        runCurrent()
        assertEquals(emptyList<EditorSmiley>(), controller.openState().favoriteSmileys)
        assertEquals(SmileyFavoriteFeedback.Removed, controller.openState().favoriteFeedback)
        assertNull(controller.openState().pendingFavoriteToken)
        assertEquals(listOf(wiki.token, wiki.token), calls)
    }

    private fun aliceCollections() = FakePickerLocalCollections().also {
        it.snapshot.value = SmileyLocalCollectionSnapshot("alice")
    }

    private fun ownerSnapshot(owner: String?) = if (owner == null) {
        SmileyLocalCollectionSnapshot()
    } else {
        SmileyLocalCollectionSnapshot(
            owner, favorites = listOf(LocalFavoriteSmiley(builtin.token, addedAt = 1)),
            top = listOf(SmileyUsageEntry(builtin.token, 3, 1)),
        )
    }

    private fun controller(
        scope: CoroutineScope,
        locals: FakePickerLocalCollections,
        form: () -> List<EditorSmiley>,
        mutation: suspend (String) -> HfrFavoriteSmileyMutationResult,
    ) = SmileyPickerController(
        scope, { _, _ -> emptyList() },
        favoriteSources = SmileyPickerFavoriteSources(
            favoriteSmileys = form,
            hfrFavoriteMutations = SmileyFavoriteMutations(
                { _, token -> mutation(token) }, { _, token -> mutation(token) },
            ),
            localCollections = locals,
        ),
    ).also { it.open() }

    private fun SmileyPickerController.assertHfrUnavailable() {
        val open = openState()
        assertEquals(emptyList<EditorSmiley>(), open.favoriteSmileys)
        assertEquals(emptySet<String>(), open.accountSmileyTokens)
        assertNull(open.pendingFavoriteToken)
        assertNull(open.onFavoriteChange)
        assertNull(open.favoriteAction(alice))
        assertNull(open.favoriteAction(wiki))
    }

    private fun SmileyPickerController.openState() = state.value as SmileyPickerState.Open
}
