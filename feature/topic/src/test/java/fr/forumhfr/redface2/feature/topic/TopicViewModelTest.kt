package fr.forumhfr.redface2.feature.topic

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.fixtures.FixedTopicFixtures
import fr.forumhfr.redface2.core.domain.fixtures.TopicFixtureRepository
import fr.forumhfr.redface2.core.model.Topic
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun `initial load succeeds and exposes loaded state`() = runTest {
        val topic = fakeTopic(page = 1)
        val repository = FakeTopicFixtureRepository(responses = listOf(Result.success(topic)))

        val viewModel = TopicViewModel(
            request = fixedRequest(page = 1),
            topicFixtureRepository = repository,
        )

        viewModel.state.test {
            val loaded = awaitItem()
            val mode = assertMode<TopicUiState.Mode.Loaded>(loaded)
            assertEquals(topic, mode.topic)
            assertEquals(FixedTopicFixtures.availablePages, loaded.availablePages)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(1), repository.pagesRequested)
    }

    @Test
    fun `initial load failure exposes error mode`() = runTest {
        val repository = FakeTopicFixtureRepository(responses = listOf(Result.failure(IOException("network"))))

        val viewModel = TopicViewModel(
            request = fixedRequest(page = 1),
            topicFixtureRepository = repository,
        )

        viewModel.state.test {
            val state = awaitItem()
            val mode = assertMode<TopicUiState.Mode.Error>(state)
            assertEquals("network", mode.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `non-fixed topic request resolves to placeholder without hitting repository`() = runTest {
        val repository = FakeTopicFixtureRepository(responses = emptyList())

        val viewModel = TopicViewModel(
            request = TopicRequest(cat = 1, post = 2, page = 1, scrollTo = null),
            topicFixtureRepository = repository,
        )

        viewModel.state.test {
            val state = awaitItem()
            assertMode<TopicUiState.Mode.Placeholder>(state)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(repository.pagesRequested.isEmpty())
    }

    @Test
    fun `retry after error replays the current page and succeeds`() = runTest {
        val topic = fakeTopic(page = 2)
        val repository = FakeTopicFixtureRepository(
            responses = listOf(
                Result.failure(IllegalStateException("boom")),
                Result.success(topic),
            ),
        )

        val viewModel = TopicViewModel(
            request = fixedRequest(page = 2),
            topicFixtureRepository = repository,
        )

        assertMode<TopicUiState.Mode.Error>(viewModel.state.value)

        viewModel.send(TopicIntent.Retry)

        val loadedMode = assertMode<TopicUiState.Mode.Loaded>(viewModel.state.value)
        assertEquals(topic, loadedMode.topic)
        assertEquals(listOf(2, 2), repository.pagesRequested)
    }

    private fun fixedRequest(page: Int): TopicRequest = TopicRequest(
        cat = FixedTopicFixtures.cat,
        post = FixedTopicFixtures.post,
        page = page,
        scrollTo = null,
    )

    private fun fakeTopic(page: Int): Topic = Topic(
        cat = FixedTopicFixtures.cat,
        post = FixedTopicFixtures.post,
        title = "fake",
        posts = emptyList(),
        page = page,
        totalPages = 3,
        isFirstPostOwner = false,
        poll = null,
    )

    private inline fun <reified T : TopicUiState.Mode> assertMode(state: TopicUiState): T {
        val mode = state.mode
        assertTrue("expected mode ${T::class.simpleName} but was ${mode::class.simpleName}", mode is T)
        return mode as T
    }
}

private class FakeTopicFixtureRepository(
    responses: List<Result<Topic>>,
) : TopicFixtureRepository {
    private val queue = ArrayDeque(responses)
    val pagesRequested: MutableList<Int> = mutableListOf()

    override suspend fun loadTopicPage(page: Int): Topic {
        pagesRequested += page
        val result = queue.removeFirstOrNull()
            ?: error("No more fake responses queued")
        return result.getOrThrow()
    }
}
