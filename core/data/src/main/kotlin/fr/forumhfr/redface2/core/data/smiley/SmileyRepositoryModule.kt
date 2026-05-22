package fr.forumhfr.redface2.core.data.smiley

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.parser.smiley.SmileySearchParser
import javax.inject.Singleton

/**
 * Phase 2F-B (#11 partial) — Hilt wiring for the wiki smiley search repository. Same
 * `@Binds interface ; @Provides parser` split as the write repositories.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SmileyRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSmileyRepository(impl: DefaultSmileyRepository): SmileyRepository

    companion object {
        @Provides
        @Singleton
        fun provideSmileySearchParser(): SmileySearchParser = SmileySearchParser()
    }
}
