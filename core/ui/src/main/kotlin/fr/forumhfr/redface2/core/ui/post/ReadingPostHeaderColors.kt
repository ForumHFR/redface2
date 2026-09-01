package fr.forumhfr.redface2.core.ui.post

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Optional two-tone override forwarded by [ReadingPostCard] to its feature-owned identity band. */
@Immutable
data class ReadingPostHeaderColors(
    val containerColor: Color,
    val contentColor: Color,
)
