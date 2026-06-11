package fr.forumhfr.redface2.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrap
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.model.FlagType
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class DataStoreUserPreferencesRepositoryTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreUserPreferencesRepository
    private val dispatcher = UnconfinedTestDispatcher()

    /** In-memory [ThemeBootstrapStore] — the SharedPreferences impl has its own Robolectric test. */
    private val themeBootstrapStore = object : ThemeBootstrapStore {
        var stored = ThemeBootstrap()
        override fun read(): ThemeBootstrap = stored
        override fun writeThemeMode(mode: ThemeMode) {
            stored = stored.copy(themeMode = mode)
        }
        override fun writeAmoledEnabled(enabled: Boolean) {
            stored = stored.copy(amoledEnabled = enabled)
        }
    }

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("user.preferences_pb") },
        )
        repository = DataStoreUserPreferencesRepository(
            dataStore = dataStore,
            themeBootstrapStore = themeBootstrapStore,
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `empty store observes disabled proxy`() = runTest(dispatcher) {
        repository.observeProxyConfig().test {
            val config = awaitItem()
            assertFalse(config.enabled)
            assertFalse(config.isUsable)
            assertEquals("", config.host)
            assertEquals(null, config.port)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save trims host and username then persists usable proxy`() = runTest(dispatcher) {
        repository.saveProxyConfig(
            ProxyConfig(
                enabled = true,
                host = " proxy.local ",
                port = 8_080,
                username = " user ",
                password = "secret",
            ),
        )

        repository.observeProxyConfig().test {
            val config = awaitItem()
            assertTrue(config.enabled)
            assertTrue(config.isUsable)
            assertTrue(config.hasCredentials)
            assertEquals("proxy.local", config.host)
            assertEquals(8_080, config.port)
            assertEquals("user", config.username)
            assertEquals("secret", config.password)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `readProxyConfigForNetworkBootstrap returns persisted proxy synchronously`() = runTest(dispatcher) {
        repository.saveProxyConfig(
            ProxyConfig(
                enabled = true,
                host = "proxy.local",
                port = 3_128,
            ),
        )

        val config = repository.readProxyConfigForNetworkBootstrap()

        assertTrue(config.isUsable)
        assertEquals("proxy.local", config.host)
        assertEquals(3_128, config.port)
    }

    @Test
    fun `observeIgnoreTopicCache defaults to false on an empty store`() = runTest(dispatcher) {
        repository.observeIgnoreTopicCache().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setIgnoreTopicCache persists true and false independently of the proxy config`() = runTest(dispatcher) {
        // Seed a usable proxy first — we want to prove that flipping the topic-cache toggle
        // does NOT touch any proxy key. The two concerns share the same DataStore file but
        // must remain isolated; this is exactly the contract `setIgnoreTopicCache` advertises.
        repository.saveProxyConfig(
            ProxyConfig(
                enabled = true,
                host = "proxy.local",
                port = 8_080,
            ),
        )

        repository.setIgnoreTopicCache(true)
        repository.observeIgnoreTopicCache().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        val proxyAfterEnable = repository.readProxyConfigForNetworkBootstrap()
        assertTrue("proxy must stay enabled when toggling ignore-topic-cache", proxyAfterEnable.enabled)
        assertEquals("proxy.local", proxyAfterEnable.host)
        assertEquals(8_080, proxyAfterEnable.port)

        repository.setIgnoreTopicCache(false)
        repository.observeIgnoreTopicCache().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        val proxyAfterDisable = repository.readProxyConfigForNetworkBootstrap()
        assertTrue("proxy stays enabled after disabling ignore-topic-cache", proxyAfterDisable.enabled)
        assertEquals("proxy.local", proxyAfterDisable.host)
    }

    @Test
    fun `observeFlagsGroupByCategory defaults to true on an empty store`() = runTest(dispatcher) {
        // Grouped is the #179 default — an empty store must report `true`, not the boolean zero.
        repository.observeFlagsGroupByCategory().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsGroupByCategory persists false and true`() = runTest(dispatcher) {
        repository.setFlagsGroupByCategory(false)
        repository.observeFlagsGroupByCategory().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setFlagsGroupByCategory(true)
        repository.observeFlagsGroupByCategory().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeFlagsHideReadCategories defaults to false on an empty store`() = runTest(dispatcher) {
        repository.observeFlagsHideReadCategories().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsHideReadCategories persists true and false independently of the grouped pref`() = runTest(dispatcher) {
        repository.setFlagsGroupByCategory(false)

        repository.setFlagsHideReadCategories(true)
        repository.observeFlagsHideReadCategories().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // Flipping one Drapeaux pref must not disturb the other (distinct keys).
        repository.observeFlagsGroupByCategory().test {
            assertFalse("grouped pref must stay false", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setFlagsHideReadCategories(false)
        repository.observeFlagsHideReadCategories().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeFlagsPerTabOverride defaults to false on an empty store`() = runTest(dispatcher) {
        repository.observeFlagsPerTabOverride().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeFlagsViewSettings returns the global pair when the per-tab override is off`() = runTest(dispatcher) {
        // Global = flat + hide-read on; per-type values are set but must be IGNORED while the
        // override is off, so every tab resolves to the global pair.
        repository.setFlagsGroupByCategory(false)
        repository.setFlagsHideReadCategories(true)
        repository.setFlagsGroupByCategoryForType(FlagType.RED, true)
        repository.setFlagsHideReadCategoriesForType(FlagType.RED, false)

        repository.observeFlagsViewSettings(FlagType.RED).test {
            val settings = awaitItem()
            assertFalse("grouped must reflect the global value, not the RED override", settings.groupByCategory)
            assertTrue("hide-read must reflect the global value, not the RED override", settings.hideReadCategories)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeFlagsViewSettings reads per-type values, falling back to global per toggle`() = runTest(dispatcher) {
        repository.setFlagsPerTabOverride(true)
        // Global defaults: grouped on, hide-read off.
        // CYAN customises ONLY grouped (off); its hide-read must fall back to the global false.
        repository.setFlagsGroupByCategoryForType(FlagType.CYAN, false)
        // RED customises ONLY hide-read (on); its grouped must fall back to the global true.
        repository.setFlagsHideReadCategoriesForType(FlagType.RED, true)

        repository.observeFlagsViewSettings(FlagType.CYAN).test {
            val cyan = awaitItem()
            assertFalse("CYAN grouped is its own override", cyan.groupByCategory)
            assertFalse("CYAN hide-read falls back to the global false", cyan.hideReadCategories)
            cancelAndIgnoreRemainingEvents()
        }
        repository.observeFlagsViewSettings(FlagType.RED).test {
            val red = awaitItem()
            assertTrue("RED grouped falls back to the global true", red.groupByCategory)
            assertTrue("RED hide-read is its own override", red.hideReadCategories)
            cancelAndIgnoreRemainingEvents()
        }
        // FAVORITE never customised anything → full global fallback.
        repository.observeFlagsViewSettings(FlagType.FAVORITE).test {
            val fav = awaitItem()
            assertTrue("FAVORITE grouped is the global default", fav.groupByCategory)
            assertFalse("FAVORITE hide-read is the global default", fav.hideReadCategories)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsGroupByCategoryForType is isolated per type`() = runTest(dispatcher) {
        repository.setFlagsPerTabOverride(true)
        repository.setFlagsGroupByCategoryForType(FlagType.CYAN, false)

        // CYAN is flat; RED keeps the global grouped default (its per-type key is unset).
        repository.observeFlagsViewSettings(FlagType.CYAN).test {
            assertFalse(awaitItem().groupByCategory)
            cancelAndIgnoreRemainingEvents()
        }
        repository.observeFlagsViewSettings(FlagType.RED).test {
            assertTrue(awaitItem().groupByCategory)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unreadOnly defaults are type-aware on an empty store`() = runTest(dispatcher) {
        // #317: unreadOnly is always per-type with a type-aware default — CYAN (« Mes sujets »)
        // shows the actionable unread subset by default; RED / FAVORITE show everything.
        repository.observeFlagsViewSettings(FlagType.CYAN).test {
            assertTrue("CYAN defaults to unread-only", awaitItem().unreadOnly)
            cancelAndIgnoreRemainingEvents()
        }
        repository.observeFlagsViewSettings(FlagType.RED).test {
            assertFalse("RED defaults to show all", awaitItem().unreadOnly)
            cancelAndIgnoreRemainingEvents()
        }
        repository.observeFlagsViewSettings(FlagType.FAVORITE).test {
            assertFalse("FAVORITE defaults to show all", awaitItem().unreadOnly)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsUnreadOnlyForType persists per type and ignores the per-tab override`() = runTest(dispatcher) {
        // unreadOnly is always per-type: it overrides the type-aware default regardless of the
        // layout per-tab override (which stays OFF here), and never leaks across types.
        repository.setFlagsUnreadOnlyForType(FlagType.CYAN, false)
        repository.setFlagsUnreadOnlyForType(FlagType.RED, true)

        repository.observeFlagsViewSettings(FlagType.CYAN).test {
            assertFalse("CYAN flipped off overrides its true default", awaitItem().unreadOnly)
            cancelAndIgnoreRemainingEvents()
        }
        repository.observeFlagsViewSettings(FlagType.RED).test {
            assertTrue("RED flipped on overrides its false default", awaitItem().unreadOnly)
            cancelAndIgnoreRemainingEvents()
        }
        // FAVORITE untouched → still its type-aware default (false).
        repository.observeFlagsViewSettings(FlagType.FAVORITE).test {
            assertFalse("FAVORITE keeps its default, no cross-type leak", awaitItem().unreadOnly)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeThemeMode defaults to SYSTEM on an empty store`() = runTest(dispatcher) {
        // #286 — SYSTEM is the default (follow the OS), never the enum's first ordinal.
        repository.observeThemeMode().test {
            assertEquals(ThemeMode.SYSTEM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setThemeMode persists and round-trips DARK then LIGHT`() = runTest(dispatcher) {
        repository.setThemeMode(ThemeMode.DARK)
        repository.observeThemeMode().test {
            assertEquals(ThemeMode.DARK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setThemeMode(ThemeMode.LIGHT)
        repository.observeThemeMode().test {
            assertEquals(ThemeMode.LIGHT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `corrupt theme_mode value falls back to SYSTEM instead of crashing`() = runTest(dispatcher) {
        // A value from an older build / manual edit that no longer maps to a ThemeMode must not
        // crash observeThemeMode on ThemeMode.valueOf — it degrades to SYSTEM.
        dataStore.edit { prefs -> prefs[stringPreferencesKey("theme_mode")] = "PURPLE" }

        repository.observeThemeMode().test {
            assertEquals(ThemeMode.SYSTEM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setThemeMode mirrors the value into the bootstrap store`() = runTest(dispatcher) {
        // #386 — the synchronous cold-start mirror must follow every theme write.
        repository.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, themeBootstrapStore.read().themeMode)

        repository.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, themeBootstrapStore.read().themeMode)
    }

    @Test
    fun `setAmoledEnabled mirrors the flag without clobbering the mirrored theme mode`() = runTest(dispatcher) {
        repository.setThemeMode(ThemeMode.DARK)
        repository.setAmoledEnabled(true)

        assertEquals(ThemeBootstrap(ThemeMode.DARK, amoledEnabled = true), themeBootstrapStore.read())
    }

    @Test
    fun `observing the theme backfills an empty mirror from the persisted value`() = runTest(dispatcher) {
        // #386 (Codex review) — users who picked their theme BEFORE the mirror existed must
        // converge on first observation, not keep flashing until they touch the setting again.
        dataStore.edit { prefs -> prefs[stringPreferencesKey("theme_mode")] = ThemeMode.DARK.name }
        assertEquals(ThemeMode.SYSTEM, themeBootstrapStore.read().themeMode)

        repository.observeThemeMode().test {
            assertEquals(ThemeMode.DARK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(ThemeMode.DARK, themeBootstrapStore.read().themeMode)
    }

    @Test
    fun `observeAmoledEnabled defaults to false then persists true`() = runTest(dispatcher) {
        repository.observeAmoledEnabled().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setAmoledEnabled(true)
        repository.observeAmoledEnabled().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeTopicTopBarAutoHide defaults to false then persists true and false`() = runTest(dispatcher) {
        repository.observeTopicTopBarAutoHide().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setTopicTopBarAutoHide(true)
        repository.observeTopicTopBarAutoHide().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setTopicTopBarAutoHide(false)
        repository.observeTopicTopBarAutoHide().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeConfirmBeforePosting defaults to false then persists true and false`() = runTest(dispatcher) {
        // #312 — publishing stays one-tap by default; the guard is strictly opt-in.
        repository.observeConfirmBeforePosting().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setConfirmBeforePosting(true)
        repository.observeConfirmBeforePosting().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setConfirmBeforePosting(false)
        repository.observeConfirmBeforePosting().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saving disabled proxy removes optional fields from effective config`() = runTest(dispatcher) {
        repository.saveProxyConfig(
            ProxyConfig(
                enabled = false,
                host = " ",
                port = null,
                username = " ",
                password = "",
            ),
        )

        val config = repository.readProxyConfigForNetworkBootstrap()

        assertFalse(config.enabled)
        assertFalse(config.isUsable)
        assertEquals("", config.host)
        assertEquals(null, config.port)
        assertEquals(null, config.username)
        assertEquals(null, config.password)
    }
}
