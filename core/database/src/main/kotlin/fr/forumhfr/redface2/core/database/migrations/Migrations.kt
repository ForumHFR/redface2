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

/**
 * v2 → v3 (Phase 1D hotfix):
 *
 * Rebuilds `flag_topics` to match the REST-aligned shape of [FlagTopicEntity] :
 * - drops `views NOT NULL` (the REST API does not expose a view count),
 * - replaces `firstUnreadPostId INTEGER NOT NULL` with `lastPostReadId INTEGER`
 *   (REST exposes `last_post_read_id`, the **last** post the user has read,
 *   nullable when omitted on a row).
 *
 * Devices that ran an intermediate Phase 1D AAB (v25-v28) wrote `flag_topics`
 * with the legacy shape, then upgraded to a build whose entity expects the new
 * shape — Room then fails with `IllegalStateException: Room cannot verify the
 * data integrity` because the identity hash changed without a version bump.
 * This migration drops the table and recreates it: `flag_topics` is pure cache
 * (drapeaux are refetched at next observe), so destructive recreation is safe
 * and avoids a column-by-column rewrite that would gain nothing for empty rows.
 *
 * `topic_pages` and `posts` are untouched : their schema didn't change between
 * v2 and v3, only `flag_topics` did.
 */
/**
 * v3 → v4 (Phase 2C, #145):
 *
 * Adds `subcat` to `topic_pages`. Phase 2C requires the sub-category id to build a
 * valid `message.php` GET form and a valid `bddpost.php` POST (cf.
 * `docs/specs/protocol-hfr.md` § POST `bddpost.php`). The parser extracts the value
 * from the topic page HTML (`input[name=subcat]`), the entity stores it next to
 * `cat`, `post`, `page`.
 *
 * Existing v3 rows are backfilled to `-1`, a sentinel that means "unknown, must be
 * refreshed before any write flow". Write paths refuse to POST when `subcat < 0` —
 * the value is *never* transmitted to HFR. Setting the column NOT NULL via the
 * `-1` default keeps Room's schema verification happy without forcing a row rewrite
 * (topic pages are short-lived cache, the next live fetch replaces the sentinel).
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE topic_pages ADD COLUMN subcat INTEGER NOT NULL DEFAULT -1",
        )
    }
}

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `flag_topics`")
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
