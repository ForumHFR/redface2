package fr.forumhfr.redface2.core.data.smiley

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.smiley.HfrFavoriteSmileyRepository
import fr.forumhfr.redface2.core.parser.smiley.HfrFavoriteSmileyFormParser
import fr.forumhfr.redface2.core.parser.smiley.HfrFavoriteSmileyResponseParser
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HfrFavoriteSmileyRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindHfrFavoriteSmileyRepository(impl: DefaultHfrFavoriteSmileyRepository): HfrFavoriteSmileyRepository

    companion object {
        @Provides
        @Singleton
        fun provideHfrFavoriteSmileyFormParser(): HfrFavoriteSmileyFormParser = HfrFavoriteSmileyFormParser()

        @Provides
        @Singleton
        fun provideHfrFavoriteSmileyResponseParser(): HfrFavoriteSmileyResponseParser =
            HfrFavoriteSmileyResponseParser()
    }
}
