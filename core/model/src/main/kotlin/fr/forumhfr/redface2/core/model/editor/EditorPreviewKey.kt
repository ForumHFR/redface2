package fr.forumhfr.redface2.core.model.editor

enum class EditorPreviewDirection { Vertical, Horizontal }

/** Width of the current window, never the physical display or the IME-sized editor viewport. */
enum class EditorPreviewWidthClass {
    Compact, Medium, Expanded;

    companion object {
        fun fromWidthDp(width: Float): EditorPreviewWidthClass = when {
            width < 600f -> Compact
            width < 840f -> Medium
            else -> Expanded
        }
    }
}

data class EditorPreviewKey(val direction: EditorPreviewDirection, val widthClass: EditorPreviewWidthClass) {
    /** Explicit stable names: renaming an enum must not silently reset a preference. */
    val storageKey: String get() {
        val axis = when (direction) {
            EditorPreviewDirection.Vertical -> "vertical"
            EditorPreviewDirection.Horizontal -> "horizontal"
        }
        val width = when (widthClass) {
            EditorPreviewWidthClass.Compact -> "compact"
            EditorPreviewWidthClass.Medium -> "medium"
            EditorPreviewWidthClass.Expanded -> "expanded"
        }
        return "editor_preview_source_dp_${axis}_$width"
    }
}
