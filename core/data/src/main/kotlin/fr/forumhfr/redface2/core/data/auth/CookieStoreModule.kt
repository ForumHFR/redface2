package fr.forumhfr.redface2.core.data.auth

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
import fr.forumhfr.redface2.core.network.cookie.CookieStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CookieStoreModule {

    @Provides
    @Singleton
    @CookieDataStore
    fun provideCookieDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = {
            context.preferencesDataStoreFile(DATASTORE_FILE_NAME)
        })

    private const val DATASTORE_FILE_NAME = "hfr_cookies"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CookieStoreBindingsModule {

    @Binds
    @Singleton
    abstract fun bindCookieStore(impl: DataStoreCookieStore): CookieStore
}
