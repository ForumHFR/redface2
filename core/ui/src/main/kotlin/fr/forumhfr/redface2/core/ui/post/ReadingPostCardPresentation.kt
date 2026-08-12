package fr.forumhfr.redface2.core.ui.post

import androidx.compose.runtime.Immutable

/**
 * Visual state of a [ReadingPostCard]. Defaults describe the neutral reading surface: an inset
 * card with no signature, own-post highlight, quote marker or selection outline. Hosts opt into
 * each presentation detail while capabilities remain modelled by nullable callbacks and slots on
 * [ReadingPostCard]. [Immutable] keeps the bundle stable when passed through Compose.
 *
 * Lives in its own file (like [PostCardShellFlatBottomEdge]) so detekt's `MatchingDeclarationName`
 * stays satisfied: `ReadingPostCard.kt` holds the composable, this file holds its state bundle.
 */
@Immutable
data class ReadingPostCardPresentation(
    val showSignature: Boolean = false,
    val flat: Boolean = false,
    val flatBottomEdge: PostCardShellFlatBottomEdge = PostCardShellFlatBottomEdge.HAIRLINE,
    val egoQuoteCanonicalPseudo: String? = null,
    val egoPostHighlighted: Boolean = false,
    val selected: Boolean = false,
)
