package fr.forumhfr.redface2.core.data.smiley

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.LocalFavoriteSmiley
import fr.forumhfr.redface2.core.model.SmileyUsageEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSmileyLocalCollectionsTest {
    private val auth = mockk<AuthRepository>()
    private val favorites = mockk<LocalFavoriteSmileyRepository>()
    private val usage = mockk<SmileyUsageRepository>()
    private val state = MutableStateFlow<AuthState>(AuthState.Authenticated(" Alice "))

    @Test
    fun `normalization keeps spaces and switching cancels former owner collections`() = runTest {
        every { auth.observeAuthState() } returns state
        val alice = MutableStateFlow(listOf(LocalFavoriteSmiley(":o", addedAt = 1)))
        val bob = MutableStateFlow(listOf(LocalFavoriteSmiley(":D", addedAt = 2)))
        every { favorites.observeFavorites(" alice ") } returns alice
        every { favorites.observeFavorites("bob") } returns bob
        val aliceTop = MutableStateFlow(listOf(SmileyUsageEntry(":o", 2, 1)))
        val bobTop = MutableStateFlow(listOf(SmileyUsageEntry(":D", 3, 2)))
        every { usage.observeTop(" alice ", any()) } returns aliceTop
        every { usage.observeTop("bob", any()) } returns bobTop
        val component = DefaultSmileyLocalCollections(auth, favorites, usage)
        val snapshots = mutableListOf<SmileyLocalCollectionSnapshot>()
        backgroundScope.launch { component.observe().toList(snapshots) }
        runCurrent()
        assertEquals(" alice ", snapshots.last().owner)
        assertEquals(alice.value, snapshots.last().favorites)
        state.value = AuthState.Authenticated("BOB")
        runCurrent()
        assertTrue(snapshots.contains(SmileyLocalCollectionSnapshot("bob")))
        assertEquals(bob.value, snapshots.last().favorites)
        assertEquals(bobTop.value, snapshots.last().top)
        val count = snapshots.size
        alice.value = emptyList()
        aliceTop.value = emptyList()
        runCurrent()
        assertEquals(count, snapshots.size)
        state.value = AuthState.Anonymous
        runCurrent()
        assertEquals(SmileyLocalCollectionSnapshot(), snapshots.last())
        state.value = AuthState.Authenticated(" \t")
        runCurrent()
        assertEquals(SmileyLocalCollectionSnapshot(), snapshots.last())
        verify(exactly = 0) { favorites.observeFavorites(" \t") }
    }

    @Test
    fun `unknown account is anonymous and captured mutations never look up the new account`() = runTest {
        state.value = AuthState.Anonymous
        every { auth.observeAuthState() } returns state
        coEvery { favorites.add("alice", ":o", "saved") } returns LocalFavoriteSmileyMutationResult.Added
        coEvery { favorites.remove("alice", ":o") } returns LocalFavoriteSmileyMutationResult.Updated
        val component = DefaultSmileyLocalCollections(auth, favorites, usage)
        val snapshots = mutableListOf<SmileyLocalCollectionSnapshot>()
        backgroundScope.launch { component.observe().toList(snapshots) }
        runCurrent()
        assertEquals(listOf(SmileyLocalCollectionSnapshot()), snapshots)
        component.add("alice", ":o", "saved")
        component.remove("alice", ":o")
        coVerify(exactly = 1) { favorites.add("alice", ":o", "saved") }
        coVerify(exactly = 1) { favorites.remove("alice", ":o") }
        verify(exactly = 0) { favorites.observeFavorites(any()) }
    }
}
