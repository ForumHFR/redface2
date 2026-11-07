package fr.forumhfr.redface2.core.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFieldStateBbcodeTest {
    @Test
    fun changedRangeKeepsCommonPrefixAndSuffix() {
        assertEquals(BbcodeChangedRange(3, 6, 7), bbcodeChangedRange("abcOLDxyz", "abcNEW!xyz"))
    }

    @Test
    fun changedRangeNeverOverlapsOnInsertionDeletionOrRepeatedText() {
        listOf(
            Triple("aaaa", "aaaaa", BbcodeChangedRange(4, 4, 5)),
            Triple("aaaaa", "aaaa", BbcodeChangedRange(4, 5, 4)),
            Triple("", "[b][/b]", BbcodeChangedRange(0, 0, 7)),
            Triple("abc", "", BbcodeChangedRange(0, 3, 0)),
            Triple("same", "same", BbcodeChangedRange(4, 4, 4)),
            Triple("abc", "xyz", BbcodeChangedRange(0, 3, 3)),
        ).forEach { (before, after, expected) ->
            val range = bbcodeChangedRange(before, after)
            assertEquals(expected, range)
            assertEquals(after, before.replaceRange(range.start, range.oldEnd, after.substring(range.start, range.newEnd)))
        }
    }
}
