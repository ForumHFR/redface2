package fr.forumhfr.redface2.core.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2 (Phase 1D PR 3 — #26):
 *
 * - Adds `authMode` to `topic_pages` and `posts` so an anonymous prefetch row
 *   never overwrites the per-user fields of an authenticated row. Existing
 *   v1 rows were all written by the authenticated client (only one client
 *   existed in v1), so we backfill `'AUTHENTICATED'`.
 * - Creates `flag_topics`: persistence of the user's drapeaux page with
 *   isolation by HFR pseudo (`userId` in the primary key).
 *
 * Why hand-written, not auto: Room's auto-migrations would require splitting
 * the schema into two `@Database` versions and an `AutoMigrationSpec` to set
 * the default for the new column, which is more code than the four DDL
 * statements below. The migration is pure DDL — no row rewrite — so a manual
 * migration is the simpler answer.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE topic_pages ADD COLUMN authMode TEXT NOT NULL DEFAULT 'AUTHENTICATED'",
        )
        db.execSQL(
            "ALTER TABLE posts ADD COLUMN authMode TEXT NOT NULL DEFAULT 'AUTHENTICATED'",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `flag_topics` (
                `userId` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `cat` INTEGER NOT NULL,
                `subcat` INTEGER,
                `topicId` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `totalPages` INTEGER NOT NULL,
                `replyCount` INTEGER NOT NULL,
                `hasUnread` INTEGER NOT NULL,
                `lastReadPage` INTEGER NOT NULL,
                `lastPostReadId` INTEGER,
                `firstPostAuthor` TEXT NOT NULL,
                `lastReplyAuthor` TEXT NOT NULL,
                `lastReplyAt` TEXT NOT NULL,
                `fetchedAt` INTEGER NOT NULL,
                `authMode` TEXT NOT NULL,
                PRIMARY KEY(`userId`, `type`, `cat`, `topicId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_flag_topics_userId_type` " +
                "ON `flag_topics` (`userId`, `type`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_flag_topics_userId_fetchedAt` " +
                "ON `flag_topics` (`userId`, `fetchedAt`)",
        )
    }
}
