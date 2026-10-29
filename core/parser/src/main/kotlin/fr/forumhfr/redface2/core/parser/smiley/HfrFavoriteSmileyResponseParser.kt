package fr.forumhfr.redface2.core.parser.smiley

import java.text.Normalizer
import java.util.Locale
import org.jsoup.Jsoup

/** Wire classifications only; a removal acknowledgement never proves the final server list. */
sealed interface HfrFavoriteSmileyResponse {
    data object Added : HfrFavoriteSmileyResponse
    data object Removed : HfrFavoriteSmileyResponse
    data object AlreadyPresent : HfrFavoriteSmileyResponse
    data class Full(val limit: Int) : HfrFavoriteSmileyResponse
    data object NotFound : HfrFavoriteSmileyResponse
    data object NotAuthenticated : HfrFavoriteSmileyResponse
    data object UnknownResponse : HfrFavoriteSmileyResponse
}

class HfrFavoriteSmileyResponseParser {
    @Suppress("ReturnCount") // Login and missing-hop guards precede message classification.
    fun parse(html: String): HfrFavoriteSmileyResponse {
        val document = Jsoup.parse(html)
        val login = document.select("form[action*=login_validation.php]").any { form ->
            form.select("input[name=pseudo]").isNotEmpty() && form.select("input[name=password]").isNotEmpty()
        }
        if (login) return HfrFavoriteSmileyResponse.NotAuthenticated
        val text = document.selectFirst("div.hop")?.text()?.let(::normalize)
            ?: return HfrFavoriteSmileyResponse.UnknownResponse
        return when {
            "ajout effectue avec succes" in text -> HfrFavoriteSmileyResponse.Added
            "suppression effectuee avec succes" in text -> HfrFavoriteSmileyResponse.Removed
            "ce smilie est deja dans votre liste" in text -> HfrFavoriteSmileyResponse.AlreadyPresent
            "vous ne pouvez rajouter plus de" in text -> HfrFavoriteSmileyResponse.Full(
                FULL_LIMIT.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 10,
            )
            "le smiley correspondant a ce code n'existe pas" in text -> HfrFavoriteSmileyResponse.NotFound
            else -> HfrFavoriteSmileyResponse.UnknownResponse
        }
    }

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .replace('\u00a0', ' ')
        .replace('’', '\'')
        .replace(WHITESPACE, " ")
        .trim()
        .lowercase(Locale.ROOT)

    private companion object {
        val FULL_LIMIT = Regex("vous ne pouvez rajouter plus de (\\d+)")
        val DIACRITICS = Regex("\\p{M}+")
        val WHITESPACE = Regex("\\s+")
    }
}
