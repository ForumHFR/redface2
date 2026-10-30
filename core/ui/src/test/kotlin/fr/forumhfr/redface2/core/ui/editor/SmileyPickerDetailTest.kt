package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.model.LocalFavoriteSmiley
import fr.forumhfr.redface2.core.model.SmileyUsageEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmileyPickerDetailTest {
    private val builtin = BUILTIN_HFR_SMILEYS.first()
    private val perso = EditorSmiley("[:fictif a:2]", "https://example.invalid/a.gif", EditorSmileySource.HFR_FAVORITE)
    private val locals = FakePickerLocalCollections()

    @Test
    fun `detail retains the touched smiley and back changes neither search nor visibility`() = runTest {
        val controller = controller(backgroundScope)
        controller.onDetailChanged(builtin)
        assertEquals(SmileyPickerState.Hidden, controller.state.value)
        controller.open()
        controller.onQueryChanged("fictif")
        advanceTimeBy(SmileyPickerController.SEARCH_DEBOUNCE_MS)
        runCurrent()
        val before = controller.openState()
        before.onDetailChange?.invoke(perso)
        assertSame(perso, controller.openState().detailSmiley)
        assertEquals(before.query, controller.openState().query)
        assertEquals(before.wiki, controller.openState().wiki)
        controller.openState().onDetailChange?.invoke(null)
        assertEquals(before, controller.openState())
    }

    @Test
    fun `dismissal and every opening discard the detail without losing the query`() = runTest {
        val controller = controller(backgroundScope)
        controller.open()
        controller.onQueryChanged("ab")
        controller.onDetailChanged(builtin)
        controller.open()
        assertNull(controller.openState().detailSmiley)
        assertEquals("ab", controller.openState().query)
        controller.onDetailChanged(perso)
        val staleCommand = controller.openState().onDetailChange
        controller.dismiss()
        staleCommand?.invoke(builtin)
        assertEquals(SmileyPickerState.Hidden, controller.state.value)
        controller.open()
        assertNull(controller.openState().detailSmiley)
        assertEquals("ab", controller.openState().query)
    }

    @Test
    fun `account switch and logout close detail and discard counts from the former owner`() = runTest {
        locals.snapshot.value = SmileyLocalCollectionSnapshot(
            "alice", top = listOf(SmileyUsageEntry(builtin.token, 4, 1)),
        )
        val controller = controller(backgroundScope)
        runCurrent()
        controller.open()
        listOf(SmileyLocalCollectionSnapshot("bob"), SmileyLocalCollectionSnapshot()).forEach { snapshot ->
            controller.onDetailChanged(builtin)
            locals.snapshot.value = snapshot
            runCurrent()
            assertNull(controller.openState().detailSmiley)
            assertEquals(emptyMap<String, Long>(), controller.openState().usageCounts)
        }
    }

    @Test
    fun `local and HFR removal keep the original target and recompute actions from current membership`() = runTest {
        locals.snapshot.value = SmileyLocalCollectionSnapshot(
            "alice", favorites = listOf(LocalFavoriteSmiley(perso.token, perso.imageUrl, 1)),
        )
        val controller = controller(backgroundScope)
        runCurrent()
        controller.open()
        controller.onDetailChanged(perso)
        assertEquals(SmileyFavoriteAction.REMOVE, controller.openState().localFavoriteAction(perso))
        controller.openState().onLocalFavoriteChange?.invoke(perso)
        runCurrent()
        assertSame(perso, controller.openState().detailSmiley)
        assertEquals(SmileyFavoriteAction.ADD, controller.openState().localFavoriteAction(perso))
        assertEquals(SmileyFavoriteFeedback.LocalRemoved, controller.openState().favoriteFeedback)
        controller.onFavoriteClicked(perso)
        assertNull(controller.openState().favoriteAction(perso))
        assertEquals(SmileyFavoriteAction.REMOVE, controller.openState().favoriteAction(perso, includePending = true))
        runCurrent()
        assertSame(perso, controller.openState().detailSmiley)
        assertEquals(SmileyFavoriteAction.ADD, controller.openState().favoriteAction(perso))
        assertEquals(SmileyFavoriteFeedback.Removed, controller.openState().favoriteFeedback)
    }

    @Test
    fun `counts include the 512th token outside Top and update without another detail lookup`() = runTest {
        val entries = (1..512).map { SmileyUsageEntry("[:fictif_$it]", (513 - it).toLong(), 1) }
        locals.snapshot.value = SmileyLocalCollectionSnapshot(
            "alice", top = entries.take(24), usageCounts = entries.associate { it.token to it.count },
        )
        val controller = controller(backgroundScope)
        runCurrent()
        controller.open()
        val last = perso.copy(token = entries.last().token)
        controller.onDetailChanged(last)
        assertEquals(24, controller.openState().mostUsedSmileys.size)
        assertEquals(1L, controller.openState().usageCounts[last.token])
        assertNull(controller.openState().usageCounts[builtin.token])
        locals.snapshot.value = locals.snapshot.value.copy(usageCounts = mapOf(last.token to Long.MAX_VALUE))
        runCurrent()
        assertSame(last, controller.openState().detailSmiley)
        assertEquals(Long.MAX_VALUE, controller.openState().usageCounts[last.token])
    }

    private fun controller(scope: CoroutineScope) = SmileyPickerController(
        scope, { _, _ -> listOf(perso) },
        favoriteSources = SmileyPickerFavoriteSources(
            favoriteSmileys = { listOf(perso) },
            hfrFavoriteMutations = SmileyFavoriteMutations(
                { HfrFavoriteSmileyMutationResult.Success(listOf(perso)) },
                { HfrFavoriteSmileyMutationResult.Success(emptyList()) },
            ),
            localCollections = locals,
        ),
    )

    private fun SmileyPickerController.openState() = state.value as SmileyPickerState.Open
}
