package fr.forumhfr.redface2.core.data.forum

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ForumRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindForumRepository(impl: DefaultForumRepository): ForumRepository
}

@Module
@InstallIn(SingletonComponent::class)
internal object ForumJsonModule {

    /**
     * Lenient JSON: REST responses occasionally carry server-side keys we don't model
     * yet (e.g. `tns3` avatar filename, `linked_type`, `type` discriminators). Ignoring
     * unknown keys keeps the contract additive — adding a new field in fixtures doesn't
     * break parsing.
     */
    @Provides
    @Singleton
    fun provideForumJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}
