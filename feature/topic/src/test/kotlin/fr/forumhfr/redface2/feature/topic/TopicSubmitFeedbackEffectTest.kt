package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1301 — integration coverage for the StateFlow → recomposition → LaunchedEffect bridge. The
 * coordinator-only tests cannot reproduce conflation or recreation of the screen-owned instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopicSubmitFeedbackEffectTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `activity recreation during the submitted-post jump does not replay confirmation`() {
        val restorationTester = StateRestorationTester(compose)
        val refreshKind = MutableStateFlow(TopicRefreshKind.PostSubmitJump)
        var confirmations = 0

        restorationTester.setContent {
            val currentRefreshKind by refreshKind.collectAsState()
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val scope = rememberCoroutineScope()
                    val feedback = remember { TopicSubmitFeedback(dismissCurrentSnackbar = {}) }
                    TopicSubmitFeedbackEffect(currentRefreshKind, feedback, scope) {
                        confirmations += 1
                    }
                }
            }
        }

        compose.waitForIdle()
        assertEquals("a jump is progress, not a fresh submit", 0, confirmations)

        // #1301 — the retained ViewModel still exposes PostSubmitJump while the Activity-owned
        // coordinator is recreated from scratch.
        restorationTester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("recreation must not turn the jump into a submit", 0, confirmations)

        compose.runOnIdle { refreshKind.value = TopicRefreshKind.PostSubmit }
        compose.waitUntil(TIMEOUT_MS) { confirmations == 1 }
    }

    @Test
    fun `a conflated jump completion cannot consume the next real submit confirmation`() {
        val refreshKind = MutableStateFlow(TopicRefreshKind.None)
        var confirmations = 0
        lateinit var feedback: TopicSubmitFeedback
        lateinit var scope: CoroutineScope

        compose.setContent {
            val currentRefreshKind by refreshKind.collectAsState()
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Box(modifier = Modifier.fillMaxSize()) {
                    scope = rememberCoroutineScope()
                    feedback = remember { TopicSubmitFeedback(dismissCurrentSnackbar = {}) }
                    TopicSubmitFeedbackEffect(currentRefreshKind, feedback, scope) {
                        confirmations += 1
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.runOnIdle {
            feedback.offerSubmittedElsewhere(
                scope = scope,
                show = { SnackbarResult.ActionPerformed },
                openPage = {
                    // #1301 — both writes happen before Compose can observe an intermediate value,
                    // reproducing the StateFlow conflation that left skipNextConfirmation armed.
                    refreshKind.value = TopicRefreshKind.PostSubmitJump
                    refreshKind.value = TopicRefreshKind.None
                },
            )
        }
        compose.waitForIdle()
        assertEquals(0, confirmations)

        compose.runOnIdle { refreshKind.value = TopicRefreshKind.PostSubmit }
        compose.waitUntil(TIMEOUT_MS) { confirmations == 1 }

        assertEquals("the next genuine submit must still be acknowledged", 1, confirmations)
    }

    private companion object {
        const val TIMEOUT_MS = 2_000L
    }
}
