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
    /** #1417: the tab of the open Options sheet; every opening and closing starts again on the options. */
    var optionsTab by mutableStateOf(EditorOptionsTab.Options)
        internal set
    /** Options callbacks belong to one opening; closing and reset invalidate without reusing its identity. */
    internal var optionsGeneration by mutableIntStateOf(0)
    var detail by mutableStateOf<EditorEvent?>(null)
        internal set
    var bar by mutableStateOf(EditorBarState())
        internal set
    internal var selection = TextRange.Zero
    internal var textBeforeSurface = ""
    var returnGeneration by mutableIntStateOf(0)
        internal set
    internal var returnedGeneration = 0

    fun reset() {
        surface = null
        optionsTab = EditorOptionsTab.Options
        optionsGeneration++
        detail = null
        bar = EditorBarState()
        selection = TextRange.Zero
        textBeforeSurface = ""
        returnGeneration = 0
        returnedGeneration = 0
        focus.reset()
    }
}
