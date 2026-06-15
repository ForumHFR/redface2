package fr.forumhfr.redface2.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.coroutines.ApplicationScope
import fr.forumhfr.redface2.core.domain.coroutines.DefaultDispatcher
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.coroutines.MainDispatcher
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.parser.HfrParser
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageListParser
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageThreadParser
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /**
     * Process-lifetime scope for writes that must complete regardless of the caller's lifecycle
     * (#507). `SupervisorJob` so one failed write never tears down the scope; on [IoDispatcher] since
     * its only client today is DataStore commits. Never cancelled (it lives for the whole process).
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Provides
    @Singleton
    fun provideHfrParser(): HfrParser = HfrParser()

    @Provides
    @Singleton
    fun providePrivateMessageListParser(): PrivateMessageListParser = PrivateMessageListParser()

    @Provides
    @Singleton
    fun providePrivateMessageThreadParser(): PrivateMessageThreadParser = PrivateMessageThreadParser()

    @Provides
    @Singleton
    fun provideBbcodePreviewParser(parser: HfrParser): BbcodePreviewParser =
        BbcodePreviewParser { bbcode -> parser.parsePostContentFromBbcode(bbcode) }
}
