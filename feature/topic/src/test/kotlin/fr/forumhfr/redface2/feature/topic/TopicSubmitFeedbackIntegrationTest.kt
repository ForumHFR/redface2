package fr.forumhfr.redface2.feature.topic

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.topic.TopicPageEmission
import fr.forumhfr.redface2.core.domain.topic.TopicRepository
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopicSubmitFeedbackIntegrationTest {
    private val testClock = TestCoroutineScheduler()
    private val mainDispatcher = UnconfinedTestDispatcher(testClock)
    @get:Rule val compose = createComposeRule(mainDispatcher)
    private val models = mutableListOf<TopicViewModel>()

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
    fun `activity recreation during the real submitted-post jump never replays confirmation`() {
        val restoration = StateRestorationTester(compose)
        val host = SnackbarHostState()
        val repository = FeedbackTopicRepository()
        val jump = CompletableDeferred<Topic>()
        repository.responses.add(CompletableDeferred(feedbackTopic(2, 5)))
        repository.responses.add(jump)
        val model = submitFeedbackTestViewModel(repository).also { models += it }
        val presentations = mutableListOf<SnackbarVisuals>()
        restoration.setContent { FeedbackHost(model, host, presentations, renderHost = false) }
        compose.runOnIdle { model.applySubmitResult(2, null) }
        compose.waitUntil {
            model.submitAcknowledgements.feedback.value?.expiresAtMillis != null && presentations.size == 1
        }
        assertEquals(SnackbarDuration.Long, host.currentSnackbarData?.visuals?.duration)
        compose.runOnIdle { host.currentSnackbarData!!.performAction() }
        compose.waitUntil { model.state.value.refreshKind == TopicRefreshKind.PostSubmitJump }
        assertEquals(listOf(2, 5), repository.refreshes)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertNull(host.currentSnackbarData)
        compose.runOnIdle { jump.complete(feedbackTopic(5, 5)) }
        compose.mainClock.advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS + 1)
        compose.waitForIdle()
        assertEquals(TopicRefreshKind.None, model.state.value.refreshKind)
        assertEquals(5, model.state.value.request.page)
        assertNull(host.currentSnackbarData)
        assertEquals(1, presentations.size)
        compose.runOnIdle {
            repository.responses.add(CompletableDeferred(feedbackTopic(5, 5)))
            model.applySubmitResult(5, 777, operation = PostSubmitOperation.Edit)
        }
        compose.waitUntil { presentations.size == 2 }
        assertEquals("Message modifié", host.currentSnackbarData?.visuals?.message)
        assertEquals(SnackbarDuration.Short, host.currentSnackbarData?.visuals?.duration)
        assertEquals(listOf(2, 5, 5), repository.refreshes)
    }

    @Test
    fun `network completion after presentation leaves the same snackbar until its Material timeout`() {
        compose.mainClock.autoAdvance = false
        val host = SnackbarHostState()
        val repository = FeedbackTopicRepository()
        val refresh = CompletableDeferred<Topic>()
        repository.responses.add(refresh)
        val model = submitFeedbackTestViewModel(repository).also { models += it }
        val presentations = mutableListOf<SnackbarVisuals>()
        compose.setContent { FeedbackHost(model, host, presentations) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            model.applySubmitResult(2, 777, operation = PostSubmitOperation.Edit)
        }
        compose.mainClock.advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil { host.currentSnackbarData != null }
        val displayed = host.currentSnackbarData!!
        assertEquals("Message modifié", displayed.visuals.message)
        assertEquals(SnackbarDuration.Short, displayed.visuals.duration)
        assertEquals(TopicRefreshKind.PostSubmit, model.state.value.refreshKind)
        compose.mainClock.advanceTimeBy(3_000)
        compose.runOnIdle { refresh.complete(feedbackTopic(2, 2)) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(TopicRefreshKind.None, model.state.value.refreshKind)
        assertSame(displayed, host.currentSnackbarData)
        assertEquals(1, presentations.size)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitUntil { host.currentSnackbarData == null }
        assertEquals(1, presentations.size)
        assertEquals(listOf(2), repository.refreshes)
    }

    @Test
    fun `failure after the wait bound replaces a success queued behind an occupied host`() {
        val host = SnackbarHostState()
        // Hold the Material mutex until explicitly resumed, even if somebody dismisses its data.
        val blockerClock = TestCoroutineScheduler()
        val blocker = CoroutineScope(StandardTestDispatcher(blockerClock)).launch {
            host.showSnackbar("Busy", duration = SnackbarDuration.Indefinite)
        }
        blockerClock.runCurrent()
        val repository = FeedbackTopicRepository()
        val refresh = CompletableDeferred<Topic>()
        repository.responses.add(refresh)
        val model = submitFeedbackTestViewModel(repository).also { models += it }
        val presentations = mutableListOf<SnackbarVisuals>()
        compose.setContent { FeedbackHost(model, host, presentations, renderHost = false) }
        compose.runOnIdle {
            model.applySubmitResult(2, 777, operation = PostSubmitOperation.Edit)
        }
        compose.mainClock.advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        compose.waitForIdle()
        assertEquals("Busy", host.currentSnackbarData?.visuals?.message)
        compose.runOnIdle { refresh.completeExceptionally(IOException("refresh failed")) }
        compose.waitForIdle()
        assertEquals("Busy", host.currentSnackbarData?.visuals?.message)
        compose.runOnIdle {
            host.currentSnackbarData!!.dismiss()
            blockerClock.runCurrent()
        }
        compose.waitUntil {
            host.currentSnackbarData?.visuals?.message?.startsWith("Message modifié,") == true
        }
        assertEquals(
            "Message modifié, mais actualisation impossible. Tirez pour réessayer.",
            host.currentSnackbarData?.visuals?.message,
        )
        assertEquals(TopicRefreshKind.None, model.state.value.refreshKind)
        compose.waitUntil { presentations.count { it.message != "Busy" } == 1 }
        compose.runOnIdle { host.currentSnackbarData!!.dismiss() }
        compose.waitForIdle()
        assertNull(host.currentSnackbarData)
        assertNull(model.submitAcknowledgements.feedback.value)
        assertEquals(1, presentations.count { it.message != "Busy" })
        blocker.cancel()
    }
}

@Composable
private fun FeedbackHost(
    model: TopicViewModel,
    host: SnackbarHostState,
    presentations: MutableList<SnackbarVisuals>,
    renderHost: Boolean = true,
) {
    LaunchedEffect(host) {
        snapshotFlow { host.currentSnackbarData }.filterNotNull().collect { presentations += it.visuals }
    }
    RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
        if (renderHost) SnackbarHost(host)
        TopicSubmitFeedbackEffect(model.submitAcknowledgements, host, active = true, account = "xaat") {
            model.openSubmittedPostPage(it)
        }
    }
}

private class FeedbackTopicRepository : TopicRepository {
    val responses = ArrayDeque<CompletableDeferred<Topic>>()
    val refreshes = mutableListOf<Int>()

    override fun observeTopicPage(cat: Int, post: Int, page: Int, forceRefresh: Boolean) =
        flowOf(TopicPageEmission(feedbackTopic(page, 5), provisional = false))

    override suspend fun refreshTopicPage(cat: Int, post: Int, page: Int): Topic {
        refreshes += page
        return responses.removeFirst().await()
    }

    override suspend fun getCitingPosts(cat: Int, post: Int, numreponse: Int): Result<List<Post>> =
        Result.success(emptyList())

    override suspend fun prefetch(cat: Int, post: Int, page: Int) = Unit
}

private fun feedbackTopic(page: Int, totalPages: Int) = Topic(
    cat = 13, post = 84_540, subcat = 432, title = "Topic", posts = emptyList(),
    page = page, totalPages = totalPages, isFirstPostOwner = false, canReply = true,
)
