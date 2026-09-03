package fr.forumhfr.redface2.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteTopic
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
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
        assertEquals(emptySet<SuperFavoriteTopic>(), repository.observeSuperFavoriteTopics().first())
    }

    @Test
    fun `setSuperFavorite enabled adds the topic snapshot`() = runTest(dispatcher) {
        repository.setSuperFavorite(flag(topicId = 42, title = "Topic RF2", subcat = 550), enabled = true)
        assertEquals(
            setOf(SuperFavoriteTopic(cat = 23, topicId = 42, title = "Topic RF2", subcat = 550)),
            repository.observeSuperFavoriteTopics().first(),
        )
    }

    @Test
    fun `setSuperFavorite disabled removes the matching topic and keeps the others`() = runTest(dispatcher) {
        val first = flag(topicId = 42, title = "Topic RF2")
        val second = flag(topicId = 7, title = "Autre")
        repository.setSuperFavorite(first, enabled = true)
        repository.setSuperFavorite(second, enabled = true)
        repository.setSuperFavorite(first, enabled = false)
        assertEquals(
            setOf(SuperFavoriteTopic(cat = 23, topicId = 7, title = "Autre", subcat = null)),
            repository.observeSuperFavoriteTopics().first(),
        )
    }

    @Test
    fun `enabling twice is idempotent`() = runTest(dispatcher) {
        val flag = flag(topicId = 42, title = "Topic RF2")
        repository.setSuperFavorite(flag, enabled = true)
        repository.setSuperFavorite(flag, enabled = true)
        assertEquals(
            setOf(SuperFavoriteTopic(cat = 23, topicId = 42, title = "Topic RF2", subcat = null)),
            repository.observeSuperFavoriteTopics().first(),
        )
    }

    @Test
    fun `disabling an absent topic is a no-op`() = runTest(dispatcher) {
        repository.setSuperFavorite(flag(topicId = 99), enabled = false)
        assertEquals(emptySet<SuperFavoriteTopic>(), repository.observeSuperFavoriteTopics().first())
    }

    @Test
    fun `legacy topic-id-only entries are decoded as orphan topics and corrupt entries are ignored`() =
        runTest(dispatcher) {
            dataStore.edit { it[stringSetPreferencesKey("super_favorite_topic_ids")] = setOf("3", "boom", "5") }
            assertEquals(
                setOf(
                    SuperFavoriteTopic(cat = null, topicId = 3, title = null, subcat = null),
                    SuperFavoriteTopic(cat = null, topicId = 5, title = null, subcat = null),
                ),
                repository.observeSuperFavoriteTopics().first(),
            )
        }

    @Test
    fun `enabling a resolved legacy orphan replaces it with a keyed snapshot`() = runTest(dispatcher) {
        dataStore.edit { it[stringSetPreferencesKey("super_favorite_topic_ids")] = setOf("42") }
        repository.setSuperFavorite(flag(topicId = 42, title = "Resolved", subcat = 550), enabled = true)
        assertEquals(
            setOf(SuperFavoriteTopic(cat = 23, topicId = 42, title = "Resolved", subcat = 550)),
            repository.observeSuperFavoriteTopics().first(),
        )
    }

    private fun flag(
        topicId: Int,
        title: String = "Topic $topicId",
        cat: Int = 23,
        subcat: Int? = null,
    ): Flag = Flag(
        cat = cat,
        subcat = subcat,
        topicId = topicId,
        title = title,
        totalPages = 1,
        replyCount = 0,
        type = FlagType.CYAN,
        isFavorite = false,
        hasUnread = true,
        lastReadPage = 1,
        lastPostReadId = null,
        firstPostAuthor = "author",
        lastReplyAuthor = "last",
        lastReplyAt = "2026-09-03T12:00:00Z",
    )
}
