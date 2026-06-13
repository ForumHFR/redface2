package fr.forumhfr.redface2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.upload.UploadedImageRecord
import fr.forumhfr.redface2.core.model.AuthState
import javax.inject.Inject
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
 * session changes and clears (→ [MesImagesUiState.Mode.RequiresLogin]) on logout — same shape as
 * `FlagsViewModel` / `MessagesViewModel`. The repository wraps its own I/O on the IO dispatcher
 * (project rule: data sources hop to IO), so the ViewModel only collects / suspends on
 * `viewModelScope` and injects no dispatcher itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MesImagesViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val uploadRepository: UploadRepository,
) : ViewModel() {

    // The active lowercased pseudo (= upload `userId`) ; null when anonymous. Captured from the
    // auth stream so confirmDelete() can pass the right owner without re-reading the flow.
    private var activeUserId: String? = null

    private val _state = MutableStateFlow(MesImagesUiState())
    val state: StateFlow<MesImagesUiState> = _state.asStateFlow()

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
                            current.copy(mode = MesImagesUiState.Mode.RequiresLogin, pendingDeletion = null)
                        } else {
                            current.copy(mode = MesImagesUiState.Mode.Content(records))
                        }
                    }
                }
        }
    }

    private fun onAuthStateChanged(authState: AuthState) {
        activeUserId = when (authState) {
            AuthState.Anonymous -> null
            is AuthState.Authenticated -> authState.pseudo.lowercase()
        }
    }

    /** Deferred delete step 1 — open the confirmation dialog for [record]. */
    fun requestDelete(record: UploadedImageRecord) {
        _state.update { it.copy(pendingDeletion = record) }
    }

    /** Deferred delete cancelled — close the dialog, delete nothing. */
    fun cancelDelete() {
        _state.update { it.copy(pendingDeletion = null) }
    }

    /**
     * Deferred delete step 2 — call the repository for the pending record. The local trace is
     * always evicted by the repository (the list reactively drops the row) ; the returned Boolean
     * only tells whether the host CONFIRMED the deletion, surfaced as a one-shot snackbar message.
     */
    fun confirmDelete() {
        val record = _state.value.pendingDeletion ?: return
        val userId = activeUserId ?: return
        _state.update { it.copy(pendingDeletion = null) }
        viewModelScope.launch {
            val confirmed = runCatching { uploadRepository.delete(record, userId) }.getOrDefault(false)
            _state.update {
                it.copy(
                    deletionMessage = if (confirmed) {
                        MesImagesUiState.DeletionMessage.Confirmed
                    } else {
                        MesImagesUiState.DeletionMessage.BestEffort
                    },
                )
            }
        }
    }

    /** Acknowledge the one-shot deletion snackbar so a recomposition does not replay it. */
    fun consumeDeletionMessage() {
        _state.update { it.copy(deletionMessage = null) }
    }

    /** MVI dispatcher mirroring the other screens — maps an [MesImagesIntent] to the handler. */
    fun submit(intent: MesImagesIntent) {
        when (intent) {
            is MesImagesIntent.RequestDelete -> requestDelete(intent.record)
            MesImagesIntent.ConfirmDelete -> confirmDelete()
            MesImagesIntent.CancelDelete -> cancelDelete()
            MesImagesIntent.MessageShown -> consumeDeletionMessage()
        }
    }
}
