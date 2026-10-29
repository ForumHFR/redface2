package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyFailure
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.model.LocalFavoriteSmiley
import fr.forumhfr.redface2.core.model.SmileyUsageEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmileyPickerLocalCollectionsTest {
    private val builtin = BUILTIN_HFR_SMILEYS.first()
    private val perso = EditorSmiley("[:fictif a:2]", "https://example.invalid/a.gif", EditorSmileySource.WIKI)
    private val hfrFailures = listOf(
        HfrFavoriteSmileyMutationResult.Full(7, emptyList()) to SmileyFavoriteFeedback.Full(7),
        HfrFavoriteSmileyMutationResult.TransportFailure to SmileyFavoriteFeedback.TransportFailure,
    )
    private val localFailures = listOf(
        LocalFavoriteSmileyMutationResult.LimitReached to SmileyFavoriteFeedback.LocalLimitReached,
        LocalFavoriteSmileyMutationResult.Failure(LocalFavoriteSmileyFailure.STORAGE_FAILURE) to
            SmileyFavoriteFeedback.LocalStorageFailure,
    )

    @Test
    fun `initial tab uses any known collection and restored Wiki always wins`() = runTest {
        val locals = FakePickerLocalCollections()
        val controller = SmileyPickerController(backgroundScope, { _, _ -> emptyList() }, localCollections = locals)
        runCurrent()
        controller.open()
        assertEquals(SmileyPickerTab.STANDARD, controller.openState().initialTab)
        val snapshots = listOf(
            SmileyLocalCollectionSnapshot("alice", favorites = listOf(LocalFavoriteSmiley(builtin.token, addedAt = 1))),
            SmileyLocalCollectionSnapshot("alice", top = listOf(SmileyUsageEntry(builtin.token, 3, 1))),
        )
        snapshots.forEachIndexed { index, snapshot ->
            locals.snapshot.value = snapshot
            runCurrent()
            // Late data does not retake selection during this opening.
            if (index == 0) assertEquals(SmileyPickerTab.STANDARD, controller.openState().initialTab)
            controller.dismiss()
            controller.open()
            assertEquals(SmileyPickerTab.FAVORITES, controller.openState().initialTab)
        }
        controller.onQueryChanged("fictif")
        controller.dismiss()
        controller.open()
        assertEquals(SmileyPickerTab.WIKI, controller.openState().initialTab)
        val hfr = SmileyPickerController(backgroundScope, { _, _ -> emptyList() }, favoriteSmileys = { listOf(perso) })
        hfr.open()
        assertEquals(SmileyPickerTab.FAVORITES, hfr.openState().initialTab)
    }

    @Test
    fun `unresolvable rows are omitted but a known collection still selects Favorites`() = runTest {
        val locals = FakePickerLocalCollections()
        locals.snapshot.value = SmileyLocalCollectionSnapshot(
            "alice", favorites = listOf(LocalFavoriteSmiley("unresolvable", addedAt = 1)),
        )
        val controller = SmileyPickerController(backgroundScope, { _, _ -> emptyList() }, localCollections = locals)
        runCurrent()
        controller.open()
        assertEquals(SmileyPickerTab.FAVORITES, controller.openState().initialTab)
        assertEquals(emptyList<EditorSmiley>(), controller.openState().localFavoriteSmileys)
        assertEquals(setOf("unresolvable"), controller.openState().localFavoriteTokens)
    }

    @Test
    fun `sections keep repository order and resolve saved known builtin and variant images`() = runTest {
        val locals = FakePickerLocalCollections()
        locals.snapshot.value = SmileyLocalCollectionSnapshot(
            "alice",
            listOf(LocalFavoriteSmiley(perso.token, "https://example.invalid/saved.gif", 1)),
            listOf(SmileyUsageEntry(perso.token, 4, 1), SmileyUsageEntry(builtin.token, 3, 1)),
        )
        val controller = SmileyPickerController(
            backgroundScope, { _, _ -> listOf(perso) }, favoriteSmileys = { listOf(perso) }, localCollections = locals,
        )
        runCurrent()
        controller.open()
        val open = controller.openState()
        assertEquals("https://example.invalid/saved.gif", open.localFavoriteSmileys.single().imageUrl)
        assertEquals(listOf(perso.token, builtin.token), open.mostUsedSmileys.map { it.token })
        assertEquals(perso.imageUrl, open.mostUsedSmileys.first().imageUrl)
        assertEquals(builtin, open.mostUsedSmileys.last())
        val resolver = SmileyImageResolver(emptyList())
        assertEquals(
            "https://forum-images.hardware.fr/images/perso/2/fictif a.gif", resolver.resolve(perso.token)?.imageUrl,
        )
        assertEquals(
            "https://forum-images.hardware.fr/images/perso/fictif a.gif", resolver.resolve("[:fictif a]")?.imageUrl,
        )
        assertEquals(
            "https://forum-images.hardware.fr/images/perso/fictif:interne.gif",
            resolver.resolve("[:fictif:interne]")?.imageUrl,
        )
        assertEquals(
            "https://forum-images.hardware.fr/images/perso/2/fictif:interne.gif",
            resolver.resolve("[:fictif:interne:2]")?.imageUrl,
        )
        assertNull(resolver.resolve("not a smiley"))
    }

    @Test
    fun `local gesture captures owner before coroutine and account switch replaces every local row`() = runTest {
        val locals = FakePickerLocalCollections()
        locals.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        val controller = SmileyPickerController(backgroundScope, { _, _ -> emptyList() }, localCollections = locals)
        runCurrent()
        controller.open()
        controller.openState().onLocalFavoriteChange?.invoke(builtin)
        locals.snapshot.value = SmileyLocalCollectionSnapshot(
            "bob", favorites = listOf(LocalFavoriteSmiley(perso.token, perso.imageUrl, 1)),
        )
        runCurrent()
        assertEquals(listOf(Triple("alice", builtin.token, builtin.imageUrl)), locals.calls)
        assertEquals(listOf(perso.token), controller.openState().localFavoriteSmileys.map { it.token })
        assertNull(controller.openState().favoriteFeedback)
        controller.openState().onLocalFavoriteChange?.invoke(perso)
        runCurrent()
        assertEquals(Triple("bob", perso.token, null), locals.calls.last())
        assertEquals(SmileyFavoriteFeedback.LocalRemoved, controller.openState().favoriteFeedback)
        val staleCommand = controller.openState().onLocalFavoriteChange
        locals.snapshot.value = SmileyLocalCollectionSnapshot()
        runCurrent()
        assertNull(controller.openState().onLocalFavoriteChange)
        assertEquals(emptyList<EditorSmiley>(), controller.openState().localFavoriteSmileys)
        assertEquals(emptyList<EditorSmiley>(), controller.openState().mostUsedSmileys)
        staleCommand?.invoke(builtin)
        runCurrent()
        assertEquals(2, locals.calls.size)
    }

    @Test
    fun `late Wiki images resolve a local favorite and Top without changing the initial tab`() = runTest {
        val locals = FakePickerLocalCollections()
        locals.snapshot.value = SmileyLocalCollectionSnapshot(
            "alice", listOf(LocalFavoriteSmiley(perso.token, addedAt = 1)),
            listOf(SmileyUsageEntry(perso.token, 2, 1)),
        )
        val controller = SmileyPickerController(backgroundScope, { _, _ -> listOf(perso) }, localCollections = locals)
        runCurrent()
        controller.open()
        assertEquals(EditorSmileySource.WIKI, controller.openState().localFavoriteSmileys.single().source)
        controller.onQueryChanged("fictif")
        advanceTimeBy(SmileyPickerController.SEARCH_DEBOUNCE_MS)
        runCurrent()
        assertEquals(perso.imageUrl, controller.openState().localFavoriteSmileys.single().imageUrl)
        assertEquals(perso.imageUrl, controller.openState().mostUsedSmileys.single().imageUrl)
        assertEquals(SmileyPickerTab.FAVORITES, controller.openState().initialTab)
    }

    @Test
    fun `local mutation remains available during HFR mutation and results share feedback generations`() = runTest {
        val locals = FakePickerLocalCollections()
        locals.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = SmileyPickerController(
            backgroundScope, { _, _ -> emptyList() },
            hfrFavoriteMutations = SmileyFavoriteMutations({ gate.await() }, { gate.await() }),
            localCollections = locals,
        )
        runCurrent()
        controller.open()
        controller.onFavoriteClicked(perso)
        val generation = controller.openState().favoriteFeedbackGeneration
        assertNotNull(controller.openState().localFavoriteAction(builtin))
        controller.openState().onLocalFavoriteChange?.invoke(builtin)
        runCurrent()
        assertEquals(SmileyFavoriteFeedback.LocalAdded, controller.openState().favoriteFeedback)
        assertEquals(generation + 1, controller.openState().favoriteFeedbackGeneration)
        gate.complete(HfrFavoriteSmileyMutationResult.Success(listOf(perso)))
        runCurrent()
        assertEquals(SmileyFavoriteFeedback.LocalAdded, controller.openState().favoriteFeedback)
    }

    @Test
    fun `late HFR failures replace newer local success and restart the feedback generation`() = runTest {
        hfrFailures.forEach { (result, feedback) ->
            val locals = FakePickerLocalCollections()
            locals.snapshot.value = SmileyLocalCollectionSnapshot("alice")
            val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
            val controller = SmileyPickerController(
                backgroundScope, { _, _ -> emptyList() },
                hfrFavoriteMutations = SmileyFavoriteMutations({ gate.await() }, { gate.await() }),
                localCollections = locals,
            )
            runCurrent()
            controller.open()
            controller.onFavoriteClicked(perso)
            runCurrent()
            assertEquals(listOf(perso.token), controller.openState().favoriteSmileys.map { it.token })
            assertEquals(perso.token, controller.openState().pendingFavoriteToken)
            controller.openState().onLocalFavoriteChange?.invoke(builtin)
            runCurrent()
            assertEquals(SmileyFavoriteFeedback.LocalAdded, controller.openState().favoriteFeedback)
            val generation = controller.openState().favoriteFeedbackGeneration

            gate.complete(result)
            runCurrent()
            assertEquals(result.toString(), feedback, controller.openState().favoriteFeedback)
            assertEquals(generation + 1, controller.openState().favoriteFeedbackGeneration)
            assertEquals(emptyList<EditorSmiley>(), controller.openState().favoriteSmileys)
            assertNull(controller.openState().pendingFavoriteToken)
            assertEquals(listOf(builtin), controller.openState().localFavoriteSmileys)
        }
    }

    @Test
    fun `late local failures replace newer HFR success and restart the feedback generation`() = runTest {
        localFailures.forEach { (result, feedback) ->
            val locals = FakePickerLocalCollections()
            locals.snapshot.value = SmileyLocalCollectionSnapshot("alice")
            val gate = CompletableDeferred<LocalFavoriteSmileyMutationResult>()
            locals.addResult = { gate.await() }
            val controller = SmileyPickerController(
                backgroundScope, { _, _ -> emptyList() },
                hfrFavoriteMutations = SmileyFavoriteMutations(
                    { HfrFavoriteSmileyMutationResult.Success(listOf(perso)) },
                    { HfrFavoriteSmileyMutationResult.Success(emptyList()) },
                ),
                localCollections = locals,
            )
            runCurrent()
            controller.open()
            controller.openState().onLocalFavoriteChange?.invoke(builtin)
            runCurrent()
            controller.onFavoriteClicked(perso)
            runCurrent()
            assertEquals(SmileyFavoriteFeedback.Added, controller.openState().favoriteFeedback)
            val generation = controller.openState().favoriteFeedbackGeneration

            gate.complete(result)
            runCurrent()
            assertEquals(result.toString(), feedback, controller.openState().favoriteFeedback)
            assertEquals(generation + 1, controller.openState().favoriteFeedbackGeneration)
            assertEquals(listOf(perso), controller.openState().favoriteSmileys)
            assertEquals(emptyList<EditorSmiley>(), controller.openState().localFavoriteSmileys)
        }
    }

    @Test
    fun `late HFR failures stay silent across account changes and returns`() = runTest {
        listOf("bob", "alice").forEach { completionOwner ->
            hfrFailures.forEach { (result, _) ->
                val locals = FakePickerLocalCollections()
                locals.snapshot.value = SmileyLocalCollectionSnapshot("alice")
                val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
                val controller = SmileyPickerController(
                    backgroundScope, { _, _ -> emptyList() },
                    hfrFavoriteMutations = SmileyFavoriteMutations({ gate.await() }, { gate.await() }),
                    localCollections = locals,
                )
                runCurrent()
                controller.open()
                controller.onFavoriteClicked(perso)
                runCurrent()
                locals.snapshot.value = SmileyLocalCollectionSnapshot("bob")
                runCurrent()
                locals.snapshot.value = SmileyLocalCollectionSnapshot(completionOwner)
                runCurrent()
                controller.openState().onLocalFavoriteChange?.invoke(builtin)
                runCurrent()
                val generation = controller.openState().favoriteFeedbackGeneration
                assertEquals(SmileyFavoriteFeedback.LocalAdded, controller.openState().favoriteFeedback)

                gate.complete(result)
                runCurrent()
                assertEquals(SmileyFavoriteFeedback.LocalAdded, controller.openState().favoriteFeedback)
                assertEquals(generation, controller.openState().favoriteFeedbackGeneration)
                assertNull(controller.openState().pendingFavoriteToken)
            }
        }
    }

    @Test
    fun `late local failures stay silent across account changes and returns`() = runTest {
        listOf("bob", "alice").forEach { completionOwner ->
            localFailures.forEach { (result, _) ->
                val locals = FakePickerLocalCollections()
                locals.snapshot.value = SmileyLocalCollectionSnapshot("alice")
                val gate = CompletableDeferred<LocalFavoriteSmileyMutationResult>()
                locals.addResult = { gate.await() }
                val controller = SmileyPickerController(
                    backgroundScope, { _, _ -> emptyList() },
                    hfrFavoriteMutations = SmileyFavoriteMutations(
                        { HfrFavoriteSmileyMutationResult.Success(listOf(perso)) },
                        { HfrFavoriteSmileyMutationResult.Success(emptyList()) },
                    ),
                    localCollections = locals,
                )
                runCurrent()
                controller.open()
                controller.openState().onLocalFavoriteChange?.invoke(builtin)
                runCurrent()
                locals.snapshot.value = SmileyLocalCollectionSnapshot("bob")
                runCurrent()
                locals.snapshot.value = SmileyLocalCollectionSnapshot(completionOwner)
                runCurrent()
                controller.onFavoriteClicked(perso)
                runCurrent()
                val generation = controller.openState().favoriteFeedbackGeneration
                assertEquals(SmileyFavoriteFeedback.Added, controller.openState().favoriteFeedback)

                gate.complete(result)
                runCurrent()
                assertEquals(SmileyFavoriteFeedback.Added, controller.openState().favoriteFeedback)
                assertEquals(generation, controller.openState().favoriteFeedbackGeneration)
            }
        }
    }

    @Test
    fun `local limit explicit failure and exception produce classified feedback`() = runTest {
        val locals = FakePickerLocalCollections()
        locals.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        val controller = SmileyPickerController(backgroundScope, { _, _ -> emptyList() }, localCollections = locals)
        runCurrent()
        controller.open()
        val outcomes = listOf(
            LocalFavoriteSmileyMutationResult.LimitReached to SmileyFavoriteFeedback.LocalLimitReached,
            LocalFavoriteSmileyMutationResult.Failure(LocalFavoriteSmileyFailure.STORAGE_FAILURE) to
                SmileyFavoriteFeedback.LocalStorageFailure,
        )
        outcomes.forEach { (result, feedback) ->
            locals.addResult = { result }
            controller.openState().onLocalFavoriteChange?.invoke(builtin)
            runCurrent()
            assertEquals(feedback, controller.openState().favoriteFeedback)
        }
        locals.addResult = { error("storage unavailable") }
        controller.openState().onLocalFavoriteChange?.invoke(builtin)
        runCurrent()
        assertEquals(SmileyFavoriteFeedback.LocalStorageFailure, controller.openState().favoriteFeedback)
        controller.dismiss()
        controller.open()
        assertNull(controller.openState().favoriteFeedback)
    }

    @Test
    fun `local write in flight does not block a HFR gesture`() = runTest {
        val locals = FakePickerLocalCollections()
        locals.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        val localGate = CompletableDeferred<LocalFavoriteSmileyMutationResult>()
        locals.addResult = { localGate.await() }
        val hfrCalls = mutableListOf<String>()
        val controller = SmileyPickerController(
            backgroundScope, { _, _ -> emptyList() },
            hfrFavoriteMutations = SmileyFavoriteMutations(
                { token -> hfrCalls += token; HfrFavoriteSmileyMutationResult.Success(listOf(perso)) },
                { HfrFavoriteSmileyMutationResult.Success(emptyList()) },
            ),
            localCollections = locals,
        )
        runCurrent()
        controller.open()
        controller.openState().onLocalFavoriteChange?.invoke(builtin)
        runCurrent()
        assertEquals(SmileyFavoriteAction.ADD, controller.openState().favoriteAction(perso))
        controller.onFavoriteClicked(perso)
        runCurrent()
        assertEquals(listOf(perso.token), hfrCalls)
        assertEquals(SmileyFavoriteFeedback.Added, controller.openState().favoriteFeedback)
        localGate.complete(LocalFavoriteSmileyMutationResult.Added)
        runCurrent()
        assertEquals(SmileyFavoriteFeedback.Added, controller.openState().favoriteFeedback)
    }

    @Test
    fun `hidden local successes are silent and hidden failures last for one opening`() = runTest {
        val locals = FakePickerLocalCollections()
        locals.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        val controller = SmileyPickerController(backgroundScope, { _, _ -> emptyList() }, localCollections = locals)
        runCurrent()
        controller.open()
        val success = CompletableDeferred<LocalFavoriteSmileyMutationResult>()
        locals.addResult = { success.await() }
        controller.openState().onLocalFavoriteChange?.invoke(builtin)
        controller.dismiss()
        success.complete(LocalFavoriteSmileyMutationResult.Added)
        runCurrent()
        controller.open()
        assertNull(controller.openState().favoriteFeedback)
        val failure = CompletableDeferred<LocalFavoriteSmileyMutationResult>()
        locals.removeResult = { failure.await() }
        controller.openState().onLocalFavoriteChange?.invoke(builtin)
        controller.dismiss()
        failure.complete(LocalFavoriteSmileyMutationResult.Failure(LocalFavoriteSmileyFailure.STORAGE_FAILURE))
        runCurrent()
        controller.open()
        assertEquals(SmileyFavoriteFeedback.LocalStorageFailure, controller.openState().favoriteFeedback)
        controller.dismiss()
        controller.open()
        assertNull(controller.openState().favoriteFeedback)
    }

    private fun SmileyPickerController.openState() = state.value as SmileyPickerState.Open
}
