package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteTopic
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.SubCategory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SuperFavoriteListMapperTest {

    @Test
    fun `exact favorites resolve by category while legacy orphans use cache-only fallback`() = runTest {
        val flagRepository = mockk<FlagRepository>()
        val forumRepository = mockk<ForumRepository>()
        val preferences = mockk<UserPreferencesRepository>()
        val mapper = SuperFavoriteListMapper(flagRepository, forumRepository, preferences)
        val liveExact = stubFlag(topicId = 2, type = FlagType.CYAN).copy(
            cat = 23,
            title = "Live exact",
        )
        val cachedLegacy = stubFlag(topicId = 3, type = FlagType.RED).copy(
            cat = 10,
            title = "Cached legacy",
        )
        val favorites = setOf(
            SuperFavoriteTopic(cat = null, topicId = 99, title = null, subcat = null),
            SuperFavoriteTopic(cat = 23, topicId = 4, title = "Snapshot only", subcat = 550),
            SuperFavoriteTopic(cat = null, topicId = 3, title = null, subcat = null),
            SuperFavoriteTopic(cat = 23, topicId = 2, title = "Old title", subcat = null),
        )

        every { preferences.observeFlagsViewSettings(FlagType.CYAN) } returns flowOf(
            FlagsViewSettings(markerStyle = MarkerStyle.PASTILLE),
        )
        every { forumRepository.observeCachedSubcategories(23) } returns flowOf(
            ForumResult.Success(listOf(SubCategory(id = 550, name = "Android", parentCategoryId = 23))),
        )
        coEvery { flagRepository.findFlag(cat = 23, topicId = 2) } returns liveExact
        coEvery { flagRepository.findFlag(cat = 23, topicId = 4) } returns null
        coEvery { flagRepository.findCachedFlag(topicId = 3) } returns cachedLegacy
        coEvery { flagRepository.findCachedFlag(topicId = 99) } returns null

        val state = mapper.superFavoriteListState(flowOf(favorites)).first() as FlagsListUiState.Success

        val rows = (state.content as FlagsContent.Flat).rows
        assertEquals(listOf("Live exact", "Snapshot only", "Cached legacy", "Sujet #99"), rows.map { it.title })
        assertEquals(List(rows.size) { MarkerStyle.PASTILLE }, rows.map { it.markerStyle })
        assertEquals("Android", rows[1].subcatName)
        coVerify(exactly = 1) { flagRepository.findFlag(cat = 23, topicId = 2) }
        coVerify(exactly = 1) { flagRepository.findFlag(cat = 23, topicId = 4) }
        coVerify(exactly = 0) { flagRepository.findCachedFlag(topicId = 4) }
        coVerify(exactly = 1) { flagRepository.findCachedFlag(topicId = 3) }
        coVerify(exactly = 1) { flagRepository.findCachedFlag(topicId = 99) }
        verify(exactly = 1) { forumRepository.observeCachedSubcategories(23) }
    }

    private fun stubFlag(topicId: Int, type: FlagType): Flag = Flag(
        cat = 1,
        subcat = null,
        topicId = topicId,
        title = "Topic $topicId",
        totalPages = 1,
        replyCount = 0,
        type = type,
        hasUnread = true,
        lastReadPage = 1,
        lastPostReadId = null,
        firstPostAuthor = "",
        lastReplyAuthor = "",
        lastReplyAt = "",
    )
}
