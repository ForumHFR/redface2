package fr.forumhfr.redface2.core.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class BbcodeTextDiffTest {

    @Test
    fun `common prefix and suffix are preserved`() {
        assertReplacement("abcOLDxyz", "abcNEWxyz", BbcodeTextReplacement(3, 6, "NEW"))
    }

    @Test
    fun `repeated text never overlaps the prefix and suffix`() {
        assertReplacement("aaaa", "aaaaa", BbcodeTextReplacement(4, 4, "a"))
        assertReplacement("aaaaa", "aaaa", BbcodeTextReplacement(4, 5, ""))
        assertReplacement("abab", "ab", BbcodeTextReplacement(2, 4, ""))
    }

    @Test
    fun `insertions at the beginning middle and end keep the old text`() {
        assertReplacement("abc", "Xabc", BbcodeTextReplacement(0, 0, "X"))
        assertReplacement("abc", "aXbc", BbcodeTextReplacement(1, 1, "X"))
        assertReplacement("abc", "abcX", BbcodeTextReplacement(3, 3, "X"))
    }

    @Test
    fun `deletions at the beginning middle and end insert nothing`() {
        assertReplacement("Xabc", "abc", BbcodeTextReplacement(0, 1, ""))
        assertReplacement("aXbc", "abc", BbcodeTextReplacement(1, 2, ""))
        assertReplacement("abcX", "abc", BbcodeTextReplacement(3, 4, ""))
    }

    @Test
    fun `a total replacement covers the whole old text`() {
        assertReplacement("abc", "xyz", BbcodeTextReplacement(0, 3, "xyz"))
    }

    @Test
    fun `empty text supports insertion deletion and no change`() {
        assertReplacement("", "abc", BbcodeTextReplacement(0, 0, "abc"))
        assertReplacement("abc", "", BbcodeTextReplacement(0, 3, ""))
        assertReplacement("", "", BbcodeTextReplacement(0, 0, ""))
    }

    @Test
    fun `identical text has an empty replacement`() {
        assertReplacement("same", "same", BbcodeTextReplacement(4, 4, ""))
    }

    @Test
    fun `a shared high surrogate stays inside the replacement`() {
        assertReplacement("a😀z", "a😁z", BbcodeTextReplacement(1, 3, "😁"))
    }

    @Test
    fun `a shared low surrogate stays inside the replacement`() {
        assertReplacement("a\uD83D\uDE00z", "a\uD83E\uDE00z", BbcodeTextReplacement(1, 3, "\uD83E\uDE00"))
    }

    @Test
    fun `an emoji next to either edge of an insertion is preserved`() {
        assertReplacement("a😀b", "a😀Xb", BbcodeTextReplacement(3, 3, "X"))
        assertReplacement("a😀b", "aX😀b", BbcodeTextReplacement(1, 1, "X"))
    }

    @Test
    fun `emoji insertion and deletion include the whole pair`() {
        assertReplacement("ab", "a😀b", BbcodeTextReplacement(1, 1, "😀"))
        assertReplacement("a😀b", "ab", BbcodeTextReplacement(1, 3, ""))
    }

    @Test
    fun `suffix is bounded after moving the prefix out of a surrogate pair`() {
        assertReplacement("😀", "😁😀", BbcodeTextReplacement(0, 0, "😁"))
        assertReplacement("😀😀", "😀😁😀", BbcodeTextReplacement(2, 2, "😁"))
    }

    private fun assertReplacement(old: String, new: String, expected: BbcodeTextReplacement) {
        val actual = minimalReplacement(old, new)
        assertEquals(expected, actual)
        assertEquals(new, old.replaceRange(actual.start, actual.end, actual.text))
    }
}
