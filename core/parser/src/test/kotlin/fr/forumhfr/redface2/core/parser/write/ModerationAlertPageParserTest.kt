package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.write.ModerationAlertOutcome
import fr.forumhfr.redface2.core.model.write.ModerationAlertState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerationAlertPageParserTest {
    private val parser = ModerationAlertPageParser()

    @Test
    fun `form extracts its own token action and decoded referer`() {
        assertEquals(
            ModerationAlertState.Form(ACTION, HASH_CHECK, REFERER),
            parser.parseState(fixture("moderation_alert_form.html").replaceFirst(HASH_CHECK, "search-form-token")),
        )
    }

    @Test
    fun `join prompt preserves the absolute form action`() {
        assertEquals(
            ModerationAlertState.JoinPrompt("https://forum.hardware.fr/user/$ACTION", HASH_CHECK, REFERER),
            parser.parseState(fixture("moderation_alert_join_confirm.html")),
        )
    }

    @Test
    fun `pending author is recognised and carries HFR's own sentence`() {
        assertEquals(
            ModerationAlertState.PendingMine(PENDING_MINE),
            parser.parseState(fixture("moderation_alert_pending_mine.html")),
        )
    }

    @Test
    fun `pending joined member is recognised and carries HFR's own sentence`() {
        assertEquals(
            ModerationAlertState.PendingJoined(PENDING_JOINED),
            parser.parseState(fixture("moderation_alert_pending_joined.html")),
        )
    }

    @Test
    fun `treated author ignores userscript noise and extracts the date`() {
        assertEquals(
            ModerationAlertState.TreatedMine(TREATED_MINE, TREATED_AT),
            parser.parseState(fixture("moderation_alert_treated_mine.html")),
        )
    }

    @Test
    fun `treated joined member extracts the date`() {
        assertEquals(
            ModerationAlertState.TreatedJoined(TREATED_JOINED, TREATED_AT),
            parser.parseState(fixture("moderation_alert_treated_joined.html")),
        )
    }

    @Test
    fun `sent confirmation is recognised and carries HFR's own sentence`() {
        assertEquals(
            ModerationAlertOutcome.Sent(SENT),
            parser.parseOutcome(fixture("moderation_alert_sent.html")),
        )
    }

    @Test
    fun `joined confirmation is recognised and carries HFR's own sentence`() {
        assertEquals(
            ModerationAlertOutcome.Joined(JOINED),
            parser.parseOutcome(fixture("moderation_alert_joined.html")),
        )
    }

    @Test
    fun `topic page is unknown and never confirms a submission`() {
        val html = fixture("write_ia_topic_page.html")
        val state = parser.parseState(html) as ModerationAlertState.Unknown
        assertTrue(state.excerpt.isNotBlank())
        assertTrue(parser.parseOutcome(html) is ModerationAlertOutcome.Rejected)
    }

    @Test
    fun `entities unicode spaces and line breaks do not change the state`() {
        val html = fixture("moderation_alert_pending_joined.html")
            .replace("demande de modération", "demande&nbsp;de\n  mod&eacute;ration")
        assertTrue(parser.parseState(html) is ModerationAlertState.PendingJoined)
    }

    @Test
    fun `missing token fails closed and optional referer may be absent`() {
        val html = fixture("moderation_alert_form.html")
        assertTrue(parser.parseState(html.replace(HASH_CHECK, "")) is ModerationAlertState.Unknown)
        val withoutReferer = html.replace(Regex("<input[^>]*name=\"referer_page\"[^>]*>"), "")
        assertEquals(
            ModerationAlertState.Form(ACTION, HASH_CHECK, null),
            parser.parseState(withoutReferer),
        )
    }

    @Test
    fun `pending response to submission is rejected with only the server message`() {
        assertEquals(
            ModerationAlertOutcome.Rejected("Votre demande de modération sur ce message n'est pas encore traitée."),
            parser.parseOutcome(fixture("moderation_alert_pending_mine.html")),
        )
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/fixtures/$name")).readText()

    private companion object {
        private const val HASH_CHECK = "00000000000000000000000000000000"
        private const val ACTION = "modo.php?cat=23&ref=18&post=35421&numreponse=2800456&page=76&config=hfr.inc"
        private const val REFERER =
            "https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=23&subcat=550&post=35421&page=76"
        private const val TREATED_AT = "2026-09-05 17:27:28"

        // #293 — HFR's own sentences, links stripped, whitespace normalized (verbatim in the UI).
        private const val PENDING_MINE =
            "Votre demande de modération sur ce message n'est pas encore traitée."
        private const val PENDING_JOINED = "La demande de modération sur ce message à laquelle " +
            "vous vous êtes joint n'est pas encore traitée."
        private const val TREATED_MINE = "Votre demande de modération sur ce message a été " +
            "traitée le $TREATED_AT, vous ne pouvez pas le signaler à nouveau."
        private const val TREATED_JOINED = "Une demande de modération sur ce message a été " +
            "traitée le $TREATED_AT, vous ne pouvez pas le signaler à nouveau."
        private const val SENT = "Un message a été envoyé avec succès aux modérateurs"
        private const val JOINED = "Vous êtes désormais joint à la demande de modération."
    }
}
