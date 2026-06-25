package fr.forumhfr.redface2.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSuperFavoriteRepositoryTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreSuperFavoriteRepository
    private val dispatcher = UnconfinedTestDispatcher()
    private val externalScope = CoroutineScope(dispatcher + SupervisorJob())

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("super.preferences_pb") },
        )
        repository = DataStoreSuperFavoriteRepository(dataStore = dataStore, externalScope = externalScope)
    }

    @Test
    fun `starts with an empty set`() = runTest(dispatcher) {
        assertEquals(emptySet<Int>(), repository.observeSuperFavoriteTopicIds().first())
    }

    @Test
    fun `setSuperFavorite enabled adds the topic id`() = runTest(dispatcher) {
        repository.setSuperFavorite(42, enabled = true)
        assertEquals(setOf(42), repository.observeSuperFavoriteTopicIds().first())
    }

    @Test
    fun `setSuperFavorite disabled removes the topic id and keeps the others`() = runTest(dispatcher) {
        repository.setSuperFavorite(42, enabled = true)
        repository.setSuperFavorite(7, enabled = true)
        repository.setSuperFavorite(42, enabled = false)
        assertEquals(setOf(7), repository.observeSuperFavoriteTopicIds().first())
    }

    @Test
    fun `enabling twice is idempotent`() = runTest(dispatcher) {
        repository.setSuperFavorite(42, enabled = true)
        repository.setSuperFavorite(42, enabled = true)
        assertEquals(setOf(42), repository.observeSuperFavoriteTopicIds().first())
    }

    @Test
    fun `disabling an absent id is a no-op`() = runTest(dispatcher) {
        repository.setSuperFavorite(99, enabled = false)
        assertEquals(emptySet<Int>(), repository.observeSuperFavoriteTopicIds().first())
    }

    @Test
    fun `a corrupt non-numeric persisted entry is ignored`() = runTest(dispatcher) {
        dataStore.edit { it[stringSetPreferencesKey("super_favorite_topic_ids")] = setOf("3", "boom", "5") }
        assertEquals(setOf(3, 5), repository.observeSuperFavoriteTopicIds().first())
    }
}
