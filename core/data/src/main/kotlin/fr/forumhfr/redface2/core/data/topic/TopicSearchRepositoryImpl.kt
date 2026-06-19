package fr.forumhfr.redface2.core.data.topic

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.topic.NoTopicSearchResultsException
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
                "onlyMatches=${request.onlyMatches} isStep=${request.isStep} " +
                "hasCursor=${!request.currentNum.isNullOrBlank()}",
        )
        val html = client.searchInTopic(
            cat = request.form.cat,
            topicId = request.form.topicId,
            word = request.word,
            spseudo = request.spseudo,
            onlyMatches = request.onlyMatches,
            hashCheck = request.form.hashCheck,
            // #546 — on ne renvoie JAMAIS firstnum (le client omet firstnum+dep quand firstnum=null) :
            // avec firstnum HFR ancre la recherche en avant de la page courante et rate les matches
            // antérieurs ; sans firstnum elle couvre tout le topic — vérifié live #546/bug tinc 2788609.
            // Fresh comme step l'omettent : la recherche fraîche trouve le 1er match du topic entier,
            // puis next/prev avance via currentnum.
            firstnum = null,
            owntopic = request.form.owntopic,
            currentnum = request.currentNum,
        )
        if (html.hasNoSearchResults()) {
            // Chantier B (#546) — HFR rendered « aucune réponse n'a été trouvée » (a frequent term
            // hitting the MyISAM fulltext 50%-rule, or a genuine miss). Raise a typed « no result »
            // BEFORE parsing so the empty page never surfaces as a misleading « recherche échouée ».
            throw NoTopicSearchResultsException()
        }
        // The transsearch response IS a topic page (documented contract). Re-parse with the canonical
        // topic-page parser ; an unexpected shape would surface as a normal parse error the ViewModel
        // reports, never a poisoned cache row.
        parser.parseTopicPage(html)
    }

    /**
     * HFR's « no result » page is a short body carrying the literal « aucune réponse n'a été trouvée »
     * inside a `.hop` block (verified live, Chantier B / #546). The marker text is the robust signal —
     * the page length varies — so we match case-insensitively on it.
     */
    private fun String.hasNoSearchResults(): Boolean =
        contains(NO_RESULT_MARKER, ignoreCase = true)

    private companion object {
        private const val LOG_TAG = "TopicSearchRepository"

        // Literal text HFR renders when transsearch matched nothing. Anchored on the message, not the
        // `.hop` wrapper class, so a future markup tweak around the same copy keeps working.
        private const val NO_RESULT_MARKER = "aucune réponse n'a été trouvée"
    }
}
