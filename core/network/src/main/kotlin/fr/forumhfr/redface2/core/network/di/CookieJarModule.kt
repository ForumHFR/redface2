package fr.forumhfr.redface2.core.network.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.network.cookie.InMemoryCookieJar
import javax.inject.Singleton
import okhttp3.CookieJar

@Module
@InstallIn(SingletonComponent::class)
abstract class CookieJarModule {
    @Binds
    @Singleton
    abstract fun bindCookieJar(impl: InMemoryCookieJar): CookieJar
}
