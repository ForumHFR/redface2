package fr.forumhfr.redface2.core.parser.smiley

import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS

/** Exact typed tokens, independent of the preview's deliberately incomplete smiley expansion. */
object SmileyUsageExtractor {
    private val excludedTags = setOf("quote", "quotemsg", "code", "cpp", "fixed", "img")
    private val urlMarkupTags = excludedTags + setOf("b", "i", "u", "strike", "spoiler", "url", "email", "list", "*")
    private val builtins = BUILTIN_HFR_SMILEYS.map { it.token }.sortedByDescending { it.length }

    /**
     * Tag parameters are never text. Bare [url] destinations and image bodies are excluded too;
     * [url=destination] labels remain eligible. Only a matching top-of-stack close ends an excluded
     * block: malformed nesting fails closed, including an unclosed block through end of input.
     */
    fun extract(bbcode: String): Map<String, Int> = Scanner(bbcode).extract()

    /**
     * Two linear passes, without the rendering tokenizer: bracket ends are indexed once, then each
     * consumed span is visited a bounded number of times. URLs end at recognized BBCode tags;
     * other brackets in their path, query or fragment remain part of the excluded URL.
     */
    private class Scanner(private val source: String) {
        private val bracketEnds = indexBracketEnds(source)
        private val usages = mutableMapOf<String, Int>()
        private val excluded = ArrayDeque<String>()

        fun extract(): Map<String, Int> {
            var index = 0
            while (index < source.length) {
                index = if (source[index] == '[') consumeBracket(index) else consumeText(index)
            }
            return usages
        }

        private fun consumeBracket(start: Int): Int {
            val end = bracketEnds[start]
            return when {
                end < 0 -> start + 1
                source.startsWith("[:", start) -> consumePersonal(start, end)
                else -> consumeTag(start, end)
            }
        }

        private fun consumeTag(start: Int, end: Int): Int {
            val prefixEnd = tagPrefixEnd(start + 1, end)
            val rawName = source.substring(start + 1, prefixEnd).trim()
            val closing = rawName.startsWith('/')
            val name = rawName.removePrefix("/").trim().lowercase()
            val valid = isTagName(name) && source[prefixEnd] != '['
            return if (valid && !(closing && source[prefixEnd] == '=')) {
                val bareUrl = prefixEnd == end || source.substring(prefixEnd + 1, end).isBlank()
                applyTag(name, closing, bareUrl)
                end + 1
            } else {
                start + 1
            }
        }

        private fun tagPrefixEnd(start: Int, end: Int): Int {
            var index = start
            while (index < end && source[index] != '=' && source[index] != '[') index++
            return index
        }

        private fun consumePersonal(start: Int, end: Int): Int {
            var index = start + 2
            while (index < end && source[index] != '[') index++
            return if (index == end && end > start + 2) {
                if (excluded.isEmpty()) count(source.substring(start, end + 1))
                end + 1
            } else {
                start + 1
            }
        }

        private fun applyTag(name: String, closing: Boolean, bareUrl: Boolean) {
            val excludeUrl = name == "url" && (bareUrl || excluded.isNotEmpty())
            if (closing) {
                if (excluded.lastOrNull() == name) excluded.removeLast()
            } else if (name in excludedTags || excludeUrl) {
                excluded.addLast(name)
            }
        }

        private fun consumeText(start: Int): Int = when {
            source.startsWith("www.", start, ignoreCase = true) && hasUrlBoundary(start) -> urlEnd(start)
            source[start].isAsciiLetter() && hasUrlBoundary(start) -> consumeScheme(start)
            else -> consumeBuiltin(start)
        }

        private fun consumeScheme(start: Int): Int {
            var end = start + 1
            while (end < source.length && isSchemeCharacter(source[end])) {
                if (source.startsWith("www.", end, ignoreCase = true) && hasUrlBoundary(end)) return urlEnd(end)
                end++
            }
            // Advance over a failed scheme too: retrying at each dot would rescan the same suffix.
            return if (source.startsWith("://", end)) urlEnd(end + 3) else end
        }

        private fun urlEnd(start: Int): Int {
            var end = start
            while (end < source.length && !isUrlDelimiter(source[end])) {
                if (source[end] == '[' && isUrlMarkup(end)) break
                end++
            }
            return end
        }

        private fun isUrlMarkup(start: Int): Boolean {
            val end = bracketEnds[start]
            if (end < 0) return false
            // Stop at the next '[' or '=' too, so nested query brackets never rescan a suffix.
            val prefixEnd = tagPrefixEnd(start + 1, end)
            val rawName = source.substring(start + 1, prefixEnd).trim()
            val closing = rawName.startsWith('/')
            val name = rawName.removePrefix("/").trim().lowercase()
            val recognized = name in urlMarkupTags || (name.startsWith('#') && isTagName(name))
            val validEnding = source[prefixEnd] != '[' && !(closing && source[prefixEnd] == '=')
            return recognized && validEnding
        }

        private fun consumeBuiltin(start: Int): Int {
            val token = builtins.firstOrNull { source.startsWith(it, start) }
            if (token != null && excluded.isEmpty() && hasBuiltinBoundaries(start, token.length)) count(token)
            return start + (token?.length ?: 1)
        }

        /** Builtins need word/path boundaries on both sides; a preceding colon also rules out times. */
        private fun hasBuiltinBoundaries(start: Int, length: Int): Boolean {
            val before = source.getOrNull(start - 1)
            val after = source.getOrNull(start + length)
            return !isWordOrPath(before) && before != ':' && !isWordOrPath(after)
        }

        private fun hasUrlBoundary(start: Int): Boolean {
            val before = source.getOrNull(start - 1)
            return before == null || !(before.isLetterOrDigit() || before == '_')
        }

        private fun count(token: String) {
            usages[token] = (usages[token] ?: 0) + 1
        }
    }

    private fun indexBracketEnds(source: String): IntArray {
        val ends = IntArray(source.length) { -1 }
        val openings = ArrayDeque<Int>()
        var lineStart = 0
        source.forEachIndexed { index, char ->
            when (char) {
                '[' -> openings.addLast(index)
                ']' -> {
                    val start = openings.removeLastOrNull() ?: -1
                    if (start >= lineStart) ends[start] = index
                }
                '\n', '\r' -> lineStart = index + 1
            }
        }
        return ends
    }

    private fun isTagName(name: String): Boolean = when {
        name.isEmpty() -> false
        name.startsWith('#') -> name.length == 7 && name.drop(1).all { it in "0123456789abcdef" }
        else -> name.all { it.isLetterOrDigit() || it == '*' || it == '-' || it == '_' }
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    private fun isSchemeCharacter(char: Char): Boolean =
        char.isAsciiLetter() || char in '0'..'9' || char in "+.-"

    private fun isUrlDelimiter(char: Char): Boolean = char.isWhitespace() || char == '<' || char == '>'

    private fun isWordOrPath(char: Char?): Boolean =
        char != null && (char.isLetterOrDigit() || char == '_' || char == '/')
}
