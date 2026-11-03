package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.topic.TopicPageEmission
import fr.forumhfr.redface2.core.domain.topic.TopicRepository
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.Topic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1300 — lifecycle regression around the real composition-local [TopicListAlignment] gate. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class TopicLandingAlignmentComposeTest {

    private val mainDispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())
    private val models = mutableListOf<TopicViewModel>()

    @get:Rule
    val compose = createComposeRule(mainDispatcher)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun same_page_missing_post_reopens_persistence() {
        val repository = MissingPostLandingRepository()
        val model = submitFeedbackTestViewModel(repository).also { models += it }
        val resolutions = mutableListOf<TopicEffect>()
        lateinit var listState: LazyListState
        lateinit var alignment: TopicListAlignment
        compose.setContent {
            val state by model.state.collectAsState()
            listState = rememberLazyListState(
                initialFirstVisibleItemIndex = 7,
                initialFirstVisibleItemScrollOffset = 19,
            )
            alignment = remember { TopicListAlignment().apply { onLandingApplied(page = 2) } }
            TopicLandingAlignmentEffect(
                landing = state.landing,
                canonicalPage = state.request.page,
                isLoaded = state.mode is TopicUiState.Mode.Loaded,
                alignment = alignment,
            )
            LaunchedEffect(model) {
                model.effects.collect { effect ->
                    if (effect is TopicEffect.LandingResolvedWithoutScroll &&
                        model.state.value.request.page == effect.page
                    ) {
                        resolutions += effect
                        model.onLandingApplied(effect)
                    }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.height(240.dp)) {
                items((0..20).toList()) {
                    Box(Modifier.fillMaxWidth().height(48.dp))
                }
            }
        }
        compose.waitForIdle()
        assertTrue(alignment.shouldPersist(canonicalPage = 2, isLoaded = true))

        compose.runOnIdle { model.goToPost(targetPage = 2, numreponse = 777) }
        compose.waitForIdle()
        assertFalse(alignment.shouldPersist(canonicalPage = 2, isLoaded = true))
        assertTrue(model.state.value.landing is TopicUiState.Landing.Pending)
        assertEquals(listOf(false, true), repository.forceRefreshCalls)

        compose.runOnIdle { repository.reload.complete(Unit) }
        compose.waitForIdle()
        assertEquals(listOf(TopicEffect.LandingResolvedWithoutScroll(page = 2)), resolutions)
        assertTrue(model.state.value.landing is TopicUiState.Landing.Applied)
        assertTrue(alignment.shouldPersist(canonicalPage = 2, isLoaded = true))
        val departure = TopicScrollAnchor(
            index = listState.firstVisibleItemIndex,
            offset = listState.firstVisibleItemScrollOffset,
        ).takeIf { alignment.shouldPersist(canonicalPage = 2, isLoaded = true) }
        assertEquals(TopicScrollAnchor(index = 7, offset = 19), departure)
        assertEquals(listOf(false, true), repository.forceRefreshCalls)
    }

    @Test
    fun `a no-scroll resolution stays persistable after the alignment object is recreated`() {
        val mounted = mutableStateOf(true)
        val landing = mutableStateOf<TopicUiState.Landing>(
            TopicUiState.Landing.Pending(id = 12, page = 2),
        )
        lateinit var listState: LazyListState
        lateinit var alignment: TopicListAlignment

        compose.setContent {
            listState = rememberLazyListState(
                initialFirstVisibleItemIndex = 7,
                initialFirstVisibleItemScrollOffset = 19,
            )
            if (mounted.value) {
                alignment = remember { TopicListAlignment() }
                TopicLandingAlignmentEffect(
                    landing = landing.value,
                    canonicalPage = 2,
                    isLoaded = true,
                    alignment = alignment,
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.height(240.dp),
                ) {
                    items((0..20).toList()) {
                        Box(Modifier.fillMaxWidth().height(48.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertFalse(alignment.shouldPersist(canonicalPage = 2, isLoaded = true))

        // LandingResolvedWithoutScroll performs no LazyListState movement; its acknowledgement
        // alone opens persistence on the coordinates that are already visible.
        compose.runOnIdle {
            landing.value = TopicUiState.Landing.Applied(id = 12, page = 2)
        }
        compose.waitForIdle()
        assertTrue(alignment.shouldPersist(canonicalPage = 2, isLoaded = true))
        val firstAlignment = alignment

        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = true }
        compose.waitForIdle()

        assertNotSame(firstAlignment, alignment)
        val swipeDeparture = TopicScrollAnchor(
            index = listState.firstVisibleItemIndex,
            offset = listState.firstVisibleItemScrollOffset,
        ).takeIf {
            alignment.shouldPersist(canonicalPage = 2, isLoaded = true)
        }
        assertEquals(TopicScrollAnchor(index = 7, offset = 19), swipeDeparture)
    }
}

private class MissingPostLandingRepository : TopicRepository {
    val reload = CompletableDeferred<Unit>()
    val forceRefreshCalls = mutableListOf<Boolean>()

    override fun observeTopicPage(cat: Int, post: Int, page: Int, forceRefresh: Boolean) = flow {
        forceRefreshCalls += forceRefresh
        val cached = fakeTopic(page = page, totalPages = 2)
        if (forceRefresh) {
            emit(TopicPageEmission(cached, provisional = true))
            reload.await()
        }
        emit(TopicPageEmission(cached, provisional = false))
    }

    override suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic =
        error("The missing-post landing uses the cache-aside load")

    override suspend fun getCitingPosts(cat: Int, post: Int, numreponse: Int): Result<List<Post>> =
        Result.success(emptyList())

    override suspend fun prefetch(cat: Int, post: Int, page: Int) = Unit
}
