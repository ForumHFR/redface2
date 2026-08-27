package fr.forumhfr.redface2.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.preferences.CategoryFlagFilter
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.AccentColor
import fr.forumhfr.redface2.core.domain.preferences.CategoryBandStyle
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.domain.preferences.FlagGlyphStyle
import fr.forumhfr.redface2.core.domain.preferences.PlusLusIndicatorStyle
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.SmileyPickerDecoration
import fr.forumhfr.redface2.core.domain.preferences.StartScreenBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.StartScreenChoice
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrap
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.WritingSurfacePreset
import fr.forumhfr.redface2.core.model.FlagType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
    private val externalScope = CoroutineScope(dispatcher + SupervisorJob())

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

    /** In-memory [StartScreenBootstrapStore] (#458) — same stance as the theme mirror above. */
    private val startScreenBootstrapStore = object : StartScreenBootstrapStore {
        var stored = StartScreenPreference()
        override fun read(): StartScreenPreference = stored
        override fun write(preference: StartScreenPreference) {
            stored = preference
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
            startScreenBootstrapStore = startScreenBootstrapStore,
            ioDispatcher = dispatcher,
            externalScope = externalScope,
        )
    }

    /**
     * #507 — observable contract: a preference write whose initiating coroutine is cancelled (a
     * settings sub-page popped right after a toggle, cancelling its `viewModelScope`) must still land,
     * because the commit runs on the injected application scope, not the caller's. Built on a paused
     * [StandardTestDispatcher] so the write is provably only *started* (dispatched onto the app scope,
     * caller suspended at `await`) — not yet committed — when the caller is cancelled. Deterministic
     * (no real time / threads): the assertion is the persisted value after the scheduler drains.
     */
    @Test
    fun `setter write lands even when the initiating coroutine is cancelled`() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val dataStoreScope = CoroutineScope(ioDispatcher + Job())
        val appScope = CoroutineScope(ioDispatcher + SupervisorJob())
        val survivalStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFolder.newFile("survival.preferences_pb") },
        )
        val survivalRepository = DataStoreUserPreferencesRepository(
            dataStore = survivalStore,
            themeBootstrapStore = themeBootstrapStore,
            startScreenBootstrapStore = startScreenBootstrapStore,
            ioDispatcher = ioDispatcher,
            externalScope = appScope,
        )

        // setFlagsAutoRefresh defaults to true; flip it to false from a cancellable caller scope.
        val callerScope = CoroutineScope(ioDispatcher + Job())
        callerScope.launch { survivalRepository.setFlagsAutoRefresh(false) }
        runCurrent() // caller dispatched the write onto appScope, then suspended on await — not committed
        callerScope.cancel() // the sub-page is popped before the commit completes
        advanceUntilIdle() // appScope (not the cancelled caller) drives the commit to completion

        assertFalse(survivalRepository.observeFlagsAutoRefresh().first())

        appScope.cancel()
        dataStoreScope.cancel()
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
    fun `markerStyle defaults to STRIPE on an empty store`() = runTest(dispatcher) {
        repository.observeFlagsViewSettings(FlagType.CYAN).test {
            assertEquals(MarkerStyle.STRIPE, awaitItem().markerStyle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsMarkerStyle persists and round-trips PASTILLE then DOT for every tab`() = runTest(dispatcher) {
        // GLOBAL: written once, observed on any tab type.
        repository.setFlagsMarkerStyle(MarkerStyle.PASTILLE)
        repository.observeFlagsViewSettings(FlagType.RED).test {
            assertEquals(MarkerStyle.PASTILLE, awaitItem().markerStyle)
            cancelAndIgnoreRemainingEvents()
        }
        repository.setFlagsMarkerStyle(MarkerStyle.DOT)
        repository.observeFlagsViewSettings(FlagType.FAVORITE).test {
            assertEquals(MarkerStyle.DOT, awaitItem().markerStyle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `corrupt flags_marker_style value falls back to STRIPE instead of crashing`() = runTest(dispatcher) {
        // A value from an older build / manual edit that no longer maps to a MarkerStyle must not
        // crash observeFlagsViewSettings on MarkerStyle.valueOf — it degrades to STRIPE.
        dataStore.edit { prefs -> prefs[stringPreferencesKey("flags_marker_style")] = "WAVY" }

        repository.observeFlagsViewSettings(FlagType.CYAN).test {
            assertEquals(MarkerStyle.STRIPE, awaitItem().markerStyle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markerBorder defaults to false on an empty store`() = runTest(dispatcher) {
        repository.observeFlagsViewSettings(FlagType.CYAN).test {
            assertEquals(false, awaitItem().markerBorder)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsMarkerBorder persists and round-trips for every tab`() = runTest(dispatcher) {
        // #690 GLOBAL: written once, observed on any tab type.
        repository.setFlagsMarkerBorder(true)
        repository.observeFlagsViewSettings(FlagType.RED).test {
            assertEquals(true, awaitItem().markerBorder)
            cancelAndIgnoreRemainingEvents()
        }
        repository.setFlagsMarkerBorder(false)
        repository.observeFlagsViewSettings(FlagType.FAVORITE).test {
            assertEquals(false, awaitItem().markerBorder)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `singleLineTitle defaults to false on an empty store`() = runTest(dispatcher) {
        // #603 GLOBAL: titles wrap to 2 lines by default; the single-line ellipsis is the opt-in.
        repository.observeFlagsViewSettings(FlagType.CYAN).test {
            assertEquals(false, awaitItem().singleLineTitle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsSingleLineTitle persists and round-trips for every tab`() = runTest(dispatcher) {
        // #603 GLOBAL: written once, observed on any tab type.
        repository.setFlagsSingleLineTitle(true)
        repository.observeFlagsViewSettings(FlagType.RED).test {
            assertEquals(true, awaitItem().singleLineTitle)
            cancelAndIgnoreRemainingEvents()
        }
        repository.setFlagsSingleLineTitle(false)
        repository.observeFlagsViewSettings(FlagType.FAVORITE).test {
            assertEquals(false, awaitItem().singleLineTitle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `showLoadingBar defaults to true on an empty store`() = runTest(dispatcher) {
        // #728 GLOBAL: the thin top loading bar is shown by default (opt-out).
        repository.observeFlagsViewSettings(FlagType.CYAN).test {
            assertEquals(true, awaitItem().showLoadingBar)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsShowLoadingBar persists and round-trips for every tab`() = runTest(dispatcher) {
        // #728 GLOBAL: written once, observed on any tab type.
        repository.setFlagsShowLoadingBar(false)
        repository.observeFlagsViewSettings(FlagType.RED).test {
            assertEquals(false, awaitItem().showLoadingBar)
            cancelAndIgnoreRemainingEvents()
        }
        repository.setFlagsShowLoadingBar(true)
        repository.observeFlagsViewSettings(FlagType.FAVORITE).test {
            assertEquals(true, awaitItem().showLoadingBar)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `avatar appearance defaults to borderless on an empty store`() = runTest(dispatcher) {
        // #718 GLOBAL: borderless by default (the #603/#665 look).
        repository.observeAvatarAppearance().test {
            val appearance = awaitItem()
            assertEquals(false, appearance.border)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setAvatarBorder persists and round-trips`() = runTest(dispatcher) {
        // #718 GLOBAL: a single key, surfaced as the bundled AvatarAppearance (border only since #718).
        repository.setAvatarBorder(true)
        repository.observeAvatarAppearance().test {
            val appearance = awaitItem()
            assertEquals(true, appearance.border)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `plusLusIndicatorStyle defaults to Ring on an empty store`() = runTest(dispatcher) {
        repository.observeFlagsViewSettings(FlagType.CYAN).test {
            assertEquals(PlusLusIndicatorStyle.Ring, awaitItem().plusLusIndicatorStyle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsPlusLusIndicatorStyle persists and round-trips Ring then Eye for every tab`() =
        runTest(dispatcher) {
            // #661 GLOBAL: written once, observed on any tab type.
            repository.setFlagsPlusLusIndicatorStyle(PlusLusIndicatorStyle.Ring)
            repository.observeFlagsViewSettings(FlagType.RED).test {
                assertEquals(PlusLusIndicatorStyle.Ring, awaitItem().plusLusIndicatorStyle)
                cancelAndIgnoreRemainingEvents()
            }
            repository.setFlagsPlusLusIndicatorStyle(PlusLusIndicatorStyle.Eye)
            repository.observeFlagsViewSettings(FlagType.FAVORITE).test {
                assertEquals(PlusLusIndicatorStyle.Eye, awaitItem().plusLusIndicatorStyle)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `corrupt flags_plus_lus_indicator_style value falls back to Ring instead of crashing`() =
        runTest(dispatcher) {
            // An unknown value from an older build / manual edit must not crash
            // observeFlagsViewSettings on PlusLusIndicatorStyle.valueOf — it degrades to Ring (default).
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("flags_plus_lus_indicator_style")] = "STAR"
            }

            repository.observeFlagsViewSettings(FlagType.CYAN).test {
                assertEquals(PlusLusIndicatorStyle.Ring, awaitItem().plusLusIndicatorStyle)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `flagGlyphStyle defaults to Flag on an empty store`() = runTest(dispatcher) {
        repository.observeFlagsViewSettings(FlagType.CYAN).test {
            assertEquals(FlagGlyphStyle.Flag, awaitItem().flagGlyphStyle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsGlyphStyle persists and round-trips Dot then Flag for every tab`() =
        runTest(dispatcher) {
            // #603/#665 GLOBAL: written once, observed on any tab type.
            repository.setFlagsGlyphStyle(FlagGlyphStyle.Dot)
            repository.observeFlagsViewSettings(FlagType.RED).test {
                assertEquals(FlagGlyphStyle.Dot, awaitItem().flagGlyphStyle)
                cancelAndIgnoreRemainingEvents()
            }
            repository.setFlagsGlyphStyle(FlagGlyphStyle.Flag)
            repository.observeFlagsViewSettings(FlagType.FAVORITE).test {
                assertEquals(FlagGlyphStyle.Flag, awaitItem().flagGlyphStyle)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `corrupt flags_glyph_style value falls back to Flag instead of crashing`() =
        runTest(dispatcher) {
            // An unknown value from an older build / manual edit must not crash
            // observeFlagsViewSettings on FlagGlyphStyle.valueOf — it degrades to Flag.
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("flags_glyph_style")] = "SQUARE"
            }

            repository.observeFlagsViewSettings(FlagType.CYAN).test {
                assertEquals(FlagGlyphStyle.Flag, awaitItem().flagGlyphStyle)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `categoryBandStyle defaults to MINIMAL on an empty store`() = runTest(dispatcher) {
        repository.observeFlagsViewSettings(FlagType.CYAN).test {
            assertEquals(CategoryBandStyle.MINIMAL, awaitItem().categoryBandStyle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsCategoryBandStyle persists and round-trips SOFT then BULLET for every tab`() = runTest(dispatcher) {
        // GLOBAL: written once, observed on any tab type.
        repository.setFlagsCategoryBandStyle(CategoryBandStyle.SOFT)
        repository.observeFlagsViewSettings(FlagType.RED).test {
            assertEquals(CategoryBandStyle.SOFT, awaitItem().categoryBandStyle)
            cancelAndIgnoreRemainingEvents()
        }
        repository.setFlagsCategoryBandStyle(CategoryBandStyle.BULLET)
        repository.observeFlagsViewSettings(FlagType.FAVORITE).test {
            assertEquals(CategoryBandStyle.BULLET, awaitItem().categoryBandStyle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `corrupt flags_category_band_style value falls back to MINIMAL instead of crashing`() = runTest(dispatcher) {
        // A value from an older build / manual edit that no longer maps to a CategoryBandStyle must
        // not crash observeFlagsViewSettings on CategoryBandStyle.valueOf — it degrades to MINIMAL.
        dataStore.edit { prefs -> prefs[stringPreferencesKey("flags_category_band_style")] = "RAINBOW" }

        repository.observeFlagsViewSettings(FlagType.CYAN).test {
            assertEquals(CategoryBandStyle.MINIMAL, awaitItem().categoryBandStyle)
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
    fun `observeStartScreen defaults to the Drapeaux tab on an empty store`() = runTest(dispatcher) {
        repository.observeStartScreen().test {
            assertEquals(StartScreenPreference(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setStartScreen round-trips a Forum category and mirrors it (#458)`() = runTest(dispatcher) {
        val forumHardware = StartScreenPreference(StartScreenChoice.FORUM, forumCatId = 13)
        repository.setStartScreen(forumHardware)

        repository.observeStartScreen().test {
            assertEquals(forumHardware, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(forumHardware, startScreenBootstrapStore.read())
    }

    @Test
    fun `setStartScreen drops the category when the screen is not FORUM`() = runTest(dispatcher) {
        repository.setStartScreen(StartScreenPreference(StartScreenChoice.FORUM, forumCatId = 13))
        repository.setStartScreen(StartScreenPreference(StartScreenChoice.MESSAGES, forumCatId = 13))

        repository.observeStartScreen().test {
            // The stale category id must not resurface on a later FLAGS/MESSAGES read.
            assertEquals(StartScreenPreference(StartScreenChoice.MESSAGES), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `corrupt start_screen value falls back to FLAGS instead of crashing`() = runTest(dispatcher) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey("start_screen")] = "DESKTOP" }

        repository.observeStartScreen().test {
            assertEquals(StartScreenPreference(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observing the start screen backfills an empty mirror from the persisted value`() = runTest(dispatcher) {
        // #458 — same convergence contract as the theme mirror (#386).
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("start_screen")] = StartScreenChoice.MESSAGES.name
        }
        assertEquals(StartScreenPreference(), startScreenBootstrapStore.read())

        repository.observeStartScreen().test {
            assertEquals(StartScreenPreference(StartScreenChoice.MESSAGES), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(StartScreenPreference(StartScreenChoice.MESSAGES), startScreenBootstrapStore.read())
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
    fun `observeTopicPageFabs defaults to true then persists false and true`() = runTest(dispatcher) {
        // #383 — the ‹/› cluster is the historical behaviour; hiding it is the opt-out.
        repository.observeTopicPageFabs().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setTopicPageFabs(false)
        repository.observeTopicPageFabs().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setTopicPageFabs(true)
        repository.observeTopicPageFabs().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeFoldLongQuotes defaults to true then persists false and true`() = runTest(dispatcher) {
        // #332 — the long-quote fold is the historical behaviour; disabling it is the opt-out.
        repository.observeFoldLongQuotes().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setFoldLongQuotes(false)
        repository.observeFoldLongQuotes().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setFoldLongQuotes(true)
        repository.observeFoldLongQuotes().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeTopicFullWidthPosts defaults to false then persists true and false`() = runTest(dispatcher) {
        // #884 — the card inset is the historical layout; full-width posts are the opt-in.
        repository.observeTopicFullWidthPosts().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setTopicFullWidthPosts(true)
        repository.observeTopicFullWidthPosts().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setTopicFullWidthPosts(false)
        repository.observeTopicFullWidthPosts().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `EgoQuote defaults to true and persists independently from EgoPost`() = runTest(dispatcher) {
        assertTrue(repository.observeTopicEgoQuoteEnabled().first())
        assertTrue(repository.observeTopicEgoPostEnabled().first())

        repository.setTopicEgoQuoteEnabled(false)
        assertFalse(repository.observeTopicEgoQuoteEnabled().first())
        assertTrue(repository.observeTopicEgoPostEnabled().first())

        repository.setTopicEgoQuoteEnabled(true)
        assertTrue(repository.observeTopicEgoQuoteEnabled().first())
        assertTrue(repository.observeTopicEgoPostEnabled().first())
    }

    @Test
    fun `EgoPost defaults to true and persists independently from EgoQuote`() = runTest(dispatcher) {
        assertTrue(repository.observeTopicEgoQuoteEnabled().first())
        assertTrue(repository.observeTopicEgoPostEnabled().first())

        repository.setTopicEgoPostEnabled(false)
        assertTrue(repository.observeTopicEgoQuoteEnabled().first())
        assertFalse(repository.observeTopicEgoPostEnabled().first())

        repository.setTopicEgoPostEnabled(true)
        assertTrue(repository.observeTopicEgoQuoteEnabled().first())
        assertTrue(repository.observeTopicEgoPostEnabled().first())
    }

    @Test
    fun `observeShowScrollbar defaults to true then persists false and true`() = runTest(dispatcher) {
        // #105 — the reading scrollbar is the historical behaviour; disabling it is the opt-out.
        repository.observeShowScrollbar().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setShowScrollbar(false)
        repository.observeShowScrollbar().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setShowScrollbar(true)
        repository.observeShowScrollbar().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeNavBarLabels defaults to true then persists false and true`() = runTest(dispatcher) {
        // #666 — labels under the bottom-nav icons are the historical M3 behaviour; hiding them is the opt-out.
        repository.observeNavBarLabels().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setNavBarLabels(false)
        repository.observeNavBarLabels().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setNavBarLabels(true)
        repository.observeNavBarLabels().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeFunnyEmptyState defaults to false then persists true and false`() = runTest(dispatcher) {
        // #662 — the sober style-A empty state is the default; the smiley wink is strictly opt-in.
        repository.observeFunnyEmptyState().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setFunnyEmptyState(true)
        repository.observeFunnyEmptyState().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setFunnyEmptyState(false)
        repository.observeFunnyEmptyState().test {
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
    fun `observeDebugBoundsOverlay defaults to false then persists true and false`() = runTest(dispatcher) {
        // #445 — the debug bounds overlay is opt-in (dev channel only); an empty store reports false.
        repository.observeDebugBoundsOverlay().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setDebugBoundsOverlay(true)
        repository.observeDebugBoundsOverlay().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setDebugBoundsOverlay(false)
        repository.observeDebugBoundsOverlay().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeHideSystemNavBar defaults to false then persists true and false`() = runTest(dispatcher) {
        // #518 — immersive mode is opt-in; an empty store keeps the system nav bar visible.
        repository.observeHideSystemNavBar().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setHideSystemNavBar(true)
        repository.observeHideSystemNavBar().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setHideSystemNavBar(false)
        repository.observeHideSystemNavBar().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeImmersiveBackButton defaults to true then persists false and true`() = runTest(dispatcher) {
        // #518 follow-up — default ON (only shown while immersive is active); an empty store reports true.
        repository.observeImmersiveBackButton().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setImmersiveBackButton(false)
        repository.observeImmersiveBackButton().test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setImmersiveBackButton(true)
        repository.observeImmersiveBackButton().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeImmersiveNavBarReveal defaults to MANUAL then persists chosen modes`() = runTest(dispatcher) {
        // #518 follow-up — default MANUAL (swipe-only) on an empty store.
        repository.observeImmersiveNavBarReveal().test {
            assertEquals(ImmersiveNavBarReveal.MANUAL, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setImmersiveNavBarReveal(ImmersiveNavBarReveal.AT_BOTTOM)
        repository.observeImmersiveNavBarReveal().test {
            assertEquals(ImmersiveNavBarReveal.AT_BOTTOM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setImmersiveNavBarReveal(ImmersiveNavBarReveal.ON_SCROLL_UP)
        repository.observeImmersiveNavBarReveal().test {
            assertEquals(ImmersiveNavBarReveal.ON_SCROLL_UP, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeAccentColor defaults to ROSE then persists the chosen colour`() = runTest(dispatcher) {
        // TU 2788511 — default ROSE (the historical maroon/rose scheme) on an empty store.
        repository.observeAccentColor().test {
            assertEquals(AccentColor.ROSE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setAccentColor(AccentColor.ROUGE_REDFACE1)
        repository.observeAccentColor().test {
            assertEquals(AccentColor.ROUGE_REDFACE1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setAccentColor(AccentColor.ROSE)
        repository.observeAccentColor().test {
            assertEquals(AccentColor.ROSE, awaitItem())
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

    @Test
    fun `observeUploadProvider defaults to DIBERIE on an empty store`() = runTest(dispatcher) {
        // #459 — DIBERIE is the default (no auth, no Client-ID), never the enum's first ordinal alone.
        repository.observeUploadProvider().test {
            assertEquals(UploadProviderId.DIBERIE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeEditorImageInsert defaults to REDUCED on an empty store`() = runTest(dispatcher) {
        // #459 PR-images follow-up — REDUCED is the default (the HFR "vignette cliquable").
        repository.observeEditorImageInsert().test {
            assertEquals(EditorImageInsert.REDUCED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setEditorImageInsert persists and round-trips FULL then REDUCED`() = runTest(dispatcher) {
        repository.setEditorImageInsert(EditorImageInsert.FULL)
        repository.observeEditorImageInsert().test {
            assertEquals(EditorImageInsert.FULL, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setEditorImageInsert(EditorImageInsert.REDUCED)
        repository.observeEditorImageInsert().test {
            assertEquals(EditorImageInsert.REDUCED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeWritingSurfacePreset defaults to FULL_EDITOR on an empty store`() = runTest(dispatcher) {
        // #951 — FULL_EDITOR is the default (the quick-reply sheet is experimental opt-in),
        // never the enum's first ordinal by chance.
        repository.observeWritingSurfacePreset().test {
            assertEquals(WritingSurfacePreset.FULL_EDITOR, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setWritingSurfacePreset persists and round-trips FULL_EDITOR then SHEET`() = runTest(dispatcher) {
        repository.setWritingSurfacePreset(WritingSurfacePreset.FULL_EDITOR)
        repository.observeWritingSurfacePreset().test {
            assertEquals(WritingSurfacePreset.FULL_EDITOR, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setWritingSurfacePreset(WritingSurfacePreset.SHEET)
        repository.observeWritingSurfacePreset().test {
            assertEquals(WritingSurfacePreset.SHEET, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `corrupt writing_surface_preset value falls back to FULL_EDITOR instead of crashing`() = runTest(dispatcher) {
        // An unknown value (older build / manual edit) must degrade to FULL_EDITOR, not crash valueOf.
        dataStore.edit { prefs -> prefs[stringPreferencesKey("writing_surface_preset")] = "HOLODECK" }

        repository.observeWritingSurfacePreset().test {
            assertEquals(WritingSurfacePreset.FULL_EDITOR, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeDisplayDensity defaults to COMFORT on an empty store`() = runTest(dispatcher) {
        // #287 — COMFORT is the default (historical rhythm), never the enum's first ordinal by chance.
        repository.observeDisplayDensity().test {
            assertEquals(DisplayDensity.COMFORT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setUploadProvider persists and round-trips IMGUR then DIBERIE`() = runTest(dispatcher) {
        repository.setUploadProvider(UploadProviderId.IMGUR)
        repository.observeUploadProvider().test {
            assertEquals(UploadProviderId.IMGUR, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setUploadProvider(UploadProviderId.DIBERIE)
        repository.observeUploadProvider().test {
            assertEquals(UploadProviderId.DIBERIE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setDisplayDensity persists and round-trips COMPACT then COMFORT`() = runTest(dispatcher) {
        repository.setDisplayDensity(DisplayDensity.COMPACT)
        repository.observeDisplayDensity().test {
            assertEquals(DisplayDensity.COMPACT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setDisplayDensity(DisplayDensity.COMFORT)
        repository.observeDisplayDensity().test {
            assertEquals(DisplayDensity.COMFORT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `corrupt upload_provider value falls back to DIBERIE instead of crashing`() = runTest(dispatcher) {
        // A value from an older build / manual edit that no longer maps to a UploadProviderId must
        // not crash observeUploadProvider on valueOf — it degrades to the DIBERIE default.
        dataStore.edit { prefs -> prefs[stringPreferencesKey("upload_provider")] = "PHOTOBUCKET" }

        repository.observeUploadProvider().test {
            assertEquals(UploadProviderId.DIBERIE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `corrupt display_density value falls back to COMFORT instead of crashing`() = runTest(dispatcher) {
        // An unknown value (older build / manual edit) must degrade to COMFORT, not crash valueOf.
        dataStore.edit { prefs -> prefs[stringPreferencesKey("display_density")] = "ULTRA" }

        repository.observeDisplayDensity().test {
            assertEquals(DisplayDensity.COMFORT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeMediaDisplayProfile defaults to M on an empty store`() = runTest(dispatcher) {
        // #973 ([AMENDEMENT-v1.5-2]) — M (×1,5) is the default chosen by XaTriX, never the
        // enum's first ordinal by chance.
        repository.observeMediaDisplayProfile().test {
            assertEquals(MediaDisplayProfile.M, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setMediaDisplayProfile persists and round-trips S then L then M`() = runTest(dispatcher) {
        repository.setMediaDisplayProfile(MediaDisplayProfile.S)
        repository.observeMediaDisplayProfile().test {
            assertEquals(MediaDisplayProfile.S, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setMediaDisplayProfile(MediaDisplayProfile.L)
        repository.observeMediaDisplayProfile().test {
            assertEquals(MediaDisplayProfile.L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setMediaDisplayProfile(MediaDisplayProfile.M)
        repository.observeMediaDisplayProfile().test {
            assertEquals(MediaDisplayProfile.M, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `corrupt media_display_profile value falls back to M instead of crashing`() = runTest(dispatcher) {
        // #973 — an unknown value (older build / manual edit) must degrade to M, not crash valueOf.
        dataStore.edit { prefs -> prefs[stringPreferencesKey("media_display_profile")] = "XXL" }

        repository.observeMediaDisplayProfile().test {
            assertEquals(MediaDisplayProfile.M, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeSmileyPickerDecoration defaults to NONE on an empty store`() = runTest(dispatcher) {
        // #989 — no delimiter is the default; decoration stays opt-in and never changes thumbnail size.
        repository.observeSmileyPickerDecoration().test {
            assertEquals(SmileyPickerDecoration.NONE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setSmileyPickerDecoration persists and round-trips OUTLINE then SEPARATORS then NONE`() =
        runTest(dispatcher) {
            repository.setSmileyPickerDecoration(SmileyPickerDecoration.OUTLINE)
            repository.observeSmileyPickerDecoration().test {
                assertEquals(SmileyPickerDecoration.OUTLINE, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            repository.setSmileyPickerDecoration(SmileyPickerDecoration.SEPARATORS)
            repository.observeSmileyPickerDecoration().test {
                assertEquals(SmileyPickerDecoration.SEPARATORS, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            repository.setSmileyPickerDecoration(SmileyPickerDecoration.NONE)
            repository.observeSmileyPickerDecoration().test {
                assertEquals(SmileyPickerDecoration.NONE, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `corrupt smiley_picker_decoration value falls back to NONE instead of crashing`() = runTest(dispatcher) {
        // #989 — an unknown value (older build / manual edit) must degrade to NONE, not crash valueOf.
        dataStore.edit { prefs -> prefs[stringPreferencesKey("smiley_picker_decoration")] = "DOTTED" }

        repository.observeSmileyPickerDecoration().test {
            assertEquals(SmileyPickerDecoration.NONE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `corrupt accent_color value falls back to ROSE instead of crashing`() = runTest(dispatcher) {
        // TU 2788511 — an unknown value (older build / manual edit) must degrade to ROSE, not crash valueOf.
        dataStore.edit { prefs -> prefs[stringPreferencesKey("accent_color")] = "BOGUS" }

        repository.observeAccentColor().test {
            assertEquals(AccentColor.ROSE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeImgurClientId defaults to empty then round-trips a trimmed value`() = runTest(dispatcher) {
        // #459 (option B) — empty means imgur is unconfigured; the value is stored trimmed.
        repository.observeImgurClientId().test {
            assertEquals("", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setImgurClientId("  abc123  ")
        repository.observeImgurClientId().test {
            assertEquals("abc123", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeFontScale defaults to M on an empty store`() = runTest(dispatcher) {
        repository.observeFontScale().test {
            assertEquals(FontScalePreference.M, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFontScale persists and round-trips S then L`() = runTest(dispatcher) {
        repository.setFontScale(FontScalePreference.S)
        repository.observeFontScale().test {
            assertEquals(FontScalePreference.S, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        repository.setFontScale(FontScalePreference.L)
        repository.observeFontScale().test {
            assertEquals(FontScalePreference.L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `corrupt font_scale value falls back to M instead of crashing`() = runTest(dispatcher) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey("font_scale")] = "XXL" }

        repository.observeFontScale().test {
            assertEquals(FontScalePreference.M, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeForumCategoryFlagFilter defaults to ALL on an empty store`() = runTest(dispatcher) {
        // #1132 — ALL is the default (the normal listing), never the enum's first ordinal by chance.
        repository.observeForumCategoryFlagFilter().test {
            assertEquals(CategoryFlagFilter.ALL, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setForumCategoryFlagFilter persists and round-trips all four values`() = runTest(dispatcher) {
        // #1132 — every value must round-trip, FAVORITES included (treated like the other three).
        for (filter in CategoryFlagFilter.entries) {
            repository.setForumCategoryFlagFilter(filter)
            repository.observeForumCategoryFlagFilter().test {
                assertEquals(filter, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `corrupt forum_category_flag_filter value falls back to ALL instead of crashing`() = runTest(dispatcher) {
        // An unknown value (older build / manual edit) must degrade to ALL, not crash valueOf.
        dataStore.edit { prefs -> prefs[stringPreferencesKey("forum_category_flag_filter")] = "STARRED" }

        repository.observeForumCategoryFlagFilter().test {
            assertEquals(CategoryFlagFilter.ALL, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `forum flag-filter reads come from the in-memory cache, not a divergent disk value`() = runTest(dispatcher) {
        // #1132 bug A — the cache is the in-memory source of truth. Once a value is set in-session, a
        // later reader (the next category's ViewModel) sees THAT value, even if the disk holds a
        // different one (e.g. an older value not yet overwritten by the async commit). Pre-fix, observe
        // mapped `dataStore.data` live and would return the disk value — this test would fail.
        repository.setForumCategoryFlagFilter(CategoryFlagFilter.READ)
        assertEquals(CategoryFlagFilter.READ, repository.observeForumCategoryFlagFilter().first())

        // Mutate the DISK directly to a different value behind the cache's back.
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("forum_category_flag_filter")] = CategoryFlagFilter.FAVORITES.name
        }

        // A fresh collector still returns the cached READ — the disk change is not re-read.
        assertEquals(CategoryFlagFilter.READ, repository.observeForumCategoryFlagFilter().first())
    }

    @Test
    fun `two rapid forum flag-filter writes are cache-visible before commit and persist last-wins`() = runTest {
        // #1132 bugs A+B — cache-first decouples the in-session read from the async disk commit, and
        // the last choice wins both in the cache and on disk. Built on a StandardTestDispatcher so the
        // disk commits are provably only STARTED (dispatched onto the app scope) — not committed —
        // after `runCurrent`, mirroring the #507 survival test's harness.
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val dataStoreScope = CoroutineScope(ioDispatcher + Job())
        val appScope = CoroutineScope(ioDispatcher + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFolder.newFile("forum_filter_ordering.preferences_pb") },
        )
        val repo = DataStoreUserPreferencesRepository(
            dataStore = store,
            themeBootstrapStore = themeBootstrapStore,
            startScreenBootstrapStore = startScreenBootstrapStore,
            ioDispatcher = ioDispatcher,
            externalScope = appScope,
        )

        // Two rapid choices, each on its own caller (mirrors two quick selectFlagFilter taps, each on
        // its own viewModelScope.launch). Their async disk commits stay queued on appScope.
        val callers = CoroutineScope(ioDispatcher + Job())
        callers.launch { repo.setForumCategoryFlagFilter(CategoryFlagFilter.READ) }
        callers.launch { repo.setForumCategoryFlagFilter(CategoryFlagFilter.FAVORITES) }
        runCurrent() // runs the SYNCHRONOUS cache updates; the disk commits are dispatched, not committed

        // In-session: a fresh collector already sees the last choice from the cache, with NO disk
        // commit yet. Pre-fix (observe mapped `dataStore.data`) this would read the empty disk → ALL.
        assertEquals(CategoryFlagFilter.FAVORITES, repo.observeForumCategoryFlagFilter().first())

        advanceUntilIdle() // let both queued disk commits run

        // Cross-restart: a fresh repository reading the same file cold sees the last choice on disk.
        val fresh = DataStoreUserPreferencesRepository(
            dataStore = store,
            themeBootstrapStore = themeBootstrapStore,
            startScreenBootstrapStore = startScreenBootstrapStore,
            ioDispatcher = ioDispatcher,
            externalScope = appScope,
        )
        assertEquals(CategoryFlagFilter.FAVORITES, fresh.observeForumCategoryFlagFilter().first())

        callers.cancel()
        appScope.cancel()
        dataStoreScope.cancel()
    }
}
