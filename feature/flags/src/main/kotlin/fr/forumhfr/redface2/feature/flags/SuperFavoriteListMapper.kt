package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteTopic
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
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

/**
 * Builds the local Super tab list (#737). It never observes the flag buckets directly and has no
 * refresh endpoint; exact `(cat, topicId)` snapshots are enriched through [FlagRepository.findFlag],
 * while legacy topic-id-only orphans only scan warm caches through [FlagRepository.findCachedFlag].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SuperFavoriteListMapper @Inject constructor(
    private val flagRepository: FlagRepository,
    private val forumRepository: ForumRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) {

    fun superFavoriteListState(superFavoriteTopics: Flow<Set<SuperFavoriteTopic>>): Flow<FlagsListUiState?> {
        val markerStyleFlow = userPreferencesRepository.observeFlagsViewSettings(FlagType.CYAN)
            .map { it.markerStyle }
            .distinctUntilChanged()
        return combine(superFavoriteTopics, markerStyleFlow) { favorites, markerStyle -> favorites to markerStyle }
            .mapLatest { (favorites, markerStyle) ->
                resolveSuperFavoriteFlags(favorites) to markerStyle
            }
            .flatMapLatest { (flags, markerStyle) ->
                forumRepository.subcategoryNamesForFlags(flags).map { names ->
                    FlagsListUiState.Success(
                        FlagsContent.Flat(toFlagRows(flags, markerStyle, names)),
                    )
                }
            }
    }

    private suspend fun resolveSuperFavoriteFlags(favorites: Set<SuperFavoriteTopic>): List<Flag> =
        favorites.dedupedForDisplay().map { favorite ->
            resolveSuperFavoriteFlag(favorite) ?: favorite.toFallbackFlag()
        }

    private suspend fun resolveSuperFavoriteFlag(favorite: SuperFavoriteTopic): Flag? {
        // Local copy : `cat` is a public property of another module, so no smart cast across it.
        val cat = favorite.cat
        return if (cat != null) {
            flagRepository.findFlag(cat = cat, topicId = favorite.topicId)
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

}

private const val ORPHAN_SUPER_FAVORITE_CAT = 0
