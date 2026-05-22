package fr.forumhfr.redface2.core.data.search

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.search.SearchRepository
import fr.forumhfr.redface2.core.parser.search.SearchResultParser
import javax.inject.Singleton

/**
 * Phase 2G-A (#150 partiel) — Hilt wiring for the search repository.
 *
 * Pattern mirrors [fr.forumhfr.redface2.core.data.smiley.SmileyRepositoryModule] :
 * `@Binds` for the interface → impl alias, `@Provides` for the parser companion since
 * `SearchResultParser` has no `@Inject` constructor (it's a pure class with no deps).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SearchRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSearchRepository(impl: DefaultSearchRepository): SearchRepository

    companion object {
        @Provides
        @Singleton
        fun provideSearchResultParser(): SearchResultParser = SearchResultParser()
    }
}
