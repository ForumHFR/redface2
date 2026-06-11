package fr.forumhfr.redface2.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import fr.forumhfr.redface2.core.database.converters.Converters
import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.database.dao.TopicDao
import fr.forumhfr.redface2.core.database.entities.FlagTopicEntity
import fr.forumhfr.redface2.core.database.entities.PostEntity
import fr.forumhfr.redface2.core.database.entities.TopicEntity

@Database(
    entities = [TopicEntity::class, PostEntity::class, FlagTopicEntity::class],
    version = 9,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RedfaceDatabase : RoomDatabase() {
    abstract fun topicDao(): TopicDao
    abstract fun flagDao(): FlagDao

    companion object {
        const val DATABASE_NAME: String = "redface.db"
    }
}
