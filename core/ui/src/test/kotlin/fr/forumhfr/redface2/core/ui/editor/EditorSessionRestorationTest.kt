package fr.forumhfr.redface2.core.ui.editor

import android.os.Bundle
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Attempt
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftEntry
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class EditorSessionRestorationTest {
    private val surface = EditorSessionSurface.MessageCompose
    private val owner = "tester"
    private val key = EditorDraftKey.mpCompose()
    private val recipients = EditorRecipientState(listOf("Alice", "Bob with spaces"), "half a pseudo")
    private val draft = Draft("body", "subject", recipients.encode(), isPrivate = true)

    @Test fun `fingerprint covers body title chips and buffer but ignores write timestamps`() {
        val saved = checkpoint()
        val variants = listOf(
            draft.copy(body = "changed"), draft.copy(subject = "changed"),
            draft.copy(recipients = recipients.copy(chips = listOf("Alice")).encode()),
            draft.copy(recipients = recipients.copy(buffer = "changed").encode()),
        )
        variants.forEach { changed ->
            assertEquals(EditorSessionRestore.Offer, reopen(saved).resolve(DraftEntry(changed, 1), owner, key))
        }
        assertEquals(EditorSessionRestore.Resume,
            reopen(saved).resolve(DraftEntry(draft.copy(updatedAt = 123), 1), owner, key))
        assertEquals(EditorSessionRestore.Offer, reopen(saved).resolve(DraftEntry(draft, 2), owner, key))
        assertEquals(EditorSessionRestore.None, reopen(saved).resolve(null, owner, key))
    }

    @Test fun `length delimited headers cannot collide and null fields normalize to empty`() {
        val a = Draft("a", "bc", "d")
        val b = Draft("ab", "c", "d")
        assertNotEquals(a.restoreOfferFingerprint(), b.restoreOfferFingerprint())
        val saved = checkpoint(Draft("body"))
        assertEquals(EditorSessionRestore.Resume,
            reopen(saved).resolve(DraftEntry(Draft("body", "", ""), 1), owner, key))
    }

    @Suppress("DEPRECATION") // Bundle.get reads the deliberately primitive-only historical metadata.
    @Test fun `post reads and writes the historical body only reference from a manual Bundle`() {
        val content = Draft("legacy post body")
        val version = 7L
        val postKey = EditorDraftKey.reply(23, 101)
        val expectedReference = "$version:" + content.restoreOfferFingerprint()
        val bundle = Bundle().apply {
            putString("postEditor.resumeOwner", owner)
            putString("postEditor.resumeKey", postKey)
            putString("postEditor.resumeVersion", expectedReference)
            putInt("postEditor.selectionStart", 8)
            putInt("postEditor.selectionEnd", 3)
            putInt("postEditor.scroll", 42)
        }
        val saved = SavedStateHandle(bundle.keySet().associateWith { bundle.get(it) })
        val session = EditorSessionRestoration(saved, EditorSessionSurface.Post)
        session.bind(owner, postKey)
        assertEquals(EditorSessionRestore.Resume, session.resolve(DraftEntry(content, version), owner, postKey))
        assertEquals(TextRange(8, 3), session.selection(content.body.length))
        assertEquals(42, session.scrollToRestore)
        assertTrue(session.capture(EditorSessionCheckpoint(content, version, TextRange(8, 3), 42), bodyKnown = true))
        assertEquals(expectedReference, saved.get<String>("postEditor.resumeVersion"))
    }

    @Test fun `every surface keeps independent metadata even with identical owner and context`() {
        val saved = SavedStateHandle()
        EditorSessionSurface.entries.forEachIndexed { index, target ->
            val content = draft.copy(subject = "title $index")
            val session = EditorSessionRestoration(saved, target)
            session.bind(owner, key)
            session.capture(EditorSessionCheckpoint(content, 1, TextRange(index), index), bodyKnown = true)
        }
        EditorSessionSurface.entries.forEachIndexed { index, target ->
            val session = reopen(saved, target)
            assertEquals(EditorSessionRestore.Resume,
                session.resolve(DraftEntry(draft.copy(subject = "title $index"), 1), owner, key))
            assertEquals(TextRange(index), session.selection(draft.body.length))
            assertEquals(index, session.scrollToRestore)
        }
    }

    @Test fun `routing keys and accounts cannot inherit a matching fingerprint`() {
        val saved = checkpoint()
        val keys = listOf(EditorDraftKey.newTopic(23), EditorDraftKey.editFirstPost(23, 101),
            EditorDraftKey.mpReply(42), EditorDraftKey.mpCompose())
        assertEquals(keys.size, keys.toSet().size)
        keys.filter { it != key }.forEach { foreign ->
            assertEquals(EditorSessionRestore.Offer, reopen(saved).resolve(DraftEntry(draft, 1), owner, foreign))
        }
        assertEquals(EditorSessionRestore.Offer, reopen(saved).resolve(DraftEntry(draft, 1), "other", key))
        assertEquals(EditorSessionRestore.None, reopen(saved).resolve(DraftEntry(draft, 1), null, key))
        val rebound = reopen(saved)
        rebound.bind("other", key)
        rebound.bind(owner, key)
        assertFalse(rebound.matches(DraftEntry(draft, 1), owner, key))
    }

    @Test fun `metadata contains no header or body content and restores bounded viewport only`() {
        val saved = SavedStateHandle()
        val session = EditorSessionRestoration(saved, surface)
        session.bind(owner, key)
        val huge = draft.copy(body = "secret ".repeat(10_000))
        session.capture(EditorSessionCheckpoint(huge, 1, TextRange(100, 2), -10), bodyKnown = true)
        assertTrue(saved.keys().mapNotNull { saved.get<Any?>(it) }.all { it is String || it is Int })
        val serialized = saved.keys().joinToString { saved.get<Any?>(it).toString() }
        listOf(huge.body, "subject", "Alice", "half a pseudo").forEach { assertFalse(serialized.contains(it)) }
        assertTrue(serialized.length < 1_000)
        val restored = reopen(saved)
        assertEquals(EditorSessionRestore.Resume, restored.resolve(DraftEntry(huge, 1), owner, key))
        assertEquals(TextRange(4, 2), restored.selection(4))
        assertEquals(0, restored.scrollToRestore)
        restored.scrollRestored()
        assertNull(restored.scrollToRestore)
    }

    @Test fun `failed GET and undecided offer never certify a placeholder blank`() {
        val saved = SavedStateHandle()
        val session = EditorSessionRestoration(saved, surface)
        session.bind(owner, key)
        val blank = Draft("")
        assertFalse(session.canCheckpoint(bodyKnown = false))
        assertFalse(session.canCheckpoint(bodyKnown = true, hasUndecidedOffer = true))
        assertFalse(session.capture(EditorSessionCheckpoint(blank, 1, TextRange.Zero, 0), bodyKnown = false))
        assertEquals(EditorSessionRestore.Offer, reopen(saved).resolve(DraftEntry(blank, 1), owner, key))
        assertTrue(session.capture(EditorSessionCheckpoint(blank, 2, TextRange.Zero, 0), bodyKnown = true))
        val restored = reopen(saved)
        assertEquals(EditorSessionRestore.Resume, restored.resolve(DraftEntry(blank, 2), owner, key))
        restored.decide(EditorSessionField.Body) // The surface applied this certified intentional blank.
        assertFalse(restored.canHydrate(EditorSessionField.Body, liveIsBlank = true))
    }

    @Test fun `late GET and initial recipient cannot replace restored or explicitly cleared fields`() {
        val session = reopen(checkpoint(Draft("", "", recipients.copy(buffer = "").encode())))
        val content = Draft("", "", recipients.copy(buffer = "").encode())
        assertEquals(EditorSessionRestore.Resume, session.resolve(DraftEntry(content, 1), owner, key))
        listOf(EditorSessionField.Body, EditorSessionField.Subject, EditorSessionField.Recipients).forEach { field ->
            session.decide(field) // The surface actually restored each field, including its blank value.
            assertFalse(session.canHydrate(field, liveIsBlank = true))
        }
        val fresh = EditorSessionRestoration(SavedStateHandle(), surface)
        assertTrue(fresh.canHydrate(EditorSessionField.Recipients, liveIsBlank = true))
        fresh.decide(EditorSessionField.Recipients)
        assertFalse(fresh.canHydrate(EditorSessionField.Recipients, liveIsBlank = true))
        assertFalse(fresh.canHydrate(EditorSessionField.Subject, liveIsBlank = false))
    }

    @Test fun `CAS failures revoke only their own checkpoint including wrong written revision`() {
        val failures = listOf(StoreWriteResult.Conflict, StoreWriteResult.OwnerChanged,
            StoreWriteResult.Failed(IOException("disk")), StoreWriteResult.Written(2))
        failures.forEach { failure ->
            val saved = checkpoint(subcategory = EditorSessionSubcategory(12))
            EditorSessionRestoration(saved, surface).completed(draft, 1, failure)
            val restored = reopen(saved)
            assertEquals(EditorSessionRestore.Offer, restored.resolve(DraftEntry(draft, 1), owner, key))
            assertEquals(13, restored.subcategoryForForm(setOf(12, 13), 13))
        }
        val saved = checkpoint()
        val session = EditorSessionRestoration(saved, surface)
        session.capture(EditorSessionCheckpoint(draft, 2, TextRange.Zero, 0), bodyKnown = true)
        session.completed(draft, 1, StoreWriteResult.Conflict)
        assertEquals(EditorSessionRestore.Resume, reopen(saved).resolve(DraftEntry(draft, 2), owner, key))
    }

    @Test fun `real session observer checkpoints a committed CAS before its dispatcher returns`() = runTest {
        val saved = SavedStateHandle()
        val store = VersionedEditorDraftStore()
        val drafts = EditorDraftSession(store, key)
        drafts.load()
        val session = EditorSessionRestoration(saved, surface)
        session.bind(drafts.owner, key)
        drafts.checkpointObserver = { content, version, result ->
            if (result == null) {
                session.capture(EditorSessionCheckpoint(content, version, TextRange(2), 7), bodyKnown = true)
            } else session.completed(content, version, result)
        }
        store.saveReturnGate = CompletableDeferred()
        val write = launch { assertEquals(StoreWriteResult.Written(1), drafts.save(draft)) }
        runCurrent()
        assertFalse(write.isCompleted)
        assertEquals(EditorSessionRestore.Resume, reopen(saved).resolve(store.entries[key], owner, key))
        store.saveReturnGate?.complete(Unit)
        write.join()
        // A competing writer wins the following CAS; its body must only be offered.
        store.entries[key] = DraftEntry(draft.copy(subject = "competitor"), 2)
        assertEquals(StoreWriteResult.Conflict, drafts.save(draft))
        assertEquals(EditorSessionRestore.Offer, reopen(saved).resolve(store.entries[key], owner, key))
    }

    @Test fun `published marker wins even over an exact checkpoint and retained content`() {
        val saved = checkpoint()
        val published = DraftEntry(draft, 1, attempt(AttemptStatus.PUBLISHED))
        assertEquals(EditorSessionRestore.None, reopen(saved).resolve(published, owner, key))
        assertFalse(reopen(saved).matches(published, owner, key))
    }

    @Test fun `unfinished attempts always return uncertain even with a stale checkpoint`() {
        val saved = checkpoint()
        listOf(AttemptStatus.IN_PROGRESS, AttemptStatus.UNCERTAIN).forEach { status ->
            val entry = DraftEntry(draft.copy(body = "possibly published"), 2, attempt(status))
            val session = reopen(saved)
            assertEquals(EditorSessionRestore.Uncertain, session.resolve(entry, owner, key))
            assertTrue(session.canHydrate(EditorSessionField.Body, liveIsBlank = true))
            session.decide(EditorSessionField.Body)
            assertFalse(session.canHydrate(EditorSessionField.Body, liveIsBlank = true))
            assertEquals(EditorSessionRestore.None, reopen(saved).resolve(entry, "other", key))
        }
    }

    @Test fun `resolution without an applied restore leaves every content field available for hydration`() {
        val content = Draft("", "", "")
        val entries = listOf(DraftEntry(content, 1), DraftEntry(content, 1, attempt(AttemptStatus.UNCERTAIN)))
        val fields = listOf(EditorSessionField.Body, EditorSessionField.Subject, EditorSessionField.Recipients)
        entries.forEach { entry ->
            val session = reopen(checkpoint(content))
            val expected = if (entry.attempt == null) EditorSessionRestore.Resume else EditorSessionRestore.Uncertain
            assertEquals(expected, session.resolve(entry, owner, key))
            // A surface blocked by submission has not applied anything and must not lock blank fields.
            fields.forEach { field ->
                assertTrue(session.canHydrate(field, liveIsBlank = true))
            }
            session.decide(EditorSessionField.Subject)
            assertFalse(session.canHydrate(EditorSessionField.Subject, liveIsBlank = true))
            assertTrue(session.canHydrate(EditorSessionField.Body, liveIsBlank = true))
            assertTrue(session.canHydrate(EditorSessionField.Recipients, liveIsBlank = true))
        }
    }

    @Test fun `subcategory resumes only when still offered then rejects later GET defaults`() {
        val saved = checkpoint(subcategory = EditorSessionSubcategory(12))
        val session = reopen(saved)
        assertEquals(EditorSessionRestore.Resume, session.resolve(DraftEntry(draft, 1), owner, key))
        assertEquals(12, session.subcategoryForForm(setOf(null, 12, 13), 13))
        assertEquals(12, session.subcategoryForForm(setOf(null, 13), 12))
        val removed = reopen(saved)
        removed.resolve(DraftEntry(draft, 1), owner, key)
        assertEquals(13, removed.subcategoryForForm(setOf(null, 13), 13))
        val userChoice = reopen(saved)
        userChoice.resolve(DraftEntry(draft, 1), owner, key)
        userChoice.decide(EditorSessionField.Subcategory)
        assertEquals(13, userChoice.subcategoryForForm(setOf(null, 12, 13), 13))
    }

    @Test fun `subcategory null has presence and an ordinary restore offer never restores it`() {
        val saved = checkpoint(subcategory = EditorSessionSubcategory(null))
        val session = reopen(saved)
        assertEquals(EditorSessionRestore.Resume, session.resolve(DraftEntry(draft, 1), owner, key))
        assertNull(session.subcategoryForForm(setOf(null, 13), 13))
        val absent = reopen(checkpoint())
        absent.resolve(DraftEntry(draft, 1), owner, key)
        assertEquals(13, absent.subcategoryForForm(setOf(null, 13), 13))
        val offer = reopen(checkpoint(subcategory = EditorSessionSubcategory(12)))
        assertEquals(EditorSessionRestore.Offer, offer.resolve(DraftEntry(draft, 2), owner, key))
        assertEquals(13, offer.subcategoryForForm(setOf(null, 12, 13), 13))
    }

    @Test fun `two deaths before form load preserve the pending subcategory including explicit null`() {
        listOf(EditorSessionSubcategory(12), EditorSessionSubcategory(null)).forEach { pending ->
            listOf(null, EditorSessionSubcategory(13)).forEach { liveDefault ->
                val saved = checkpoint(subcategory = pending)
                val firstResume = EditorSessionRestoration(saved, surface)
                firstResume.bind(owner, key)
                assertEquals(EditorSessionRestore.Resume, firstResume.resolve(DraftEntry(draft, 1), owner, key))
                // GET is still pending/failed: autosave or ON_STOP only knows the surface's default.
                assertTrue(firstResume.capture(
                    EditorSessionCheckpoint(draft, 2, TextRange.Zero, 0, liveDefault), bodyKnown = true,
                ))
                firstResume.completed(draft, 2, StoreWriteResult.Written(2))
                val secondResume = reopen(saved)
                assertEquals(EditorSessionRestore.Resume, secondResume.resolve(DraftEntry(draft, 2), owner, key))
                assertEquals(pending.id, secondResume.subcategoryForForm(setOf(null, 12, 13), 13))
            }
        }
    }

    @Test fun `reconciliation before form load keeps the resumed subcategory across another death`() {
        val saved = checkpoint(subcategory = EditorSessionSubcategory(12))
        val session = EditorSessionRestoration(saved, surface)
        assertEquals(EditorSessionRestore.Resume, session.resolve(DraftEntry(draft, 1), owner, key))
        assertEquals(EditorSessionRestore.Offer,
            session.resolve(DraftEntry(draft, 2), owner, key, allowResume = false))
        session.capture(EditorSessionCheckpoint(draft, 3, TextRange.Zero, 0, EditorSessionSubcategory(13)),
            bodyKnown = true)
        val next = reopen(saved)
        assertEquals(EditorSessionRestore.Resume, next.resolve(DraftEntry(draft, 3), owner, key))
        assertEquals(12, next.subcategoryForForm(setOf(null, 12, 13), 13))
    }

    @Test fun `a decided subcategory replaces the pending choice in the next checkpoint`() {
        val saved = checkpoint(subcategory = EditorSessionSubcategory(12))
        val session = EditorSessionRestoration(saved, surface)
        assertEquals(EditorSessionRestore.Resume, session.resolve(DraftEntry(draft, 1), owner, key))
        assertEquals(13, session.subcategoryForForm(setOf(13), 13)) // Previous choice was removed by HFR.
        session.capture(EditorSessionCheckpoint(draft, 2, TextRange.Zero, 0, EditorSessionSubcategory(13)),
            bodyKnown = true)
        val next = reopen(saved)
        assertEquals(EditorSessionRestore.Resume, next.resolve(DraftEntry(draft, 2), owner, key))
        assertEquals(13, next.subcategoryForForm(setOf(12, 13), 12))
    }

    @Test fun `a matching uncertain checkpoint retains subcategory but publication still suppresses it`() {
        val saved = checkpoint(subcategory = EditorSessionSubcategory(12))
        val entry = DraftEntry(draft, 1, attempt(AttemptStatus.IN_PROGRESS))
        val uncertain = reopen(saved)
        assertEquals(EditorSessionRestore.Uncertain, uncertain.resolve(entry, owner, key))
        assertEquals(12, uncertain.subcategoryForForm(setOf(12, 13), 13))
        val published = reopen(saved)
        assertEquals(EditorSessionRestore.None,
            published.resolve(entry.copy(attempt = attempt(AttemptStatus.PUBLISHED)), owner, key))
        assertEquals(13, published.subcategoryForForm(setOf(12, 13), 13))
    }

    @Test fun `subcategory is tied to the content version owner and key and cannot be changed independently`() {
        val saved = checkpoint(subcategory = EditorSessionSubcategory(12))
        listOf("other" to key, owner to EditorDraftKey.mpReply(42)).forEach { (account, context) ->
            val session = reopen(saved)
            assertEquals(EditorSessionRestore.Offer, session.resolve(DraftEntry(draft, 1), account, context))
            assertEquals(13, session.subcategoryForForm(setOf(12, 13), 13))
        }
        saved["messageComposeEditor.resumeSubcategory"] = 13
        assertEquals(EditorSessionRestore.Offer, reopen(saved).resolve(DraftEntry(draft, 1), owner, key))
        val reconcile = reopen(checkpoint())
        assertEquals(EditorSessionRestore.Offer,
            reconcile.resolve(DraftEntry(draft, 1), owner, key, allowResume = false))
        assertTrue(reconcile.canHydrate(EditorSessionField.Body, liveIsBlank = true))
    }

    private fun checkpoint(
        content: Draft = draft,
        subcategory: EditorSessionSubcategory? = null,
    ): SavedStateHandle = SavedStateHandle().also { saved ->
        val session = EditorSessionRestoration(saved, surface)
        session.bind(owner, key)
        session.capture(EditorSessionCheckpoint(content, 1, TextRange.Zero, 0, subcategory), bodyKnown = true)
        session.completed(content, 1, StoreWriteResult.Written(1))
    }

    private fun reopen(saved: SavedStateHandle, target: EditorSessionSurface = surface): EditorSessionRestoration =
        EditorSessionRestoration(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), target)

    private fun attempt(status: AttemptStatus) = Attempt(AttemptToken(owner, key, "attempt", 1), status)
}
