package fr.forumhfr.redface2.core.data.mpstorage

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageReadPositionStore
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageReadPositionSeeder
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageSeedOutcome
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageDocument
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Default [MpStorageReadPositionSeeder] (#6, ADR-014): fetches the storage document and seeds the
 * DT reading positions into the local [PrivateMessageReadPositionStore] (ADR-013 stage 1).
 *
 * Seeding is LOCAL-PRIORITY: a position is written only when the conversation has no local row yet,
 * or when the stored DT page is FURTHER than the local one — MPStorage never rewinds a page the
 * user already advanced past on this device. Read-only on the storage side (no write-back).
 */
@Singleton
class DefaultMpStorageReadPositionSeeder @Inject constructor(
    private val mpStorageRepository: MpStorageRepository,
    private val readPositionStore: PrivateMessageReadPositionStore,
    private val authRepository: AuthRepository,
    private val diagnostics: DiagnosticsLog,
) : MpStorageReadPositionSeeder {

    override suspend fun seed(): MpStorageSeedOutcome {
        val owner = (authRepository.observeAuthState().first() as? AuthState.Authenticated)?.pseudo
            ?: return MpStorageSeedOutcome.NotAuthenticated

        return when (val result = mpStorageRepository.fetchStorage()) {
            MpStorageResult.NotFound -> MpStorageSeedOutcome.NoStorage
            MpStorageResult.Unreadable -> MpStorageSeedOutcome.Unreadable
            is MpStorageResult.Found -> seedPositions(owner, result.document)
        }
    }

    private suspend fun seedPositions(owner: String, document: MpStorageDocument): MpStorageSeedOutcome {
        var applied = 0
        for (entry in document.mpFlags) {
            val existing = readPositionStore.readPage(owner, entry.threadId)
            if (existing == null || entry.page > existing) {
                readPositionStore.savePage(owner, entry.threadId, entry.page)
                applied++
            }
        }
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "seeded $applied/${document.mpFlags.size} DT reading positions",
        )
        return MpStorageSeedOutcome.Seeded(total = document.mpFlags.size, applied = applied)
    }

    private companion object {
        private const val LOG_TAG = "MpStorageSeed"
    }
}
