package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.persoSmileyUrl

/** Local collections have no guaranteed image metadata; loaded URLs always beat reconstruction. */
internal class SmileyImageResolver(known: List<EditorSmiley>) {
    private val knownUrls = known.filter { it.imageUrl.isNotBlank() }.associateBy { it.token }

    fun resolve(token: String, savedUrl: String? = null): EditorSmiley? {
        val builtin = BUILTIN_HFR_SMILEYS.firstOrNull { it.token == token }
        val url = savedUrl?.takeIf { it.isNotBlank() } ?: builtin?.imageUrl ?: knownUrls[token]?.imageUrl
        return when {
            builtin != null -> builtin.copy(imageUrl = url ?: builtin.imageUrl)
            url != null -> EditorSmiley(token, url, EditorSmileySource.WIKI)
            else -> persoSmileyUrl(token)?.let { EditorSmiley(token, it, EditorSmileySource.WIKI) }
        }
    }

}
