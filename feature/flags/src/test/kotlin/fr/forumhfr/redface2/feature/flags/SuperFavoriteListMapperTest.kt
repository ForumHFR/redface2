package fr.forumhfr.redface2.feature.flags

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteTopic
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.SubCategory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SuperFavoriteListMapperTest {

    @Test
    fun `group off resolves all favorites cache-only and keeps snapshot fallbacks`() = runTest {
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

        stubPreferences(preferences, groupByCategory = false)
        stubCachedCategories(forumRepository)
        every { flagRepository.observeCacheUpdates(any()) } returns emptyFlow<FlagType>()
        every { forumRepository.observeCachedSubcategories(23) } returns flowOf(
            ForumResult.Success(listOf(SubCategory(id = 550, name = "Android", parentCategoryId = 23))),
        )
        coEvery { flagRepository.findCachedFlag(cat = 23, topicId = 2) } returns liveExact
        coEvery { flagRepository.findCachedFlag(cat = 23, topicId = 4) } returns null
        coEvery { flagRepository.findCachedFlag(topicId = 3) } returns cachedLegacy
        coEvery { flagRepository.findCachedFlag(topicId = 99) } returns null

        val state = mapper.superFavoriteListState(flowOf(favorites)).first() as FlagsListUiState.Success

        val rows = (state.content as FlagsContent.Flat).rows
        assertEquals(listOf("Live exact", "Snapshot only", "Cached legacy", "Sujet #99"), rows.map { it.title })
        assertEquals(listOf(true, false, true, false), rows.map { it.flag.resolvedFromServer })
        assertEquals(List(rows.size) { MarkerStyle.PASTILLE }, rows.map { it.markerStyle })
        assertEquals("Android", rows[1].subcatName)
        coVerify(exactly = 0) { flagRepository.findFlag(any(), any()) }
        coVerify(exactly = 1) { flagRepository.findCachedFlag(cat = 23, topicId = 2) }
        coVerify(exactly = 1) { flagRepository.findCachedFlag(cat = 23, topicId = 4) }
        coVerify(exactly = 1) { flagRepository.findCachedFlag(topicId = 3) }
        coVerify(exactly = 1) { flagRepository.findCachedFlag(topicId = 99) }
        verify(exactly = 1) { forumRepository.observeCachedCategories() }
        verify(exactly = 0) { forumRepository.observeCategories() }
        coVerify(exactly = 0) { forumRepository.refreshCategories() }
        verify(exactly = 1) { forumRepository.observeCachedSubcategories(23) }
    }

    @Test
    fun `group on drops empty categories and returns one section per favorite category`() = runTest {
        val flagRepository = mockk<FlagRepository>()
        val forumRepository = mockk<ForumRepository>()
        val preferences = mockk<UserPreferencesRepository>()
        val mapper = SuperFavoriteListMapper(flagRepository, forumRepository, preferences)
        val favorites = setOf(
            SuperFavoriteTopic(cat = 23, topicId = 2, title = null, subcat = 550),
            SuperFavoriteTopic(cat = 23, topicId = 5, title = null, subcat = null),
            SuperFavoriteTopic(cat = 10, topicId = 3, title = null, subcat = null),
        )

        stubPreferences(preferences, groupByCategory = true)
        stubCachedCategories(
            forumRepository,
            23 to "Technologies Mobiles",
            10 to "Programmation",
            13 to "Discussions",
        )
        every { flagRepository.observeCacheUpdates(any()) } returns emptyFlow<FlagType>()
        every { forumRepository.observeCachedSubcategories(23) } returns flowOf(
            ForumResult.Success(listOf(SubCategory(id = 550, name = "Android", parentCategoryId = 23))),
        )
        coEvery { flagRepository.findCachedFlag(cat = 23, topicId = 2) } returns stubFlag(
            topicId = 2,
            type = FlagType.CYAN,
        ).copy(cat = 23, subcat = 550, title = "Mobile")
        coEvery { flagRepository.findCachedFlag(cat = 10, topicId = 3) } returns stubFlag(
            topicId = 3,
            type = FlagType.RED,
        ).copy(cat = 10, title = "Code")
        coEvery { flagRepository.findCachedFlag(cat = 23, topicId = 5) } returns stubFlag(
            topicId = 5,
            type = FlagType.FAVORITE,
        ).copy(cat = 23, title = "Wearable")

        val state = mapper.superFavoriteListState(flowOf(favorites)).first() as FlagsListUiState.Success

        val sections = (state.content as FlagsContent.Grouped).sections
        assertEquals("three favorites in two categories must render two sections", 2, sections.size)
        assertEquals(listOf(23, 10), sections.map { it.catId })
        assertEquals(listOf("Mobile", "Wearable"), sections.first { it.catId == 23 }.topics.map { it.title })
        assertEquals(listOf("Code"), sections.first { it.catId == 10 }.topics.map { it.title })
        assertEquals("Android", sections.first { it.catId == 23 }.topics.first().subcatName)
    }

    @Test
    fun `hide-read keeps resolved fully-read Super favorites visible`() = runTest {
        val flagRepository = mockk<FlagRepository>()
        val forumRepository = mockk<ForumRepository>()
        val preferences = mockk<UserPreferencesRepository>()
        val mapper = SuperFavoriteListMapper(flagRepository, forumRepository, preferences)
        val favorites = setOf(
            SuperFavoriteTopic(cat = 10, topicId = 3, title = null, subcat = null),
        )

        stubPreferences(preferences, groupByCategory = true, hideReadCategories = true)
        stubCachedCategories(forumRepository, 23 to "Technologies Mobiles", 10 to "Programmation")
        every { flagRepository.observeCacheUpdates(any()) } returns emptyFlow<FlagType>()
        coEvery { flagRepository.findCachedFlag(cat = 10, topicId = 3) } returns stubFlag(
            topicId = 3,
            type = FlagType.RED,
        ).copy(cat = 10, title = "Resolved read", hasUnread = false)

        val state = mapper.superFavoriteListState(flowOf(favorites)).first() as FlagsListUiState.Success

        val sections = (state.content as FlagsContent.Grouped).sections
        assertEquals(listOf(10), sections.map { it.catId })
        assertEquals(listOf("Resolved read"), sections.flatMap { section -> section.topics.map { it.title } })
    }

    @Test
    fun `cache update signal re-resolves the Super list without observing real buckets`() = runTest {
        val flagRepository = mockk<FlagRepository>()
        val forumRepository = mockk<ForumRepository>()
        val preferences = mockk<UserPreferencesRepository>()
        val mapper = SuperFavoriteListMapper(flagRepository, forumRepository, preferences)
        val favorites = setOf(SuperFavoriteTopic(cat = 23, topicId = 2, title = "Snapshot", subcat = null))
        val cacheUpdates = MutableSharedFlow<FlagType>(extraBufferCapacity = 1)
        var cachedFlag: Flag? = null

        stubPreferences(preferences, groupByCategory = false)
        stubCachedCategories(forumRepository)
        every { flagRepository.observeCacheUpdates(any()) } returns cacheUpdates
        coEvery { flagRepository.findCachedFlag(cat = 23, topicId = 2) } answers { cachedFlag }

        mapper.superFavoriteListState(flowOf(favorites)).test {
            val cold = awaitItem() as FlagsListUiState.Success
            assertEquals("Snapshot", (cold.content as FlagsContent.Flat).rows.single().title)

            cachedFlag = stubFlag(topicId = 2, type = FlagType.CYAN).copy(cat = 23, title = "Live")
            cacheUpdates.emit(FlagType.CYAN)

            val warmed = awaitItem() as FlagsListUiState.Success
            assertEquals("Live", (warmed.content as FlagsContent.Flat).rows.single().title)
            coVerify(exactly = 0) { flagRepository.findFlag(any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun stubPreferences(
        preferences: UserPreferencesRepository,
        groupByCategory: Boolean,
        hideReadCategories: Boolean = false,
        markerStyle: MarkerStyle = MarkerStyle.PASTILLE,
    ) {
        every { preferences.observeFlagsViewSettings(FlagType.CYAN) } returns flowOf(
            FlagsViewSettings(markerStyle = markerStyle),
        )
        every { preferences.observeFlagsGroupByCategory() } returns flowOf(groupByCategory)
        every { preferences.observeFlagsHideReadCategories() } returns flowOf(hideReadCategories)
    }

    private fun stubCachedCategories(
        forumRepository: ForumRepository,
        vararg categories: Pair<Int, String>,
    ) {
        every { forumRepository.observeCachedCategories() } returns flowOf(
            ForumResult.Success(
                categories.map { (id, name) ->
                    Category(id = id, name = name, forceSubcat = false, subcategoryCount = 0)
                },
            ),
        )
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
