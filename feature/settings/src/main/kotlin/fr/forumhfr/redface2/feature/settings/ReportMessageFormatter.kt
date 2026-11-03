package fr.forumhfr.redface2.feature.settings

import java.net.URI
import java.net.URISyntaxException

/** No IO and no implicit account, post, logs or device identifiers. */
internal object ReportMessageFormatter {
    fun format(draft: ReportDraft, build: ReportBuildInfo): String = buildString {
        append("[b]Motif :[/b] ").append(requireNotNull(draft.reason).messageLabel).append("\n\n")
        append("[b]Description :[/b]\n").append(draft.description)
        if (draft.link.isNotBlank()) append("\n\n[b]Lien du contenu :[/b]\n").append(draft.link.trim())
        append("\n\n[b]Application :[/b] Redface 2 ").append(build.versionName)
        append(" (").append(build.versionCode).append(")")
        append("\n[b]Canal :[/b] ").append(build.channel)
    }

    fun isValidLink(link: String): Boolean = if (link.isBlank()) {
        true
    } else {
        try {
            val uri = URI(link.trim())
            val http = uri.scheme.equals("http", true) || uri.scheme.equals("https", true)
            http && !uri.host.isNullOrBlank()
        } catch (_: URISyntaxException) {
            false
        }
    }

    private val ReportReason.messageLabel: String
        get() = when (this) {
            ReportReason.CHILD_SAFETY -> "Sécurité des enfants (CSAE/CSAM)"
            ReportReason.ILLEGAL_CONTENT -> "Contenu illicite"
            ReportReason.HARASSMENT -> "Harcèlement"
            ReportReason.APP_PROBLEM -> "Problème de l’application"
            ReportReason.OTHER -> "Autre"
        }
}
