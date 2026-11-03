package fr.forumhfr.redface2.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportMessageFormatterTest {
    private val build = ReportBuildInfo("0.65.0", 123, "beta")

    @Test
    fun subjectFitsHfrLimit() {
        assertTrue(REPORT_SUBJECT.length <= 70)
    }

    @Test
    fun blankLinkIsOmitted() {
        val message = ReportMessageFormatter.format(ReportDraft(ReportReason.CHILD_SAFETY, "Description"), build)
        assertTrue(message.contains("Sécurité des enfants (CSAE/CSAM)"))
        assertTrue(message.contains("Description"))
        assertTrue(message.contains("Redface 2 0.65.0 (123)"))
        assertTrue(message.contains("Canal :[/b] beta"))
        assertFalse(message.contains("Lien du contenu"))
    }

    @Test
    fun suppliedLinkIsIncludedWithoutFetchingOrAddingPrivateContext() {
        val link = "https://forum.hardware.fr/forum2.php?cat=23&post=35421"
        val message = ReportMessageFormatter.format(ReportDraft(ReportReason.OTHER, "Texte", link), build)
        assertTrue(message.contains("[b]Lien du contenu :[/b]\n$link"))
        assertFalse(message.contains("hash_check"))
    }

    @Test
    fun linkValidationAcceptsOnlyAbsoluteHttpLinks() {
        assertTrue(ReportMessageFormatter.isValidLink(""))
        assertTrue(ReportMessageFormatter.isValidLink("https://forum.hardware.fr/test#t123"))
        listOf("/relative", "mailto:test@example.org", "https:", "https://", "bad link").forEach {
            assertFalse(it, ReportMessageFormatter.isValidLink(it))
        }
    }
}
