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
     * Persisted in Room v5 (cf. `MIGRATION_4_5`) so cache hits keep the
     * « Citer » button available without a network refresh. Pre-v5 rows
     * backfill to `NULL` and recover the real value on the next live fetch.
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
)
