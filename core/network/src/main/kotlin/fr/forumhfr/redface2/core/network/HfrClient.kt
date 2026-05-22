package fr.forumhfr.redface2.core.network

import androidx.tracing.trace
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.network.qualifiers.AnonymousClient
import fr.forumhfr.redface2.core.network.qualifiers.AuthenticatedClient
import fr.forumhfr.redface2.core.network.qualifiers.HfrBaseUrl
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Singleton
class HfrClient @Inject constructor(
    @param:AuthenticatedClient private val authenticated: OkHttpClient,
    @param:AnonymousClient private val anonymous: OkHttpClient,
    @param:HfrBaseUrl private val baseUrl: HttpUrl,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun getTopicPage(
        cat: Int,
        post: Int,
        page: Int,
        useAuth: Boolean = true,
    ): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("forum2.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("cat", cat.toString())
            .addQueryParameter("post", post.toString())
            .addQueryParameter("page", page.toString())
            .build()

        val request = Request.Builder().url(url).get().build()
        return if (useAuth) {
            // Authenticated read: an expired session would otherwise be parsed silently as an
            // empty topic. executeAuthenticatedHtml() raises SessionExpiredException so the
            // caller can surface a reconnect CTA instead of a misleading empty screen. The
            // `rf2.topic` prefix scopes the trace sections to the topic feature only — other
            // callers (MP list, …) opt out of tracing by omitting the prefix.
            authenticated.newCall(request).executeAuthenticatedHtml(tracePrefix = TOPIC_TRACE_PREFIX)
        } else {
            // `rf2.topic.network` covers DNS + connect + TLS + headers (the part OkHttp returns
            // before we touch the body). `rf2.topic.body_read` covers the bytes-on-the-wire
            // pull from the response body. Splitting them lets a profiler tell a slow handshake
            // apart from a slow body download. The auth branch above wires the same prefix into
            // `executeAuthenticatedHtml`.
            // `withContext(ioDispatcher)` ensures we never call OkHttp `.execute()` on the main
            // thread, regardless of the caller's coroutine context (cf. PR #162 v43 — repository
            // layer forgot the hop and triggered `NetworkOnMainThreadException`).
            withContext(ioDispatcher) {
                trace("$TOPIC_TRACE_PREFIX.network") {
                    anonymous.newCall(request).execute()
                }.use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HFR returned ${response.code} for $url")
                    }
                    trace("$TOPIC_TRACE_PREFIX.body_read") { response.body.string() }
                }
            }
        }
    }

    /**
     * Fetches the authenticated MP list page. The endpoint is the legacy v1 URL
     * (`forum1.php?config=hfr.inc&cat=prive&...`), which is structurally a topic listing
     * scoped to the user's private inbox. Always uses the authenticated client — there is
     * no anonymous variant of this page (HFR redirects to login).
     */
    suspend fun getPrivateMessageListPage(page: Int = 1): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("forum1.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("cat", "prive")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("subcat", "")
            .addQueryParameter("sondage", "0")
            .addQueryParameter("owntopic", "0")
            .addQueryParameter("trash", "0")
            .addQueryParameter("trash_post", "0")
            .addQueryParameter("moderation", "0")
            .addQueryParameter("new", "0")
            .addQueryParameter("nojs", "0")
            .addQueryParameter("subcatgroup", "0")
            .build()

        val request = Request.Builder().url(url).get().build()
        return authenticated.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * Phase 2C — GET the HFR reply or quote form. The shape is the same in both
     * cases (`/message.php?cat=…&post=…&page=…&p=1&subcat=…&sondage=0&owntopic=0
     * &new=0`); a quote additionally carries `numrep={quotedNumreponse}` and
     * `ref={quoteRef}` so HFR prefills `<textarea name="content_form">` with the
     * cited `[quotemsg=…]` block (cf. `docs/specs/protocol-hfr.md` § Quote, and
     * the Phase 2A fixtures `write_reply_form_open_topic.html` /
     * `write_quote_form_test_post.html`).
     *
     * [quotedNumreponse] and [quoteRef] are both opaque — `numrep` is the cited
     * post id and `ref` is HFR's per-page positional id (server-controlled). The
     * caller must pass them through unchanged from the topic page HTML. Either
     * may be null for a simple reply ; both null = reply, both non-null = quote.
     * The mixed shape (one null, one set) is **not validated** — behaviour was
     * never captured and we leave the API surface tolerant in case a future HFR
     * change drops `ref` from the quote contract. Call sites in
     * `DefaultReplyRepository` always feed the two fields together from a
     * `ReplyContext`, so this looseness is contained to the network layer.
     *
     * Always uses the authenticated client : a session-expired GET surfaces
     * [SessionExpiredException] via [executeAuthenticatedHtml] rather than
     * silently returning the anonymous composer.
     */
    @Suppress("LongParameterList") // HFR contract: 4 mandatory ids + 2 optional quote params.
    suspend fun getReplyForm(
        cat: Int,
        subcat: Int,
        post: Int,
        page: Int,
        quotedNumreponse: Int? = null,
        quoteRef: Int? = null,
    ): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("message.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("cat", cat.toString())
            .addQueryParameter("post", post.toString())
            .apply {
                // Quote params come before `page` in the canonical HFR URL —
                // mirror that order so a `recorded.requestUrl` round-trip is
                // textually identical to what the web composer sends.
                quotedNumreponse?.let { addQueryParameter("numrep", it.toString()) }
                quoteRef?.let { addQueryParameter("ref", it.toString()) }
            }
            .addQueryParameter("page", page.toString())
            .addQueryParameter("p", "1")
            .addQueryParameter("subcat", subcat.toString())
            .addQueryParameter("sondage", "0")
            .addQueryParameter("owntopic", "0")
            .addQueryParameter("new", "0")
            .build()
        val request = Request.Builder().url(url).get().build()
        return authenticated.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * Phase 2C (#145) — POST the reply payload to `bddpost.php`. The [formBody]
     * is built by the repository from the parsed [ReplyForm] (hidden fields +
     * `hash_check` + the user's BBCode `content_form`). HFR never returns a
     * proper HTTP error code on failure: success and the four documented error
     * variants (`empty`, `invalid_token`, `antiflood`, `locked`) all come back as
     * HTTP 200 with distinct body text — see `ReplySubmitResponseParser` for the
     * classification.
     *
     * `hash_check` is **never** logged, including on transport errors.
     */
    suspend fun submitReply(formBody: FormBody): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("bddpost.php")
            .addQueryParameter("config", "hfr.inc")
            .build()
        val request = Request.Builder().url(url).post(formBody).build()
        return authenticated.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * Phase 2D (#147) — GET the HFR edit form for a post the user owns. The URL
     * shape is the same as the reply form, but with a `numreponse={N}` parameter
     * that tells HFR to render the « edit existing post » composer (prefills
     * `<textarea name=content_form>` with the post's current BBCode + flips the
     * action target to `bdd.php`). `numreponse` is unique per category, so the
     * full `(cat, post, page, subcat, numreponse)` tuple is always required —
     * cf. `docs/specs/protocol-hfr.md` § Edit post and the Phase 2A fixture
     * `write_edit_form_test_post.html`.
     *
     * Always uses the authenticated client : edit is destructive enough that we
     * never want to land on the anonymous composer by accident
     * ([SessionExpiredException] is raised instead of silently returning login
     * HTML).
     */
    @Suppress("LongParameterList") // HFR contract : 5 mandatory ids.
    suspend fun getEditPostForm(
        cat: Int,
        subcat: Int,
        post: Int,
        page: Int,
        numreponse: Int,
    ): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("message.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("cat", cat.toString())
            .addQueryParameter("post", post.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("p", "1")
            .addQueryParameter("subcat", subcat.toString())
            .addQueryParameter("sondage", "0")
            .addQueryParameter("owntopic", "0")
            .addQueryParameter("new", "0")
            .addQueryParameter("numreponse", numreponse.toString())
            .build()
        val request = Request.Builder().url(url).get().build()
        return authenticated.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * Phase 2D (#147) — POST the edit payload to `bdd.php` (not `bddpost.php` —
     * HFR routes the two flows through distinct endpoints). The [formBody]
     * carries the same shape as a reply POST (`hash_check`, `verifrequet=1100`,
     * `cat`/`subcat`/`post`/`page`/`sujet`, options) plus the edited post's
     * `numreponse={N}` and a blank `numrep`. The repository is responsible for
     * filtering out the `delete=1` flag that the edit form also exposes —
     * deletion is out of scope for the edit MVP.
     *
     * Same success-vs-error classification as reply : HFR returns HTTP 200 with
     * distinct body text in both cases ; the response parser disambiguates.
     */
    suspend fun submitEditPost(formBody: FormBody): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("bdd.php")
            .addQueryParameter("config", "hfr.inc")
            .build()
        val request = Request.Builder().url(url).post(formBody).build()
        return authenticated.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * Phase 2E (#149) — GET the HFR new-topic form for [cat] / [entrySubcat].
     * `entrySubcat` is nullable because the user can land on the create-topic
     * composer either with a sub-category chip selected (`entrySubcat = 550`)
     * or from the « Toutes » view (`entrySubcat = null` → `subcat=0` in the
     * query, which is exactly how HFR serves the form when nothing is
     * pre-selected). The composer is always opened in non-poll mode
     * (`sondage=0`) — active poll creation lives outside #149.
     *
     * Always uses the authenticated client : a session that has just expired
     * must raise [SessionExpiredException] rather than silently returning the
     * anonymous composer HFR happens to serve in that case.
     */
    suspend fun getNewTopicForm(cat: Int, entrySubcat: Int?): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("message.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("cat", cat.toString())
            .addQueryParameter("subcat", (entrySubcat ?: 0).toString())
            .addQueryParameter("sondage", "0")
            .addQueryParameter("owntopic", "0")
            .addQueryParameter("new", "0")
            .build()
        val request = Request.Builder().url(url).get().build()
        return authenticated.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * Phase 2E (#149) — POST the new-topic payload to `bddpost.php`. The wire
     * endpoint is identical to reply/quote, but the body differs (no `post`,
     * `numreponse=""`, `numrep=""`, writable `sujet` + `subcat`). The
     * repository builds [formBody] and is responsible for filtering
     * `password`, `delete` and any inadvertent poll keys.
     *
     * Same HTTP-200-on-failure classification as reply : success and the four
     * documented error variants all come back with distinct body text, see
     * `ReplySubmitResponseParser` for the dispatch.
     */
    suspend fun submitNewTopic(formBody: FormBody): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("bddpost.php")
            .addQueryParameter("config", "hfr.inc")
            .build()
        val request = Request.Builder().url(url).post(formBody).build()
        return authenticated.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * Phase 2F-B (#11 partial) — GET HFR's wiki smiley search endpoint. The endpoint accepts
     * `user_id=0` for anonymous probes (cf. `message-smi-mp-aj.php` capture 2026-05-22) but a
     * logged-in form provides the user's own id so HFR pages favourites first.
     *
     * URL : `/message-smi-mp-aj.php?config=hfr.inc&user_id={userId}&findsmilies={query}`.
     * Returns an HTML fragment (no `<html>/<body>` wrapper) consumed by `SmileySearchParser`.
     *
     * Uses the **anonymous** client : the endpoint does not gate on session, and routing through
     * the anonymous jar avoids any chance of leaking the user's cookies into a debug log of the
     * search query (the URL itself contains the `findsmilies=` payload, which is fine).
     */
    suspend fun getSmileySearch(userId: Int, query: String): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("message-smi-mp-aj.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("user_id", userId.toString())
            .addQueryParameter("findsmilies", query)
            .build()
        val request = Request.Builder().url(url).get().build()
        return anonymous.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * Executes the call, returns the body as a UTF-8 string, and raises
     * [SessionExpiredException] if HFR redirected to the login page or returned the login form
     * inline. When [tracePrefix] is non-null, the OkHttp call up to headers is wrapped in
     * `<prefix>.network` and the body pull in `<prefix>.body_read` (cf. `docs/guides/profiling.md`).
     * Callers that don't belong to the topic parcours pass `null` to stay out of `rf2.topic.*`.
     *
     * Wraps the blocking OkHttp `.execute()` in `withContext(ioDispatcher)` so callers can safely
     * invoke `HfrClient.*` from any coroutine context, including `viewModelScope.launch {}`'s
     * default `Dispatchers.Main.immediate`. Repositories may keep their own `withContext` for
     * defense in depth (notably to cover CPU-bound parsers in the same IO block), but they are
     * not required to do so to avoid `NetworkOnMainThreadException`.
     */
    private suspend fun Call.executeAuthenticatedHtml(tracePrefix: String? = null): String =
        withContext(ioDispatcher) {
            val response: Response = if (tracePrefix != null) {
                trace("$tracePrefix.network") { execute() }
            } else {
                execute()
            }
            response.use {
                if (!response.isSuccessful) {
                    throw IOException("HFR returned ${response.code} for ${response.request.url}")
                }
                val html = if (tracePrefix != null) {
                    // Session-expiry detection (login redirect / login form sniff) runs after the
                    // body is in memory, so its cost is negligible relative to body_read; not
                    // worth a third section.
                    trace("$tracePrefix.body_read") { response.body.string() }
                } else {
                    response.body.string()
                }
                val finalUrl = response.request.url
                if (finalUrl.isLoginUrl() || html.looksLikeLoginPage()) {
                    throw SessionExpiredException(finalUrl.toString())
                }
                html
            }
        }

    private companion object {
        // Prefix consumed by `docs/guides/profiling.md` — keep in lockstep with the catalogue.
        private const val TOPIC_TRACE_PREFIX = "rf2.topic"
    }

    private fun HttpUrl.isLoginUrl(): Boolean =
        encodedPath.endsWith("/login.php") || encodedPath.endsWith("/login_validation.php")

    private fun String.looksLikeLoginPage(): Boolean {
        val lower = lowercase()
        return "login_validation.php" in lower &&
            "name=\"pseudo\"" in lower &&
            "name=\"password\"" in lower
    }
}
