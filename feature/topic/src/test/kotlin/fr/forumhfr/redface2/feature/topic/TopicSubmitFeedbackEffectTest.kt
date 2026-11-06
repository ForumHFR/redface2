package fr.forumhfr.redface2.feature.topic

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
import androidx.lifecycle.SavedStateHandle
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
        compose.runOnIdle { id = fixture.acceptAtDeadline() }
        compose.waitUntil { host.currentSnackbarData != null }
        val old = host.currentSnackbarData!!
        assertEquals("Message publié", old.visuals.message)
        assertEquals(SnackbarDuration.Short, old.visuals.duration)
        compose.runOnIdle { fixture.store.refreshed(id, 6) }
        compose.waitUntil { host.currentSnackbarData?.visuals?.actionLabel == "Dernière page" }
        assertEquals("Message publié", host.currentSnackbarData?.visuals?.message)
        assertEquals(SnackbarDuration.Long, host.currentSnackbarData?.visuals?.duration)
        compose.runOnIdle {
            old.dismiss()
            old.performAction()
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
                snapshotFlow { fixture.host.currentSnackbarData }.filterNotNull().collect { shown += it.visuals.message }
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
                TopicSubmitFeedbackEffect(fixture.store, host.value, true, "xaat", openPage = {})
            }
        }
        compose.mainClock.advanceTimeByFrame()
        var id = 0L
        compose.runOnIdle { id = fixture.acceptAtDeadline() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil { host.value.currentSnackbarData != null }
        compose.runOnIdle { fixture.store.refreshed(id, 6) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil { fixture.store.feedback.value?.expiresAtMillis != null }
        val deadline = fixture.store.feedback.value?.expiresAtMillis
        assertEquals(31_000L, deadline)
        val oldHost = host.value
        compose.runOnIdle {
            fixture.clock.advanceTimeBy(20_000)
            fixture.clock.runCurrent()
            host.value = SnackbarHostState()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil { host.value.currentSnackbarData != null }
        assertNull(oldHost.currentSnackbarData)
        assertEquals(deadline, fixture.store.feedback.value?.expiresAtMillis)
        assertEquals(10_000L, fixture.store.remainingMillis(id))
        compose.mainClock.advanceTimeBy(10_100)
        compose.waitUntil { host.value.currentSnackbarData == null }
        assertNull(fixture.store.feedback.value)
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

    fun acceptAtDeadline(): Long {
        val id = store.accept("xaat", PostSubmitOperation.Publish)
        clock.advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        clock.runCurrent()
        return id
    }
}
