package fr.forumhfr.redface2.feature.topic

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.topic.TopicRepository
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.Topic
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TopicViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `flow emitting one topic exposes loaded state with derived available pages`() = runTest {
        val topic = fakeTopic(page = 2, totalPages = 5)
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))

        val viewModel = TopicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
        )

        viewModel.state.test {
            val loaded = awaitItem()
            val mode = assertMode<TopicUiState.Mode.Loaded>(loaded)
            assertEquals(topic, mode.topic)
            assertEquals(listOf(1, 2, 3, 4, 5), loaded.availablePages)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2)), repository.calls)
        assertEquals(
            "anonymous prefetch should fire on page+1 once the topic is loaded",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 3)),
            repository.prefetches,
        )
    }

    @Test
    fun `prefetch is cancelled when the screen is left (issue #108)`() = runTest {
        // Symmetric to CategoryViewModelTest's same-named case. Issue #108's
        // acceptance criterion is "le prefetch est annulé quand l'utilisateur quitte
        // la page". The topic-page prefetch is the more user-visible path of the two,
        // so it deserves its own pin — without this test, only the listing path
        // proves the cancellation chain. We suspend `prefetch` on awaitCancellation
        // and verify the in-flight call observes [CancellationException] when the
        // ViewModelStore is cleared (the public API the framework calls on screen
        // leave).
        val cancelObserved = kotlinx.coroutines.CompletableDeferred<Unit>()
        val topic = fakeTopic(page = 2, totalPages = 5)
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))
        repository.prefetchHook = { _, _, _ ->
            try {
                kotlinx.coroutines.awaitCancellation()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                cancelObserved.complete(Unit)
                throw cancellation
            }
        }
        val vm = TopicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
        )

        vm.state.test {
            assertMode<TopicUiState.Mode.Loaded>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            "prefetch should have been launched on Loaded(page=2)",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 3)),
            repository.prefetches,
        )

        val store = androidx.lifecycle.ViewModelStore()
        androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = vm as T
            },
        ).get(TopicViewModel::class.java)
        store.clear()

        kotlinx.coroutines.withTimeout(CANCEL_TIMEOUT_MS) {
            cancelObserved.await()
        }
    }

    @Test
    fun `last page does not trigger an out-of-range prefetch`() = runTest {
        val topic = fakeTopic(page = 5, totalPages = 5)
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))

        TopicViewModel(
            request = topicRequest(page = 5),
            topicRepository = repository,
        ).state.test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue("no prefetch on the final page", repository.prefetches.isEmpty())
    }


    @Test
    fun `cache emission lands before fresh emission in observable state sequence`() = runTest {
        val cached = fakeTopic(page = 1, totalPages = 3, title = "cached")
        val fresh = fakeTopic(page = 1, totalPages = 3, title = "fresh")
        // SharedFlow with no replay so we control exactly when each emission lands and can
        // observe the sequence Loading → Loaded(cached) → Loaded(fresh) with Turbine instead
        // of asserting only the final state (which would pass even if cache-first was broken).
        val controlled = MutableSharedFlow<Topic>(replay = 0)
        val repository = FakeStreamingTopicRepository(controlled)

        val viewModel = TopicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
        )

        viewModel.state.test {
            assertMode<TopicUiState.Mode.Loading>(awaitItem())

            controlled.emit(cached)
            val cachedState = awaitItem()
            assertEquals(cached, assertMode<TopicUiState.Mode.Loaded>(cachedState).topic)

            controlled.emit(fresh)
            val freshState = awaitItem()
            assertEquals(fresh, assertMode<TopicUiState.Mode.Loaded>(freshState).topic)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flow failing without prior emission exposes error mode`() = runTest {
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { throw IOException("network") }),
        )

        val viewModel = TopicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
        )

        val mode = assertMode<TopicUiState.Mode.Error>(viewModel.state.value)
        assertEquals("network", mode.message)
    }

    @Test
    fun `flow failing after a cached emission keeps the cached Loaded state`() = runTest {
        val cached = fakeTopic(page = 1, totalPages = 3, title = "cached")
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow {
                    emit(cached)
                    throw IOException("refresh failed")
                },
            ),
        )

        val viewModel = TopicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
        )

        val mode = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals(cached, mode.topic)
    }

    @Test
    fun `scrollTo emits a single ScrollToPost effect when the target post is in the loaded page`() = runTest {
        val target = 12_345
        val topic = fakeTopic(
            page = 3,
            totalPages = 5,
            posts = listOf(fakePost(numreponse = target)),
        )
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))

        val viewModel = TopicViewModel(
            request = topicRequest(page = 3, scrollTo = target),
            topicRepository = repository,
        )

        viewModel.effects.test {
            val effect = awaitItem() as TopicEffect.ScrollToPost
            assertEquals(target, effect.numreponse)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `scrollTo does not emit an effect when the target post is missing from the page`() = runTest {
        val topic = fakeTopic(page = 1, totalPages = 1, posts = listOf(fakePost(numreponse = 555)))
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))

        val viewModel = TopicViewModel(
            request = topicRequest(page = 1, scrollTo = 999),
            topicRepository = repository,
        )

        viewModel.effects.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `scrollTo emits at most once even when the page reloads`() = runTest {
        val target = 7_777
        val topic = fakeTopic(page = 2, totalPages = 5, posts = listOf(fakePost(numreponse = target)))
        // Two emissions in a row simulate cache + fresh ; the effect must fire only on the
        // first one so the user does not get re-snapped after they have scrolled away.
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow {
                emit(topic)
                emit(topic.copy(title = "fresh"))
            }),
        )

        val viewModel = TopicViewModel(
            request = topicRequest(page = 2, scrollTo = target),
            topicRepository = repository,
        )

        viewModel.effects.test {
            val first = awaitItem() as TopicEffect.ScrollToPost
            assertEquals(target, first.numreponse)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `canGoPrevious is false on page 1 and true otherwise`() = runTest {
        val topic = fakeTopic(page = 1, totalPages = 3)
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))
        val viewModel = TopicViewModel(
            request = topicRequest(page = 1),
            topicRepository = repository,
        )
        assertEquals(false, viewModel.state.value.canGoPrevious)
        assertEquals(true, viewModel.state.value.canGoNext)
    }

    @Test
    fun `canGoNext is false on the last page`() = runTest {
        val topic = fakeTopic(page = 5, totalPages = 5)
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))
        val viewModel = TopicViewModel(
            request = topicRequest(page = 5),
            topicRepository = repository,
        )
        assertEquals(true, viewModel.state.value.canGoPrevious)
        assertEquals(false, viewModel.state.value.canGoNext)
    }

    @Test
    fun `retry after error replays the current page and succeeds`() = runTest {
        val topic = fakeTopic(page = 2, totalPages = 4)
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(
                flow { throw IllegalStateException("boom") },
                flow { emit(topic) },
            ),
        )

        val viewModel = TopicViewModel(
            request = topicRequest(page = 2),
            topicRepository = repository,
        )

        assertMode<TopicUiState.Mode.Error>(viewModel.state.value)

        viewModel.send(TopicIntent.Retry)

        val loadedMode = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals(topic, loadedMode.topic)
        assertEquals(
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2), Triple(SAMPLE_CAT, SAMPLE_POST, 2)),
            repository.calls,
        )
    }

    private fun topicRequest(
        page: Int,
        scrollTo: Int? = null,
        submitSignal: Long? = null,
    ): TopicRequest = TopicRequest(
        cat = SAMPLE_CAT,
        post = SAMPLE_POST,
        page = page,
        scrollTo = scrollTo,
        submitSignal = submitSignal,
    )

    // ──────────────────────────────────────────────────────────────────────
    // Issue #200 — post-submit force refresh path
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `submitSignal triggers a force refresh instead of the cache-aside path`() = runTest {
        // Reply / quote / edit / edit-FP landing: the navigation host bumped submitSignal
        // so the ViewModel must skip observeTopicPage (cache-aside) and call refreshTopicPage
        // directly, otherwise the user would see a stale page that doesn't include the post
        // they just published.
        val freshTopic = fakeTopic(page = 2, totalPages = 5, posts = listOf(fakePost(987)))
        val repository = FakeTopicRepository(
            flowsToReturn = emptyList(),
            refreshTopicsToReturn = listOf(freshTopic),
        )

        val viewModel = TopicViewModel(
            request = topicRequest(page = 2, submitSignal = 1_700_000_000_000L),
            topicRepository = repository,
        )

        viewModel.state.test {
            val loaded = awaitItem()
            val mode = assertMode<TopicUiState.Mode.Loaded>(loaded)
            assertEquals(freshTopic, mode.topic)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            "Force refresh path must hit refreshTopicPage, not observeTopicPage",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2)),
            repository.refreshCalls,
        )
        assertTrue(
            "observeTopicPage must NOT be called when submitSignal is non-null",
            repository.calls.isEmpty(),
        )
    }

    @Test
    fun `submitSignal with scrollTo emits ScrollToPost after the force refresh`() = runTest {
        // Quote / edit / edit-FP path: the parser extracted #t{numreponse} so the navigation
        // host passed scrollTo through. The ViewModel must emit ScrollToPost(target) once
        // the force-refreshed page is loaded and contains the target post.
        val targetNumreponse = 2_523_833
        val freshTopic = fakeTopic(
            page = 1,
            totalPages = 3,
            posts = listOf(fakePost(2_523_829), fakePost(targetNumreponse)),
        )
        val repository = FakeTopicRepository(
            flowsToReturn = emptyList(),
            refreshTopicsToReturn = listOf(freshTopic),
        )

        val viewModel = TopicViewModel(
            request = topicRequest(page = 1, scrollTo = targetNumreponse, submitSignal = 42L),
            topicRepository = repository,
        )

        viewModel.effects.test {
            assertEquals(TopicEffect.ScrollToPost(targetNumreponse), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submitSignal without scrollTo emits ScrollToEndOfPage so plain reply lands on the new post`() = runTest {
        // Plain reply path: HFR anchored #bas so the parser left scrollTo null. The ViewModel
        // emits ScrollToEndOfPage so the screen scrolls to the last post (the one just
        // published) rather than letting the user wonder where their reply went.
        val freshTopic = fakeTopic(
            page = 20,
            totalPages = 20,
            posts = listOf(fakePost(1), fakePost(2), fakePost(3)),
        )
        val repository = FakeTopicRepository(
            flowsToReturn = emptyList(),
            refreshTopicsToReturn = listOf(freshTopic),
        )

        val viewModel = TopicViewModel(
            request = topicRequest(page = 20, scrollTo = null, submitSignal = 99L),
            topicRepository = repository,
        )

        viewModel.effects.test {
            assertEquals(TopicEffect.ScrollToEndOfPage, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `normal load without submitSignal does not emit ScrollToEndOfPage`() = runTest {
        // Regression guard: ScrollToEndOfPage is gated on submitSignal != null. A normal
        // deep-link navigation (cache-aside) must never emit it, even when scrollTo is null,
        // otherwise we would snap to the bottom on every back navigation.
        val topic = fakeTopic(page = 1, totalPages = 1, posts = listOf(fakePost(1)))
        val repository = FakeTopicRepository(flowsToReturn = listOf(flow { emit(topic) }))

        val viewModel = TopicViewModel(
            request = topicRequest(page = 1, scrollTo = null, submitSignal = null),
            topicRepository = repository,
        )

        viewModel.state.test {
            // wait for Loaded so the effect channel had a chance to receive anything
            assertMode<TopicUiState.Mode.Loaded>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // The effects channel is BUFFERED ; if anything had been sent it would still be
        // there. tryReceive() is non-blocking so the assertion is deterministic.
        viewModel.effects.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submitSignal refresh failure falls back to the cache-aside path`() = runTest {
        // Resilience: a transient network blip on the force refresh must not strand the user
        // on an error screen. The ViewModel falls back to observeTopicPage so the cached
        // page is shown (without the new post — but with a Retry affordance).
        val cachedTopic = fakeTopic(page = 2, totalPages = 5)
        val repository = FakeTopicRepository(
            flowsToReturn = listOf(flow { emit(cachedTopic) }),
            refreshErrorToThrow = IOException("force refresh transient failure"),
        )

        val viewModel = TopicViewModel(
            request = topicRequest(page = 2, submitSignal = 7L),
            topicRepository = repository,
        )

        viewModel.state.test {
            val loaded = awaitItem()
            val mode = assertMode<TopicUiState.Mode.Loaded>(loaded)
            assertEquals(cachedTopic, mode.topic)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            "Refresh attempted once before falling back",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2)),
            repository.refreshCalls,
        )
        assertEquals(
            "Fallback path must hit observeTopicPage after the failure",
            listOf(Triple(SAMPLE_CAT, SAMPLE_POST, 2)),
            repository.calls,
        )
    }

    private fun fakeTopic(
        page: Int,
        totalPages: Int,
        title: String = "fake",
        posts: List<Post> = emptyList(),
    ): Topic = Topic(
        cat = SAMPLE_CAT,
        post = SAMPLE_POST,
        subcat = SAMPLE_SUBCAT,
        title = title,
        posts = posts,
        page = page,
        totalPages = totalPages,
        isFirstPostOwner = false,
        poll = null,
    )

    private fun fakePost(numreponse: Int): Post = Post(
        numreponse = numreponse,
        author = "tester",
        date = java.time.Instant.parse("2026-05-04T12:00:00Z"),
        content = PostContent(blocks = emptyList()),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
    )

    private inline fun <reified T : TopicUiState.Mode> assertMode(state: TopicUiState): T {
        val mode = state.mode
        assertTrue("expected mode ${T::class.simpleName} but was ${mode::class.simpleName}", mode is T)
        return mode as T
    }

    companion object {
        private const val SAMPLE_CAT = 13
        private const val SAMPLE_POST = 84_540
        private const val SAMPLE_SUBCAT = 432
        private const val CANCEL_TIMEOUT_MS = 2_000L
    }
}

private class FakeTopicRepository(
    flowsToReturn: List<Flow<Topic>>,
    private val refreshTopicsToReturn: List<Topic> = emptyList(),
    private val refreshErrorToThrow: Throwable? = null,
) : TopicRepository {
    private val queue = ArrayDeque(flowsToReturn)
    private val refreshQueue = ArrayDeque(refreshTopicsToReturn)
    val calls: MutableList<Triple<Int, Int, Int>> = mutableListOf()
    val refreshCalls: MutableList<Triple<Int, Int, Int>> = mutableListOf()
    val prefetches: MutableList<Triple<Int, Int, Int>> = mutableListOf()

    /**
     * Optional hook to suspend or fail inside `prefetch(...)`. Tests that need to
     * observe the in-flight prefetch (cancellation, hung network) install this
     * to gate the suspend until they're ready to assert. Default keeps the fake
     * fast: `prefetch` returns immediately after recording the call.
     */
    var prefetchHook: (suspend (cat: Int, post: Int, page: Int) -> Unit)? = null

    override fun observeTopicPage(cat: Int, post: Int, page: Int): Flow<Topic> {
        calls += Triple(cat, post, page)
        return queue.removeFirstOrNull() ?: error("No more flows queued")
    }

    override suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic {
        refreshCalls += Triple(cat, post, page)
        refreshErrorToThrow?.let { throw it }
        return refreshQueue.removeFirstOrNull()
            ?: error("No more refresh topics queued (issue #200 post-submit force fetch path)")
    }

    override suspend fun prefetch(cat: Int, post: Int, page: Int) {
        prefetches += Triple(cat, post, page)
        prefetchHook?.invoke(cat, post, page)
    }
}

private class FakeStreamingTopicRepository(
    private val source: Flow<Topic>,
) : TopicRepository {
    override fun observeTopicPage(cat: Int, post: Int, page: Int): Flow<Topic> = source

    override suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic {
        error("refreshTopicPage not used by ViewModel under test")
    }

    override suspend fun prefetch(cat: Int, post: Int, page: Int) {
        // no-op for streaming tests
    }
}
