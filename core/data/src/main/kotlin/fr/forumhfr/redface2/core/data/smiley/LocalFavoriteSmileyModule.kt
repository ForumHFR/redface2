package fr.forumhfr.redface2.core.data.smiley

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalFavoriteSmileyModule {
    @Provides
    @Singleton
    @LocalFavoriteSmileyDataStore
    fun provideLocalFavoriteSmileyDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // No replacement corruption handler: never erase user data to repair a failed read.
            produceFile = { context.preferencesDataStoreFile(DATASTORE_FILE_NAME) },
        )

    private const val DATASTORE_FILE_NAME = "local_favorite_smileys"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalFavoriteSmileyBindingsModule {
    @Binds
    @Singleton
    abstract fun bindLocalFavoriteSmileyRepository(
        impl: DataStoreLocalFavoriteSmileyRepository,
    ): LocalFavoriteSmileyRepository
}
