package fr.forumhfr.redface2.core.domain.write

/**
 * Truncates one HFR `[quotemsg=...]...[/quotemsg]` prefill while preserving the outer citation
 * shell. Malformed or composite input is returned unchanged: the caller only gets a shortened quote
 * when the single outer quote structure is unambiguous.
 */
fun truncateQuote(bbcode: String, limit: Int = DEFAULT_TRUNCATED_QUOTE_LIMIT): String {
    val quote = singleOuterQuote(bbcode)
    val truncated = quote
        ?.takeUnless { it.isAlreadyTruncated(limit.coerceAtLeast(0)) }
        ?.let { truncatedQuoteContent(it.content, limit.coerceAtLeast(0)) }

    return if (quote != null && truncated != null) {
        quote.renderContent(truncated + TRUNCATION_MARKER)
    } else {
        bbcode
    }
}

private fun singleOuterQuote(bbcode: String): QuoteShell? {
    val open = QuotedNumreponses.QUOTEMSG_OPEN_TAG.find(bbcode)
    val openRange = open?.range
    val close = openRange?.let { matchingQuoteClose(bbcode, it.last + 1) }

    return when {
        openRange == null || close == null -> null
        bbcode.substring(0, openRange.first).isNotBlank() -> null
        bbcode.substring(close.last + 1).isNotBlank() -> null
        else -> QuoteShell(
            leading = bbcode.substring(0, openRange.first),
            openTag = bbcode.substring(openRange.first, openRange.last + 1),
            content = bbcode.substring(openRange.last + 1, close.first),
            closeTag = bbcode.substring(close.first, close.last + 1),
            trailing = bbcode.substring(close.last + 1),
        )
    }
}

private fun matchingQuoteClose(bbcode: String, startIndex: Int): IntRange? {
    var depth = 1
    var index = startIndex
    var result: IntRange? = null
    while (index < bbcode.length && result == null) {
        val nextOpen = QuotedNumreponses.QUOTEMSG_OPEN_TAG.find(bbcode, index)
        val nextClose = QUOTEMSG_CLOSE_TAG.find(bbcode, index)
        val step = nextQuoteDepthStep(nextOpen, nextClose, depth, bbcode.length)
        depth = step.depth
        index = step.index
        result = step.match ?: result
    }
    return result
}

private fun nextQuoteDepthStep(
    nextOpen: MatchResult?,
    nextClose: MatchResult?,
    depth: Int,
    contentLength: Int,
): QuoteDepthStep {
    val openRange = nextOpen?.range
    val closeRange = nextClose?.range
    return when {
        closeRange == null -> QuoteDepthStep(depth = depth, index = contentLength, match = null)
        openRange != null && openRange.first < closeRange.first -> QuoteDepthStep(
            depth = depth + 1,
            index = openRange.last + 1,
            match = null,
        )

        depth == 1 -> QuoteDepthStep(depth = 0, index = closeRange.last + 1, match = closeRange)
        else -> QuoteDepthStep(depth = depth - 1, index = closeRange.last + 1, match = null)
    }
}

private fun truncatedQuoteContent(content: String, limit: Int): String? =
    if (visibleLength(content) <= limit) {
        null
    } else {
        val scan = scanQuoteContent(content, limit)
        val chosen = chooseCutBoundary(scan)
        content.substring(0, chosen.rawIndex).trimEnd() + closingTagsFor(chosen)
    }

private fun scanQuoteContent(content: String, limit: Int): TruncationScan {
    var state = initialScanState(limit)
    while (state.index < content.length && state.visible < limit) {
        state = nextScanState(content, state, limit)
    }
    return state.toTruncationScan()
}

private fun initialScanState(limit: Int): ScanState =
    ScanState(hardCut = START_BOUNDARY.takeIf { limit == 0 })

private fun nextScanState(content: String, state: ScanState, limit: Int): ScanState {
    val tag = bbcodeTagAt(content, state.index)
    val atom = nonSplittableAtomAt(content, state.index)
    return if (tag != null) {
        scanBbcodeTag(state, tag)
    } else if (atom != null) {
        scanNonSplittableAtom(state, atom, limit)
    } else {
        scanVisibleCodePoint(state, content, limit)
    }
}

private fun scanBbcodeTag(state: ScanState, tag: MatchResult): ScanState =
    state.copy(
        index = tag.range.last + 1,
        openTags = state.openTags.after(tag),
    )

private fun scanNonSplittableAtom(state: ScanState, atom: MatchResult, limit: Int): ScanState {
    val boundary = Boundary(rawIndex = state.index, openTags = state.openTags)
    val nextVisible = state.visible + atom.value.codePointCount(0, atom.value.length)
    return state.copy(
        index = atom.range.last + 1,
        visible = nextVisible,
        cuts = state.cuts + boundary,
        hardCut = boundary.takeIf { nextVisible >= limit } ?: state.hardCut,
    )
}

private fun scanVisibleCodePoint(state: ScanState, content: String, limit: Int): ScanState {
    val codePoint = content.codePointAt(state.index)
    val rawLength = Character.charCount(codePoint)
    val nextVisible = state.visible + 1
    val boundary = Boundary(rawIndex = state.index + rawLength, openTags = state.openTags)
    return state.copy(
        index = state.index + rawLength,
        visible = nextVisible,
        cuts = state.cuts + boundary,
        wordBoundaries = state.wordBoundaries.plusIfWordBoundary(codePoint, boundary),
        hardCut = boundary.takeIf { nextVisible >= limit } ?: state.hardCut,
    )
}

private fun List<String>.after(tag: MatchResult): List<String> {
    val name = tag.groupValues[2].lowercase()
    return if (tag.isClosingTag()) close(name) else this + name
}

private fun List<String>.close(name: String): List<String> {
    val last = lastIndexOf(name)
    return if (last == lastIndex) dropLast(1) else this
}

private fun List<Boundary>.plusIfWordBoundary(codePoint: Int, boundary: Boundary): List<Boundary> =
    if (isWordOrLineBoundary(codePoint)) this + boundary else this

private fun chooseCutBoundary(scan: TruncationScan): Boundary {
    val preferred = scan.preferredBoundary()
    return if (preferred.hasExcludedOpenTag()) {
        scan.safeBoundaryBefore(preferred)
    } else {
        preferred
    }
}

private fun TruncationScan.preferredBoundary(): Boundary =
    wordBoundaries.lastOrNull() ?: hardCut ?: START_BOUNDARY

private fun TruncationScan.safeBoundaryBefore(preferred: Boundary): Boundary =
    cuts.lastOrNull { boundary ->
        boundary.rawIndex <= preferred.rawIndex &&
            !boundary.hasExcludedOpenTag()
    } ?: START_BOUNDARY

private fun Boundary.hasExcludedOpenTag(): Boolean =
    openTags.any { it in EXCLUDED_IF_OPEN_TAGS }

private fun closingTagsFor(boundary: Boundary): String =
    boundary.openTags.asReversed().joinToString(separator = "") { tag -> "[/$tag]" }

private fun visibleLength(content: String): Int {
    var index = 0
    var length = 0
    while (index < content.length) {
        val tag = bbcodeTagAt(content, index)
        if (tag != null) {
            index = tag.range.last + 1
        } else {
            val codePoint = content.codePointAt(index)
            length += 1
            index += Character.charCount(codePoint)
        }
    }
    return length
}

private fun bbcodeTagAt(content: String, index: Int): MatchResult? =
    BBCODE_TAG.find(content, index)?.takeIf { it.range.first == index }

private fun nonSplittableAtomAt(content: String, index: Int): MatchResult? =
    NON_SPLITTABLE_ATOMS.firstNotNullOfOrNull { regex ->
        regex.find(content, index)?.takeIf { it.range.first == index }
    }

private fun MatchResult.isClosingTag(): Boolean =
    groupValues[1] == "/"

private fun isWordOrLineBoundary(codePoint: Int): Boolean =
    Character.isWhitespace(codePoint) || codePoint in WORD_BOUNDARY_PUNCTUATION

private data class QuoteShell(
    val leading: String,
    val openTag: String,
    val content: String,
    val closeTag: String,
    val trailing: String,
)

private fun QuoteShell.isAlreadyTruncated(limit: Int): Boolean =
    content.trimEnd().endsWith(TRUNCATION_MARKER) &&
        visibleLength(content) <= limit + TRUNCATION_MARKER.length

private fun QuoteShell.renderContent(content: String): String =
    leading + openTag + content + closeTag + trailing

private data class QuoteDepthStep(
    val depth: Int,
    val index: Int,
    val match: IntRange?,
)

private data class ScanState(
    val index: Int = 0,
    val visible: Int = 0,
    val openTags: List<String> = emptyList(),
    val cuts: List<Boundary> = emptyList(),
    val wordBoundaries: List<Boundary> = emptyList(),
    val hardCut: Boundary? = null,
)

private fun ScanState.toTruncationScan(): TruncationScan =
    TruncationScan(cuts = cuts, wordBoundaries = wordBoundaries, hardCut = hardCut)

private data class TruncationScan(
    val cuts: List<Boundary>,
    val wordBoundaries: List<Boundary>,
    val hardCut: Boundary?,
)

private data class Boundary(
    val rawIndex: Int,
    val openTags: List<String>,
)

private val START_BOUNDARY = Boundary(rawIndex = 0, openTags = emptyList())

const val DEFAULT_TRUNCATED_QUOTE_LIMIT = 300

private const val TRUNCATION_MARKER = " [...]"

private val QUOTEMSG_CLOSE_TAG = Regex("""\[/quotemsg]""", RegexOption.IGNORE_CASE)

private val BBCODE_TAG = Regex("""\[(/?)([#a-z][#a-z0-9]*)(?:=[^\]]*)?]""", RegexOption.IGNORE_CASE)

private val PERSONAL_SMILEY_ATOM = Regex("""\[:[^\]]+]""")

// Requires at least one letter or underscore so digit-only pairs (e.g. the `:30:` inside
// a clock time like `12:30:00`) are not mistaken for a builtin smiley code.
private val BUILTIN_SMILEY_ATOM = Regex(""":[a-z0-9_]*[a-z_][a-z0-9_]*:""", RegexOption.IGNORE_CASE)

// A raw `[` starts a BBCode tag in quote content and must remain available to [BBCODE_TAG].
private val URL_ATOM = Regex("""https?://[^\s\[]+""", RegexOption.IGNORE_CASE)

private val NON_SPLITTABLE_ATOMS = listOf(PERSONAL_SMILEY_ATOM, BUILTIN_SMILEY_ATOM, URL_ATOM)

private val EXCLUDED_IF_OPEN_TAGS = setOf("img", "quotemsg", "url")

private val WORD_BOUNDARY_PUNCTUATION = setOf(
    ','.code,
    ';'.code,
    '!'.code,
    '?'.code,
    ')'.code,
    ']'.code,
    '}'.code,
    '»'.code,
)
