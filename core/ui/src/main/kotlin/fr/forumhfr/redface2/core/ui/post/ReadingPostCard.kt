package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.ui.theme.LocalDisplayMetrics
import fr.forumhfr.redface2.core.ui.theme.LocalEgoQuotePseudo
import fr.forumhfr.redface2.core.ui.theme.LocalIgnoreInlineColors
import fr.forumhfr.redface2.core.ui.theme.egoHighlightColors

/**
 * #1042 — shared rich-reading card used by feature-owned topic and private-message adapters.
 *
 * The card owns the stable reading body: density-aware gutters, selectable rich content, optional
 * signature and the body-scoped image/EgoQuote providers. [identity] stays mandatory because every
 * reading post has an author header, but its text, icons, tint and semantics belong to the host.
 * [badges] and [footer] are optional feature slots; their absence adds no placeholder node. The
 * body owns the card's bottom padding exactly when [footer] is absent.
 *
 * [onGoToCitedPost] and [onImageLongPress] are capabilities by presence. A null callback keeps the
 * corresponding renderer affordance inert. Selection is deliberately always enabled: changing
 * `PostRenderer.selectable` at runtime would insert or remove its `SelectionContainer`, recreate
 * the body subtree and discard nested `rememberSaveable` state (#946).
 */
@Composable
// LongParameterList: post + mandatory identity + presentation, then four independent host seams.
@Suppress("LongParameterList")
fun ReadingPostCard(
    post: Post,
    identity: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    presentation: ReadingPostCardPresentation = ReadingPostCardPresentation(),
    onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)? = null,
    onImageLongPress: ((PostImageTarget) -> Unit)? = null,
    badges: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    // #287 — structural spacing from the active density preset (Comfort = the historical rhythm).
    val m = LocalDisplayMetrics.current
    // #1042 — presence of the footer slot is the sole geometry gate: without it, the body closes
    // the card with m.cardBodyBottom; with it, the footer owns everything below the body.
    val hasFooter = footer != null
    val egoColors = egoHighlightColors()
    val postContainerColor = when {
        presentation.egoPostHighlighted -> egoColors.postContainer
        presentation.flat -> Color.Transparent
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    PostCardShell(
        modifier = modifier,
        flat = presentation.flat,
        containerColorOverride = postContainerColor,
        flatBottomEdge = presentation.flatBottomEdge,
        border = if (presentation.selected) {
            BorderStroke(width = 2.dp, color = MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        header = identity,
        badges = badges,
        body = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = m.cardBodyHorizontal,
                        top = m.cardBodyTop,
                        end = m.cardBodyHorizontal,
                        bottom = if (hasFooter) 0.dp else m.cardBodyBottom,
                    ),
                verticalArrangement = Arrangement.spacedBy(m.postSpacing),
            ) {
                val imageActions = remember(onImageLongPress) {
                    onImageLongPress?.let { PostImageActions(onLongPress = it) }
                }
                CompositionLocalProvider(
                    LocalPostImageActions provides imageActions,
                    LocalEgoQuotePseudo provides presentation.egoQuoteCanonicalPseudo,
                ) {
                    PostRenderer(
                        content = post.content,
                        // #946 — constant by construction: never swap SelectionContainer at runtime.
                        selectable = true,
                        onGoToCitedPost = onGoToCitedPost,
                    )
                }
                post.signature?.let { signature ->
                    if (presentation.showSignature) {
                        HorizontalDivider(
                            modifier = Modifier.testTag(READING_POST_SIGNATURE_DIVIDER_TAG),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        CompositionLocalProvider(LocalIgnoreInlineColors provides true) {
                            PostRenderer(
                                content = signature,
                                modifier = Modifier.alpha(READING_POST_SIGNATURE_ALPHA),
                            )
                        }
                    }
                }
            }
        },
        footer = footer,
    )
}

/**
 * #330 — tag of the divider that separates a post body from its signature (dividers have no
 * text/semantics to assert on; testTag is invisible to TalkBack). Public so feature-module tests can
 * pin its presence or absence on their own card rendering — same precedent as
 * [POST_CARD_SHELL_DIVIDER_TAG].
 */
const val READING_POST_SIGNATURE_DIVIDER_TAG = "ReadingPostSignatureDivider"

/** #330 — signatures keep the historical subdued opacity after extraction to the shared card. */
internal const val READING_POST_SIGNATURE_ALPHA = 0.7f
