package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.data.preferences.normalizeLocalAccountOwner
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.ApplicationScope
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageMutationResult
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.parser.smiley.SmileyUsageExtractor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.FormBody

/** Best-effort local accounting; callers invoke [record] only after classifying an HFR success. */
@Singleton
class SuccessfulSmileyUsageRecorder @Inject constructor(
    private val authRepository: AuthRepository,
    private val usageRepository: SmileyUsageRepository,
    private val diagnostics: DiagnosticsLog,
    @param:ApplicationScope private val externalScope: CoroutineScope,
) {
    /** Capture before the POST, never look up the current account after the response. */
    suspend fun captureOwner(): String? = try {
        val state = authRepository.observeAuthState().first()
        (state as? AuthState.Authenticated)?.pseudo
            ?.takeIf { it.isNotBlank() }
            ?.let(::normalizeLocalAccountOwner)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        reportFailure()
        null
    }

    /**
     * ApplicationScope runs extraction and storage off the sending coroutine, survives screen closure,
     * and never awaits disk on the send path. Process death may lose a count (ADR-019).
     * [formBody] supplies the decoded content_form actually posted; null [initialContent] means creation.
     */
    fun record(owner: String?, formBody: FormBody, smileyDisabled: Boolean, initialContent: String? = null) {
        if (owner.isNullOrBlank() || smileyDisabled) return
        externalScope.launch {
            try {
                val content = formBody.contentForm() ?: return@launch
                val before = initialContent?.let(SmileyUsageExtractor::extract).orEmpty()
                val delta = SmileyUsageExtractor.extract(content)
                    .mapValues { (token, count) -> (count - (before[token] ?: 0)).coerceAtLeast(0) }
                    .filterValues { it > 0 }
                if (delta.isNotEmpty() && usageRepository.record(owner, delta) is SmileyUsageMutationResult.Failure) {
                    reportFailure()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                // No raw exception/message: either may contain private content or smiley tokens.
                reportFailure()
            }
        }
    }

    private fun reportFailure() {
        diagnostics.record(DiagnosticsLog.Level.WARN, "SmileyUsage", "Local usage recording unavailable")
    }
}

/** Read the wire value rather than independently reproducing sanitization at the accounting sites. */
private fun FormBody.contentForm(): String? =
    (0 until size).firstOrNull { name(it) == "content_form" }?.let(::value)
