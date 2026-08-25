package fr.forumhfr.redface2.core.domain.author

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #221 — creator matching must keep routing through the shared pseudo canonicalizer. */
class Rf2CreatorsTest {

    @Test
    fun `creator detection ignores case format characters and non-breaking spaces`() {
        val nbsp = Char(0x00A0)
        val zeroWidthSpace = Char(0x200B)

        assertTrue(isRf2Creator("$nbsp${zeroWidthSpace}xAtRiX$zeroWidthSpace$nbsp"))
    }

    @Test
    fun `unlisted pseudo is not a creator`() {
        assertFalse(isRf2Creator("Lt Ripley"))
    }
}
