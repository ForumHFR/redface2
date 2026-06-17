package fr.forumhfr.redface2.core.data.blacklist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultBlacklistRepositoryTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val externalScope = CoroutineScope(dispatcher + SupervisorJob())

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DefaultBlacklistRepository

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("blacklist.preferences_pb") },
        )
        repository = DefaultBlacklistRepository(dataStore, externalScope)
    }

    @Test
    fun `empty store hides nothing`() = runTest(dispatcher) {
        assertEquals(emptyList<Any>(), repository.observeEntries().first())
        assertEquals(emptySet<String>(), repository.observeBlockedCanonicals().first())
        assertFalse(repository.isBlocked("Foo"))
    }

    @Test
    fun `block stores canonical key and preserves the display spelling`() = runTest(dispatcher) {
        repository.block("FooBar")

        val entry = repository.observeEntries().first().single()
        assertEquals("foobar", entry.canonical)
        assertEquals("FooBar", entry.display)
        assertEquals(setOf("foobar"), repository.observeBlockedCanonicals().first())
    }

    @Test
    fun `isBlocked matches case and whitespace variants`() = runTest(dispatcher) {
        repository.block("Foo")

        assertTrue(repository.isBlocked("foo"))
        assertTrue(repository.isBlocked("FOO"))
        assertTrue(repository.isBlocked("  Foo  "))
        assertFalse(repository.isBlocked("Bar"))
    }

    @Test
    fun `unblock accepts a raw pseudo, not just the canonical key`() = runTest(dispatcher) {
        repository.block("Foo")

        repository.unblock("  FOO  ")

        assertFalse(repository.isBlocked("Foo"))
    }

    @Test
    fun `blocking the same canonical twice keeps a single entry with the first spelling`() = runTest(dispatcher) {
        repository.block("Foo")
        repository.block("foo ")

        val entries = repository.observeEntries().first()
        assertEquals(1, entries.size)
        assertEquals("Foo", entries.single().display)
    }

    @Test
    fun `block preserves insertion order`() = runTest(dispatcher) {
        repository.block("Alice")
        repository.block("Bob")

        assertEquals(listOf("Alice", "Bob"), repository.observeEntries().first().map { it.display })
    }

    @Test
    fun `unblock removes the matching entry and is a no-op when absent`() = runTest(dispatcher) {
        repository.block("Foo")
        repository.unblock("nope")
        assertTrue(repository.isBlocked("Foo"))

        repository.unblock("foo")
        assertEquals(emptyList<Any>(), repository.observeEntries().first())
    }

    @Test
    fun `blank pseudo is never blocked`() = runTest(dispatcher) {
        repository.block("   ")
        assertEquals(emptyList<Any>(), repository.observeEntries().first())
    }

    @Test
    fun `a fresh repository decodes the persisted document`() = runTest(dispatcher) {
        repository.block("Foo")

        val reopened = DefaultBlacklistRepository(dataStore, externalScope)
        assertTrue(reopened.isBlocked("foo"))
    }

    @Test
    fun `a corrupt document falls back to an empty blacklist`() = runTest(dispatcher) {
        dataStore.edit { it[stringPreferencesKey("blacklist_v1")] = "{ not valid json" }

        assertEquals(emptyList<Any>(), repository.observeEntries().first())
        assertFalse(repository.isBlocked("Foo"))
    }
}
