package fr.forumhfr.redface2.core.ui

/**
 * Footer line of a [FlagItem] row, split in two segments (#325 follow-up). [start] is the
 * only segment allowed to truncate (ellipsis); [end] — typically the last-reply
 * timestamp — keeps its intrinsic width, pinned to the row's end. Either side may be
 * empty. Bundled as one value so callers stay under the detekt parameter-count threshold.
 */
data class FlagMetadata(val start: String = "", val end: String = "")
