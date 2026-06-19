package fr.forumhfr.redface2.core.data.blacklist

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.blacklist.BlacklistRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BlacklistModule {

    @Provides
    @Singleton
    @BlacklistDataStore
    fun provideBlacklistDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // A corrupt Preferences file (proto-level) resets to an empty blacklist rather than
            // crashing on first read — a bad on-disk store must never hide the whole forum.
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile(DATASTORE_FILE_NAME) },
        )

    private const val DATASTORE_FILE_NAME = "blacklist"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BlacklistBindingsModule {

    @Binds
    @Singleton
    abstract fun bindBlacklistRepository(
        impl: DefaultBlacklistRepository,
    ): BlacklistRepository
}
