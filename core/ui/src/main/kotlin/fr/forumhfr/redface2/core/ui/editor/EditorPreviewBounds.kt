package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.editor.EditorPreviewDirection

internal const val PREVIEW_DIVIDER_DP = 18f
internal const val PREVIEW_DIVIDER_TARGET_DP = 48f
internal const val PREVIEW_CLOSED_SOURCE_MIN_DP = 160f
// ED-62: provisional, calibrate on device. A width floor, independent of the vertical 160 dp stop.
internal const val PREVIEW_HORIZONTAL_MIN_DP = 120f

/** All dimensions use dp. Null means ED-62's full-field toggle fallback. */
internal data class EditorPreviewBounds(val minimum: Float, val maximum: Float, val available: Float) {
    fun restore(saved: Float?): Float = (saved?.takeIf { it.isFinite() } ?: (available / 2f))
        .coerceIn(minimum, maximum)
}

internal fun editorPreviewBounds(
    extent: Float,
    direction: EditorPreviewDirection,
    imeVisible: Boolean,
    measuredThreeLines: Float?,
    sourceExtra: Float = 0f,
): EditorPreviewBounds? {
    val available = extent - PREVIEW_DIVIDER_DP
    val horizontal = direction == EditorPreviewDirection.Horizontal
    // Wait for the actual field instead of making assumptions about font size or the label.
    val threeLines = measuredThreeLines?.takeIf { it.isFinite() && it > 0f } ?: return null
    val sourceMin = when {
        horizontal -> PREVIEW_HORIZONTAL_MIN_DP
        imeVisible -> threeLines
        else -> PREVIEW_CLOSED_SOURCE_MIN_DP
    } + sourceExtra
    val previewMin = if (horizontal) PREVIEW_HORIZONTAL_MIN_DP else threeLines
    return if (available >= sourceMin + previewMin) {
        EditorPreviewBounds(sourceMin, available - previewMin, available)
    } else null
}

/** A gesture owns its raw displacement. Only release may request hiding the IME, once. */
internal class EditorPreviewDrag(start: Float) {
    var raw = start
        private set
    private var finished = false

    fun move(delta: Float) { if (!finished) raw += delta }
    fun overdrag(bounds: EditorPreviewBounds): Float = raw - raw.coerceIn(bounds.minimum, bounds.maximum)
    fun release(bounds: EditorPreviewBounds, elastic: Boolean): Boolean {
        if (finished) return false
        finished = true
        return elastic && overdrag(bounds) != 0f
    }
}
