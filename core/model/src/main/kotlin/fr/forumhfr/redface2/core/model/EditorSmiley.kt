package fr.forumhfr.redface2.core.model

/**
 * Phase 2F-B (#11 partial) — one entry rendered in the editor's smiley picker.
 *
 * Lives in `:core:model` because the type travels across the parser (which builds it from
 * `<img alt="…" src="…">` fragments), the repository (which serves it), the ViewModel (which
 * holds the picker state) and the UI (which renders the `imageUrl` and emits the `token` on
 * tap).
 *
 * Not to be confused with [SmileyKind] (in `PostContent.kt`) : that is the **AST** view of a
 * smiley already parsed inside a post body. [EditorSmiley] is the **picker** view of a smiley
 * the user can pick to write — the two surfaces never share an instance.
 *
 * Invariants :
 *  - [token] is the raw BBCode the user wants inserted. For builtin smileys this is the
 *    canonical `:o`, `:jap:`, `;)` etc. ; for the wiki search, it is the HFR perso syntax
 *    `[:name]`, `[:name with spaces]` or `[:name:N]` (variant index). The picker never
 *    rewrites or normalises the token — accents, underscores, dashes, internal colons,
 *    spaces and apostrophes all survive verbatim. The token is meant to be wrapped by an
 *    insertion helper that adds spaces around it (cf. HFR's `putSmiley` JS), so it carries
 *    no leading/trailing whitespace itself.
 *  - [imageUrl] is the URL HFR served for the preview image, never reconstructed from the token.
 *    The account management form also preserves broken historical entries: their URL may be
 *    relative or empty, and their token may be noncanonical. Mutations must round-trip those rows.
 *  - [source] identifies the origin : builtins, wiki search or HFR account favorites.
 *    A smiley can appear in both the account favorites and the wiki search.
 *    Callers that need to merge / dedupe rely on `token` equality, not on identity.
 */
data class EditorSmiley(
    val token: String,
    val imageUrl: String,
    val source: EditorSmileySource,
)

/**
 * Where the smiley entry came from. Favorites are read from an editor or account management form ;
 * builtins ship with the app and wiki results come from a live search.
 */
enum class EditorSmileySource {
    // Canonical HFR built-in set served from `/icones/smilies/<name>.gif`. ~25 entries that
    // ship with the form HTML and stay constant across users.
    BUILTIN,

    // HFR wiki search via GET /message-smi-mp-aj.php?config=hfr.inc&user_id=...&findsmilies=...
    // — a live REST hit per query, returns the perso corpus filtered by the search term.
    WIKI,

    // HFR account favorites from `div#dynamic_smilies` or the fresh account management form.
    HFR_FAVORITE,
}
