package fr.forumhfr.redface2.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import fr.forumhfr.redface2.core.database.converters.Converters
import fr.forumhfr.redface2.core.database.dao.EditorDraftDao
import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.database.dao.MpReadPositionDao
import fr.forumhfr.redface2.core.database.dao.MpStorageLocationDao
import fr.forumhfr.redface2.core.database.dao.PrivateMessageContentDao
import fr.forumhfr.redface2.core.database.dao.TopicDao
import fr.forumhfr.redface2.core.database.dao.UploadedImageDao
import fr.forumhfr.redface2.core.database.entities.EditorDraftEntity
import fr.forumhfr.redface2.core.database.entities.FlagTopicEntity
import fr.forumhfr.redface2.core.database.entities.MpReadPositionEntity
import fr.forumhfr.redface2.core.database.entities.MpStorageLocationEntity
import fr.forumhfr.redface2.core.database.entities.PrivateMessageEntity
import fr.forumhfr.redface2.core.database.entities.PrivateMessageThreadPageEntity
import fr.forumhfr.redface2.core.database.entities.PostEntity
import fr.forumhfr.redface2.core.database.entities.TopicEntity
import fr.forumhfr.redface2.core.database.entities.UploadedImageEntity

@Database(
    entities = [
        TopicEntity::class,
        PostEntity::class,
        FlagTopicEntity::class,
        MpReadPositionEntity::class,
        EditorDraftEntity::class,
        UploadedImageEntity::class,
        MpStorageLocationEntity::class,
        PrivateMessageThreadPageEntity::class,
        PrivateMessageEntity::class,
    ],
    version = 19,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RedfaceDatabase : RoomDatabase() {
    abstract fun topicDao(): TopicDao
    abstract fun flagDao(): FlagDao
    abstract fun mpReadPositionDao(): MpReadPositionDao
    abstract fun editorDraftDao(): EditorDraftDao
    abstract fun uploadedImageDao(): UploadedImageDao
    abstract fun mpStorageLocationDao(): MpStorageLocationDao
    abstract fun privateMessageContentDao(): PrivateMessageContentDao

    companion object {
        const val DATABASE_NAME: String = "redface.db"
    }
}
