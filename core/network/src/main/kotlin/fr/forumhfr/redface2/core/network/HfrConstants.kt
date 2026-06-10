package fr.forumhfr.redface2.core.network

import java.time.Duration

object HfrConstants {
    const val BASE_URL: String = "https://forum.hardware.fr/"

    const val USER_AGENT: String = "Redface2/0.1 (Android; +https://github.com/ForumHFR/redface2)"

    /** Query string `config` value present on every HFR endpoint touched by Redface 2. */
    const val CONFIG: String = "hfr.inc"

    /**
     * Anti-bot value HFR expects on `bddpost.php` POSTs (Phase 2C, #145). Captured
     * verbatim from the Phase 2A reply / quote / edit / create-topic fixtures —
     * see `docs/specs/protocol-hfr.md` § POST `bddpost.php`. Centralised here so the
     * upcoming quote / edit / create flows share the same literal.
     */
    const val VERIF_REQUET: String = "1100"

    val ConnectTimeout: Duration = Duration.ofSeconds(15)
    val ReadTimeout: Duration = Duration.ofSeconds(20)
    val WriteTimeout: Duration = Duration.ofSeconds(20)
    val CallTimeout: Duration = Duration.ofSeconds(30)

    /**
     * End-to-end budget of the search-result page probe (`resolveTopicPageUrl`, #277).
     * MUST stay <= the ViewModel-side `RESOLVE_TIMEOUT_MS` (3 s) : coroutine cancellation
     * cannot interrupt a blocking OkHttp `execute()`, so the enforcement lives HERE —
     * OkHttp aborts the call itself and surfaces an IOException, which the probe already
     * degrades to its page-1 fallback. The coroutine timeout is only a belt-and-braces.
     */
    val ProbeCallTimeout: Duration = Duration.ofSeconds(3)
}
