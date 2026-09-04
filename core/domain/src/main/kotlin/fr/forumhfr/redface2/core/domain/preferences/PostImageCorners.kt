package fr.forumhfr.redface2.core.domain.preferences

enum class PostImageCorners(val radiusDp: Int) {
    ROUNDED(radiusDp = 8),
    SOFT(radiusDp = 4),
    SQUARE(radiusDp = 0),
    ;

    companion object {
        val DEFAULT: PostImageCorners = ROUNDED
    }
}
