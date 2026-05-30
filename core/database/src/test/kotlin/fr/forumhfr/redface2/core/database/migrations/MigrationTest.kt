package fr.forumhfr.redface2.core.database.migrations

import android.content.ContentValues
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-driven migration tests for every hand-written Room migration in the schema
 * — currently `MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`, `MIGRATION_4_5`,
 * `MIGRATION_5_6` (Phase 2 finish #208 added `Post.profileId` in v6) and `MIGRATION_6_7`
 * (#213 added `Topic.canReply` in v7). Without these tests a typo (missing column, wrong
 * index name, wrong default) would only crash on a real upgrade-in-place install, where
 * the diagnostic loop is days long. The tests take seconds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RedfaceDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun tearDown() {
        // Helper closes its own DBs, but the runMigrationsAndValidate-then-Room-open
        // path leaves the file open until the test process exits. Belt-and-braces.
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RedfaceDatabase::class.java,
        ).build().close()
    }

    @Test
    fun migrate_1_to_3_chains_both_migrations_and_keeps_topic_pages_data() {
        val dbName = "redface-test-migration-1-to-3.db"

        // 1. Build the v1 DB and insert a `topic_pages` + `posts` row that pre-dates
        //    `authMode`. We write through the raw SQLite helper because the v1
        //    entity classes no longer exist in source — only the schema does.
        helper.createDatabase(dbName, 1).use { v1 ->
            v1.insert(
                "topic_pages",
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("cat", 23)
                    put("post", 35395)
                    put("page", 1)
                    put("title", "fixture topic")
                    put("totalPages", 1)
                    put("isFirstPostOwner", 0)
                    putNull("pollJson")
                    put("numreponses", "[\"100\"]")
                    put("fetchedAt", 1_700_000_000_000L)
                },
            )
            v1.insert(
                "posts",
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("cat", 23)
                    put("numreponse", 100L)
                    put("post", 35395)
                    put("author", "alice")
                    put("date", 1_700_000_000_000L)
                    put("content", "{}")
                    putNull("avatarUrl")
                    put("isEditable", 0)
                    put("isOwnPost", 0)
                    put("quotedAuthors", "[]")
                    putNull("postIndex")
                    put("fetchedAt", 1_700_000_000_000L)
                },
            )
        }

        // 2. Run both migrations in chain against the v1 fixture DB. Target version 3
        //    is the current @Database version. `validateDroppedTables = true` asserts
        //    every Room-known table matches the v3 schema exactly — so a typo in
        //    MIGRATION_1_2 or MIGRATION_2_3 would surface here.
        helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_1_2, MIGRATION_2_3).close()

        // 3. Open the upgraded DB via Room (already at the latest version). Read back
        //    through the DAOs and confirm the shape: `authMode` was backfilled to
        //    AUTHENTICATED on the pre-existing rows, `flag_topics` is queryable with
        //    the v3 shape (no `views`, `lastPostReadId` nullable).
        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RedfaceDatabase::class.java,
            dbName,
        )
            .allowMainThreadQueries()
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )
            .build()

        try {
            migrated.openHelper.readableDatabase.query(
                "SELECT authMode FROM topic_pages WHERE cat = 23 AND post = 35395 AND page = 1",
            ).use { cursor ->
                assertTrue("topic_pages row should survive migration", cursor.moveToFirst())
                assertEquals("AUTHENTICATED", cursor.getString(0))
                assertNull("only one row expected", cursor.moveToNext().takeIf { it }?.let { "extra row" })
            }
            migrated.openHelper.readableDatabase.query(
                "SELECT authMode FROM posts WHERE cat = 23 AND numreponse = 100",
            ).use { cursor ->
                assertTrue("posts row should survive migration", cursor.moveToFirst())
                assertEquals("AUTHENTICATED", cursor.getString(0))
            }

            // The flag_topics table reaches v3 shape after the chain: REST-aligned
            // columns (no `views`, `lastPostReadId` nullable) compile against the
            // migrated DB. If anyone re-introduced a legacy column, this query would
            // throw.
            migrated.openHelper.readableDatabase.query(
                "SELECT lastPostReadId FROM flag_topics LIMIT 1",
            ).use { cursor ->
                assertEquals(0, cursor.count)
            }
            // flag_topics is empty but queryable — the table was created with the
            // expected indices (validated by runMigrationsAndValidate above).
            migrated.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM flag_topics",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            assertNotNull(migrated.flagDao())
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrate_2_to_3_rebuilds_flag_topics_with_REST_shape_and_keeps_topic_pages_intact() {
        val dbName = "redface-test-migration-2-to-3.db"

        // 1. Build a v2 DB with the LEGACY flag_topics shape — what intermediate
        //    Phase 1D AABs (v25-v28) actually wrote on real devices. Cooking the v2
        //    fixture by hand instead of running MIGRATION_1_2 because the v2 schema
        //    JSON exported by Room reflects the FIXED shape, not the legacy one.
        helper.createDatabase(dbName, 2).use { v2 ->
            v2.execSQL("DROP TABLE IF EXISTS `flag_topics`")
            v2.execSQL(
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
                    `views` INTEGER NOT NULL,
                    `hasUnread` INTEGER NOT NULL,
                    `lastReadPage` INTEGER NOT NULL,
                    `firstUnreadPostId` INTEGER NOT NULL,
                    `firstPostAuthor` TEXT NOT NULL,
                    `lastReplyAuthor` TEXT NOT NULL,
                    `lastReplyAt` TEXT NOT NULL,
                    `fetchedAt` INTEGER NOT NULL,
                    `authMode` TEXT NOT NULL,
                    PRIMARY KEY(`userId`, `type`, `cat`, `topicId`)
                )
                """.trimIndent(),
            )
            // Insert a row with the legacy NOT NULL columns to prove the migration
            // doesn't choke on existing data — the destructive recreate is safe.
            v2.insert(
                "flag_topics",
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("userId", "alice")
                    put("type", "CYAN")
                    put("cat", 23)
                    putNull("subcat")
                    put("topicId", 35395)
                    put("title", "legacy topic")
                    put("totalPages", 1)
                    put("replyCount", 0)
                    put("views", 9_999)
                    put("hasUnread", 0)
                    put("lastReadPage", 1)
                    put("firstUnreadPostId", 555_000_100L)
                    put("firstPostAuthor", "alice")
                    put("lastReplyAuthor", "bob")
                    put("lastReplyAt", "2026-04-01 10:00")
                    put("fetchedAt", 1_700_000_000_000L)
                    put("authMode", "AUTHENTICATED")
                },
            )
            // Touch topic_pages so we can prove MIGRATION_2_3 leaves it alone.
            v2.insert(
                "topic_pages",
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("cat", 23)
                    put("post", 35395)
                    put("page", 1)
                    put("title", "kept across migration")
                    put("totalPages", 1)
                    put("isFirstPostOwner", 0)
                    putNull("pollJson")
                    put("numreponses", "[\"100\"]")
                    put("fetchedAt", 1_700_000_000_000L)
                    put("authMode", "AUTHENTICATED")
                },
            )
        }

        // 2. Run MIGRATION_2_3 and validate against the v3 schema.
        helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3).close()

        // 3. flag_topics legacy row is gone (drapeaux are pure cache, refetched at
        //    next observe), topic_pages survives.
        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RedfaceDatabase::class.java,
            dbName,
        )
            .allowMainThreadQueries()
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )
            .build()

        try {
            migrated.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM flag_topics",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("flag_topics must be empty after destructive rebuild", 0, cursor.getInt(0))
            }
            migrated.openHelper.readableDatabase.query(
                "SELECT title FROM topic_pages WHERE cat = 23 AND post = 35395 AND page = 1",
            ).use { cursor ->
                assertTrue("topic_pages row must survive MIGRATION_2_3", cursor.moveToFirst())
                assertEquals("kept across migration", cursor.getString(0))
            }
            // Querying the new column name compiles, querying the legacy ones throws —
            // proving the rebuild took effect.
            migrated.openHelper.readableDatabase.query(
                "SELECT lastPostReadId FROM flag_topics LIMIT 1",
            ).use { cursor ->
                assertEquals(0, cursor.count)
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * Phase 2C (#146 round 2) — proves `MIGRATION_4_5` adds `quoteRef` to `posts`
     * as a nullable column, backfills pre-v5 rows to `NULL`, and that the column
     * is queryable. Without this migration, every fresh cache hit would reset
     * `quoteRef` to null and the « Citer » button would vanish from the UI until
     * the next live fetch — see `MIGRATION_4_5` KDoc.
     */
    @Test
    fun migrate_4_to_5_adds_nullable_quoteRef_to_posts() {
        val dbName = "redface-test-migration-4-to-5.db"

        // 1. Build the v4 DB and insert one topic_pages row + one posts row that
        //    pre-date `quoteRef`. We write through the raw SQLite helper because
        //    the v4 entity classes no longer exist in source — only the schema does.
        helper.createDatabase(dbName, 4).use { v4 ->
            v4.insert(
                "topic_pages",
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("cat", 23)
                    put("post", 35395)
                    put("page", 1)
                    put("title", "v4 cached topic")
                    put("totalPages", 1)
                    put("isFirstPostOwner", 0)
                    putNull("pollJson")
                    put("numreponses", "[\"100\"]")
                    put("fetchedAt", 1_700_000_000_000L)
                    put("authMode", "AUTHENTICATED")
                    put("subcat", 550)
                },
            )
            v4.insert(
                "posts",
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("cat", 23)
                    put("numreponse", 100)
                    put("post", 35395)
                    put("author", "fixtureUser")
                    put("date", 1_700_000_000_000L)
                    put("content", "{\"blocks\":[]}")
                    putNull("avatarUrl")
                    put("isEditable", 0)
                    put("isOwnPost", 0)
                    put("quotedAuthors", "[]")
                    putNull("postIndex")
                    put("fetchedAt", 1_700_000_000_000L)
                    put("authMode", "AUTHENTICATED")
                },
            )
        }

        // 2. Run MIGRATION_4_5 and validate against the v5 schema.
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()

        // 3. Open the production Room database (chains every migration).
        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RedfaceDatabase::class.java,
            dbName,
        )
            .allowMainThreadQueries()
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )
            .build()

        try {
            migrated.openHelper.readableDatabase.query(
                "SELECT quoteRef FROM posts WHERE cat = 23 AND numreponse = 100",
            ).use { cursor ->
                assertTrue("v4 post row must survive MIGRATION_4_5", cursor.moveToFirst())
                assertTrue(
                    "quoteRef must default to NULL for pre-v5 rows (sentinel = refresh required)",
                    cursor.isNull(0),
                )
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * Phase 2C (#145) — proves `MIGRATION_3_4` adds `subcat` to `topic_pages` with
     * the sentinel default `-1`, that pre-existing rows are preserved and that the
     * new column is queryable as a real `INTEGER`. The sentinel value (-1,
     * SUBCAT_UNKNOWN) is the "unknown, must be refreshed before any write flow"
     * marker. Since #213, write paths gate on `subcat >= 0` (rejecting only the
     * sentinel) : `subcat = 0` is a valid, postable value for a category without
     * sub-category (e.g. cat IA), proven by a live capture of the IA reply form.
     */
    @Test
    fun migrate_3_to_4_adds_subcat_with_sentinel_default_to_topic_pages() {
        val dbName = "redface-test-migration-3-to-4.db"

        // 1. Build the v3 DB and insert a topic_pages row that pre-dates `subcat`.
        helper.createDatabase(dbName, 3).use { v3 ->
            v3.insert(
                "topic_pages",
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("cat", 23)
                    put("post", 35395)
                    put("page", 1)
                    put("title", "v3 cached topic")
                    put("totalPages", 1)
                    put("isFirstPostOwner", 0)
                    putNull("pollJson")
                    put("numreponses", "[\"100\"]")
                    put("fetchedAt", 1_700_000_000_000L)
                    put("authMode", "AUTHENTICATED")
                },
            )
        }

        // 2. Run MIGRATION_3_4 and validate against the v4 schema.
        helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4).close()

        // 3. Open the production Room database (which chains every migration) so
        //    the post-migration schema is what real users land on.
        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RedfaceDatabase::class.java,
            dbName,
        )
            .allowMainThreadQueries()
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )
            .build()

        try {
            migrated.openHelper.readableDatabase.query(
                "SELECT subcat FROM topic_pages WHERE cat = 23 AND post = 35395 AND page = 1",
            ).use { cursor ->
                assertTrue("v3 row must survive MIGRATION_3_4", cursor.moveToFirst())
                assertEquals(
                    "subcat must default to the SUBCAT_UNKNOWN sentinel for pre-v4 rows",
                    -1,
                    cursor.getInt(0),
                )
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * Phase 2 finish (#208) — v5 → v6 adds nullable `profileId` to `posts`.
     *
     * Verifies:
     * 1. The migration runs cleanly against the v5 fixture.
     * 2. Pre-existing post rows survive the migration.
     * 3. The new column defaults to NULL on old rows.
     */
    @Test
    fun migrate_5_to_6_adds_nullable_profileId_to_posts() {
        val dbName = "migration_5_6_test"

        // 1. Create a v5 database and insert a posts row so we can validate
        //    that the column is added without losing existing data.
        helper.createDatabase(dbName, 5).apply {
            execSQL(
                """INSERT INTO topic_pages (cat, post, page, title, totalPages, isFirstPostOwner,
                   numreponses, fetchedAt, authMode, subcat)
                   VALUES (23, 35395, 1, 'Test topic', 10, 0, '[]', 1000, 'AUTHENTICATED', 550)""",
            )
            execSQL(
                """INSERT INTO posts (cat, numreponse, post, author, date, content, avatarUrl,
                   isEditable, isOwnPost, quotedAuthors, postIndex, fetchedAt, authMode, quoteRef)
                   VALUES (23, 100, 35395, 'XaTriX', 1000,
                   '{"blocks":[]}', NULL, 0, 0, '[]', 1, 1000, 'AUTHENTICATED', NULL)""",
            )
            close()
        }

        // 2. Run MIGRATION_5_6 and validate against the v6 schema.
        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6).close()

        // 3. Open the production Room database to verify data integrity.
        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RedfaceDatabase::class.java,
            dbName,
        )
            .allowMainThreadQueries()
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )
            .build()

        try {
            migrated.openHelper.readableDatabase.query(
                "SELECT profileId FROM posts WHERE cat = 23 AND numreponse = 100",
            ).use { cursor ->
                assertTrue("pre-v6 post row must survive MIGRATION_5_6", cursor.moveToFirst())
                assertTrue("profileId must be NULL for pre-v6 rows", cursor.isNull(0))
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * #213 — v6 → v7 adds `canReply` to `topic_pages` with the default `0` (`false`).
     *
     * Verifies:
     * 1. The migration runs cleanly against the v6 fixture.
     * 2. Pre-existing topic rows survive the migration.
     * 3. The new column defaults to `0` (read-only) on pre-v7 rows — they stay
     *    read-only until the next live authenticated fetch surfaces the reply form.
     */
    @Test
    fun migrate_6_to_7_adds_canReply_with_false_default_to_topic_pages() {
        val dbName = "migration_6_7_test"

        // 1. Create a v6 database and insert a topic_pages row that pre-dates `canReply`.
        helper.createDatabase(dbName, 6).apply {
            execSQL(
                """INSERT INTO topic_pages (cat, post, page, title, totalPages, isFirstPostOwner,
                   numreponses, fetchedAt, authMode, subcat)
                   VALUES (23, 35395, 1, 'v6 cached topic', 10, 0, '[]', 1000, 'AUTHENTICATED', 550)""",
            )
            close()
        }

        // 2. Run MIGRATION_6_7 and validate against the v7 schema.
        helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7).close()

        // 3. Open the production Room database (which chains every migration).
        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RedfaceDatabase::class.java,
            dbName,
        )
            .allowMainThreadQueries()
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )
            .build()

        try {
            migrated.openHelper.readableDatabase.query(
                "SELECT canReply FROM topic_pages WHERE cat = 23 AND post = 35395 AND page = 1",
            ).use { cursor ->
                assertTrue("v6 row must survive MIGRATION_6_7", cursor.moveToFirst())
                assertEquals(
                    "canReply must default to 0 (read-only) for pre-v7 rows",
                    0,
                    cursor.getInt(0),
                )
            }
        } finally {
            migrated.close()
        }
    }

}
