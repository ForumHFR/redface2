package fr.forumhfr.redface2.core.database.stats

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        NetworkRequestEntity::class, NoRequestEntity::class, NetworkPeakEntity::class, NetworkActionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NetworkStatsDatabase : RoomDatabase() {
    abstract fun statsDao(): NetworkStatsDao

    companion object {
        const val DATABASE_NAME = "network_stats.db"

        fun open(context: Context): NetworkStatsDatabase =
            Room.databaseBuilder(context, NetworkStatsDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
