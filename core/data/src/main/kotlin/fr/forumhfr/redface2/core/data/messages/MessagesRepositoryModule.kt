package fr.forumhfr.redface2.core.data.messages

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MessagesRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMessagesRepository(impl: DefaultMessagesRepository): MessagesRepository
}
