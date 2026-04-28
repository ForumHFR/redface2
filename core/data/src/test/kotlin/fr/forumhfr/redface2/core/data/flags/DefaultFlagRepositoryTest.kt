package fr.forumhfr.redface2.core.data.flags

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.flags.FlagsListParser
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
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
class DefaultFlagRepositoryTest {

    @Test
    fun `observe emits Loading then Success on a happy fetch`() = runTest {
        val flags = listOf(stubFlag(topicId = 5))
        val (repo, _, _) = buildRepository(html = "<html/>", parsedFlags = flags)

        repo.observe(FlagType.RED).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            assertEquals(FlagsResult.Success(flags), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observe emits Failure when the network throws`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getFlagsPage(owntopic = 1) } throws IOException("offline")
        val (repo, _, _) = buildRepository(hfrClient = hfrClient)

        repo.observe(FlagType.RED).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            val result = awaitItem()
            assertTrue("expected Failure, got $result", result is FlagsResult.Failure)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh broadcasts a fresh result to active observers`() = runTest {
        val initialFlags = listOf(stubFlag(topicId = 5))
        val refreshedFlags = listOf(stubFlag(topicId = 5), stubFlag(topicId = 9))
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getFlagsPage(owntopic = 1) } returnsMany listOf("<html v=1/>", "<html v=2/>")
        val parser = mockk<FlagsListParser>()
        coEvery { parser.parse("<html v=1/>", FlagType.RED) } returns initialFlags
        coEvery { parser.parse("<html v=2/>", FlagType.RED) } returns refreshedFlags

        val (repo, _, _) = buildRepository(hfrClient = hfrClient, parser = parser)

        repo.observe(FlagType.RED).test {
            assertEquals(FlagsResult.Loading, awaitItem())
            assertEquals(FlagsResult.Success(initialFlags), awaitItem())

            repo.refresh(FlagType.RED)
            assertEquals(FlagsResult.Success(refreshedFlags), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `each FlagType maps to its own owntopic value`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getFlagsPage(owntopic = 1) } returns "<r/>"
        coEvery { hfrClient.getFlagsPage(owntopic = 2) } returns "<c/>"
        coEvery { hfrClient.getFlagsPage(owntopic = 3) } returns "<f/>"
        val parser = mockk<FlagsListParser>()
        coEvery { parser.parse("<r/>", FlagType.RED) } returns
            listOf(stubFlag(topicId = 1, type = FlagType.RED))
        coEvery { parser.parse("<c/>", FlagType.CYAN) } returns
            listOf(stubFlag(topicId = 2, type = FlagType.CYAN))
        coEvery { parser.parse("<f/>", FlagType.FAVORITE) } returns
            listOf(stubFlag(topicId = 3, type = FlagType.FAVORITE))

        val (repo, _, _) = buildRepository(hfrClient = hfrClient, parser = parser)

        listOf(FlagType.RED to 1, FlagType.CYAN to 2, FlagType.FAVORITE to 3).forEach { (type, topicId) ->
            repo.observe(type).test {
                awaitItem() // Loading
                val success = awaitItem() as FlagsResult.Success
                assertEquals(topicId, success.flags.single().topicId)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    private fun stubFlag(topicId: Int, type: FlagType = FlagType.RED): Flag = Flag(
        cat = 1,
        subcat = 1,
        topicId = topicId,
        title = "Topic $topicId",
        totalPages = 1,
        replyCount = 0,
        views = 0,
        type = type,
        hasUnread = true,
        lastReadPage = 1,
        firstUnreadPostId = 0L,
        firstPostAuthor = "",
        lastReplyAuthor = "",
        lastReplyAt = "",
    )

    private fun buildRepository(
        html: String = "<html/>",
        parsedFlags: List<Flag> = emptyList(),
        hfrClient: HfrClient = mockk<HfrClient>().also {
            coEvery { it.getFlagsPage(owntopic = any()) } returns html
        },
        parser: FlagsListParser = mockk<FlagsListParser>().also {
            coEvery { it.parse(any(), any()) } returns parsedFlags
        },
    ): Triple<DefaultFlagRepository, HfrClient, FlagsListParser> {
        val repo = DefaultFlagRepository(
            hfrClient = hfrClient,
            parser = parser,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        return Triple(repo, hfrClient, parser)
    }
}
