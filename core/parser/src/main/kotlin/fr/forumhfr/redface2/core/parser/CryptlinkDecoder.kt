package fr.forumhfr.redface2.core.parser

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Decodes HFR's `md_*cryptlink` link obfuscation (anti-aspirateur).
 *
 * HFR renders the per-post toolbar action links (quote, edit, profile, PV, addflag…)
 * **either in clear** (`<a href="/message.php?…&numrep=N&ref=M…">`) **or obfuscated**
 * in a `<span class="md_noclass_cryptlink{HEX}">…</span>`, decoded client-side by HFR's
 * own JS (`md_forum_decryptlink.init()`, shipped in `common.js`). The choice is
 * intermittent / topic-dependent: e.g. cat IA (cat=32) and some pinned topics ship the
 * obfuscated variant, where a clear `<a>` is absent. A non-JS client (Redface 2) must
 * replicate the decode to read those links. See `docs/specs/protocol-hfr.md` § Liens
 * obfusqués (md_*cryptlink) and issue #227.
 *
 * **Status — on-demand utility, NOT wired into [TopicPageParser].** « Citer » no longer
 * needs it: the cited post is identified by `numrep={numreponse}` alone (HFR ignores
 * `ref`, proven live — cf. #213/#227 and `HfrClient.getReplyForm`), so the quote action
 * is gated on `Topic.canReply` and never reads the quote link. This decoder is kept for
 * the toolbar links that still rely on parsing the HTML — « Modifier » ([isEditable]) and
 * the profile tap ([profileId]) — which break on the obfuscated variant. Call
 * [materialize] before toolbar extraction when those must work on an obfuscated page.
 *
 * ## Algorithm (verified against `common.js` and live fixtures)
 * A custom base-16 alphabet: each character of the class suffix is a hex "digit" whose
 * value is its index in [ALPHABET]; consecutive pairs form one byte
 * (`index(c0) * 16 + index(c1)` → `Char`). Proven: `45CBCBC0C22D1F1F…` → `https://…`,
 * and a cat=13 quote span → `/message.php?…&numrep=74594002&ref=0&…`.
 */
object CryptlinkDecoder {

    /**
     * HFR's custom base-16 alphabet (`md_forum_decryptlink._base16`). The index of each
     * character is its nibble value: `0`→0, `A`→1, `1`→2, `2`→3, `B`→4, `3`→5, `4`→6,
     * `C`→7, `5`→8, `6`→9, `D`→10, `7`→11, `8`→12, `E`→13, `9`→14, `F`→15.
     */
    private const val ALPHABET = "0A12B34C56D78E9F"

    /**
     * Class-name prefixes HFR uses, longest first so the [decodeClass] scan never matches
     * the shorter `md_cryptlink` against a `md_noclass_cryptlink` / `md_blank_cryptlink`
     * token. The trailing characters after the prefix are the encoded URL.
     */
    private val PREFIXES = listOf("md_noclass_cryptlink", "md_blank_cryptlink", "md_cryptlink")

    private const val CSS_SELECTOR =
        "span[class^=md_noclass_cryptlink], span[class^=md_blank_cryptlink], span[class^=md_cryptlink]"

    /**
     * Decodes a raw cryptlink hex suffix (the part after the prefix) into its URL.
     * Returns `null` for an odd-length string or any character outside [ALPHABET], so a
     * malformed class never throws — the caller simply leaves that span untouched.
     */
    fun decode(hex: String): String? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val out = StringBuilder(hex.length / 2)
        // `all` short-circuits on the first byte whose nibbles fall outside ALPHABET,
        // leaving `out` partial — discarded via the single tail return below.
        val ok = hex.chunked(2).all { pair ->
            val hi = ALPHABET.indexOf(pair[0])
            val lo = ALPHABET.indexOf(pair[1])
            (hi >= 0 && lo >= 0).also { valid -> if (valid) out.append(((hi shl 4) or lo).toChar()) }
        }
        return if (ok) out.toString() else null
    }

    /**
     * Decodes a full single-token `class` value (`md_noclass_cryptlink{HEX}`, etc.) into
     * its URL, or `null` when [className] is not a cryptlink token. HFR emits exactly one
     * class on these spans, so a plain prefix match is sufficient.
     */
    fun decodeClass(className: String): String? {
        val prefix = PREFIXES.firstOrNull { className.startsWith(it) } ?: return null
        return decode(className.substring(prefix.length))
    }

    /**
     * Materializes every `md_*cryptlink` span in [document] into a real `<a href="…">`,
     * mirroring `md_forum_decryptlink.init()`: the span's existing children (the `<img>`)
     * are moved into the new anchor, then the anchor is appended to the span. The cryptlink
     * class is cleared afterwards so the operation is idempotent (a second call is a no-op).
     * After this, the standard toolbar selectors (`a[href*=numrep=]`, `a[href*=message.php]`,
     * `a[href*=/hfr/profil-]`) resolve on a formerly-obfuscated page.
     */
    fun materialize(document: Document) {
        for (span in document.select(CSS_SELECTOR)) {
            val url = decodeClass(span.className()) ?: continue
            val anchor = Element("a").attr("href", url).attr("rel", "nofollow")
            // Move the span's current children (icons) into the anchor, preserving order.
            for (child in ArrayList(span.childNodes())) {
                child.remove()
                anchor.appendChild(child)
            }
            span.appendChild(anchor)
            // Clear the cryptlink class so a repeated materialize() does not re-wrap.
            span.removeAttr("class")
        }
    }
}
