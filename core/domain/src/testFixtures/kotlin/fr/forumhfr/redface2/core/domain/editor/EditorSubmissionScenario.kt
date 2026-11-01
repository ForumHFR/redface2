package fr.forumhfr.redface2.core.domain.editor

import java.io.IOException

/** ED-170/172: shared test ports; feature adapters attach these to their real repositories and VM. */
class EditorSubmissionScenario {
    val store = VersionedEditorDraftStore()
    var postFailure: IOException? = null
    var recreate: (() -> Unit)? = null
    var replaceCollector: (() -> Unit)? = null

    fun recreateEditor() = checkNotNull(recreate) { "Adapter must implement recreation" }.invoke()
    fun rotateEditor() = checkNotNull(replaceCollector) { "Adapter must retain its VM on rotation" }.invoke()
}
