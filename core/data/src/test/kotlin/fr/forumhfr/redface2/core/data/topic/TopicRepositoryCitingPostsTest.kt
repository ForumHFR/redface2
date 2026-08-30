package fr.forumhfr.redface2.core.data.topic

import fr.forumhfr.redface2.core.database.dao.TopicDao
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicRepositoryCitingPostsTest {

    @Test
    fun `getCitingPosts runs fetch and parse on io and returns the parsed rows`() {
        val ioDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "citing-posts-io")
        }.asCoroutineDispatcher()
        try {
            val client = mockk<HfrClient>()
            val parser = mockk<HfrParser>()
            val post = fakePost()
            coEvery { client.getCitingPosts(13, 95_092, 23_786_379) } coAnswers {
                assertTrue(Thread.currentThread().name.startsWith("citing-posts-io"))
                "<html>quote-only</html>"
            }
            every { parser.parseCitingPosts("<html>quote-only</html>") } answers {
                assertTrue(Thread.currentThread().name.startsWith("citing-posts-io"))
                listOf(post)
            }
            val repository = repository(client, parser, ioDispatcher)

            runTest {
                val result = repository.getCitingPosts(13, 95_092, 23_786_379)

                assertEquals(listOf(post), result.getOrThrow())
                coVerify(exactly = 1) { client.getCitingPosts(13, 95_092, 23_786_379) }
            }
        } finally {
            ioDispatcher.close()
        }
    }

    @Test
    fun `getCitingPosts deduplicates rows that repeat a numreponse`() = runTest {
        val client = mockk<HfrClient>()
        val parser = mockk<HfrParser>()
        val first = fakePost(numreponse = 23_786_634, author = "Premier")
        val duplicate = fakePost(numreponse = 23_786_634, author = "Doublon")
        val other = fakePost(numreponse = 74_328_265, author = "Autre")
        coEvery { client.getCitingPosts(any(), any(), any()) } coAnswers { "<html>quote-only</html>" }
        every { parser.parseCitingPosts(any()) } answers { listOf(first, duplicate, other) }

        val result = repository(client, parser).getCitingPosts(13, 95_092, 23_786_379)

        // #783 R2 — distinctBy keeps the first occurrence of each numreponse; the duplicate is dropped.
        assertEquals(listOf(first, other), result.getOrThrow())
    }

    @Test
    fun `getCitingPosts preserves typed server failures in Result`() = runTest {
        val client = mockk<HfrClient>()
        val parser = mockk<HfrParser>()
        val failure = HfrServerException(503, "https://forum.hardware.fr/forum2.php")
        coEvery { client.getCitingPosts(any(), any(), any()) } throws failure

        val result = repository(client, parser).getCitingPosts(13, 95_092, 23_786_379)

        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun `getCitingPosts rethrows cancellation instead of wrapping it`() = runTest {
        val client = mockk<HfrClient>()
        val parser = mockk<HfrParser>()
        val cancellation = CancellationException("screen left")
        coEvery { client.getCitingPosts(any(), any(), any()) } throws cancellation

        var caught: Throwable? = null
        try {
            repository(client, parser).getCitingPosts(13, 95_092, 23_786_379)
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            caught = throwable
        }

        assertSame(cancellation, caught)
    }

    private fun repository(
        client: HfrClient,
        parser: HfrParser,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    ): TopicRepositoryImpl = TopicRepositoryImpl(
        client = client,
        parser = parser,
        topicDao = mockk<TopicDao>(relaxed = true),
        clock = Clock.systemUTC(),
        userPreferencesRepository = mockk<UserPreferencesRepository>(relaxed = true),
        ioDispatcher = ioDispatcher,
    )

    private fun fakePost(
        numreponse: Int = 23_786_634,
        author: String = "Citeur",
    ): Post = Post(
        numreponse = numreponse,
        author = author,
        date = Instant.parse("2026-08-30T12:00:00Z"),
        content = PostContent(emptyList()),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
    )
}
