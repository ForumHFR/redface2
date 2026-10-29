package fr.forumhfr.redface2.core.data.smiley

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.smiley.HfrFavoriteSmileyRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.network.HfrFavoriteSmileyClient
import fr.forumhfr.redface2.core.parser.smiley.HfrFavoriteSmileyForm
import fr.forumhfr.redface2.core.parser.smiley.HfrFavoriteSmileyFormParser
import fr.forumhfr.redface2.core.parser.smiley.HfrFavoriteSmileyResponse
import fr.forumhfr.redface2.core.parser.smiley.HfrFavoriteSmileyResponseParser
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** #1154 B1 — no cache or optimistic list: only the current server form can authorize a rewrite. */
@Singleton
class DefaultHfrFavoriteSmileyRepository @Inject constructor(
    private val hfrClient: HfrFavoriteSmileyClient,
    private val formParser: HfrFavoriteSmileyFormParser,
    private val responseParser: HfrFavoriteSmileyResponseParser,
    private val authRepository: AuthRepository,
    private val diagnostics: DiagnosticsLog,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : HfrFavoriteSmileyRepository {
    private val mutationMutex = Mutex()

    override suspend fun add(token: String): HfrFavoriteSmileyMutationResult = mutate("add", token) { account ->
        val form = readForm()
        when {
            !isCurrentAccount(account) -> HfrFavoriteSmileyMutationResult.AccountChanged
            form.favorites.any { it.token == token } ->
                HfrFavoriteSmileyMutationResult.AlreadyPresent(form.favorites)
            form.favorites.size >= FAVORITE_LIMIT ->
                HfrFavoriteSmileyMutationResult.Full(FAVORITE_LIMIT, form.favorites)
            else -> addToForm(account, form, token)
        }
    }

    private suspend fun addToForm(
        account: AuthState.Authenticated,
        form: HfrFavoriteSmileyForm,
        token: String,
    ): HfrFavoriteSmileyMutationResult {
        if (!isCurrentAccount(account)) return HfrFavoriteSmileyMutationResult.AccountChanged
        return when (val response = responseParser.parse(hfrClient.addFavoriteSmiley(form.hashCheck, token))) {
            HfrFavoriteSmileyResponse.Added -> {
                val confirmed = readForm()
                when {
                    !isCurrentAccount(account) -> HfrFavoriteSmileyMutationResult.AccountChanged
                    confirmed.favorites.none { it.token == token } ->
                        HfrFavoriteSmileyMutationResult.VerificationFailed(confirmed.favorites)
                    else -> HfrFavoriteSmileyMutationResult.Success(confirmed.favorites)
                }
            }
            HfrFavoriteSmileyResponse.AlreadyPresent -> HfrFavoriteSmileyMutationResult.AlreadyPresent(form.favorites)
            is HfrFavoriteSmileyResponse.Full -> HfrFavoriteSmileyMutationResult.Full(response.limit, form.favorites)
            HfrFavoriteSmileyResponse.NotFound -> HfrFavoriteSmileyMutationResult.NotFound
            HfrFavoriteSmileyResponse.NotAuthenticated -> HfrFavoriteSmileyMutationResult.NotAuthenticated
            else -> HfrFavoriteSmileyMutationResult.UnknownResponse
        }
    }

    override suspend fun remove(token: String): HfrFavoriteSmileyMutationResult = mutate("remove", token) { account ->
        val form = readForm()
        val index = form.favorites.indexOfFirst { it.token == token }
        if (!isCurrentAccount(account)) return@mutate HfrFavoriteSmileyMutationResult.AccountChanged
        if (index < 0) return@mutate HfrFavoriteSmileyMutationResult.Success(form.favorites)
        val expected = form.favorites.filterIndexed { row, _ -> row != index }.map { it.token }
        // No suspension / interaction between the final account check and the POST call.
        if (!isCurrentAccount(account)) return@mutate HfrFavoriteSmileyMutationResult.AccountChanged
        val html = hfrClient.removeFavoriteSmileys(form.hashCheck, form.codehex, form.favorites.map { it.token }, index)
        val response = responseParser.parse(html)
        if (response == HfrFavoriteSmileyResponse.NotAuthenticated) {
            return@mutate HfrFavoriteSmileyMutationResult.NotAuthenticated
        }
        // Even an unrecognized acknowledgement cannot replace the mandatory state verification.
        val confirmed = readForm()
        when {
            !isCurrentAccount(account) -> HfrFavoriteSmileyMutationResult.AccountChanged
            confirmed.favorites.map { it.token } != expected ->
                HfrFavoriteSmileyMutationResult.VerificationFailed(confirmed.favorites)
            else -> HfrFavoriteSmileyMutationResult.Success(confirmed.favorites)
        }
    }

    private suspend fun readForm(): HfrFavoriteSmileyForm {
        val html = hfrClient.getFavoriteSmileyForm()
        if (responseParser.parse(html) == HfrFavoriteSmileyResponse.NotAuthenticated) {
            throw SessionExpiredException("[redacted]")
        }
        return formParser.parse(html) ?: throw UnrecognizedFormException()
    }

    private suspend fun isCurrentAccount(account: AuthState.Authenticated): Boolean =
        (authRepository.observeAuthState().first() as? AuthState.Authenticated)?.pseudo?.lowercase() ==
            account.pseudo.lowercase()

    @Suppress("SwallowedException") // Results and diagnostics deliberately carry no raw transport details.
    private suspend fun mutate(
        operation: String,
        token: String,
        action: suspend (AuthState.Authenticated) -> HfrFavoriteSmileyMutationResult,
    ): HfrFavoriteSmileyMutationResult = mutationMutex.withLock {
        val result = try {
            withContext(ioDispatcher) {
                if (token.isEmpty() || (operation == "add" && !isCanonicalPersoToken(token))) {
                    return@withContext HfrFavoriteSmileyMutationResult.InvalidToken
                }
                val account = authRepository.observeAuthState().first() as? AuthState.Authenticated
                    ?: return@withContext HfrFavoriteSmileyMutationResult.NotAuthenticated
                action(account)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: SessionExpiredException) {
            HfrFavoriteSmileyMutationResult.NotAuthenticated
        } catch (_: UnrecognizedFormException) {
            HfrFavoriteSmileyMutationResult.UnknownResponse
        } catch (_: IllegalArgumentException) {
            HfrFavoriteSmileyMutationResult.UnknownResponse
        } catch (_: IOException) {
            HfrFavoriteSmileyMutationResult.TransportFailure
        }
        diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "$operation ${result::class.simpleName}")
        result
    }

    // Match the wiki's broad name grammar (spaces, accents, punctuation, internal colons),
    // without trimming or rewriting the token. Reject brackets and control characters inside.
    private fun isCanonicalPersoToken(token: String): Boolean = PERSO_TOKEN.matches(token) &&
        token.substring(2, token.lastIndex).let { it.isNotBlank() && it == it.trim() }

    private class UnrecognizedFormException : Exception()

    private companion object {
        const val LOG_TAG = "HfrFavoriteSmileys"
        const val FAVORITE_LIMIT = 10 // Server limit captured on 2026-10-29; only used for the local add precheck.
        val PERSO_TOKEN = Regex("""\[:[^\[\]\p{C}\p{Zl}\p{Zp}]+\]""")
    }
}
