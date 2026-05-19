package fr.forumhfr.redface2.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.database.dao.TopicDao
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_1_2
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_2_3
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_3_4
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_4_5
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideRedfaceDatabase(
        @ApplicationContext context: Context,
    ): RedfaceDatabase = Room.databaseBuilder(
        context,
        RedfaceDatabase::class.java,
        RedfaceDatabase.DATABASE_NAME,
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        .build()

    @Provides
    fun provideTopicDao(database: RedfaceDatabase): TopicDao = database.topicDao()

    @Provides
    fun provideFlagDao(database: RedfaceDatabase): FlagDao = database.flagDao()
}
