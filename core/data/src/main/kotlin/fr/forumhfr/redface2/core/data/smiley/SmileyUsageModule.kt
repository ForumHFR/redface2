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
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SmileyUsageModule {
    @Provides
    @Singleton
    @SmileyUsageDataStore
    fun provideSmileyUsageDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // No replacement corruption handler: failed reads never erase local history.
            produceFile = { context.preferencesDataStoreFile(DATASTORE_FILE_NAME) },
        )

    private const val DATASTORE_FILE_NAME = "smiley_usage"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SmileyUsageBindingsModule {
    @Binds
    @Singleton
    abstract fun bindSmileyUsageRepository(impl: DataStoreSmileyUsageRepository): SmileyUsageRepository
}
