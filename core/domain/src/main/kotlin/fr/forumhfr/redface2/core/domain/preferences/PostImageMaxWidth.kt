package fr.forumhfr.redface2.core.domain.preferences

enum class PostImageMaxWidth(
    val percent: Int,
    val fraction: Float,
) {
    P90(percent = 90, fraction = 0.9f),
    P95(percent = 95, fraction = 0.95f),
    P99(percent = 99, fraction = 0.99f),
    P100(percent = 100, fraction = 1f),
    ;

    companion object {
        val DEFAULT: PostImageMaxWidth = P95
    }
}
