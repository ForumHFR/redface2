package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange

/**
 * ED-86: owned by the VM, retained across rotation, never serialized into a process-death checkpoint.
 * The surface resets this memory when its owner/session changes (ED-83). EditorScaffold captures and
 * restores IME through [focus]; the host retains selection and the exactly-once deferred focus return.
 */
class EditorHostMemory<S : Any> {
    val focus = EditorSurfaceFocusMemory()
    var surface by mutableStateOf<S?>(null)
        internal set
    var detail by mutableStateOf<EditorEvent?>(null)
        internal set
    // Capture presentation on opening: the event may be acknowledged or its offer disappear later.
    internal var detailSheet by mutableStateOf<EditorSheet?>(null)
    var bar by mutableStateOf(EditorBarState())
        internal set
    internal var selection = TextRange.Zero
    internal var textBeforeSurface = ""
    var returnGeneration by mutableIntStateOf(0)
        internal set
    internal var returnedGeneration = 0

    fun reset() {
        surface = null
        detail = null
        detailSheet = null
        bar = EditorBarState()
        selection = TextRange.Zero
        textBeforeSurface = ""
        returnGeneration = 0
        returnedGeneration = 0
        focus.reset()
    }
}
