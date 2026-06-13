package fr.forumhfr.redface2.core.data.editor

import fr.forumhfr.redface2.core.database.dao.EditorDraftDao
import fr.forumhfr.redface2.core.database.entities.EditorDraftEntity
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.model.AuthState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomEditorDraftStoreTest {

    private val dao = mockk<EditorDraftDao>(relaxed = true)
    private val fixedClock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)

    private fun store(authState: AuthState) = RoomEditorDraftStore(
        authRepository = mockk<AuthRepository> {
            every { observeAuthState() } returns MutableStateFlow(authState)
        },
        editorDraftDao = dao,
        clock = fixedClock,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `anonymous session reads null and never touches the DAO`() = runTest {
        val store = store(AuthState.Anonymous)

        assertNull(store.load(key = "reply:29:123456"))
        store.save(key = "reply:29:123456", draft = Draft(body = "wip"))
        store.delete(key = "reply:29:123456")

        coVerify(exactly = 0) { dao.get(any()) }
        coVerify(exactly = 0) { dao.upsert(any()) }
        coVerify(exactly = 0) { dao.deleteByKey(any()) }
    }

    @Test
    fun `save folds the lowercased owner into the row key and stamps the clock`() = runTest {
        val store = store(AuthState.Authenticated("XaTriX"))

        store.save(
            key = "reply:29:123456",
            draft = Draft(body = "wip body", isPrivate = false, updatedAt = 42L),
        )

        coVerify {
            dao.upsert(
                EditorDraftEntity(
                    draftKey = "xatrix|reply:29:123456",
                    ownerId = "xatrix",
                    body = "wip body",
                    subject = null,
                    recipients = null,
                    updatedAt = 1_700_000_000_000L,
                    isPrivate = false,
                ),
            )
        }
    }

    @Test
    fun `load round-trips the stored draft for the active account`() = runTest {
        coEvery { dao.get("xatrix|mpnew") } returns EditorDraftEntity(
            draftKey = "xatrix|mpnew",
            ownerId = "xatrix",
            body = "private body",
            subject = "private subject",
            recipients = "bob",
            updatedAt = 1_700_000_000_000L,
            isPrivate = true,
        )
        val store = store(AuthState.Authenticated("xatrix"))

        assertEquals(
            Draft(
                body = "private body",
                subject = "private subject",
                recipients = "bob",
                isPrivate = true,
                updatedAt = 1_700_000_000_000L,
            ),
            store.load(key = "mpnew"),
        )
    }

    @Test
    fun `drafts are isolated per account`() = runTest {
        // Account A wrote a row; account B must never read it.
        coEvery { dao.get("alice|reply:29:123456") } returns EditorDraftEntity(
            draftKey = "alice|reply:29:123456",
            ownerId = "alice",
            body = "alice wip",
            subject = null,
            recipients = null,
            updatedAt = 1_700_000_000_000L,
            isPrivate = false,
        )
        // Bob has no row under his own key — a real DAO returns null (the relaxed mock would
        // otherwise hand back a dummy entity, masking the isolation we are asserting).
        coEvery { dao.get("bob|reply:29:123456") } returns null
        val storeForBob = store(AuthState.Authenticated("bob"))

        // Bob's read targets "bob|..." — the DAO has no such row, so it returns null.
        assertNull(storeForBob.load(key = "reply:29:123456"))
        coVerify { dao.get("bob|reply:29:123456") }
    }

    @Test
    fun `delete targets the owner-scoped row key`() = runTest {
        val store = store(AuthState.Authenticated("xatrix"))

        store.delete(key = "edit:23:35395")

        coVerify { dao.deleteByKey("xatrix|edit:23:35395") }
    }
}
