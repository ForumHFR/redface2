package fr.forumhfr.redface2.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.preferences.NavBarLabelsBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.StartScreenBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteRepository
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UserPreferencesModule {

    @Provides
    @Singleton
    @UserPreferencesDataStore
    fun provideUserPreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = {
            context.preferencesDataStoreFile(DATASTORE_FILE_NAME)
        })

    private const val DATASTORE_FILE_NAME = "user_preferences"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UserPreferencesBindingsModule {

    @Binds
    @Singleton
    abstract fun bindUserPreferencesRepository(
        impl: DataStoreUserPreferencesRepository,
    ): UserPreferencesRepository

    @Binds
    @Singleton
    abstract fun bindThemeBootstrapStore(
        impl: SharedPreferencesThemeBootstrapStore,
    ): ThemeBootstrapStore

    @Binds
    @Singleton
    abstract fun bindStartScreenBootstrapStore(
        impl: SharedPreferencesStartScreenBootstrapStore,
    ): StartScreenBootstrapStore

    @Binds
    @Singleton
    abstract fun bindNavBarLabelsBootstrapStore(
        impl: SharedPreferencesNavBarLabelsBootstrapStore,
    ): NavBarLabelsBootstrapStore

    @Binds
    @Singleton
    abstract fun bindSuperFavoriteRepository(
        impl: DataStoreSuperFavoriteRepository,
    ): SuperFavoriteRepository
}
