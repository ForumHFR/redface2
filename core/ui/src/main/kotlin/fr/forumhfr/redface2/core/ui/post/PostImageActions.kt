package fr.forumhfr.redface2.core.ui.post

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * #831 — the post image a contextual action targets: the rendered [url], its BBCode alt text
 * ([description]) and, for a `[url=…][img]` linked image (#257), the wrapping [linkUrl].
 *
 * Built at gesture time from the frozen content models (`PostInline.InlineImage`,
 * `PostBlock.Image`, structural `MediaRun` gallery images since #957) — the on-disk `PostContent`
 * contract is never touched. A data class so gesture keys (`Modifier.pointerInput`) compare by value.
 */
data class PostImageTarget(
    val url: String,
    val description: String?,
    val linkUrl: String?,
)

/**
 * #831 — actions a hosting surface offers on post images. PR1 carries the single long-press
 * entry point (the contextual menu); the PR2 viewer adds its own member when it lands.
 */
class PostImageActions(
    val onLongPress: (PostImageTarget) -> Unit,
)

/**
 * #831 — CompositionLocal wiring [PostImageActions] into the two image render paths of
 * [PostRenderer] (inline `[img]` and block images, standalone or MediaRun). Same `staticCompositionLocalOf`
 * rationale as `LocalFoldLongQuotes` (DisplayMetrics.kt): the value changes only when the hosting
 * surface swaps its handler, so scoped reads without fine-grained tracking are the right trade.
 *
 * Defaults to `null` = the surface offers NO image actions. Since #958 (Lot 2, §5) `null` makes
 * every content image TOTALLY inert — no tap even on a linked image, no long-press, no interactive
 * role — on private messages, the editor BBCode preview and signatures (text links stay live).
 * Only the topic reading surface provides a non-null value (TopicScreen).
 */
val LocalPostImageActions = staticCompositionLocalOf<PostImageActions?> { null }

/**
 * #831 — whether a post image URL is eligible for the contextual image actions. The menu offers
 * « open in browser » / « save » / « copy », all of which assume a fetchable http(s) resource:
 * `data:` / `blob:` payloads, empty URLs and exotic schemes are refused up front (the long-press
 * handler is simply not installed), instead of failing later inside an Intent or the saver.
 *
 * Pure so the boundary is pinned by a JVM test ([PostImageUrlEligibilityTest]) without Compose.
 */
fun isEligiblePostImageUrl(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
}
