package fr.forumhfr.redface2.core.data.profile

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.profile.ProfileRepository
import fr.forumhfr.redface2.core.domain.profile.SanctionsRepository
import fr.forumhfr.redface2.core.parser.profile.SanctionsHistoryParser
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: DefaultProfileRepository): ProfileRepository

    @Binds
    @Singleton
    abstract fun bindSanctionsRepository(impl: DefaultSanctionsRepository): SanctionsRepository

    companion object {
        @Provides
        @Singleton
        fun provideSanctionsHistoryParser(): SanctionsHistoryParser = SanctionsHistoryParser()
    }
}
