package fr.forumhfr.redface2.feature.topic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1040 lot 6 — pixel proof for the extraction of the topic draw layer. The `before` branch is the
 * exact pre-extraction graphicsLayer body; the `after` branch is [topicZoomTransform]. Both render
 * the SAME deterministic topic harness at 1× and at one fixed non-trivial transform. Roborazzi
 * records all four review artefacts, while Bitmap.sameAs makes exact pixel equality an assertion.
 *
 * This proves draw-layer equivalence only. Gesture/state behaviour remains the responsibility of
 * TopicZoomMathTest, TopicZoomGestureTest, TopicZoomQuoteFoldTest and TopicSwipeMultiTouchTest.
 *
 *     ./scripts/docker-dev.sh ./gradlew :feature:topic:testDebugUnitTest \
 *         --tests '*TopicZoomExtractionRoborazziTest*' --console=plain --no-daemon
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TopicZoomExtractionRoborazziTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shared draw modifier is pixel identical at 1x`() {
        assertPixelIdentical(scale = 1f, panX = 0f, panY = 0f, suffix = "1x")
    }

    @Test
    fun `shared draw modifier is pixel identical at a fixed transform`() {
        assertPixelIdentical(scale = 1.75f, panX = -240f, panY = -360f, suffix = "fixed")
    }

    private fun assertPixelIdentical(scale: Float, panX: Float, panY: Float, suffix: String) {
        val useSharedTransform = mutableStateOf(false)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val scope = rememberCoroutineScope()
                val zoomState = remember(scope, scale, panX, panY) {
                    TopicZoomState(scope).apply {
                        this.scale.floatValue = scale
                        this.panX.floatValue = panX
                        this.panY.floatValue = panY
                    }
                }
                val transform = if (useSharedTransform.value) {
                    Modifier.topicZoomTransform(zoomState)
                } else {
                    Modifier.legacyTopicZoomTransform(zoomState)
                }
                TopicHarness(
                    modifier = Modifier
                        .size(360.dp, 600.dp)
                        .testTag(CAPTURE_TAG)
                        .then(transform),
                )
            }
        }

        val before = captureHarness(name = "topic_zoom_extraction_before_$suffix")
        compose.runOnIdle { useSharedTransform.value = true }
        val after = captureHarness(name = "topic_zoom_extraction_after_$suffix")

        assertTrue("before/after pixels must match exactly at $suffix", before.sameAs(after))
    }

    private fun captureHarness(name: String): Bitmap {
        val filePath = "build/outputs/roborazzi/$name.png"
        val node = compose.onNodeWithTag(CAPTURE_TAG)
        node.captureRoboImage(filePath = filePath)
        return checkNotNull(BitmapFactory.decodeFile(filePath)) {
            "Roborazzi capture could not be decoded: $filePath"
        }
    }

    /** Exact draw layer that lived inline in TopicScreen before the extraction. */
    private fun Modifier.legacyTopicZoomTransform(state: TopicZoomState): Modifier = graphicsLayer {
        val zoomScale = state.scale.floatValue
        scaleX = zoomScale
        scaleY = zoomScale
        translationX = state.panX.floatValue
        translationY = state.panY.floatValue
        transformOrigin = TransformOrigin(0f, 0f)
    }

    @Composable
    private fun TopicHarness(modifier: Modifier) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(8) { index ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(
                                if (index % 2 == 0) {
                                    MaterialTheme.colorScheme.surfaceContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                            )
                            .padding(12.dp),
                    ) {
                        Text(
                            text = "Message topic de référence ${index + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val CAPTURE_TAG = "topic_zoom_extraction_capture"
    }
}
