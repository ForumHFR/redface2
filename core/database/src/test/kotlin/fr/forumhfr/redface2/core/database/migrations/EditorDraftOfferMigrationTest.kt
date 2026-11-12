package fr.forumhfr.redface2.core.database.migrations

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import fr.forumhfr.redface2.core.database.RedfaceDatabase
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
}
