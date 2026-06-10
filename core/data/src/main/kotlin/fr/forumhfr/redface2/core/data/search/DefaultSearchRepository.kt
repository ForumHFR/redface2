package fr.forumhfr.redface2.core.data.search

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.search.SearchRepository
import fr.forumhfr.redface2.core.model.search.SearchCategoryScope
import fr.forumhfr.redface2.core.model.search.SearchRequest
import fr.forumhfr.redface2.core.model.search.SearchResultPage
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.search.SearchResultParser
import fr.forumhfr.redface2.core.parser.search.TopicPageUrlParser
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Phase 2G-A (#150 partiel) — default [SearchRepository] hitting HFR's
 * `forum1.php?recherches=1&...` endpoint and parsing the returned page via
 * [SearchResultParser].
 *
 * Both the HTTP call and the parse run inside a single `withContext(ioDispatcher)` block,
 * mandated by `feedback_repos_must_wrap_io` (cf. #161 regression fallout) — without it the
 * Jsoup pass and `.execute()` would land on the caller's dispatcher
 * (`Dispatchers.Main.immediate` in a `viewModelScope.launch {}`), risking
 * `NetworkOnMainThreadException` on Android.
 *
 * Diagnostics never carry the [SearchRequest.query] verbatim — a search term can be free
 * user text, including content lifted from a message draft. We log only the length, the
 * cat presence flag, and the page index. The author filter ([SearchRequest.pseudo]) is a
 * public username, but it rides the same URL as the query — same presence-flag treatment.
 *
 * On `IOException`, the catch site strips the URL (which contains `search=<query>`) from
 * the message before re-throwing, so the wrapped exception never leaks into a diagnostic
 * panel screenshot.
 */
@Singleton
class DefaultSearchRepository @Inject constructor(
    private val hfrClient: HfrClient,
    private val parser: SearchResultParser,
    private val diagnostics: DiagnosticsLog,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SearchRepository {

    // ThrowsCount: the three rethrow branches are the same redaction contract applied per
    // exception TYPE (#324 — SessionExpired / HfrServer / generic IOException); folding them
    // into one branch would either lose the type the downstream classifier needs or leak the
    // query-bearing URL. Suppressed locally rather than relaxing the project rule.
    @Suppress("ThrowsCount")
    override suspend fun search(request: SearchRequest): SearchResultPage {
        val catId = (request.category as? SearchCategoryScope.Category)?.id
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "GET forum1 search hasCat=${catId != null} scope=${request.textScope} " +
                "queryLength=${request.query.length} hasPseudo=${!request.pseudo.isNullOrBlank()} " +
                "page=${request.page}",
        )
        return withContext(ioDispatcher) {
            // HFR's form serialises today's date even when `daterange=2` makes it
            // functionally irrelevant. We compute from an injectable Clock so tests
            // can pin the date deterministically.
            val today = LocalDate.now(clock)
            // The original exception's message embeds the search URL (`HFR returned 500 for
            // https://forum.hardware.fr/forum1.php?recherches=1&search=<query>...`), which is
            // exactly the leak we're trying to prevent. We deliberately drop the cause so the
            // stack-trace screenshot of a diagnostics panel never carries the query — the
            // network-level details remain in `DiagnosticsLog` (status code only) and are
            // enough to diagnose the failure.
            @Suppress("SwallowedException")
            val html = try {
                hfrClient.searchTopics(
                    query = request.query,
                    cat = catId,
                    page = request.page,
                    date = today,
                    textScope = request.textScope,
                    pseudo = request.pseudo,
                )
            } catch (error: SessionExpiredException) {
                // `SessionExpiredException` extends `IOException` (so it's caught by the
                // generic branch too if we let it through) ; its message embeds the final
                // URL via "final URL was <url>" — which carries `search=<query>` — and the
                // generic `substringBefore(" for ")` strip wouldn't catch this prefix.
                // Handle it explicitly so neither variant leaks the query. #324: re-throw
                // the SAME type (with a redacted URL) instead of a generic IOException so
                // the shared error classifier downstream still sees the session expiry.
                throw SessionExpiredException(REDACTED_URL)
            } catch (error: HfrServerException) {
                // #324 — keep the TYPE and status code (the ViewModel classifies a 5xx as
                // « HFR est en panne », not as a network cut) while rebuilding the message
                // so the URL — which carries `search=<query>` — never leaks, matching the
                // redaction contract of the sibling branches.
                throw HfrServerException(error.code, REDACTED_URL)
            } catch (error: IOException) {
                throw IOException("HFR search request failed: ${error.message?.substringBefore(" for ")}")
            }
            parser.parse(
                html = html,
                query = request.query,
                requestedCategory = request.category,
                requestedPage = request.page,
            )
        }
    }

    /**
     * Issue #277 — page resolution through HFR's server-side redirect. The HfrClient
     * already degrades network failures to `null` ; this layer adds the Location →
     * page extraction (also `null`-degrading). Wrapped in `withContext(ioDispatcher)`
     * per the project rule (`feedback_repos_must_wrap_io`) even though the regex pass
     * is cheap — every repo path that reaches HfrClient hops to IO, no exception.
     *
     * Diagnostics never need redaction here : unlike [search], the probe URL carries
     * no user text — only the `(cat, post, numreponse)` ids.
     */
    override suspend fun resolveSearchResultPage(cat: Int, post: Int, numreponse: Int): Int? =
        withContext(ioDispatcher) {
            val location = hfrClient.resolveTopicPageUrl(cat = cat, post = post, numreponse = numreponse)
            val page = location?.let { TopicPageUrlParser.parseTopicPageFromUrl(url = it, post = post) }
            diagnostics.record(
                DiagnosticsLog.Level.INFO,
                LOG_TAG,
                "resolve result page cat=$cat post=$post numreponse=$numreponse -> " +
                    "page=$page (location=${if (location != null) "present" else "absent"})",
            )
            page
        }

    private companion object {
        private const val LOG_TAG = "SearchRepository"

        /**
         * Placeholder substituted for the search URL when re-throwing typed exceptions
         * (#324) : the original URL embeds `search=<query>` and must never survive into a
         * message that can land in a diagnostics screenshot.
         */
        private const val REDACTED_URL = "<redacted>"
    }
}
