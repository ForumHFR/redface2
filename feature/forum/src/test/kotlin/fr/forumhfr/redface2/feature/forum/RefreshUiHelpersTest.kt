package fr.forumhfr.redface2.feature.forum

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pin the contract of [keepContentDuringRefresh] directly. The two ViewModel
 * tests cover the helper indirectly via their refresh flows, but only this
 * test asserts the suppression rule in isolation — no dispatcher, no scope,
 * no Hilt — so a regression cannot hide behind ViewModel orchestration.
 *
 * Test fixture is a tiny sealed type that mirrors the project's UI-state
 * shape (`Loading` / `Content` / `Error`) without dragging in the real
 * Forum/Category states.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshUiHelpersTest {

    private sealed interface FakeState {
        data object Loading : FakeState
        data class Content(val payload: String) : FakeState
        data class Error(val message: String) : FakeState
    }

    private val isLoading: (FakeState) -> Boolean = { it is FakeState.Loading }
    private val isContent: (FakeState) -> Boolean = { it is FakeState.Content }

    @Test
    fun `cold-start Loading passes through when no prior Content`() = runTest {
        val source = MutableSharedFlow<FakeState>(replay = 0, extraBufferCapacity = 4)

        source.asSharedFlow().keepContentDuringRefresh(isLoading, isContent).test {
            source.emit(FakeState.Loading)
            assertEquals(FakeState.Loading, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loading after Content is suppressed so the screen keeps the prior content`() = runTest {
        val source = MutableSharedFlow<FakeState>(replay = 0, extraBufferCapacity = 4)
        val content = FakeState.Content("topics-page-1")

        source.asSharedFlow().keepContentDuringRefresh(isLoading, isContent).test {
            // First Loading gets through — cold start.
            source.emit(FakeState.Loading)
            assertEquals(FakeState.Loading, awaitItem())

            // Content lands and is replayed downstream.
            source.emit(content)
            assertEquals(content, awaitItem())

            // Refresh: the repository's broadcast re-emits Loading. The helper
            // must swallow it so the UI keeps rendering `content` under the
            // PullToRefresh indicator instead of bouncing through a spinner.
            source.emit(FakeState.Loading)
            expectNoEvents()

            // The refreshed Content arrives and propagates as the new value.
            val refreshed = FakeState.Content("topics-page-1-refreshed")
            source.emit(refreshed)
            assertEquals(refreshed, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Error after Content propagates so the Reessayer button can surface`() = runTest {
        val source = MutableSharedFlow<FakeState>(replay = 0, extraBufferCapacity = 4)
        val content = FakeState.Content("topics-page-1")
        val error = FakeState.Error("HFR éteint")

        source.asSharedFlow().keepContentDuringRefresh(isLoading, isContent).test {
            source.emit(content)
            assertEquals(content, awaitItem())

            // A refresh failure must NOT be silenced — keeping stale data forever
            // would mask network outages indefinitely.
            source.emit(error)
            assertEquals(error, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loading immediately after Error is suppressed only if Content was seen earlier`() = runTest {
        val source = MutableSharedFlow<FakeState>(replay = 0, extraBufferCapacity = 4)
        val content = FakeState.Content("topics-page-1")

        source.asSharedFlow().keepContentDuringRefresh(isLoading, isContent).test {
            // Establish the "lastContent" anchor.
            source.emit(content)
            assertEquals(content, awaitItem())

            // Error propagates …
            source.emit(FakeState.Error("boom"))
            awaitItem()

            // … but the next Loading is still suppressed because lastContent is set.
            // (The helper only resets lastContent on a new Content; an Error in the
            // middle does not invalidate the anchor.)
            source.emit(FakeState.Loading)
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }
}
