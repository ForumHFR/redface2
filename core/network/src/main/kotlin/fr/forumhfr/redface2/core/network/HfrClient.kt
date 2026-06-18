package fr.forumhfr.redface2.core.network

import androidx.tracing.trace
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.search.SearchTextScope
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
                        // #324 — typed so the read screens can tell a 5xx outage from a
                        // local network cut (cf. core.domain.error.classifyHfrError).
                        throw HfrServerException(response.code, url.toString())
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
     * Fetches one authenticated page of a private-message conversation. The endpoint is the
     * topic page (`forum2.php?config=hfr.inc&cat=prive&post={threadId}&page={page}`) scoped to
     * the private category — structurally identical to a topic, which is why the response is
     * parsed by the shared post extractor. Always authenticated: HFR has no anonymous variant
     * of a private conversation (it redirects to login).
     */
    suspend fun getPrivateMessageThreadPage(threadId: Int, page: Int = 1): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("forum2.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("cat", "prive")
            .addQueryParameter("post", threadId.toString())
            .addQueryParameter("page", page.toString())
            .build()

        val request = Request.Builder().url(url).get().build()
        return authenticated.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * #301 follow-up — GET the « new private message » composer. The URL is the one HFR's own
     * « Créer un nouveau message » buttons carry on the MP list (captured live 2026-06-11,
     * fixture `mp_compose_form.html`) : `message.php?config=hfr.inc&cat=prive&sond=0&p=1
     * &subcat=0&dest=&subcatgroup=0` — `message.php` **without** `post=` opens the standalone
     * MP composer. [prefilledDest] pre-fills the recipient field server-side (verified live :
     * `dest=foo` renders `<input name="dest" value="foo">`), handy for a future « send a MP to
     * this user » entry point ; the empty default mirrors HFR's own buttons.
     *
     * Always authenticated : composing toward the anonymous form would only produce a
     * [ReplyForm.isAnonymous] refusal downstream — surface [SessionExpiredException] instead.
     */
    suspend fun getPrivateMessageComposePage(prefilledDest: String? = null): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("message.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("cat", "prive")
            .addQueryParameter("sond", "0")
            .addQueryParameter("p", "1")
            .addQueryParameter("subcat", "0")
            .addQueryParameter("dest", prefilledDest.orEmpty())
            .addQueryParameter("subcatgroup", "0")
            .build()

        val request = Request.Builder().url(url).get().build()
        return authenticated.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * MPStorage read (#6, ADR-014) — GET the edit form of a private-message post the user
     * owns. The textarea `content_form` carries the RAW storage document (not rendered
     * HTML), exactly how `MPStorage.user.js` reads it. Same `message.php` family as
     * [getEditForm], with `cat=prive` (a String — the typed public variant cannot carry it).
     */
    suspend fun getPrivateMessageEditForm(threadId: Int, numreponse: Int): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("message.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("cat", "prive")
            .addQueryParameter("post", threadId.toString())
            .addQueryParameter("numreponse", numreponse.toString())
            .addQueryParameter("page", "1")
            .addQueryParameter("p", "1")
            .addQueryParameter("subcat", "0")
            .addQueryParameter("sondage", "0")
            .addQueryParameter("owntopic", "0")
            .addQueryParameter("new", "0")
            .build()
        val request = Request.Builder().url(url).get().build()
        return authenticated.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * Phase 2C — GET the HFR reply or quote form. The shape is the same in both
     * cases (`/message.php?cat=…&post=…&page=…&p=1&subcat=…&sondage=0&owntopic=0
     * &new=0`); a quote carries `numrep={quotedNumreponse}` and may additionally
     * carry `ref={quoteRef}` when HFR exposed it in a clear toolbar link. HFR still
     * prefills `<textarea name="content_form">` from `numrep` alone when `ref` is
     * absent (cf. `docs/specs/protocol-hfr.md` § Quote / md_*cryptlink).
     *
     * [quotedNumreponse] and [quoteRef] are opaque — `numrep` is the cited post id
     * and `ref` is HFR's per-page positional id (server-controlled). `quoteRef` is
     * optional by design: obfuscated toolbar rows can still be quoted by sending
     * only `numrep`. Simple reply = both null ; quote fallback = `quotedNumreponse`
     * non-null and `quoteRef` null ; clear-link quote = both non-null. A lone
     * `quoteRef` with no `quotedNumreponse` is rejected by `ReplyContext`.
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
     * MPStorage write (#6, ADR-014 §4) — POST the edited FIRST post of the dedicated storage MP to
     * `bdd.php?config=hfr.inc`, the same endpoint as [submitEditPost]. The ONLY wire difference is
     * that the storage post lives under `cat=prive`, so the [formBody] carries `cat=prive` as a
     * **String** (the public edit flow passes `cat` as an Int) — that is why this is a distinct
     * method rather than a reuse of [submitEditPost] : the typed public path cannot express `prive`.
     * Everything else (`hash_check`, `verifrequet`, `content_form`, `numreponse`, `sujet`, the
     * preserved hidden fields) is shaped identically by the repository.
     *
     * NOT OBSERVED LIVE : the `bdd.php cat=prive` write contract has never been captured (no device
     * round-trip). The caller GUARDS this — it is reached only via the repository's module-internal,
     * test-only POST path (never from app/prod code). Same HTTP-200-with-body-text contract as the reply
     * / edit flows ; the response
     * (when ever exercised) is classified by `ReplySubmitResponseParser`. `hash_check` is never logged.
     */
    suspend fun submitPrivateMessageEdit(formBody: FormBody): String {
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
     * Phase 2G-A/B (#150 partiel) — fetches HFR's search result page.
     *
     * Wire shape (cf. fixtures `search_*.html` captured 2026-05-22) : HFR's search form lives
     * at `POST /search.php` but renders a 1-second meta-refresh wait page that redirects to
     * `GET /forum1.php?recherches=1&...` carrying every form parameter in the query string.
     * Skipping the wait page and hitting `forum1.php` directly is the canonical fetch path.
     *
     * - [query]     : the user's term, forwarded as-is. Echoed back in upper-case by HFR.
     * - [cat]       : the HFR cat id, encoded as `<id>*hfr.inc` (the form's expected shape).
     *                 Pass `null` for an all-categories search (encoded as an empty `cat=`).
     * - [page]      : 1-based pagination ; currently always 1 for the MVP UI but plumbed
     *                 through for the multi-page follow-up.
     * - [date]      : the « date d'aujourd'hui » HFR's form serialises even when `daterange=2`
     *                 (depuis le début) makes them functionally irrelevant. The repository
     *                 computes it from an injectable [java.time.Clock] so tests are
     *                 reproducible.
     * - [textScope] : HFR's `titre` field (`1` titles, `3` titles + posts, `0` posts).
     * - [pseudo]    : HFR's `pseud` field — author filter. `null`/blank = no filter (encoded
     *                 as the empty `pseud=` the form always carries). Author-only searches
     *                 (empty [query]) are supported by HFR ; all-categories author searches
     *                 answer a 302 onto the multi-cat pivot, which OkHttp follows.
     *
     * Uses the **anonymous** client : the search endpoint is public and we don't want the
     * user's session cookies attached to a request whose URL contains the search payload
     * (logged in proxies / debug captures). The repository layer translates network
     * failures to typed errors and is responsible for stripping the `search=` parameter
     * before logging.
     */
    @Suppress("LongParameterList") // HFR search form : one parameter per wire field.
    suspend fun searchTopics(
        query: String,
        cat: Int?,
        page: Int,
        date: java.time.LocalDate,
        textScope: SearchTextScope,
        pseudo: String? = null,
    ): String {
        val orderSearch = if (textScope == SearchTextScope.TitlesOnly) {
            ORDER_BY_LAST_TOPIC_REPLY
        } else {
            ORDER_BY_MATCHED_MESSAGE_DATE
        }
        val url = baseUrl.newBuilder()
            .addPathSegment("forum1.php")
            .addQueryParameter("recherches", "1")
            .addQueryParameter("cat", cat?.let { "$it*hfr.inc" } ?: "")
            .addQueryParameter("orderSearch", orderSearch)
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("pseud", pseudo.orEmpty())
            .addQueryParameter("search", query)
            .addQueryParameter("titre", textScope.hfrTitreValue.toString())
            .addQueryParameter("jour", date.dayOfMonth.toString())
            .addQueryParameter("mois", date.monthValue.toString())
            .addQueryParameter("annee", date.year.toString())
            .addQueryParameter("resSearch", "20")
            .addQueryParameter("daterange", "2")
            .addQueryParameter("subcat", "0")
            .addQueryParameter("searchtype", "1")
            .addQueryParameter("trash", "0")
            .addQueryParameter("trash_post", "0")
            .addQueryParameter("moderation", "0")
            .also { if (page > 1) it.addQueryParameter("page", page.toString()) }
            .build()
        val request = Request.Builder().url(url).get().build()
        return anonymous.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * Chantier C (#546) — POST the intra-topic search to `/transsearch.php`.
     *
     * **AUTHENTICATED by design.** `transsearch.php` rejects an empty `hash_check`, so the search is
     * only meaningful with the token parsed from an authenticated topic page. Routing through the
     * authenticated client also means a freshly-expired session surfaces [SessionExpiredException]
     * (via [executeAuthenticatedHtml]) instead of silently returning anonymous HTML.
     *
     * Wire shape (cf. the `transsearch` form in fixtures `topic_*.html` / `write_*topic*.html`,
     * captured 2026-05/06) :
     *
     * `POST /transsearch.php` with `hash_check`, `post`(=topic id), `cat`, `config=hfr.inc`, `p=1`,
     * `sondage=0`, `owntopic`, `word`, `spseudo`, `filter` (=`1` only when [onlyMatches]), `dep=0`,
     * `firstnum`, `currentnum`.
     *
     * - [onlyMatches] maps to the form's `filter` checkbox : included as `filter=1` only when `true`
     *   (an unchecked HTML checkbox sends no field at all), so HFR re-renders the page showing ONLY
     *   the matching messages. When `false` we omit `filter`, matching the unchecked form.
     * - [currentnum] is HFR's JS-managed navigation cursor. The static form has NO `currentnum`
     *   input — HFR's own script creates it and the submit button clears it. We therefore send it
     *   empty for a fresh search and only carry a value for the **EXPERIMENTAL / best-effort**
     *   next/previous navigation. **The `transsearch` response has NEVER been observed live** (no
     *   fixture), so the cursor semantics are unverified ; callers must treat navigation as
     *   best-effort and re-parse whatever topic page comes back.
     *
     * The response IS a topic page → the data layer re-parses it with the existing topic-page parser.
     *
     * `hashCheck`, `word` and `spseudo` are **never** logged here (cf. the submit-reply precedent ;
     * the repository performs the redacted diagnostics).
     */
    @Suppress("LongParameterList") // HFR transsearch form : one parameter per wire field.
    suspend fun searchInTopic(
        cat: Int,
        topicId: Int,
        word: String,
        spseudo: String,
        onlyMatches: Boolean,
        hashCheck: String,
        firstnum: Int,
        owntopic: Int = 0,
        currentnum: String? = null,
    ): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("transsearch.php")
            .build()
        val formBody = FormBody.Builder()
            .add("hash_check", hashCheck)
            .add("post", topicId.toString())
            .add("cat", cat.toString())
            .add("config", "hfr.inc")
            .add("p", "1")
            .add("sondage", "0")
            .add("owntopic", owntopic.toString())
            .add("word", word)
            .add("spseudo", spseudo)
            .apply {
                // An unchecked HTML checkbox sends no field at all ; mirror that so HFR's
                // server-side branch behaves exactly like the web form.
                if (onlyMatches) add("filter", "1")
            }
            .add("dep", "0")
            .add("firstnum", firstnum.toString())
            // HFR's JS clears `currentnum` on a fresh submit (empty string) and only sets it for
            // in-result navigation. Best-effort : the value is unverified (no live response capture).
            .add("currentnum", currentnum.orEmpty())
            .build()
        val request = Request.Builder().url(url).post(formBody).build()
        return authenticated.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * Phase 2 finish (#99) — GET `/user/delflag.php` to remove a single drapeau the user
     * owns. Per ADR-003 the drapeau mutations stay HTML (the REST `PUT topics/{id}/`
     * semantics for downgrade/no-op are opaque), so this is a GET on the legacy endpoint.
     *
     * Wire shape (captured on HFR réel for `FAVORITE -> owntopic=3`, cf.
     * `docs/specs/protocol-hfr.md` § Retirer un drapeau and the fixtures
     * `flag_delete_success.html` / `flag_delete_already_removed.html`) :
     *
     * `/user/delflag.php?config=hfr.inc&cat={cat}&subcat={subcat}&post={topicId}
     * &page={page}&p=1&sondage=0&owntopic={1|2|3}&new=0`
     *
     * - [type] maps to the `owntopic` discriminator that identifies the drapeau bucket :
     *   `CYAN → 1`, `RED → 2`, `FAVORITE → 3`. This is a WRITE-side bucket selector only —
     *   do not read it back from the REST `flag_owntopic` response field, which describes
     *   the strongest flag ON the topic, not bucket membership (cf. `Flag.kt`, #384).
     *   Targeting the right bucket matters : HFR keys the deletion on it.
     *   The `FAVORITE` branch is proven by a destructive capture ; `CYAN` / `RED` reuse the
     *   same proven `owntopic` discriminant and should be recaptured when safe.
     * - [subcat] is nullable. REST flag listings do not always carry a sub-category, so we
     *   emit an empty `subcat=` when it is null — mirroring how HFR's own listing links
     *   serialise a missing sub-category, and matching the `getPrivateMessageListPage`
     *   precedent of an empty `subcat`.
     * - [page] is the drapeau's `lastReadPage` (its current page), forwarded verbatim.
     *
     * Returns the response HTML for [fr.forumhfr.redface2.core.parser.write] to classify :
     * success carries « Drapeau effacé avec succès », anything else (e.g. an already-removed
     * favourite) does not. HFR returns HTTP 200 in both cases, so the body text is the only
     * signal.
     *
     * Always uses the authenticated client : delflag is a destructive mutation, a freshly
     * expired session must raise [SessionExpiredException] rather than silently hitting the
     * anonymous page.
     */
    suspend fun removeFlag(
        cat: Int,
        subcat: Int?,
        topicId: Int,
        type: FlagType,
        page: Int,
    ): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("user")
            .addPathSegment("delflag.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("cat", cat.toString())
            .addQueryParameter("subcat", subcat?.toString().orEmpty())
            .addQueryParameter("post", topicId.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("p", "1")
            .addQueryParameter("sondage", "0")
            .addQueryParameter("owntopic", type.toOwntopic().toString())
            .addQueryParameter("new", "0")
            .build()
        val request = Request.Builder().url(url).get().build()
        return authenticated.newCall(request).executeAuthenticatedHtml()
    }

    /**
     * Phase 2 finish (#208) — GET the public profile page for user [userId].
     *
     * Wire shape (cf. `docs/specs/protocol-hfr.md` § Profil public):
     * `GET /hfr/profil-{userId}.htm` — no authentication required for public profiles.
     *
     * The endpoint is documented in the endpoint table as `Profil public | non` (auth not
     * required). The fixture `profile_xatrix_authenticated.html` was captured in an
     * authenticated session but the page content is the same anonymously — the difference
     * is only in the HFR session cookie used, not in the profile page layout.
     *
     * Uses the [anonymous] client: profile reads must never mark drapeaux as read (the
     * prefetch-non-authentifié rule, cf. `docs/specs/protocol-hfr.md`). Using the
     * anonymous client also avoids leaking the user's session to a page whose content
     * is publicly visible.
     */
    suspend fun getProfile(userId: Int): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("hfr")
            .addPathSegment("profil-$userId.htm")
            .build()
        val request = Request.Builder().url(url).get().build()
        return withContext(ioDispatcher) {
            anonymous.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // #324 — typed so the profile sheet/page can tell a 5xx outage from a
                    // local network cut (cf. core.domain.error.classifyHfrError).
                    throw HfrServerException(response.code, url.toString())
                }
                response.body.string()
            }
        }
    }

    /**
     * Issue #277 — resolves the real topic page of a post by letting HFR's server-side
     * redirect answer for us.
     *
     * HFR's search result hrefs ALWAYS carry `page=1` (verified 2026-06-10 on both the
     * anonymous and the authenticated captures : 34/34 anchors), so the page in the href
     * is useless for navigation. The actual page is resolved server-side : requesting
     * `forum2.php?config=hfr.inc&cat={cat}&post={post}&page=1&numreponse={numreponse}`
     * returns a 301 whose `Location` is the **relative** pretty URL of the right page,
     * with a `#t{numreponse}` fragment. Live proof (2026-06-10, anonymous) :
     *
     * `GET …forum2.php?config=hfr.inc&cat=23&post=35421&page=1&numreponse=2786758`
     * → `301 Location: /hfr/gsmgpspda/redface-dev-sujet_35421_3.htm#t2786758` (page 3).
     *
     * The pretty path may or may not include the sub-category segment — callers must
     * anchor on the `sujet_{post}_{page}.htm` segment, never on path depth. Note that
     * [numreponse] is unique per **category**, not globally, hence the full
     * `(cat, post, numreponse)` tuple.
     *
     * Uses the [anonymousNoRedirect] client : anonymous because a probe must never
     * mark drapeaux as read (prefetch-non-authentifié rule), and no-follow because the
     * redirect target IS the payload — following it would download a full topic page
     * for nothing.
     *
     * @return the raw `Location` header of the 3xx response, or `null` when the
     * response is not a redirect, carries no `Location`, or the request fails with an
     * [IOException] (the caller falls back to the href page — never worse than today).
     */
    suspend fun resolveTopicPageUrl(cat: Int, post: Int, numreponse: Int): String? {
        val url = baseUrl.newBuilder()
            .addPathSegment("forum2.php")
            .addQueryParameter("config", "hfr.inc")
            .addQueryParameter("cat", cat.toString())
            .addQueryParameter("post", post.toString())
            .addQueryParameter("page", "1")
            .addQueryParameter("numreponse", numreponse.toString())
            .build()
        val request = Request.Builder().url(url).get().build()
        return withContext(ioDispatcher) {
            // The Location header is the only thing we want ; a network failure simply
            // degrades to the caller's fallback (search-href page). Other exception
            // kinds (cancellation, programming errors) keep propagating.
            @Suppress("SwallowedException")
            try {
                anonymousNoRedirect.newCall(request).execute().use { response ->
                    if (response.code in REDIRECT_CODE_RANGE) response.header("Location") else null
                }
            } catch (error: IOException) {
                null
            }
        }
    }

    /**
     * Anonymous client variant that does NOT follow redirects, for callers that consume
     * the `Location` header itself (cf. [resolveTopicPageUrl]). Derived once — sharing
     * the connection pool / dispatcher of [anonymous] — instead of being rebuilt per call.
     *
     * The tight [HfrConstants.ProbeCallTimeout] (3 s vs the 30 s default) is the REAL
     * timeout of the probe : the caller's `withTimeoutOrNull` cannot interrupt a blocking
     * `execute()`, so without it a degraded network would freeze the search tap (and its
     * in-flight guard) for the full default call timeout (promotion review finding).
     */
    private val anonymousNoRedirect: OkHttpClient by lazy {
        anonymous.newBuilder()
            .followRedirects(false)
            .callTimeout(HfrConstants.ProbeCallTimeout)
            .build()
    }

    /**
     * Maps [FlagType] to the WRITE-side `owntopic` bucket selector of `delflag.php` — and that
     * endpoint only (`addflag.php` ignores `owntopic` : favori-only, live-verified ; and the REST
     * `flag_owntopic` response field describes the strongest flag ON the topic, not the bucket,
     * cf. `Flag.kt`). Kept private to the network layer : the mapping is a wire detail of the
     * HFR contract, not a domain concept the rest of the app should know.
     */
    private fun FlagType.toOwntopic(): Int = when (this) {
        FlagType.CYAN -> 1
        FlagType.RED -> 2
        FlagType.FAVORITE -> 3
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
                    // #324 — typed so the read screens can tell a 5xx outage from a local
                    // network cut (cf. core.domain.error.classifyHfrError).
                    throw HfrServerException(response.code, response.request.url.toString())
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
        private const val ORDER_BY_MATCHED_MESSAGE_DATE = "0"
        private const val ORDER_BY_LAST_TOPIC_REPLY = "1"

        // 3xx — any redirect status whose Location header points at the resolved pretty URL
        // (HFR serves 301 in practice, cf. resolveTopicPageUrl ; the range is defensive).
        private val REDIRECT_CODE_RANGE = 300..399
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
