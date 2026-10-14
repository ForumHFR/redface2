package fr.forumhfr.redface2.feature.flags

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshCategory
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshIssue
import fr.forumhfr.redface2.core.model.FlagType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlagsRefreshWarningEffectTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `retry is consumed once and remounting cannot replay the warning`() {
        val host = SnackbarHostState()
        val warning = mutableStateOf<FlagsRefreshWarning?>(warning())
        val mount = mutableIntStateOf(0)
        var consumed = 0
        var retried = 0
        compose.setContent {
            key(mount.intValue) {
                FlagsRefreshWarningEffect(
                    warning = warning.value,
                    authSessionKey = "old",
                    selectedTab = FlagTab.Cyan,
                    host = host,
                    onConsumed = { consumed++; warning.value = null },
                    onRetry = { retried++ },
                )
            }
        }
        compose.waitUntil(TIMEOUT_MS) { host.currentSnackbarData != null }
        assertEquals("Liste incomplète : épinglés de Programmation non chargés",
            host.currentSnackbarData?.visuals?.message)
        assertEquals("Réessayer", host.currentSnackbarData?.visuals?.actionLabel)
        compose.runOnIdle { host.currentSnackbarData?.performAction() }
        compose.waitUntil(TIMEOUT_MS) { consumed == 1 && retried == 1 }
        compose.runOnIdle { mount.intValue++; Snapshot.sendApplyNotifications() }
        compose.waitForIdle()
        assertNull(host.currentSnackbarData)
        assertEquals(1, consumed)
        assertEquals(1, retried)
    }

    @Test
    fun `unmounting before snackbar dismissal cannot replay a presented warning`() {
        val host = SnackbarHostState()
        val warning = mutableStateOf<FlagsRefreshWarning?>(warning())
        val mounted = mutableStateOf(true)
        var consumed = 0
        var retried = 0
        compose.setContent {
            if (mounted.value) {
                FlagsRefreshWarningEffect(
                    warning = warning.value,
                    authSessionKey = "old",
                    selectedTab = FlagTab.Cyan,
                    host = host,
                    onConsumed = { consumed++; warning.value = null },
                    onRetry = { retried++ },
                )
            }
        }
        compose.waitUntil(TIMEOUT_MS) { host.currentSnackbarData != null }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = false; Snapshot.sendApplyNotifications() }
        compose.waitUntil(TIMEOUT_MS) { host.currentSnackbarData == null }
        compose.runOnIdle { mounted.value = true; Snapshot.sendApplyNotifications() }
        compose.waitForIdle()
        assertNull(host.currentSnackbarData)
        assertEquals(1, consumed)
        assertEquals(0, retried)
    }

    @Test
    fun `a warning waits for its own tab and leaving that tab cancels its action`() {
        val host = SnackbarHostState()
        val tab = mutableStateOf<FlagTab>(FlagTab.Red)
        val warning = mutableStateOf<FlagsRefreshWarning?>(warning())
        var consumed = 0
        var retried = 0
        compose.setContent {
            FlagsRefreshWarningEffect(
                warning = warning.value,
                authSessionKey = "old",
                selectedTab = tab.value,
                host = host,
                onConsumed = { consumed++; warning.value = null },
                onRetry = { retried++ },
            )
        }
        compose.waitForIdle()
        assertNull(host.currentSnackbarData)
        assertEquals(0, consumed)
        compose.runOnIdle { tab.value = FlagTab.Cyan; Snapshot.sendApplyNotifications() }
        compose.waitUntil(TIMEOUT_MS) { host.currentSnackbarData != null }
        compose.waitForIdle()
        val oldSnackbar = requireNotNull(host.currentSnackbarData)
        compose.runOnIdle { tab.value = FlagTab.Dt; Snapshot.sendApplyNotifications() }
        compose.waitUntil(TIMEOUT_MS) { host.currentSnackbarData == null }
        compose.runOnIdle { oldSnackbar.performAction() }
        compose.waitForIdle()
        assertEquals(0, retried)
        compose.runOnIdle { tab.value = FlagTab.Cyan; Snapshot.sendApplyNotifications() }
        compose.waitForIdle()
        assertNull(host.currentSnackbarData)
        assertEquals(1, consumed)
    }

    @Test
    fun `a complete snapshot withdraws a warning queued behind another snackbar`() {
        val host = SnackbarHostState()
        val warning = mutableStateOf<FlagsRefreshWarning?>(null)
        var consumed = 0
        compose.setContent {
            LaunchedEffect(Unit) {
                host.showRefreshWarning("Other message", "Other action")
            }
            FlagsRefreshWarningEffect(
                warning = warning.value,
                authSessionKey = "old",
                selectedTab = FlagTab.Cyan,
                host = host,
                onConsumed = { consumed++ },
                onRetry = {},
            )
        }
        compose.waitUntil(TIMEOUT_MS) { host.currentSnackbarData != null }
        compose.runOnIdle { warning.value = warning(); Snapshot.sendApplyNotifications() }
        compose.waitForIdle()
        assertEquals("Other message", host.currentSnackbarData?.visuals?.message)
        compose.runOnIdle { warning.value = null; Snapshot.sendApplyNotifications() }
        compose.waitForIdle()
        compose.runOnIdle { host.currentSnackbarData?.dismiss() }
        compose.waitForIdle()
        assertNull(host.currentSnackbarData)
        assertEquals(0, consumed)
    }

    @Test
    fun `recreation before presentation keeps a queued warning available for retry`() {
        val host = SnackbarHostState()
        val warning = mutableStateOf<FlagsRefreshWarning?>(null)
        val mounted = mutableStateOf(true)
        var consumed = 0
        var retried = 0
        compose.setContent {
            LaunchedEffect(Unit) { host.showRefreshWarning("Other message", "Other action") }
            if (mounted.value) {
                FlagsRefreshWarningEffect(
                    warning = warning.value,
                    authSessionKey = "old",
                    selectedTab = FlagTab.Cyan,
                    host = host,
                    onConsumed = { consumed++; warning.value = null },
                    onRetry = { retried++ },
                )
            }
        }
        compose.waitUntil(TIMEOUT_MS) { host.currentSnackbarData != null }
        compose.runOnIdle { warning.value = warning(); Snapshot.sendApplyNotifications() }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = false; Snapshot.sendApplyNotifications() }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = true; Snapshot.sendApplyNotifications() }
        compose.waitForIdle()
        assertEquals(0, consumed)
        assertEquals("Other message", host.currentSnackbarData?.visuals?.message)
        compose.runOnIdle { host.currentSnackbarData?.dismiss() }
        compose.waitUntil(TIMEOUT_MS) { host.currentSnackbarData?.visuals?.actionLabel == "Réessayer" }
        compose.runOnIdle { host.currentSnackbarData?.performAction() }
        compose.waitUntil(TIMEOUT_MS) { consumed == 1 && retried == 1 }
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun `an account switch cancels the visible old warning and its action`() {
        val host = SnackbarHostState()
        val auth = mutableStateOf("old")
        val warning = warning()
        var consumed = 0
        var retried = 0
        compose.setContent {
            FlagsRefreshWarningEffect(
                warning = warning,
                authSessionKey = auth.value,
                selectedTab = FlagTab.Cyan,
                host = host,
                onConsumed = { consumed++ },
                onRetry = { retried++ },
            )
        }
        compose.waitUntil(TIMEOUT_MS) { host.currentSnackbarData != null }
        compose.runOnIdle { auth.value = "new"; Snapshot.sendApplyNotifications() }
        compose.waitUntil(TIMEOUT_MS) { host.currentSnackbarData == null }
        compose.waitForIdle()
        assertEquals(0, consumed)
        assertEquals(0, retried)
    }

    private fun warning(): FlagsRefreshWarning = FlagsRefreshWarning(
        issuesByType = mapOf(
            FlagType.CYAN to FlagRefreshIssue(stickyCategories = listOf(FlagRefreshCategory(13, "Programmation"))),
        ),
        tab = FlagTab.Cyan,
        retryTypes = listOf(FlagType.CYAN),
        sessionGeneration = 1,
        pseudo = "old",
    )

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
