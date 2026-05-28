package fr.forumhfr.redface2.core.data.profile

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.profile.ProfileRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: DefaultProfileRepository): ProfileRepository
}
