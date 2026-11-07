package fr.forumhfr.redface2.core.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFieldStateBbcodeTest {
    @Test
    fun changedRangeKeepsCommonPrefixAndSuffix() {
        assertEquals(BbcodeTextReplacement(3, 6, "NEW!"), minimalReplacement("abcOLDxyz", "abcNEW!xyz"))
    }

    @Test
    fun changedRangeNeverOverlapsOnInsertionDeletionOrRepeatedText() {
        listOf(
            Triple("aaaa", "aaaaa", BbcodeTextReplacement(4, 4, "a")),
            Triple("aaaaa", "aaaa", BbcodeTextReplacement(4, 5, "")),
            Triple("", "[b][/b]", BbcodeTextReplacement(0, 0, "[b][/b]")),
            Triple("abc", "", BbcodeTextReplacement(0, 3, "")),
            Triple("same", "same", BbcodeTextReplacement(4, 4, "")),
            Triple("abc", "xyz", BbcodeTextReplacement(0, 3, "xyz")),
        ).forEach { (before, after, expected) ->
            val range = minimalReplacement(before, after)
            assertEquals(expected, range)
            assertEquals(
                after,
                before.replaceRange(range.start, range.end, range.text),
            )
        }
    }
}
