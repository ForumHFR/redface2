package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.cache.ImageCacheMaintenance
import fr.forumhfr.redface2.core.domain.cache.TopicCacheMaintenance
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.CategoryBandStyle
import fr.forumhfr.redface2.core.domain.preferences.FlagGlyphStyle
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val repository = FakeUserPreferencesRepository()
    private val topicCacheMaintenance = FakeTopicCacheMaintenance()
    private val imageCacheMaintenance = FakeImageCacheMaintenance()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init hydrates proxy fields from preferences`() = runTest {
        repository.emit(
            ProxyConfig(
                enabled = true,
                host = "proxy.local",
                port = 8_080,
                username = "user",
                password = "secret",
            ),
        )

        val viewModel = newViewModel()
        val state = viewModel.state.value

        assertTrue(state.proxyEnabled)
        assertEquals("proxy.local", state.proxyHost)
        assertEquals("8080", state.proxyPort)
        assertEquals("user", state.proxyUsername)
        assertEquals("secret", state.proxyPassword)
    }

    @Test
    fun `save rejects enabled proxy with missing host or invalid port`() = runTest {
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.ProxyEnabledChanged(true))
        viewModel.submit(SettingsIntent.ProxyHostChanged(""))
        viewModel.submit(SettingsIntent.ProxyPortChanged("70000"))
        viewModel.submit(SettingsIntent.SaveProxyClicked)

        assertEquals(SettingsError.InvalidProxy, viewModel.state.value.error)
        assertFalse(viewModel.state.value.saved)
        assertEquals(0, repository.saveCalls)
    }

    @Test
    fun `save persists normalized proxy config`() = runTest {
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.ProxyEnabledChanged(true))
        viewModel.submit(SettingsIntent.ProxyHostChanged(" proxy.local "))
        viewModel.submit(SettingsIntent.ProxyPortChanged("8080abc"))
        viewModel.submit(SettingsIntent.ProxyUsernameChanged(" user "))
        viewModel.submit(SettingsIntent.ProxyPasswordChanged("secret"))
        viewModel.submit(SettingsIntent.SaveProxyClicked)

        val saved = requireNotNull(repository.lastSaved)
        assertTrue(saved.enabled)
        assertEquals("proxy.local", saved.host)
        assertEquals(8_080, saved.port)
        assertEquals("user", saved.username)
        assertEquals("secret", saved.password)
        assertTrue(viewModel.state.value.saved)
        assertFalse(viewModel.state.value.isSaving)
    }

    @Test
    fun `save reports persist failure and re-enables saving when repository throws`() = runTest {
        repository.failOnSave = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.ProxyEnabledChanged(true))
        viewModel.submit(SettingsIntent.ProxyHostChanged("proxy.local"))
        viewModel.submit(SettingsIntent.ProxyPortChanged("8080"))
        viewModel.submit(SettingsIntent.SaveProxyClicked)

        val state = viewModel.state.value
        assertEquals(SettingsError.PersistFailed, state.error)
        assertFalse(state.saved)
        assertFalse(state.isSaving)
        assertTrue(state.canSave)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Topic cache maintenance — "Vider le cache des topics" action
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `ClearTopicCacheClicked opens the confirmation dialog without touching the cache`() = runTest {
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)

        assertTrue(viewModel.state.value.showClearTopicCacheConfirm)
        assertEquals(
            "clear() must NOT run until the user confirms",
            0,
            topicCacheMaintenance.clearCalls,
        )
    }

    @Test
    fun `ClearTopicCacheDismissed closes the dialog without calling clear`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)

        viewModel.submit(SettingsIntent.ClearTopicCacheDismissed)

        assertFalse(viewModel.state.value.showClearTopicCacheConfirm)
        assertEquals(0, topicCacheMaintenance.clearCalls)
        assertNull(viewModel.state.value.topicCacheClearResult)
    }

    @Test
    fun `ClearTopicCacheConfirmed runs clear and surfaces Success`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)

        viewModel.submit(SettingsIntent.ClearTopicCacheConfirmed)

        val state = viewModel.state.value
        assertEquals(1, topicCacheMaintenance.clearCalls)
        assertFalse("dialog must close at confirm time", state.showClearTopicCacheConfirm)
        assertFalse("isClearing must flip back to false after success", state.isClearingTopicCache)
        assertEquals(TopicCacheClearResult.Success, state.topicCacheClearResult)
    }

    @Test
    fun `ClearTopicCacheConfirmed exposes in-progress state while clear is running`() = runTest {
        topicCacheMaintenance.blockUntil = CompletableDeferred()
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)

        viewModel.submit(SettingsIntent.ClearTopicCacheConfirmed)

        assertTrue(viewModel.state.value.isClearingTopicCache)
        assertFalse(viewModel.state.value.canClearTopicCache)

        topicCacheMaintenance.blockUntil?.complete(Unit)
        yield()

        assertFalse(viewModel.state.value.isClearingTopicCache)
        assertEquals(TopicCacheClearResult.Success, viewModel.state.value.topicCacheClearResult)
    }

    @Test
    fun `ClearTopicCacheConfirmed surfaces Failure when the maintenance call throws`() = runTest {
        topicCacheMaintenance.failOnClear = true
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)

        viewModel.submit(SettingsIntent.ClearTopicCacheConfirmed)

        val state = viewModel.state.value
        assertEquals(1, topicCacheMaintenance.clearCalls)
        assertFalse(state.isClearingTopicCache)
        assertEquals(TopicCacheClearResult.Failure, state.topicCacheClearResult)
        // Proxy state stays untouched — the two domains must not bleed.
        assertNull(state.error)
        assertFalse(state.saved)
    }

    @Test
    fun `re-clicking after a previous result resets the inline message`() = runTest {
        topicCacheMaintenance.failOnClear = true
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)
        viewModel.submit(SettingsIntent.ClearTopicCacheConfirmed)
        assertEquals(TopicCacheClearResult.Failure, viewModel.state.value.topicCacheClearResult)

        // Second pass — user retries
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)

        // The previous result must be cleared when the new confirmation opens, so the
        // dialog isn't surfaced over a stale "échec" message that no longer reflects the
        // pending operation.
        assertTrue(viewModel.state.value.showClearTopicCacheConfirm)
        assertNull(viewModel.state.value.topicCacheClearResult)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Image cache maintenance — "Vider le cache des images" action (#314)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `ClearImageCacheClicked opens the confirmation dialog without touching the cache`() = runTest {
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.ClearImageCacheClicked)

        assertTrue(viewModel.state.value.showClearImageCacheConfirm)
        assertEquals(
            "clear() must NOT run until the user confirms",
            0,
            imageCacheMaintenance.clearCalls,
        )
    }

    @Test
    fun `ClearImageCacheDismissed closes the dialog without calling clear`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearImageCacheClicked)

        viewModel.submit(SettingsIntent.ClearImageCacheDismissed)

        assertFalse(viewModel.state.value.showClearImageCacheConfirm)
        assertEquals(0, imageCacheMaintenance.clearCalls)
        assertNull(viewModel.state.value.imageCacheClearResult)
    }

    @Test
    fun `ClearImageCacheConfirmed runs clear and surfaces Success`() = runTest {
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearImageCacheClicked)

        viewModel.submit(SettingsIntent.ClearImageCacheConfirmed)

        val state = viewModel.state.value
        assertEquals(1, imageCacheMaintenance.clearCalls)
        assertFalse("dialog must close at confirm time", state.showClearImageCacheConfirm)
        assertFalse("isClearing must flip back to false after success", state.isClearingImageCache)
        assertEquals(ImageCacheClearResult.Success, state.imageCacheClearResult)
    }

    @Test
    fun `ClearImageCacheConfirmed ignores a re-entrant confirm while a clear is running`() = runTest {
        // Codex final-review finding (#314): the dialog's confirm button is not gated by
        // state — a double-tap before recomposition must not start two concurrent clears.
        val gate = CompletableDeferred<Unit>()
        imageCacheMaintenance.blockUntil = gate
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearImageCacheClicked)

        viewModel.submit(SettingsIntent.ClearImageCacheConfirmed)
        viewModel.submit(SettingsIntent.ClearImageCacheConfirmed)

        assertEquals(1, imageCacheMaintenance.clearCalls)
        gate.complete(Unit)
        assertEquals(ImageCacheClearResult.Success, viewModel.state.value.imageCacheClearResult)
        assertFalse(viewModel.state.value.isClearingImageCache)
    }

    @Test
    fun `ClearImageCacheConfirmed exposes in-progress state while clear is running`() = runTest {
        imageCacheMaintenance.blockUntil = CompletableDeferred()
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearImageCacheClicked)

        viewModel.submit(SettingsIntent.ClearImageCacheConfirmed)

        assertTrue(viewModel.state.value.isClearingImageCache)
        assertFalse(viewModel.state.value.canClearImageCache)

        imageCacheMaintenance.blockUntil?.complete(Unit)
        yield()

        assertFalse(viewModel.state.value.isClearingImageCache)
        assertEquals(ImageCacheClearResult.Success, viewModel.state.value.imageCacheClearResult)
    }

    @Test
    fun `ClearImageCacheConfirmed surfaces Failure when the maintenance call throws`() = runTest {
        imageCacheMaintenance.failOnClear = true
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearImageCacheClicked)

        viewModel.submit(SettingsIntent.ClearImageCacheConfirmed)

        val state = viewModel.state.value
        assertEquals(1, imageCacheMaintenance.clearCalls)
        assertFalse(state.isClearingImageCache)
        assertEquals(ImageCacheClearResult.Failure, state.imageCacheClearResult)
        // Proxy state stays untouched — the two domains must not bleed.
        assertNull(state.error)
        assertFalse(state.saved)
    }

    @Test
    fun `re-clicking the image clear after a previous result resets the inline message`() = runTest {
        imageCacheMaintenance.failOnClear = true
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearImageCacheClicked)
        viewModel.submit(SettingsIntent.ClearImageCacheConfirmed)
        assertEquals(ImageCacheClearResult.Failure, viewModel.state.value.imageCacheClearResult)

        // Second pass — user retries.
        viewModel.submit(SettingsIntent.ClearImageCacheClicked)

        assertTrue(viewModel.state.value.showClearImageCacheConfirm)
        assertNull(viewModel.state.value.imageCacheClearResult)
    }

    @Test
    fun `image and topic clear flows stay independent`() = runTest {
        // The two Maintenance entries live side by side in the same card — dedicated state
        // fields must guarantee a click on one never opens/repaints the other (#314 design
        // note: no shared topicCacheClearResult/isClearingTopicCache).
        imageCacheMaintenance.failOnClear = true
        val viewModel = newViewModel()
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)
        viewModel.submit(SettingsIntent.ClearTopicCacheConfirmed)
        assertEquals(TopicCacheClearResult.Success, viewModel.state.value.topicCacheClearResult)

        viewModel.submit(SettingsIntent.ClearImageCacheClicked)
        assertFalse(
            "the image dialog must not be mirrored on the topic flag",
            viewModel.state.value.showClearTopicCacheConfirm,
        )
        viewModel.submit(SettingsIntent.ClearImageCacheConfirmed)

        val state = viewModel.state.value
        assertEquals(ImageCacheClearResult.Failure, state.imageCacheClearResult)
        assertEquals(
            "the image failure must not erase the topic success feedback",
            TopicCacheClearResult.Success,
            state.topicCacheClearResult,
        )
        assertEquals(1, topicCacheMaintenance.clearCalls)
        assertEquals(1, imageCacheMaintenance.clearCalls)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Ignore topic cache — alpha toggle
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `init hydrates ignoreTopicCache from the persisted preference`() = runTest {
        repository.emitIgnoreTopicCache(true)

        val viewModel = newViewModel()

        assertTrue(viewModel.state.value.ignoreTopicCache)
        assertFalse(viewModel.state.value.ignoreTopicCacheError)
    }

    @Test
    fun `IgnoreTopicCacheChanged true persists the new value and exposes it`() = runTest {
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.IgnoreTopicCacheChanged(true))

        val state = viewModel.state.value
        assertTrue(state.ignoreTopicCache)
        assertFalse(state.isUpdatingIgnoreTopicCache)
        assertFalse(state.ignoreTopicCacheError)
        assertEquals(1, repository.ignoreTopicCacheSetCalls)
        assertEquals(true, repository.lastIgnoreTopicCacheSet)
    }

    @Test
    fun `IgnoreTopicCacheChanged failure reverts the optimistic flip and raises the error flag`() = runTest {
        repository.failOnIgnoreTopicCacheSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.IgnoreTopicCacheChanged(true))

        val state = viewModel.state.value
        assertFalse("optimistic flip must revert on failure", state.ignoreTopicCache)
        assertFalse(state.isUpdatingIgnoreTopicCache)
        assertTrue("a topic-cache-specific error flag must be raised", state.ignoreTopicCacheError)
        // Pin the contract: the DataStore write must actually be attempted before the failure
        // is surfaced. Without this assertion a future refactor that short-circuits the call
        // (e.g. early-return on same-value) would still pass since no exception is raised.
        assertEquals(
            "DataStore write must have been attempted before the failure was surfaced",
            1,
            repository.ignoreTopicCacheSetCalls,
        )
        // The proxy-scoped error remains untouched — the two domains must not bleed.
        assertNull("proxy SettingsError must not be set by an ignore-topic-cache failure", state.error)
        assertFalse(state.saved)
    }

    @Test
    fun `IgnoreTopicCacheChanged exposes in-progress state while DataStore is writing`() = runTest {
        // Block the fake's setIgnoreTopicCache until we explicitly release it, so we can observe
        // the intermediate state. Without this gate `runTest` would drain the launch in one shot
        // and we'd only see the final post-write state.
        val gate = CompletableDeferred<Unit>()
        repository.blockIgnoreTopicCacheSetUntil = gate
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.IgnoreTopicCacheChanged(true))

        // Optimistic flip applied + gate raised before the launch suspends on `await()`.
        val midFlightState = viewModel.state.value
        assertTrue(
            "optimistic value must be exposed before DataStore confirms",
            midFlightState.ignoreTopicCache,
        )
        assertTrue(
            "gate flag must keep the Switch disabled while the write is in flight",
            midFlightState.isUpdatingIgnoreTopicCache,
        )
        assertFalse(
            "canToggleIgnoreTopicCache must be false while the write is in flight",
            midFlightState.canToggleIgnoreTopicCache,
        )

        gate.complete(Unit)

        val finalState = viewModel.state.value
        assertFalse("gate must release after the write completes", finalState.isUpdatingIgnoreTopicCache)
        assertTrue(finalState.ignoreTopicCache)
        assertEquals(1, repository.ignoreTopicCacheSetCalls)
    }

    @Test
    fun `hydration race - a stale initial DataStore emission must not overwrite a local toggle change`() = runTest {
        // Reproduce the startup race: the init coroutine subscribes to
        // observeIgnoreTopicCache() and suspends on .first() because the override emits
        // nothing yet. The user then flips the toggle locally (optimistic true + write
        // succeeds). Finally, the still-suspended init resumes and tries to apply a
        // stale `false` — the guard must skip the apply.
        val initialHydrationFlow = MutableSharedFlow<Boolean>(replay = 0)
        repository.ignoreTopicCacheObserveOverride = initialHydrationFlow
        val viewModel = newViewModel()

        // Step 1: init is now suspended on `initialHydrationFlow.first()`.
        // Step 2: user flips the toggle. The optimistic flip + DataStore write run
        // synchronously under UnconfinedTestDispatcher and complete before we return here.
        viewModel.submit(SettingsIntent.IgnoreTopicCacheChanged(true))
        assertTrue(
            "optimistic flip must reach the state synchronously",
            viewModel.state.value.ignoreTopicCache,
        )
        assertTrue(viewModel.state.value.ignoreTopicCacheTouchedLocally)
        assertFalse(
            "write must have completed under the UnconfinedTestDispatcher",
            viewModel.state.value.isUpdatingIgnoreTopicCache,
        )

        // Step 3: the late hydration finally produces a stale `false`. On the buggy code
        // this overwrites the local true; on the fixed code the guard skips the apply.
        initialHydrationFlow.emit(false)

        val finalState = viewModel.state.value
        assertTrue(
            "stale initial DataStore hydration must NOT overwrite the local toggle change",
            finalState.ignoreTopicCache,
        )
        assertFalse(finalState.isUpdatingIgnoreTopicCache)
        assertFalse(finalState.ignoreTopicCacheError)
        assertEquals(1, repository.ignoreTopicCacheSetCalls)
        assertEquals(true, repository.lastIgnoreTopicCacheSet)
    }

    @Test
    fun `hydration race - failure path keeps the toggle latched to the reverted previous value`() = runTest {
        // Symmetric guard for the failure branch: the local flip happens first, then DataStore
        // write fails (revert to previous=false + error flag), then a stale hydration arrives.
        // The reverted state must survive — the hydration must NOT silently mutate it back to
        // anything else, even if the stale value happens to match the reverted one.
        repository.failOnIgnoreTopicCacheSet = true
        val initialHydrationFlow = MutableSharedFlow<Boolean>(replay = 0)
        repository.ignoreTopicCacheObserveOverride = initialHydrationFlow
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.IgnoreTopicCacheChanged(true))
        // After failure: reverted to false + error flag raised; touchedLocally remains true.
        val midState = viewModel.state.value
        assertFalse(midState.ignoreTopicCache)
        assertTrue(midState.ignoreTopicCacheError)
        assertTrue(midState.ignoreTopicCacheTouchedLocally)

        // Stale hydration arrives — must be ignored because touchedLocally == true.
        initialHydrationFlow.emit(true)

        val finalState = viewModel.state.value
        assertFalse(
            "stale hydration must not flip the toggle back on after a failed write",
            finalState.ignoreTopicCache,
        )
        assertTrue(finalState.ignoreTopicCacheError)
    }

    @Test
    fun `IgnoreTopicCacheChanged does not touch proxy or clear-cache state`() = runTest {
        repository.emit(
            ProxyConfig(enabled = true, host = "proxy.local", port = 8_080, username = "user", password = "secret"),
        )
        val viewModel = newViewModel()
        // Trigger a previous clear-cache result to make sure the toggle does not wipe it.
        viewModel.submit(SettingsIntent.ClearTopicCacheClicked)
        viewModel.submit(SettingsIntent.ClearTopicCacheConfirmed)
        assertEquals(TopicCacheClearResult.Success, viewModel.state.value.topicCacheClearResult)

        viewModel.submit(SettingsIntent.IgnoreTopicCacheChanged(true))

        val state = viewModel.state.value
        // Proxy state untouched.
        assertTrue(state.proxyEnabled)
        assertEquals("proxy.local", state.proxyHost)
        assertEquals("8080", state.proxyPort)
        // Clear-cache result preserved — toggling one alpha tool must not erase the feedback
        // from the other (orthogonal domains).
        assertEquals(TopicCacheClearResult.Success, state.topicCacheClearResult)
    }

    @Test
    fun `init hydrates flags view preferences from storage`() = runTest {
        repository.emitFlagsGroupByCategory(false)
        repository.emitFlagsHideReadCategories(true)

        val viewModel = newViewModel()
        val state = viewModel.state.value

        assertFalse(state.flagsGroupByCategory)
        assertTrue(state.flagsHideReadCategories)
    }

    @Test
    fun `FlagsGroupByCategoryChanged persists the flip and clears the updating flag`() = runTest {
        val viewModel = newViewModel()
        assertTrue("grouped is the default", viewModel.state.value.flagsGroupByCategory)

        viewModel.submit(SettingsIntent.FlagsGroupByCategoryChanged(false))

        assertFalse(viewModel.state.value.flagsGroupByCategory)
        assertFalse(viewModel.state.value.isUpdatingFlagsGroupByCategory)
        assertFalse(viewModel.state.value.flagsGroupByCategoryError)
        assertEquals(1, repository.flagsGroupByCategorySetCalls)
    }

    @Test
    fun `FunnyEmptyStateChanged persists the flip and clears the updating flag`() = runTest {
        // #662 — the smiley empty state is opt-in (default off); toggling persists and settles.
        val viewModel = newViewModel()
        assertFalse("sober empty state is the default", viewModel.state.value.funnyEmptyState)

        viewModel.submit(SettingsIntent.FunnyEmptyStateChanged(true))

        assertTrue(viewModel.state.value.funnyEmptyState)
        assertFalse(viewModel.state.value.isUpdatingFunnyEmptyState)
        assertFalse(viewModel.state.value.funnyEmptyStateError)
        assertEquals(1, repository.funnyEmptyStateSetCalls)
    }

    @Test
    fun `hide-read categories switch is disabled while the flat flags view is selected`() = runTest {
        val viewModel = newViewModel()
        assertTrue(viewModel.state.value.canToggleFlagsHideReadCategories)

        viewModel.submit(SettingsIntent.FlagsGroupByCategoryChanged(false))

        assertFalse(viewModel.state.value.flagsGroupByCategory)
        assertFalse(
            "hide-read categories has no effect in flat view, so the Settings switch must be disabled",
            viewModel.state.value.canToggleFlagsHideReadCategories,
        )

        viewModel.submit(SettingsIntent.FlagsGroupByCategoryChanged(true))

        assertTrue(viewModel.state.value.flagsGroupByCategory)
        assertTrue(viewModel.state.value.canToggleFlagsHideReadCategories)
    }

    @Test
    fun `hide-read switch stays enabled under per-tab override even when global grouped is off`() = runTest {
        // #309 Codex review: with the per-tab override on, the global hide-read still serves as the
        // fallback for a tab grouped per-type, so the Settings switch must NOT be disabled just
        // because the GLOBAL grouped toggle is off.
        repository.emitFlagsPerTabOverride(true)
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.FlagsGroupByCategoryChanged(false))

        assertFalse(viewModel.state.value.flagsGroupByCategory)
        assertTrue(
            "per-tab override keeps the global hide-read editable as a fallback",
            viewModel.state.value.canToggleFlagsHideReadCategories,
        )
    }

    @Test
    fun `FlagsGroupByCategoryChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnFlagsGroupByCategorySet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.FlagsGroupByCategoryChanged(false))

        assertTrue("must revert to the previous value on failure", viewModel.state.value.flagsGroupByCategory)
        assertFalse(viewModel.state.value.isUpdatingFlagsGroupByCategory)
        assertTrue(viewModel.state.value.flagsGroupByCategoryError)
    }

    @Test
    fun `FlagsHideReadCategoriesChanged persists the flip`() = runTest {
        val viewModel = newViewModel()
        assertFalse("hide-read is off by default (web parity)", viewModel.state.value.flagsHideReadCategories)

        viewModel.submit(SettingsIntent.FlagsHideReadCategoriesChanged(true))

        assertTrue(viewModel.state.value.flagsHideReadCategories)
        assertFalse(viewModel.state.value.isUpdatingFlagsHideReadCategories)
        assertEquals(1, repository.flagsHideReadCategoriesSetCalls)
    }

    @Test
    fun `FlagsHideReadCategoriesChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnFlagsHideReadCategoriesSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.FlagsHideReadCategoriesChanged(true))

        assertFalse("must revert to the previous value on failure", viewModel.state.value.flagsHideReadCategories)
        assertFalse(viewModel.state.value.isUpdatingFlagsHideReadCategories)
        assertTrue(viewModel.state.value.flagsHideReadCategoriesError)
    }

    @Test
    fun `FlagsPerTabOverrideChanged persists the flip and clears the updating flag`() = runTest {
        val viewModel = newViewModel()
        assertFalse("per-tab override is off by default", viewModel.state.value.flagsPerTabOverride)

        viewModel.submit(SettingsIntent.FlagsPerTabOverrideChanged(true))

        assertTrue(viewModel.state.value.flagsPerTabOverride)
        assertFalse(viewModel.state.value.isUpdatingFlagsPerTabOverride)
        assertFalse(viewModel.state.value.flagsPerTabOverrideError)
        assertEquals(1, repository.flagsPerTabOverrideSetCalls)
    }

    @Test
    fun `FlagsPerTabOverrideChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnFlagsPerTabOverrideSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.FlagsPerTabOverrideChanged(true))

        assertFalse("must revert to the previous value on failure", viewModel.state.value.flagsPerTabOverride)
        assertFalse(viewModel.state.value.isUpdatingFlagsPerTabOverride)
        assertTrue(viewModel.state.value.flagsPerTabOverrideError)
    }

    @Test
    fun `per-tab override hydrates from the persisted value on init`() = runTest {
        repository.emitFlagsPerTabOverride(true)

        val viewModel = newViewModel()

        assertTrue("init must hydrate the per-tab override from DataStore", viewModel.state.value.flagsPerTabOverride)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Theme preferences (#286)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `init hydrates theme preferences from storage`() = runTest {
        repository.emitThemeMode(ThemeMode.DARK)
        repository.emitAmoledEnabled(true)

        val viewModel = newViewModel()
        val state = viewModel.state.value

        assertEquals(ThemeMode.DARK, state.themeMode)
        assertTrue(state.amoledEnabled)
    }

    @Test
    fun `ThemeModeChanged persists the new mode and clears the updating flag`() = runTest {
        val viewModel = newViewModel()
        assertEquals("SYSTEM is the default", ThemeMode.SYSTEM, viewModel.state.value.themeMode)

        viewModel.submit(SettingsIntent.ThemeModeChanged(ThemeMode.DARK))

        val state = viewModel.state.value
        assertEquals(ThemeMode.DARK, state.themeMode)
        assertFalse(state.isUpdatingThemeMode)
        assertFalse(state.themeModeError)
        assertEquals(1, repository.themeModeSetCalls)
        assertEquals(ThemeMode.DARK, repository.lastThemeModeSet)
    }

    @Test
    fun `ThemeModeChanged reverts to the previous mode and raises the error flag on persist failure`() = runTest {
        repository.failOnThemeModeSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.ThemeModeChanged(ThemeMode.LIGHT))

        val state = viewModel.state.value
        assertEquals("must revert to the previous mode on failure", ThemeMode.SYSTEM, state.themeMode)
        assertFalse(state.isUpdatingThemeMode)
        assertTrue(state.themeModeError)
    }

    @Test
    fun `AmoledEnabledChanged persists the flip`() = runTest {
        val viewModel = newViewModel()
        assertFalse("AMOLED is off by default", viewModel.state.value.amoledEnabled)

        viewModel.submit(SettingsIntent.AmoledEnabledChanged(true))

        assertTrue(viewModel.state.value.amoledEnabled)
        assertFalse(viewModel.state.value.isUpdatingAmoled)
        assertEquals(1, repository.amoledSetCalls)
    }

    @Test
    fun `AmoledEnabledChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnAmoledSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.AmoledEnabledChanged(true))

        assertFalse("must revert to the previous value on failure", viewModel.state.value.amoledEnabled)
        assertFalse(viewModel.state.value.isUpdatingAmoled)
        assertTrue(viewModel.state.value.amoledError)
    }

    @Test
    fun `init hydrates reading display presets from storage`() = runTest {
        repository.emitDisplayDensity(DisplayDensity.COMPACT)
        repository.emitFontScale(FontScalePreference.L)

        val viewModel = newViewModel()
        val state = viewModel.state.value

        assertEquals(DisplayDensity.COMPACT, state.displayDensity)
        assertEquals(FontScalePreference.L, state.fontScale)
    }

    @Test
    fun `DisplayDensityChanged persists the new preset and clears the updating flag`() = runTest {
        val viewModel = newViewModel()
        assertEquals("COMFORT is the default", DisplayDensity.COMFORT, viewModel.state.value.displayDensity)

        viewModel.submit(SettingsIntent.DisplayDensityChanged(DisplayDensity.COMPACT))

        val state = viewModel.state.value
        assertEquals(DisplayDensity.COMPACT, state.displayDensity)
        assertFalse(state.isUpdatingDisplayDensity)
        assertFalse(state.displayDensityError)
        assertEquals(1, repository.displayDensitySetCalls)
        assertEquals(DisplayDensity.COMPACT, repository.lastDisplayDensitySet)
    }

    @Test
    fun `DisplayDensityChanged reverts to the previous preset and raises the error flag on persist failure`() =
        runTest {
            repository.failOnDisplayDensitySet = true
            val viewModel = newViewModel()

            viewModel.submit(SettingsIntent.DisplayDensityChanged(DisplayDensity.COMPACT))

            val state = viewModel.state.value
            assertEquals("must revert to the previous preset on failure", DisplayDensity.COMFORT, state.displayDensity)
            assertFalse(state.isUpdatingDisplayDensity)
            assertTrue(state.displayDensityError)
        }

    @Test
    fun `FontScaleChanged persists the new preset and clears the updating flag`() = runTest {
        val viewModel = newViewModel()
        assertEquals("M is the default", FontScalePreference.M, viewModel.state.value.fontScale)

        viewModel.submit(SettingsIntent.FontScaleChanged(FontScalePreference.L))

        val state = viewModel.state.value
        assertEquals(FontScalePreference.L, state.fontScale)
        assertFalse(state.isUpdatingFontScale)
        assertFalse(state.fontScaleError)
        assertEquals(1, repository.fontScaleSetCalls)
        assertEquals(FontScalePreference.L, repository.lastFontScaleSet)
    }

    @Test
    fun `FontScaleChanged reverts to the previous preset and raises the error flag on persist failure`() = runTest {
        repository.failOnFontScaleSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.FontScaleChanged(FontScalePreference.S))

        val state = viewModel.state.value
        assertEquals("must revert to the previous preset on failure", FontScalePreference.M, state.fontScale)
        assertFalse(state.isUpdatingFontScale)
        assertTrue(state.fontScaleError)
    }

    @Test
    fun `TopicTopBarAutoHideChanged persists the flip`() = runTest {
        val viewModel = newViewModel()
        assertFalse("auto-hide is off by default", viewModel.state.value.topicTopBarAutoHide)

        viewModel.submit(SettingsIntent.TopicTopBarAutoHideChanged(true))

        assertTrue(viewModel.state.value.topicTopBarAutoHide)
        assertFalse(viewModel.state.value.isUpdatingTopicTopBarAutoHide)
        assertEquals(1, repository.topicTopBarAutoHideSetCalls)
    }

    @Test
    fun `TopicTopBarAutoHideChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnTopicTopBarAutoHideSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.TopicTopBarAutoHideChanged(true))

        assertFalse("must revert to the previous value on failure", viewModel.state.value.topicTopBarAutoHide)
        assertFalse(viewModel.state.value.isUpdatingTopicTopBarAutoHide)
        assertTrue(viewModel.state.value.topicTopBarAutoHideError)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Topic page FABs (#383)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `init hydrates topicPageFabs from a persisted false`() = runTest {
        // The default is true — only a persisted opt-out exercises the hydration path.
        repository.emitTopicPageFabs(false)

        val viewModel = newViewModel()

        assertFalse(viewModel.state.value.topicPageFabs)
        assertFalse(viewModel.state.value.topicPageFabsError)
    }

    @Test
    fun `topicPageFabs hydration race - a stale emission must not overwrite a local flip`() = runTest {
        // Same startup race as ignoreTopicCache: init suspends on .first() (the override
        // emits nothing yet), the user opts out locally, then the stale `true` arrives —
        // the TouchedLocally guard must skip the apply.
        val initialHydrationFlow = MutableSharedFlow<Boolean>(replay = 0)
        repository.topicPageFabsObserveOverride = initialHydrationFlow
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.TopicPageFabsChanged(false))
        assertFalse(
            "optimistic flip must reach the state synchronously",
            viewModel.state.value.topicPageFabs,
        )
        assertTrue(viewModel.state.value.topicPageFabsTouchedLocally)

        initialHydrationFlow.emit(true)

        assertFalse(
            "stale initial DataStore hydration must NOT overwrite the local opt-out",
            viewModel.state.value.topicPageFabs,
        )
        assertEquals(1, repository.topicPageFabsSetCalls)
    }

    @Test
    fun `TopicPageFabsChanged persists the flip`() = runTest {
        val viewModel = newViewModel()
        assertTrue("page FABs are on by default", viewModel.state.value.topicPageFabs)

        viewModel.submit(SettingsIntent.TopicPageFabsChanged(false))

        assertFalse(viewModel.state.value.topicPageFabs)
        assertFalse(viewModel.state.value.isUpdatingTopicPageFabs)
        assertEquals(1, repository.topicPageFabsSetCalls)
    }

    @Test
    fun `TopicPageFabsChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnTopicPageFabsSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.TopicPageFabsChanged(false))

        assertTrue("must revert to the previous value on failure", viewModel.state.value.topicPageFabs)
        assertFalse(viewModel.state.value.isUpdatingTopicPageFabs)
        assertTrue(viewModel.state.value.topicPageFabsError)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Badge MP non lus (#313)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `init hydrates mpUnreadBadge from a persisted false`() = runTest {
        // Default is true — only a persisted opt-out exercises the hydration path.
        repository.emitMpUnreadBadge(false)

        val viewModel = newViewModel()

        assertFalse(viewModel.state.value.mpUnreadBadge)
        assertFalse(viewModel.state.value.mpUnreadBadgeError)
    }

    @Test
    fun `MpUnreadBadgeChanged persists the flip`() = runTest {
        val viewModel = newViewModel()
        assertTrue("badge is on by default", viewModel.state.value.mpUnreadBadge)

        viewModel.submit(SettingsIntent.MpUnreadBadgeChanged(false))

        assertFalse(viewModel.state.value.mpUnreadBadge)
        assertFalse(viewModel.state.value.isUpdatingMpUnreadBadge)
        assertEquals(1, repository.mpUnreadBadgeSetCalls)
    }

    @Test
    fun `MpUnreadBadgeChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnMpUnreadBadgeSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.MpUnreadBadgeChanged(false))

        assertTrue("failed persist must revert to the previous value", viewModel.state.value.mpUnreadBadge)
        assertFalse(viewModel.state.value.isUpdatingMpUnreadBadge)
        assertTrue(viewModel.state.value.mpUnreadBadgeError)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Sondages dépliés par défaut (#456)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `init hydrates topicPollsExpanded from a persisted true`() = runTest {
        // Default is false — only a persisted opt-in exercises the hydration path.
        repository.emitTopicPollsExpanded(true)

        val viewModel = newViewModel()

        assertTrue(viewModel.state.value.topicPollsExpanded)
        assertFalse(viewModel.state.value.topicPollsExpandedError)
    }

    @Test
    fun `TopicPollsExpandedChanged persists the flip`() = runTest {
        val viewModel = newViewModel()
        assertFalse("polls are collapsed by default", viewModel.state.value.topicPollsExpanded)

        viewModel.submit(SettingsIntent.TopicPollsExpandedChanged(true))

        assertTrue(viewModel.state.value.topicPollsExpanded)
        assertFalse(viewModel.state.value.isUpdatingTopicPollsExpanded)
        assertEquals(1, repository.topicPollsExpandedSetCalls)
    }

    @Test
    fun `TopicPollsExpandedChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnTopicPollsExpandedSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.TopicPollsExpandedChanged(true))

        assertFalse("failed persist must revert to the previous value", viewModel.state.value.topicPollsExpanded)
        assertFalse(viewModel.state.value.isUpdatingTopicPollsExpanded)
        assertTrue(viewModel.state.value.topicPollsExpandedError)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Author signatures (#330)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `init hydrates topicSignatures from a persisted true`() = runTest {
        // Default is false — only a persisted opt-in exercises the hydration path.
        repository.emitTopicSignatures(true)

        val viewModel = newViewModel()

        assertTrue(viewModel.state.value.topicSignatures)
        assertFalse(viewModel.state.value.topicSignaturesError)
    }

    @Test
    fun `TopicSignaturesChanged persists the flip`() = runTest {
        val viewModel = newViewModel()
        assertFalse("signatures are hidden by default", viewModel.state.value.topicSignatures)

        viewModel.submit(SettingsIntent.TopicSignaturesChanged(true))

        assertTrue(viewModel.state.value.topicSignatures)
        assertFalse(viewModel.state.value.isUpdatingTopicSignatures)
        assertEquals(1, repository.topicSignaturesSetCalls)
    }

    @Test
    fun `TopicSignaturesChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnTopicSignaturesSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.TopicSignaturesChanged(true))

        assertFalse("failed persist must revert to the previous value", viewModel.state.value.topicSignatures)
        assertFalse(viewModel.state.value.isUpdatingTopicSignatures)
        assertTrue(viewModel.state.value.topicSignaturesError)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Fold long quotes (#332)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `init hydrates foldLongQuotes from a persisted false`() = runTest {
        // Default is true — only a persisted opt-OUT exercises the hydration path.
        repository.emitFoldLongQuotes(false)

        val viewModel = newViewModel()

        assertFalse(viewModel.state.value.foldLongQuotes)
        assertFalse(viewModel.state.value.foldLongQuotesError)
    }

    @Test
    fun `FoldLongQuotesChanged persists the flip`() = runTest {
        val viewModel = newViewModel()
        assertTrue("long quotes fold by default", viewModel.state.value.foldLongQuotes)

        viewModel.submit(SettingsIntent.FoldLongQuotesChanged(false))

        assertFalse(viewModel.state.value.foldLongQuotes)
        assertFalse(viewModel.state.value.isUpdatingFoldLongQuotes)
        assertEquals(1, repository.foldLongQuotesSetCalls)
    }

    @Test
    fun `FoldLongQuotesChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnFoldLongQuotesSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.FoldLongQuotesChanged(false))

        assertTrue("failed persist must revert to the previous value", viewModel.state.value.foldLongQuotes)
        assertFalse(viewModel.state.value.isUpdatingFoldLongQuotes)
        assertTrue(viewModel.state.value.foldLongQuotesError)
    }

    @Test
    fun `init hydrates showScrollbar from a persisted false`() = runTest {
        // #105 — default is true; only a persisted opt-OUT exercises the hydration path.
        repository.emitShowScrollbar(false)

        val viewModel = newViewModel()

        assertFalse(viewModel.state.value.showScrollbar)
        assertFalse(viewModel.state.value.showScrollbarError)
    }

    @Test
    fun `ShowScrollbarChanged persists the flip`() = runTest {
        val viewModel = newViewModel()
        assertTrue("scrollbar shown by default", viewModel.state.value.showScrollbar)

        viewModel.submit(SettingsIntent.ShowScrollbarChanged(false))

        assertFalse(viewModel.state.value.showScrollbar)
        assertFalse(viewModel.state.value.isUpdatingShowScrollbar)
        assertEquals(1, repository.showScrollbarSetCalls)
    }

    @Test
    fun `ShowScrollbarChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnShowScrollbarSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.ShowScrollbarChanged(false))

        assertTrue("failed persist must revert to the previous value", viewModel.state.value.showScrollbar)
        assertFalse(viewModel.state.value.isUpdatingShowScrollbar)
        assertTrue(viewModel.state.value.showScrollbarError)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Hide system navigation bar (#518)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `init hydrates hideSystemNavBar from a persisted true`() = runTest {
        // Default is false — only a persisted opt-in exercises the hydration path.
        repository.emitHideSystemNavBar(true)

        val viewModel = newViewModel()

        assertTrue(viewModel.state.value.hideSystemNavBar)
        assertFalse(viewModel.state.value.hideSystemNavBarError)
    }

    @Test
    fun `HideSystemNavBarChanged persists the flip`() = runTest {
        val viewModel = newViewModel()
        assertFalse("nav bar shown by default", viewModel.state.value.hideSystemNavBar)

        viewModel.submit(SettingsIntent.HideSystemNavBarChanged(true))

        assertTrue(viewModel.state.value.hideSystemNavBar)
        assertFalse(viewModel.state.value.isUpdatingHideSystemNavBar)
        assertEquals(1, repository.hideSystemNavBarSetCalls)
    }

    @Test
    fun `HideSystemNavBarChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnHideSystemNavBarSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.HideSystemNavBarChanged(true))

        assertFalse("failed persist must revert to the previous value", viewModel.state.value.hideSystemNavBar)
        assertFalse(viewModel.state.value.isUpdatingHideSystemNavBar)
        assertTrue(viewModel.state.value.hideSystemNavBarError)
    }

    @Test
    fun `init hydrates immersiveBackButton from a persisted false`() = runTest {
        // Default is true — only a persisted opt-OUT exercises the hydration path.
        repository.emitImmersiveBackButton(false)

        val viewModel = newViewModel()

        assertFalse(viewModel.state.value.immersiveBackButton)
        assertFalse(viewModel.state.value.immersiveBackButtonError)
    }

    @Test
    fun `ImmersiveBackButtonChanged persists the flip`() = runTest {
        val viewModel = newViewModel()
        assertTrue("back button on by default", viewModel.state.value.immersiveBackButton)

        viewModel.submit(SettingsIntent.ImmersiveBackButtonChanged(false))

        assertFalse(viewModel.state.value.immersiveBackButton)
        assertFalse(viewModel.state.value.isUpdatingImmersiveBackButton)
        assertEquals(1, repository.immersiveBackButtonSetCalls)
    }

    @Test
    fun `ImmersiveBackButtonChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnImmersiveBackButtonSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.ImmersiveBackButtonChanged(false))

        assertTrue("failed persist must revert to the previous value", viewModel.state.value.immersiveBackButton)
        assertFalse(viewModel.state.value.isUpdatingImmersiveBackButton)
        assertTrue(viewModel.state.value.immersiveBackButtonError)
    }

    @Test
    fun `init hydrates immersiveNavBarReveal from a persisted mode`() = runTest {
        repository.emitImmersiveNavBarReveal(ImmersiveNavBarReveal.AT_BOTTOM)

        val viewModel = newViewModel()

        assertEquals(ImmersiveNavBarReveal.AT_BOTTOM, viewModel.state.value.immersiveNavBarReveal)
        assertFalse(viewModel.state.value.immersiveNavBarRevealError)
    }

    @Test
    fun `ImmersiveNavBarRevealChanged persists the chosen mode`() = runTest {
        val viewModel = newViewModel()
        assertEquals(ImmersiveNavBarReveal.MANUAL, viewModel.state.value.immersiveNavBarReveal)

        viewModel.submit(SettingsIntent.ImmersiveNavBarRevealChanged(ImmersiveNavBarReveal.ON_SCROLL_UP))

        assertEquals(ImmersiveNavBarReveal.ON_SCROLL_UP, viewModel.state.value.immersiveNavBarReveal)
        assertFalse(viewModel.state.value.isUpdatingImmersiveNavBarReveal)
        assertEquals(1, repository.immersiveNavBarRevealSetCalls)
    }

    @Test
    fun `ImmersiveNavBarRevealChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnImmersiveNavBarRevealSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.ImmersiveNavBarRevealChanged(ImmersiveNavBarReveal.AT_BOTTOM))

        assertEquals(
            "failed persist must revert to the previous mode",
            ImmersiveNavBarReveal.MANUAL,
            viewModel.state.value.immersiveNavBarReveal,
        )
        assertFalse(viewModel.state.value.isUpdatingImmersiveNavBarReveal)
        assertTrue(viewModel.state.value.immersiveNavBarRevealError)
    }

    @Test
    fun `init hydrates accentColor from a persisted value`() = runTest {
        repository.emitAccentColor(AccentColor.ROUGE_REDFACE1)

        val viewModel = newViewModel()

        assertEquals(AccentColor.ROUGE_REDFACE1, viewModel.state.value.accentColor)
        assertFalse(viewModel.state.value.accentColorError)
    }

    @Test
    fun `AccentColorChanged persists the chosen colour`() = runTest {
        val viewModel = newViewModel()
        assertEquals(AccentColor.ROSE, viewModel.state.value.accentColor)

        viewModel.submit(SettingsIntent.AccentColorChanged(AccentColor.ROUGE_REDFACE1))

        assertEquals(AccentColor.ROUGE_REDFACE1, viewModel.state.value.accentColor)
        assertFalse(viewModel.state.value.isUpdatingAccentColor)
        assertEquals(1, repository.accentColorSetCalls)
    }

    @Test
    fun `AccentColorChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnAccentColorSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.AccentColorChanged(AccentColor.ROUGE_REDFACE1))

        assertEquals(
            "failed persist must revert to the previous colour",
            AccentColor.ROSE,
            viewModel.state.value.accentColor,
        )
        assertFalse(viewModel.state.value.isUpdatingAccentColor)
        assertTrue(viewModel.state.value.accentColorError)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Debug bounds overlay (#445)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `init hydrates debugBoundsOverlay from a persisted true`() = runTest {
        // Default is false — only a persisted opt-in exercises the hydration path.
        repository.emitDebugBoundsOverlay(true)

        val viewModel = newViewModel()

        assertTrue(viewModel.state.value.debugBoundsOverlay)
        assertFalse(viewModel.state.value.debugBoundsOverlayError)
    }

    @Test
    fun `DebugBoundsOverlayChanged persists the flip`() = runTest {
        val viewModel = newViewModel()
        assertFalse("overlay is off by default", viewModel.state.value.debugBoundsOverlay)

        viewModel.submit(SettingsIntent.DebugBoundsOverlayChanged(true))

        assertTrue(viewModel.state.value.debugBoundsOverlay)
        assertFalse(viewModel.state.value.isUpdatingDebugBoundsOverlay)
        assertEquals(1, repository.debugBoundsOverlaySetCalls)
    }

    @Test
    fun `DebugBoundsOverlayChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnDebugBoundsOverlaySet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.DebugBoundsOverlayChanged(true))

        assertFalse("failed persist must revert to the previous value", viewModel.state.value.debugBoundsOverlay)
        assertFalse(viewModel.state.value.isUpdatingDebugBoundsOverlay)
        assertTrue(viewModel.state.value.debugBoundsOverlayError)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Confirm before posting (#312)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `init hydrates confirmBeforePosting from the persisted preference`() = runTest {
        repository.emitConfirmBeforePosting(true)

        val viewModel = newViewModel()

        assertTrue(viewModel.state.value.confirmBeforePosting)
        assertFalse(viewModel.state.value.confirmBeforePostingError)
    }

    @Test
    fun `ConfirmBeforePostingChanged persists the flip`() = runTest {
        val viewModel = newViewModel()
        assertFalse("confirm-before-posting is off by default", viewModel.state.value.confirmBeforePosting)

        viewModel.submit(SettingsIntent.ConfirmBeforePostingChanged(true))

        assertTrue(viewModel.state.value.confirmBeforePosting)
        assertFalse(viewModel.state.value.isUpdatingConfirmBeforePosting)
        assertEquals(1, repository.confirmBeforePostingSetCalls)
    }

    @Test
    fun `ConfirmBeforePostingChanged reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnConfirmBeforePostingSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.ConfirmBeforePostingChanged(true))

        assertFalse("must revert to the previous value on failure", viewModel.state.value.confirmBeforePosting)
        assertFalse(viewModel.state.value.isUpdatingConfirmBeforePosting)
        assertTrue(viewModel.state.value.confirmBeforePostingError)
    }

    @Test
    fun `init hydrates the experimental MPStorage write opt-in from the persisted preference`() = runTest {
        repository.emitSyncPrivateMessagesWriteEnabled(true)

        val viewModel = newViewModel()

        assertTrue(viewModel.state.value.syncPrivateMessagesWriteEnabled)
        assertFalse(viewModel.state.value.syncPrivateMessagesWriteEnabledError)
    }

    @Test
    fun `the MPStorage write opt-in is OFF by default and a flip persists`() = runTest {
        val viewModel = newViewModel()
        assertFalse(
            "the experimental write opt-in must be off by default",
            viewModel.state.value.syncPrivateMessagesWriteEnabled,
        )

        viewModel.submit(SettingsIntent.SyncPrivateMessagesWriteEnabledChanged(true))

        assertTrue(viewModel.state.value.syncPrivateMessagesWriteEnabled)
        assertFalse(viewModel.state.value.isUpdatingSyncPrivateMessagesWriteEnabled)
        assertEquals(1, repository.syncPrivateMessagesWriteEnabledSetCalls)
    }

    @Test
    fun `the MPStorage write opt-in reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnSyncPrivateMessagesWriteEnabledSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.SyncPrivateMessagesWriteEnabledChanged(true))

        assertFalse(
            "must revert to the previous value on failure",
            viewModel.state.value.syncPrivateMessagesWriteEnabled,
        )
        assertFalse(viewModel.state.value.isUpdatingSyncPrivateMessagesWriteEnabled)
        assertTrue(viewModel.state.value.syncPrivateMessagesWriteEnabledError)
    }

    @Test
    fun `theme hydration race - a stale initial DataStore emission must not overwrite a local mode change`() =
        runTest {
            // Mirror of the ignoreTopicCache startup-race test for ThemeMode (#286, Codex nit): init
            // subscribes to observeThemeMode() and suspends on .first() (override emits nothing yet),
            // the user picks DARK locally (optimistic + write succeeds, touchedLocally = true), then the
            // still-suspended init resumes with a stale SYSTEM — the guard must skip the apply.
            val initialHydrationFlow = MutableSharedFlow<ThemeMode>(replay = 0)
            repository.themeModeObserveOverride = initialHydrationFlow
            val viewModel = newViewModel()

            viewModel.submit(SettingsIntent.ThemeModeChanged(ThemeMode.DARK))
            assertEquals(
                "optimistic flip must reach the state synchronously",
                ThemeMode.DARK,
                viewModel.state.value.themeMode,
            )
            assertTrue(viewModel.state.value.themeModeTouchedLocally)
            assertFalse(viewModel.state.value.isUpdatingThemeMode)

            // Late, stale hydration produces SYSTEM. On the buggy code this overwrites the local DARK;
            // on the fixed code the touchedLocally guard skips the apply.
            initialHydrationFlow.emit(ThemeMode.SYSTEM)

            val finalState = viewModel.state.value
            assertEquals(
                "stale initial DataStore hydration must NOT overwrite the local mode change",
                ThemeMode.DARK,
                finalState.themeMode,
            )
            assertFalse(finalState.isUpdatingThemeMode)
            assertFalse(finalState.themeModeError)
            assertEquals(1, repository.themeModeSetCalls)
            assertEquals(ThemeMode.DARK, repository.lastThemeModeSet)
        }

    @Test
    fun `displayDensity hydration race - a stale initial emission must not overwrite a local change`() =
        runTest {
            // Same startup-race contract as ThemeMode (#287): the user picks COMPACT while init is
            // still suspended on observeDisplayDensity().first(); the late stale COMFORT must be
            // skipped by the touchedLocally guard.
            val initialHydrationFlow = MutableSharedFlow<DisplayDensity>(replay = 0)
            repository.displayDensityObserveOverride = initialHydrationFlow
            val viewModel = newViewModel()

            viewModel.submit(SettingsIntent.DisplayDensityChanged(DisplayDensity.COMPACT))
            assertEquals(DisplayDensity.COMPACT, viewModel.state.value.displayDensity)
            assertTrue(viewModel.state.value.displayDensityTouchedLocally)

            initialHydrationFlow.emit(DisplayDensity.COMFORT)

            val finalState = viewModel.state.value
            assertEquals(
                "stale hydration must NOT overwrite the local density change",
                DisplayDensity.COMPACT,
                finalState.displayDensity,
            )
            assertFalse(finalState.isUpdatingDisplayDensity)
            assertFalse(finalState.displayDensityError)
            assertEquals(1, repository.displayDensitySetCalls)
            assertEquals(DisplayDensity.COMPACT, repository.lastDisplayDensitySet)
        }

    @Test
    fun `fontScale hydration race - a stale initial emission must not overwrite a local change`() =
        runTest {
            val initialHydrationFlow = MutableSharedFlow<FontScalePreference>(replay = 0)
            repository.fontScaleObserveOverride = initialHydrationFlow
            val viewModel = newViewModel()

            viewModel.submit(SettingsIntent.FontScaleChanged(FontScalePreference.L))
            assertEquals(FontScalePreference.L, viewModel.state.value.fontScale)
            assertTrue(viewModel.state.value.fontScaleTouchedLocally)

            initialHydrationFlow.emit(FontScalePreference.M)

            val finalState = viewModel.state.value
            assertEquals(
                "stale hydration must NOT overwrite the local font-scale change",
                FontScalePreference.L,
                finalState.fontScale,
            )
            assertFalse(finalState.isUpdatingFontScale)
            assertFalse(finalState.fontScaleError)
            assertEquals(1, repository.fontScaleSetCalls)
            assertEquals(FontScalePreference.L, repository.lastFontScaleSet)
        }

    // ──────────────────────────────────────────────────────────────────────
    // Hébergeur d'images — provider + imgur Client-ID (#459)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `init hydrates upload provider and imgur client id from storage`() = runTest {
        repository.emitUploadProvider(UploadProviderId.IMGUR)
        repository.emitImgurClientId("abc123")

        val viewModel = newViewModel()
        val state = viewModel.state.value

        assertEquals(UploadProviderId.IMGUR, state.uploadProvider)
        assertEquals("abc123", state.imgurClientId)
    }

    @Test
    fun `SetUploadProvider persists the new provider and clears the updating flag`() = runTest {
        val viewModel = newViewModel()
        assertEquals("DIBERIE is the default", UploadProviderId.DIBERIE, viewModel.state.value.uploadProvider)

        viewModel.submit(SettingsIntent.SetUploadProvider(UploadProviderId.IMGUR))

        val state = viewModel.state.value
        assertEquals(UploadProviderId.IMGUR, state.uploadProvider)
        assertFalse(state.isUpdatingUploadProvider)
        assertFalse(state.uploadProviderError)
        assertEquals(1, repository.uploadProviderSetCalls)
        assertEquals(UploadProviderId.IMGUR, repository.lastUploadProviderSet)
    }

    @Test
    fun `SetUploadProvider reverts to the previous provider and raises the error flag on persist failure`() =
        runTest {
            repository.failOnUploadProviderSet = true
            val viewModel = newViewModel()

            viewModel.submit(SettingsIntent.SetUploadProvider(UploadProviderId.IMGUR))

            val state = viewModel.state.value
            assertEquals(
                "must revert to the previous provider on failure",
                UploadProviderId.DIBERIE,
                state.uploadProvider,
            )
            assertFalse(state.isUpdatingUploadProvider)
            assertTrue(state.uploadProviderError)
        }

    @Test
    fun `SetEditorImageInsert persists the new mode and clears the updating flag`() = runTest {
        val viewModel = newViewModel()
        assertEquals("REDUCED is the default", EditorImageInsert.REDUCED, viewModel.state.value.editorImageInsert)

        viewModel.submit(SettingsIntent.SetEditorImageInsert(EditorImageInsert.FULL))

        val state = viewModel.state.value
        assertEquals(EditorImageInsert.FULL, state.editorImageInsert)
        assertFalse(state.isUpdatingEditorImageInsert)
        assertFalse(state.editorImageInsertError)
        assertEquals(EditorImageInsert.FULL, repository.lastEditorImageInsertSet)
    }

    @Test
    fun `SetEditorImageInsert reverts and raises the error flag on persist failure`() = runTest {
        repository.failOnEditorImageInsertSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.SetEditorImageInsert(EditorImageInsert.FULL))

        val state = viewModel.state.value
        assertEquals("must revert on failure", EditorImageInsert.REDUCED, state.editorImageInsert)
        assertFalse(state.isUpdatingEditorImageInsert)
        assertTrue(state.editorImageInsertError)
    }

    @Test
    fun `SetImgurClientId persists the text and exposes it`() = runTest {
        val viewModel = newViewModel()
        assertEquals("client id is empty by default", "", viewModel.state.value.imgurClientId)

        viewModel.submit(SettingsIntent.SetImgurClientId("CID-42"))

        val state = viewModel.state.value
        assertEquals("CID-42", state.imgurClientId)
        assertFalse(state.imgurClientIdError)
        assertEquals(1, repository.imgurClientIdSetCalls)
        assertEquals("CID-42", repository.lastImgurClientIdSet)
    }

    @Test
    fun `SetImgurClientId raises the error flag on persist failure but keeps the typed text`() = runTest {
        repository.failOnImgurClientIdSet = true
        val viewModel = newViewModel()

        viewModel.submit(SettingsIntent.SetImgurClientId("CID-42"))

        val state = viewModel.state.value
        // The optimistic value is kept — wiping what the user just typed would be hostile.
        assertEquals("typed text is preserved on persist failure", "CID-42", state.imgurClientId)
        assertTrue(state.imgurClientIdError)
        assertEquals(1, repository.imgurClientIdSetCalls)
    }

    @Test
    fun `upload provider hydration race - a stale initial emission must not overwrite a local change`() =
        runTest {
            // Same startup-race contract as ThemeMode (#459): the user picks IMGUR while init is still
            // suspended on observeUploadProvider().first(); the late stale DIBERIE must be skipped by
            // the touchedLocally guard.
            val initialHydrationFlow = MutableSharedFlow<UploadProviderId>(replay = 0)
            repository.uploadProviderObserveOverride = initialHydrationFlow
            val viewModel = newViewModel()

            viewModel.submit(SettingsIntent.SetUploadProvider(UploadProviderId.IMGUR))
            assertEquals(UploadProviderId.IMGUR, viewModel.state.value.uploadProvider)
            assertTrue(viewModel.state.value.uploadProviderTouchedLocally)

            initialHydrationFlow.emit(UploadProviderId.DIBERIE)

            val finalState = viewModel.state.value
            assertEquals(
                "stale hydration must NOT overwrite the local provider change",
                UploadProviderId.IMGUR,
                finalState.uploadProvider,
            )
            assertEquals(1, repository.uploadProviderSetCalls)
            assertEquals(UploadProviderId.IMGUR, repository.lastUploadProviderSet)
        }

    private fun newViewModel(): SettingsViewModel =
        SettingsViewModel(repository, topicCacheMaintenance, imageCacheMaintenance)

    private class FakeUserPreferencesRepository : UserPreferencesRepository {
        private val config = MutableStateFlow(ProxyConfig())
        private val ignoreTopicCache = MutableStateFlow(false)
        var saveCalls: Int = 0
            private set
        var lastSaved: ProxyConfig? = null
            private set
        var failOnSave: Boolean = false
        var ignoreTopicCacheSetCalls: Int = 0
            private set
        var lastIgnoreTopicCacheSet: Boolean? = null
            private set
        var failOnIgnoreTopicCacheSet: Boolean = false
        var blockIgnoreTopicCacheSetUntil: CompletableDeferred<Unit>? = null

        override fun observeProxyConfig(): Flow<ProxyConfig> = config

        override suspend fun saveProxyConfig(config: ProxyConfig) {
            saveCalls += 1
            check(!failOnSave) { "boom" }
            val normalized = config.normalized()
            lastSaved = normalized
            this.config.value = normalized
        }

        override fun readProxyConfigForNetworkBootstrap(): ProxyConfig = config.value

        /**
         * Test seam for the startup-race test: when non-null, `observeIgnoreTopicCache()` returns
         * this overridden flow instead of the internal `MutableStateFlow`. That lets the test hold
         * the initial `.first()` suspension, perform a local toggle while the hydration is still
         * pending, and only then release a stale emission to verify the guard ignores it.
         */
        var ignoreTopicCacheObserveOverride: Flow<Boolean>? = null

        override fun observeIgnoreTopicCache(): Flow<Boolean> =
            ignoreTopicCacheObserveOverride ?: ignoreTopicCache

        override suspend fun setIgnoreTopicCache(enabled: Boolean) {
            ignoreTopicCacheSetCalls += 1
            // Suspend the write here so the test can observe `isUpdatingIgnoreTopicCache = true`
            // before the launch resumes. Same pattern as `FakeTopicCacheMaintenance.blockUntil`.
            blockIgnoreTopicCacheSetUntil?.await()
            check(!failOnIgnoreTopicCacheSet) { "boom" }
            lastIgnoreTopicCacheSet = enabled
            ignoreTopicCache.value = enabled
        }

        // Flags view preferences — same optimistic-flip seams as ignoreTopicCache so the Settings
        // tests can gate the write and assert intermediate / revert states.
        private val flagsGroupByCategory = MutableStateFlow(true)
        var flagsGroupByCategorySetCalls: Int = 0
            private set
        var failOnFlagsGroupByCategorySet: Boolean = false

        private val flagsHideReadCategories = MutableStateFlow(false)
        var flagsHideReadCategoriesSetCalls: Int = 0
            private set
        var failOnFlagsHideReadCategoriesSet: Boolean = false

        override fun observeFlagsGroupByCategory(): Flow<Boolean> = flagsGroupByCategory

        override suspend fun setFlagsGroupByCategory(enabled: Boolean) {
            flagsGroupByCategorySetCalls += 1
            check(!failOnFlagsGroupByCategorySet) { "boom" }
            flagsGroupByCategory.value = enabled
        }

        override fun observeFlagsHideReadCategories(): Flow<Boolean> = flagsHideReadCategories

        override suspend fun setFlagsHideReadCategories(enabled: Boolean) {
            flagsHideReadCategoriesSetCalls += 1
            check(!failOnFlagsHideReadCategoriesSet) { "boom" }
            flagsHideReadCategories.value = enabled
        }

        // #309 — per-tab override master switch. Same optimistic-flip seam as the toggles above so
        // the Settings mirror tests can gate the write and assert the intermediate / revert states.
        private val flagsPerTabOverride = MutableStateFlow(false)
        var flagsPerTabOverrideSetCalls: Int = 0
            private set
        var failOnFlagsPerTabOverrideSet: Boolean = false

        override fun observeFlagsPerTabOverride(): Flow<Boolean> = flagsPerTabOverride

        override suspend fun setFlagsPerTabOverride(enabled: Boolean) {
            flagsPerTabOverrideSetCalls += 1
            check(!failOnFlagsPerTabOverrideSet) { "boom" }
            flagsPerTabOverride.value = enabled
        }

        // #286 — theme preferences. Same optimistic-flip seams as the flags toggles.
        private val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
        var themeModeSetCalls: Int = 0
            private set
        var lastThemeModeSet: ThemeMode? = null
            private set
        var failOnThemeModeSet: Boolean = false

        private val amoledEnabled = MutableStateFlow(false)
        var amoledSetCalls: Int = 0
            private set
        var failOnAmoledSet: Boolean = false

        /**
         * Test seam for the theme startup-race test (#286), mirroring [ignoreTopicCacheObserveOverride]:
         * when non-null, `observeThemeMode()` returns this flow so the test can hold the init `.first()`
         * suspension, perform a local mode change, then release a stale emission to prove the guard skips it.
         */
        var themeModeObserveOverride: Flow<ThemeMode>? = null

        override fun observeThemeMode(): Flow<ThemeMode> = themeModeObserveOverride ?: themeMode

        override suspend fun setThemeMode(mode: ThemeMode) {
            themeModeSetCalls += 1
            check(!failOnThemeModeSet) { "boom" }
            lastThemeModeSet = mode
            themeMode.value = mode
        }

        override fun observeAmoledEnabled(): Flow<Boolean> = amoledEnabled

        override suspend fun setAmoledEnabled(enabled: Boolean) {
            amoledSetCalls += 1
            check(!failOnAmoledSet) { "boom" }
            amoledEnabled.value = enabled
        }

        // #287 — reading display presets. Same optimistic-flip seams as the theme controls.
        private val displayDensity = MutableStateFlow(DisplayDensity.COMFORT)
        var displayDensitySetCalls: Int = 0
            private set
        var lastDisplayDensitySet: DisplayDensity? = null
            private set
        var failOnDisplayDensitySet: Boolean = false

        /** Startup-race seam, mirroring [themeModeObserveOverride] (#287). */
        var displayDensityObserveOverride: Flow<DisplayDensity>? = null

        override fun observeDisplayDensity(): Flow<DisplayDensity> =
            displayDensityObserveOverride ?: displayDensity

        override suspend fun setDisplayDensity(density: DisplayDensity) {
            displayDensitySetCalls += 1
            check(!failOnDisplayDensitySet) { "boom" }
            lastDisplayDensitySet = density
            displayDensity.value = density
        }

        fun emitDisplayDensity(value: DisplayDensity) {
            displayDensity.value = value
        }

        private val fontScale = MutableStateFlow(FontScalePreference.M)
        var fontScaleSetCalls: Int = 0
            private set
        var lastFontScaleSet: FontScalePreference? = null
            private set
        var failOnFontScaleSet: Boolean = false

        /** Startup-race seam, mirroring [themeModeObserveOverride] (#287). */
        var fontScaleObserveOverride: Flow<FontScalePreference>? = null

        override fun observeFontScale(): Flow<FontScalePreference> =
            fontScaleObserveOverride ?: fontScale

        override suspend fun setFontScale(scale: FontScalePreference) {
            fontScaleSetCalls += 1
            check(!failOnFontScaleSet) { "boom" }
            lastFontScaleSet = scale
            fontScale.value = scale
        }

        fun emitFontScale(value: FontScalePreference) {
            fontScale.value = value
        }

        // Build 89 follow-up — topic top-bar auto-hide. Same optimistic-flip seam as amoled.
        private val topicTopBarAutoHide = MutableStateFlow(false)
        var topicTopBarAutoHideSetCalls: Int = 0
            private set
        var failOnTopicTopBarAutoHideSet: Boolean = false

        override fun observeTopicTopBarAutoHide(): Flow<Boolean> = topicTopBarAutoHide

        override suspend fun setTopicTopBarAutoHide(enabled: Boolean) {
            topicTopBarAutoHideSetCalls += 1
            check(!failOnTopicTopBarAutoHideSet) { "boom" }
            topicTopBarAutoHide.value = enabled
        }

        // #383 — topic page FABs. Same optimistic-flip seam as the topic top-bar toggle,
        // plus the observe-override seam of ignoreTopicCache for the hydration-race test.
        private val topicPageFabs = MutableStateFlow(true)
        var topicPageFabsSetCalls: Int = 0
            private set
        var failOnTopicPageFabsSet: Boolean = false
        var topicPageFabsObserveOverride: Flow<Boolean>? = null

        override fun observeTopicPageFabs(): Flow<Boolean> =
            topicPageFabsObserveOverride ?: topicPageFabs

        override suspend fun setTopicPageFabs(enabled: Boolean) {
            topicPageFabsSetCalls += 1
            check(!failOnTopicPageFabsSet) { "boom" }
            topicPageFabs.value = enabled
        }

        fun emitTopicPageFabs(value: Boolean) {
            topicPageFabs.value = value
        }

        // #313 — badge MP non lus. Même seam optimistic-flip que topicPageFabs.
        private val mpUnreadBadge = MutableStateFlow(true)
        var mpUnreadBadgeSetCalls: Int = 0
            private set
        var failOnMpUnreadBadgeSet: Boolean = false

        override fun observeMpUnreadBadge(): Flow<Boolean> = mpUnreadBadge

        override suspend fun setMpUnreadBadge(enabled: Boolean) {
            mpUnreadBadgeSetCalls += 1
            check(!failOnMpUnreadBadgeSet) { "boom" }
            mpUnreadBadge.value = enabled
        }

        fun emitMpUnreadBadge(value: Boolean) {
            mpUnreadBadge.value = value
        }

        // #456 — sondages dépliés par défaut. Même seam optimistic-flip que mpUnreadBadge.
        private val topicPollsExpanded = MutableStateFlow(false)
        var topicPollsExpandedSetCalls: Int = 0
            private set
        var failOnTopicPollsExpandedSet: Boolean = false

        override fun observeTopicPollsExpanded(): Flow<Boolean> = topicPollsExpanded

        override suspend fun setTopicPollsExpanded(enabled: Boolean) {
            topicPollsExpandedSetCalls += 1
            check(!failOnTopicPollsExpandedSet) { "boom" }
            topicPollsExpanded.value = enabled
        }

        fun emitTopicPollsExpanded(value: Boolean) {
            topicPollsExpanded.value = value
        }

        // #330 — afficher les signatures. Même seam optimistic-flip que topicPollsExpanded.
        private val topicSignatures = MutableStateFlow(false)
        var topicSignaturesSetCalls: Int = 0
            private set
        var failOnTopicSignaturesSet: Boolean = false

        override fun observeTopicSignatures(): Flow<Boolean> = topicSignatures

        override suspend fun setTopicSignatures(enabled: Boolean) {
            topicSignaturesSetCalls += 1
            check(!failOnTopicSignaturesSet) { "boom" }
            topicSignatures.value = enabled
        }

        fun emitTopicSignatures(value: Boolean) {
            topicSignatures.value = value
        }

        // #332 — replier les longues citations. Même seam optimistic-flip ; default TRUE (repli).
        private val foldLongQuotes = MutableStateFlow(true)
        var foldLongQuotesSetCalls: Int = 0
            private set
        var failOnFoldLongQuotesSet: Boolean = false

        override fun observeFoldLongQuotes(): Flow<Boolean> = foldLongQuotes

        override suspend fun setFoldLongQuotes(enabled: Boolean) {
            foldLongQuotesSetCalls += 1
            check(!failOnFoldLongQuotesSet) { "boom" }
            foldLongQuotes.value = enabled
        }

        fun emitFoldLongQuotes(value: Boolean) {
            foldLongQuotes.value = value
        }

        // #105 — afficher l'ascenseur. Même seam optimistic-flip ; default TRUE (ascenseur affiché).
        private val showScrollbar = MutableStateFlow(true)
        var showScrollbarSetCalls: Int = 0
            private set
        var failOnShowScrollbarSet: Boolean = false

        override fun observeShowScrollbar(): Flow<Boolean> = showScrollbar

        override suspend fun setShowScrollbar(enabled: Boolean) {
            showScrollbarSetCalls += 1
            check(!failOnShowScrollbarSet) { "boom" }
            showScrollbar.value = enabled
        }

        fun emitShowScrollbar(value: Boolean) {
            showScrollbar.value = value
        }

        // #666 — afficher les libellés de la barre du bas. Même seam optimistic-flip ; default TRUE.
        private val navBarLabels = MutableStateFlow(true)
        var navBarLabelsSetCalls: Int = 0
            private set
        var failOnNavBarLabelsSet: Boolean = false

        override fun observeNavBarLabels(): Flow<Boolean> = navBarLabels

        override suspend fun setNavBarLabels(enabled: Boolean) {
            navBarLabelsSetCalls += 1
            check(!failOnNavBarLabelsSet) { "boom" }
            navBarLabels.value = enabled
        }

        fun emitNavBarLabels(value: Boolean) {
            navBarLabels.value = value
        }

        private val funnyEmptyState = MutableStateFlow(false)
        var funnyEmptyStateSetCalls: Int = 0
            private set

        override fun observeFunnyEmptyState(): Flow<Boolean> = funnyEmptyState

        override suspend fun setFunnyEmptyState(enabled: Boolean) {
            funnyEmptyStateSetCalls += 1
            funnyEmptyState.value = enabled
        }

        fun emitFunnyEmptyState(value: Boolean) {
            funnyEmptyState.value = value
        }

        // #458 — start screen lives on its own StartScreenSettingsViewModel; this fake only
        // satisfies the interface for the main Settings ViewModel under test.
        override fun observeStartScreen(): Flow<StartScreenPreference> =
            MutableStateFlow(StartScreenPreference())

        override suspend fun setStartScreen(preference: StartScreenPreference) = Unit

        // #459 — upload provider / imgur Client-ID. Same optimistic-flip seam as the theme controls
        // so the Settings tests can assert hydration, the repo call, and the revert-on-failure path.
        private val uploadProvider = MutableStateFlow(UploadProviderId.DIBERIE)
        var uploadProviderSetCalls: Int = 0
            private set
        var lastUploadProviderSet: UploadProviderId? = null
            private set
        var failOnUploadProviderSet: Boolean = false

        /** Startup-race seam, mirroring [themeModeObserveOverride] (#459). */
        var uploadProviderObserveOverride: Flow<UploadProviderId>? = null

        override fun observeUploadProvider(): Flow<UploadProviderId> =
            uploadProviderObserveOverride ?: uploadProvider

        override suspend fun setUploadProvider(provider: UploadProviderId) {
            uploadProviderSetCalls += 1
            check(!failOnUploadProviderSet) { "boom" }
            lastUploadProviderSet = provider
            uploadProvider.value = provider
        }

        fun emitUploadProvider(value: UploadProviderId) {
            uploadProvider.value = value
        }

        private val imgurClientId = MutableStateFlow("")
        var imgurClientIdSetCalls: Int = 0
            private set
        var lastImgurClientIdSet: String? = null
            private set
        var failOnImgurClientIdSet: Boolean = false

        override fun observeImgurClientId(): Flow<String> = imgurClientId

        override suspend fun setImgurClientId(clientId: String) {
            imgurClientIdSetCalls += 1
            check(!failOnImgurClientIdSet) { "boom" }
            lastImgurClientIdSet = clientId
            imgurClientId.value = clientId
        }

        fun emitImgurClientId(value: String) {
            imgurClientId.value = value
        }

        // #459 PR-images follow-up — editor image insert mode. Same optimistic-flip seam.
        private val editorImageInsert = MutableStateFlow(EditorImageInsert.REDUCED)
        var lastEditorImageInsertSet: EditorImageInsert? = null
            private set
        var failOnEditorImageInsertSet: Boolean = false

        override fun observeEditorImageInsert(): Flow<EditorImageInsert> = editorImageInsert

        override suspend fun setEditorImageInsert(mode: EditorImageInsert) {
            check(!failOnEditorImageInsertSet) { "boom" }
            lastEditorImageInsertSet = mode
            editorImageInsert.value = mode
        }

        // #312 — confirm-before-posting. Same optimistic-flip seam as the topic top-bar toggle.
        private val confirmBeforePosting = MutableStateFlow(false)
        var confirmBeforePostingSetCalls: Int = 0
            private set
        var failOnConfirmBeforePostingSet: Boolean = false

        override fun observeConfirmBeforePosting(): Flow<Boolean> = confirmBeforePosting

        override suspend fun setConfirmBeforePosting(enabled: Boolean) {
            confirmBeforePostingSetCalls += 1
            check(!failOnConfirmBeforePostingSet) { "boom" }
            confirmBeforePosting.value = enabled
        }

        private val showDtSection = MutableStateFlow(false)

        override fun observeShowDtSection(): Flow<Boolean> = showDtSection

        override suspend fun setShowDtSection(enabled: Boolean) {
            showDtSection.value = enabled
        }

        // #6 — experimental MPStorage write-back opt-in. Rich seam (set-call counter + fail flag +
        // emit helper) so the Settings tests can assert hydration, the repo call, and revert-on-failure.
        private val syncPrivateMessagesWriteEnabled = MutableStateFlow(false)
        var syncPrivateMessagesWriteEnabledSetCalls: Int = 0
            private set
        var failOnSyncPrivateMessagesWriteEnabledSet: Boolean = false

        override fun observeSyncPrivateMessagesWriteEnabled(): Flow<Boolean> = syncPrivateMessagesWriteEnabled

        override suspend fun setSyncPrivateMessagesWriteEnabled(enabled: Boolean) {
            syncPrivateMessagesWriteEnabledSetCalls += 1
            check(!failOnSyncPrivateMessagesWriteEnabledSet) { "boom" }
            syncPrivateMessagesWriteEnabled.value = enabled
        }

        fun emitSyncPrivateMessagesWriteEnabled(value: Boolean) {
            syncPrivateMessagesWriteEnabled.value = value
        }

        // #378 — flags auto-refresh opt-out, same writable seam as showDtSection.
        private val flagsAutoRefresh = MutableStateFlow(true)

        override fun observeFlagsAutoRefresh(): Flow<Boolean> = flagsAutoRefresh

        override suspend fun setFlagsAutoRefresh(enabled: Boolean) {
            flagsAutoRefresh.value = enabled
        }

        fun emitConfirmBeforePosting(value: Boolean) {
            confirmBeforePosting.value = value
        }

        fun emitThemeMode(value: ThemeMode) {
            themeMode.value = value
        }

        fun emitAmoledEnabled(value: Boolean) {
            amoledEnabled.value = value
        }

        fun emitTopicTopBarAutoHide(value: Boolean) {
            topicTopBarAutoHide.value = value
        }

        // Per-type resolution / writes are exercised by the Flags ViewModel, not Settings; stubbed.
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
        override suspend fun setFlagsGlyphStyle(style: FlagGlyphStyle) = Unit

        fun emit(value: ProxyConfig) {
            config.value = value
        }

        fun emitIgnoreTopicCache(value: Boolean) {
            ignoreTopicCache.value = value
        }

        fun emitFlagsGroupByCategory(value: Boolean) {
            flagsGroupByCategory.value = value
        }

        fun emitFlagsHideReadCategories(value: Boolean) {
            flagsHideReadCategories.value = value
        }

        fun emitFlagsPerTabOverride(value: Boolean) {
            flagsPerTabOverride.value = value
        }

        // #445 — debug bounds overlay. Same optimistic-flip seam as topicSignatures so the Settings
        // tests can assert hydration, the repo call, and the revert-on-failure path.
        private val debugBoundsOverlay = MutableStateFlow(false)
        var debugBoundsOverlaySetCalls: Int = 0
            private set
        var failOnDebugBoundsOverlaySet: Boolean = false

        override fun observeDebugBoundsOverlay(): Flow<Boolean> = debugBoundsOverlay

        override suspend fun setDebugBoundsOverlay(enabled: Boolean) {
            debugBoundsOverlaySetCalls += 1
            check(!failOnDebugBoundsOverlaySet) { "boom" }
            debugBoundsOverlay.value = enabled
        }

        fun emitDebugBoundsOverlay(value: Boolean) {
            debugBoundsOverlay.value = value
        }

        // #518 — hide system nav bar. Same optimistic-flip seam as above.
        private val hideSystemNavBar = MutableStateFlow(false)
        var hideSystemNavBarSetCalls: Int = 0
            private set
        var failOnHideSystemNavBarSet: Boolean = false

        override fun observeHideSystemNavBar(): Flow<Boolean> = hideSystemNavBar

        override suspend fun setHideSystemNavBar(enabled: Boolean) {
            hideSystemNavBarSetCalls += 1
            check(!failOnHideSystemNavBarSet) { "boom" }
            hideSystemNavBar.value = enabled
        }

        fun emitHideSystemNavBar(value: Boolean) {
            hideSystemNavBar.value = value
        }

        // #518 follow-up — immersive back button. Default TRUE, same optimistic-flip seam as above.
        private val immersiveBackButton = MutableStateFlow(true)
        var immersiveBackButtonSetCalls: Int = 0
            private set
        var failOnImmersiveBackButtonSet: Boolean = false

        override fun observeImmersiveBackButton(): Flow<Boolean> = immersiveBackButton

        override suspend fun setImmersiveBackButton(enabled: Boolean) {
            immersiveBackButtonSetCalls += 1
            check(!failOnImmersiveBackButtonSet) { "boom" }
            immersiveBackButton.value = enabled
        }

        fun emitImmersiveBackButton(value: Boolean) {
            immersiveBackButton.value = value
        }

        // #518 follow-up — immersive nav-bar reveal mode. Default MANUAL, enum optimistic-flip seam.
        private val immersiveNavBarReveal = MutableStateFlow(ImmersiveNavBarReveal.MANUAL)
        var immersiveNavBarRevealSetCalls: Int = 0
            private set
        var failOnImmersiveNavBarRevealSet: Boolean = false

        override fun observeImmersiveNavBarReveal(): Flow<ImmersiveNavBarReveal> = immersiveNavBarReveal

        override suspend fun setImmersiveNavBarReveal(mode: ImmersiveNavBarReveal) {
            immersiveNavBarRevealSetCalls += 1
            check(!failOnImmersiveNavBarRevealSet) { "boom" }
            immersiveNavBarReveal.value = mode
        }

        fun emitImmersiveNavBarReveal(value: ImmersiveNavBarReveal) {
            immersiveNavBarReveal.value = value
        }

        // TU 2788511 — accent colour family. Default ROSE, enum optimistic-flip seam.
        private val accentColor = MutableStateFlow(AccentColor.ROSE)
        var accentColorSetCalls: Int = 0
            private set
        var failOnAccentColorSet: Boolean = false

        override fun observeAccentColor(): Flow<AccentColor> = accentColor

        override suspend fun setAccentColor(color: AccentColor) {
            accentColorSetCalls += 1
            check(!failOnAccentColorSet) { "boom" }
            accentColor.value = color
        }

        fun emitAccentColor(value: AccentColor) {
            accentColor.value = value
        }
    }

    private class FakeTopicCacheMaintenance : TopicCacheMaintenance {
        var clearCalls: Int = 0
            private set
        var failOnClear: Boolean = false
        var blockUntil: CompletableDeferred<Unit>? = null

        override suspend fun clearTopicCache() {
            clearCalls += 1
            blockUntil?.await()
            check(!failOnClear) { "boom" }
        }
    }

    private class FakeImageCacheMaintenance : ImageCacheMaintenance {
        var clearCalls: Int = 0
            private set
        var failOnClear: Boolean = false
        var blockUntil: CompletableDeferred<Unit>? = null

        override suspend fun clearImageCache() {
            clearCalls += 1
            blockUntil?.await()
            check(!failOnClear) { "boom" }
        }
    }
}
