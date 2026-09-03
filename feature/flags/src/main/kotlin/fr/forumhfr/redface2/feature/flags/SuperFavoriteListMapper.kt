package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteTopic
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart

/**
 * Builds the local Super tab list (#737). It never observes the flag buckets directly and has no
 * refresh endpoint of its own; every stored topic only scans warm caches through
 * [FlagRepository.findCachedFlag]. Missing rows fall back to their local snapshot so opening the tab
 * never fans out REST requests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SuperFavoriteListMapper @Inject constructor(
    private val flagRepository: FlagRepository,
    private val forumRepository: ForumRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) {

    fun superFavoriteListState(superFavoriteTopics: Flow<Set<SuperFavoriteTopic>>): Flow<FlagsListUiState?> {
        val layoutFlow = combine(
            userPreferencesRepository.observeFlagsViewSettings(FlagType.CYAN),
            userPreferencesRepository.observeFlagsGroupByCategory(),
            userPreferencesRepository.observeFlagsHideReadCategories(),
        ) { resolved, groupByCategory, hideReadCategories ->
            SuperFavoriteLayout(
                groupByCategory = groupByCategory,
                hideReadCategories = hideReadCategories,
                markerStyle = resolved.markerStyle,
            )
        }
            .distinctUntilChanged()

        val cacheUpdates = flagRepository.observeCacheUpdates(SUPER_FAVORITE_BACKING_TYPES.toSet())
            .map { Unit }
            .onStart { emit(Unit) }
        val categories = forumRepository.observeCachedCategories()
            .onStart { emit(null) }

        val resolvedFavorites = combine(
            superFavoriteTopics,
            layoutFlow,
            cacheUpdates,
        ) { favorites, layout, _ -> favorites to layout }
            .mapLatest { (favorites, layout) ->
                ResolvedSuperFavorites(resolveSuperFavoriteFlags(favorites), layout)
            }
            .flatMapLatest { (flags, layout) ->
                forumRepository.subcategoryNamesForFlags(flags).map { names ->
                    ResolvedSuperFavoriteRows(flags, layout, names)
                }
            }

        return combine(resolvedFavorites, categories) { rows, categoriesResult ->
            FlagsListUiState.Success(
                superFavoriteContent(
                    rows = rows,
                    categoriesResult = categoriesResult,
                ),
            )
        }.distinctUntilChanged()
    }

    private suspend fun resolveSuperFavoriteFlags(favorites: Set<SuperFavoriteTopic>): List<Flag> =
        favorites.dedupedForDisplay().map { favorite ->
            resolveSuperFavoriteFlag(favorite) ?: favorite.toFallbackFlag()
        }

    private suspend fun resolveSuperFavoriteFlag(favorite: SuperFavoriteTopic): Flag? {
        // Local copy : `cat` is a public property of another module, so no smart cast across it.
        val cat = favorite.cat
        return if (cat != null) {
            flagRepository.findCachedFlag(cat = cat, topicId = favorite.topicId)
        } else {
            flagRepository.findCachedFlag(topicId = favorite.topicId)
        }
    }

    private fun Set<SuperFavoriteTopic>.dedupedForDisplay(): List<SuperFavoriteTopic> {
        val exactTopicIds = asSequence()
            .filter { it.cat != null }
            .mapTo(HashSet()) { it.topicId }
        return asSequence()
            .filterNot { it.cat == null && it.topicId in exactTopicIds }
            .sortedWith(compareBy<SuperFavoriteTopic> { it.cat ?: Int.MAX_VALUE }.thenBy { it.topicId })
            .toList()
    }

    private fun SuperFavoriteTopic.toFallbackFlag(): Flag = Flag(
        cat = cat ?: ORPHAN_SUPER_FAVORITE_CAT,
        subcat = subcat,
        topicId = topicId,
        title = title ?: "Sujet #$topicId",
        totalPages = 1,
        replyCount = 0,
        type = FlagType.FAVORITE,
        isFavorite = true,
        hasUnread = false,
        lastReadPage = 1,
        lastPostReadId = null,
        firstPostAuthor = "",
        lastReplyAuthor = "",
        lastReplyAt = "",
    )

    private fun superFavoriteContent(
        rows: ResolvedSuperFavoriteRows,
        categoriesResult: ForumResult<List<Category>>?,
    ): FlagsContent {
        val flagRows = toFlagRows(
            flags = rows.flags,
            markerStyle = rows.layout.markerStyle,
            subcategoryNames = rows.subcategoryNames,
        )
        if (!rows.layout.groupByCategory) return FlagsContent.Flat(flagRows)

        val grouped = groupFlagRowsByCategory(
            rows = flagRows,
            orderedCategories = resolveCategoryOrder(categoriesResult),
        ).filter { it.topics.isNotEmpty() }
        val sections = if (rows.layout.hideReadCategories) {
            filterCategoriesWithUnread(grouped, keepFullyRead = true)
        } else {
            grouped
        }
        return FlagsContent.Grouped(sections)
    }

    private fun resolveCategoryOrder(categoriesResult: ForumResult<List<Category>>?): List<FlagCategoryOrderEntry> =
        when (categoriesResult) {
            is ForumResult.Success -> categoriesResult.value
                .takeIf { it.isNotEmpty() }
                ?.map { FlagCategoryOrderEntry(it.id, it.name) }
                ?: FALLBACK_CATEGORY_ORDER
            else -> FALLBACK_CATEGORY_ORDER
        }

    private data class SuperFavoriteLayout(
        val groupByCategory: Boolean,
        val hideReadCategories: Boolean,
        val markerStyle: MarkerStyle,
    )

    private data class ResolvedSuperFavorites(
        val flags: List<Flag>,
        val layout: SuperFavoriteLayout,
    )

    private data class ResolvedSuperFavoriteRows(
        val flags: List<Flag>,
        val layout: SuperFavoriteLayout,
        val subcategoryNames: Map<SubcategoryKey, String>,
    )

}

private const val ORPHAN_SUPER_FAVORITE_CAT = 0

internal val SUPER_FAVORITE_BACKING_TYPES: List<FlagType> = listOf(
    FlagType.CYAN,
    FlagType.RED,
    FlagType.FAVORITE,
)
