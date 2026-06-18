package fr.forumhfr.redface2.core.data.topic

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.topic.TopicSearchRepository
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.model.TopicSearchRequest
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Chantier C (#546) — default [TopicSearchRepository] hitting HFR's `POST /transsearch.php` and
 * re-parsing the response (a topic page) into a [Topic] via the shared [HfrParser.parseTopicPage].
 *
 * Both the HTTP call and the parse run inside a single `withContext(ioDispatcher)` block, mandated
 * by `feedback_repos_must_wrap_io` (the Jsoup pass and `.execute()` must never land on
 * `Dispatchers.Main.immediate`).
 *
 * **Privacy** : the search term ([TopicSearchRequest.word]) and the author filter
 * ([TopicSearchRequest.spseudo]) are free user text ; the `hash_check` is a session secret. NONE of
 * them is ever logged — diagnostics record only presence flags + the `onlyMatches` mode, never the
 * values. The network layer never logs the POST body either.
 *
 * **Cache** : intentionally none. A `transsearch` response is a transient, possibly-filtered view
 * of the topic ; persisting it as the `(cat, post, page)` row would corrupt the cache the normal
 * topic flow reads (cf. `TopicRepositoryImpl`). It is parsed and handed straight back to the
 * ViewModel.
 */
@Singleton
class TopicSearchRepositoryImpl @Inject constructor(
    private val client: HfrClient,
    private val parser: HfrParser,
    private val diagnostics: DiagnosticsLog,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TopicSearchRepository {

    override suspend fun searchInTopic(request: TopicSearchRequest): Topic = withContext(ioDispatcher) {
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            // No word / spseudo / hash_check verbatim — only presence flags + the filter mode.
            "POST transsearch cat=${request.form.cat} post=${request.form.topicId} " +
                "hasWord=${request.word.isNotBlank()} hasPseudo=${request.spseudo.isNotBlank()} " +
                "onlyMatches=${request.onlyMatches} hasCursor=${!request.currentNum.isNullOrBlank()}",
        )
        val html = client.searchInTopic(
            cat = request.form.cat,
            topicId = request.form.topicId,
            word = request.word,
            spseudo = request.spseudo,
            onlyMatches = request.onlyMatches,
            hashCheck = request.form.hashCheck,
            firstnum = request.form.firstnum,
            owntopic = request.form.owntopic,
            currentnum = request.currentNum,
        )
        // The transsearch response IS a topic page (documented contract — never observed live, so
        // best-effort). Re-parse with the canonical topic-page parser ; an unexpected shape would
        // surface as a normal parse error the ViewModel reports, never a poisoned cache row.
        parser.parseTopicPage(html)
    }

    private companion object {
        private const val LOG_TAG = "TopicSearchRepository"
    }
}
