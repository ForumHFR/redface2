package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
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
 * #1040 lot 6 — transition and hardening contract of the in-place MP pager gesture. These tests
 * mount [threadPageSwipe] without the magnifier so multi-touch cancellation is proved by the swipe's
 * own topology loop, not incidentally by the magnifier's Initial-pass consumption.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadPageSwipeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `cache chaud - latch slide puis selection - la transition va au bout`() {
        val currentPage = mutableStateOf(2)
        val isRefreshing = mutableStateOf(false)
        val dragOffset = mutableFloatStateOf(0f)
        val selectedPages = mutableListOf<Int>()
        val offsetsAtSelection = mutableListOf<Float>()
        setSwipeContent(
            currentPage = currentPage,
            isRefreshing = isRefreshing,
            dragOffset = dragOffset,
            isTargetPageWarm = { true },
            onSelectPage = { target ->
                offsetsAtSelection += dragOffset.floatValue
                selectedPages += target
                currentPage.value = target
            },
        )

        swipeLeft(waitForIdle = false)
        // A second gesture starts inside the 200 ms slide-out window. The committed latch must
        // ignore it rather than compute another target from the still-rendered page 2.
        swipeLeft(waitForIdle = false)
        compose.waitForIdle()

        assertEquals(listOf(3), selectedPages)
        assertTrue(
            "warm selection must happen only after the outgoing page reached the left edge",
            offsetsAtSelection.single() < -1_000f,
        )
        assertEquals("the rendered target resets the retained offset", 0f, dragOffset.floatValue, 0.5f)

        // Page 3 re-keys pointerInput and creates a fresh latch: the next independent swipe works.
        swipeLeft()
        assertEquals(listOf(3, 4), selectedPages)
    }

    @Test
    fun `cache froid - selection a offset nul - la page sortante reste au repos`() {
        val isRefreshing = mutableStateOf(false)
        val dragOffset = mutableFloatStateOf(0f)
        var offsetAtSelection: Float? = null
        val selectedPages = mutableListOf<Int>()
        setSwipeContent(
            currentPage = mutableStateOf(2),
            isRefreshing = isRefreshing,
            dragOffset = dragOffset,
            isTargetPageWarm = { false },
            onSelectPage = { target ->
                offsetAtSelection = dragOffset.floatValue
                selectedPages += target
                // Mirrors the ViewModel's keep-content load: rendered page stays 2 while loading.
                isRefreshing.value = true
            },
        )

        swipeLeft()

        assertEquals(listOf(3), selectedPages)
        assertEquals(
            "a cold selection must start only after spring-back reached readable rest",
            0f,
            requireNotNull(offsetAtSelection),
            0.5f,
        )
        assertEquals(0f, dragOffset.floatValue, 0.5f)
        assertTrue(isRefreshing.value)
    }

    @Test
    fun `echec reseau - retour au repos contenu lisible et geste rearmable`() {
        val currentPage = mutableStateOf(2)
        val isRefreshing = mutableStateOf(false)
        val dragOffset = mutableFloatStateOf(0f)
        val selectedPages = mutableListOf<Int>()
        setSwipeContent(
            currentPage = currentPage,
            isRefreshing = isRefreshing,
            dragOffset = dragOffset,
            isTargetPageWarm = { false },
            onSelectPage = { target ->
                selectedPages += target
                isRefreshing.value = true
            },
        )

        swipeLeft()
        assertEquals(listOf(3), selectedPages)
        assertEquals(2, currentPage.value)
        assertEquals(0f, dragOffset.floatValue, 0.5f)

        // A failed keep-content load returns true→false without changing the rendered page. That
        // transition is deliberately a pointerInput key, so it must replace the committed latch.
        compose.runOnIdle { isRefreshing.value = false }
        compose.waitForIdle()
        swipeLeft()

        assertEquals("the same target can be retried after failure", listOf(3, 3), selectedPages)
        assertEquals(0f, dragOffset.floatValue, 0.5f)
    }

    @Test
    fun `outward swipes are inert on the first and last pages`() {
        val currentPage = mutableStateOf(1)
        val selectedPages = mutableListOf<Int>()
        setSwipeContent(
            currentPage = currentPage,
            isRefreshing = mutableStateOf(false),
            onSelectPage = selectedPages::add,
        )

        swipeRight()
        assertEquals(emptyList<Int>(), selectedPages)

        compose.runOnIdle { currentPage.value = 5 }
        compose.waitForIdle()
        swipeLeft()

        assertEquals(emptyList<Int>(), selectedPages)
    }

    @Test
    fun `the loading gate is read again after refresh settles`() {
        val isRefreshing = mutableStateOf(true)
        val selectedPages = mutableListOf<Int>()
        setSwipeContent(
            currentPage = mutableStateOf(2),
            isRefreshing = isRefreshing,
            onSelectPage = selectedPages::add,
        )

        swipeLeft()
        assertEquals("swipe must be inert while loading", emptyList<Int>(), selectedPages)

        compose.runOnIdle { isRefreshing.value = false }
        compose.waitForIdle()
        swipeLeft()

        assertEquals("the same composition must re-arm", listOf(3), selectedPages)
    }

    @Test
    fun `a second pointer cancels an armed MP swipe without selecting`() {
        val dragOffset = mutableFloatStateOf(0f)
        var selected: Int? = null
        setSwipeContent(
            currentPage = mutableStateOf(2),
            isRefreshing = mutableStateOf(false),
            dragOffset = dragOffset,
            onSelectPage = { selected = it },
        )

        compose.onNodeWithTag(PAGE_TAG).performTouchInput {
            down(0, center)
            repeat(10) { moveBy(0, Offset(-60f, 0f)) }
        }
        compose.runOnIdle {
            assertTrue("the primary drag must be armed before cancellation", dragOffset.floatValue < -100f)
        }
        compose.onNodeWithTag(PAGE_TAG).performTouchInput {
            down(1, center + Offset(0f, 150f))
            up(0)
            up(1)
        }
        compose.waitForIdle()

        assertNull(selected)
        assertEquals(0f, dragOffset.floatValue, 0.5f)
    }

    @Test
    fun `a competing producer starting during drag cancels before commit`() {
        val dragOffset = mutableFloatStateOf(0f)
        val gestureBlocked = mutableStateOf(false)
        var selected: Int? = null
        setSwipeContent(
            currentPage = mutableStateOf(2),
            isRefreshing = mutableStateOf(false),
            dragOffset = dragOffset,
            gestureBlocked = gestureBlocked,
            onSelectPage = { selected = it },
        )

        compose.onNodeWithTag(PAGE_TAG).performTouchInput {
            down(0, center)
            repeat(10) { moveBy(0, Offset(-60f, 0f)) }
        }
        compose.runOnIdle {
            assertTrue("the swipe must be armed before the producer starts", dragOffset.floatValue < -100f)
            gestureBlocked.value = true
        }
        compose.onNodeWithTag(PAGE_TAG).performTouchInput { up(0) }
        compose.waitForIdle()

        assertNull(selected)
        assertEquals(0f, dragOffset.floatValue, 0.5f)
    }

    @Test
    fun `a down in the system gesture band never starts the MP swipe`() {
        val selectedPages = mutableListOf<Int>()
        setSwipeContent(
            currentPage = mutableStateOf(2),
            isRefreshing = mutableStateOf(false),
            leftGestureInsetPx = 120,
            onSelectPage = selectedPages::add,
        )

        compose.onNodeWithTag(PAGE_TAG).performTouchInput {
            down(0, Offset(60f, center.y))
            repeat(10) { moveBy(0, Offset(-60f, 0f)) }
            up(0)
        }
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), selectedPages)

        swipeLeft()
        assertEquals("a later down outside the band must remain available", listOf(3), selectedPages)
    }

    @Suppress("LongParameterList") // Gesture harness: every independent gate is intentionally injectable.
    private fun setSwipeContent(
        currentPage: State<Int>,
        isRefreshing: State<Boolean>,
        onSelectPage: (Int) -> Unit,
        dragOffset: MutableFloatState = mutableFloatStateOf(0f),
        totalPages: Int = 5,
        isTargetPageWarm: (Int) -> Boolean = { false },
        leftGestureInsetPx: Int = 0,
        rightGestureInsetPx: Int = 0,
        gestureBlocked: State<Boolean> = mutableStateOf(false),
    ) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val currentTotal = rememberUpdatedState(totalPages)
                val currentRefreshing = rememberUpdatedState(isRefreshing.value)
                val currentOnSelectPage = rememberUpdatedState(onSelectPage)
                val currentWarmProbe = rememberUpdatedState(isTargetPageWarm)
                val currentLeftInset = rememberUpdatedState(leftGestureInsetPx)
                val currentRightInset = rememberUpdatedState(rightGestureInsetPx)
                val currentGestureBlocked = rememberUpdatedState(gestureBlocked.value)
                val haptics = LocalHapticFeedback.current
                val handlers = remember(haptics) {
                    ThreadSwipeHandlers(
                        haptics = haptics,
                        onSelectPage = { page -> currentOnSelectPage.value(page) },
                        enabled = { !currentRefreshing.value && !currentGestureBlocked.value },
                        isTargetPageWarm = { page -> currentWarmProbe.value(page) },
                        leftGestureInsetPx = { currentLeftInset.value },
                        rightGestureInsetPx = { currentRightInset.value },
                    )
                }
                LaunchedEffect(currentPage.value, isRefreshing.value) {
                    dragOffset.floatValue = 0f
                }
                Box(
                    modifier = Modifier
                        .size(360.dp, 600.dp)
                        .testTag(PAGE_TAG)
                        .threadPageSwipe(
                            currentPage = currentPage.value,
                            totalPages = { currentTotal.value },
                            isRefreshing = isRefreshing.value,
                            dragOffset = dragOffset,
                            handlers = handlers,
                        ),
                )
            }
        }
    }

    private fun swipeLeft(waitForIdle: Boolean = true) {
        compose.onNodeWithTag(PAGE_TAG).performTouchInput {
            down(0, center)
            repeat(8) { moveBy(0, Offset(-60f, 0f)) }
            up(0)
        }
        if (waitForIdle) compose.waitForIdle()
    }

    private fun swipeRight() {
        compose.onNodeWithTag(PAGE_TAG).performTouchInput {
            down(0, center)
            repeat(8) { moveBy(0, Offset(60f, 0f)) }
            up(0)
        }
        compose.waitForIdle()
    }

    private companion object {
        const val PAGE_TAG = "private_thread_page_swipe"
    }
}
