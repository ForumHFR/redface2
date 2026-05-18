package fr.forumhfr.redface2.core.data.write

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import fr.forumhfr.redface2.core.parser.write.ReplySubmitResponseParser
import javax.inject.Singleton

/**
 * Hilt wiring for the Phase 2C reply repository. Mirrors the (`@Binds` interface,
 * `@Provides` parsers) split adopted by `FlagRepositoryModule` and friends.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReplyRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindReplyRepository(impl: DefaultReplyRepository): ReplyRepository

    companion object {
        @Provides
        @Singleton
        fun provideReplyFormParser(): ReplyFormParser = ReplyFormParser()

        @Provides
        @Singleton
        fun provideReplySubmitResponseParser(): ReplySubmitResponseParser =
            ReplySubmitResponseParser()
    }
}
