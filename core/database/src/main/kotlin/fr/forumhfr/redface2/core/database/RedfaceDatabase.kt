package fr.forumhfr.redface2.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import fr.forumhfr.redface2.core.database.converters.Converters
import fr.forumhfr.redface2.core.database.dao.TopicDao
import fr.forumhfr.redface2.core.database.entities.PostEntity
import fr.forumhfr.redface2.core.database.entities.TopicEntity

@Database(
    entities = [TopicEntity::class, PostEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RedfaceDatabase : RoomDatabase() {
    abstract fun topicDao(): TopicDao

    companion object {
        const val DATABASE_NAME: String = "redface.db"
    }
}
