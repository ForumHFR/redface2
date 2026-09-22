package fr.forumhfr.redface2.feature.flags

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1301 — the saveable handshake behind the flags list's submit acknowledgement. What the
 * acknowledgement itself looks like is covered by [FlagsSubmitAcknowledgementTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlagsSubmitAcknowledgementEffectTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an armed id is consumed after handling and a later re-mount never replays it`() {
        val request = mutableLongStateOf(0L)
        val mount = mutableIntStateOf(0)
        val handled = CompletableDeferred<Unit>()
        val consumed = mutableListOf<Long>()
        var handlingStarted = 0
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // #1301 — `key` stands in for leaving the flags list and coming back to it.
                    key(mount.intValue) {
                        FlagsSubmitAcknowledgementEffect(
                            request = request.longValue,
                            onConsumed = { requestId ->
                                consumed += requestId
                                if (request.longValue == requestId) request.longValue = 0L
                            },
                        ) {
                            handlingStarted += 1
                            handled.await()
                        }
                    }
                }
            }
        }

        // #1301 — the state lives outside the composition, so publish its global-snapshot write
        // explicitly for the recomposer to see it in this test.
        compose.runOnIdle {
            request.longValue = 1L
            Snapshot.sendApplyNotifications()
        }
        compose.waitUntil(TIMEOUT_MS) { handlingStarted == 1 }
        assertEquals("the pending id must survive while the snackbar is handled", emptyList<Long>(), consumed)

        compose.runOnIdle { handled.complete(Unit) }
        compose.waitUntil(TIMEOUT_MS) { consumed == listOf(1L) }

        compose.runOnIdle {
            mount.intValue += 1
            Snapshot.sendApplyNotifications()
        }
        compose.waitForIdle()

        assertEquals("a handled acknowledgement must not replay", 1, handlingStarted)
        assertEquals("the pending id must be free again", 0L, request.longValue)
    }

    @Test
    fun `activity recreation before handling preserves and retries the pending id`() {
        val restorationTester = StateRestorationTester(compose)
        val handlingGate = CompletableDeferred<Unit>()
        val consumed = mutableListOf<Long>()
        var handlingStarted = 0
        lateinit var publish: () -> Unit

        restorationTester.setContent {
            var request by rememberSaveable { mutableStateOf(0L) }
            publish = { request += 1L }
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Box(modifier = Modifier.fillMaxSize()) {
                    FlagsSubmitAcknowledgementEffect(
                        request = request,
                        onConsumed = { requestId ->
                            consumed += requestId
                            if (request == requestId) request = 0L
                        },
                    ) {
                        handlingStarted += 1
                        handlingGate.await()
                    }
                }
            }
        }

        // #1301 — publishing the arming write is NOT optional here, exactly like in the test above.
        // Recomposition alone would eventually observe it, but `emulateSavedInstanceStateRestore`
        // saves the state from a snapshot of its own: left unpublished, the write is invisible to
        // that save, the restored composition comes back with `request = 0` and the retry the test
        // is about never happens. Isolated on 2026-09-20: this line alone turns the case green,
        // and no amount of extra waiting does.
        compose.runOnIdle {
            publish()
            Snapshot.sendApplyNotifications()
        }
        compose.waitUntil(TIMEOUT_MS) { handlingStarted == 1 }
        assertEquals("the id must not be consumed before snackbar handling completes", emptyList<Long>(), consumed)

        // #1301 — emulateSavedInstanceStateRestore destroys the Activity composition while the
        // snackbar is suspended, then restores the production rememberSaveable contract.
        restorationTester.emulateSavedInstanceStateRestore()
        compose.waitUntil(TIMEOUT_MS) { handlingStarted == 2 }
        assertEquals("recreation must leave the acknowledgement pending", emptyList<Long>(), consumed)

        compose.runOnIdle { handlingGate.complete(Unit) }
        compose.waitUntil(TIMEOUT_MS) { consumed == listOf(1L) }

        assertEquals("only the restored attempt may settle the pending id", listOf(1L), consumed)
    }

    private companion object {
        const val TIMEOUT_MS = 2_000L
    }
}
