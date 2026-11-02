package fr.forumhfr.redface2.feature.topic

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopicSubmitFeedbackEffectTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `rotation restores the same offer and action is taken once`() {
        val restoration = StateRestorationTester(compose)
        val host = SnackbarHostState()
        var retained: TopicAcknowledgements? = null
        var opened = 0
        restoration.setContent {
            val scope = rememberCoroutineScope()
            val store = retained ?: TopicAcknowledgements(SavedStateHandle(), scope).also { retained = it }
            TopicSubmitFeedbackEffect(store, host, active = true, account = "xaat", openPage = { opened += 1 })
        }
        compose.runOnIdle {
            val id = retained!!.accept("xaat", PostSubmitOperation.Publish)
            retained!!.refreshed(id, lastPage = 6)
        }
        compose.waitUntil { retained?.feedback?.value?.expiresAtMillis != null }
        val deadline = retained!!.feedback.value!!.expiresAtMillis
        assertEquals("Dernière page", host.currentSnackbarData?.visuals?.actionLabel)
        assertEquals(SnackbarDuration.Long, host.currentSnackbarData?.visuals?.duration)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil { host.currentSnackbarData != null }
        assertEquals(deadline, retained!!.feedback.value!!.expiresAtMillis)
        compose.runOnIdle { host.currentSnackbarData!!.performAction() }
        compose.waitUntil { opened == 1 }
        assertNull(retained!!.feedback.value)
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
