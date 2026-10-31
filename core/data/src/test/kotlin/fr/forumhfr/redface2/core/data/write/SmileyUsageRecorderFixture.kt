package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageMutationResult
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.SmileyUsageEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/** Real recorder with in-memory collaborators, shared by the six write-site contract tests. */
internal class SmileyUsageRecorderFixture(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
) {
    val authState = MutableStateFlow<AuthState>(AuthState.Authenticated("ALIce"))
    val calls = mutableListOf<Call>()
    val diagnostics = DiagnosticsLog()
    var storageError: Exception? = null
    var authError: Exception? = null
    var storageResult: SmileyUsageMutationResult = SmileyUsageMutationResult.Updated
    var beforeRecord: suspend () -> Unit = {}

    private val auth = object : AuthRepository {
        override fun observeAuthState(): Flow<AuthState> = flow {
            authError?.let { throw it }
            emit(authState.value)
        }
        override suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated> =
            error("Unused in usage tests")
        override suspend fun logout() { authState.value = AuthState.Anonymous }
    }

    private val store = object : SmileyUsageRepository {
        override fun observeTop(owner: String, limit: Int): Flow<List<SmileyUsageEntry>> = flowOf(emptyList())
        override suspend fun record(owner: String, usages: Map<String, Int>): SmileyUsageMutationResult {
            beforeRecord()
            calls += Call(owner, usages.toMap())
            storageError?.let { throw it }
            return storageResult
        }
        override suspend fun clear(owner: String): SmileyUsageMutationResult = error("Unused in usage tests")
    }

    val recorder = SuccessfulSmileyUsageRecorder(auth, store, diagnostics, scope)

    fun close() = scope.cancel()

    data class Call(val owner: String, val usages: Map<String, Int>)
}
