package fr.forumhfr.redface2.core.ui.zoom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1106 — characterizes Compose layer hit-testing for the shared magnifier transform.
 *
 * A click at the visual position of a scaled + translated child must reach that child; a click at
 * the child's original layout position must not. If this turns red, PinchZoom cannot rely on
 * graphicsLayer hit-testing and needs explicit coordinate mapping or a layout transform.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinchZoomTransformHitTestTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `graphicsLayer maps hit testing through the zoom transform`() {
        var clicks = 0
        var transformedChildCenter: Offset? = null
        var untransformedChildCenter: Offset? = null
        compose.setContent {
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            val pan = with(density) { Offset(PAN_X_DP.dp.toPx(), PAN_Y_DP.dp.toPx()) }
            val childCenter = with(density) {
                Offset(
                    x = (CHILD_X_DP + CHILD_SIZE_DP / 2f).dp.toPx(),
                    y = (CHILD_Y_DP + CHILD_SIZE_DP / 2f).dp.toPx(),
                )
            }
            val zoomState = remember(scope, density) {
                PinchZoomState(scope).apply {
                    scale.floatValue = ZOOM_SCALE
                    panX.floatValue = pan.x
                    panY.floatValue = pan.y
                }
            }
            SideEffect {
                untransformedChildCenter = childCenter
                transformedChildCenter = Offset(
                    x = childCenter.x * ZOOM_SCALE + pan.x,
                    y = childCenter.y * ZOOM_SCALE + pan.y,
                )
            }
            Box(Modifier.size(HOST_SIZE_DP.dp).testTag(HOST_TAG)) {
                Box(
                    Modifier
                        .size(CONTENT_SIZE_DP.dp)
                        .pinchZoomTransform(zoomState),
                ) {
                    Box(
                        Modifier
                            .offset(x = CHILD_X_DP.dp, y = CHILD_Y_DP.dp)
                            .size(CHILD_SIZE_DP.dp)
                            .background(Color.Red)
                            .clickable { clicks++ },
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(HOST_TAG).performTouchInput {
            click(requireNotNull(transformedChildCenter))
        }
        compose.waitForIdle()
        assertEquals("the visual transformed child position must receive the click", 1, clicks)

        compose.onNodeWithTag(HOST_TAG).performTouchInput {
            click(requireNotNull(untransformedChildCenter))
        }
        compose.waitForIdle()
        assertEquals("the original layout position must no longer hit the transformed child", 1, clicks)
    }

    private companion object {
        const val HOST_TAG = "zoom-hit-host"
        const val HOST_SIZE_DP = 320f
        const val CONTENT_SIZE_DP = 160f
        const val CHILD_X_DP = 90f
        const val CHILD_Y_DP = 50f
        const val CHILD_SIZE_DP = 20f
        const val PAN_X_DP = -30f
        const val PAN_Y_DP = 20f
        const val ZOOM_SCALE = 2f
    }
}
