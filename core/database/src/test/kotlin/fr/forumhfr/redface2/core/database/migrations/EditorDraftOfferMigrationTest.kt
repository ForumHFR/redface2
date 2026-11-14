package fr.forumhfr.redface2.core.database.migrations

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.entities.EditorDraftOfferEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EditorDraftOfferMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), RedfaceDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `21 to 22 preserves drafts requests attempts and dates without manufacturing offers`() = runTest {
        val name = "draft-offers-21-to-22.db"
        val request = """{"version":1,"scope":{"type":"topic","cat":23,"post":42},"quotes":[""" +
            """{"numreponse":101,"page":1,"ref":null,"truncate":false}]}"""
        helper.createDatabase(name, 21).use { old ->
            listOf(null, "IN_PROGRESS", "UNCERTAIN", "PUBLISHED").forEachIndexed { index, state ->
                old.execSQL("""INSERT INTO editor_drafts (draftKey, ownerId, body, subject, recipients,
                    updatedAt, isPrivate, contentVersion, attemptId, attemptVersion, attemptState, pendingInlineQuoteRequest)
                    VALUES (?, 'alice', ?, 'title', 'bob', 1234, 1, 7, ?, ?, ?, ?)""",
                    arrayOf<Any?>("alice|mpreply:$index", "body $index", state?.let { "attempt-$index" },
                        state?.let { 5 }, state, request))
            }
        }
        // Room itself runs and validates the migration against generated entities. No handwritten 22.json.
        val database = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            RedfaceDatabase::class.java, name).addMigrations(MIGRATION_21_22).build()
        try {
            val dao = database.editorDraftDao()
            listOf(null, "IN_PROGRESS", "UNCERTAIN", "PUBLISHED").forEachIndexed { index, state ->
                val row = checkNotNull(dao.get("alice|mpreply:$index"))
                assertEquals("body $index", row.body)
                assertEquals("title", row.subject)
                assertEquals("bob", row.recipients)
                assertEquals(1234L, row.updatedAt)
                assertEquals(7L, row.contentVersion)
                assertEquals(state, row.attemptState)
                assertEquals(state?.let { "attempt-$index" }, row.attemptId)
                assertEquals(state?.let { 5L }, row.attemptVersion)
                assertEquals(request, row.pendingInlineQuoteRequest)
                assertTrue(row.isPrivate)
                assertNull(row.bodyProvenance)
                assertTrue(dao.offers("alice", "mpreply:$index").isEmpty())
            }
            assertEquals(22, database.openHelper.readableDatabase.version)
        } finally {
            database.close()
        }
    }

    /** Upgrade path of a 0.68/0.69 install: migrations keep the rows, the start-up purge must too. */
    @Test fun `20 to 22 then retention keeps 31-day-old unresolved sends and expires the rest`() = runTest {
        val name = "draft-retention-20-to-22.db"
        val day = 86_400_000L
        val now = 1_800_000_000_000L
        val stale = now - 31 * day
        val states = listOf(null, "IN_PROGRESS", "UNCERTAIN", "PUBLISHED")
        val keys = listOf(0, 1).flatMap { isPrivate -> states.indices.map { index -> isPrivate to index } }
        fun key(isPrivate: Int, index: Int) = "alice|${if (isPrivate == 1) "mpreply" else "reply:23"}:$index"
        helper.createDatabase(name, 20).use { old ->
            keys.forEach { (isPrivate, index) ->
                val state = states[index]
                old.execSQL("""INSERT INTO editor_drafts (draftKey, ownerId, body, subject, recipients,
                    updatedAt, isPrivate, contentVersion, attemptId, attemptVersion, attemptState)
                    VALUES (?, 'alice', ?, 'title', 'bob', ?, ?, 7, ?, ?, ?)""",
                    arrayOf<Any?>(key(isPrivate, index), "body $index", stale, isPrivate,
                        state?.let { "attempt-$index" }, state?.let { 5 }, state))
            }
        }
        val database = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            RedfaceDatabase::class.java, name).addMigrations(MIGRATION_20_21, MIGRATION_21_22).build()
        try {
            val dao = database.editorDraftDao()
            dao.insertOffer(EditorDraftOfferEntity("alice", "reply:23:1", "stale", 7, "offered", null, null,
                stale, false))
            dao.insertOffer(EditorDraftOfferEntity("alice", "reply:23:1", "fresh", 7, "offered", null, null,
                now, false))

            dao.deleteOlderThan(now - 30 * day)

            keys.forEach { (isPrivate, index) ->
                val state = states[index]
                val row = dao.get(key(isPrivate, index))
                if (state == "IN_PROGRESS" || state == "UNCERTAIN") {
                    checkNotNull(row) { "unresolved $state row was purged" }
                    assertEquals("body $index", row.body)
                    assertEquals("title", row.subject)
                    assertEquals("bob", row.recipients)
                    assertEquals(stale, row.updatedAt)
                    assertEquals("attempt-$index", row.attemptId)
                    assertEquals(5L, row.attemptVersion)
                    assertEquals(state, row.attemptState)
                    assertEquals(isPrivate == 1, row.isPrivate)
                } else {
                    assertNull("${state ?: "ordinary"} row outlived retention", row)
                }
            }
            assertEquals(listOf("fresh"), dao.offers("alice", "reply:23:1").map { it.offerId })
        } finally {
            database.close()
        }
    }
}
