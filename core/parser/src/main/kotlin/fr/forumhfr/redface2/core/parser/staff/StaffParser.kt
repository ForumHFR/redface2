package fr.forumhfr.redface2.core.parser.staff

import fr.forumhfr.redface2.core.model.AuthorRole
import fr.forumhfr.redface2.core.parser.authorRoleFromLabel
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Rôle HFR (#1112, #221) — source **primaire** : parse l'annuaire staff GLOBAL
 * (« Contacter un responsable », réponse AJAX de
 * `message-smi-mp-aj.php?config=hfr.inc&user_id=0&responsable=1`, cf. `docs/specs/protocol-hfr.md`).
 *
 * DOM : une `<table class="main">` d'ancres
 * `<a class="s1Topic" onclick="fillfield_private('pseudo')">pseudo <i>(Rôle)</i></a>`.
 * - **pseudo** (source primaire) = l'argument de `fillfield_private('…')` (dé-échappé : le pseudo
 *   `o'gure` est sérialisé `fillfield_private('o\'gure')`). Repli défensif : `anchor.ownText()`
 *   (le texte propre de l'ancre, HORS le `<i>`), jamais `anchor.text()` qui inclurait « (Rôle) ».
 * - **rôle** = le texte du `<i>` parenthèses retirées, mappé via le mapping **partagé**
 *   [authorRoleFromLabel] ; un libellé **inconnu** fait **ignorer** l'entrée (pas de rôle inventé).
 *
 * Sortie = pseudos **bruts** (non canonicalisés) → la canonicalisation ([canonicalizePseudo]) est
 * faite par le repository (`:core:parser` ne dépend pas de `:core:domain`). L'annuaire n'expose
 * **aucun** `profileId` : la clé est le pseudo.
 */
class StaffParser {

    /** Parse l'annuaire en `pseudo brut -> rôle` ; les libellés inconnus sont écartés. */
    fun parse(html: String): Map<String, AuthorRole> {
        val document = Jsoup.parse(html)
        return document.select(STAFF_ANCHOR_QUERY).mapNotNull { anchor ->
            val pseudo = extractPseudo(anchor) ?: return@mapNotNull null
            val label = anchor.selectFirst("i")?.text()?.stripParentheses() ?: return@mapNotNull null
            val role = authorRoleFromLabel(label) ?: return@mapNotNull null
            pseudo to role
        }.toMap()
    }

    /** Pseudo primaire = argument de `fillfield_private('…')` (dé-échappé) ; repli sur `ownText()`. */
    private fun extractPseudo(anchor: Element): String? {
        val fromCall = FILLFIELD_REGEX.find(anchor.attr("onclick"))
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::unescapeJs)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        return fromCall ?: anchor.ownText().trim().takeIf(String::isNotEmpty)
    }

    /** Dé-échappe les backslash-escapes JS de l'argument (ex. `o\'gure` -> `o'gure`). */
    private fun unescapeJs(raw: String): String = raw.replace(JS_ESCAPE_REGEX, "$1")

    private fun String.stripParentheses(): String =
        trim().removePrefix("(").removeSuffix(")").trim()

    private companion object {
        private const val STAFF_ANCHOR_QUERY = "table.main a.s1Topic[onclick*=fillfield_private]"
        private val FILLFIELD_REGEX = Regex("""fillfield_private\('(.*)'\)""")
        private val JS_ESCAPE_REGEX = Regex("""\\(.)""")
    }
}
