package fr.forumhfr.redface2.core.model

/**
 * User profile loaded from `/hfr/profil-{userId}.htm`.
 *
 * Navigation key is always [userId] — [pseudo] and [avatarUrl] are display hints that
 * can be pre-populated from the topic page before the profile is fully loaded.
 *
 * All fields beyond [userId] and [pseudo] are nullable because HFR's profile page is
 * fragile: optional sections (location, signature, birthdate) can be absent, and the
 * exact HTML structure has changed over the years. Tolerant nullability avoids crashes
 * on edge cases and partial renders.
 *
 * [rawFields] holds every `profilCase2/profilCase3` pair that the parser recognised
 * but did not promote to a typed field — preserved for future parsing improvements and
 * diagnostics without requiring a model bump.
 */
data class UserProfile(
    /** HFR numeric user id, extracted from `/hfr/profil-{userId}.htm`. */
    val userId: Int,
    /**
     * Display pseudo as shown on the profile page.
     *
     * The parser tries three sources in order (« Pseudo : » row, `<h4 class="Ext">`
     * header, HTML `<title>`) and falls back to the literal `"?"` only when **all**
     * three are empty (defensive case for fully-empty / placeholder HTML).
     * Kept non-null to avoid forcing every UI call-site to handle a missing pseudo
     * (in practice a real HFR profile page always exposes one).
     */
    val pseudo: String,
    /**
     * Absolute URL of the avatar image served by HFR
     * (`https://forum-images.hardware.fr/images/perso/{userId}/mesdiscussions-{userId}.png`).
     * Null when HFR did not render an avatar `<img>` in `div.avatar_center`.
     */
    val avatarUrl: String?,
    /**
     * Registration date as a raw string in HFR's format (`DD/MM/YYYY`).
     * Kept as [String] because the exact format is fragile — promoting to
     * [java.time.LocalDate] is deferred until a real use-case (e.g. sorting) requires it.
     */
    val registeredAt: String?,
    /** Post count as an integer, null if HFR did not render the field. */
    val postCount: Int?,
    /**
     * User's city / location as a raw string. Null when the field is empty or absent.
     * HFR may obfuscate or omit this for certain account settings.
     */
    val location: String?,
    /**
     * Signature plain text extracted from HFR's `td.profilCase3` cell for the
     * « Signature » row.
     *
     * The signature is rendered server-side as HTML (HFR converts the user's BBCode
     * to inline markup with `<br>`, `<div>`, occasional inline styling). We flatten
     * it to plain text at parse time (`Jsoup.parse(html).text()`) so the UI can
     * display it with a simple `Text(...)` composable without rendering raw HTML
     * tags as literal characters. A full BBCode round-trip / styled rendering is
     * deferred — the signature is a low-priority display field and the MVP brief
     * accepts the limitation. Null when the row is absent or the content is only
     * whitespace.
     */
    val signatureText: String?,
    /**
     * Untyped key→value pairs for every HFR profile row that the parser encountered
     * but did not promote to a dedicated typed field (e.g. « Profession », « Loisirs »,
     * « Citation personnelle »). Preserved verbatim for forward-compatibility and
     * diagnostics. Keys are the trimmed text of `td.profilCase2` (e.g. `"Profession"`,
     * `"Loisirs"`). Values are the trimmed text content of `td.profilCase3`.
     */
    val rawFields: Map<String, String> = emptyMap(),
)
