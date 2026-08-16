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
    /**
     * Reserved legacy field (#1055). The topic parser has never derived a stable global index from a
     * topic page, so production parsing leaves this `null` and no UI consumes it. The property is
     * retained only because its Room column has existed since schema v1; removing that inert column
     * alone does not justify a user-data migration. Do not populate or display it without first
     * establishing the cross-page semantics from real HFR fixtures.
     */
    val postIndex: Int?,
    /**
     * `ref` parameter parsed from HFR's quote link href
     * (`/message.php?...&numrep={numreponse}&ref={N}&...`). Phase 2C (#146) :
     * needed to build the quote GET URL. The app forwards whatever HFR shipped,
     * never re-derives it.
     *
     * #986 — the contract is no longer opaque: `ref` is the **1-based rank of the post inside its
     * page**. Established on the fixtures: 40 links per page numbered 1..40, incremented by one even
     * when `numreponse` jumps (520051→1, 520052→2, **520054**→3), and on pages 2+ the « Reprise du
     * message précédent » recap carries **`ref=0`** — it does not consume a rank. This is why the
     * value must never be recomputed from a list index: on any page but the first, the recap would
     * shift every rank by one. The same `ref` is what `addflag.php` needs to anchor a favourite on a
     * position, so `Post.quoteRef` is also the source for that mutation.
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
     * #863 — HFR's SERVER-side citation counter, parsed from the same `div.edited`
     * trailer as [editedAt] : « Message cité N fois » (an `a[href*=quote_only=1]`
     * link the web renders for every post cited at least once, ANYWHERE in the
     * topic). This is the authoritative CROSS-PAGE count — unlike the historical
     * client-side scan of the loaded page, which undercounted by construction.
     * `null` = absent, unknown or not parsed : a post never cited, a pre-v15
     * cached row not yet refreshed, or an unrecognized trailer — render as
     * 0 / no badge in every case (the next live fetch settles it).
     *
     * Persisted in Room v15 (cf. `MIGRATION_14_15`) so cache hits keep the badge;
     * pre-v15 rows backfill to `NULL` and recover on the next live fetch.
     */
    val citedCount: Int? = null,
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
