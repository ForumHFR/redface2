package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
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
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.pager.MIN_COMMIT_DISTANCE
import fr.forumhfr.redface2.core.ui.pager.swipeArmed
import fr.forumhfr.redface2.core.ui.pager.swipeCommitDistancePx
import fr.forumhfr.redface2.core.ui.zoom.PinchZoomState
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1040 lot 6 — transition and hardening contract of the in-place MP pager gesture. The gesture
 * mechanics mount [threadPageSwipe] without the magnifier so multi-touch cancellation is proved by
 * the swipe's own topology loop, not incidentally by the magnifier's Initial-pass consumption.
 * Separate wiring cases mount [rememberThreadSwipeModifier] and [PrivateMessageThreadContent] so
 * the production gate and composition-level reset cannot drift away from those mechanics tests.
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
                // The production load first keeps mode.thread (the rendered page) and closes the
                // load gate. Only a later cache/network emission may replace currentPage.
                isRefreshing.value = true
            },
        )
        val pageWidthPx = pageWidth()
        compose.mainClock.autoAdvance = false

        swipeLeft(waitForIdle = false)
        assertEquals("selection must wait for the slide-out", emptyList<Int>(), selectedPages)
        // A second gesture starts inside the 200 ms slide-out window. The committed latch must
        // ignore it rather than compute another target from the still-rendered page 2. Freezing
        // the frame clock is essential: a second Compose test action otherwise advances the first
        // animation to idle before injecting its DOWN and no longer represents overlapping input.
        swipeLeft(waitForIdle = false)
        compose.mainClock.advanceTimeBy(SLIDE_OUT_TEST_ADVANCE_MILLIS)
        compose.waitForIdle()

        assertEquals(listOf(3), selectedPages)
        assertEquals("selection alone must not replace rendered content", 2, currentPage.value)
        assertTrue("selection must close the production load gate", isRefreshing.value)
        assertEquals(
            "warm selection must happen only after the outgoing page reached the left edge",
            -pageWidthPx,
            offsetsAtSelection.single(),
            GEOMETRY_TOLERANCE_PX,
        )
        assertEquals(
            "the rendered target resets the retained offset",
            0f,
            dragOffset.floatValue,
            GEOMETRY_TOLERANCE_PX,
        )

        // Mirror the production order: the asynchronous cache emission renders page 3 while
        // revalidation keeps the fresh pointer block disabled, then the terminal network emission
        // reopens the gate. Only then is another intentional swipe independent.
        compose.runOnIdle { currentPage.value = 3 }
        compose.waitForIdle()
        assertTrue(isRefreshing.value)
        compose.runOnIdle { isRefreshing.value = false }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
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
            GEOMETRY_TOLERANCE_PX,
        )
        assertEquals(0f, dragOffset.floatValue, GEOMETRY_TOLERANCE_PX)
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
        assertEquals(0f, dragOffset.floatValue, GEOMETRY_TOLERANCE_PX)

        // A failed keep-content load returns true→false without changing the rendered page. That
        // transition is deliberately a pointerInput key, so it must replace the committed latch.
        compose.runOnIdle { isRefreshing.value = false }
        compose.waitForIdle()
        swipeLeft()

        assertEquals("the same target can be retried after failure", listOf(3, 3), selectedPages)
        assertEquals(0f, dragOffset.floatValue, GEOMETRY_TOLERANCE_PX)
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
        val commitDistancePx = pageCommitDistance()

        compose.onNodeWithTag(PAGE_TAG).performTouchInput {
            down(0, center)
            repeat(10) { moveBy(0, Offset(-60f, 0f)) }
        }
        compose.runOnIdle {
            assertTrue(
                "the primary drag must be armed before cancellation",
                swipeArmed(dragOffset.floatValue, commitDistancePx),
            )
        }
        compose.onNodeWithTag(PAGE_TAG).performTouchInput {
            down(1, center + Offset(0f, 150f))
            up(0)
            up(1)
        }
        compose.waitForIdle()

        assertNull(selected)
        assertEquals(0f, dragOffset.floatValue, GEOMETRY_TOLERANCE_PX)
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
        val commitDistancePx = pageCommitDistance()

        compose.onNodeWithTag(PAGE_TAG).performTouchInput {
            down(0, center)
            repeat(10) { moveBy(0, Offset(-60f, 0f)) }
        }
        compose.runOnIdle {
            assertTrue(
                "the swipe must be armed before the producer starts",
                swipeArmed(dragOffset.floatValue, commitDistancePx),
            )
            gestureBlocked.value = true
        }
        compose.onNodeWithTag(PAGE_TAG).performTouchInput { up(0) }
        compose.waitForIdle()

        assertNull(selected)
        assertEquals(0f, dragOffset.floatValue, GEOMETRY_TOLERANCE_PX)
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

    @Test
    fun `an aligned idle list has no competing producer`() {
        assertFalse(hasCompetingProducer())
    }

    @Test
    fun `zoomed content alone is a competing producer`() {
        assertTrue(
            hasCompetingProducer(
                ProducerScenario(zoom = ZoomProducer(zoomed = true)),
            ),
        )
    }

    @Test
    fun `a zoom list mutation alone is a competing producer`() {
        assertTrue(
            hasCompetingProducer(
                ProducerScenario(zoom = ZoomProducer(mutatingListPosition = true)),
            ),
        )
    }

    @Test
    fun `a scrollbar drag alone is a competing producer`() {
        assertTrue(hasCompetingProducer(ProducerScenario(scrollbarDragging = true)))
    }

    @Test
    fun `a native scroll alone is a competing producer`() {
        assertTrue(hasCompetingProducer(ProducerScenario(nativeScrollInProgress = true)))
    }

    @Test
    fun `a pending page landing alone is a competing producer`() {
        assertTrue(hasCompetingProducer(ProducerScenario(pageLandingPending = true)))
    }

    @Test
    fun `the alignment window alone is a competing producer`() {
        assertTrue(hasCompetingProducer(ProducerScenario(alignmentWindowOpen = true)))
    }

    @Test
    fun `thread content passes its pending landing gate through the production modifier`() {
        val pendingLanding = mutableStateOf<PrivateMessagePageLanding?>(
            PrivateMessagePageLanding.Top(
                generation = 1,
                account = "xaat",
                page = PAGE,
            ),
        )
        val selectedPages = mutableListOf<Int>()
        compose.setContent {
            val state = loadedState(pageLandingPending = false).copy(
                pageLanding = pendingLanding.value,
                connectedPseudo = "xaat",
            )
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = swipeCallbacks(selectedPages::add),
                )
            }
        }

        swipeThreadReaderLeft()
        assertEquals("the real pending landing gate must block the gesture", emptyList<Int>(), selectedPages)

        compose.runOnIdle { pendingLanding.value = null }
        compose.waitForIdle()
        swipeThreadReaderLeft()
        assertEquals("the same production wiring must re-arm after landing", listOf(3), selectedPages)
    }

    @Test
    fun `production modifier keeps one commit until the warm page has landed`() {
        val renderedPage = mutableStateOf(2)
        val isRefreshing = mutableStateOf(false)
        val competingProducer = mutableStateOf(false)
        val selectedPages = mutableListOf<Int>()
        setProductionSwipeContent(
            currentPage = renderedPage,
            isRefreshing = isRefreshing,
            interaction = ThreadSwipeInteraction(
                onSelectPage = { target ->
                    selectedPages += target
                    // PrivateMessageThreadViewModel.fetchPage publishes this keep-content state
                    // before its repository Flow can emit the target page on the IO dispatcher.
                    isRefreshing.value = true
                },
                isTargetPageWarm = { true },
                hasCompetingListProducer = { competingProducer.value },
            ),
        )
        compose.mainClock.autoAdvance = false

        swipeLeft(waitForIdle = false)
        assertEquals("selection must wait for the slide-out", emptyList<Int>(), selectedPages)
        // The decision was acquired at lift-off. A scrollbar/zoom/list producer appearing during
        // the release may invalidate the departure anchor, but must not revoke the page switch.
        competingProducer.value = true
        swipeLeft(waitForIdle = false)
        compose.mainClock.advanceTimeBy(SLIDE_OUT_TEST_ADVANCE_MILLIS)
        compose.waitForIdle()

        assertEquals("the committed production modifier must select exactly once", listOf(3), selectedPages)
        assertEquals("the outgoing page remains rendered until content arrives", 2, renderedPage.value)
        assertTrue(isRefreshing.value)

        competingProducer.value = false
        // A cache hit now replaces the rendered page and re-keys pointerInput, but mandatory
        // network revalidation keeps the new block disabled: a fresh latch is not yet a re-arm.
        compose.runOnIdle { renderedPage.value = 3 }
        compose.waitForIdle()
        swipeLeft(waitForIdle = false)
        assertEquals("cache landing must stay gated during revalidation", listOf(3), selectedPages)

        compose.runOnIdle { isRefreshing.value = false }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        swipeLeft()

        assertEquals("terminal landing must re-arm one later intentional swipe", listOf(3, 4), selectedPages)
    }

    @Test
    fun `remember modifier resets an unfinished drag when refresh rekeys the gesture`() {
        val isRefreshing = mutableStateOf(false)
        setProductionSwipeContent(
            currentPage = mutableStateOf(2),
            isRefreshing = isRefreshing,
            interaction = ThreadSwipeInteraction(onSelectPage = {}),
        )
        val restLeft = pageLeft()
        val pageWidthPx = pageWidth()

        compose.onNodeWithTag(PAGE_TAG).performTouchInput {
            down(0, center)
            repeat(4) { moveBy(0, Offset(-60f, 0f)) }
        }
        assertTrue(
            "the unfinished production drag must translate the rendered page",
            pageLeft() < restLeft - pageWidthPx * VISIBLE_TRANSLATION_FRACTION,
        )
        compose.runOnIdle { isRefreshing.value = true }
        compose.waitForIdle()

        assertEquals(
            "the composition-level reset must park retained content after pointer cancellation",
            restLeft,
            pageLeft(),
            GEOMETRY_TOLERANCE_PX,
        )
        compose.onNodeWithTag(PAGE_TAG).performTouchInput { up(0) }
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

    private fun setProductionSwipeContent(
        currentPage: State<Int>,
        isRefreshing: State<Boolean>,
        interaction: ThreadSwipeInteraction,
    ) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val swipeModifier = rememberThreadSwipeModifier(
                    renderedPage = currentPage.value,
                    totalPages = 5,
                    isRefreshing = isRefreshing.value,
                    interaction = interaction,
                )
                Box(
                    modifier = Modifier
                        .size(360.dp, 600.dp)
                        .then(swipeModifier)
                        .testTag(PAGE_TAG),
                )
            }
        }
    }

    private fun hasCompetingProducer(
        scenario: ProducerScenario = ProducerScenario(),
    ): Boolean {
        val alignment = PrivateMessageListAlignment().apply {
            if (!scenario.alignmentWindowOpen) onLandingApplied(PAGE)
        }
        val listState = mockk<LazyListState> {
            every { isScrollInProgress } returns scenario.nativeScrollInProgress
        }
        val zoomState = mockk<PinchZoomState> {
            every { zoomed } returns scenario.zoom.zoomed
            every { isListPositionMutationInProgress } returns scenario.zoom.mutatingListPosition
        }
        return hasCompetingThreadListProducer(
            state = loadedState(pageLandingPending = scenario.pageLandingPending),
            session = PrivateMessageReaderSession(
                listState = listState,
                alignment = alignment,
                isScrollbarDragging = { scenario.scrollbarDragging },
                onScrollbarDragStateChanged = {},
            ),
            zoomState = zoomState,
        )
    }

    private fun loadedState(pageLandingPending: Boolean): PrivateMessageThreadUiState {
        val initial = PrivateMessageThreadUiState.initial(
            PrivateMessageThreadRequest(threadId = THREAD_ID, page = PAGE),
        )
        return initial.copy(
            mode = PrivateMessageThreadUiState.Mode.Content(
                thread = PrivateMessageThread(
                    threadId = THREAD_ID,
                    subject = "Sujet",
                    correspondent = "Correspondant",
                    messages = emptyList(),
                    page = PAGE,
                    totalPages = 5,
                ),
            ),
            totalPages = 5,
            pageLanding = if (pageLandingPending) {
                PrivateMessagePageLanding.Top(
                    generation = 1,
                    account = "xaat",
                    page = PAGE,
                )
            } else {
                null
            },
        )
    }

    private fun swipeCallbacks(onSelectPage: (Int) -> Unit) = PrivateMessageThreadCallbacks(
        onBack = {},
        onReply = {},
        onRetry = {},
        onRefresh = {},
        onSelectPage = { page, _ -> onSelectPage(page) },
        onOpenRoster = {},
        onDismissRoster = {},
        onRetryRoster = {},
        onManageRecipients = {},
    )

    private fun pageLeft(): Float = compose
        .onNodeWithTag(PAGE_TAG)
        .fetchSemanticsNode()
        .boundsInRoot
        .left

    private fun pageWidth(): Float = compose
        .onNodeWithTag(PAGE_TAG)
        .fetchSemanticsNode()
        .boundsInRoot
        .width

    private fun pageCommitDistance(): Float {
        val minCommitPx = with(compose.density) { MIN_COMMIT_DISTANCE.toPx() }
        return swipeCommitDistancePx(pageWidth(), minCommitPx)
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

    private fun swipeThreadReaderLeft() {
        compose.onNodeWithTag(PRIVATE_MESSAGE_THREAD_READER_TAG).performTouchInput {
            down(0, center)
            repeat(8) { moveBy(0, Offset(-60f, 0f)) }
            up(0)
        }
        compose.waitForIdle()
    }

    private companion object {
        const val PAGE_TAG = "private_thread_page_swipe"
        const val THREAD_ID = 42
        const val PAGE = 2
        const val GEOMETRY_TOLERANCE_PX = 0.5f
        const val VISIBLE_TRANSLATION_FRACTION = 0.05f
        const val SLIDE_OUT_TEST_ADVANCE_MILLIS = 250L
    }

    private data class ProducerScenario(
        val zoom: ZoomProducer = ZoomProducer(),
        val scrollbarDragging: Boolean = false,
        val nativeScrollInProgress: Boolean = false,
        val pageLandingPending: Boolean = false,
        val alignmentWindowOpen: Boolean = false,
    )

    private data class ZoomProducer(
        val zoomed: Boolean = false,
        val mutatingListPosition: Boolean = false,
    )
}
