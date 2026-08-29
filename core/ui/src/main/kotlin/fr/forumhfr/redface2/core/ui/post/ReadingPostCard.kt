package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
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
 * reading post has an author header, but its text, icons, tint and semantics belong to the host. It
 * receives the resolved two-tone moderation header when the intrinsic post marker is present AND
 * the post is not an EgoPost, otherwise `null`, so each host can tint its opaque identity band
 * without duplicating detection and the band follows the card (#874 EgoPost wins on both).
 * [badges] and [footer] are optional feature slots; their absence adds no placeholder node. The
 * body owns the card's bottom padding exactly when [footer] is absent.
 *
 * [onGoToCitedPost] and [onImageLongPress] are capabilities by presence. A null callback keeps the
 * corresponding renderer affordance inert. Selection is deliberately always enabled: changing
 * `PostRenderer.selectable` at runtime would insert or remove its `SelectionContainer`, recreate
 * the body subtree and discard nested `rememberSaveable` state (#946).
 *
 * [mediaDiskCachePolicy] is host context, not a property that the global Coil loader can infer.
 * Public-topic callers keep the default; private-message callers disable disk reads and writes for
 * every media request made by the body and signature, including intrinsic-size probes (#1096).
 */
@Composable
// LongParameterList: post + mandatory identity + presentation, then independent host seams.
@Suppress("LongParameterList")
fun ReadingPostCard(
    post: Post,
    identity: @Composable (moderationHeaderColors: ReadingPostHeaderColors?) -> Unit,
    modifier: Modifier = Modifier,
    presentation: ReadingPostCardPresentation = ReadingPostCardPresentation(),
    mediaDiskCachePolicy: PostMediaDiskCachePolicy = PostMediaDiskCachePolicy.ENABLED,
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
    // Resolve the complete structural palette once. #874 is the stronger invariant on every
    // surface: a post that is both EgoPost and `messageModo` stays wholly blue/neutral, without a
    // red body, red identity override or red sub-surfaces.
    val moderationColors = moderationHighlightColors().takeIf {
        post.isModerationPost && !presentation.egoPostHighlighted
    }
    val moderationHeaderColors = moderationColors?.let {
        ReadingPostHeaderColors(
            containerColor = it.headerContainer,
            contentColor = it.onModeration,
        )
    }
    val postContainerColor = readingPostContainerColor(
        presentation = presentation,
        moderationColors = moderationColors,
        egoPostContainer = egoColors.postContainer,
        neutralContainer = MaterialTheme.colorScheme.surfaceContainer,
    )
    val border = readingPostBorder(
        presentation = presentation,
        selectionColor = MaterialTheme.colorScheme.primary,
    )
    PostCardShell(
        modifier = modifier,
        flat = presentation.flat,
        containerColorOverride = postContainerColor,
        flatBottomEdge = presentation.flatBottomEdge,
        border = border,
        header = { identity(moderationHeaderColors) },
        badges = badges?.let { badgesContent ->
            {
                ReadingPostContentProvider(moderationColors) {
                    badgesContent()
                }
            }
        },
        body = {
            ReadingPostContentProvider(moderationColors) {
                CompositionLocalProvider(LocalPostMediaDiskCachePolicy provides mediaDiskCachePolicy) {
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
                                val signatureColors = moderationColors?.let {
                                    ReadingContentColors(
                                        onBody = it.onModerationVariant,
                                        onBodyVariant = it.onModerationVariant,
                                        linkColor = it.linkColor,
                                    )
                                }
                                val signatureContentColor = signatureColors?.onBody
                                    ?: MaterialTheme.colorScheme.onSurface
                                CompositionLocalProvider(
                                    LocalIgnoreInlineColors provides true,
                                    LocalReadingContentColors provides signatureColors,
                                    LocalContentColor provides signatureContentColor,
                                ) {
                                    PostRenderer(
                                        content = signature,
                                        modifier = Modifier.alpha(
                                            readingPostSignatureAlpha(isModeration = moderationColors != null),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        footer = footer?.let { footerContent ->
            {
                ReadingPostContentProvider(moderationColors) {
                    footerContent()
                }
            }
        },
    )
}

/** Constant provider shape: only its nullable values change when the structural marker changes. */
@Composable
private fun ReadingPostContentProvider(
    moderationColors: ModerationHighlightColors?,
    content: @Composable () -> Unit,
) {
    val readingColors = moderationColors?.let {
        ReadingContentColors(
            onBody = it.onModeration,
            onBodyVariant = it.onModerationVariant,
            linkColor = it.linkColor,
        )
    }
    CompositionLocalProvider(
        LocalModerationHighlightColors provides moderationColors,
        LocalReadingContentColors provides readingColors,
        LocalContentColor provides (readingColors?.onBody ?: MaterialTheme.colorScheme.onSurface),
        content = content,
    )
}

/**
 * #330 — tag of the divider that separates a post body from its signature (dividers have no
 * text/semantics to assert on; testTag is invisible to TalkBack). Public so feature-module tests can
 * pin its presence or absence on their own card rendering — same precedent as
 * [POST_CARD_SHELL_DIVIDER_TAG].
 */
const val READING_POST_SIGNATURE_DIVIDER_TAG = "ReadingPostSignatureDivider"

/** #330 — normal signatures keep the historical subdued opacity. */
internal const val READING_POST_SIGNATURE_ALPHA = 0.7f

/** RF1 moderation uses an opaque light text variant; normal signatures retain their 0.7 alpha. */
internal fun readingPostSignatureAlpha(isModeration: Boolean): Float =
    if (isModeration) 1f else READING_POST_SIGNATURE_ALPHA

/**
 * #1042/#1112 — the single resolved card container: EgoPost (#874) wins on every surface, then the
 * moderation body, then the transparent full-width branch, otherwise the neutral card. Extracted as a
 * pure decision so [ReadingPostCard] stays under the cyclomatic-complexity budget.
 */
private fun readingPostContainerColor(
    presentation: ReadingPostCardPresentation,
    moderationColors: ModerationHighlightColors?,
    egoPostContainer: Color,
    neutralContainer: Color,
): Color = when {
    presentation.egoPostHighlighted -> egoPostContainer
    moderationColors != null -> moderationColors.bodyContainer
    presentation.flat -> Color.Transparent
    else -> neutralContainer
}

/**
 * #436/#1112 — the single resolved outline: only multi-quote selection draws one (2 dp primary),
 * otherwise none. #1112 dropped the moderation marker's 1 dp #C62828 outline: it was invisible on
 * the already-red card and redundant with the white « Modération » header. Pure decision so
 * [ReadingPostCard] stays under the cyclomatic-complexity budget.
 */
private fun readingPostBorder(
    presentation: ReadingPostCardPresentation,
    selectionColor: Color,
): BorderStroke? = if (presentation.selected) {
    BorderStroke(width = 2.dp, color = selectionColor)
} else {
    null
}
