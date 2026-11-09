package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState

enum class EditorBarMode { Normal, Format, Color, Image }
enum class EditorBarGesture { ToggleFormat, OpenColor, ColorChosen, OpenImage, ImageAction, Back, OpenSurface }

/** The saved mode survives masking by an event or an arm; neither mask rewrites it. */
data class EditorBarState(val mode: EditorBarMode = EditorBarMode.Normal, val arm: ArmToken? = null) {
    /** A displayed event masks tools without opening a level or changing the retained mode. */
    fun visibleMode(event: EditorEvent? = null): EditorBarMode =
        if (arm == null && event == null) mode else EditorBarMode.Normal

    /** Observe the existing controller; do not reproduce its arm timer or acceptance guards. */
    fun withSubmission(submission: EditorSubmissionState): EditorBarState =
        copy(arm = (submission as? EditorSubmissionState.Armed)?.token)
}

/**
 * ED-23/24/31. The host performs insertion through the existing BBCode API, outside this model.
 * [EditorBarGesture.OpenSurface] clears the local arm; a host without [EditorScaffold] must also
 * disarm its submission controller.
 */
fun reduceEditorBar(state: EditorBarState, gesture: EditorBarGesture): EditorBarState = when {
    gesture == EditorBarGesture.OpenSurface -> EditorBarState()
    state.arm != null -> state
    else -> state.copy(mode = reduceEditorBarMode(state.mode, gesture))
}

private fun reduceEditorBarMode(mode: EditorBarMode, gesture: EditorBarGesture): EditorBarMode =
    when (gesture) {
        EditorBarGesture.ToggleFormat -> if (mode == EditorBarMode.Normal) {
            EditorBarMode.Format
        } else {
            EditorBarMode.Normal
        }
        EditorBarGesture.OpenColor -> if (mode == EditorBarMode.Format) EditorBarMode.Color else mode
        EditorBarGesture.ColorChosen -> if (mode == EditorBarMode.Color) EditorBarMode.Format else mode
        EditorBarGesture.OpenImage -> EditorBarMode.Image
        EditorBarGesture.ImageAction, EditorBarGesture.OpenSurface -> EditorBarMode.Normal
        EditorBarGesture.Back -> if (mode == EditorBarMode.Color) EditorBarMode.Format else EditorBarMode.Normal
    }
