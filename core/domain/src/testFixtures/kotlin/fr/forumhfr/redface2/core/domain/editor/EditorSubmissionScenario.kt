package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** ED-170/172: shared test ports; feature adapters attach these to their real repositories and VM. */
class EditorSubmissionScenario {
    val store = VersionedEditorDraftStore()
    var postFailure: IOException? = null
    var postRejection: ReplyFailureReason? = null
    var recreate: (() -> Unit)? = null
    var replaceCollector: (() -> Unit)? = null
    var changeAccount: (() -> Unit)? = null
    var ignorePostCancellation = false
    var returnedPosts = 0
        private set

    suspend fun awaitPost(gate: CompletableDeferred<Unit>) {
        if (ignorePostCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
        returnedPosts++
    }

    fun recreateEditor() = checkNotNull(recreate) { "Adapter must implement recreation" }.invoke()
    fun rotateEditor() = checkNotNull(replaceCollector) { "Adapter must retain its VM on rotation" }.invoke()
    fun switchAccount() = checkNotNull(changeAccount) { "Adapter must expose its real auth flow" }.invoke()
}
