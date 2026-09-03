package fr.forumhfr.redface2.core.ui.post

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import fr.forumhfr.redface2.core.domain.preferences.PostHeaderEmphasis

/**
 * Author/date identity-band colours for reading post cards.
 *
 * SUBTLE keeps the historical `contentColorFor(secondaryContainer)` resolution so existing equal-role
 * schemes do not drift. VIVID uses the solid accent pair: `primary` / `onPrimary`.
 */
@Composable
fun postHeaderColors(emphasis: PostHeaderEmphasis): ReadingPostHeaderColors {
    val scheme = MaterialTheme.colorScheme
    val subtleContainer = scheme.secondaryContainer
    return postHeaderColors(
        emphasis = emphasis,
        scheme = scheme,
        subtleContentColor = contentColorFor(subtleContainer),
    )
}

/** Pure overload used by colour-contract tests. */
internal fun postHeaderColors(
    emphasis: PostHeaderEmphasis,
    scheme: ColorScheme,
    subtleContentColor: Color = scheme.onSecondaryContainer,
): ReadingPostHeaderColors = when (emphasis) {
    PostHeaderEmphasis.SUBTLE -> ReadingPostHeaderColors(
        containerColor = scheme.secondaryContainer,
        contentColor = subtleContentColor,
    )
    PostHeaderEmphasis.VIVID -> ReadingPostHeaderColors(
        containerColor = scheme.primary,
        contentColor = scheme.onPrimary,
    )
}
