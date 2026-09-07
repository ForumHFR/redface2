package fr.forumhfr.redface2.core.database.migrations

import android.content.ContentValues
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.entities.PrivateMessageEntity
import fr.forumhfr.redface2.core.database.entities.PrivateMessageThreadPageEntity
import fr.forumhfr.redface2.core.model.PostContent
import java.time.Instant
import kotlinx.coroutines.test.runTest
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
 * `MIGRATION_5_6` (Phase 2 finish #208 added `Post.profileId` in v6), `MIGRATION_6_7`
 * (#213 added `Topic.canReply` in v7), `MIGRATION_7_8` (#362 added `Post.editedAt`
 * in v8), `MIGRATION_8_9` (#384 follow-up added `FlagTopic.isFavorite` in v9),
 * `MIGRATION_9_10` (#430 added the `mp_read_positions` table in v10),
 * `MIGRATION_10_11` (#405 added the `editor_drafts` table in v11),
 * `MIGRATION_11_12` (#459 added the `uploaded_images` table in v12),
 * `MIGRATION_12_13` (#6/ADR-014 added the `mp_storage_locations` table in v13),
 * `MIGRATION_13_14` (#330 added `Post.signature` in v14), `MIGRATION_14_15` (#863 added
 * `Post.citedCount` in v15), `MIGRATION_15_16` (#638 added flag position metadata in v16),
 * `MIGRATION_16_17` (#1040/#1097 added the dormant private-message content tables in v17), and
 * `MIGRATION_17_18` (#1112 persisted the moderation marker in both Post-backed caches), and
 * `MIGRATION_18_19` (#340 persisted the nullable message tone for topic posts).
 * Without these tests a typo (missing column, wrong index name, wrong default)
 * would only crash on a real upgrade-in-place install, where the diagnostic loop is
 * days long. The tests take seconds.
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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
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
     * is queryable. This preserves HFR's positional `ref` on clear-link cache hits;
     * since #227, a null value no longer hides « Citer ».
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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
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
     * 3. The new column defaults to `0` (read-only) on pre-v7 rows.
     * 4. The row is marked stale (`fetchedAt = 0`) so a fresh v6 authenticated cache hit
     *    does not keep reply / quote / edit hidden for the full topic-page TTL.
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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
            )
            .build()

        try {
            migrated.openHelper.readableDatabase.query(
                "SELECT canReply, fetchedAt FROM topic_pages WHERE cat = 23 AND post = 35395 AND page = 1",
            ).use { cursor ->
                assertTrue("v6 row must survive MIGRATION_6_7", cursor.moveToFirst())
                assertEquals(
                    "canReply must default to 0 (read-only) for pre-v7 rows",
                    0,
                    cursor.getInt(0),
                )
                assertEquals(
                    "fetchedAt must be reset so the next observe refreshes canReply from live HTML",
                    0L,
                    cursor.getLong(1),
                )
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * #362 — v7 → v8 adds nullable `editedAt` to `posts`.
     *
     * Verifies:
     * 1. The migration runs cleanly against the v7 fixture.
     * 2. Pre-existing post rows survive the migration.
     * 3. The new column defaults to NULL on old rows (recovered on the next live fetch).
     */
    @Test
    fun migrate_7_to_8_adds_nullable_editedAt_to_posts() {
        val dbName = "migration_7_8_test"

        // 1. Create a v7 database and insert a posts row that pre-dates `editedAt`.
        helper.createDatabase(dbName, 7).apply {
            execSQL(
                """INSERT INTO topic_pages (cat, post, page, title, totalPages, isFirstPostOwner,
                   numreponses, fetchedAt, authMode, subcat, canReply)
                   VALUES (23, 35395, 1, 'v7 cached topic', 10, 0, '[]', 1000, 'AUTHENTICATED', 550, 1)""",
            )
            execSQL(
                """INSERT INTO posts (cat, numreponse, post, author, date, content, avatarUrl,
                   isEditable, isOwnPost, quotedAuthors, postIndex, fetchedAt, authMode, quoteRef,
                   profileId)
                   VALUES (23, 100, 35395, 'XaTriX', 1000,
                   '{"blocks":[]}', NULL, 0, 0, '[]', 1, 1000, 'AUTHENTICATED', NULL, NULL)""",
            )
            close()
        }

        // 2. Run MIGRATION_7_8 and validate against the v8 schema.
        helper.runMigrationsAndValidate(dbName, 8, true, MIGRATION_7_8).close()

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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
            )
            .build()

        try {
            migrated.openHelper.readableDatabase.query(
                "SELECT editedAt FROM posts WHERE cat = 23 AND numreponse = 100",
            ).use { cursor ->
                assertTrue("pre-v8 post row must survive MIGRATION_7_8", cursor.moveToFirst())
                assertTrue("editedAt must be NULL for pre-v8 rows", cursor.isNull(0))
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * #384 follow-up — v8 → v9 adds `isFavorite` to `flag_topics` (`NOT NULL DEFAULT 0`).
     *
     * Verifies:
     * 1. The migration runs cleanly against the v8 fixture.
     * 2. Pre-existing flag rows survive the migration.
     * 3. The new column defaults to `0` (not favorited) on pre-v9 rows — the next live fetch
     *    sets the real value.
     */
    @Test
    fun migrate_8_to_9_adds_isFavorite_with_false_default_to_flag_topics() {
        val dbName = "migration_8_9_test"

        // 1. Create a v8 database and insert a flag row that pre-dates `isFavorite`.
        helper.createDatabase(dbName, 8).apply {
            execSQL(
                """INSERT INTO flag_topics (userId, type, cat, subcat, topicId, title, totalPages,
                   replyCount, hasUnread, lastReadPage, lastPostReadId, firstPostAuthor,
                   lastReplyAuthor, lastReplyAt, fetchedAt, authMode)
                   VALUES ('xatrix', 'CYAN', 13, NULL, 26595, 'v8 cached flag', 3, 99, 1, 2, NULL,
                   'XaTriX', 'bitubo', '2026-06-11 21:00', 1000, 'AUTHENTICATED')""",
            )
            close()
        }

        // 2. Run MIGRATION_8_9 and validate against the v9 schema.
        helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9).close()

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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
            )
            .build()

        try {
            migrated.openHelper.readableDatabase.query(
                "SELECT isFavorite FROM flag_topics WHERE userId = 'xatrix' AND topicId = 26595",
            ).use { cursor ->
                assertTrue("pre-v9 flag row must survive MIGRATION_8_9", cursor.moveToFirst())
                assertEquals("isFavorite must default to 0 for pre-v9 rows", 0, cursor.getInt(0))
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * #430 — v9 → v10 creates `mp_read_positions` (per-account MP reading positions).
     *
     * Verifies:
     * 1. The migration runs cleanly against the v9 fixture and matches the exported v10 schema.
     * 2. The production Room database (full migration chain) can write and read a position row
     *    through the new table.
     */
    @Test
    fun migrate_9_to_10_creates_mp_read_positions() {
        val dbName = "migration_9_10_test"

        // 1. Create a v9 database (no MP-position rows can pre-exist the table).
        helper.createDatabase(dbName, 9).close()

        // 2. Run MIGRATION_9_10 and validate against the exported v10 schema.
        helper.runMigrationsAndValidate(dbName, 10, true, MIGRATION_9_10).close()

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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
            )
            .build()

        try {
            migrated.openHelper.writableDatabase.execSQL(
                "INSERT INTO mp_read_positions (userId, threadId, page) VALUES ('xatrix', 42, 7)",
            )
            migrated.openHelper.readableDatabase.query(
                "SELECT page FROM mp_read_positions WHERE userId = 'xatrix' AND threadId = 42",
            ).use { cursor ->
                assertTrue("the migrated table must accept and return a row", cursor.moveToFirst())
                assertEquals(7, cursor.getInt(0))
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * #405 — v10 → v11 creates `editor_drafts` (per-account cache of in-progress editor content).
     *
     * Verifies:
     * 1. The migration runs cleanly against the v10 fixture and matches the exported v11 schema.
     * 2. The production Room database (full migration chain) can write and read a draft row through
     *    the new table — including the nullable `subject`/`recipients` columns and a private
     *    (`isPrivate = 1`) MP draft.
     */
    @Test
    fun migrate_10_to_11_creates_editor_drafts() {
        val dbName = "migration_10_11_test"

        // 1. Create a v10 database (no editor-draft rows can pre-exist the table).
        helper.createDatabase(dbName, 10).close()

        // 2. Run MIGRATION_10_11 and validate against the exported v11 schema.
        helper.runMigrationsAndValidate(dbName, 11, true, MIGRATION_10_11).close()

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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
            )
            .build()

        try {
            migrated.openHelper.writableDatabase.execSQL(
                "INSERT INTO editor_drafts (draftKey, ownerId, body, subject, recipients, " +
                    "updatedAt, isPrivate) " +
                    "VALUES ('xatrix|mpreply:42', 'xatrix', 'draft body', NULL, NULL, 1000, 1)",
            )
            migrated.openHelper.readableDatabase.query(
                "SELECT body, subject, recipients, isPrivate FROM editor_drafts " +
                    "WHERE draftKey = 'xatrix|mpreply:42'",
            ).use { cursor ->
                assertTrue("the migrated table must accept and return a row", cursor.moveToFirst())
                assertEquals("draft body", cursor.getString(0))
                assertTrue("subject must round-trip NULL", cursor.isNull(1))
                assertTrue("recipients must round-trip NULL", cursor.isNull(2))
                assertEquals("MP draft must persist isPrivate = 1", 1, cursor.getInt(3))
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * #459 — v11 → v12 creates `uploaded_images` (per-account uploaded-image history).
     *
     * Verifies:
     * 1. The migration runs cleanly against the v11 fixture and matches the exported v12 schema.
     * 2. The production Room database (full migration chain) can write and read an image row through
     *    the new table, including the nullable `thumbnailUrl` / `deleteHandle` / `expiresAt` columns.
     */
    @Test
    fun migrate_11_to_12_creates_uploaded_images() {
        val dbName = "migration_11_12_test"

        // 1. Create a v11 database (no uploaded-image rows can pre-exist the table).
        helper.createDatabase(dbName, 11).close()

        // 2. Run MIGRATION_11_12 and validate against the exported v12 schema.
        helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12).close()

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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
            )
            .build()

        try {
            migrated.openHelper.writableDatabase.execSQL(
                "INSERT INTO uploaded_images (userId, provider, picId, imageUrl, thumbnailUrl, " +
                    "deleteHandle, uploadedAt, expiresAt) " +
                    "VALUES ('xatrix', 'DIBERIE', 'ABC123', 'https://host/f/ABC123', NULL, " +
                    "'ABC123', 1000, NULL)",
            )
            migrated.openHelper.readableDatabase.query(
                "SELECT imageUrl, thumbnailUrl, deleteHandle, expiresAt FROM uploaded_images " +
                    "WHERE userId = 'xatrix' AND provider = 'DIBERIE' AND picId = 'ABC123'",
            ).use { cursor ->
                assertTrue("the migrated table must accept and return a row", cursor.moveToFirst())
                assertEquals("https://host/f/ABC123", cursor.getString(0))
                assertTrue("thumbnailUrl must round-trip NULL", cursor.isNull(1))
                assertEquals("ABC123", cursor.getString(2))
                assertTrue("expiresAt must round-trip NULL", cursor.isNull(3))
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * #6 / ADR-014 — v12 → v13 creates `mp_storage_locations` (per-account cached MPStorage location).
     *
     * Verifies:
     * 1. The migration runs cleanly against the v12 fixture and matches the exported v13 schema.
     * 2. The production Room database (full migration chain) can write and read a location row.
     */
    @Test
    fun migrate_12_to_13_creates_mp_storage_locations() {
        val dbName = "migration_12_13_test"

        // 1. Create a v12 database (no location rows can pre-exist the table).
        helper.createDatabase(dbName, 12).close()

        // 2. Run MIGRATION_12_13 and validate against the exported v13 schema.
        helper.runMigrationsAndValidate(dbName, 13, true, MIGRATION_12_13).close()

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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
            )
            .build()

        try {
            migrated.openHelper.writableDatabase.execSQL(
                "INSERT INTO mp_storage_locations (userId, threadId, numreponse) " +
                    "VALUES ('xatrix', 9100200, 1980664234)",
            )
            migrated.openHelper.readableDatabase.query(
                "SELECT threadId, numreponse FROM mp_storage_locations WHERE userId = 'xatrix'",
            ).use { cursor ->
                assertTrue("the migrated table must accept and return a row", cursor.moveToFirst())
                assertEquals(9100200, cursor.getInt(0))
                assertEquals(1980664234, cursor.getInt(1))
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * #330 — v13 → v14 adds nullable `signature` to `posts`.
     *
     * Verifies:
     * 1. The migration runs cleanly against the v13 fixture and matches the exported v14 schema.
     * 2. Pre-existing post rows survive the migration.
     * 3. The new column defaults to NULL on old rows (recovered on the next live fetch).
     */
    @Test
    fun migrate_13_to_14_adds_nullable_signature_to_posts() {
        val dbName = "migration_13_14_test"

        // 1. Create a v13 database and insert a posts row that pre-dates `signature`.
        helper.createDatabase(dbName, 13).apply {
            execSQL(
                """INSERT INTO topic_pages (cat, post, page, title, totalPages, isFirstPostOwner,
                   numreponses, fetchedAt, authMode, subcat, canReply)
                   VALUES (23, 35395, 1, 'v13 cached topic', 10, 0, '[]', 1000, 'AUTHENTICATED', 550, 1)""",
            )
            execSQL(
                """INSERT INTO posts (cat, numreponse, post, author, date, content, avatarUrl,
                   isEditable, isOwnPost, quotedAuthors, postIndex, fetchedAt, authMode, quoteRef,
                   profileId, editedAt)
                   VALUES (23, 100, 35395, 'XaTriX', 1000,
                   '{"blocks":[]}', NULL, 0, 0, '[]', 1, 1000, 'AUTHENTICATED', NULL, NULL, NULL)""",
            )
            close()
        }

        // 2. Run MIGRATION_13_14 and validate against the v14 schema.
        helper.runMigrationsAndValidate(dbName, 14, true, MIGRATION_13_14).close()

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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
            )
            .build()

        try {
            migrated.openHelper.readableDatabase.query(
                "SELECT signature FROM posts WHERE cat = 23 AND numreponse = 100",
            ).use { cursor ->
                assertTrue("pre-v14 post row must survive MIGRATION_13_14", cursor.moveToFirst())
                assertTrue("signature must be NULL for pre-v14 rows", cursor.isNull(0))
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * v14 → v15 (#863) — `posts.citedCount` (compteur serveur « Message cité N fois »).
     *
     * Verifies:
     * 1. The migration runs cleanly against the v14 fixture and matches the exported v15 schema.
     * 2. Pre-existing post rows survive the migration.
     * 3. The new column defaults to NULL on old rows (recovered on the next live fetch).
     */
    @Test
    fun migrate_14_to_15_adds_nullable_citedCount_to_posts() {
        val dbName = "migration_14_15_test"

        // 1. Create a v14 database and insert a posts row that pre-dates `citedCount`.
        helper.createDatabase(dbName, 14).apply {
            execSQL(
                """INSERT INTO topic_pages (cat, post, page, title, totalPages, isFirstPostOwner,
                   numreponses, fetchedAt, authMode, subcat, canReply)
                   VALUES (23, 35395, 1, 'v14 cached topic', 10, 0, '[]', 1000, 'AUTHENTICATED', 550, 1)""",
            )
            execSQL(
                """INSERT INTO posts (cat, numreponse, post, author, date, content, avatarUrl,
                   isEditable, isOwnPost, quotedAuthors, postIndex, fetchedAt, authMode, quoteRef,
                   profileId, editedAt, signature)
                   VALUES (23, 100, 35395, 'XaTriX', 1000,
                   '{"blocks":[]}', NULL, 0, 0, '[]', 1, 1000, 'AUTHENTICATED', NULL, NULL, NULL, NULL)""",
            )
            close()
        }

        // 2. Run MIGRATION_14_15 and validate against the v15 schema.
        helper.runMigrationsAndValidate(dbName, 15, true, MIGRATION_14_15).close()

        // 3. Re-open through Room. The DB is ALREADY at v15 here — no migration re-runs ; this
        //    only proves the migrated file opens cleanly against the compiled schema (the DAO
        //    contract), the full-chain coverage lives in the 1→N tests above.
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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
            )
            .build()

        try {
            migrated.openHelper.readableDatabase.query(
                "SELECT citedCount FROM posts WHERE cat = 23 AND numreponse = 100",
            ).use { cursor ->
                assertTrue("pre-v15 post row must survive MIGRATION_14_15", cursor.moveToFirst())
                assertTrue("citedCount must be NULL for pre-v15 rows", cursor.isNull(0))
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * v15 → v16 (#638) — `flag_topics.lastPosition` + `flag_topics.postsPerPage`.
     *
     * Verifies:
     * 1. The migration runs cleanly against the v15 fixture and matches the exported v16 schema.
     * 2. A pre-existing flag row survives.
     * 3. `lastPosition` defaults to NULL on old rows — which `Flag.pageToOpen()` treats as
     *    « unknown » and degrades to the pre-#638 behaviour rather than guessing a page.
     * 4. `postsPerPage` backfills to HFR's 40, so a migrated row stays usable until the next fetch.
     */
    @Test
    fun migrate_15_to_16_adds_lastPosition_and_postsPerPage_to_flag_topics() {
        val dbName = "migration_15_16_test"

        // 1. Create a v15 database and insert a flag row that pre-dates both columns.
        helper.createDatabase(dbName, 15).apply {
            execSQL(
                """INSERT INTO flag_topics (userId, type, cat, subcat, topicId, title, totalPages,
                   replyCount, isFavorite, hasUnread, lastReadPage, lastPostReadId, firstPostAuthor,
                   lastReplyAuthor, lastReplyAt, fetchedAt, authMode)
                   VALUES ('54596', 'CYAN', 23, 550, 35395, 'v15 cached flag', 14, 540, 0, 1, 12,
                   2783256, 'XaTriX', 'qwazer', '2026-05-01 17:07', 1000, 'AUTHENTICATED')""",
            )
            close()
        }

        // 2. Run MIGRATION_15_16 and validate against the v16 schema.
        helper.runMigrationsAndValidate(dbName, 16, true, MIGRATION_15_16).close()

        // 3. Re-open through Room to prove the migrated file matches the compiled schema.
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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
            )
            .build()

        try {
            migrated.openHelper.readableDatabase.query(
                "SELECT lastPosition, postsPerPage FROM flag_topics WHERE topicId = 35395",
            ).use { cursor ->
                assertTrue("pre-v16 flag row must survive MIGRATION_15_16", cursor.moveToFirst())
                assertTrue("lastPosition must be NULL for pre-v16 rows", cursor.isNull(0))
                assertEquals("postsPerPage must backfill to HFR's 40", 40, cursor.getInt(1))
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * v16 → v17 (#1040/#1097) — dormant private-message content tables.
     *
     * The sentinel is deliberately inserted before the new tables exist. After raw schema
     * validation, Room reopens the migrated file before any MP row is written, proving that the
     * hand-written migration and the compiled schema describe the same database.
     */
    @Test
    fun migrate_16_to_17_preserves_existing_data_and_creates_private_message_tables() = runTest {
        val dbName = "migration_16_17_test"

        helper.createDatabase(dbName, 16).use { v16 ->
            v16.execSQL(
                """INSERT INTO flag_topics (userId, type, cat, subcat, topicId, title, totalPages,
                   replyCount, isFavorite, hasUnread, lastReadPage, lastPostReadId, firstPostAuthor,
                   lastReplyAuthor, lastReplyAt, fetchedAt, authMode, lastPosition, postsPerPage)
                   VALUES ('sentinel', 'CYAN', 23, 550, 35395, 'survives v17', 14, 540, 0, 1, 12,
                   2783256, 'author', 'reply', '2026-08-24 12:00', 1000, 'AUTHENTICATED', 480, 40)""",
            )
        }

        helper.runMigrationsAndValidate(dbName, 17, true, MIGRATION_16_17).use { v17 ->
            v17.query("SELECT title FROM flag_topics WHERE userId = 'sentinel'").use { cursor ->
                assertTrue("the v16 sentinel must survive", cursor.moveToFirst())
                assertEquals("survives v17", cursor.getString(0))
            }

            assertEquals(
                setOf(
                    "userId",
                    "threadId",
                    "page",
                    "subject",
                    "correspondent",
                    "totalPages",
                    "canReply",
                    "isMultiRecipient",
                    "fetchedAt",
                ),
                v17.columnNames("mp_thread_pages"),
            )
            assertEquals(
                setOf(
                    "userId",
                    "threadId",
                    "page",
                    "numreponse",
                    "ordinal",
                    "author",
                    "date",
                    "content",
                    "avatarUrl",
                    "isEditable",
                    "isOwnPost",
                    "quotedAuthors",
                    "postIndex",
                    "quoteRef",
                    "profileId",
                    "editedAt",
                    "citedCount",
                    "signature",
                ),
                v17.columnNames("mp_messages"),
            )
            v17.query("PRAGMA foreign_key_list(`mp_messages`)").use { cursor ->
                val parentIndex = cursor.getColumnIndexOrThrow("table")
                val onDeleteIndex = cursor.getColumnIndexOrThrow("on_delete")
                var compositeParts = 0
                while (cursor.moveToNext()) {
                    assertEquals("mp_thread_pages", cursor.getString(parentIndex))
                    assertEquals("CASCADE", cursor.getString(onDeleteIndex))
                    compositeParts++
                }
                assertEquals("the composite parent has three columns", 3, compositeParts)
            }
            v17.query("PRAGMA index_list(`mp_messages`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val names = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
                assertTrue(
                    names.contains("index_mp_messages_userId_threadId_page_ordinal"),
                )
            }
        }

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
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
            )
            .build()

        try {
            val page = PrivateMessageThreadPageEntity(
                userId = "alice",
                threadId = 42,
                page = 1,
                subject = "subject",
                correspondent = "correspondent",
                totalPages = 1,
                canReply = true,
                isMultiRecipient = false,
                fetchedAt = Instant.parse("2026-08-24T12:00:00Z"),
            )
            val message = PrivateMessageEntity(
                userId = "alice",
                threadId = 42,
                page = 1,
                numreponse = 7,
                ordinal = 0,
                author = "author",
                date = Instant.parse("2026-08-24T12:00:00Z"),
                content = PostContent(emptyList()),
                avatarUrl = null,
                isEditable = false,
                isOwnPost = false,
                quotedAuthors = emptyList(),
                postIndex = null,
                quoteRef = null,
                profileId = null,
                editedAt = null,
                citedCount = null,
                signature = null,
            )
            migrated.privateMessageContentDao().replacePage(page, listOf(message), maxPages = 5)

            val stored = migrated.privateMessageContentDao().getPage("alice", 42, 1)
            assertEquals("subject", stored?.page?.subject)
            assertEquals(listOf(7), stored?.messages?.map { it.numreponse })
        } finally {
            migrated.close()
        }
    }

    /** v17 → v18 (#1112) — both existing Post-backed caches backfill the marker to false. */
    @Test
    fun migrate_17_to_18_adds_moderation_marker_to_topic_and_private_message_posts() {
        val dbName = "migration_17_18_test"

        helper.createDatabase(dbName, 17).use { v17 ->
            v17.execSQL(
                """
                INSERT INTO posts (
                    cat, numreponse, post, author, date, content, isEditable, isOwnPost,
                    quotedAuthors, fetchedAt, authMode
                ) VALUES (
                    13, 75210915, 21512, 'Modération', 0, '{"blocks":[]}', 0, 0,
                    '[]', 0, 'ANONYMOUS'
                )
                """.trimIndent(),
            )
            v17.execSQL(
                """
                INSERT INTO mp_thread_pages (
                    userId, threadId, page, subject, correspondent, totalPages, canReply,
                    isMultiRecipient, fetchedAt
                ) VALUES ('alice', 42, 1, 'subject', 'correspondent', 1, 0, 0, 0)
                """.trimIndent(),
            )
            v17.execSQL(
                """
                INSERT INTO mp_messages (
                    userId, threadId, page, numreponse, ordinal, author, date, content,
                    isEditable, isOwnPost, quotedAuthors
                ) VALUES (
                    'alice', 42, 1, 75210915, 0, 'Modération', 0, '{"blocks":[]}',
                    0, 0, '[]'
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(dbName, 18, true, MIGRATION_17_18).use { v18 ->
            assertTrue("posts must expose the new column", "isModerationPost" in v18.columnNames("posts"))
            assertTrue(
                "mp_messages must expose the new column",
                "isModerationPost" in v18.columnNames("mp_messages"),
            )
            v18.query(
                "SELECT isModerationPost FROM posts WHERE numreponse = 75210915",
            ).use { cursor ->
                assertTrue("the pre-v18 topic post must survive", cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            v18.query(
                "SELECT isModerationPost FROM mp_messages WHERE numreponse = 75210915",
            ).use { cursor ->
                assertTrue("the pre-v18 private message must survive", cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    /** v18 → v19 (#340) — topic posts gain a nullable message tone; MP storage is unchanged. */
    @Test
    fun migrate_18_to_19_adds_nullable_msgIcon() {
        val dbName = "migration_18_19_test"

        helper.createDatabase(dbName, 18).use { v18 ->
            v18.execSQL(
                """
                INSERT INTO posts (
                    cat, numreponse, post, author, date, content, isEditable, isOwnPost,
                    quotedAuthors, fetchedAt, authMode, isModerationPost
                ) VALUES (
                    13, 2800250, 21512, 'Auteur', 0, '{"blocks":[]}', 0, 0,
                    '[]', 0, 'ANONYMOUS', 0
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(dbName, 19, true, MIGRATION_18_19).use { v19 ->
            assertTrue("posts must expose the new column", "msgIcon" in v19.columnNames("posts"))
            assertTrue(
                "mp_messages must stay unchanged",
                "msgIcon" !in v19.columnNames("mp_messages"),
            )
            v19.query(
                "SELECT msgIcon FROM posts WHERE numreponse = 2800250",
            ).use { cursor ->
                assertTrue("the pre-v19 topic post must survive", cursor.moveToFirst())
                assertTrue("the new column must backfill to NULL", cursor.isNull(0))
            }
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.columnNames(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

}
