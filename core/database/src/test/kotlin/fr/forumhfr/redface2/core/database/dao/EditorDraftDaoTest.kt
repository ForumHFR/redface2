package fr.forumhfr.redface2.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.entities.EditorDraftEntity
import fr.forumhfr.redface2.core.database.entities.EditorDraftOfferEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-DB coverage of the privacy-critical DELETE clauses on [EditorDraftDao] (#405). Unlike the
 * store/CacheInvalidator tests (which only `coVerify` the call on a mock), these exercise the SQL
 * EFFECT against an in-memory Room database, so a regression that drops or inverts the
 * `AND isPrivate = 1` filter — or flips the TTL comparison — fails loudly here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EditorDraftDaoTest {

    private lateinit var database: RedfaceDatabase
    private lateinit var dao: EditorDraftDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            RedfaceDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.editorDraftDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `upsert then get round-trips every column`() = runTest {
        dao.upsert(
            draft(
                draftKey = "alice|mpnew",
                ownerId = "alice",
                body = "secret body",
                subject = "secret subject",
                recipients = "bob",
                updatedAt = 1_700_000_000_000L,
                isPrivate = true,
            ).copy(pendingInlineQuoteRequest = "coordinates"),
        )

        val stored = dao.get("alice|mpnew")
        assertNotNull(stored)
        assertEquals("secret body", stored!!.body)
        assertEquals("secret subject", stored.subject)
        assertEquals("bob", stored.recipients)
        assertEquals(true, stored.isPrivate)
        assertEquals("coordinates", stored.pendingInlineQuoteRequest)
    }

    @Test
    fun `deletePrivateForUser removes ONLY the targeted owner's private drafts`() = runTest {
        dao.upsert(draft("alice|mpreply:1", ownerId = "alice", isPrivate = true))
        // A public draft of the SAME owner must survive logout — only MP drafts are sensitive.
        dao.upsert(draft("alice|reply:23:1", ownerId = "alice", isPrivate = false))
        // Another account's private draft must never be touched by alice's logout.
        dao.upsert(draft("bob|mpreply:2", ownerId = "bob", isPrivate = true))

        dao.deletePrivateForUser("alice")

        // Alice's MP draft is gone …
        assertNull(dao.get("alice|mpreply:1"))
        // … her public post draft survives (bounded only by the TTL purge) …
        assertNotNull(dao.get("alice|reply:23:1"))
        // … and Bob's MP draft is untouched.
        assertNotNull(dao.get("bob|mpreply:2"))
    }

    @Test
    fun `private purge erases content but preserves every durable attempt state`() = runTest {
        for (state in listOf("IN_PROGRESS", "UNCERTAIN", "PUBLISHED")) {
            val original = draft(
                "alice|$state", "alice", body = "private body", subject = "private subject",
                recipients = "recipient", isPrivate = true,
            ).copy(contentVersion = 7, attemptId = "attempt", attemptVersion = 4, attemptState = state,
                pendingInlineQuoteRequest = "coordinates")
            dao.upsert(original)
            val otherOwner = original.copy(draftKey = "bob|$state", ownerId = "bob")
            val publicDraft = original.copy(draftKey = "alice|public:$state", isPrivate = false)
            dao.upsert(otherOwner)
            dao.upsert(publicDraft)

            dao.deletePrivateForUser("alice")

            val erased = original.copy(body = "", subject = null, recipients = null, pendingInlineQuoteRequest = null)
            assertEquals(erased, dao.get(original.draftKey))
            assertEquals(otherOwner, dao.get(otherOwner.draftKey))
            assertEquals(publicDraft, dao.get(publicDraft.draftKey))
            // The transaction remains safe and idempotent on a repeated auth emission.
            dao.deletePrivateForUser("alice")
            assertEquals(erased, dao.get(original.draftKey))
        }
    }

    @Test
    fun `deleteOlderThan is a strict less-than at the cutoff boundary`() = runTest {
        val cutoff = 1_700_000_000_000L
        dao.upsert(draft("alice|old", ownerId = "alice", updatedAt = cutoff - 1)
            .copy(pendingInlineQuoteRequest = "coordinates"))
        dao.upsert(draft("alice|exact", ownerId = "alice", updatedAt = cutoff)
            .copy(pendingInlineQuoteRequest = "coordinates"))
        dao.upsert(draft("alice|fresh", ownerId = "alice", updatedAt = cutoff + 1)
            .copy(pendingInlineQuoteRequest = "coordinates"))

        dao.deleteOlderThan(cutoff)

        // Strictly older than the cutoff is purged …
        assertNull(dao.get("alice|old"))
        // … the row exactly at the cutoff is KEPT (strict `<`, no off-by-one) …
        assertNotNull(dao.get("alice|exact"))
        assertEquals("coordinates", dao.get("alice|exact")?.pendingInlineQuoteRequest)
        // … and anything newer is obviously kept.
        assertNotNull(dao.get("alice|fresh"))
    }

    @Test
    fun `deleteOlderThan never ages out an unresolved send, public or private`() = runTest {
        val day = 86_400_000L
        val now = 1_700_000_000_000L
        val stale = now - 31 * day
        val states = listOf(null, "IN_PROGRESS", "UNCERTAIN", "PUBLISHED")
        val rows = listOf(false, true).flatMap { isPrivate ->
            states.map { state ->
                draft("alice|${if (isPrivate) "mpreply" else "reply:23"}:${state ?: "ORDINARY"}", ownerId = "alice",
                    body = "typed text", subject = "subject", recipients = "bob", updatedAt = stale,
                    isPrivate = isPrivate,
                ).copy(contentVersion = 7, attemptId = state?.let { "attempt-$it" }, attemptVersion = state?.let { 7L },
                    attemptState = state, pendingInlineQuoteRequest = "coordinates", bodyProvenance = "provenance")
            }
        }
        rows.forEach { dao.upsert(it) }
        listOf(false, true).forEach { isPrivate ->
            dao.insertOffer(offer("stale-$isPrivate", updatedAt = stale, isPrivate = isPrivate))
            dao.insertOffer(offer("fresh-$isPrivate", updatedAt = now, isPrivate = isPrivate))
        }

        dao.deleteOlderThan(now - 30 * day)

        rows.forEach { row ->
            val expected = row.takeIf { it.attemptState == "IN_PROGRESS" || it.attemptState == "UNCERTAIN" }
            // Whole-row equality: text, headers, attempt token/version/state, quote request and date.
            assertEquals(row.draftKey, expected, dao.get(row.draftKey))
        }
        // Offers keep their own retention, attached or not to a surviving row.
        assertEquals(listOf("fresh-false", "fresh-true"), dao.offers("alice", "ctx").map { it.offerId }.sorted())
    }

    private fun offer(offerId: String, updatedAt: Long, isPrivate: Boolean) = EditorDraftOfferEntity(
        ownerId = "alice", contextKey = "ctx", offerId = offerId, sourceVersion = 1, body = "offered",
        subject = null, recipients = null, updatedAt = updatedAt, isPrivate = isPrivate,
    )

    @Test
    fun `deleteByKey removes a single row`() = runTest {
        dao.upsert(draft("alice|edit:23:99", ownerId = "alice"))
        dao.upsert(draft("alice|reply:23:1", ownerId = "alice"))

        dao.deleteByKey("alice|edit:23:99")

        assertNull(dao.get("alice|edit:23:99"))
        assertNotNull(dao.get("alice|reply:23:1"))
    }

    // One parameter per entity column — a faithful test factory, not a design smell.
    @Suppress("LongParameterList")
    private fun draft(
        draftKey: String,
        ownerId: String,
        body: String = "body",
        subject: String? = null,
        recipients: String? = null,
        updatedAt: Long = 1_700_000_000_000L,
        isPrivate: Boolean = false,
    ) = EditorDraftEntity(
        draftKey = draftKey,
        ownerId = ownerId,
        body = body,
        subject = subject,
        recipients = recipients,
        updatedAt = updatedAt,
        isPrivate = isPrivate,
    )
}
