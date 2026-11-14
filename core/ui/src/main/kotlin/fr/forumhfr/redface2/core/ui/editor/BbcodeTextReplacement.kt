package fr.forumhfr.redface2.core.ui.editor

/** A single replacement in the old text; [end] is exclusive and offsets are UTF-16 code units. */
internal data class BbcodeTextReplacement(val start: Int, val end: Int, val text: String)

/** Keeps the common prefix and non-overlapping suffix without splitting a surrogate pair. */
internal fun minimalReplacement(old: String, new: String): BbcodeTextReplacement {
    val shortest = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < shortest && old[prefix] == new[prefix]) prefix++
    if (splitsSurrogatePair(old, prefix) || splitsSurrogatePair(new, prefix)) prefix--

    var suffix = 0
    while (suffix < shortest - prefix && old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) {
        suffix++
    }
    if (splitsSurrogatePair(old, old.length - suffix) || splitsSurrogatePair(new, new.length - suffix)) {
        suffix--
    }

    return BbcodeTextReplacement(
        start = prefix,
        end = old.length - suffix,
        text = new.substring(prefix, new.length - suffix),
    )
}

private fun splitsSurrogatePair(text: String, offset: Int): Boolean =
    offset > 0 && offset < text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()
