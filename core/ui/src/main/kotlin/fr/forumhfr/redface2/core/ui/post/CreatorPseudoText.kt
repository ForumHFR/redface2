package fr.forumhfr.redface2.core.ui.post

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import fr.forumhfr.redface2.core.ui.theme.rememberCreatorPseudoBrush

/**
 * #221 — a Redface 2 creator's pseudo, painted with the animated gold sheen shared by the topic and
 * private-message reading surfaces. Kept as its own leaf composable so the per-frame shimmer
 * ([rememberCreatorPseudoBrush]) invalidates only this text node, never the enclosing (and expensive)
 * post card.
 *
 * The caller owns creator detection, interaction and accessibility semantics through [modifier]. In
 * particular, when this text fills the [PostIdentityHeader] `pseudo` slot, that modifier must put the
 * slot's single `heading()` on this real pseudo node. [colorOverride] disables the brush entirely:
 * a `TextStyle` brush wins over `color`, so merely copying a colour onto the animated style would
 * leave a moderation pseudo gold instead of white.
 */
@Composable
fun CreatorPseudoText(
    author: String,
    modifier: Modifier = Modifier,
    colorOverride: Color? = null,
) {
    val style = if (colorOverride != null) {
        MaterialTheme.typography.titleSmall.copy(color = colorOverride)
    } else {
        MaterialTheme.typography.titleSmall.copy(brush = rememberCreatorPseudoBrush())
    }
    Text(
        text = author,
        style = style,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.testTag(CREATOR_PSEUDO_TEXT_TAG),
    )
}

/** Stable integration-test hook for the shared gold-sheen pseudo leaf (#221). */
const val CREATOR_PSEUDO_TEXT_TAG = "CreatorPseudoText"
