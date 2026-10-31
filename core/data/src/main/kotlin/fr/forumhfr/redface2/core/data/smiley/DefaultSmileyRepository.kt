package fr.forumhfr.redface2.core.data.smiley

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.SmileyWikiKeywordsResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.smiley.SmileySearchParser
import fr.forumhfr.redface2.core.parser.smiley.SmileyWikiKeywordsParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Phase 2F-B (#11 partial) — default [SmileyRepository] hitting HFR's
 * `message-smi-mp-aj.php` endpoint and parsing the returned fragment via [SmileySearchParser].
 *
 * The HTTP call + parse are both wrapped in `withContext(ioDispatcher)` to follow the repo-
 * wide convention pinned by `feedback_repos_must_wrap_io` (cf. #161 fallout). Diagnostics
 * never carry the [query] verbatim — it can contain free text the user typed mid-message —
 * we only log its length.
 */
@Singleton
class DefaultSmileyRepository @Inject constructor(
    private val hfrClient: HfrClient,
    private val parser: SmileySearchParser,
    private val diagnostics: DiagnosticsLog,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val keywordsParser: SmileyWikiKeywordsParser,
) : SmileyRepository {

    // Owned by this singleton, not by an editor or an account. Synchronize even reads: accessOrder
    // mutates the LRU on a hit. No lock spans network IO, so independent details can load in parallel.
    private val keywordsCache = LinkedHashMap<String, SmileyWikiKeywordsResult.Loaded>(16, 0.75f, true)

    override fun cachedWikiKeywords(token: String): SmileyWikiKeywordsResult.Loaded? =
        synchronized(keywordsCache) { keywordsCache[token] }

    override suspend fun getWikiKeywords(token: String): SmileyWikiKeywordsResult {
        if (!token.startsWith("[:") || !token.endsWith("]")) return SmileyWikiKeywordsResult.NotApplicable
        return cachedWikiKeywords(token) ?: withContext(ioDispatcher) {
            runCatching {
                val html = hfrClient.getSmileyWikiDetail(token)
                val result = SmileyWikiKeywordsResult.Loaded(keywordsParser.parse(html, token))
                currentCoroutineContext().ensureActive()
                synchronized(keywordsCache) {
                    keywordsCache[token] = result
                    if (keywordsCache.size > MAX_WIKI_KEYWORDS_ENTRIES) {
                        keywordsCache.remove(keywordsCache.keys.first())
                    }
                }
                result
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                SmileyWikiKeywordsResult.Failed
            }
        }
    }

    override suspend fun searchWiki(userId: Int, query: String): List<EditorSmiley> {
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "GET wiki smiley search hasUserId=${userId > 0} queryLength=${query.length}",
        )
        // No catch-all WARN here — the ViewModel already logs the failure via its own tag,
        // and a double log produced two near-identical entries in the diagnostics panel on
        // the same network error. `CancellationException` still propagates untouched via
        // `withContext`'s structured concurrency.
        return withContext(ioDispatcher) {
            val fragment = hfrClient.getSmileySearch(userId = userId, query = toImplicitAndQuery(query))
            val results = parser.parse(fragment)
            diagnostics.record(
                DiagnosticsLog.Level.DEBUG,
                LOG_TAG,
                "wiki smiley search results=${results.size}",
            )
            results
        }
    }

    internal companion object {
        private const val LOG_TAG = "SmileyRepository"
        const val MAX_WIKI_KEYWORDS_ENTRIES = 256
    }
}

/**
 * #794 — implicit AND between search terms. The wiki engine treats a bare space as OR (union,
 * server-capped at 1000 rows), `+term` as a requirement and `-term` as an exclusion — all three
 * live-verified on `message-smi-mp-aj.php` (2026-07-05 : `chat noir`=1000, `+chat +noir`=25,
 * `+chat -noir`=657−25). Nobody searching « chat noir » wants the union, so every UNOPERATED term
 * gets a `+` prefix ; terms the user already prefixed with `+` or `-` keep their operator (the
 * NOT is functional and must not be clobbered). Pure and unit-tested ; blank input is returned
 * untouched.
 */
internal fun toImplicitAndQuery(raw: String): String {
    val terms = raw.trim().split(WHITESPACE).filter { it.isNotBlank() }
    if (terms.isEmpty()) return raw
    return terms.joinToString(" ") { term ->
        if (term.startsWith('+') || term.startsWith('-')) term else "+$term"
    }
}

private val WHITESPACE = Regex("""\s+""")
