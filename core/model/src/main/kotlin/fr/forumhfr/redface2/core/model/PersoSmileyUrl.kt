package fr.forumhfr.redface2.core.model

/**
 * Pure derivation shared by the local picker and BBCode preview; no URL lookup or network access.
 * ED-66 interpretation, pending maintainer ratification: typing never searches for a smiley URL.
 * Once a preview is displayed, its renderer may load the derived URL as ordinary image media,
 * including when a completed token is typed into an already open preview. That media load is
 * distinct from URL discovery; private-message previews disable its disk cache, probes included.
 */
fun persoSmileyUrl(token: String): String? {
    val match = PERSO_SMILEY_TOKEN.matchEntire(token)?.takeIf { it.groupValues[1].isNotBlank() } ?: return null
    val name = match.groupValues[1]
    val variant = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { "$it/" }.orEmpty()
    // Preserve case, accents and spaces. Coil/OkHttp owns path encoding, as for server-loaded URLs.
    return "https://forum-images.hardware.fr/images/perso/$variant$name.gif"
}

private val PERSO_SMILEY_TOKEN = Regex("""\[:([^\[\]/\\?#\p{C}\p{Zl}\p{Zp}]+?)(?::(\d+))?]""")
