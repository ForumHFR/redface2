package fr.forumhfr.redface2.feature.topic

import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopicSubmitFeedbackEffectTest {
    @get:Rule val compose = createComposeRule()
    private val fixtures = mutableListOf<LateFeedback>()

    @After
    fun tearDown() {
        fixtures.forEach { it.scope.cancel() }
    }

    private fun lateFeedback() = LateFeedback().also { fixtures += it }

    @Test
    fun `rotation restores the same offer and action is taken once`() {
        val restoration = StateRestorationTester(compose)
        val fixture = lateFeedback()
        val hosts = mutableListOf<SnackbarHostState>()
        var opened = 0
        restoration.setContent {
            val host = remember { SnackbarHostState().also { hosts += it } }
            TopicSubmitFeedbackEffect(fixture.store, host, true, "xaat", openPage = { opened += 1 })
        }
        compose.runOnIdle {
            val id = fixture.store.accept("xaat", PostSubmitOperation.Publish)
            fixture.store.refreshed(id, lastPage = 6)
        }
        compose.waitUntil { fixture.store.feedback.value?.expiresAtMillis != null }
        val deadline = fixture.store.feedback.value!!.expiresAtMillis
        val host = hosts.last()
        assertEquals("Dernière page", host.currentSnackbarData?.visuals?.actionLabel)
        assertEquals(SnackbarDuration.Long, host.currentSnackbarData?.visuals?.duration)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil { hosts.size == 2 && hosts.last().currentSnackbarData != null }
        assertNull(host.currentSnackbarData)
        assertEquals(deadline, fixture.store.feedback.value?.expiresAtMillis)
        compose.runOnIdle { hosts.last().currentSnackbarData!!.performAction() }
        compose.waitUntil { opened == 1 }
        assertNull(fixture.store.feedback.value)
    }

    @Test
    fun `account invalidation removes a displayed offer and disables its old action`() {
        val host = SnackbarHostState()
        val account = mutableStateOf("xaat")
        lateinit var store: TopicAcknowledgements
        var opened = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            store = remember { TopicAcknowledgements(SavedStateHandle(), scope) }
            TopicSubmitFeedbackEffect(store, host, active = true, account = account.value, openPage = { opened += 1 })
        }
        compose.runOnIdle {
            val id = store.accept("xaat", PostSubmitOperation.Publish)
            store.refreshed(id, lastPage = 6)
        }
        compose.waitUntil { host.currentSnackbarData != null }
        val oldAction = host.currentSnackbarData!!
        compose.runOnIdle {
            store.accountChanged("other")
            account.value = "other"
        }
        compose.waitUntil { host.currentSnackbarData == null }
        compose.runOnIdle { oldAction.performAction() }
        compose.waitForIdle()
        assertEquals(0, opened)
        assertNull(store.feedback.value)
    }

    @Test
    fun `accessible offer keeps the recommended deadline and exposes one polite region`() {
        val host = SnackbarHostState()
        lateinit var store: TopicAcknowledgements
        val accessibility = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(
                originalTimeoutMillis: Long,
                containsIcons: Boolean,
                containsText: Boolean,
                containsControls: Boolean,
            ): Long = 30_000L
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            store = remember { TopicAcknowledgements(SavedStateHandle(), scope) { 0L } }
            CompositionLocalProvider(LocalAccessibilityManager provides accessibility) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    SnackbarHost(host)
                    TopicSubmitFeedbackEffect(store, host, active = true, account = "xaat", openPage = {})
                }
            }
        }
        compose.runOnIdle {
            val id = store.accept("xaat", PostSubmitOperation.Publish)
            store.refreshed(id, lastPage = 6)
        }
        compose.waitUntil { store.feedback.value?.expiresAtMillis != null }
        assertEquals(30_000L, store.feedback.value?.expiresAtMillis)
        compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        ).assertCountEquals(1)
    }

    @Test
    fun `new submit removes the old action and keeps its fast confirmation visible`() {
        val fixture = lateFeedback()
        var opened = 0
        compose.setContent {
            TopicSubmitFeedbackEffect(fixture.store, fixture.host, true, "xaat", openPage = { opened += 1 })
        }
        compose.runOnIdle {
            val id = fixture.store.accept("xaat", PostSubmitOperation.Publish)
            fixture.store.refreshed(id, 6)
        }
        compose.waitUntil { fixture.store.feedback.value?.expiresAtMillis != null }
        val old = fixture.host.currentSnackbarData!!
        compose.runOnIdle {
            val id = fixture.store.accept("xaat", PostSubmitOperation.Publish)
            fixture.store.refreshed(id, null)
        }
        compose.waitUntil { fixture.host.currentSnackbarData?.visuals?.duration == SnackbarDuration.Short }
        compose.runOnIdle { old.performAction() }
        compose.waitForIdle()
        assertEquals("Message publié", fixture.host.currentSnackbarData?.visuals?.message)
        assertEquals(SnackbarDuration.Short, fixture.host.currentSnackbarData?.visuals?.duration)
        assertNull(fixture.host.currentSnackbarData?.visuals?.actionLabel)
        assertEquals(0, opened)
    }

    @Test
    fun `late offer replaces the visible short and its old callbacks cannot consume the action`() {
        val fixture = lateFeedback()
        val host = fixture.host
        var opened: Int? = null
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                SnackbarHost(host)
                TopicSubmitFeedbackEffect(fixture.store, host, true, "xaat") { opened = it }
            }
        }
        var id = 0L
        var oldRevision = 0
        compose.runOnIdle {
            id = fixture.acceptAtDeadline()
            oldRevision = requireNotNull(fixture.store.feedback.value).revision
        }
        compose.waitUntil { host.currentSnackbarData != null }
        val old = host.currentSnackbarData!!
        assertEquals("Message publié", old.visuals.message)
        assertEquals(SnackbarDuration.Short, old.visuals.duration)
        compose.runOnIdle { fixture.store.refreshed(id, 6) }
        compose.waitUntil { host.currentSnackbarData?.visuals?.actionLabel == "Dernière page" }
        compose.waitUntil { fixture.store.feedback.value?.expiresAtMillis != null }
        assertEquals("Message publié", host.currentSnackbarData?.visuals?.message)
        assertEquals(SnackbarDuration.Long, host.currentSnackbarData?.visuals?.duration)
        compose.runOnIdle {
            val offer = requireNotNull(fixture.store.feedback.value)
            fixture.store.dismiss(id, oldRevision)
            assertEquals(offer, fixture.store.feedback.value)
            fixture.store.presented(id, 60_000, oldRevision)
            assertEquals(offer, fixture.store.feedback.value)
            assertNull(fixture.store.takeOffer(id, "xaat", oldRevision))
            assertEquals(offer, fixture.store.feedback.value)
        }
        compose.waitForIdle()
        assertNull(opened)
        assertEquals(6, fixture.store.feedback.value?.lastPage)
        compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        ).assertCountEquals(1)
        compose.runOnIdle { host.currentSnackbarData!!.performAction() }
        compose.waitUntil { opened == 6 }
        assertNull(fixture.store.feedback.value)
    }

    @Test
    fun `late offer replaces a short still waiting for the host`() {
        val fixture = lateFeedback()
        val blocker = fixture.scope.launch {
            fixture.host.showSnackbar("Busy", duration = SnackbarDuration.Indefinite)
        }
        fixture.clock.runCurrent()
        val shown = mutableListOf<String>()
        compose.setContent {
            LaunchedEffect(fixture.host) {
                snapshotFlow { fixture.host.currentSnackbarData }
                    .filterNotNull()
                    .collect { shown += it.visuals.message }
            }
            TopicSubmitFeedbackEffect(fixture.store, fixture.host, true, "xaat", openPage = {})
        }
        var id = 0L
        compose.runOnIdle { id = fixture.acceptAtDeadline() }
        compose.waitForIdle()
        assertEquals("Busy", fixture.host.currentSnackbarData?.visuals?.message)
        compose.runOnIdle { fixture.store.refreshed(id, 6) }
        compose.waitForIdle()
        assertNull(fixture.store.feedback.value?.expiresAtMillis)
        compose.runOnIdle {
            fixture.host.currentSnackbarData!!.dismiss()
            fixture.clock.runCurrent()
        }
        compose.waitUntil { fixture.host.currentSnackbarData?.visuals?.actionLabel == "Dernière page" }
        assertEquals(SnackbarDuration.Long, fixture.host.currentSnackbarData?.visuals?.duration)
        compose.waitUntil { shown.count { it == "Message publié" } == 1 }
        compose.runOnIdle { fixture.host.currentSnackbarData!!.dismiss() }
        compose.waitUntil { fixture.host.currentSnackbarData == null }
        assertEquals(1, shown.count { it == "Message publié" })
        blocker.cancel()
    }

    @Test
    fun `late offer after the short timeout presents only navigation without repeating success`() {
        compose.mainClock.autoAdvance = false
        val fixture = lateFeedback()
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                SnackbarHost(fixture.host)
                TopicSubmitFeedbackEffect(fixture.store, fixture.host, true, "xaat", openPage = {})
            }
        }
        compose.mainClock.advanceTimeByFrame()
        var id = 0L
        compose.runOnIdle { id = fixture.acceptAtDeadline() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil { fixture.host.currentSnackbarData != null }
        compose.runOnIdle {
            fixture.clock.advanceTimeBy(4_001)
            fixture.clock.runCurrent()
        }
        compose.mainClock.advanceTimeBy(4_200)
        compose.waitUntil { fixture.host.currentSnackbarData == null }
        compose.runOnIdle { fixture.store.refreshed(id, 6) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil { fixture.host.currentSnackbarData != null }
        assertEquals("Aller à la dernière page", fixture.host.currentSnackbarData?.visuals?.message)
        assertEquals("Dernière page", fixture.host.currentSnackbarData?.visuals?.actionLabel)
        assertEquals(SnackbarDuration.Long, fixture.host.currentSnackbarData?.visuals?.duration)
    }

    @Test
    fun `late accessible offer on a new host keeps only its remaining duration`() {
        val (fixture, host) = lateAccessibleOfferOnNewHost()
        val displayed = requireNotNull(host.currentSnackbarData)
        // Keep store time fixed: the new host's cap must dismiss the offer without the store's timer.
        compose.mainClock.advanceTimeBy(9_000)
        compose.runOnIdle { assertSame(displayed, host.currentSnackbarData) }
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitUntil { host.currentSnackbarData == null }
        assertNull(fixture.store.feedback.value)
    }

    @Test
    fun `late accessible offer on a new host can be taken only once`() {
        val opened = mutableListOf<Int>()
        val (fixture, host) = lateAccessibleOfferOnNewHost { opened += it }
        val offer = requireNotNull(fixture.store.feedback.value)
        val action = requireNotNull(host.currentSnackbarData)
        compose.runOnIdle {
            fixture.clock.advanceTimeBy(9_000)
            fixture.clock.runCurrent()
        }
        compose.mainClock.advanceTimeBy(9_000)
        compose.runOnIdle {
            assertSame(action, host.currentSnackbarData)
            assertEquals(1_000L, fixture.store.remainingMillis(offer.id))
            action.performAction()
        }
        compose.waitUntil { opened.size == 1 }
        compose.runOnIdle {
            action.performAction()
            assertNull(fixture.store.takeOffer(offer.id, "xaat", offer.revision))
        }
        compose.waitForIdle()
        assertEquals(listOf(6), opened)
        assertNull(host.currentSnackbarData)
        assertNull(fixture.store.feedback.value)
    }

    private fun lateAccessibleOfferOnNewHost(openPage: (Int) -> Unit = {}): Pair<LateFeedback, SnackbarHostState> {
        compose.mainClock.autoAdvance = false
        val fixture = lateFeedback()
        val host = mutableStateOf(fixture.host)
        val accessibility = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(
                originalTimeoutMillis: Long,
                containsIcons: Boolean,
                containsText: Boolean,
                containsControls: Boolean,
            ): Long = 30_000L
        }
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilityManager provides accessibility) {
                TopicSubmitFeedbackEffect(fixture.store, host.value, true, "xaat", openPage)
            }
        }
        var id = 0L
        compose.runOnIdle { id = fixture.acceptAtDeadline() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeUntil(timeoutMillis = 1_000) { host.value.currentSnackbarData != null }
        compose.runOnIdle { fixture.store.refreshed(id, 6) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeUntil(timeoutMillis = 1_000) {
            fixture.store.feedback.value?.expiresAtMillis != null
        }
        val offer = requireNotNull(fixture.store.feedback.value)
        assertEquals(31_000L, offer.expiresAtMillis)
        val oldHost = host.value
        compose.runOnIdle {
            fixture.clock.advanceTimeBy(20_000)
            fixture.clock.runCurrent()
        }
        compose.mainClock.advanceTimeBy(20_000)
        val newHost = SnackbarHostState()
        compose.runOnIdle { host.value = newHost }
        // Flush snapshot notifications before driving the recomposition and its restarted effect.
        // Frozen-clock waitUntil alone cannot drive that work.
        compose.waitForIdle()
        compose.mainClock.advanceTimeUntil(timeoutMillis = 1_000) { newHost.currentSnackbarData != null }
        assertNull(oldHost.currentSnackbarData)
        assertEquals(offer, fixture.store.feedback.value)
        assertEquals("Dernière page", newHost.currentSnackbarData?.visuals?.actionLabel)
        assertEquals(SnackbarDuration.Long, newHost.currentSnackbarData?.visuals?.duration)
        assertEquals(10_000L, fixture.store.remainingMillis(id))
        return fixture to newHost
    }

    @Test
    fun `predicted offer is the only snackbar and a late refresh retargets it in place`() {
        val fixture = lateFeedback()
        val host = fixture.host
        var opened: Int? = null
        val shown = mutableListOf<SnackbarData>()
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                LaunchedEffect(host) {
                    snapshotFlow { host.currentSnackbarData }.filterNotNull().collect { shown += it }
                }
                SnackbarHost(host)
                TopicSubmitFeedbackEffect(fixture.store, host, true, "xaat") { opened = it }
            }
        }
        var id = 0L
        compose.runOnIdle { id = fixture.acceptAtDeadline(predictedLastPage = 5) }
        compose.waitUntil { host.currentSnackbarData != null }
        val first = host.currentSnackbarData!!
        assertEquals("Message publié", first.visuals.message)
        assertEquals("Dernière page", first.visuals.actionLabel)
        assertEquals(SnackbarDuration.Long, first.visuals.duration)
        compose.waitUntil { fixture.store.feedback.value?.expiresAtMillis != null }
        compose.runOnIdle { fixture.store.refreshed(id, 6) }
        compose.waitForIdle()
        assertSame(first, host.currentSnackbarData)
        assertEquals(listOf(first), shown)
        compose.runOnIdle { host.currentSnackbarData!!.performAction() }
        compose.waitUntil { opened == 6 }
        assertNull(fixture.store.feedback.value)
        compose.waitUntil { host.currentSnackbarData == null }
        assertEquals(listOf(first), shown)
    }

    @Test
    fun `rotation of a predicted offer presents the success only once and preserves its deadline`() {
        val restoration = StateRestorationTester(compose)
        val fixture = lateFeedback()
        val hosts = mutableListOf<SnackbarHostState>()
        val shown = mutableListOf<String>()
        val opened = mutableListOf<Int>()
        restoration.setContent {
            val host = remember { SnackbarHostState().also { hosts += it } }
            LaunchedEffect(host) {
                snapshotFlow { host.currentSnackbarData }.filterNotNull().collect { shown += it.visuals.message }
            }
            TopicSubmitFeedbackEffect(fixture.store, host, true, "xaat") { opened += it }
        }
        compose.runOnIdle { fixture.acceptAtDeadline(predictedLastPage = 5) }
        compose.waitUntil { fixture.store.feedback.value?.expiresAtMillis != null && shown.size == 1 }
        val deadline = fixture.store.feedback.value?.expiresAtMillis
        assertEquals(listOf("Message publié"), shown)
        val oldHost = hosts.last()
        compose.runOnIdle {
            fixture.clock.advanceTimeBy(500)
            fixture.clock.runCurrent()
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil { hosts.size == 2 && shown.size == 2 }
        assertNull(oldHost.currentSnackbarData)
        assertEquals(listOf("Message publié", "Aller à la dernière page"), shown)
        assertEquals(deadline, fixture.store.feedback.value?.expiresAtMillis)
        assertEquals(9_500L, fixture.store.remainingMillis(fixture.store.feedback.value!!.id))
        compose.runOnIdle { hosts.last().currentSnackbarData!!.performAction() }
        compose.waitUntil { opened == listOf(5) }
        assertEquals(1, shown.count { it == "Message publié" })
        assertNull(fixture.store.feedback.value)
    }

    @Test
    fun `returning to the foreground with a predicted offer never repeats the success`() {
        val fixture = lateFeedback()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        val shown = mutableListOf<String>()
        compose.setContent {
            LaunchedEffect(fixture.host) {
                snapshotFlow { fixture.host.currentSnackbarData }.filterNotNull()
                    .collect { shown += it.visuals.message }
            }
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TopicSubmitFeedbackEffect(fixture.store, fixture.host, true, "xaat", openPage = {})
            }
        }
        compose.runOnIdle { fixture.acceptAtDeadline(predictedLastPage = 5) }
        compose.waitUntil { fixture.store.feedback.value?.expiresAtMillis != null && shown.size == 1 }
        val deadline = fixture.store.feedback.value?.expiresAtMillis
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitUntil { fixture.host.currentSnackbarData == null }
        compose.runOnIdle {
            fixture.clock.advanceTimeBy(500)
            fixture.clock.runCurrent()
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil { fixture.host.currentSnackbarData != null && shown.size == 2 }
        assertEquals(listOf("Message publié", "Aller à la dernière page"), shown)
        assertEquals("Dernière page", fixture.host.currentSnackbarData?.visuals?.actionLabel)
        assertEquals(deadline, fixture.store.feedback.value?.expiresAtMillis)
        assertEquals(9_500L, fixture.store.remainingMillis(fixture.store.feedback.value!!.id))
    }

    @Test
    fun `a contradicted prediction removes the displayed offer without presenting another snackbar`() {
        val fixture = lateFeedback()
        val shown = mutableListOf<SnackbarData>()
        val opened = mutableListOf<Int>()
        compose.setContent {
            LaunchedEffect(fixture.host) {
                snapshotFlow { fixture.host.currentSnackbarData }.filterNotNull().collect { shown += it }
            }
            TopicSubmitFeedbackEffect(fixture.store, fixture.host, true, "xaat") { opened += it }
        }
        var id = 0L
        compose.runOnIdle { id = fixture.acceptAtDeadline(predictedLastPage = 5) }
        compose.waitUntil { fixture.store.feedback.value?.expiresAtMillis != null && shown.size == 1 }
        val old = requireNotNull(fixture.host.currentSnackbarData)
        compose.runOnIdle { fixture.store.refreshed(id, lastPage = null) }
        compose.waitUntil { fixture.host.currentSnackbarData == null }
        compose.runOnIdle { old.performAction() }
        compose.waitForIdle()
        assertEquals(listOf(old), shown)
        assertEquals(emptyList<Int>(), opened)
        assertNull(fixture.store.feedback.value)
    }

    @Test
    fun `a contradicted prediction waiting for the host presents only one short confirmation`() {
        val fixture = lateFeedback()
        val blocker = fixture.scope.launch {
            fixture.host.showSnackbar("Busy", duration = SnackbarDuration.Indefinite)
        }
        fixture.clock.runCurrent()
        val shown = mutableListOf<SnackbarData>()
        compose.setContent {
            LaunchedEffect(fixture.host) {
                snapshotFlow { fixture.host.currentSnackbarData }.filterNotNull().collect { shown += it }
            }
            TopicSubmitFeedbackEffect(fixture.store, fixture.host, true, "xaat", openPage = {})
        }
        var id = 0L
        compose.runOnIdle { id = fixture.acceptAtDeadline(predictedLastPage = 5) }
        compose.waitForIdle()
        assertEquals("Busy", fixture.host.currentSnackbarData?.visuals?.message)
        assertNull(fixture.store.feedback.value?.expiresAtMillis)
        compose.runOnIdle { fixture.store.refreshed(id, lastPage = null) }
        compose.waitForIdle()
        compose.runOnIdle {
            fixture.host.currentSnackbarData!!.dismiss()
            fixture.clock.runCurrent()
        }
        compose.waitUntil { fixture.host.currentSnackbarData?.visuals?.message == "Message publié" }
        val confirmation = requireNotNull(fixture.host.currentSnackbarData)
        assertNull(confirmation.visuals.actionLabel)
        assertEquals(SnackbarDuration.Short, confirmation.visuals.duration)
        compose.runOnIdle { confirmation.dismiss() }
        compose.waitUntil { fixture.host.currentSnackbarData == null }
        assertEquals(1, shown.count { it.visuals.message == "Message publié" })
        blocker.cancel()
    }

    @Test
    fun `delete success uses the common short snackbar`() {
        val host = SnackbarHostState()
        lateinit var store: TopicAcknowledgements
        compose.setContent {
            val scope = rememberCoroutineScope()
            store = remember { TopicAcknowledgements(SavedStateHandle(), scope) }
            TopicSubmitFeedbackEffect(store, host, active = true, account = "xaat", openPage = {})
        }
        compose.runOnIdle { store.deleted("xaat") }
        compose.waitUntil { host.currentSnackbarData != null }
        assertEquals("Message supprimé", host.currentSnackbarData?.visuals?.message)
        assertEquals(SnackbarDuration.Short, host.currentSnackbarData?.visuals?.duration)
        assertNull(host.currentSnackbarData?.visuals?.actionLabel)
    }
}

/** Store time is independent of Compose frames, so a queued snackbar cannot hide the 1 s boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
private class LateFeedback {
    val clock = TestCoroutineScheduler()
    val scope = CoroutineScope(StandardTestDispatcher(clock))
    val host = SnackbarHostState()
    val store = TopicAcknowledgements(SavedStateHandle(), scope) { clock.currentTime }

    fun acceptAtDeadline(predictedLastPage: Int? = null): Long {
        val id = store.accept("xaat", PostSubmitOperation.Publish, predictedLastPage)
        clock.advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        clock.runCurrent()
        return id
    }
}
