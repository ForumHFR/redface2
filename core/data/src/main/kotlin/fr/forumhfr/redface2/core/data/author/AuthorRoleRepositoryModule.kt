package fr.forumhfr.redface2.core.data.author

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.author.AuthorRoleRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthorRoleRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthorRoleRepository(impl: DefaultAuthorRoleRepository): AuthorRoleRepository
}
