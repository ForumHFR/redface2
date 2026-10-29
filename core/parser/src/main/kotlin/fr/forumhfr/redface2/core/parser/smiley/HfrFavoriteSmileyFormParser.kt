package fr.forumhfr.redface2.core.parser.smiley

import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Transient technical form. Both tokens are stable account secrets; never persist or log this object. */
class HfrFavoriteSmileyForm internal constructor(
    val hashCheck: String,
    val codehex: String,
    val favorites: List<EditorSmiley>,
) {
    override fun toString(): String = "HfrFavoriteSmileyForm([redacted])"
}

/** #1154 — fail closed: a partial list would erase unrelated favorites when posted back to HFR. */
class HfrFavoriteSmileyFormParser {
    @Suppress("ReturnCount") // Each guard rejects a form that would be unsafe to post back.
    fun parse(html: String): HfrFavoriteSmileyForm? {
        val document = Jsoup.parse(html)
        val form = document.select("form[name=test]").singleOrNull() ?: return null
        // A second form or orphaned rows/fields can conceal favorites from the rewrite list.
        if (FAVORITE_SELECTORS.any { document.select(it).size != form.select(it).size }) return null
        if (!form.attr("method").equals("post", ignoreCase = true)) return null
        val codehex = parseCodehex(form.attr("action")) ?: return null
        val hashCheck = form.select("input[type=hidden][name=hash_check]").singleOrNull()
            ?.attr("value")?.takeIf { it.isNotBlank() } ?: return null
        val favorites = parseRows(form) ?: return null
        if (!hasCompleteRawForm(html, favorites.size)) return null
        return HfrFavoriteSmileyForm(hashCheck, codehex, favorites)
    }

    private fun hasCompleteRawForm(html: String, favoriteCount: Int): Boolean {
        val accumulator = RawFormAccumulator()
        // Match whole tags before attributes so quoted alt/title values cannot create fake fields.
        return RAW_TAG_OR_COMMENT.findAll(html).all(accumulator::accept) && accumulator.isComplete(favoriteCount)
    }

    private class RawFormAccumulator {
        private var formOpen = false
        private var forms = 0
        private var smileys = 0
        private var deletions = 0
        private var rows = 0

        fun accept(tag: MatchResult): Boolean {
            val tagName = tag.groupValues[2]
            if (tagName.isEmpty()) return true // Comments, including their apparent tags, are ignored.
            val closing = tag.groupValues[1].isNotEmpty()
            val attributes = tag.groupValues[3]
            val isForm = tagName.equals("form", ignoreCase = true)
            val validForm = !isForm || acceptFormTag(closing, attributes)
            if (validForm && !closing) {
                val names = rawAttributeValues(attributes, "name")
                smileys += names.count { RAW_SMILEY_NAME.matches(it) }
                deletions += names.count { RAW_DELETE_NAME.matches(it) }
                rows += rawAttributeValues(attributes, "class").count { RAW_ROW_CLASS.containsMatchIn(it) }
                if (isForm) forms += names.count { it.equals("test", ignoreCase = true) }
            }
            return validForm
        }

        private fun acceptFormTag(closing: Boolean, attributes: String): Boolean {
            if (closing != formOpen || hasInvalidFormSyntax(closing, attributes)) {
                return false
            }
            formOpen = !closing
            return true
        }

        private fun hasInvalidFormSyntax(closing: Boolean, attributes: String): Boolean =
            if (closing) attributes.isNotBlank() else attributes.trimEnd().endsWith('/')

        fun isComplete(favoriteCount: Int): Boolean =
            !formOpen && forms == 1 && listOf(smileys, deletions, rows).all { it == favoriteCount }

        private fun rawAttributeValues(attributes: String, name: String): List<String> =
            RAW_ATTRIBUTE.findAll(attributes)
                .filter { it.groupValues[1].equals(name, ignoreCase = true) }
                .map { it.groups[2]?.value ?: it.groups[3]?.value ?: it.groupValues[4] }
                .toList()
    }

    @Suppress("ReturnCount") // Fail at the first ambiguous row; never return a partial list.
    private fun parseRows(form: Element): List<EditorSmiley>? {
        val rows = form.select("tr.s2Topic")
        val inputs = form.select("[name^=smiley]")
        val deletions = form.select("[name^=delete]")
        if (inputs.size != rows.size || deletions.size != rows.size) return null
        val favorites = mutableListOf<EditorSmiley>()
        inputs.forEachIndexed { index, input ->
            if (input.tagName() != "input" || !input.attr("type").equals("hidden", ignoreCase = true)) return null
            val deletion = deletions[index]
            if (deletion.tagName() != "input" || !deletion.attr("type").equals("checkbox", ignoreCase = true)) {
                return null
            }
            // Check association too: two fields in one row must not conceal a missing row.
            if (input.attr("name") != "smiley$index" || !input.hasAttr("value")) return null
            if (deletion.attr("name") != "delete$index") return null
            if (rows[index].select("[name^=smiley]").singleOrNull() != input) return null
            if (rows[index].select("[name^=delete]").singleOrNull() != deletion) return null
            favorites += EditorSmiley(
                token = input.attr("value"),
                // Broken historical entries (e.g. `aaa` with <img aaa>) still have to round-trip.
                imageUrl = rows[index].selectFirst("img")?.attr("src").orEmpty(),
                source = EditorSmileySource.HFR_FAVORITE,
            )
        }
        return favorites
    }

    @Suppress("ReturnCount") // Keep the origin/path/query allowlist checks explicit.
    private fun parseCodehex(action: String): String? {
        // URI failures can echo the action (and its secret). Never propagate their exceptions.
        val uri = runCatching { FORM_URI.resolve(URI(action)) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "forum.hardware.fr" || uri.port !in listOf(-1, 443)) return null
        if (uri.rawUserInfo != null || uri.rawFragment != null || uri.rawPath != REMOVAL_PATH) return null
        val parameters = uri.rawQuery?.split('&') ?: return null
        if (parameters.size != 2 || parameters.count { it == "config=hfr.inc" } != 1) return null
        val codehex = parameters.singleOrNull { it.startsWith("codehex=") }?.substringAfter('=')
        return codehex?.takeIf { HEX.matches(it) }
    }

    private companion object {
        val FAVORITE_SELECTORS = listOf("[name^=smiley]", "[name^=delete]", ".s2Topic")
        val RAW_TAG_OR_COMMENT = Regex(
            """<!--[\s\S]*?(?:-->|$)|<(/?)([a-z][a-z0-9:-]*)(?=[\s/>])""" +
                """((?:"[^"]*"|'[^']*'|[^"'<>])*)>""",
            RegexOption.IGNORE_CASE,
        )
        val RAW_ATTRIBUTE = Regex("""\s+([^\s"'<>/=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?""")
        val RAW_SMILEY_NAME = Regex("smiley[0-9]+", RegexOption.IGNORE_CASE)
        val RAW_DELETE_NAME = Regex("delete[0-9]+", RegexOption.IGNORE_CASE)
        val RAW_ROW_CLASS = Regex("""(?:^|\s)s2Topic(?:\s|$)""", RegexOption.IGNORE_CASE)
        const val REMOVAL_PATH = "/user/supprperso_validation.php"
        val FORM_URI = URI("https://forum.hardware.fr/user/supprperso.php?config=hfr.inc")
        val HEX = Regex("[0-9a-fA-F]+")
    }
}
