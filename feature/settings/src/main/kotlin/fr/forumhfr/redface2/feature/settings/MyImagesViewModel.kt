package fr.forumhfr.redface2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.ApplicationScope
import fr.forumhfr.redface2.core.domain.coroutines.awaitDetached
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.upload.UploadedImageRecord
import fr.forumhfr.redface2.core.model.AuthState
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the « Mes images uploadées » screen (#459 PR3). Observes the upload history scoped
 * to the active HFR pseudo (lowercased, the `userId` the foundation expects) and exposes a deferred
 * delete: [requestDelete] opens a confirmation, [confirmDelete] calls the repository.
 *
 * The history flow is driven by [AuthRepository.observeAuthState] so the list re-scopes when the
 * session changes and clears (→ [MyImagesUiState.Mode.RequiresLogin]) on logout — same shape as
 * `FlagsViewModel` / `MessagesViewModel`. The repository wraps its own I/O on the IO dispatcher
 * (project rule: data sources hop to IO), so the ViewModel only collects / suspends on
 * `viewModelScope` and injects no dispatcher itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MyImagesViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val uploadRepository: UploadRepository,
    // #1144 — process-lifetime scope for [confirmDelete], the one server mutation of this screen.
    // Same shape as the topic-screen deletion: the user confirmed a destructive host call, so a back
    // press must not cut the socket half-way. Reads stay on `viewModelScope`.
    @param:ApplicationScope private val externalScope: CoroutineScope,
) : ViewModel() {

    // The active lowercased pseudo (= upload `userId`) ; null when anonymous. Captured from the
    // auth stream so confirmDelete() can pass the right owner without re-reading the flow.
    private var activeUserId: String? = null

    // Owner pseudo captured WHEN the delete dialog opens, so a session switch between request and
    // confirm cannot retarget the deletion at a different account (Codex review, #459 PR3).
    private var pendingDeletionUserId: String? = null

    private val _state = MutableStateFlow(MyImagesUiState())
    val state: StateFlow<MyImagesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.observeAuthState()
                .distinctUntilChanged()
                .onEach(::onAuthStateChanged)
                .flatMapLatest { authState ->
                    when (authState) {
                        AuthState.Anonymous -> flowOf<List<UploadedImageRecord>?>(null)
                        is AuthState.Authenticated ->
                            uploadRepository.observeUploads(authState.pseudo.lowercase())
                    }
                }
                .collect { records ->
                    _state.update { current ->
                        if (records == null) {
                            current.copy(mode = MyImagesUiState.Mode.RequiresLogin, pendingDeletion = null)
                        } else {
                            current.copy(mode = MyImagesUiState.Mode.Content(records))
                        }
                    }
                }
        }
    }

    private fun onAuthStateChanged(authState: AuthState) {
        val newUserId = when (authState) {
            AuthState.Anonymous -> null
            is AuthState.Authenticated -> authState.pseudo.lowercase()
        }
        if (newUserId != activeUserId) {
            // Identity changed → a delete dialog left open belonged to the previous account ; drop it.
            pendingDeletionUserId = null
            _state.update { if (it.pendingDeletion != null) it.copy(pendingDeletion = null) else it }
        }
        activeUserId = newUserId
    }

    /** Deferred delete step 1 — open the confirmation dialog for [record], capturing its owner. */
    fun requestDelete(record: UploadedImageRecord) {
        val owner = activeUserId ?: return
        pendingDeletionUserId = owner
        _state.update { it.copy(pendingDeletion = record) }
    }

    /** Deferred delete cancelled — close the dialog, delete nothing. */
    fun cancelDelete() {
        pendingDeletionUserId = null
        _state.update { it.copy(pendingDeletion = null) }
    }

    /**
     * Deferred delete step 2 — call the repository for the pending record. The local trace is
     * always evicted by the repository (the list reactively drops the row) ; the returned Boolean
     * only tells whether the host CONFIRMED the deletion, surfaced as a one-shot snackbar message.
     */
    fun confirmDelete() {
        val record = _state.value.pendingDeletion ?: return
        val userId = pendingDeletionUserId ?: return
        pendingDeletionUserId = null
        _state.update { it.copy(pendingDeletion = null) }
        viewModelScope.launch {
            // #1144 — detached: leaving « Mes images » right after confirming must not abort the
            // host DELETE (it would leave the picture on Imgur/Diberie with its local trace already
            // evicted — an orphan nothing can reach). The snackbar below stays on this coroutine.
            val confirmed = runCatching {
                externalScope.awaitDetached { uploadRepository.delete(record, userId) }
            }.getOrElse { raised ->
                // #1144 — cancellation is control flow, not a « best-effort » outcome: re-throw it
                // instead of folding it to `false`, otherwise a destroyed ViewModel would still run
                // the state update below and mislabel a detached, still-running delete.
                if (raised is CancellationException) throw raised
                false
            }
            _state.update {
                it.copy(
                    deletionMessage = if (confirmed) {
                        MyImagesUiState.DeletionMessage.Confirmed
                    } else {
                        MyImagesUiState.DeletionMessage.BestEffort
                    },
                )
            }
        }
    }

    /** Acknowledge the one-shot deletion snackbar so a recomposition does not replay it. */
    fun consumeDeletionMessage() {
        _state.update { it.copy(deletionMessage = null) }
    }

    /** MVI dispatcher mirroring the other screens — maps an [MyImagesIntent] to the handler. */
    fun submit(intent: MyImagesIntent) {
        when (intent) {
            is MyImagesIntent.RequestDelete -> requestDelete(intent.record)
            MyImagesIntent.ConfirmDelete -> confirmDelete()
            MyImagesIntent.CancelDelete -> cancelDelete()
            MyImagesIntent.MessageShown -> consumeDeletionMessage()
        }
    }
}
