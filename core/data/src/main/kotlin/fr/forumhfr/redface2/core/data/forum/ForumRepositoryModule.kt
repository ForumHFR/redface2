package fr.forumhfr.redface2.core.data.forum

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ForumRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindForumRepository(impl: DefaultForumRepository): ForumRepository
}
