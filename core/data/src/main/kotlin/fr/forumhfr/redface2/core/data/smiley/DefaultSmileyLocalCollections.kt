package fr.forumhfr.redface2.core.data.smiley

import fr.forumhfr.redface2.core.data.preferences.normalizeLocalAccountOwner
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollections
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageRepository
import fr.forumhfr.redface2.core.model.AuthState
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class DefaultSmileyLocalCollections @Inject constructor(
    private val authRepository: AuthRepository,
    private val favorites: LocalFavoriteSmileyRepository,
    private val usage: SmileyUsageRepository,
) : SmileyLocalCollections {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<SmileyLocalCollectionSnapshot> = authRepository.observeAuthState()
        .map { state ->
            (state as? AuthState.Authenticated)?.pseudo?.takeUnless { it.isBlank() }
                ?.let(::normalizeLocalAccountOwner)
        }
        .distinctUntilChanged()
        .flatMapLatest { owner ->
            flow {
                // Never expose the previous account's rows while the new stores are loading.
                emit(SmileyLocalCollectionSnapshot(owner))
                if (owner != null) {
                    emitAll(combine(favorites.observeFavorites(owner), usage.observeTop(owner)) { local, top ->
                        SmileyLocalCollectionSnapshot(owner, local, top)
                    })
                }
            }
        }

    override suspend fun add(owner: String, token: String, imageUrl: String) =
        favorites.add(owner, token, imageUrl)

    override suspend fun remove(owner: String, token: String) = favorites.remove(owner, token)
}
