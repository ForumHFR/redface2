package fr.forumhfr.redface2.core.data.flags

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FlagRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindFlagRepository(impl: DefaultFlagRepository): FlagRepository
}
