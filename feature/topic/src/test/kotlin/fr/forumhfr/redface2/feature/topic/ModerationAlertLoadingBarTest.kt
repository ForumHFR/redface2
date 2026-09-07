package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Host fallback: :app has no Compose test harness; the VM tests cover the retained read and dismissal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ModerationAlertLoadingBarTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `initial read indicator is thin indeterminate and politely announced`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.testTag(BAR_SLOT_TAG)) { ModerationAlertLoadingBar(visible = true) }
                }
            }
        }
        // M3 inflates the indicator's own semantics bounds by 10.dp on each side to meet the
        // screen-reader target, so the drawn height is read from the wrapper's layout size.
        compose.onNodeWithTag(BAR_SLOT_TAG).assertHeightIsEqualTo(4.dp)
        compose.onNodeWithContentDescription(LOADING_LABEL)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
                ),
            )
    }

    @Test
    fun `toggling the initial read overlay keeps scaffold content in place`() {
        val visible = mutableStateOf(false)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Scaffold { padding ->
                    Surface(
                        modifier = Modifier.fillMaxSize().padding(padding).testTag("topic-content"),
                        color = MaterialTheme.colorScheme.surface,
                    ) { Box(Modifier.fillMaxSize()) }
                    ModerationAlertLoadingBar(visible.value, Modifier.padding(padding))
                }
            }
        }
        val initialBounds = compose.onNodeWithTag("topic-content").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription(LOADING_LABEL).assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        val barBounds = compose.onNodeWithContentDescription(LOADING_LABEL)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(initialBounds.top, barBounds.top, 0.5f)
        assertEquals(initialBounds.width, barBounds.width, 0.5f)
        assertEquals(initialBounds, compose.onNodeWithTag("topic-content").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithContentDescription(LOADING_LABEL).assertDoesNotExist()
        assertEquals(initialBounds, compose.onNodeWithTag("topic-content").fetchSemanticsNode().boundsInRoot)
    }

    private companion object {
        const val LOADING_LABEL = "Chargement de l'alerte"
        const val BAR_SLOT_TAG = "alert-loading-bar-slot"
    }
}
