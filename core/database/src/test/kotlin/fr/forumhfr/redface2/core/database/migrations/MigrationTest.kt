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
 * Robolectric-driven migration test for `MIGRATION_1_2`. Creates a v1 database with
 * the actual v1 schema (no `authMode`, no `flag_topics`), inserts a representative
 * row, runs the migration, then verifies the new shape on the same disk file by
 * opening it with the full Room database and reading back through the DAOs.
 *
 * Why this exists: the v1 → v2 migration is hand-written DDL. Without this test a
 * typo in `MIGRATION_1_2` (missing column, wrong index name, wrong default) would
 * only crash on a real upgrade-in-place install, where the diagnostic loop is days
 * long. The test takes seconds.
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
    fun migrate_1_to_2_adds_authMode_with_AUTHENTICATED_default_and_creates_flag_topics() {
        val dbName = "redface-test-migration.db"

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

        // 2. Run the migration against the v1 fixture DB. `validateDroppedTables = true`
        //    asserts every Room-known table matches the v2 schema exactly — so a typo
        //    in MIGRATION_1_2 would surface here (column type, NOT NULL flag, index).
        helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2).close()

        // 3. Open the upgraded DB via Room (forbidding any further migration, since
        //    we already migrated to the latest). Read back through the DAOs and
        //    confirm the shape. The migration backfilled `authMode = AUTHENTICATED`
        //    on the pre-existing row, and the new `flag_topics` table is callable.
        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RedfaceDatabase::class.java,
            dbName,
        )
            .allowMainThreadQueries()
            .addMigrations(MIGRATION_1_2)
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

            // The new flag_topics table is created with the v2 shape: queries on the
            // REST-aligned columns (no `views`, `lastPostReadId` instead of
            // `firstUnreadPostId`) compile against the migrated DB. If anyone
            // re-introduced a legacy column, this query would throw.
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
}
