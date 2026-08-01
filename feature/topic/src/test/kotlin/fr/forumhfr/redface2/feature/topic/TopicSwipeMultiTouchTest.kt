package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #936 — multi-touch hardening of [topicPageSwipe], the first TESTED slice of the #182 magnifier:
 * a second pointer landing during an UNCOMMITTED drag must cancel the swipe into the existing
 * spring-back (no navigation, no residual translation) — even past the commit distance (armed is
 * not committed), reading the raw pointer topology BEFORE consumption so the outcome never depends
 * on another detector's pass order. These tests run WITHOUT the magnifier in the tree on purpose:
 * with it, its Initial-pass consumption alone would cancel the drag and the test would stay green
 * without proving anything about the swipe's own defense.
 *
 * Geometry: box 360dp wide at xxhdpi. Commit distance = max(width×0.20, 72dp) = 72dp+; total
 * drags of 600px (~200dp) are safely past both slop and the commit distance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopicSwipeMultiTouchTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setSwipeContent(
        dragOffset: androidx.compose.runtime.MutableFloatState,
        onOpenPage: (Int) -> Unit,
    ) {
        compose.setContent {
            Box(
                Modifier
                    .size(360.dp, 600.dp)
                    .testTag("page")
                    .topicPageSwipe(
                        currentPage = 5,
                        totalPages = { 10 },
                        dragOffset = dragOffset,
                        handlers = TopicSwipeHandlers(
                            haptics = LocalHapticFeedback.current,
                            onOpenPage = onOpenPage,
                            enabled = { true },
                            leftGestureInsetPx = { 0 },
                            rightGestureInsetPx = { 0 },
                        ),
                    ),
            )
        }
    }

    @Test
    fun `a second pointer during an uncommitted drag cancels without navigating`() {
        var opened: Int? = null
        val dragOffset = mutableFloatStateOf(0f)
        setSwipeContent(dragOffset) { opened = it }

        compose.onNodeWithTag("page").performTouchInput {
            down(0, center)
            // Past slop AND past the commit distance: the swipe is ARMED — still cancellable.
            repeat(10) { moveBy(0, Offset(-60f, 0f)) }
        }
        // Post-slop PROOF (gate Sol): the drag must be visibly engaged BEFORE the second finger,
        // otherwise this test could pass without any swipe ever existing.
        compose.runOnIdle {
            assertTrue(
                "the drag must be engaged before the cancellation is tested",
                dragOffset.floatValue < -100f,
            )
        }
        compose.onNodeWithTag("page").performTouchInput {
            down(1, center + Offset(0f, 150f))
            up(0)
            up(1)
        }
        compose.waitForIdle()

        assertNull("a cancelled swipe must never navigate", opened)
        assertEquals("spring-back must return the page to rest", 0f, dragOffset.floatValue, 0.5f)
    }

    @Test
    fun `the primary up racing the secondary down still cancels`() {
        var opened: Int? = null
        val dragOffset = mutableFloatStateOf(0f)
        setSwipeContent(dragOffset) { opened = it }

        compose.onNodeWithTag("page").performTouchInput {
            down(0, center)
            repeat(10) { moveBy(0, Offset(-60f, 0f)) }
            // Adjacent events with no move in between — the closest the test API gets to the
            // same-frame race. Structural guarantee: the topology branch runs FIRST, so even a
            // single event carrying both changes resolves to MULTI_TOUCH (armed ≠ committed).
            down(1, center + Offset(0f, 150f))
            up(0)
            up(1)
        }
        compose.waitForIdle()

        assertNull("no transfer: the armed swipe must cancel, not commit", opened)
        assertEquals(0f, dragOffset.floatValue, 0.5f)
    }

    @Test
    fun `vertical movement past slop does not feed the swipe`() {
        var opened: Int? = null
        val dragOffset = mutableFloatStateOf(0f)
        setSwipeContent(dragOffset) { opened = it }

        compose.onNodeWithTag("page").performTouchInput {
            down(0, center)
            repeat(3) { moveBy(0, Offset(-30f, 0f)) } // past slop, under the commit distance
        }
        val engaged = dragOffset.floatValue
        compose.onNodeWithTag("page").performTouchInput {
            // Purely vertical deltas: the former horizontalDrag ignored them; the hand-rolled
            // loop must too (gate Sol: positionChanged() accepted them, stealing the scroll).
            repeat(5) { moveBy(0, Offset(0f, 80f)) }
        }
        compose.runOnIdle {
            assertEquals(
                "vertical deltas must not move the swipe offset",
                engaged,
                dragOffset.floatValue,
                0.5f,
            )
        }
        compose.onNodeWithTag("page").performTouchInput { up(0) }
        compose.waitForIdle()
        assertNull("under the commit distance, release must not navigate", opened)
    }

    @Test
    fun `a committed swipe still navigates when a pointer lands during the slide-out`() {
        var opened: Int? = null
        val dragOffset = mutableFloatStateOf(0f)
        setSwipeContent(dragOffset) { opened = it }

        compose.onNodeWithTag("page").performTouchInput {
            down(0, center)
            repeat(10) { moveBy(0, Offset(-60f, 0f)) }
            up(0) // commit: the slide-out starts, then onOpenPage fires
        }
        compose.onNodeWithTag("page").performTouchInput {
            // Lands inside the slide-out window: the committed latch must ignore it.
            down(1, center)
            up(1)
        }
        compose.waitForIdle()

        assertEquals("the committed navigation must fire exactly once", 6, opened)
    }

    @Test
    fun `a single-finger commit still navigates`() {
        var opened: Int? = null
        val dragOffset = mutableFloatStateOf(0f)
        setSwipeContent(dragOffset) { opened = it }

        compose.onNodeWithTag("page").performTouchInput {
            down(0, center)
            repeat(10) { moveBy(0, Offset(-60f, 0f)) }
            up(0)
        }
        compose.waitForIdle()

        assertEquals("regression: the plain swipe must keep navigating", 6, opened)
    }

    @Test
    fun `a second pointer before slop leaves everything at rest`() {
        var opened: Int? = null
        val dragOffset = mutableFloatStateOf(0f)
        setSwipeContent(dragOffset) { opened = it }

        compose.onNodeWithTag("page").performTouchInput {
            down(0, center)
            moveBy(0, Offset(-4f, 0f)) // under slop: no swipe exists yet (#936 scope boundary)
            down(1, center + Offset(0f, 150f))
            up(0)
            up(1)
        }
        compose.waitForIdle()

        assertNull(opened)
        assertEquals(0f, dragOffset.floatValue, 0.5f)
    }
}
