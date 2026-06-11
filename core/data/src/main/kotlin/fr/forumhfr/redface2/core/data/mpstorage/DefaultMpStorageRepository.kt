package fr.forumhfr.redface2.core.data.mpstorage

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageThreadParser
import fr.forumhfr.redface2.core.parser.mpstorage.MpStorageDiscoveryParser
import fr.forumhfr.redface2.core.parser.mpstorage.MpStorageParser
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Default [MpStorageRepository] (#6, ADR-014) — three authenticated GETs, zero writes :
 *
 *  1. subject search (`forum1.php?recherches=1&cat=prive&search=<hash>&titre=1`) →
 *     [MpStorageDiscoveryParser] yields the storage conversation's thread id, or `null`
 *     (no storage on this account → [MpStorageResult.NotFound], the nominal case) ;
 *  2. conversation page 1 → the FIRST post's `numreponse` (the storage document lives in
 *     the first post, per the de-facto contract) ;
 *  3. edit form of that post → `content_form` (raw text) → [MpStorageParser].
 *
 * Diagnostics never log the document content (it aggregates private reading positions from
 * every userscript) — only presence flags, sizes and failure classes, same #316 stance as
 * the other private-message repositories.
 */
@Singleton
@Suppress("LongParameterList") // One dep per pipeline stage (3 GETs, 3 parsers) + diagnostics, clock, dispatcher.
class DefaultMpStorageRepository @Inject constructor(
    private val hfrClient: HfrClient,
    private val discoveryParser: MpStorageDiscoveryParser,
    private val threadParser: PrivateMessageThreadParser,
    private val replyFormParser: ReplyFormParser,
    private val storageParser: MpStorageParser,
    private val diagnostics: DiagnosticsLog,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MpStorageRepository {

    override suspend fun fetchStorage(): MpStorageResult {
        return try {
            withContext(ioDispatcher) {
                val searchHtml = hfrClient.searchPrivateMessagesBySubject(
                    subject = MpStorageRepository.STORAGE_SUBJECT_HASH,
                    date = LocalDate.now(clock),
                )
                val threadId = discoveryParser.parseFirstThreadId(searchHtml)
                if (threadId == null) {
                    diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "discovery: no storage MP")
                    return@withContext MpStorageResult.NotFound
                }
                diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "discovery: storage MP found")

                val thread = threadParser.parse(hfrClient.getPrivateMessageThreadPage(threadId, page = 1))
                val firstNumreponse = thread.messages.firstOrNull()?.numreponse
                if (firstNumreponse == null) {
                    diagnostics.record(DiagnosticsLog.Level.WARN, LOG_TAG, "storage thread has no first post")
                    return@withContext MpStorageResult.Unreadable
                }

                val form = replyFormParser
                    .parse(hfrClient.getPrivateMessageEditForm(threadId, firstNumreponse))
                    .getOrElse { error ->
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "edit form parse FAILED: ${error::class.simpleName}",
                        )
                        return@withContext MpStorageResult.Unreadable
                    }
                if (form.isAnonymous) {
                    // The session evaporated between the search and the edit form GET.
                    throw SessionExpiredException("MPStorage edit form served anonymous composer")
                }

                storageParser.parse(form.initialContent).fold(
                    onSuccess = { document ->
                        diagnostics.record(
                            DiagnosticsLog.Level.INFO,
                            LOG_TAG,
                            "storage parsed: flags=${document.mpFlags.size} " +
                                "rawSize=${document.rawEnvelope.length}",
                        )
                        MpStorageResult.Found(document)
                    },
                    onFailure = { error ->
                        // ADR-014 : an unreadable document is surfaced, NEVER repaired.
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "storage document unreadable: ${error::class.simpleName}",
                        )
                        MpStorageResult.Unreadable
                    },
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: SessionExpiredException) {
            diagnostics.record(DiagnosticsLog.Level.WARN, LOG_TAG, "fetchStorage SessionExpired")
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "fetchStorage FAILED: ${error::class.simpleName}",
            )
            throw error
        }
    }

    private companion object {
        private const val LOG_TAG = "MpStorageRepository"
    }
}
