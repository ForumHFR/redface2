package fr.forumhfr.redface2.core.data.topic

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.dao.TopicDao
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.CategoryBandStyle
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.AccentColor
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.domain.preferences.PlusLusIndicatorStyle
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TopicRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var database: RedfaceDatabase
    private lateinit var dao: TopicDao
    private lateinit var client: HfrClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, RedfaceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.topicDao()

        val okHttp = OkHttpClient.Builder().build()
        client = HfrClient(
            authenticated = okHttp,
            anonymous = okHttp,
            baseUrl = server.url("/"),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    @Test
    fun `observeTopicPage emits fresh fetch when cache is empty`() = runTest {
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val repository = repository(now = Instant.parse("2026-04-26T18:00:00Z"))

        repository.observeTopicPage(cat = 1, post = 999_395, page = 1).test {
            val fresh = awaitItem()
            assertEquals(1, fresh.cat)
            assertEquals(999_395, fresh.post)
            assertEquals(1, fresh.page)
            assertTrue("expected at least one post", fresh.posts.isNotEmpty())
            awaitComplete()
        }

        val recorded = server.takeRequest()
        val requestUrl = recorded.requestUrl
        assertNotNull(requestUrl)
        requestUrl!!
        assertEquals("forum2.php", requestUrl.pathSegments.first())
        assertEquals("hfr.inc", requestUrl.queryParameter("config"))
        assertEquals("1", requestUrl.queryParameter("cat"))
        assertEquals("999395", requestUrl.queryParameter("post"))
        assertEquals("1", requestUrl.queryParameter("page"))
    }

    @Test
    fun `observeTopicPage on a fresh cache hit skips the network refresh`() = runTest {
        // Warm the cache: only one fixture is enqueued, the second observe call
        // must not consume a second response — that's the whole point of the TTL.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val repo = repository(now = Instant.parse("2026-04-26T18:00:00Z"))
        repo.refreshTopicPage(1, 999_395, 1)
        assertEquals("warmup should issue exactly one network request", 1, server.requestCount)

        // Same fixed clock → cache fetchedAt equals "now" → fresh → no refresh.
        repo.observeTopicPage(1, 999_395, 1).test {
            val cached = awaitItem()
            awaitComplete()
            assertTrue("cache emission should carry posts", cached.posts.isNotEmpty())
        }
        assertEquals("fresh cache hit must not trigger a refresh", 1, server.requestCount)
    }

    @Test
    fun `observeTopicPage with forceRefresh re-fetches despite a fresh cache (#231)`() = runTest {
        // Two responses: the warmup + the forced refresh that must fire even though the
        // cache is fresh by TTL — the #231 « open from a flag = catch up on new posts » path.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val repo = repository(now = Instant.parse("2026-04-26T18:00:00Z"))
        repo.refreshTopicPage(1, 999_395, 1)
        assertEquals("warmup should issue exactly one network request", 1, server.requestCount)

        // Same fixed clock → cache fresh by TTL → without forceRefresh it would skip.
        repo.observeTopicPage(1, 999_395, 1, forceRefresh = true).test {
            awaitItem() // cached emission (instant)
            awaitItem() // forced refresh emission
            awaitComplete()
        }
        assertEquals(
            "forceRefresh must re-fetch despite a fresh AUTHENTICATED cache",
            2,
            server.requestCount,
        )
    }

    @Test
    fun `observeTopicPage on a stale cache emits cache then refreshes`() = runTest {
        // Warm cache as of T0.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        repository(now = Instant.parse("2026-04-26T18:00:00Z")).refreshTopicPage(1, 999_395, 1)

        // Move 5 minutes forward — beyond CachePolicy.topicPage (60s).
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val staleRepository = repository(now = Instant.parse("2026-04-26T18:05:00Z"))
        staleRepository.observeTopicPage(1, 999_395, 1).test {
            val cached = awaitItem()
            val fresh = awaitItem()
            assertEquals(cached.title, fresh.title)
            assertEquals(cached.posts.size, fresh.posts.size)
            // Round-trip the PostContent AST through the Room JSON converter:
            // the cached read goes through PostContentSerializer.decode while the
            // fresh read comes straight from HfrParser, so equality proves the
            // converter preserves the polymorphic block/inline hierarchy.
            assertEquals(fresh.posts.first().content, cached.posts.first().content)
            awaitComplete()
        }
        assertEquals("stale cache must trigger one refresh request", 2, server.requestCount)
    }

    @Test
    fun `network failure after cached emission keeps the cache and swallows the error`() = runTest {
        // Warm cache as of T0.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        repository(now = Instant.parse("2026-04-26T18:00:00Z")).refreshTopicPage(1, 999_395, 1)

        // Stale TTL → refresh path triggers, but the next response is a hard
        // disconnect. The flow must emit the cached page exactly once and complete
        // without rethrowing the network failure.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val staleRepository = repository(now = Instant.parse("2026-04-26T18:05:00Z"))
        staleRepository.observeTopicPage(1, 999_395, 1).test {
            val cached = awaitItem()
            assertTrue("cache emission should carry the warmed posts", cached.posts.isNotEmpty())
            awaitComplete()
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `prefetch does not overwrite an existing AUTHENTICATED row`() = runTest {
        // First request lands as authenticated and warms the cache with auth-derived
        // post fields (the fixture has isOwnPost=false but the row is tagged AUTH).
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val authedRepo = repository(now = Instant.parse("2026-04-26T18:00:00Z"))
        authedRepo.refreshTopicPage(1, 999_395, 1)
        val authRow = dao.getTopicPage(1, 999_395, 1)
        assertNotNull(authRow)
        assertEquals(FetchMode.AUTHENTICATED, authRow!!.authMode)

        // Anonymous prefetch arrives later — it must NOT clobber the auth row.
        // We enqueue a second response in case the implementation issues the network
        // call; the assertion is on the persisted row's authMode.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val anonRepo = repository(now = Instant.parse("2026-04-26T18:00:30Z"))
        anonRepo.prefetch(1, 999_395, 1)

        val afterPrefetch = dao.getTopicPage(1, 999_395, 1)
        assertNotNull(afterPrefetch)
        assertEquals(
            "anonymous prefetch must keep AUTHENTICATED authMode on the cached row",
            FetchMode.AUTHENTICATED,
            afterPrefetch!!.authMode,
        )
    }

    @Test
    fun `observeTopicPage with a fresh ANONYMOUS cache still re-fetches authenticated`() = runTest {
        // Cold cache → anonymous prefetch lands an ANONYMOUS row at T0.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        repository(now = Instant.parse("2026-04-26T18:00:00Z")).prefetch(1, 999_395, 1)
        val anonRow = dao.getTopicPage(1, 999_395, 1)
        assertEquals(FetchMode.ANONYMOUS, anonRow!!.authMode)
        assertEquals("warm-up should have issued exactly one network request", 1, server.requestCount)

        // Same fixed clock → cache fetchedAt equals "now" → fresh BY TTL. But because the
        // row is ANONYMOUS, observeTopicPage must still re-fetch authenticated to surface
        // per-user fields and let HFR mark drapeaux as read. Without this guard, a
        // prefetched page would silently shortcut the read into a stale anon view.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val repo = repository(now = Instant.parse("2026-04-26T18:00:00Z"))
        repo.observeTopicPage(1, 999_395, 1).test {
            awaitItem() // cached anon emission
            awaitItem() // authenticated refresh
            awaitComplete()
        }
        assertEquals("anon cache must trigger an auth refresh regardless of TTL", 2, server.requestCount)
        assertEquals(
            "the cache must have been upgraded to AUTHENTICATED",
            FetchMode.AUTHENTICATED,
            dao.getTopicPage(1, 999_395, 1)!!.authMode,
        )
    }

    @Test
    fun `refreshTopicPage upgrades an existing ANONYMOUS row to AUTHENTICATED`() = runTest {
        // Cold cache → an anonymous prefetch writes an ANONYMOUS row.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val anonRepo = repository(now = Instant.parse("2026-04-26T18:00:00Z"))
        anonRepo.prefetch(1, 999_395, 1)
        val anonRow = dao.getTopicPage(1, 999_395, 1)
        assertNotNull(anonRow)
        assertEquals(FetchMode.ANONYMOUS, anonRow!!.authMode)

        // The user opens the page : the authenticated path must overwrite the anon row,
        // bringing back per-user fields. The "do not downgrade" guard is one-way —
        // anon→auth is the upgrade path, not the other direction.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val authedRepo = repository(now = Instant.parse("2026-04-26T18:00:30Z"))
        authedRepo.refreshTopicPage(1, 999_395, 1)

        val upgraded = dao.getTopicPage(1, 999_395, 1)
        assertNotNull(upgraded)
        assertEquals(
            "AUTHENTICATED fetch must overwrite a previously ANONYMOUS row",
            FetchMode.AUTHENTICATED,
            upgraded!!.authMode,
        )
    }

    @Test
    fun `prefetch on a cold cache writes an ANONYMOUS row`() = runTest {
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val anonRepo = repository(now = Instant.parse("2026-04-26T18:00:00Z"))

        anonRepo.prefetch(1, 999_395, 1)

        val row = dao.getTopicPage(1, 999_395, 1)
        assertNotNull(row)
        assertEquals(FetchMode.ANONYMOUS, row!!.authMode)
    }

    @Test
    fun `refreshTopicPage upserts the topic page into Room and tags it as authenticated`() = runTest {
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val repo = repository(now = Instant.parse("2026-04-26T18:00:00Z"))

        assertNull(dao.getTopicPage(1, 999_395, 1))
        val refreshed = repo.refreshTopicPage(1, 999_395, 1)

        val cachedTopic = dao.getTopicPage(1, 999_395, 1)
        assertNotNull(cachedTopic)
        cachedTopic!!
        assertEquals(refreshed.title, cachedTopic.title)
        assertEquals(refreshed.posts.map { it.numreponse }, cachedTopic.numreponses)
        assertEquals(FetchMode.AUTHENTICATED, cachedTopic.authMode)

        val cachedPosts = dao.getPostsByNumreponse(1, cachedTopic.numreponses)
        assertEquals(refreshed.posts.size, cachedPosts.size)
        cachedPosts.forEach { post ->
            assertEquals(FetchMode.AUTHENTICATED, post.authMode)
        }
    }

    @Test
    fun `observeTopicPage fresh cache preserves quoteRef without network refresh`() = runTest {
        // Phase 2C (#146 round 2) regression : before Room v5, Post.quoteRef was not
        // persisted, so a fresh cache hit would lose HFR's best-effort positional
        // `ref`. Since #227 this no longer controls « Citer » visibility, but the
        // cache must still preserve the value when HFR exposed it. This test pins
        // that contract — the warmup parses real HFR-quote hrefs from the topic_khakha
        // fixture (refs 0/1/2/3/4/5 on the first 6 quotable posts) and the cache
        // re-emission must keep at least one of them non-null.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_khakha_page_2.html")))
        val repo = repository(now = Instant.parse("2026-04-26T18:00:00Z"))
        val fresh = repo.refreshTopicPage(13, 84_540, 2)
        assertEquals("warmup must issue exactly one network request", 1, server.requestCount)

        val freshWithQuote = fresh.posts.firstOrNull { it.quoteRef != null }
        assertNotNull(
            "Phase 2C fixture must expose at least one parsed quoteRef on warmup",
            freshWithQuote,
        )
        requireNotNull(freshWithQuote)

        // Same clock → fresh cache → no network refresh.
        repo.observeTopicPage(13, 84_540, 2).test {
            val cached = awaitItem()
            awaitComplete()
            val cachedSamePost = cached.posts.first { it.numreponse == freshWithQuote.numreponse }
            assertEquals(
                "quoteRef must round-trip Room v5 unchanged on cache hit",
                freshWithQuote.quoteRef,
                cachedSamePost.quoteRef,
            )
        }
        assertEquals("fresh cache hit must not trigger a refresh", 1, server.requestCount)
    }

    @Test
    fun `observeTopicPage fresh cache preserves editedAt without network refresh`() = runTest {
        // #362 regression : Post.editedAt is persisted in Room v8 (MIGRATION_7_8). Without
        // the column + mapper round-trip, every fresh cache hit (the common case once a
        // topic has been refreshed once) would silently reset the edit marker to null and
        // the « Édité le … » menu line would vanish. The khakha p2 fixture carries both
        // edited and never-edited posts, so the cache re-emission is checked on both.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_khakha_page_2.html")))
        val repo = repository(now = Instant.parse("2026-04-26T18:00:00Z"))
        val fresh = repo.refreshTopicPage(13, 84_540, 2)
        assertEquals("warmup must issue exactly one network request", 1, server.requestCount)

        // n°16628102 = reelooz10, « Message cité 2 fois » + edited 03-11-2008 à 21:43:47
        // (CET = UTC+1) ; n°16628071 = Mora1651, never edited.
        val freshEdited = fresh.posts.first { it.numreponse == 16_628_102 }
        assertEquals(Instant.parse("2008-11-03T20:43:47Z"), freshEdited.editedAt)

        // Same clock → fresh cache → no network refresh.
        repo.observeTopicPage(13, 84_540, 2).test {
            val cached = awaitItem()
            awaitComplete()
            assertEquals(
                "editedAt must round-trip Room v8 unchanged on cache hit",
                freshEdited.editedAt,
                cached.posts.first { it.numreponse == 16_628_102 }.editedAt,
            )
            assertNull(
                "never-edited post must keep editedAt null on cache hit",
                cached.posts.first { it.numreponse == 16_628_071 }.editedAt,
            )
        }
        assertEquals("fresh cache hit must not trigger a refresh", 1, server.requestCount)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Alpha "Ignorer le cache topic" toggle — bypass tests
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `observeTopicPage bypasses a fresh AUTHENTICATED cache when ignoreTopicCache is true`() = runTest {
        // Warm the cache with an AUTHENTICATED row — without the toggle this would be a
        // no-network case (cf. "skips the network refresh" test above). The toggle must
        // override the TTL check entirely.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val prefs = FakeUserPreferencesRepository(ignoreTopicCacheInitial = false)
        val warmup = repository(now = Instant.parse("2026-04-26T18:00:00Z"), userPreferences = prefs)
        warmup.refreshTopicPage(1, 999_395, 1)
        assertEquals(1, server.requestCount)

        // Flip the toggle and reopen the page at the SAME instant — the cache would be fresh.
        prefs.setIgnoreTopicCache(true)
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val bypass = repository(now = Instant.parse("2026-04-26T18:00:00Z"), userPreferences = prefs)
        bypass.observeTopicPage(1, 999_395, 1).test {
            val fresh = awaitItem()
            assertTrue(fresh.posts.isNotEmpty())
            awaitComplete()
        }

        assertEquals(
            "ignoreTopicCache=true must always hit the network, even on a TTL-fresh row",
            2,
            server.requestCount,
        )
    }

    @Test
    fun `observeTopicPage in bypass mode still persists the fetched page so the cache stays coherent`() = runTest {
        val prefs = FakeUserPreferencesRepository(ignoreTopicCacheInitial = true)

        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val repo = repository(now = Instant.parse("2026-04-26T18:00:00Z"), userPreferences = prefs)
        repo.observeTopicPage(1, 999_395, 1).test {
            awaitItem()
            awaitComplete()
        }

        val persisted = dao.getTopicPage(1, 999_395, 1)
        assertNotNull(
            "bypass mode must still persist so toggling back OFF finds a parser-coherent cache",
            persisted,
        )
        assertEquals(FetchMode.AUTHENTICATED, persisted!!.authMode)
    }

    @Test
    fun `prefetch is a no-op when ignoreTopicCache is true`() = runTest {
        val prefs = FakeUserPreferencesRepository(ignoreTopicCacheInitial = true)
        val repo = repository(now = Instant.parse("2026-04-26T18:00:00Z"), userPreferences = prefs)

        // No MockResponse enqueued on purpose — if prefetch hits the network the test fails
        // with a "no more responses" / IOException.
        repo.prefetch(1, 999_395, 1)

        assertEquals("prefetch must not issue any network call in bypass mode", 0, server.requestCount)
        assertNull(
            "prefetch must not write a Room row in bypass mode",
            dao.getTopicPage(1, 999_395, 1),
        )
    }

    @Test
    fun `prefetch keeps current behaviour when ignoreTopicCache is false`() = runTest {
        // Regression guard : the default false case must still issue an ANONYMOUS prefetch.
        // Without this test, a future refactor of the bypass branch could silently disable
        // prefetch globally.
        val prefs = FakeUserPreferencesRepository(ignoreTopicCacheInitial = false)
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val repo = repository(now = Instant.parse("2026-04-26T18:00:00Z"), userPreferences = prefs)

        repo.prefetch(1, 999_395, 1)

        assertEquals(1, server.requestCount)
        val row = dao.getTopicPage(1, 999_395, 1)
        assertNotNull(row)
        assertEquals(FetchMode.ANONYMOUS, row!!.authMode)
    }

    @Test
    fun `observeTopicPage bypass mode surfaces the network error as a flow exception`() = runTest {
        // Conformance check : the prompt requires that "ne pas masquer les erreurs reseau en
        // mode ignore-cache". With no cache to fall back to, the flow must propagate. Note:
        // the cache MAY exist on disk, but the bypass path must not even read it.
        server.enqueue(MockResponse().setBody(fixtureHtml("topic_page_single.html")))
        val prefs = FakeUserPreferencesRepository(ignoreTopicCacheInitial = false)
        repository(now = Instant.parse("2026-04-26T18:00:00Z"), userPreferences = prefs)
            .refreshTopicPage(1, 999_395, 1)
        prefs.setIgnoreTopicCache(true)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val repo = repository(now = Instant.parse("2026-04-26T18:00:00Z"), userPreferences = prefs)
        repo.observeTopicPage(1, 999_395, 1).test {
            // No cached emission — bypass skipped the read. Then the network fails → the
            // flow surfaces the failure rather than silently keeping the previous cache.
            awaitError()
        }
        assertEquals(2, server.requestCount)
    }

    private fun repository(
        now: Instant,
        userPreferences: UserPreferencesRepository = FakeUserPreferencesRepository(),
    ): TopicRepositoryImpl = TopicRepositoryImpl(
        client = client,
        parser = HfrParser(),
        topicDao = dao,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        userPreferencesRepository = userPreferences,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun fixtureHtml(name: String): String {
        return requireNotNull(javaClass.getResource("/fixtures/$name")) {
            "Fixture not found: $name"
        }.readText()
    }

    /**
     * Lightweight in-memory implementation. The bypass-cache tests need a writable toggle but
     * not the real DataStore; they only exercise the network/cache decision branch. Proxy
     * methods are stubbed out — `TopicRepositoryImpl` never reads them and Hilt only routes the
     * real binding through `UserPreferencesRepository` in production.
     */
    private class FakeUserPreferencesRepository(
        ignoreTopicCacheInitial: Boolean = false,
    ) : UserPreferencesRepository {
        private val ignoreTopicCache = MutableStateFlow(ignoreTopicCacheInitial)

        override fun observeProxyConfig(): Flow<ProxyConfig> = MutableStateFlow(ProxyConfig())

        override suspend fun saveProxyConfig(config: ProxyConfig) = Unit

        override fun readProxyConfigForNetworkBootstrap(): ProxyConfig = ProxyConfig()

        override fun observeIgnoreTopicCache(): Flow<Boolean> = ignoreTopicCache

        override suspend fun setIgnoreTopicCache(enabled: Boolean) {
            ignoreTopicCache.value = enabled
        }

        // Flags view preferences are irrelevant to TopicRepositoryImpl — stubbed at their defaults.
        override fun observeFlagsGroupByCategory(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setFlagsGroupByCategory(enabled: Boolean) = Unit

        override fun observeFlagsHideReadCategories(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setFlagsHideReadCategories(enabled: Boolean) = Unit

        override fun observeFlagsPerTabOverride(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setFlagsPerTabOverride(enabled: Boolean) = Unit

        override fun observeFlagsViewSettings(type: FlagType): Flow<FlagsViewSettings> =
            MutableStateFlow(FlagsViewSettings())

        override suspend fun setFlagsGroupByCategoryForType(type: FlagType, enabled: Boolean) = Unit

        override suspend fun setFlagsHideReadCategoriesForType(type: FlagType, enabled: Boolean) = Unit

        override suspend fun setFlagsUnreadOnlyForType(type: FlagType, enabled: Boolean) = Unit
        override suspend fun setFlagsMarkerStyle(style: MarkerStyle) = Unit
        override suspend fun setFlagsSingleLineTitle(enabled: Boolean) = Unit
        override suspend fun setFlagsCategoryBandStyle(style: CategoryBandStyle) = Unit
        override suspend fun setFlagsMarkerBorder(enabled: Boolean) = Unit
        override suspend fun setFlagsPlusLusIndicatorStyle(style: PlusLusIndicatorStyle) = Unit

        // #286 — theme prefs are irrelevant to TopicRepositoryImpl; stubbed at their defaults.
        override fun observeThemeMode(): Flow<ThemeMode> = MutableStateFlow(ThemeMode.SYSTEM)

        override suspend fun setThemeMode(mode: ThemeMode) = Unit

        override fun observeAmoledEnabled(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setAmoledEnabled(enabled: Boolean) = Unit

        override fun observeTopicTopBarAutoHide(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setTopicTopBarAutoHide(enabled: Boolean) = Unit

        // #312 — confirm-before-posting is irrelevant to TopicRepositoryImpl; stubbed at its default.
        override fun observeConfirmBeforePosting(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setConfirmBeforePosting(enabled: Boolean) = Unit

        override fun observeShowDtSection(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setShowDtSection(enabled: Boolean) = Unit

        override fun observeSyncPrivateMessagesWriteEnabled(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setSyncPrivateMessagesWriteEnabled(enabled: Boolean) = Unit

        override fun observeFlagsAutoRefresh(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setFlagsAutoRefresh(enabled: Boolean) = Unit

        override fun observeTopicPageFabs(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setTopicPageFabs(enabled: Boolean) = Unit

        override fun observeMpUnreadBadge(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setMpUnreadBadge(enabled: Boolean) = Unit

        override fun observeTopicPollsExpanded(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setTopicPollsExpanded(enabled: Boolean) = Unit

        override fun observeTopicSignatures(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setTopicSignatures(enabled: Boolean) = Unit

        override fun observeFoldLongQuotes(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setFoldLongQuotes(enabled: Boolean) = Unit

        override fun observeShowScrollbar(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setShowScrollbar(enabled: Boolean) = Unit

        override fun observeNavBarLabels(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setNavBarLabels(enabled: Boolean) = Unit

        override fun observeFunnyEmptyState(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setFunnyEmptyState(enabled: Boolean) = Unit

        override fun observeStartScreen(): Flow<StartScreenPreference> =
            MutableStateFlow(StartScreenPreference())

        override suspend fun setStartScreen(preference: StartScreenPreference) = Unit

        // #459 — upload provider / imgur Client-ID are irrelevant to this repository; default stubs.
        override fun observeUploadProvider(): Flow<UploadProviderId> =
            MutableStateFlow(UploadProviderId.DIBERIE)

        override suspend fun setUploadProvider(provider: UploadProviderId) = Unit

        override fun observeImgurClientId(): Flow<String> = MutableStateFlow("")

        override suspend fun setImgurClientId(clientId: String) = Unit

        override fun observeEditorImageInsert(): Flow<EditorImageInsert> =
            MutableStateFlow(EditorImageInsert.REDUCED)

        override suspend fun setEditorImageInsert(mode: EditorImageInsert) = Unit

        // #287 — reading display presets are irrelevant to TopicRepository; stubbed at defaults.
        override fun observeDisplayDensity(): Flow<DisplayDensity> = MutableStateFlow(DisplayDensity.COMFORT)

        override suspend fun setDisplayDensity(density: DisplayDensity) = Unit

        override fun observeFontScale(): Flow<FontScalePreference> = MutableStateFlow(FontScalePreference.M)

        override suspend fun setFontScale(scale: FontScalePreference) = Unit

        override fun observeDebugBoundsOverlay(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setDebugBoundsOverlay(enabled: Boolean) = Unit

        override fun observeHideSystemNavBar(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setHideSystemNavBar(enabled: Boolean) = Unit

        override fun observeImmersiveBackButton(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setImmersiveBackButton(enabled: Boolean) = Unit

        override fun observeImmersiveNavBarReveal(): Flow<ImmersiveNavBarReveal> =
            MutableStateFlow(ImmersiveNavBarReveal.MANUAL)

        override suspend fun setImmersiveNavBarReveal(mode: ImmersiveNavBarReveal) = Unit
        override fun observeAccentColor(): Flow<AccentColor> = MutableStateFlow(AccentColor.ROSE)
        override suspend fun setAccentColor(color: AccentColor) = Unit
    }
}
