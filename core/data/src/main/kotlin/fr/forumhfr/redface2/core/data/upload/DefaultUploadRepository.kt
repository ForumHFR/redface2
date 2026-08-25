package fr.forumhfr.redface2.core.data.upload

import fr.forumhfr.redface2.core.database.dao.UploadedImageDao
import fr.forumhfr.redface2.core.database.entities.UploadedImageEntity
import fr.forumhfr.redface2.core.domain.coroutines.ApplicationScope
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.coroutines.awaitDetached
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.UploadProvider
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.upload.UploadedImage
import fr.forumhfr.redface2.core.domain.upload.UploadedImageRecord
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Default [UploadRepository] (#459): resolves the host from the current preference, uploads through
 * the matching [UploadProvider], persists the trace, exposes the history, and deletes.
 *
 * The provider is resolved per call from [UserPreferencesRepository.observeUploadProvider], so a
 * change in Settings takes effect on the next upload. [userId] is lowercased here (defensive — the
 * DAO byte-matches), mirroring the [fr.forumhfr.redface2.core.database.dao.FlagDao] convention.
 *
 * The providers already wrap their network work in `withContext(ioDispatcher)`; the DAO upsert /
 * delete are wrapped here as well (project rule: data sources hop to IO).
 *
 * @param clock injected so the persisted `uploadedAt` is deterministic in tests (same seam as
 * [fr.forumhfr.redface2.core.data.topic.TopicRepositoryImpl]).
 */
@Singleton
internal class DefaultUploadRepository @Inject constructor(
    private val providers: Map<UploadProviderId, @JvmSuppressWildcards UploadProvider>,
    private val uploadedImageDao: UploadedImageDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    // #1144 — see [uploadWithCurrentProvider]: the bookkeeping that follows a COMPLETED host upload
    // must not be lost to the caller's cancellation.
    @param:ApplicationScope private val externalScope: CoroutineScope,
) : UploadRepository {

    override suspend fun uploadWithCurrentProvider(image: ImageUpload, userId: String): UploadedImage {
        val providerId = userPreferencesRepository.observeUploadProvider().first()
        val provider = providers[providerId]
            ?: error("No UploadProvider registered for $providerId")
        val result = provider.upload(image)
        // #1144 — the upload ITSELF stays cancellable on purpose (#459 cancels `uploadJob` in the
        // editors' `onCleared`: an image picked for an abandoned draft should not keep uploading).
        // But once the host has accepted the picture, dropping this row would strand it: « Mes
        // images » could neither list nor delete it ever again. So the trace — and only the trace —
        // is written on the process-lifetime scope, closing the window between the POST returning
        // and the upsert landing.
        externalScope.awaitDetached {
            withContext(ioDispatcher) {
                uploadedImageDao.upsert(result.toEntity(userId.lowercase(), Instant.now(clock)))
            }
        }
        return result
    }

    override fun observeUploads(userId: String): Flow<List<UploadedImageRecord>> =
        uploadedImageDao.observeForUser(userId.lowercase())
            .map { rows -> rows.map(UploadedImageEntity::toRecord) }

    override suspend fun delete(record: UploadedImageRecord, userId: String): Boolean {
        val owner = userId.lowercase()
        val confirmed = record.deleteHandle?.let { handle ->
            providers[record.provider]?.delete(handle) ?: false
        } ?: false
        // Evict the local trace regardless of the host outcome (a dead row helps no one).
        withContext(ioDispatcher) {
            uploadedImageDao.delete(owner, record.provider.name, record.picId)
        }
        return confirmed
    }
}

private fun UploadedImage.toEntity(userId: String, uploadedAt: Instant): UploadedImageEntity =
    UploadedImageEntity(
        userId = userId,
        provider = provider.name,
        // picId doubles as the deletion handle; fall back to the imageUrl when no handle exists so
        // the row still has a stable, unique PK for the « Mes images » list.
        picId = deleteHandle ?: imageUrl,
        imageUrl = imageUrl,
        thumbnailUrl = thumbnailUrl,
        deleteHandle = deleteHandle,
        uploadedAt = uploadedAt,
        expiresAt = expiresAt,
    )

private fun UploadedImageEntity.toRecord(): UploadedImageRecord {
    // Defensive read (#474): an unknown stored provider name (downgrade, manual DB edit, or a future
    // provider written by a newer build) must NOT crash valueOf nor be dropped — the trace stays
    // listable so the user still sees the image. The enum has no "unknown" member, so we degrade the
    // provider to the safe DIBERIE default AND force `deleteHandle = null`: with no known provider we
    // cannot route a deletion correctly, so deletion is disabled (canDelete=false) rather than
    // mis-sent to the wrong host. A recognised provider keeps its real handle untouched.
    val knownProvider = runCatching { UploadProviderId.valueOf(provider) }.getOrNull()
    return UploadedImageRecord(
        provider = knownProvider ?: UploadProviderId.DIBERIE,
        picId = picId,
        imageUrl = imageUrl,
        thumbnailUrl = thumbnailUrl,
        deleteHandle = if (knownProvider != null) deleteHandle else null,
        uploadedAt = uploadedAt,
        expiresAt = expiresAt,
    )
}
