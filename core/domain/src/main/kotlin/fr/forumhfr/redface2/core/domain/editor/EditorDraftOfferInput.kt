package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Text typed under an undecided restore offer, stored separately from the offered composition.
 * The account and offer fingerprint bind this scratch row; it contains no quote coordinates.
 * Ordinary draft retention applies. Clear by CAS after the decided composition is durable, never
 * delete (a delayed writer must not recreate an absent generation). Both topic surfaces read it.
 */
class EditorDraftOfferInput private constructor(
    private val session: EditorDraftSession,
    val body: String,
    isPending: Boolean,
) {
    var isPending: Boolean = isPending
        private set

    suspend fun save(body: String): StoreWriteResult = withContext(NonCancellable) {
        session.save(Draft(PENDING_PREFIX + body)).also {
            if (it is StoreWriteResult.Written) isPending = true
        }
    }

    suspend fun clear(): StoreWriteResult = withContext(NonCancellable) {
        session.save(Draft("")).also {
            if (it is StoreWriteResult.Written) isPending = false
        }
    }

    companion object {
        private const val PENDING_PREFIX = "pending\n"
        suspend fun load(store: EditorDraftStore, key: String, owner: String?, offered: String): EditorDraftOfferInput {
            val fingerprint = Draft(offered).restoreOfferFingerprint()
            val session = EditorDraftSession(store, EditorDraftKey.offerInput(key, fingerprint))
            val entry = session.load()
            if (session.owner != owner) {
                session.invalidate()
                throw IOException("Restore offer owner changed")
            }
            val raw = entry?.draft?.body.orEmpty()
            val pending = raw.startsWith(PENDING_PREFIX)
            return EditorDraftOfferInput(session, if (pending) raw.removePrefix(PENDING_PREFIX) else "", pending)
        }
    }
}
