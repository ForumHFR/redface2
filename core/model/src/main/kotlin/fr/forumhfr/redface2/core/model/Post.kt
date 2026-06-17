package fr.forumhfr.redface2.core.model

import java.time.Instant

data class Post(
    val numreponse: Int,
    val author: String,
    val date: Instant,
    val content: PostContent,
    val avatarUrl: String?,
    val isEditable: Boolean,
    val isOwnPost: Boolean,
    val quotedAuthors: List<String>,
    val postIndex: Int?,
    /**
     * `ref` parameter parsed from HFR's quote link href
     * (`/message.php?...&numrep={numreponse}&ref={N}&...`). Phase 2C (#146) :
     * needed to build the quote GET URL. The value is opaque to the app —
     * `ref` correlates with the post position inside the current topic page,
     * but the exact contract is undocumented (cf. `docs/specs/protocol-hfr.md`
     * § Quote). The app forwards whatever HFR shipped, never re-derives it.
     * `null` when HFR did not expose a *clear* quote link for this post (obfuscated
     * `md_*cryptlink` toolbar, locked topic, anonymous read, future server-side
     * change). Forwarded as-is when non-null; when `null` the quote GET omits `&ref=`
     * (HFR identifies the cited post by `numrep={numreponse}` alone — #227, proven
     * live). « Citer » visibility is driven by `Topic.canReply`, NOT by this field.
     *
     * Persisted in Room v5 (cf. `MIGRATION_4_5`) so cache hits preserve HFR's
     * positional `ref` when it was parseable. Pre-v5 rows backfill to `NULL` and
     * recover the real value on the next live fetch; since #227 this no longer
     * controls « Citer » visibility.
     */
    val quoteRef: Int? = null,
    /**
     * HFR numeric user id extracted from the profile link in the post toolbar
     * (`<a href="/hfr/profil-{userId}.htm">`). Used as the canonical key for
     * opening the profile bottom sheet / full profile page. Phase 2 finish (#208).
     *
     * Null when HFR did not render a profile link (e.g. « Publicité » rows,
     * anonymous reads without a profile link, or future server-side changes).
     * The UI hides the « Voir le profil » tap target in that case rather than
     * guessing a user id.
     *
     * Persisted in Room v6 (cf. `MIGRATION_5_6`) — a cache hit keeps the
     * profile tap available without a network round-trip.
     */
    val profileId: Int? = null,
    /**
     * #362 — when HFR marks the post as edited, the timestamp of the last edit,
     * parsed from the `div.edited` trailer (`Message édité par <auteur> le
     * DD-MM-YYYY à HH:MM:SS`). `null` when the post was never edited — including
     * the case where `div.edited` exists but only carries the « Message cité N
     * fois » citation link (a cited-but-never-edited post).
     *
     * Persisted in Room v8 (cf. `MIGRATION_7_8`) so cache hits keep the edit
     * marker; pre-v8 rows backfill to `NULL` and recover on the next live fetch.
     */
    val editedAt: Instant? = null,
    /**
     * #330 — the author's signature block (the HFR `<span class="signature">`
     * trailer rendered under the post body on the web), parsed into the same
     * [PostContent] AST as [content] so it can be rendered with the shared
     * `PostRenderer`. `null` when the post has no signature (most posts) — the
     * span is absent or empty.
     *
     * Gated for DISPLAY behind the `topic_signatures` reading preference (default
     * OFF, signatures are noisy); the field is always parsed and persisted so the
     * toggle is a pure render-time switch with no refetch.
     *
     * Persisted in Room v14 (cf. `MIGRATION_13_14`) so a cache hit keeps the
     * signature without a network round-trip. Pre-v14 rows backfill to `NULL`
     * and recover the real value on the next live fetch.
     */
    val signature: PostContent? = null,
)
