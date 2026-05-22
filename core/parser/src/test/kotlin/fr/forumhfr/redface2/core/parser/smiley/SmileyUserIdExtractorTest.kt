package fr.forumhfr.redface2.core.parser.smiley

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 2F-B (#11 partial) — pins the contract of [SmileyUserIdExtractor].
 *
 * The four cases the regex must distinguish :
 *  - present + positive id → returns the id ;
 *  - present + literal `0` → returns `0` (anonymous probe, kept distinct from « absent ») ;
 *  - absent → `null` ;
 *  - corrupt (non-digit) → `null`.
 *
 * Form fixtures already exercise the « present + positive id » path via the
 * `ReplyFormParser` / `TopicFormParser` tests, but those don't pin the `0` vs `null` split —
 * which is the change that landed in round 2 of #174.
 */
class SmileyUserIdExtractorTest {

    @Test
    fun `extracts a positive user_id from the find_smilies_timer call`() {
        val html = """<script>find_smilies_timer('hfr.inc',1214571)</script>"""
        assertEquals(1_214_571, SmileyUserIdExtractor.extract(html))
    }

    @Test
    fun `extracts a literal zero as a real value, not as null`() {
        // HFR can embed `find_smilies_timer('hfr.inc',0)` on the anonymous composer. We keep
        // the `0` distinct from « marker absent » so future diagnostics can tell the two
        // paths apart even though both collapse to user_id=0 at the wire.
        val html = """<script>find_smilies_timer('hfr.inc',0)</script>"""
        assertEquals(0, SmileyUserIdExtractor.extract(html))
    }

    @Test
    fun `tolerates whitespace inside the call arguments`() {
        // HFR ships the JS minified, but the regex must stay forgiving so a future deploy
        // that inserts spaces (or a captured fixture pretty-printed for review) does not
        // silently break the extraction.
        val html = """<script>find_smilies_timer( 'hfr.inc' , 1214571 )</script>"""
        assertEquals(1_214_571, SmileyUserIdExtractor.extract(html))
    }

    @Test
    fun `returns null when the marker is absent`() {
        // Most non-form HFR pages don't embed the smiley search bootstrap. The repository
        // then falls back to user_id=0 via `userId ?: 0`.
        assertNull(SmileyUserIdExtractor.extract("<html><body>no smiley bootstrap here</body></html>"))
    }

    @Test
    fun `returns null when the second argument is not an integer literal`() {
        // Defensive : if HFR ever inlines a variable instead of a literal int, the regex
        // should not accidentally pick something up. We require `\d+` so a non-digit fails.
        val html = """<script>find_smilies_timer('hfr.inc',user_id)</script>"""
        assertNull(SmileyUserIdExtractor.extract(html))
    }

    @Test
    fun `extracts the first marker when multiple calls exist`() {
        // Form HTML can carry the bootstrap call more than once (the JS file ships with
        // both the form binding and a fallback inline call). The first match is the form's
        // own value, which is what we want.
        val html = """
            <script>find_smilies_timer('hfr.inc',1214571)</script>
            <script>find_smilies_timer('hfr.inc',9999999)</script>
        """.trimIndent()
        assertEquals(1_214_571, SmileyUserIdExtractor.extract(html))
    }
}
