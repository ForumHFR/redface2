package fr.forumhfr.redface2.core.ui.post

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Semantic text colours shared by every rich-reading surface nested in a post. */
@Immutable
data class ReadingContentColors(
    val onBody: Color,
    val onBodyVariant: Color,
    val linkColor: Color,
)

/** Null outside a structurally highlighted reading post, including editor previews. */
val LocalReadingContentColors = staticCompositionLocalOf<ReadingContentColors?> { null }

/**
 * Returns the active structural reading colours, or the exact historical Material roles.
 *
 * Consumers can route every text role through this accessor without branching. Code that needs to
 * distinguish moderation from a normal post (for example `[color]` neutralisation) must inspect
 * [LocalReadingContentColors] directly instead of comparing colour values.
 */
@Composable
fun readingContentColors(): ReadingContentColors {
    LocalReadingContentColors.current?.let { return it }
    val scheme = MaterialTheme.colorScheme
    return remember(scheme.onSurface, scheme.onSurfaceVariant, scheme.primary) {
        ReadingContentColors(
            onBody = scheme.onSurface,
            onBodyVariant = scheme.onSurfaceVariant,
            linkColor = scheme.primary,
        )
    }
}
