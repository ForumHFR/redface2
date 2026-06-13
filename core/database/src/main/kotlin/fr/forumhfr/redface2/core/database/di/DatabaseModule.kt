package fr.forumhfr.redface2.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.dao.EditorDraftDao
import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.database.dao.MpReadPositionDao
import fr.forumhfr.redface2.core.database.dao.TopicDao
import fr.forumhfr.redface2.core.database.dao.UploadedImageDao
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_10_11
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_11_12
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_1_2
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_2_3
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_3_4
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_4_5
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_5_6
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_6_7
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_7_8
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_8_9
import fr.forumhfr.redface2.core.database.migrations.MIGRATION_9_10
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
        .addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
        )
        .build()

    @Provides
    fun provideTopicDao(database: RedfaceDatabase): TopicDao = database.topicDao()

    @Provides
    fun provideFlagDao(database: RedfaceDatabase): FlagDao = database.flagDao()

    @Provides
    fun provideMpReadPositionDao(database: RedfaceDatabase): MpReadPositionDao =
        database.mpReadPositionDao()

    @Provides
    fun provideEditorDraftDao(database: RedfaceDatabase): EditorDraftDao =
        database.editorDraftDao()

    @Provides
    fun provideUploadedImageDao(database: RedfaceDatabase): UploadedImageDao =
        database.uploadedImageDao()
}
