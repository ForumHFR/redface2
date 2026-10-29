package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource

/** Local collections have no guaranteed image metadata; loaded URLs always beat reconstruction. */
internal class SmileyImageResolver(known: List<EditorSmiley>) {
    private val knownUrls = known.filter { it.imageUrl.isNotBlank() }.associateBy { it.token }

    fun resolve(token: String, savedUrl: String? = null): EditorSmiley? {
        val builtin = BUILTIN_HFR_SMILEYS.firstOrNull { it.token == token }
        val url = savedUrl?.takeIf { it.isNotBlank() } ?: builtin?.imageUrl ?: knownUrls[token]?.imageUrl
        return when {
            builtin != null -> builtin.copy(imageUrl = url ?: builtin.imageUrl)
            url != null -> EditorSmiley(token, url, EditorSmileySource.WIKI)
            else -> persoUrl(token)?.let { EditorSmiley(token, it, EditorSmileySource.WIKI) }
        }
    }

    private fun persoUrl(token: String): String? {
        val match = PERSO.matchEntire(token)?.takeIf { it.groupValues[1].isNotBlank() } ?: return null
        val name = match.groupValues[1]
        val variant = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { "$it/" }.orEmpty()
        // Keep the literal name (including spaces); Coil/OkHttp owns path encoding, as for loaded URLs.
        return "https://forum-images.hardware.fr/images/perso/$variant$name.gif"
    }

    private companion object {
        val PERSO = Regex("""\[:([^\[\]/\\?#\p{C}\p{Zl}\p{Zp}]+?)(?::(\d+))?]""")
    }
}
