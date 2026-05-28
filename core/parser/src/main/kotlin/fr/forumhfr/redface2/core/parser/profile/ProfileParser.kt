package fr.forumhfr.redface2.core.parser.profile

import fr.forumhfr.redface2.core.model.UserProfile
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Phase 2 finish (#208) — parses `/hfr/profil-{userId}.htm` into a [UserProfile].
 *
 * The page layout is a single `<table class="main">` with rows structured as:
 *
 * ```html
 * <tr class="profil">
 *   <td class="profilCase2">Pseudo&nbsp;: </td>
 *   <td class="profilCase3"> XaTriX </td>
 *   <td class="profilCase4"> <!-- avatar cell (rowspan=6) --> </td>
 * </tr>
 * ```
 *
 * The parser is intentionally tolerant: missing rows return `null` instead of
 * throwing, because HFR's profile page structure has changed over time and may
 * differ between authenticated/anonymous sessions, user privacy settings, and
 * account types.
 *
 * Known verified fixtures:
 * - `profile_xatrix_authenticated.html` (userId=54596, authenticated)
 * - `profile_ezzz_anonymous.html` (userId=15867, anonymous)
 *
 * Contrat HFR (docs/specs/protocol-hfr.md § Profil public):
 * - Endpoint: `GET /hfr/profil-{userId}.htm` — no auth required.
 * - Emails are obfuscated by HFR: `Vous n'avez pas accès à cette information`.
 */
class ProfileParser {

    /**
     * Parses the HTML of a HFR user profile page into a [UserProfile].
     *
     * @param html  Raw HTML from `GET /hfr/profil-{userId}.htm`.
     * @param userId  The user id used to fetch the page, pre-known from the URL.
     *                Used as a fallback when the id cannot be recovered from the page
     *                itself (e.g. no `contactlist.php?adduser=N` link available).
     */
    fun parse(html: String, userId: Int): UserProfile {
        val document = Jsoup.parse(html)

        // Collect all key/value rows from the profile table.
        val rows = mutableMapOf<String, String>()
        document.select("tr.profil").forEach { row ->
            val key = row.selectFirst("td.profilCase2")?.text()?.trimLabel() ?: return@forEach
            val value = row.selectFirst("td.profilCase3")?.text()?.trim() ?: return@forEach
            if (key.isNotEmpty() && value.isNotEmpty()) {
                rows[key] = value
            }
        }

        // Pseudo: from the page title `h4.Ext` ("Informations sur : XaTriX") or row.
        val pseudo = parsePseudo(document, rows)

        // Avatar: the `<img>` inside `div.avatar_center` on the profile page.
        val avatarUrl = parseAvatarUrl(document)

        // Signature HTML: the full inner HTML of the profilCase3 cell for the signature row.
        // Stored raw for MVP — proper BBCode round-trip is deferred.
        val signatureHtml = parseSignatureHtml(document)

        // Post count: the value for « Nombre de messages postés ».
        val postCount = rows[KEY_POST_COUNT]?.trim()?.toIntOrNull()

        // Registered at: value for « Date d'arrivée sur le forum ».
        val registeredAt = rows[KEY_REGISTERED_AT]?.trim()?.takeIf(String::isNotEmpty)

        // Location: value for « Ville ».
        val location = rows[KEY_LOCATION]?.trim()?.takeIf(String::isNotEmpty)

        // Untyped fields: preserve everything the parser understood but didn't promote.
        val rawFields = rows.filter { (key, _) -> key !in PROMOTED_KEYS }

        return UserProfile(
            userId = userId,
            pseudo = pseudo,
            avatarUrl = avatarUrl,
            registeredAt = registeredAt,
            postCount = postCount,
            location = location,
            signatureHtml = signatureHtml,
            rawFields = rawFields,
        )
    }

    private fun parsePseudo(document: org.jsoup.nodes.Document, rows: Map<String, String>): String {
        // Preferred: from the row « Pseudo : »
        val fromRow = rows[KEY_PSEUDO]?.trim()?.takeIf(String::isNotEmpty)

        // Fallback: from the page title `<h4 class="Ext">Informations sur : XaTriX</h4>`
        val title = document.selectFirst("h4.Ext")?.text()?.trim().orEmpty()
        val fromTitle = TITLE_PSEUDO_REGEX.find(title)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf(String::isNotEmpty)

        // Last fallback: from the HTML `<title>XaTriX - FORUM HardWare.fr</title>`
        val fromPageTitle = document.title().trim().substringBefore(" - ").trim().ifEmpty { "?" }

        return fromRow ?: fromTitle ?: fromPageTitle
    }

    /**
     * Extracts the avatar absolute URL from `div.avatar_center img[src]`.
     * The `src` attribute in the fixture HTML is a relative path like
     * `XaTriX - FORUM HardWare.fr_files/mesdiscussions-54596.png` (due to
     * the browser-save origin). In a live fetch, HFR serves the real URL
     * `https://forum-images.hardware.fr/images/perso/{userId}/mesdiscussions-{userId}.png`.
     * We normalise to the canonical HFR CDN URL when the src contains the pattern
     * `mesdiscussions-{N}.png`, otherwise return the src as-is.
     */
    private fun parseAvatarUrl(document: org.jsoup.nodes.Document): String? {
        val imgSrc = document.selectFirst("div.avatar_center img[src]")
            ?.attr("src")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null

        // If the src already looks like an absolute URL, use it as-is.
        val isAbsolute = imgSrc.startsWith("https://") || imgSrc.startsWith("http://")

        // Try to extract the userId from the filename `mesdiscussions-{N}.png`.
        val id = AVATAR_FILENAME_REGEX.find(imgSrc)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return when {
            isAbsolute -> imgSrc
            id != null -> "https://forum-images.hardware.fr/images/perso/$id/mesdiscussions-$id.png"
            // Cannot reconstruct — return the local src as-is. A live fetch will have
            // the real absolute URL so this only affects fixture-based tests.
            else -> imgSrc
        }
    }

    /**
     * Returns the raw inner HTML of the `profilCase3` cell for the « Signature »
     * row, trimmed and null when blank or absent.
     *
     * The signature is stored as HTML because HFR renders it as a styled fragment
     * (BBCode interpreted server-side). A full BBCode round-trip is out of scope for
     * Phase 2 finish — the raw HTML is sufficient for the profile screen display.
     */
    private fun parseSignatureHtml(document: org.jsoup.nodes.Document): String? {
        // Locate the row whose profilCase2 cell contains the signature label.
        return document.select("tr.profil").firstOrNull { row ->
            val label = row.selectFirst("td.profilCase2")?.text()?.trimLabel()
            label == KEY_SIGNATURE
        }
            ?.selectFirst("td.profilCase3")
            ?.let { cell ->
                // Remove the trailing ` &nbsp;` or whitespace-only text nodes.
                val inner = cell.html().trim()
                inner.takeIf { it.isNotBlank() && it != "&nbsp;" }
            }
    }

    /** Strips trailing `&nbsp;: ` / `: ` / ` ` decoration from row header cells. */
    private fun String.trimLabel(): String =
        this.trim().trimEnd(':', ' ', ' ').trim()

    private companion object {
        private const val KEY_PSEUDO = "Pseudo"
        private const val KEY_POST_COUNT = "Nombre de messages postés"
        private const val KEY_REGISTERED_AT = "Date d'arrivée sur le forum"
        private const val KEY_LOCATION = "Ville"
        private const val KEY_SIGNATURE = "Signature des messages"

        private val PROMOTED_KEYS = setOf(
            KEY_PSEUDO,
            KEY_POST_COUNT,
            KEY_REGISTERED_AT,
            KEY_LOCATION,
            KEY_SIGNATURE,
        )

        private val TITLE_PSEUDO_REGEX = Regex("""Informations sur\s*:\s*(.+)""")
        private val AVATAR_FILENAME_REGEX = Regex("""mesdiscussions-(\d+)\.png""")
    }
}
