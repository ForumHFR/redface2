package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.write.ModerationAlertOutcome
import fr.forumhfr.redface2.core.model.write.ModerationAlertState
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Classifies the live /user/modo.php forms and messages captured for #293. */
class ModerationAlertPageParser {
    fun parseState(html: String): ModerationAlertState {
        val document = Jsoup.parse(html)
        val form = document.select("form[method=post][action]")
            .firstOrNull {
                it.attr("action").substringBefore('?').endsWith("modo.php") &&
                    it.selectFirst("[name=cfmodoalert], textarea[name=raison]") != null
            }
        val message = messageText(document)
        return form?.let(::parseForm) ?: parseMessage(message)
            ?: ModerationAlertState.Unknown(message.ifBlank { document.body().text() }.take(EXCERPT_LIMIT))
    }

    fun parseOutcome(html: String): ModerationAlertOutcome {
        val message = messageText(Jsoup.parse(html))
        val normalized = normalize(message)
        // The detection stays keyed on the known sentences, but the state carries HFR's own
        // wording so a reworded (yet still matching) message reaches the user unchanged.
        return when {
            normalized.contains("un message a été envoyé avec succès aux modérateurs") ->
                ModerationAlertOutcome.Sent(message.take(EXCERPT_LIMIT))
            normalized.contains("vous êtes désormais joint à la demande de modération") ->
                ModerationAlertOutcome.Joined(message.take(EXCERPT_LIMIT))
            else -> ModerationAlertOutcome.Rejected(message.take(EXCERPT_LIMIT))
        }
    }

    private fun parseForm(form: Element): ModerationAlertState? {
        val action = form.attr("action")
        val hashCheck = form.selectFirst("input[name=hash_check]")?.attr("value").orEmpty()
        val refererPage = form.selectFirst("input[name=referer_page]")?.attr("value")
        return when {
            action.isBlank() || hashCheck.isBlank() -> null
            form.selectFirst("input[name=cfmodoalert]") != null ->
                ModerationAlertState.JoinPrompt(action, hashCheck, refererPage)
            else -> ModerationAlertState.Form(action, hashCheck, refererPage)
        }
    }

    private fun parseMessage(message: String): ModerationAlertState? {
        val normalized = normalize(message)
        val treated = TREATED.find(normalized)
        val verbatim = message.take(EXCERPT_LIMIT)
        return when {
            normalized.contains("votre demande de modération sur ce message n'est pas encore traitée") ->
                ModerationAlertState.PendingMine(verbatim)
            normalized.contains(
                "la demande de modération sur ce message à laquelle vous vous êtes joint n'est pas encore traitée",
            ) -> ModerationAlertState.PendingJoined(verbatim)
            treated != null -> if (treated.groupValues[1] == "votre") {
                ModerationAlertState.TreatedMine(verbatim, treated.groupValues[2])
            } else {
                ModerationAlertState.TreatedJoined(verbatim, treated.groupValues[2])
            }
            else -> null
        }
    }

    // A topic quoting a moderation message must never masquerade as a successful alert response.
    private fun messageText(document: Element): String = document.selectFirst("div.hop")
        ?.clone()?.apply { select("a, script, style").remove() }?.text().orEmpty()
        .replace(WHITESPACE, " ").trim()

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(WHITESPACE, " ").trim()

    private companion object {
        private const val EXCERPT_LIMIT = 300
        private val WHITESPACE = Regex("[\\p{Z}\\s]+")
        private val TREATED = Regex(
            "(votre|une) demande de modération sur ce message a été traitée le " +
                "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}), vous ne pouvez pas le signaler à nouveau",
        )
    }
}
