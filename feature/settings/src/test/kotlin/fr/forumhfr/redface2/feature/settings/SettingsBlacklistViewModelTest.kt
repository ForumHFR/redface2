package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.blacklist.BlacklistRepository
import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.model.blacklist.BlacklistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsBlacklistViewModelTest {

    private val repository = FakeBlacklistRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `mirrors the repository entries`() = runTest {
        repository.block("Alice")
        val viewModel = SettingsBlacklistViewModel(repository)

        assertEquals(listOf("alice"), viewModel.state.value.entries.map { it.canonical })
    }

    @Test
    fun `add blocks the typed pseudo and clears the field`() = runTest {
        val viewModel = SettingsBlacklistViewModel(repository)
        viewModel.submit(SettingsBlacklistIntent.PseudoChanged("  Alice  "))
        viewModel.submit(SettingsBlacklistIntent.AddClicked)

        val entry = viewModel.state.value.entries.single()
        assertEquals("alice", entry.canonical)
        assertEquals("Alice", entry.display)
        assertEquals("", viewModel.state.value.newPseudo)
    }

    @Test
    fun `blank pseudo cannot be added`() = runTest {
        val viewModel = SettingsBlacklistViewModel(repository)
        viewModel.submit(SettingsBlacklistIntent.PseudoChanged("   "))

        assertFalse(viewModel.state.value.canAdd)
        viewModel.submit(SettingsBlacklistIntent.AddClicked)
        assertTrue(viewModel.state.value.entries.isEmpty())
    }

    @Test
    fun `remove unblocks by canonical`() = runTest {
        repository.block("Alice")
        val viewModel = SettingsBlacklistViewModel(repository)
        val entry = viewModel.state.value.entries.single()

        viewModel.submit(SettingsBlacklistIntent.RemoveClicked(entry))

        assertTrue(viewModel.state.value.entries.isEmpty())
    }
}

private class FakeBlacklistRepository : BlacklistRepository {
    private val entries = MutableStateFlow<List<BlacklistEntry>>(emptyList())

    override fun observeEntries(): Flow<List<BlacklistEntry>> = entries

    override fun observeBlockedCanonicals(): Flow<Set<String>> =
        entries.map { list -> list.map { it.canonical }.toSet() }

    override suspend fun isBlocked(pseudo: String): Boolean =
        entries.value.any { it.canonical == canonicalizePseudo(pseudo) }

    override suspend fun block(pseudo: String) {
        val canonical = canonicalizePseudo(pseudo)
        if (canonical.isNotEmpty() && entries.value.none { it.canonical == canonical }) {
            entries.value = entries.value + BlacklistEntry(canonical, pseudo.trim(), 0L)
        }
    }

    override suspend fun unblock(pseudo: String) {
        val canonical = canonicalizePseudo(pseudo)
        entries.value = entries.value.filterNot { it.canonical == canonical }
    }
}
