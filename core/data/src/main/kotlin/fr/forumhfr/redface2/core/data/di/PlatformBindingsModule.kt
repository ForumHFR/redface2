package fr.forumhfr.redface2.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.data.fixtures.AssetTopicFixtureRepository
import fr.forumhfr.redface2.core.domain.coroutines.DefaultDispatcher
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.coroutines.MainDispatcher
import fr.forumhfr.redface2.core.domain.fixtures.TopicFixtureRepository
import fr.forumhfr.redface2.core.parser.HfrParser
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object PlatformBindingsModule {
    @Provides
    @Singleton
    fun provideRedfaceClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    @Provides
    @Singleton
    fun provideHfrParser(): HfrParser = HfrParser()

    @Provides
    @Singleton
    fun provideTopicFixtureRepository(
        @ApplicationContext context: Context,
        parser: HfrParser,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): TopicFixtureRepository = AssetTopicFixtureRepository(
        context = context,
        parser = parser,
        ioDispatcher = ioDispatcher,
    )
}
