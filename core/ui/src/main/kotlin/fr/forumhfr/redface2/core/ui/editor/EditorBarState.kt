package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState

enum class EditorBarMode { Normal, Format, Color, Image }
enum class EditorBarGesture { ToggleFormat, OpenColor, ColorChosen, OpenImage, ImageAction, Back, OpenSurface }

/** The saved mode survives masking by an event or an arm; neither mask rewrites it. */
data class EditorBarState(val mode: EditorBarMode = EditorBarMode.Normal, val arm: ArmToken? = null) {
    val visibleMode: EditorBarMode get() = if (arm == null) mode else EditorBarMode.Normal

    /** Observe the existing controller; do not reproduce its arm timer or acceptance guards. */
    fun withSubmission(submission: EditorSubmissionState): EditorBarState =
        copy(arm = (submission as? EditorSubmissionState.Armed)?.token)
}

/** ED-23/24/31. The host performs insertion through the existing BBCode API, outside this model. */
fun reduceEditorBar(state: EditorBarState, gesture: EditorBarGesture): EditorBarState {
    if (state.arm != null && gesture != EditorBarGesture.OpenSurface) return state
    val mode = when (gesture) {
        EditorBarGesture.ToggleFormat -> if (state.mode == EditorBarMode.Normal) {
            EditorBarMode.Format
        } else {
            EditorBarMode.Normal
        }
        EditorBarGesture.OpenColor -> if (state.mode == EditorBarMode.Format) EditorBarMode.Color else state.mode
        EditorBarGesture.ColorChosen -> if (state.mode == EditorBarMode.Color) EditorBarMode.Format else state.mode
        EditorBarGesture.OpenImage -> EditorBarMode.Image
        EditorBarGesture.ImageAction, EditorBarGesture.OpenSurface -> EditorBarMode.Normal
        EditorBarGesture.Back -> if (state.mode == EditorBarMode.Color) EditorBarMode.Format else EditorBarMode.Normal
    }
    return state.copy(mode = mode)
}
