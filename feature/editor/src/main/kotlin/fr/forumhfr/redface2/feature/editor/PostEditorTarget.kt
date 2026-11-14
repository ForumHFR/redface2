package fr.forumhfr.redface2.feature.editor

/** Routing identity only; a private editor never invents a numeric public category. */
sealed interface PostEditorTarget {
    data object Public : PostEditorTarget
    data class PrivateMessage(val threadId: Int) : PostEditorTarget {
        init { require(threadId > 0) }
        override fun toString(): String = "PrivateMessage([redacted])"
    }
}
