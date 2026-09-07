package fr.forumhfr.redface2.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteTopic
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val LEGACY_KEY = stringSetPreferencesKey("super_favorite_topic_ids")
private val ALICE_KEY = stringSetPreferencesKey("super_favorite_topic_ids_alice")
private val ANONYMOUS_KEY = stringSetPreferencesKey("super_favorite_topic_ids_anonymous")

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSuperFavoriteRepositoryTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreSuperFavoriteRepository
    private lateinit var authRepository: FakeAuthRepository
    private val dispatcher = UnconfinedTestDispatcher()
    private val externalScope = CoroutineScope(dispatcher + SupervisorJob())

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("super.preferences_pb") },
        )
        authRepository = FakeAuthRepository(AuthState.Authenticated("Alice"))
        repository = DataStoreSuperFavoriteRepository(
            dataStore = dataStore,
            authRepository = authRepository,
            externalScope = externalScope,
        )
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

    @Test
    fun `toggleSuperFavorite flips against the persisted current value`() = runTest(dispatcher) {
        val other = flag(topicId = 7, title = "Other")
        val target = flag(topicId = 42, title = "Topic RF2")
        repository.setSuperFavorite(other, enabled = true)
        val initial = repository.observeSuperFavoriteTopics().first()

        repository.toggleSuperFavorite(target)
        repository.toggleSuperFavorite(target)

        assertEquals(initial, repository.observeSuperFavoriteTopics().first())
    }

    @Test
    fun `super favorites are scoped per account`() = runTest(dispatcher) {
        val aliceTopic = flag(topicId = 42, title = "Alice topic")
        val bobTopic = flag(topicId = 7, title = "Bob topic")

        repository.setSuperFavorite(aliceTopic, enabled = true)
        authRepository.authenticate("Bob")
        assertEquals(emptySet<SuperFavoriteTopic>(), repository.observeSuperFavoriteTopics().first())
        repository.setSuperFavorite(bobTopic, enabled = true)

        assertEquals(setOf(bobTopic.toStoredTopic()), repository.observeSuperFavoriteTopics().first())
        authRepository.authenticate("ALICE")
        assertEquals(setOf(aliceTopic.toStoredTopic()), repository.observeSuperFavoriteTopics().first())
    }

    @Test
    fun `legacy global key migrates to the first authenticated account that reads it`() = runTest(dispatcher) {
        dataStore.edit { it[LEGACY_KEY] = setOf("42") }

        assertEquals(setOf(orphan(42)), repository.observeSuperFavoriteTopics().first())
        val migrated = dataStore.data.first()
        assertEquals(setOf("42"), migrated[ALICE_KEY])
        assertEquals(null, migrated[LEGACY_KEY])

        authRepository.authenticate("Bob")
        assertEquals(emptySet<SuperFavoriteTopic>(), repository.observeSuperFavoriteTopics().first())
    }

    // #1319 — the anonymous pseudo-account must never claim the legacy key: an expired HFR session
    // used to be enough to move it out of reach of the account that actually owns the pins.
    @Test
    fun `an anonymous observer reads the legacy global key without migrating it`() = runTest(dispatcher) {
        dataStore.edit { it[LEGACY_KEY] = setOf("42") }
        authRepository.logout()

        assertEquals(setOf(orphan(42)), repository.observeSuperFavoriteTopics().first())
        val afterAnonymousRead = dataStore.data.first()
        assertEquals(setOf("42"), afterAnonymousRead[LEGACY_KEY])
        assertEquals(null, afterAnonymousRead[ANONYMOUS_KEY])

        authRepository.authenticate("Alice")
        assertEquals(setOf(orphan(42)), repository.observeSuperFavoriteTopics().first())
        val afterLogin = dataStore.data.first()
        assertEquals(setOf("42"), afterLogin[ALICE_KEY])
        assertEquals(null, afterLogin[LEGACY_KEY])
    }

    @Test
    fun `toggling while logged out writes to the anonymous key and leaves the legacy key alone`() =
        runTest(dispatcher) {
            dataStore.edit { it[LEGACY_KEY] = setOf("42") }
            authRepository.logout()

            repository.setSuperFavorite(flag(topicId = 7, title = "Anon"), enabled = true)

            assertEquals(
                setOf(orphan(42), SuperFavoriteTopic(cat = 23, topicId = 7, title = "Anon", subcat = null)),
                repository.observeSuperFavoriteTopics().first(),
            )
            val prefs = dataStore.data.first()
            assertEquals(setOf("42"), prefs[LEGACY_KEY])
            assertEquals(2, prefs[ANONYMOUS_KEY]?.size)
        }

    @Test
    fun `an authenticated account adopts the anonymous set left behind by the 1319 regression`() =
        runTest(dispatcher) {
            dataStore.edit { it[ANONYMOUS_KEY] = setOf("42") }

            assertEquals(setOf(orphan(42)), repository.observeSuperFavoriteTopics().first())
            val rescued = dataStore.data.first()
            assertEquals(setOf("42"), rescued[ALICE_KEY])
            assertEquals(null, rescued[ANONYMOUS_KEY])
        }

    @Test
    fun `the anonymous rescue does not steal pins once another account owns a set`() = runTest(dispatcher) {
        dataStore.edit { prefs ->
            prefs[ALICE_KEY] = setOf("1")
            prefs[ANONYMOUS_KEY] = setOf("2")
        }
        authRepository.authenticate("Bob")

        assertEquals(emptySet<SuperFavoriteTopic>(), repository.observeSuperFavoriteTopics().first())
        assertEquals(setOf("2"), dataStore.data.first()[ANONYMOUS_KEY])
    }

    @Test
    fun `switching account switches the observed set`() = runTest(dispatcher) {
        dataStore.edit { prefs ->
            prefs[stringSetPreferencesKey("super_favorite_topic_ids_alice")] = setOf("1")
            prefs[stringSetPreferencesKey("super_favorite_topic_ids_bob")] = setOf("2")
        }

        repository.observeSuperFavoriteTopics().test {
            assertEquals(
                setOf(SuperFavoriteTopic(cat = null, topicId = 1, title = null, subcat = null)),
                awaitItem(),
            )
            authRepository.authenticate("Bob")
            assertEquals(
                setOf(SuperFavoriteTopic(cat = null, topicId = 2, title = null, subcat = null)),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun orphan(topicId: Int): SuperFavoriteTopic =
        SuperFavoriteTopic(cat = null, topicId = topicId, title = null, subcat = null)

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

    private fun Flag.toStoredTopic(): SuperFavoriteTopic = SuperFavoriteTopic(
        cat = cat,
        topicId = topicId,
        title = title,
        subcat = subcat,
    )

    private class FakeAuthRepository(initial: AuthState) : AuthRepository {
        private val state = MutableStateFlow(initial)

        override fun observeAuthState(): Flow<AuthState> = state.asStateFlow()

        override suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated> {
            val authenticated = AuthState.Authenticated(pseudo)
            state.value = authenticated
            return Result.success(authenticated)
        }

        override suspend fun logout() {
            state.value = AuthState.Anonymous
        }

        fun authenticate(pseudo: String) {
            state.value = AuthState.Authenticated(pseudo)
        }
    }
}
