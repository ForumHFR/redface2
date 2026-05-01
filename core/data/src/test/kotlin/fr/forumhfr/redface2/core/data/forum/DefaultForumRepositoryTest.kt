package fr.forumhfr.redface2.core.data.forum

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.model.ForumIndex
import fr.forumhfr.redface2.core.model.TopicListPage
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.forum.ForumCategoriesParser
import fr.forumhfr.redface2.core.parser.forum.TopicListParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DefaultForumRepositoryTest {

    @Test
    fun `getForumIndex success delegates to client and parser`() = runTest {
        val client = mockk<HfrClient>()
        val parser = mockk<ForumCategoriesParser>()
        val expected = ForumIndex(categories = emptyList())
        coEvery { client.getForumHomePage(useAuth = true) } returns ROOT_HTML
        every { parser.parse(ROOT_HTML) } returns expected

        val repo = buildRepository(client = client, categoriesParser = parser)

        val result = repo.getForumIndex()

        assertTrue("expected success", result.isSuccess)
        assertEquals(expected, result.getOrNull())
        coVerify(exactly = 1) { client.getForumHomePage(useAuth = true) }
    }

    @Test
    fun `getForumIndex propagates SessionExpiredException as Result failure`() = runTest {
        val client = mockk<HfrClient>()
        coEvery { client.getForumHomePage(useAuth = true) } throws
            SessionExpiredException("https://forum.hardware.fr/login.php")

        val repo = buildRepository(client = client)

        val result = repo.getForumIndex()

        assertTrue("expected failure", result.isFailure)
        assertTrue(
            "SessionExpiredException must be carried verbatim so the caller can render the reconnect CTA",
            result.exceptionOrNull() is SessionExpiredException,
        )
    }

    @Test
    fun `getTopicList success delegates to client and parser`() = runTest {
        val client = mockk<HfrClient>()
        val parser = mockk<TopicListParser>()
        val expected = TopicListPage(cat = 13, subcat = null, currentPage = 1, totalPages = 1, topics = emptyList())
        coEvery { client.getTopicListPage(cat = 13, subcat = 0, page = 1, useAuth = true) } returns LIST_HTML
        every { parser.parse(LIST_HTML) } returns expected

        val repo = buildRepository(client = client, topicListParser = parser)

        val result = repo.getTopicList(cat = 13, subcat = 0, page = 1)

        assertTrue("expected success", result.isSuccess)
        assertEquals(expected, result.getOrNull())
    }

    @Test
    fun `getTopicList wraps the require(cat=0) precondition into Result failure`() = runTest {
        // Wire a strict mock that reproduces the real client's `require(cat > 0)` guard:
        // a relaxed mock would silently return "" and skip the precondition entirely,
        // hiding the contract under test.
        val client = mockk<HfrClient>()
        coEvery { client.getTopicListPage(cat = 0, subcat = 0, page = 1, useAuth = true) } throws
            IllegalArgumentException("cat must be > 0, got 0")
        val repo = buildRepository(client = client)

        // Phase 1C-A guards: a non-positive cat is a programming bug — bubbling the
        // IllegalArgumentException through `Result.failure` keeps the surface uniform
        // (the ViewModel branches on Result regardless of cause type) without crashing
        // the user's session.
        val result = repo.getTopicList(cat = 0, subcat = 0, page = 1)

        assertTrue("expected failure for cat=0", result.isFailure)
        assertTrue(
            "expected IllegalArgumentException, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is IllegalArgumentException,
        )
    }

    private fun buildRepository(
        client: HfrClient = mockk(relaxed = true),
        categoriesParser: ForumCategoriesParser = mockk(relaxed = true),
        topicListParser: TopicListParser = mockk(relaxed = true),
    ): DefaultForumRepository = DefaultForumRepository(
        client = client,
        categoriesParser = categoriesParser,
        topicListParser = topicListParser,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private companion object {
        const val ROOT_HTML = "<html><body>root</body></html>"
        const val LIST_HTML = "<html><body>list</body></html>"
    }
}
