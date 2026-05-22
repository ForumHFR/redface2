package fr.forumhfr.redface2.core.data.smiley

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.smiley.SmileySearchParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
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
) : SmileyRepository {

    override suspend fun searchWiki(userId: Int, query: String): List<EditorSmiley> {
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "GET wiki smiley search userId=$userId queryLength=${query.length}",
        )
        // No catch-all WARN here — the ViewModel already logs the failure via its own tag,
        // and a double log produced two near-identical entries in the diagnostics panel on
        // the same network error. `CancellationException` still propagates untouched via
        // `withContext`'s structured concurrency.
        return withContext(ioDispatcher) {
            val fragment = hfrClient.getSmileySearch(userId = userId, query = query)
            val results = parser.parse(fragment)
            diagnostics.record(
                DiagnosticsLog.Level.DEBUG,
                LOG_TAG,
                "wiki smiley search results=${results.size}",
            )
            results
        }
    }

    private companion object {
        private const val LOG_TAG = "SmileyRepository"
    }
}
