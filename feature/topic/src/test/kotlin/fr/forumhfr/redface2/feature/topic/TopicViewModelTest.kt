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

    private fun topicRequest(page: Int, scrollTo: Int? = null): TopicRequest = TopicRequest(
        cat = SAMPLE_CAT,
        post = SAMPLE_POST,
        page = page,
        scrollTo = scrollTo,
    )

    private fun fakeTopic(
        page: Int,
        totalPages: Int,
        title: String = "fake",
        posts: List<Post> = emptyList(),
    ): Topic = Topic(
        cat = SAMPLE_CAT,
        post = SAMPLE_POST,
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
    }
}

private class FakeTopicRepository(
    flowsToReturn: List<Flow<Topic>>,
) : TopicRepository {
    private val queue = ArrayDeque(flowsToReturn)
    val calls: MutableList<Triple<Int, Int, Int>> = mutableListOf()
    val prefetches: MutableList<Triple<Int, Int, Int>> = mutableListOf()

    override fun observeTopicPage(cat: Int, post: Int, page: Int): Flow<Topic> {
        calls += Triple(cat, post, page)
        return queue.removeFirstOrNull() ?: error("No more flows queued")
    }

    override suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic {
        error("refreshTopicPage not used by ViewModel under test")
    }

    override suspend fun prefetch(cat: Int, post: Int, page: Int) {
        prefetches += Triple(cat, post, page)
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
