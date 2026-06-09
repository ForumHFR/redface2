package fr.forumhfr.redface2.core.data.write

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.write.DeletePostRepository
import fr.forumhfr.redface2.core.domain.write.EditPostRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicFormRepository
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import fr.forumhfr.redface2.core.parser.write.ReplySubmitResponseParser
import fr.forumhfr.redface2.core.parser.write.TopicFormParser
import javax.inject.Singleton

/**
 * Hilt wiring for the Phase 2C reply repository + Phase 2D edit-post repository.
 * Mirrors the (`@Binds` interface, `@Provides` parsers) split adopted by
 * `FlagRepositoryModule` and friends. The two repositories share the form +
 * response parsers (the wire shapes are identical apart from the `bddpost.php`
 * vs `bdd.php` endpoint and the `numreponse` field).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReplyRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindReplyRepository(impl: DefaultReplyRepository): ReplyRepository

    @Binds
    @Singleton
    abstract fun bindEditPostRepository(impl: DefaultEditPostRepository): EditPostRepository

    @Binds
    @Singleton
    abstract fun bindDeletePostRepository(impl: DefaultDeletePostRepository): DeletePostRepository

    @Binds
    @Singleton
    abstract fun bindTopicFormRepository(impl: DefaultTopicFormRepository): TopicFormRepository

    @Binds
    @Singleton
    abstract fun bindPrivateMessageWriteRepository(
        impl: DefaultPrivateMessageWriteRepository,
    ): PrivateMessageWriteRepository

    companion object {
        @Provides
        @Singleton
        fun provideReplyFormParser(): ReplyFormParser = ReplyFormParser()

        @Provides
        @Singleton
        fun provideReplySubmitResponseParser(): ReplySubmitResponseParser =
            ReplySubmitResponseParser()

        @Provides
        @Singleton
        fun provideTopicFormParser(): TopicFormParser = TopicFormParser()
    }
}
